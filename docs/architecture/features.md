---
title: 功能架构
last_updated: 2026-08-06
version: 1.0.17
---

# 功能架构

本部分详细介绍了 Luban-RDS 的功能模块设计，包括各个功能的实现细节、关键特性和使用方法。

## 1. 核心存储

### 1.1 MemoryStore 接口

**核心功能**：定义了所有数据类型的操作方法

**新增方法（v1.0.16 起逐步下推）**：
- `zgetAllWithScores(int database, String key)` - 获取 ZSet 所有成员及其分数，用于持久化
- `int getLruSampleSize()` / `void setLruSampleSize(int)` - LRU 采样窗口大小（v1.0.16 起，补到接口消除 `CommonCommandHandler` 向下转型）
- `int getSoftLimitPercent()` / `void setSoftLimitPercent(int)` - 软上限百分比（v1.0.16 起，同样下推至接口）

> **v1.0.17 设计要点**：所有需要运行时切换/动态调整的字段必须出现在 `MemoryStore` 接口中，禁止在 `CommonCommandHandler` / `CONFIG SET` 路径中再次强转实现类。

### 1.2 DefaultMemoryStore 实现

**核心功能**：实现了 MemoryStore 接口，使用 Java 集合存储数据

**并发安全特性**：
- **分段锁机制**：使用 1024 个分段锁替代 String.intern()，避免内存泄漏
- **原子性批量操作**：MSET 等批量操作使用同步块保证原子性
- **竞态条件处理**：过期键检查使用双重检查锁定机制

**过期键清理策略**：
- **惰性删除**：访问键时检查是否过期，过期则删除
- **主动清理**：后台定时任务（每 100ms）扫描并清理过期键
- **清理限制**：每次最多清理 100 个过期键，避免阻塞主线程

**资源管理**：
- `close()` 方法：关闭内存存储，释放后台线程资源

### 1.3 HybridMemoryStore（v1.0.17 新增，混合内存引擎）

**核心功能**：在同一实例中按数据类型**自动路由**到最合适的存储引擎——String 进堆外、Hash/List/ZSet/Stream 进堆上；可显著降低 GC 压力而不破坏结构体的零拷贝访问语义。

**适用场景**：
- String 数据占比高（典型缓存场景），希望降低 GC 频率
- 不想放弃结构体类型（Caffeine 已移除后改用 hash 缓存）的内存局部性
- 希望运行时通过 `CONFIG SET memory-store-kind` 切换引擎

**引擎组成**：

| 子引擎 | 负责类型 | 存储介质 | 适用原因 |
|--------|----------|----------|----------|
| `OffHeapStringEngine` | String | 堆外 `ByteBuffer`（池化） | String 体积小、序列化后无引用，高频 GC 痛点 |
| `OnHeapStructEngine` | Hash / List / ZSet / Stream | 堆上（Caffeine 缓存序列化结构体） | 结构体访问零拷贝，二期保留 |

**路由策略**：
- `SET` / `GET` / `APPEND` / `INCR` / `STRLEN` / `GETRANGE` / `SETRANGE` → `OffHeapStringEngine`
- `HSET` / `HGET` / `LPUSH` / `RPUSH` / `SADD` / `ZADD` / `XADD` → `OnHeapStructEngine`

**关键约束**：
- String 走堆外后，客户端需按 RESP 严格按字节读取（RDB 加载时按 `0xFD/0xFC` 长度前缀解析）
- hybrid 模式**不破坏** Redis 协议语义：所有响应经 `Encoder` 统一序列化后再返回
- mesh 模式可叠加 hybrid 模式（leader 写仍走 Raft log，落盘语义不变）

### 1.4 引擎选择（v1.0.17 起可通过配置切换）

```ini
# 默认模式（保持历史行为，所有数据走堆上）
memory-store-kind=default

# 混合模式（String 走堆外，结构体仍堆上）
memory-store-kind=hybrid
```

CLI 等价：`--memory-store-kind default|hybrid`。

**Spring Boot 配置前缀**：`luban.rds.server.memory-store-kind`。

**CONFIG SET 实时切换**：

```bash
# 运行时从 default 切到 hybrid（无需重启）
CONFIG SET memory-store-kind hybrid
# 立即生效：后续 SET 走堆外
```

### 1.5 HybridMemoryStore 实现要点

**接口解耦（S1 阶段已完成）**：
- `MemoryStore` 接口补齐 `LruSampleSize` / `SoftLimitPercent` 等方法
- `CommonCommandHandler` 不再对实现类做 `(DefaultMemoryStore) store` 等向下转型
- 新增 `HybridMemoryStore implements MemoryStore`，与 `DefaultMemoryStore` 互换零回归

**测试与冒烟覆盖**：
- 单元测试 26 项全绿
- Redisson 集群感知客户端 12 场景冒烟全绿（`e814f37` `test(cluster): 添加 hybrid 模式 Redisson 真实负载冒烟测试`）
- 堆外增减对称：扩/缩容/释放路径闭环无泄漏
- Rebalance 闭环：堆外内存重平衡正确完成
- 读写路由一致性：客户端路由表与 Leader/MOVED 一致

## 2. 命令处理

### 2.1 CommandHandler 接口

**核心功能**：定义了命令处理的基本方法

### 2.2 命令处理器实现

