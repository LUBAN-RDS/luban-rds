# 开发路线图

本文档详细描述 Luban-RDS 项目的开发路线图，基于与 Redis 7.x 的对比分析，规划了项目的后续开发工作。

## 1. 开发背景与目标

基于与 Redis 7.x 的对比分析，Luban-RDS 需要在多个方面进行功能增强和性能优化，以达到与 Redis 7.x 相当的功能水平和性能表现。本计划旨在系统性地推进 Luban-RDS 的开发，使其成为一个功能完整、性能优秀的 Redis 兼容内存数据库。

## 2. 项目状态

### 2.0 P0 数据安全修复（已归档 2026-07-27）

v1.0.7 → v1.0.8 期间集中修复了一批会影响生产数据正确性的 P0 问题，涵盖集群事务原子性、故障转移可靠性、持久化与数据正确性等关键路径：

- [x] **C1 CROSSSLOT 键校验（集群事务原子性）**: `MULTI/EXEC` 阶段补齐与单命令一致的 CROSSSLOT 校验，避免混合槽位的多键命令先入队再被批量拒绝时留下脏事务。
- [x] **C7 MIGRATE 原子性（事务支持）**: 修复 `MIGRATE` 在事务内可能被部分执行的窗口，保证事务整体原子回滚或提交。
- [x] **C8 Sentinel failover 选举安全性**: 完善 `leader-elect` 选举流程，避免在 Sentinel 集群网络分区场景下出现脑裂/双主。
- [x] **C9 FailoverResult 可靠广播**: 修正 `FailoverResult` 在网络瞬时断开时丢失通知的问题，新增失败重试与状态回查。
- [x] **C11 AOF rewrite 期间数据一致性**: 修复 AOF rewrite 与主写入并发时的子进程快照可能丢失最新写入的问题，引入追加双写与重写完成校验。
- [x] **C12 ZSet 字典序比较修复**: 修正 `ZRANGEBYLEX` / `ZRANGEBYSCORE` 中对同分数成员的字典序比较逻辑，使其与 Redis 7.x 行为一致。

### 2.1 v1.0.8 已发布功能（当前最新）

#### 分布式特性 - 集群高可用与运维友好
- [x] **集群配置持久化与节点状态恢复 (v1.0.4 起，沿用至 v1.0.8)**:
  - `ClusterConfigPersister` 在拓扑变更时自动同步 `nodes.conf`（`cluster-config-file`）
  - 脏标记（dirty flag）机制避免每次操作都同步刷盘；类 Redis 7 `clusterSaveConfigIfNeeded` 周期任务兜底刷新
  - 启动时从 `nodes.conf` 加载节点列表、槽位分配与 config epoch，复用已有节点 ID
  - 从恢复的 `ClusterConfig` 重建 `SlotManager` 槽位表，重启即可正常服务
  - 启动时主动 `MEET` 已知节点，避免全集群重启后节点成孤岛
  - 兼容旧版含 `fail` 标志的 `nodes.conf`，平滑升级
- [x] **移除 FAIL/PFAIL 状态持久化 (v1.0.4 起)**: 运行时瞬时状态不应写入 `nodes.conf`，避免重启后误判节点状态

### 2.2 v1.0.3 已发布功能

#### 分布式特性 - 集群兼容性与可靠性
- [x] **集群一键搭建 CLI (v1.0.3)**: `RedisCliMain` 对齐 `redis-cli --cluster create`，支持 `--cluster-replicas N`、`verbose` 静默模式与 Java 程序化调用 (`ClusterSetupCommand.createCluster(...)`)
- [x] **TCP 半包/粘包修复 (v1.0.3)**:
  - `RedisProtocolParser` 增加 reader index 回退机制、所有 RESP 分支 null 检查与解析失败重置
  - `NettyRedisClient` 引入累积缓冲 + 循环解析，处理跨 TCP 段的 RESP 响应与多响应合包
