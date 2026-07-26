## Why

Luban-RDS 经三轮系统性审计（见 `AUDIT-REPORT-vs-Redis7.md`）对照 Redis 7.x 基准后，发现 12 个 P0 级致命缺陷，覆盖复制、持久化、集群、核心数据结构四个子系统。这些缺陷会导致**数据损坏、数据丢失、复制端到端失效、故障转移选错节点、重启后 TTL 全部丢失**等不可恢复的生产事故，使项目当前状态明确不可用于生产环境。

本变更一次性修复全部 12 个 P0 缺陷（C1-C12），使 Luban-RDS 在数据安全维度达到 Redis 7.x 基准。范围严格限定为 P0，不含 21 个 H 级严重问题和 30 个 M 级中等问题（留待后续 change）。

## What Changes

### 复制子系统（4 个缺陷，存在强依赖链）

- **C2 修复 slave 状态机断裂**：在 `SlaveReplicationClient.handleResponse` 的 switch 中新增 PSYNC 响应路由分支，激活死代码 `handlePsyncResponse`；调整握手时序使 `+FULLRESYNC`/`+CONTINUE` 响应正确路由，使 `callback.onOnline()` 在生产路径可达
- **C4 修复 SLAVEOF 空操作**：`ReplicationCommandHandler.handleSlaveof` 解析 `host port` 参数后调用 `ReplicationCoordinator.startSlave(address)`；`SLAVEOF NO ONE` 调用 `stopSlave()` 断开复制连接
- **C5 修复 Full sync 窗口期丢失**：在 `handlePsync` 全量同步分支记录快照基准 offset，RDB 传输完成后从 backlog 重放窗口期命令；重放期间保持 slave SYNCING 状态避免 `propagateCommand` 并发直发导致乱序
- **C6 修复 slave offset 恒为 0**：作为 C2+C5 的下游症状，C2 修好后 slave 能发送 `REPLCONF ACK`、C5 修好后 offset 基准对齐；验证 `WAIT` 命令与 `INFO replication` 的 slave offset 正确

### 持久化子系统（3 个缺陷，存在依赖链）

- **C3 修复 AOF recordCommand 零调用**：在 `PersistService` 接口暴露 AOF 写入能力，在命令分发统一出口（`RedisServerHandler` 命令执行后）插入 `recordCommand` 调用，过滤读命令与特殊命令；修复 `recordCommand` 方法体丢弃 `command` 参数的 bug；接入 `BGREWRITEAOF` 空壳到 `rewrite` 方法
- **C10 修复 RDB 不保存 TTL**：在 `writeKeyValue` 每个 value 写完后按 Redis 标准写入 `0xFC`（毫秒）/ `0xFD`（秒）expire opcode（仅 `ttl > 0` 时）；加载侧 `readKeyValue` 增加对应 opcode 分支，读绝对过期时间戳后换算回剩余 TTL 调用 `expire`
- **C11 修复 AOF rewrite 类型丢失**：`writeKeyValueCommand` 按类型分支生成重建命令（string→SET、list→RPUSH、set→SADD、hash→HSET、zset→ZADD、stream→XADD + 消费者组恢复）；带 TTL 的键追加 `PEXPIREAT`；用 ISO-8859-1 字节序列化保证二进制安全

### 集群子系统（4 个缺陷，相对独立）

- **C1 修复 CROSSSLOT 缺失**：`extractKeyFromCommand` 改为返回键列表而非单键，`checkSlotAndRedirect` 遍历所有键做 CROSSSLOT 校验；为 RENAME/RENAMENX/COPY/UNLINK/TOUCH/SDIFFSTORE/SINTERSTORE/SUNIONSTORE/ZUNIONSTORE/ZINTERSTORE/BITOP/SORT STORE 等源+目标型命令同时校验两端 slot
- **C7 修复 MIGRATE 非原子**：引入批量 `MigrateKeysMessage`，目标端批量原子 `RESTORE`，源端在目标全部 ACK 后统一 DEL；失败时不删源，避免半迁移状态
- **C8 修复 Failover 选举不用偏移量**：`FailoverAuthRequestMessage` 第 4 参数填入本节点真实 `master_repl_offset`；`onAuthRequest` 同纪元多候选时按 replOffset 排序择优；`tryStartElection` 退避改为基于 replOffset rank
- **C9 修复手动 failover 不广播**：`performManualFailover` 末尾补 `busClient.broadcast(FailoverResultMessage)`，补 `masterNode.setConfigEpoch` 对齐自动路径；将广播职责收敛到 `performFailover` 共用方法内避免重复