处理各种类型的 Redis 命令：
- **StringCommandHandler** - 字符串命令处理
- **HashCommandHandler** - 哈希命令处理
- **ListCommandHandler** - 列表命令处理
- **SetCommandHandler** - 集合命令处理
- **ZSetCommandHandler** - 有序集合命令处理
- **StreamCommandHandler** - 流命令处理
- **StreamGroupCommandHandler** - Stream 消费者组命令处理（XREADGROUP、XACK、XPENDING、XCLAIM 等）

### 2.3 命令执行流程

1. 命令解析
2. 命令分发
3. 参数验证
4. 命令执行
5. 结果处理

## 3. Stream 数据类型

### 3.1 Stream 概述

**核心功能**：Stream 是 Redis 5.0 引入的数据结构，类似于消息队列，支持消费者组功能。

### 3.2 StreamCommandHandler

**核心功能**：处理 Stream 相关命令

**支持的命令**：
- `XADD` - 添加消息到流
- `XLEN` - 获取流的长度
- `XRANGE` - 获取范围内的消息
- `XREVRANGE` - 逆序获取范围内的消息
- `XDEL` - 删除消息
- `XTRIM` - 裁剪流
- `XREAD` - 读取消息（支持阻塞）
- `XINFO` - 获取流信息
- `XGROUP` - 消费者组管理
- `XREADGROUP` - 消费者组方式读取消息
- `XACK` - 确认消息已处理
- `XPENDING` - 获取待处理消息
- `XCLAIM` - 转移消息所有权
- `XAUTOCLAIM` - 自动转移消息所有权

### 3.3 Stream 数据结构

**StreamId**：消息唯一标识符
- 格式：`<millisecondsTime>-<sequenceNumber>`
- 自动生成：使用 `*` 作为 ID 参数
- 特殊 ID：`-`（最小）、`+`（最大）、`$`（最后一条）、`>`（未消费）

**StreamEntry**：消息实体
- ID：消息唯一标识
- Fields：键值对形式的字段数据

**Stream**：流数据结构
- 基于红黑树实现，支持高效的范围查询
- 支持自动 ID 生成
- 支持 MAXLEN/MINID 裁剪策略

### 3.4 消费者组

**核心功能**：支持多消费者协同处理消息

**特性**：
- 消息确认机制（XACK）
- 待处理消息查询（XPENDING）
- 消息所有权转移（XCLAIM/XAUTOCLAIM）
- 消费者组创建与管理（XGROUP）

## 4. Lua 脚本支持

### 4.1 LuaCommandHandler

**核心功能**：处理 Lua 脚本相关命令

### 4.2 Lua 执行环境

**核心功能**：提供 Lua 脚本执行的环境

### 4.3 沙箱模式

**核心功能**：提供安全的 Lua 执行环境

### 4.4 Redis API 支持

- `redis.call()` - 执行 Redis 命令，错误会向上传播
- `redis.pcall()` - 执行 Redis 命令，错误会被捕获
- `redis.error_reply()` - 返回错误响应
- `redis.status_reply()` - 返回状态响应
- `redis.sha1hex()` - 计算字符串的 SHA1 哈希值

## 5. 发布订阅

### 5.1 PubSubManager

**核心功能**：管理频道和订阅关系

### 5.2 发布订阅流程

1. 订阅频道
2. 添加订阅
3. 进入模式
4. 发布消息
5. 消息广播
6. 取消订阅
7. 移除订阅
8. 退出模式

### 5.3 消息格式

- 订阅确认：`["subscribe", "channel", count]`
- 取消订阅确认：`["unsubscribe", "channel", count]`
- 消息推送：`["message", "channel", "message"]`
- 模式订阅确认：`["psubscribe", "pattern", count]`
- 模式消息推送：`["pmessage", "pattern", "channel", "message"]`
- 流订阅确认：`["ssubscribe", "channel", count]`
- 流消息推送：`["smessage", "channel", "message"]`

## 6. 持久化

### 6.1 RDB 持久化

**核心功能**：将内存数据以二进制格式保存到磁盘

**技术实现**：使用 Kryo 序列化框架进行高效存储

**数据完整性**：
- 完整保存 ZSet 成员的分数值
- 支持所有五种数据类型的完整序列化

### 6.2 AOF 持久化

**核心功能**：将写命令追加到 AOF 文件

**命令解析增强**：
- 支持 20+ 种命令类型的完整解析
- 字符串命令：SET、SETEX、PSETEX、SETNX、APPEND、INCR、DECR、INCRBY、DECRBY
- 哈希命令：HSET、HMSET、HSETNX、HINCRBY、HINCRBYFLOAT、HDEL
- 列表命令：LPUSH、RPUSH、LPOP、RPOP、LSET、LREM、LTRIM
- 集合命令：SADD、SREM、SPOP
- 有序集合命令：ZADD、ZREM、ZINCRBY
- 通用命令：DEL、EXPIRE、PEXPIRE、EXPIREAT、PEXPIREAT、SELECT

### 6.3 数据恢复

支持从 RDB 和 AOF 文件恢复数据

## 7. 网络服务

### 7.1 NettyRedisServer

**核心功能**：基于 Netty 4.2 的高性能 Redis 服务器实现

### 7.2 RedisServerHandler

**核心功能**：处理网络事件和请求

## 8. 客户端

### 8.1 RedisClient 接口

**核心功能**：定义了 Redis 客户端的基本方法