- [x] **CLUSTER SLOTS (v1.0.2)**: 完整实现，返回当前槽位分布数组
- [x] **集群 Gossip & 拓扑修复 (v1.0.2)**:
  - Gossip 发现新节点后主动建连 / `MEET`
  - `GossipTask` 不再跳过 `HANDSHAKE` 状态节点
  - Gossip 消息携带槽位所有权（`cluster_state` 才能正确转 `ok`）
- [x] **`CLUSTER NODES` 行尾符修复 (v1.0.2)**: 改用裸 `\n`，Redisson 解析不再抛 `NumberFormatException`
- [x] **集群握手协议修复 (v1.0.2)**: 修复 `CLUSTER MEET` 装配缺陷与临时 ID 解析
- [x] **`cluster_enabled` 字段补全 (v1.0.2)**: `CLUSTER INFO` / `INFO` 同步返回
- [x] **非集群模式跳过 CLUSTER 拦截 (v1.0.2)**: 行为更明确，便于排查
- [x] **集群调试日志降级 (v1.0.2)**: Gossip 调试日志调整为 `TRACE` 级，降低生产环境开销

### 2.3 v1.0.1 已发布功能

#### 分布式特性
- [x] **Redis Cluster 集群**: 完整实现 Redis Cluster 协议，支持 16384 槽位、MOVED/ASK 重定向、Gossip 心跳。
- [x] **主从复制**: 完整支持全量同步和增量同步，复制积压缓冲区。
- [x] **哨兵模式**: 实现哨兵模式核心功能。

### 2.4 v1.0.0 已发布功能

#### 核心功能
- [x] **Redis 协议 (RESP) 支持**: 完整的请求解析与响应编码，支持 RESP2 和 RESP3 协议协商。
- [x] **内存存储**: 支持 String, List, Set, Hash, ZSet, Stream 六大数据类型。
- [x] **键过期时间**: 支持 `EXPIRE`, `PEXPIRE`, `TTL`, `PTTL`, `PERSIST` 等命令。
- [x] **发布订阅 (Pub/Sub)**: 支持 `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH`, `PSUBSCRIBE`, `PUNSUBSCRIBE`, `SSUBSCRIBE`, `SUNSUBSCRIBE`。
- [x] **Lua 脚本支持**: 集成 LuaJ，支持 `EVAL`, `EVALSHA`, `SCRIPT LOAD` 等，包含沙箱模式。
- [x] **事务支持**: 完整实现 `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`，支持乐观锁机制。
- [x] **管道 (Pipeline)**: 基于 Netty 的 ByteBuf 处理，原生支持管道化请求。
- [x] **网络服务**: 基于 Netty 4.2 的高性能 NIO 服务器。
- [x] **客户端实现**: 提供 Java 客户端 `luban-rds-client`。
- [x] **Spring Boot 集成**: 提供 `luban-rds-spring-boot-starter`，支持自动配置。

#### 持久化
- [x] **RDB 持久化**: 支持内存快照保存与加载（`SAVE`, `BGSAVE`），使用 Kryo 序列化。
- [x] **AOF 持久化**: 支持追加写日志与重写（`BGREWRITEAOF`）。

#### 性能与监控
- [x] **集合操作优化**: 直接修改集合对象，避免不必要的数据复制。
- [x] **Lua 脚本优化**: 脚本缓存、执行超时控制、指令计数。
- [x] **实时监控 (MONITOR)**: 基于 MPSC Ring Buffer 实现的高性能零内存分配监控。
- [x] **慢查询日志 (SLOWLOG)**: 支持慢查询记录、查询和清空。
- [x] **内存分析 (MEMORY)**: 支持 `MEMORY USAGE`, `MEMORY STATS`, `MEMORY DOCTOR` 等命令。
- [x] **INFO 命令重构**: 提供可扩展的服务器状态信息聚合框架。
- [x] **客户端管理**: 支持 `CLIENT LIST`, `CLIENT KILL`, `CLIENT SETNAME`, `CLIENT GETNAME` 等命令。

#### 安全特性
- [x] **Lua 沙箱**: 限制 Lua 脚本对系统资源的访问。
- [x] **基础认证**: 支持 `AUTH` 命令进行简单密码验证。
- [x] **命令超时控制**: 防止长时间运行的命令阻塞服务器。

