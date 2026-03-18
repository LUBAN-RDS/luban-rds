---
title: 功能架构
---

# 功能架构

本部分详细介绍了 Luban-RDS 的功能模块设计，包括各个功能的实现细节、关键特性和使用方法。

## 1. 核心存储

### 1.1 MemoryStore 接口

**核心功能**：定义了所有数据类型的操作方法

**新增方法**：
- `zgetAllWithScores(int database, String key)` - 获取 ZSet 所有成员及其分数，用于持久化

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
| ClusterConfig | 集群配置管理，节点列表和槽位分配表 |
| GossipProtocol | Gossip 协议实现，心跳检测和故障发现 |
| ClusterBusServer | 集群总线服务器，端口 = 服务端口 + 10000 |
| ClusterCommandHandler | CLUSTER 命令处理器 |

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
| CLUSTER INFO | 集群状态信息 |
| CLUSTER NODES | 节点列表和槽位分配 |
| CLUSTER MEET ip port | 添加节点到集群 |
| CLUSTER FORGET nodeid | 从集群移除节点 |
| CLUSTER ADDSLOTS slot [...] | 分配槽位 |
| CLUSTER SETSLOT slot NODE nodeid | 设置槽位归属 |
| CLUSTER KEYSLOT key | 计算键的槽位 |
| CLUSTER REPLICATE nodeid | 配置为从节点 |
| CLUSTER FAILOVER | 手动故障转移 |

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
- Redisson Cluster（完整兼容）

## 18. 总结

Luban-RDS 的功能架构设计具有以下特点：

- 模块化：清晰的模块划分，便于维护和扩展
- 高性能：基于 Netty 的 NIO 服务器，支持高并发
- 兼容性：完全兼容 Redis 协议，可直接使用 Redis 客户端
- 可靠性：支持持久化、备份和恢复机制
- 安全性：Lua 脚本沙箱，超时控制，操作计数
- 可观测性：分布式追踪支持，全链路 TraceId 追踪
- 扩展性：支持命令扩展、存储扩展和插件系统
- 易用性：提供 Spring Boot 集成，便于在 Spring 应用中使用

## 18. 主从复制

### 18.1 复制概述

**核心功能**：支持完整的 Redis 主从复制协议，实现数据的实时同步和高可用性。

### 18.2 核心组件

| 组件 | 功能描述 |
|------|----------|
| MasterReplicationManager | 主节点复制管理器，处理从节点连接和数据同步 |
| SlaveReplicationService | 从节点复制服务，负责与主节点建立连接和同步数据 |
| ReplicationBacklog | 复制积压缓冲区，用于增量同步 |
| ReplicationCommandHandler | 处理复制相关命令（SLAVEOF、PSYNC、REPLCONF） |

### 18.3 复制流程

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

### 18.4 复制命令

| 命令 | 功能描述 |
|------|----------|
| SLAVEOF host port | 配置为指定主节点的从节点 |
| SLAVEOF NO ONE | 取消从节点身份，成为主节点 |
| PSYNC replid offset | 部分同步命令 |
| REPLCONF | 复制配置命令 |
| ROLE | 查看节点角色 |

### 18.5 复制状态管理

**从节点状态**：
- DISCONNECTED：未连接到主节点
- CONNECTING：正在连接主节点
- HANDSHAKE：正在进行握手
- FULL_SYNC：正在进行全量同步
- PARTIAL_SYNC：正在进行增量同步
- ONLINE：复制正常运行

### 18.6 配置选项

| 配置项 | 默认值 | 描述 |
|--------|--------|------|
| replicaof | "" | 主节点地址（host:port） |
| masterauth | "" | 主节点认证密码 |
| repl-timeout | 60 | 复制超时时间（秒） |
| repl-backlog-size | 1MB | 复制积压缓冲区大小 |

## 19. 下一步

- [设计决策](./design.md)：了解重要设计选择的理由和权衡
- [部署指南](../deployment/)：学习如何部署和配置 Luban-RDS
- [使用指南](../guide/)：学习如何使用 Luban-RDS 的各项功能