### 8.2 NettyRedisClient 实现

**核心功能**：基于 Netty 的 Redis 客户端实现

## 9. Spring Boot 集成

### 9.1 LubanRdsAutoConfiguration

**核心功能**：自动配置 Luban-RDS 服务器

### 9.2 使用示例

提供在 Spring Boot 应用中使用 Luban-RDS 的示例

## 10. 监控和统计

### 10.1 INFO 命令

**核心功能**：返回服务器信息和统计数据

### 10.2 脚本统计

**核心功能**：统计 Lua 脚本执行情况

### 10.3 性能监控

**核心功能**：监控服务器性能指标

### 10.4 内存诊断

**核心功能**：提供内存使用详情和健康诊断（MEMORY 命令族）

## 11. 安全

### 11.1 认证机制

**核心功能**：提供密码认证

### 11.2 命令限制

**核心功能**：限制危险命令的使用

### 11.3 网络限制

**核心功能**：限制网络访问

## 12. 扩展性

### 12.1 命令扩展

**核心功能**：支持添加自定义命令

### 12.2 存储扩展

**核心功能**：支持自定义存储后端

### 12.3 插件系统

**核心功能**：支持通过插件扩展功能

## 13. 性能优化

### 13.1 内存优化

**核心功能**：优化内存使用

### 13.2 网络优化

**核心功能**：优化网络处理

### 13.3 执行优化

**核心功能**：优化命令执行

### 13.4 持久化优化

**核心功能**：优化持久化操作

## 14. 实时监控

### 14.1 MONITOR 命令

**核心功能**：提供低延迟、高性能的实时命令监控能力。

**架构设计**：
- **MPSC Ring Buffer**：采用无锁环形缓冲区（65536 槽位）处理监控事件，消除锁竞争。
- **Zero-Allocation**：预分配事件对象与 StringBuilder 内存池，确保持续监控下无额外 GC 压力。
- **异步 Worker**：独立的 Worker 线程负责日志格式化与网络广播，主线程开销仅为纳秒级。

**特性**：
- **历史快照**：新连接的监控客户端可立即获取最近 1MB 的命令历史。
- **服务端过滤**：支持按数据库 ID 或命令模式在服务端过滤，减少网络传输。

### 14.2 SLOWLOG 命令

**核心功能**：记录慢查询日志，帮助识别性能瓶颈

**特性**：
- 记录执行时间超过阈值的命令
- 支持获取、清空慢查询日志
- 记录命令参数、执行时间、客户端地址等信息

## 15. 分布式追踪

### 15.1 TraceContext

**核心功能**：管理请求链路的 TraceId，支持分布式追踪

**特性**：
- **全局唯一 TraceId 生成**：格式为 `{时间戳}-{机器标识}-{进程ID}-{序列号}`
- **自动注入日志**：通过 SLF4J MDC 自动在所有日志中添加 traceId 字段
- **多线程传递**：提供 TraceableRunnable、TraceableCallable、TraceableExecutor 支持异步场景

### 15.2 TraceId 格式

```
{时间戳(毫秒)}-{机器标识(8位)}-{进程ID}-{序列号(6位)}
示例：1704067200000-a1b2c3d4-1234-000001
```

### 15.3 日志格式

所有日志自动包含 traceId 字段：
```
14:30:45.123 [nioEventLoopGroup-3-1] [traceId:1704067200000-a1b2c3d4-1234-000001] DEBUG c.j.l.r.server.RedisServerHandler - Processing command: SET
```

### 15.4 使用方式

**自动模式**：请求入口自动生成，处理完成自动清理

**手动模式**：
```java
// 获取当前 TraceId
String traceId = TraceContext.getTraceId();

// 设置自定义 TraceId
TraceContext.setTraceId("custom-trace-id");

// 生成新 TraceId
String newTraceId = TraceContext.generateTraceId();
```

**异步场景**：
```java
// 包装 Runnable
executor.submit(TraceableRunnable.wrap(() -> {
    // TraceId 自动传递到子线程
}));

// 包装 Callable
Future<String> future = executor.submit(TraceableCallable.wrap(() -> {
    return TraceContext.getTraceId();
}));

// 包装 Executor
Executor traceableExecutor = TraceableExecutor.wrap(rawExecutor);
```

## 16. 事务支持

### 16.1 事务命令

**核心功能**：提供完整的事务支持

**命令**：
- `MULTI` - 开始事务
- `EXEC` - 执行事务
- `DISCARD` - 取消事务
- `WATCH` - 监视键
- `UNWATCH` - 取消监视

### 16.2 事务行为

- 使用 WATCH 监视的键在 EXEC 前如果发生变更，EXEC 返回 Null Array
- 事务入队阶段若存在参数错误，EXEC 返回 EXECABORT
- 事务内 SELECT 更新客户端数据库状态

## 17. Redis Cluster 集群

### 17.1 集群概述

**核心功能**：完整实现 Redis Cluster 协议，支持分布式部署和数据分片。

### 17.2 核心组件