#### 扩展命令
- [x] **批量命令**: `MSET`, `MGET`, `HMSET`, `HMGET`, `DEL` (多键), `LPUSH`/`RPUSH`/`SADD`/`ZADD` (多元素)。
- [x] **字符串扩展**: `SETNX`, `GETSET`, `SETRANGE`, `GETRANGE`, `PSETEX`。
- [x] **集合扩展**: `SPOP`, `SRANDMEMBER`, `SMOVE`, `SINTER`, `SUNION`, `SDIFF`。
- [x] **有序集合扩展**: `ZREVRANGE`, `ZRANGEBYSCORE`, `ZRANK`, `ZREVRANK`, `ZCOUNT`, `ZINCRBY`。
- [x] **列表扩展**: `LINDEX`, `LSET`, `LREM`。
- [x] **哈希扩展**: `HSETNX`, `HINCRBY`, `HSCAN`。

#### 性能优化
- [x] **多线程 I/O**: 实现三层线程模型（Boss Group → Worker Group → Business Group），支持配置化线程数，显著提升高并发场景下的网络吞吐量。
- [x] **内存碎片整理**: 实现内存碎片率监控、自动/手动碎片整理机制，优化 StoreValue 内存占用（每条记录节省约 36-52 字节）。
- [x] **内存池集成**: 集成 Netty PooledByteBufAllocator，减少网络缓冲区 GC 压力，支持内存泄漏检测。
- [x] **分布式追踪**: 实现基于 TraceId 的全链路追踪，自动注入日志 MDC，支持多线程环境下的 TraceId 传递。

#### 云原生与运维
- [x] **Docker 容器化**: 提供官方 Dockerfile，支持多阶段构建、非 root 用户、健康检查。
- [x] **Docker Compose**: 提供完整的 Docker Compose 配置，支持一键部署。
- [x] **Kubernetes 部署**: 提供完整的 Kubernetes 部署清单，包括 Deployment、Service、ConfigMap 等。

### 2.5 v1.0.1-SNAPSHOT 新增功能（已合并到 v1.0.1 发布）

> 该节保留为历史变更说明，详情见上文 `2.3 v1.0.1 已发布功能`。

### 2.6 正在开发的功能 (In Progress)

#### 安全特性
- [~] **访问控制列表 (ACL)** — *部分完成*：
  - 已实现：`ACLManager`、`ACLCommandHandler`、`ACLPermissionChecker`，支持 `ACL WHOAMI`、`ACL LIST`、`ACL CAT`、`ACL GETUSER`、`ACL SETUSER`（子集）等基础命令与命令级/Key 模式级权限校验
  - 未完成：`ACL LOAD`、`ACL SAVE`、`ACL LOG` 等持久化与审计能力
- [ ] **传输加密 (TLS/SSL)**: 支持 SSL/TLS 加密连接，保障数据传输安全。

### 2.7 计划中的功能 (Planned)

#### 高级数据类型
- [ ] **地理空间索引 (Geo)**: 支持 `GEOADD`, `GEODIST`, `GEORADIUS` 等。
- [ ] **位图 (Bitmap)**: 支持 `SETBIT`, `GETBIT`, `BITCOUNT`, `BITOP`。
- [ ] **超日志 (HyperLogLog)**: 支持 `PFADD`, `PFCOUNT`, `PFMERGE`。

#### 云原生与运维
- [ ] **Kubernetes Operator**: 简化在 K8s 环境下的部署与运维。
- [ ] **Prometheus Exporter**: 导出监控指标供 Prometheus 采集。

## 3. 功能模块开发顺序

### 3.1 第一阶段（高优先级）

1. **ACL 安全控制**
2. **模块系统支持**
3. **高可用性机制**

### 3.2 第二阶段（中优先级）

1. **高级数据结构**
2. **持久化优化**
3. **流处理高级特性**
4. **地理命令支持**

### 3.3 第三阶段（低优先级）

1. **命令集完善**
2. **性能优化细节**
3. **管理命令扩展**

## 4. 详细开发计划

### 4.1 第一阶段：核心功能增强

#### 4.1.1 ACL 安全控制（部分完成 + 持续增强）