### 核心数据结构（1 个缺陷，独立）

- **C12 修复 ZSet 同分字典序**：`ZSetStore.scoreMembers` 的值类型从 `ConcurrentHashMap.KeySetView<String, Boolean>` 改为 `ConcurrentSkipListSet<String>`（自然字典序，并发安全）；`zpopmax`/`zrevrange` 用 `descendingSet()` 实现反向字典序；同步调整 `estimateMemorySize` 估算常量

## Capabilities

### New Capabilities

- `cluster-slot-integrity`: 集群模式下多键命令的 CROSSSLOT 校验与 MIGRATE 原子性保证，覆盖键提取规则、跨槽拒绝语义、批量迁移的两阶段提交
- `replication-sync-state-machine`: 主从复制的握手状态机、PSYNC 响应路由、Full sync 窗口期命令缓冲与重放、slave offset 基准对齐、SLAVEOF/REPLICAOF 运行时命令的复制启动语义
- `persistence-data-integrity`: AOF 写入链路接入命令分发、RDB TTL 持久化（expire opcode 读写）、AOF rewrite 按类型重建命令，保证重启与 rewrite 后数据类型/TTL 不丢失

### Modified Capabilities

- `cluster-automatic-failover`: 新增"Failover 选举使用复制偏移量 tiebreak"和"手动 failover 广播 FailoverResult"两条要求，修改原"故障转移后原主重启经 gossip 自降级"的依赖前提（手动 failover 现在也走广播路径，收敛更快）
- `memory-store`: 新增"ZSet 同分成员按字典序排序"要求，覆盖 ZRANGE/ZRANK/ZREVRANGE/ZRANGEBYSCORE/ZPOPMIN/ZPOPMAX/ZSCAN/ZREMRANGEBYRANK 等所有同分迭代路径

## Impact

### 受影响代码

- **luban-rds-replication**：`SlaveReplicationClient.java`、`SlaveReplicationService.java`、`MasterReplicationManager.java`、`ReplicationCommandHandler.java`、`ReplicationCoordinator.java`（跨模块依赖注入）
- **luban-rds-persistence**：`AofPersistService.java`、`RdbPersistService.java`、`PersistService.java`（接口扩展）、`PersistServiceFactory.java`
- **luban-rds-cluster**：`RedisServerHandler.java`（CROSSSLOT）、`MigrateCommandHandler.java`、`FailoverManager.java`、`ClusterBusClient.java`（批量迁移消息）
- **luban-rds-core**：`DefaultMemoryStore.java`（ZSetStore）、`CommonCommandHandler.java`（BGREWRITEAOF/BGSAVE 接入）
- **luban-rds-server**：`NettyRedisServer.java`（AOF 写入钩子注入点）

### 受影响 API

- `PersistService` 接口新增 AOF 写入方法（**BREAKING** 对自定义 PersistService 实现）
- `extractKeyFromCommand` 返回类型从 `String` 改为 `List<String>`（内部方法，无外部 API 影响）
- `FailoverAuthRequestMessage` 序列化格式不变（字段已存在，仅填值）

### 依赖与风险

- C2 与 C5 存在耦合：C2 修好后 `handlePsyncResponse` 的 offset 解析才能跑通，C5 的重放基准 offset 才有来源；必须同批次修复
- C3 与 C11 存在依赖：C11 rewrite 产出的文件依赖 C3 让 AOF 写入链路通起来；C3 必须先于 C11 验证
- C6 是 C2+C5 的下游症状，不可单独修，需在 C2+C5 修复后验证
- ZSet 字典序改动影响 `MEMORY USAGE` 估算常量，需同步调整避免 maxmemory OOM 误判
- 复制状态机改造涉及 Netty 异步时序，需引入"发一条等一条响应"的模式替代当前同步串发

### 非目标

- 不修复 H1-H21 严重问题（EXEC 原子性、Lua 原子性、BGSAVE 桩、Sentinel 等）
- 不修复 M1-M30 中等问题（SCAN 游标、BLPOP 阻塞、LFU 淘汰等）
- 不补齐缺失命令族（Bit/Geo/HyperLogLog/Functions）
- 不做性能优化（skiplist span、quicklist、listpack 编码）
- 不改造 RDB 为 Redis 标准格式（仅补 TTL opcode，保留自研长度编码）