| 组件 | 功能描述 |
|------|----------|
| ClusterNode | 节点数据模型，包含 ID、地址、状态、槽位分配 |
| SlotManager | 槽位管理，支持 16384 槽位分配和查询 |
| ClusterConfig | 集群配置管理，节点列表和槽位分配表；支持脏标记（dirty flag）追踪拓扑变更 |
| ClusterConfigPersister | 集群配置持久化器，将 `ClusterConfig` 同步到 `nodes.conf` 并兼容旧版含 `fail` 标志的格式 |
| GossipProtocol | Gossip 协议实现，心跳检测和故障发现；节点变更时主动触发配置持久化 |
| ClusterBusServer | 集群总线服务器，端口 = 服务端口 + 10000 |
| ClusterCommandHandler | CLUSTER 命令处理器；处理 MEET/FORGET/ADDSLOTS 等命令时通知拓扑变更 |
| FailoverManager | 故障转移管理器，编排主节点选举与切换流程 |
| FailureDetector | 故障检测器，基于 Gossip 消息标记 PFAIL/FAIL 状态 |
| ClusterStateManager | 集群状态管理器，维护节点表与拓扑状态 |
| ClusterStats | 集群统计信息聚合（节点数、槽位状态、Gossip 计数等） |
| bus/ | 集群总线通信子包（`ClusterBusClient`、`ClusterBusCodec`、`ClusterBusHandler`、`ClusterBusServer`） |
| migration/ | 槽位迁移子包（`MigrateCommandHandler`、`SlotMigrationManager`、`MigrationState`、`ExportResult`、`ImportState`） |
| lifecycle/ | 生命周期子包（`ReplicationLifecycleListener`、`NoOpReplicationLifecycleListener`） |
| slot/ | 槽位管理子包（`SlotManager`、`DefaultSlotManager`、`SlotUtils`） |
| node/ | 节点子包（`ClusterNode`、`ClusterNodeState`、`ClusterLink`） |

### 17.3 槽位管理

**槽位计算**：
- 使用 CRC16 算法计算键的槽位
- 槽位范围：0-16383
- 支持 Hash Tag 语法：`{tag}key`

**性能优化**：
- 使用 BitSet 存储槽位（16384 bits = 2KB）
- AtomicInteger 缓存已分配槽位数量（O(1) 查询）
- ReadWriteLock 保证线程安全

### 17.4 集群命令

| 命令 | 功能描述 |
|------|----------|
| CLUSTER INFO | 集群状态信息（含 `cluster_enabled`） |
| CLUSTER NODES | 节点列表和槽位分配（裸 `\n` 行尾，兼容 Redisson） |
| CLUSTER MEET ip port | 添加节点到集群（修复装配缺陷与临时 ID 解析） |
| CLUSTER FORGET nodeid | 从集群移除节点 |
| CLUSTER ADDSLOTS slot [...] | 分配槽位 |
| CLUSTER SETSLOT slot NODE nodeid | 设置槽位归属 |
| CLUSTER KEYSLOT key | 计算键的槽位 |
| CLUSTER REPLICATE nodeid | 配置为从节点 |
| CLUSTER FAILOVER | 手动故障转移 |
| CLUSTER SLOTS | 返回当前槽位分布数组（完整实现） |

### 17.5 重定向机制

**MOVED 重定向**：
- 槽位属于其他节点时返回
- 格式：`-MOVED slot ip:port`
- 客户端应更新槽位缓存

**ASK 重定向**：
- 槽位迁移过程中返回
- 格式：`-ASK slot ip:port`
- 客户端需发送 ASKING 命令后重试

### 17.6 Gossip 协议

**心跳机制**：
- 定期发送 PING 消息（cluster-node-timeout / 2）
- 响应 PONG 消息
- 携带随机节点信息用于传播

**故障检测**：
1. 节点超时 → 标记为 PFAIL（可能下线）
2. 多数主节点确认 → 标记为 FAIL（下线）
3. 广播 FAIL 消息

### 17.7 客户端兼容性

**已测试客户端**：
- Jedis Cluster（完整兼容）
- Lettuce Cluster（完整兼容）
- Redisson Cluster（完整兼容，`CLUSTER NODES` 行尾修复后）
- `redis-cli --cluster create`（完整兼容，不再卡在 `Waiting for the cluster to join`）

### 17.8 集群配置持久化与节点状态恢复

集群配置持久化与节点状态恢复机制对标 Redis 7，节点重启后无需重新执行 `MEET`/`ADDSLOTS` 等初始化操作。

**核心机制**：

| 机制 | 描述 |
|------|------|
| `nodes.conf` 持久化 | `ClusterConfigPersister` 在拓扑变更时将集群配置同步到 `cluster-config-file` 指定路径 |
| 脏标记（dirty flag） | `ClusterConfig.markDirty` / `isDirty` / `clearDirty` 仅在发生实际变更时触发落盘，避免频繁 I/O |
| 自动持久化触发 | `ClusterCommandHandler` 处理命令时主动标记 dirty；`GossipProtocol` 在节点变更时也触发持久化 |
| 周期性检查 | 类 Redis 7 `clusterSaveConfigIfNeeded` 的周期任务，兜底刷新未及时落盘的脏配置 |
| 状态恢复加载 | 启动时从 `nodes.conf` 加载节点列表、槽位分配与 config epoch，复用已有节点 ID |
| 槽位表重建 | 从恢复的 `ClusterConfig` 重建 `SlotManager` 槽位表，重启即可正常服务请求 |
| 启动期连接 | 启动时主动 `MEET` 已知节点，避免全集群重启后节点成孤岛 |
| 兼容旧版格式 | 解析时忽略 `fail` 标志，对历史版本生成的 `nodes.conf` 完全兼容 |