**实现目标**：
- 实现完整的 ACL 访问控制列表
- 支持用户认证和权限管理
- 支持命令级别的权限控制
- 支持键空间级别的权限控制

**技术难点**：
- 权限模型设计
- 命令权限验证
- 性能影响最小化

**解决方案**：
- 参考 Redis 7.x 的 ACL 实现
- 设计高效的权限验证机制
- 缓存权限检查结果

**质量验收标准（当前进度）**：
- [x] `ACLManager` / `ACLCommandHandler` / `ACLPermissionChecker` 已落地，支持 `ACL WHOAMI` / `ACL LIST` / `ACL CAT` / `ACL GETUSER` 等基础命令
- [x] 命令级 + Key 模式级权限校验生效
- [ ] `ACL LOAD` / `ACL SAVE` / `ACL LOG` 等持久化与审计能力（计划中）
- [ ] 性能影响 < 5% 与全量命令权限回归测试（持续完善）

#### 4.1.2 模块系统支持

**实现目标**：
- 设计并实现模块加载机制
- 支持 RedisJSON 模块
- 支持 RedisTimeSeries 模块
- 提供模块 API 接口

**技术难点**：
- 模块加载和管理
- 模块 API 设计
- 性能优化

**解决方案**：
- 参考 Redis 的模块系统设计
- 实现热加载机制
- 优化模块调用性能

**质量验收标准**：
- 模块正确加载和卸载
- RedisJSON 和 RedisTimeSeries 功能正常
- 模块 API 稳定
- 性能符合预期

#### 4.1.3 高可用性机制（已完成 + 持续增强）

**实现目标**：
- 实现主从复制
- 实现 Sentinel 机制
- 支持自动故障转移
- 支持读写分离

**技术难点**：
- 复制机制设计
- 故障检测和转移
- 数据一致性保证

**解决方案**：
- 基于 Netty 实现异步复制
- 设计心跳检测机制
- 实现选举算法（v1.0.8 修复了脑裂/双主问题，见 2.0 节 C8）


**质量验收标准（当前进度）**：
- [x] 主从复制正常工作
- [x] Sentinel 心跳检测与故障转移自动化
- [x] 数据一致性保证（v1.0.8 修复 C9 FailoverResult 可靠广播）
- [ ] 高可用性测试与混沌工程演练（持续完善）

#### 4.1.4 集群功能（已完成）

**实现目标**：
- 实现集群模式
- 支持数据自动分片
- 支持槽位管理
- 支持节点发现和管理

**技术实现**：
- 使用 CRC16 算法计算键的槽位（16384 槽位）
- BitSet 优化存储（2KB 存储所有槽位状态）
- Gossip 协议实现节点间通信和故障检测
- MOVED/ASK 重定向机制
- 集群总线协议（端口 + 10000）

**质量验收标准**：
- [x] 集群正常启动和运行
- [x] 数据正确分片
- [x] 节点故障检测
- [x] Jedis/Lettuce/Redisson 客户端兼容性测试通过

### 4.2 第二阶段：功能完善

#### 4.2.1 高级数据结构

**实现目标**：
- 实现 HyperLogLog
- 实现 BitMap
- 实现 Geo 地理数据结构

**技术难点**：
- 算法实现
- 内存优化
- 性能保证

**解决方案**：
- 参考 Redis 的实现
- 优化内存使用
- 实现高效算法

**质量验收标准**：
- 数据结构功能完整
- 性能符合预期
- 内存使用合理

#### 4.2.2 持久化优化

**实现目标**：
- 实现多部分 AOF
- 支持 RDB v10 格式
- 优化持久化性能

**技术难点**：
- AOF 文件管理
- RDB 格式兼容
- 持久化性能

**解决方案**：
- 设计 AOF 文件管理机制
- 实现 RDB v10 格式解析
- 优化持久化过程


**质量验收标准**：
- 多部分 AOF 正常工作
- RDB v10 格式兼容
- 持久化性能提升

#### 4.2.3 流处理高级特性

**实现目标**：
- 增强 Stream 功能
- 支持流消费者组高级特性
- 优化流处理性能

