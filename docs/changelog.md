---
title: 更新日志
last_updated: 2026-07-27
version: 1.0.8
---
# 更新日志

Luban-RDS 是一款轻量级、高性能、完全兼容 RESP 协议的 Java 内存数据库，易于嵌入和扩展。

## [1.0.8] - 2026-07-27

### 新增功能

- **P0 数据安全 / Redis 7 兼容性修复（审计 C1/C7/C8/C9/C11/C12）**
  - **C1 CROSSSLOT 校验**（`c09903e`）：集群模式下对多键命令执行 CROSSSLOT 槽位归属校验，不在同一槽位的多键命令返回标准 `-CROSSSLOT Keys in request don't hash to the same slot` 错误
  - **C7 MIGRATE 原子性**（`18c834d`）：多键槽位迁移改用一次性 `RESTORE`，保证迁移过程原子化，避免半迁移状态
  - **C8 故障转移选举**（`9fed095`）：选举算法改用真实复制偏移（replication offset）作为选主依据，替代旧的随机/固定值选举
  - **C9 手动故障转移广播**（`278b294`）：`CLUSTER FAILOVER` 完成后通过新增的 `FAILOVER_RESULT` 消息（类型码 0x08）向全集群广播结果
  - **C11 AOF 二进制安全**（`21b8774`）：AOF rewrite 改为按数据类型分别重写；AOF 加载同样二进制安全，避免二进制数据损坏
  - **C12 ZSet 同分数排序**（`252851c`）：ZSet 相同分数成员的排序改为字典序（lexicographic），与 Redis 官方语义一致

### 修复

- 配套修复 P0 审计报告中其余条目（C2/C3/C4/C5/C6/C10 等）已随上述 P0 批次合并，详见 `openspec/changes/archive/2026-07-27-fix-p0-data-safety-redis7/`

### 兼容性

- 与 v1.0.4 ~ v1.0.7 完全兼容，AOF/RDB 文件结构与 Redis 7 协议行为一致
- 与 Jedis / Lettuce / Redisson / `redis-cli` 回归验证通过

## [1.0.7] - 2026-07-XX

### 新增功能

- **`CLUSTER SET-CONFIG-EPOCH` 命令**：完整实现，支持设置节点 configEpoch
- **`ADDSLOTS` 后自动同步 configEpoch**：`ClusterCommandHandler` 在槽位分配后主动同步 epoch
- **Gossip 协议携带 `masterNodeId`**：`GossipNodeInfo` 新增字段，主从关系随 Gossip 扩散
- **`CLUSTER REPLICATE` 角色传播**：从节点角色经 Gossip 协议正确传递到全集群
- **集群总线通信重构**：提升 Gossip 与 PING/PONG 的可靠性

### 修复

- **修复 `slotManager` / `clusterConfig` / `ClusterNode` 三重槽位归属不一致**：统一以 `slotManager` 为准
- **修复故障转移后 `CLUSTER SLOTS` 返回错误路由**：故障转移后 `ClusterConfig.slotAssignment` 同步更新
- **修复从节点始终返回 `CLUSTERDOWN`**：`checkSlotAndRedirect` 改为从 `slotManager` 读取槽位归属
- **修复 `CLUSTER MEET` 用 127.0.0.1 建连后节点地址未收敛为真实 IP**
- **修复 Gossip 协议 `masterNodeId` 在 MASTER→SLAVE 角色切换时未同步**

### 优化

- **ClusterNode 线程安全**：关键读写方法加 `synchronized`
- **ReplicationCoordinator 装配**：事务传播写入命令到复制链路
- **`ReplicationLifecycleListener`**：将角色变更接入复制路径
- **`ReplicationStreamApplier`**：实现并接入 `SlaveReplicationService`

### 兼容性

- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster` 验证通过

## [1.0.6] - 2026-07-XX

### 修复

- **修复集群 PFAIL 投票未通过 Gossip 传播**：修复 PFAIL 状态无法扩散导致自动故障转移失效
- **`ClusterNode` 状态修正**：PFAIL→FAIL 升级链路恢复，触发后续自动选举

### 新增功能

- **`FAILOVER_RESULT` 消息类型**：新增 0x08 消息类型与 `FailoverResultMessage`
- **`FailoverManager` 状态机**：状态机 + 消息分发接线 + 注入 `NettyRedisServer`
- **`ClusterConfig.getSlavesOfMaster`**：暴露主从关系查询
- **`gracePeriod` 配置**：故障转移宽限期可配置

### 兼容性

- 消息类型码向后兼容（0x07/0x08 不冲突）

## [1.0.5] - 2026-07-XX

### 新增功能

- **`FailoverManager` 骨架**：引入自动选举 / 状态切换的基础组件，为后续 PFAIL→FAIL 投票链路预留接入点
- **Gossip 状态整合**：接入点预留

### 修复

- **累积修复**：本版本以工程内部改进为主，未单独发布外部可见特性

### 兼容性

- 与 v1.0.4 协议层完全兼容

## [1.0.4] - 2026-07-08

### 新增功能

- **集群配置持久化与节点状态恢复**
  - 启动时自动从 `nodes.conf`（`cluster-config-file`）加载已有集群配置，恢复节点列表、槽位分配和配置纪元信息
  - 优先复用已存在的节点 ID，避免重启后节点 ID 漂移导致拓扑分裂
  - 重启后保留节点状态，仅更新可能变化的 IP/端口网络地址信息
  - 优化集群初始化流程，增加配置文件加载和状态恢复步骤
  - 详细日志记录配置加载与状态恢复过程，便于运维排查
  - 保持 `MYSELF` 节点的连接状态和其它重要属性在重启后不变
- **集群配置自动持久化机制**
  - 引入脏标记（dirty flag）追踪集群拓扑变更，避免每次操作都同步刷盘
  - `ClusterConfig` 新增 `markDirty`、`isDirty`、`clearDirty` 方法，供命令处理器在拓扑变更时主动标记
  - 通过 Gossip 协议在节点变更时自动触发配置持久化
  - `ClusterCommandHandler` 在处理 MEET/FORGET/ADDSLOTS 等命令时通知拓扑变更
  - 实现类 Redis 7 `clusterSaveConfigIfNeeded` 的周期性检查机制，定时刷新脏配置
  - 优化启动流程，移除重复的配置保存调用

### 修复

- **集群重启后节点状态和槽位分配恢复问题**
  - 移除 `FAIL`/`PFAIL` 状态的持久化，这些是运行时瞬时状态，不应写入 `nodes.conf`
  - 添加启动时主动连接已知节点功能，避免全集群重启后节点成孤岛无法恢复
  - 实现从恢复的 `ClusterConfig` 重建 `SlotManager` 槽位表，确保重启后能正常服务请求
  - 添加兼容旧版 `nodes.conf` 含 `fail` 标志的处理逻辑，避免升级后启动失败
  - 增加相关单元测试验证重启恢复场景的正确性

### 兼容性

- 与 v1.0.0 ~ v1.0.3 已生成的 `nodes.conf` 保持向后兼容（自动忽略 `fail` 标志）
- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 验证通过

## [1.0.3] - 2026-07-07

### 新增功能

- **Redis 集群创建 CLI 工具**（`RedisCliMain`）
  - 模仿 `redis-cli --cluster create` 子集，支持远程编排集群搭建
  - 通过 `--cluster create <host:port> ... [--cluster-replicas N]` 参数创建 3 主 + N 从的集群
  - 自动完成 `CLUSTER MEET`、主从划分、16384 槽位均分、状态校验
  - 提供 `ClusterSetupCommand` 静态方法 `createCluster(...)` 便于程序化嵌入调用
  - 新增 `verbose` 参数支持静默模式（用于脚本/批量场景）

### 修复

- **客户端半包/粘包问题**（`NettyRedisClient`）
  - 添加 `ByteBuf` 累积缓冲区，正确处理跨 TCP 段的 RESP 响应
  - 实现循环解析机制，处理同一 TCP 段中的多个完整响应
  - 通过 `readerIndex` 标记区分半包与完整响应的解析状态
  - 连接关闭时正确释放累积缓冲区，避免内存泄漏
- **协议解析器半包处理逻辑**（`RedisProtocolParser`）
  - `parseBulkStringBytes` 方法添加 reader index 回退机制处理半包
  - 为所有解析分支（简单字符串、错误、整数、批量字符串、数组、映射、集合等）统一添加 null 检查
  - 解析失败时重置缓冲区读取索引，避免协议解析死循环
  - 完善 CRLF 检测逻辑，确保半包数据能正确等待后续字节
  - 优化 `parseResp` 错误处理流程，提升解析稳定性

### 兼容性

- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 全流程验证通过
- `CLUSTER NODES` / `CLUSTER SLOTS` / `CLUSTER INFO` 输出格式与 Redis 官方一致

## [1.0.2] - 2026-07-07

### 新增功能

- **CLUSTER SLOTS 命令**：完整实现 Redis `CLUSTER SLOTS` 命令，返回当前槽位分布数组
- **集群节点过滤优化**：在节点列表中过滤下线/未握手节点，避免返回陈旧拓扑

### 修复

- **修复 `redis-cli --cluster create` 卡在 "Waiting for the cluster to join"**
  - Gossip 发现新节点后主动建连/`MEET`，保证拓扑收敛
  - `GossipTask` 心跳不再跳过 `HANDSHAKE` 状态节点，握手流程正常推进
  - Gossip 消息携带槽位所有权信息，`cluster_state` 能够正确转为 `ok`
- **修复 CLUSTER NODES 行尾符导致 Redisson 解析异常**
  - `ClusterCommandHandler.clusterNodes()` 每行改用裸 `\n` 结尾（对齐真实 Redis 行为）
  - 解决 Redisson `ClusterNodesDecoder` 因残留 `\r` 而抛 `NumberFormatException` 导致集群初始化失败的问题
- **修复 `CLUSTER MEET` 命令在集群模式下装配缺陷**：在集群模式下正确路由 `CLUSTER MEET` 到集群命令处理器
- **修复集群节点握手协议和临时 ID 解析机制**：`MEET` 消息识别握手阶段返回的临时 ID，避免误判为已知节点
- **补全 `CLUSTER INFO` 与 `INFO` 的 `cluster_enabled` 字段**：使第三方监控/健康检查能正确判定集群模式开关
- **禁用集群模式时跳过 CLUSTER 命令拦截**：避免 `cluster-enabled=no` 时仍拦截 CLUSTER 命令带来的语义混淆

### 优化

- 集群调试日志级别调整为 `TRACE`，降低生产环境日志开销

## [1.0.1] - 2026-03-24

### 新增功能

- **Redis Cluster 集群模式**：完整实现 Redis Cluster 协议兼容
  - 16384 槽位分配与管理（BitSet 优化）
  - MOVED/ASK 重定向机制
  - Gossip 协议心跳检测
  - PFAIL/FAIL 故障检测
  - 槽位迁移（IMPORTING/MIGRATING 状态）
  - 集群总线协议（端口 + 10000）
  - Hash Tag 语法支持 `{tag}`
  - Jedis/Lettuce/Redisson 客户端兼容性测试
- **主从复制**：完整支持 Redis 主从复制协议
  - 全量同步（RDB 传输）
  - 增量同步（基于复制积压缓冲区）
  - 复制状态管理
  - 从节点只读模式
- **哨兵模式（Sentinel）**：实现哨兵模式核心功能

### 变更

- 升级 Spring Boot 版本至 3.4.11

## [1.0.0] - 2026-03-04

### 新增功能

- **协议支持**
  - 完整 RESP 协议解析与编码，支持 RESP2 和 RESP3
  - 完整 RESP3 协议支持，包括新数据类型（Map、Set、Null、Boolean、Double、Big Number）
  - 协议版本自动检测和切换，支持 RESP2 和 RESP3 客户端
- **数据结构**
  - 内存数据结构与过期支持（String/List/Set/Hash/ZSet/Stream）
  - Stream 数据类型支持：完整实现 Stream 相关命令（XADD, XLEN, XRANGE, XREVRANGE, XREAD, XGROUP, XREADGROUP 等）
- **Lua 脚本**
  - Lua 脚本执行（EVAL/EVALSHA/SCRIPT），沙箱与执行统计
  - Lua struct 库增强：支持 Lc0、Ic0、ic0 等组合格式说明符，变长字符串打包和解包
- **持久化**
  - RDB 与 AOF 持久化机制
- **网络服务**
  - 基于 Netty 的高并发 NIO 服务器
  - 多线程 I/O 优化：三层线程模型（Boss → Worker → Business）
  - 内存池集成：Netty PooledByteBufAllocator
- **发布订阅**
  - 发布/订阅：频道订阅、模式订阅和流订阅
- **事务支持**
  - 事务支持：MULTI/EXEC/DISCARD/WATCH
  - 键版本控制机制，支持 WATCH 乐观锁
- **集成与扩展**
  - Spring Boot Starter 自动配置集成
- **监控与管理**
  - 内存统计与 MEMORY 命令族
  - 高性能 MONITOR 命令与事件管线（采用 MPSC 无锁环形缓冲区，<40ns 开销）
  - 慢查询日志功能（SLOWLOG GET/LEN/RESET）
  - 分布式追踪支持：基于 TraceId 的全链路追踪，自动注入日志 MDC
- **命令扩展**
  - 批量命令支持：MSET, MGET, HMSET, HMGET, DEL (多键)
  - 多元素推入：LPUSH/RPUSH/SADD/ZADD 支持多元素
  - 扩展字符串命令：SETNX, GETSET, SETRANGE, GETRANGE, PSETEX
  - 扩展集合命令：SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF, SSCAN
  - 扩展有序集合命令：ZREVRANGE, ZRANGEBYSCORE, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY, ZPOPMAX, ZPOPMIN, ZSCAN
  - 扩展列表命令：LINDEX, LSET, LREM, LTRIM
  - 扩展哈希命令：HSETNX, HINCRBY, HSCAN
  - 客户端管理命令：CLIENT LIST, CLIENT KILL, CLIENT SETNAME, CLIENT GETNAME
  - BLPOP/BRPOP 阻塞命令支持：完整实现 Redis 规范的阻塞列表弹出命令
- **内存管理**
  - 内存碎片整理：自动/手动
- **部署支持**
  - Docker 部署支持
  - Kubernetes 部署支持
- **配置**
  - 配置文件 Lua 支持：新增 lua-timeout、lua-sandbox-enabled、lua-max-script-bytes 等配置项

### 变更

- 升级 Netty 版本至 4.2.10.Final
- 升级 Caffeine 版本至 3.2.3
- 升级 Guava 版本至 33.5.0-jre
- 升级 Kryo 版本至 5.6.0
- RDB 持久化改用 Kryo 序列化框架
- MONITOR 命令支持 DB 和 MATCH 过滤参数

### 修复

- 修复事务执行时的响应序列化问题
- 修复 WATCH 机制在多数据库场景下的键版本检查
- 修复 String.intern() 导致的内存泄漏问题，改用分段锁机制
- 修复过期键竞态条件问题，使用双重检查锁定机制
- 修复 STRLEN 命令返回字符长度而非字节长度的问题
- 修复 MSET 命令缺少原子性保证的问题
- 修复 LRU 淘汰策略性能问题，优化采样算法
- 修复 AOF 持久化命令解析不完整问题，支持 20+ 种命令类型
- 修复 RDB 持久化 ZSet 分数丢失问题，完整保存和恢复分数
- 修复 RDB 长度编码错误，添加边界检查
- 添加过期键主动清理机制，避免过期键长期占用内存
- 修复 Lua 脚本中 HSCAN/SSCAN/ZSCAN 嵌套数组解析问题
- 修复 BLPOP/BRPOP 命令未注册问题

### 安全

- 增强 Lua 脚本沙箱安全性