**持久化触发点**：

| 来源 | 场景 |
|------|------|
| `CLUSTER MEET` | 加入新节点 |
| `CLUSTER FORGET` | 移除节点 |
| `CLUSTER ADDSLOTS` / `DELSLOTS` / `SETSLOT` | 槽位分配变更 |
| `CLUSTER REPLICATE` | 主从关系变更 |
| Gossip 协议 | 节点发现 / 失效传播 |
| 周期任务 | 兜底刷新未及时落盘的 dirty 配置 |

**`nodes.conf` 示例**：

```
vars currentEpoch 6 currentMyEpoch 1
vars myId a1b2c3d4e5f6...
slots 0-5460
slots 5461-10922
slots 10923-16383
a1b2c3d4e5f6 192.168.8.161:9736@19736 master - 0 1234567890 1 connected
f6e5d4c3b2a1 192.168.8.161:9739@19739 master - 0 1234567890 2 connected
```

> 运行时状态 `fail` / `fail?` 标志不会被持久化，避免重启后误判节点状态。

**运维建议**：
- 升级后无需手动干预，旧版 `nodes.conf` 会被自动迁移
- 全集群同时重启时建议保持 `cluster-node-timeout` 内的时钟同步，避免节点孤立超时

### 17.9 集群一键搭建 CLI

`luban-rds-client` 模块自带 `RedisCliMain`，对齐 `redis-cli --cluster create` 子集，可在不引入外部编排脚本的情况下远程搭建集群。

**关键组件**：

| 组件 | 功能 |
|------|------|
| `RedisCliMain` | CLI 入口，支持 `--cluster create ... --cluster-replicas N` 参数解析 |
| `ClusterSetupCommand` | 编排 MEET → 主从划分 → 槽位均分 → 状态校验；提供 `createCluster(...)` 静态方法供程序化调用 |
| `NodeAddress` | 解析 `host:port` 地址格式 |
| `ReplySupport` | RESP 协议回复判定工具 |
| `ClusterSetupException` | 统一异常类型 |

**关键修复**：

| 修复 | 影响 |
|------|------|
| Gossip 发现新节点后主动建连 / MEET | 解决 `--cluster create` 拓扑不收敛 |
| `GossipTask` 不再跳过 HANDSHAKE 节点 | 握手流程正常推进 |
| Gossip 消息携带槽位所有权 | `cluster_state` 正确转为 `ok` |
| `CLUSTER NODES` 行尾改裸 `\n` | Redisson `ClusterNodesDecoder` 不再抛 `NumberFormatException` |
| `ClusterSetupCommand` 静默模式 | 进度输出可控，便于批量部署 |

## 18. 哨兵模式

### 18.1 哨兵概述

**核心功能**：基于 Redis Sentinel 协议实现主从集群的自动故障检测与故障转移，提供高可用能力。

**适用场景**：
- 一主多从的复制拓扑需要自动主从切换
- 业务侧希望由独立哨兵进程统一管理主节点健康

### 18.2 核心组件

| 组件 | 功能描述 |
|------|----------|
| `SentinelManager` | 哨兵管理器，负责被监控主从集合的注册、心跳调度、状态维护 |
| `SentinelServerHandler` | 哨兵节点网络层处理器，处理 Sentinel 协议命令（`PING`/`SUBSCRIBE`/`SENTINEL` 等） |
| `Sentinel` | 哨兵节点实例，描述单个哨兵的 ID、地址、配置 |
| `SentinelInstance` | 哨兵之间互相发现与通信的实例对象 |
| `SentinelState` | 哨兵运行期状态枚举（启动中、监控中、领导、故障转移中） |
| `SentinelConfig` | 哨兵配置（监控主节点、quorum、down-after-milliseconds 等参数） |
| `SentinelCommandHandler` | 哨兵协议命令处理器 |
| `SentinelStats` | 哨兵运行时统计 |
| `SentinelUtils` | 哨兵相关工具方法 |
| `FailoverManager` | 故障转移管理器，编排主节点选举、切换、配置广播 |
| `FailoverProcess` | 单次故障转移流程状态机 |
| `SlaveElection` | 从节点选举算法，按优先级与复制偏移挑选新主 |
| `NodeMonitor` | 节点监控任务，定期向被监控主从发送心跳 |
| `HealthChecker` | 健康检查器 |
| `QuorumChecker` | quorum 仲裁，判断客观下线 |
| `NodeResponseHandler` | 节点心跳响应处理 |

### 18.3 哨兵子包

| 子包 | 职责 |
|------|------|
| `core/` | 哨兵核心运行时（`Sentinel`、`SentinelManager`、`SentinelServerHandler`、`SentinelState`、`SentinelInstance`、`MasterState`、`SlaveState`、`NodeState`、`FailoverState`） |
| `config/` | 哨兵配置（`SentinelConfig`、`SentinelConstants`） |
| `failover/` | 故障转移（`FailoverManager`、`FailoverProcess`、`SlaveElection`） |
| `handler/` | 哨兵协议命令处理（`SentinelCommandHandler`） |
| `monitor/` | 节点监控（`NodeMonitor`、`HealthChecker`、`NodeResponseHandler`、`QuorumChecker`） |
| `util/` | 工具与统计（`SentinelUtils`、`SentinelStats`） |