**技术难点**：
- 消费者组管理
- 消息确认机制
- 性能优化

**解决方案**：
- 完善消费者组实现
- 优化消息处理机制
- 提升流处理性能


**质量验收标准**：
- 流消费者组功能完整
- 消息处理正确
- 性能符合预期

#### 4.2.4 地理命令支持

**实现目标**：
- 实现 Geo 相关命令
- 支持地理空间查询
- 优化地理计算性能

**技术难点**：
- 地理算法实现
- 空间索引设计
- 性能优化

**解决方案**：
- 实现 GeoHash 算法
- 设计高效空间索引
- 优化地理计算

**质量验收标准**：
- 地理命令功能完整
- 空间查询准确
- 性能符合预期

### 4.3 第三阶段：优化与完善

#### 4.3.1 命令集完善

**实现目标**：
- 完善核心命令支持
- 补充缺失的管理命令
- 确保命令兼容性

**技术难点**：
- 命令实现完整性
- 兼容性保证
- 测试覆盖

**解决方案**：
- 参考 Redis 命令规范
- 完善命令实现
- 编写全面测试

**质量验收标准**：
- 命令集完整
- 兼容性测试通过
- 测试覆盖充分

#### 4.3.2 性能优化细节

**实现目标**：
- 优化内存使用
- 提升网络性能
- 优化命令执行

**技术难点**：
- 内存优化策略
- 网络性能提升
- 命令执行优化

**解决方案**：
- 分析性能瓶颈
- 优化内存管理
- 提升网络处理
- 优化命令执行路径

**质量验收标准**：
- 内存使用降低
- 网络性能提升
- 命令执行速度加快

#### 4.3.3 管理命令扩展

**实现目标**：
- 完善管理命令
- 增强监控功能
- 提供更多管理工具

**技术难点**：
- 命令设计
- 监控数据收集
- 管理工具实现

**解决方案**：
- 参考 Redis 管理命令
- 设计监控机制
- 实现管理工具


**质量验收标准**：
- 管理命令完整
- 监控功能有效
- 管理工具实用


## 5. 质量验收标准

### 5.1 功能验收

- 所有核心功能正常工作
- 与 Redis 7.x 命令兼容性 > 95%
- 集群功能稳定可靠
- 高可用性机制有效
- 模块系统正常运行

### 5.2 性能验收

- 单节点性能达到 Redis 7.x 的 90% 以上
- 集群性能线性扩展
- 内存使用合理
- 网络延迟低
- 持久化性能良好

### 5.3 可靠性验收

- 系统稳定运行 72 小时无故障
- 故障转移时间 < 10 秒
- 数据一致性保证
- 错误处理完善
- 资源使用稳定

### 5.4 安全性验收  

- ACL 权限控制有效
- 密码认证安全
- 网络传输安全
- 命令执行安全
- 模块加载安全

## 6. 贡献

如果您对路线图有任何建议或想参与开发，请参考 [贡献指南](contributing.md)。

## 7. 技术栈与依赖

- **语言**: Java 17+
- **构建工具**: Maven 3.6.3+
- **核心框架**:
    - Netty 4.2.10.Final (网络层)
    - Spring Boot 3.4.11 (集成支持)
    - Guava 33.5.0-jre (工具库)
    - Caffeine 3.2.3 (高性能缓存)
    - LuaJ 3.0.1 (Lua 脚本引擎)
    - Kryo 5.6.0 (RDB 序列化)
    - JUnit Jupiter (测试)


## 8. 总结

本开发路线图基于 Luban-RDS 与 Redis 7.x 的对比分析，系统性地规划了 Luban-RDS 的后续开发工作。通过分阶段实施，可以逐步提升 Luban-RDS 的功能完整性和性能表现，使其成为一个功能强大、性能优秀的 Redis 兼容内存数据库。

在实施过程中，需要严格遵循质量验收标准，及时应对各种风险，确保项目顺利完成。最终目标是使 Luban-RDS 达到与 Redis 7.x 相当的功能水平和性能表现，为用户提供一个可靠、高效的内存数据库解决方案。