### 18.4 故障检测与转移

**主观下线（SDOWN）**：
- 单个哨兵在 `down-after-milliseconds` 内未收到主节点有效响应，标记为 SDOWN。

**客观下线（ODOWN）**：
- 达到 `quorum` 数量的哨兵同时认为主节点 SDOWN，标记为 ODOWN。

**故障转移**：
1. 哨兵集群在所有在线从节点中按优先级与复制偏移选举新主
2. 向新主发送 `SLAVEOF NO ONE`
3. 向其他从节点发送 `SLAVEOF <new-master>` 重新指向新主
4. 旧主恢复后被自动切换为新主的从节点
5. 通过 Pub/Sub 广播配置变更（`+switch-master`）

## 19. 主从复制

### 19.1 复制概述

**核心功能**：支持完整的 Redis 主从复制协议，实现数据的实时同步和高可用性。

### 19.2 核心组件

| 组件 | 功能描述 |
|------|----------|
| MasterReplicationManager | 主节点复制管理器，处理从节点连接和数据同步 |
| SlaveReplicationService | 从节点复制服务，负责与主节点建立连接和同步数据 |
| ReplicationBacklog | 复制积压缓冲区，用于增量同步 |
| ReplicationCommandHandler | 处理复制相关命令（SLAVEOF、PSYNC、REPLCONF） |
| ReplicationController | 复制控制器，统一编排主从握手、PSYNC、RDB 传输、命令流应用 |
| ReplicationState | 复制状态机，描述从节点各阶段（DISCONNECTED/CONNECTING/HANDSHAKE/FULL_SYNC/PARTIAL_SYNC/ONLINE） |
| ReplicationCallback | 复制回调接口，抽象主从同步过程的关键节点通知 |
| ReplicationStreamApplier | 复制流应用器，将主节点发来的增量命令写入从节点存储 |
| SlaveReplicationClient | 从节点侧与主节点通信的 Netty 客户端 |
| WaitCommandExecutor | `WAIT` 命令执行器，等待指定副本确认到指定偏移 |
| ReplicationLagMonitor | 复制延迟监控器，统计从节点落后主节点的字节数与时间 |
| LoadProgressMonitor | 全量加载进度监控，跟踪 RDB 加载完成度 |
| RdbSnapshotGenerator | RDB 快照生成器，序列化主节点全量数据 |
| RdbDataLoader | RDB 数据加载器，在从节点侧解析并应用 RDB 快照 |
| ReadOnlyModeManager | 只读模式管理器，在从节点尚未同步完成时拒绝写命令 |
| TransferProgressMonitor / TransferProgressTracker | 传输进度监控与跟踪 |
| SlaveInfo / ReplicationConstants / ReplicationApplyException | 从节点信息、复制常量与异常定义 |

### 19.3 复制流程

**全量同步**：
1. 从节点发送 `PSYNC ? -1` 命令
2. 主节点返回 `+FULLRESYNC <replid> <offset>`
3. 主节点生成 RDB 快照并发送给从节点
4. 从节点加载 RDB 快照
5. 主节点发送缓冲区中的增量命令

**增量同步**：
1. 从节点发送 `PSYNC <replid> <offset>` 命令
2. 主节点检查复制积压缓冲区
3. 若找到匹配的偏移量，返回 `+CONTINUE` 并发送增量命令
4. 若未找到匹配的偏移量，触发全量同步

### 19.4 复制命令

| 命令 | 功能描述 |
|------|----------|
| SLAVEOF host port | 配置为指定主节点的从节点 |
| SLAVEOF NO ONE | 取消从节点身份，成为主节点 |
| PSYNC replid offset | 部分同步命令 |
| REPLCONF | 复制配置命令 |
| ROLE | 查看节点角色 |

### 19.5 复制状态管理

**从节点状态**：
- DISCONNECTED：未连接到主节点
- CONNECTING：正在连接主节点
- HANDSHAKE：正在进行握手
- FULL_SYNC：正在进行全量同步
- PARTIAL_SYNC：正在进行增量同步
- ONLINE：复制正常运行

### 19.6 配置选项

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| replicaof | "" | 主节点地址（host:port） |
| masterauth | "" | 主节点认证密码 |
| repl-timeout | 60 | 复制超时时间（秒） |
| repl-backlog-size | 1MB | 复制积压缓冲区大小 |

## 20. 下一步

- [设计决策](./design.md)：了解重要设计选择的理由和权衡
- [部署指南](../deployment/)：学习如何部署和配置 Luban-RDS
- [使用指南](../guide/)：学习如何使用 Luban-RDS 的各项功能

---

## 21. Mesh 集群（luban-rds-mesh）

`luban-rds-mesh` 是 v1.0.15 引入的 **3 节点 Raft 强一致集群模块**，用 3 台机器（3 节点互为副本）替代 Redis Cluster 的 6 节点（3 主 3 从），任一时刻只有 1 个 Leader 处理写入，写入需经多数派（2/3）ACK + 落盘后才返回 OK，**已确认的写入永不丢失**。

> 完整协议设计见 [luban-rds-mesh/docs/DESIGN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/DESIGN.md) v1.2；本节给出与现有 cluster 模块并列的能力视图。

### 21.1 核心卖点（vs Redis Cluster）

| 维度 | Redis Cluster（17 节） | **Mesh（本模块）** |
|------|----------------------|----------------|
| 机器数 | 6+（3 主 3 从） | **3**（互为副本，成本减半） |
| 数据分片 | 16384 Slot 分片 | 全量数据，无分片 |
| 一致性 | 最终一致（异步复制） | **强一致**（多数派 ACK + 落盘） |
| Leader 切换丢数据 | 可能丢未复制的写入 | **不会**（未 commit 的写入不返回 OK） |
| 客户端兼容 | Cluster aware 客户端 | **JedisCluster / lettuce / Redisson 零侵入**（经 `CLUSTER SLOTS` 引导 + `MOVED` 自动跟随）；普通客户端（Jedis 单机 / redis-cli）需连 Leader 或自行处理 `-MOVED` |

### 21.2 子包结构

mesh 模块的源码位于 `luban-rds-mesh/src/main/java/com/janeluo/luban/rds/mesh/`，按职责拆分为 8 个子包：

| 子包 | 主要类 | 职责 |
|------|--------|------|
| (根) | `MeshNode`, `MeshConfig` | 节点入口与配置聚合 |
| `bus/` | `MeshBusCodec`, `MeshBusClient`, `MeshBusServer`, `MeshFrame`, `MessageType` | MeshBus 传输层（Netty 私有协议，独立端口 = servicePort + 11000） |
| `core/` | `LogEntry`, `RaftStateMachine`, `MeshRole`, `MeshState`, `FileBasedPersistentStateStore` | Raft 日志条目、状态机、角色 / 状态枚举、持久化状态存储 |
| `election/` | `ElectionTimer`, `LeaseManager`, `VoteCollector` | 选举（PreVote 防 term 膨胀）+ Lease 心跳租约 + 投票收集 |
| `gateway/` | `MeshWriteGate` | handler 级读写分流门面（写打 Follower 时抛 `-MOVED`） |
| `lifecycle/` | `MeshBootstrap`, `MeshAssembly`, `MeshConfigPersister`, `MeshStartupLoader`, `MeshLifecycleListener` | 装配入口、与 `NettyRedisServer` 集成、配置持久化、启动加载 |
| `replication/` | `LogApplier`, `LogReplicator`, `SnapshotManager`, `TransactionPayload` | 日志应用、多数派复制、chunked snapshot、事务载荷封装 |
| `rpc/` | `AppendEntriesMessage`, `AppendEntriesResponse`, `RequestVoteMessage`, `RequestVoteResponse`, `InstallSnapshotMessage`, `MeshRpcMessage` | 5 类 RPC 消息（AppendEntries / 响应 / RequestVote / 响应 / InstallSnapshot） |
| `client/` | `MeshClientRedirector`, `MeshClusterCommands`, `MovedToLeaderException`, `LeaseInvalidException` | 客户端重定向、`CLUSTER` 命令实现、异常类型 |

### 21.3 关键流程

#### 选举（PreVote + Lease）

- **Follower → Candidate**：选举超时（默认 150~300ms 随机化）后转 Candidate
- **PreVote 探测**：先发送 `RequestVote` 探测多数派响应，**不增 term**（防 term 膨胀）；预投通过后才正式增 term 并发起 `RequestVote`
- **多数派 → Leader**：获得 2/3 投票转 Leader，开始接收客户端写入
- **Leader Lease**：每 100ms 发送心跳续租；默认租约时长 ≈ 2 × electionTimeout（600ms）；租约有效期内本地读，超时退化 read-index

> 详细规则与 RPC 字段见 [luban-rds-mesh/docs/DESIGN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/DESIGN.md) §三 §四。

#### 日志复制

- Leader 收到客户端写命令后封装为 `LogEntry`（含 `term` / `index` / `payload` / `clientRequestId`）
- 通过 `AppendEntriesMessage` 向所有 Follower 并行复制（带心跳 / 探测）
- 多数派 ACK + 本地落盘后 `commit`，由 `LogApplier` 顺序应用到状态机并返回客户端 OK
- 未 commit 的写入在 Leader 切换时丢弃（保证已确认写入不丢）

#### 读写分流（MeshWriteGate + MeshClientRedirector）

- **写**：MeshWriteGate 检查当前角色
  - Leader：本地提交 → 走日志复制
  - Follower / Candidate：抛 `-MOVED <slot> <leaderAddr>`，集群感知客户端自动跟随
  - 无 Leader（选举中）：返回 `-MESHDOWN The mesh cluster has no leader`，客户端退避重试
- **读**：默认 Leader Lease 内本地读；租约失效退化 read-index（确认 Leader 仍是当前多数派的最新 Leader 后再读）

#### CLUSTER 命令（单主视图）

| 命令 | 行为 |
|------|------|
| `CLUSTER INFO` | `cluster_state:ok`（有 Leader 时）/ `fail`（选举中），`cluster_known_nodes:3` |
| `CLUSTER NODES` | 3 行；`myself,master` 为当前 Leader（`linkState` 恒 `connected`），其余 2 行为 `slave`；离线节点标记 `disconnected` |
| `CLUSTER SLOTS` | `[[0, 16383, ["<leader>", <port>, "<leader-id>"]], [], []]`（含空 `replicas` 数组，兼容严格解析器） |

#### 持久化（chunked snapshot + dump.rdb）

- **Raft log 即 WAL**：所有写入首先入日志
- **dump.rdb 即快照**：每 `mesh-snapshot-log-threshold`（默认 100000）条日志由 `SnapshotManager` 触发，**chunked** 拆块传输给 Follower
- **AOF 退役**：mesh 模式**不写 AOF**（避免与 Raft log 双写）；RDB 文件唯一写者 = `SnapshotManager`
- **启动加载**：先加载最新 dump.rdb，再 replay Raft log 中 snapshot index 之后的条目

### 21.4 MOVED / MESHDOWN 语义

| 场景 | 响应 | 含义 |
|------|------|------|
| 写打到 Follower（已知 Leader） | `-MOVED <slot> <leaderServiceAddr>\r\n` | `slot` 为 key 的真实 CRC16；集群感知客户端自动跟随 |
| 选举中（无 Leader） | `-MESHDOWN The mesh cluster has no leader\r\n` | 客户端应退避重试 |

> `MOVED` 中的 slot 用 key 的真实 CRC16（非占位值），部分客户端依赖它更新本地路由缓存。

### 21.5 关键约束

| 约束 | 说明 |
|------|------|
| **NTP 时钟对齐** | Leader Lease 读依赖时钟（租约时长内本地读）；节点间时钟漂移过大需切 `mesh-read-consistency READ_INDEX` |
| **BLOCK 命令禁用** | `BLPOP / BRPOP / BLMOVE / WAIT` 等 v1 返回错误（Raft 化阻塞唤醒留待 v2） |
| **Lua 脚本当写** | `EVAL / EVALSHA` 统一按写处理（走 Raft 复制），即使脚本内只有读命令 |
| **cluster / mesh 互斥** | 同一进程只能启用其一（`mesh-enabled` 与 `cluster-enabled` 启动时校验） |
| **AOF 退役** | mesh 模式不写 AOF——Raft log 即 WAL、dump.rdb 即快照 |
| **dump.rdb 唯一写者** | mesh 模式禁用 server 原 RDB save（BGSAVE），dump.rdb 唯一写者 = SnapshotManager |

### 21.6 测试覆盖

| 阶段 | 测试内容 | 测试数（累计） |
|------|----------|--------|
| 1 | MeshBusCodec 编解码 | 10 |
| 2 | LogEntry / 5 种 RPC 序列化 | 34 |
| 3 | 选举 / 租约 / PreVote | 106 |
| 4 | LogApplier / LogReplicator | 149 |
| 5 | MeshWriteGate（读写分流 / MOVED） | 168 |
| 6 | MeshClientRedirector（MOVED/MESHDOWN） | 183 |
| 7 | 读路径（Leader Lease / read-index） | 195 |
| 8 | CLUSTER SLOTS/NODES/INFO | 221 |
| 9 | MULTI/EXEC 事务 / BLOCK 禁用 | 243 |
| 10 | chunked snapshot | 252 |
| 11 | 持久化 / 启动加载 | 278 |
| 12 | 装配（MeshBootstrap） | 286 |
| **13** | **3 节点集成测试（真实选举 + 多数派写 + 一致性）** | **291** |

阶段 13 的 `ThreeNodeIntegrationTest` 用内存路由总线连接 3 个真实 `MeshNode`，验证：选举出唯一 Leader → Leader 写 SET 经多数派确认 → 3 节点最终一致。

> **3 进程集成测试 / 故障注入**（kill leader、网络分区、时钟偏移）需真实多进程环境，留作手动验证（见 DESIGN §十「测试策略」）。单元 + 内存集成测试已覆盖协议正确性主线。

### 21.7 适用场景

| 场景 | 推荐度 |
|------|--------|
| 中小规模生产部署（数据 < 100GB） | 强烈推荐 |
| 金融 / 订单等强一致需求 | 强烈推荐 |
| 跨机房容灾（3 机房各 1 节点） | 推荐 |
| 超大规模数据（> 500GB） | 一般（建议 Redis Cluster 分片） |
| 频繁动态扩缩容 | 一般（固定 3 节点静态 meet；建议 Redis Cluster） |

### 21.8 与 cluster 模块的边界

| 维度 | cluster（17 节） | mesh（21 节） |
|------|------------------|---------------|
| 拓扑 | 3+ 主，可加从 | 固定 3 节点，互为副本 |
| 数据分布 | 16384 slot 分片 | 全量 |
| 复制协议 | 异步 PSYNC | Raft 同步复制（多数派 ACK + 落盘） |
| 一致性保证 | 最终一致 | 强一致（已确认写入不丢） |
| 选举算法 | configEpoch + 多数派投票 | Raft term + PreVote + 多数派投票 |
| 读路径 | 本地读 / replica-read | Leader Lease / read-index |
| 持久化 | RDB + AOF | dump.rdb（chunked snapshot），**不写 AOF** |
| 客户端接口 | `CLUSTER *` 全套 + `MOVED/ASK` | `CLUSTER SLOTS/NODES/INFO` + `MOVED/MESHDOWN` |

### 21.9 下一步

- [部署指南 - Mesh 集群](../mesh/setup.md)：3 节点配置与启动
- [Mesh 协议设计要点](../mesh/design.md)：状态机、RPC、Lease、read-index 摘要
- [luban-rds-mesh/README.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/README.md)：模块快速上手
- [luban-rds-mesh/docs/DESIGN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/DESIGN.md) v1.2：完整协议设计
- [luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md) v1.2：13 阶段实施计划
