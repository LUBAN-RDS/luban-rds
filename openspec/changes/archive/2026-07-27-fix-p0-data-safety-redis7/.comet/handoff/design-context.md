# Comet Design Handoff

- Change: fix-p0-data-safety-redis7
- Phase: design
- Mode: compact
- Context hash: e9ec3af999b52e52ff9f07d62438571c7a00b6313220bee779f673061b60d012

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/fix-p0-data-safety-redis7/proposal.md

- Source: openspec/changes/fix-p0-data-safety-redis7/proposal.md
- Lines: 1-76
- SHA256: ffd7c9740905e33d23690e2fb193daf8d8bfdbe0f466726c6468c57d7c3ab5af

```md
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
```

## openspec/changes/fix-p0-data-safety-redis7/design.md

- Source: openspec/changes/fix-p0-data-safety-redis7/design.md
- Lines: 1-223
- SHA256: 5b553edbd8294085bf34ac3160804e7eaad01f5ec800e25ffe673b2197452841

[TRUNCATED]

```md
## Context

Luban-RDS 是 Java 版 Redis 协议兼容服务器，经三轮审计对照 Redis 7.x 基准后发现 12 个 P0 致命缺陷。当前代码状态（HEAD `834f205`）经四个并行子代理逐行核实，**12 个缺陷全部确认存在**，且最近的 failover 相关提交（`599291a`、`0c0b835`）只触及 `FailoverManager.java` 和 `ClusterConfigPersister.java`，未触及复制状态机与持久化写入链路。

### 当前架构关键事实

- **复制握手**：`SlaveReplicationClient.handleResponse` 的 switch（191-214 行）无 PSYNC case，`handlePsyncResponse`（296 行）grep 零调用，`+FULLRESYNC`/`+CONTINUE` 被错误路由到 `handleReplconfResponse`。`sendReplConf()` 同步串发 3 条 REPLCONF 后立即 `startPsync()`，状态停留在 `HANDSHAKE_REPLCONF_CAPA`。
- **Full sync**：`performFullSync`（240-283 行）RDB 传输完成后只 `setState(ONLINE)`，无 backlog 重放；`propagateCommand`（292-308 行）仅发给 `isOnline()` 的 slave，SYNCING 期间命令静默跳过。
- **AOF 写入**：`recordCommand`（184 行）grep 全库零外部调用，方法体还丢弃 `command` 参数；`PersistService` 接口只有 `persist/load/getInfo/close`，未暴露 AOF 写入能力；`BGREWRITEAOF`/`BGSAVE` 是空壳。
- **RDB TTL**：`writeKeyValue`（401-439 行）无 expire opcode，加载侧无 0xFC/0xFD 分支；`MemoryStore.ttl()` 接口已存在但未被持久化层调用。
- **CROSSSLOT**：`extractKeyFromCommand`（2457-2488 行）只返回首键，仅 EVAL/EVALSHA 有 `checkCrossSlotForScript`（2502-2531 行）。
- **Failover**：`FailoverAuthRequestMessage` 第 4 参数传 `0L`（225 行），偏移量仅出现在 debug 日志；`performManualFailover`（408-413 行）不广播、漏 `oldMaster.setConfigEpoch`。
- **ZSet**：`scoreMembers` 值类型为 `ConcurrentHashMap.KeySetView<String, Boolean>`（2526-2527 行），无任何字典序结构。

### 约束

- Java 17+，Maven 多模块，4 spaces 缩进，显式 import，无 raw type
- 不能破坏现有 RESP 协议兼容性
- 修改需保持并发安全（项目用多线程 business pool 模型，非 Redis 单线程）
- 现有测试中 `ReplicationIntegrationTest` 被 `@Disabled`，修复后需重新启用

## Goals / Non-Goals

**Goals:**

- 修复 12 个 P0 缺陷，使数据安全维度达到 Redis 7.x 基准
- 保持并发安全：所有修复在多线程 business pool 模型下正确
- 保持向后兼容：RESP 协议、配置项、文件格式（自研 RDB 长度编码保留）不破坏
- 修复可验证：每个缺陷有对应的测试用例，`ReplicationIntegrationTest` 重新启用

**Non-Goals:**

- 不修复 H1-H21 严重问题（EXEC 原子性、Lua 原子性、Sentinel、maxclients 等）
- 不修复 M1-M30 中等问题（SCAN 游标、BLPOP 阻塞、LFU 等）
- 不补齐缺失命令族（Bit/Geo/HyperLogLog/Functions）
- 不做性能优化（skiplist span、quicklist、listpack 编码）
- 不改造 RDB 为 Redis 标准格式（仅补 TTL opcode + AUX 可选，保留自研长度编码）
- 不实现 Redis 7.x 多部分 AOF（manifest + base + incr），仅修复单文件 AOF 写入与 rewrite

## Decisions

### D1: 复制状态机改造采用"显式 PSYNC 状态 + 逐条等待响应"

**决策**：新增 `HANDSHAKE_PSYNC` 状态，`startPsync()` 发送 PSYNC 前先把状态切到 `HANDSHAKE_PSYNC`，`handleResponse` switch 新增 case 路由到 `handlePsyncResponse`。同时改造 `sendReplConf()` 从"同步串发 3 条"改为"发一条等一条 +OK 响应"，避免 Netty 异步下发导致响应顺序错位。

**为什么**：当前 `sendReplConf()` 一次性提交 3 条 REPLCONF 命令到 Netty pipeline，状态机用单线程 `state.set()` 无法匹配异步响应到达顺序。`handleReplconfResponse` 对 3 条 REPLCONF 共用，无法区分是哪条的响应。新增 PSYNC 状态 + 逐条等待是最小改动且语义清晰。

**备选方案**：
- (A) 用响应内容区分（`+FULLRESYNC` 路由到 psync handler）：脆弱，master 实现变化会破坏
- (B) 引入 CompletableFuture 给每条命令配 future：过度工程化，复制握手是线性流程不需要异步编排

**采纳**：显式状态 + 逐条等待，最贴合 Redis 原生 `syncWithMaster` 状态机语义。

### D2: Full sync 窗口期重放用"快照基准 offset + RDB 完成后 backlog 重放"

**决策**：在 `handlePsync` 全量同步分支记录 `snapshotBaseOffset = backlog.getMasterReplOffset()`（RDB 生成时刻的 master offset）。`performFullSync` 的异步任务在 RDB 传输完成后、setState(ONLINE) 之前，调用 `backlog.getBacklogData(snapshotBaseOffset)` 重放窗口期命令。重放期间保持 slave `SLAVE_FLAG_SYNCING`，避免 `propagateCommand` 并发直发导致乱序。重放完成后 setState(ONLINE)，后续命令走正常 `propagateCommand` 路径。

**为什么**：Redis 原生用 `slave.flag` 的 `SLAVE_FLAG_RDB` + backlog 锁实现，本质就是"快照点之后的 backlog 重放"。本实现已有 `ReplicationBacklog.getBacklogData(offset)` 方法（201 行），只需调用。

**风险**：重放期间 master 又产生新命令 -> 这些命令已在 backlog 中，会被 `getBacklogData` 一次性重放（因为重放是读快照时刻到当前 offset 的全部）。若重放耗时较长，新命令持续累积，重放列表会增长。Redis 用"重放完成后再次读 backlog 至最新"的循环处理，本实现采用单次重放 + 后续走 propagateCommand 的简化方案，接受重放期间的小窗口延迟。

**备选方案**：
- (A) 不重放，依赖 slave 发送 `REPLCONF ACK` 后 master 重新发送缺失命令：复杂，且 C2 修复前 ACK 发不出
- (B) 全量同步期间不写 backlog，RDB 完成后从 offset 0 开始：破坏 backlog 语义，影响其他 slave

### D3: SLAVEOF 通过 setReplicationCoordinator setter 注入解决

**决策**：`ReplicationCommandHandler` 新增 `setReplicationCoordinator(ReplicationCoordinator coordinator)` setter（或扩展构造方法）。`ReplicationCoordinator`（server:105）构造 `ReplicationCommandHandler` 后立即 setter 注入自身。`handleSlaveof` 解析 `host port` 后调用 `coordinator.startSlave(address)`；`SLAVEOF NO ONE` 调用 `coordinator.stopSlave()`。

**为什么**：经 pom 核实，`ReplicationCommandHandler` 由 `ReplicationCoordinator`（server 模块）构造（`ReplicationCoordinator.java:105`），server 依赖 replication（无循环依赖）。当前构造方法 `ReplicationCommandHandler(RdsConfig config)` 只接收 config，需补充 coordinator 引用。用 setter 注入避免改构造签名破坏现有测试（`ReplicationCommandHandlerTest:40,78` 直接 `new ReplicationCommandHandler(config)`），且 `ReplicationCoordinator` 构造后即可注入。

`ReplicationCoordinator` 已封装 `startSlave`/`stopSlave`（92-113、212、237 行调用点），且支持 `host:port` 和 `host port` 两种格式（`normalizeAddress`）。`ReplicationCommandHandler` 缺的只是引用注入。

**备选方案**：
- (A) 在 `ReplicationCommandHandler` 内重新实现 slave 启动逻辑：代码重复，破坏封装
- (B) 扩展构造方法加 coordinator 参数：破坏现有 `new ReplicationCommandHandler(config)` 测试调用
- (C) 把 `ReplicationCoordinator` 下沉到 replication 模块：server 的大量逻辑（NettyServer 装配）依赖它在 server，下沉代价大

**采纳**：setter 注入，最小侵入且不破坏测试。

```

Full source: openspec/changes/fix-p0-data-safety-redis7/design.md

## openspec/changes/fix-p0-data-safety-redis7/tasks.md

- Source: openspec/changes/fix-p0-data-safety-redis7/tasks.md
- Lines: 1-98
- SHA256: 8db9399f99b49850717075412156722e85a76724fdfa30f431b9601330112648

[TRUNCATED]

```md
# Tasks: fix-p0-data-safety-redis7

按依赖顺序分 4 个批次推进。每批次内任务可并行，批次间存在依赖。

## 1. 批次 A：复制子系统（C2/C4/C5/C6，存在强依赖链）

C2 是上游根因，必须最先修；C5 依赖 C2 的 offset 解析；C4 相对独立；C6 是 C2+C5 的下游验证。

- [ ] 1.1 新增 `ReplicationState.HANDSHAKE_PSYNC` 状态枚举值，并在状态机文档/注释中更新 DISCONNECTED -> ... -> HANDSHAKE_PSYNC -> FULL_SYNC/PARTIAL_SYNC -> ONLINE 流转
- [ ] 1.2 改造 `SlaveReplicationClient.sendReplConf()`：从同步串发 3 条改为"发一条 REPLCONF -> 等待 +OK -> 发下一条"，每条成功后再推进状态
- [ ] 1.3 改造 `SlaveReplicationClient.startPsync()`：发送 PSYNC 前先把状态切到 `HANDSHAKE_PSYNC`
- [ ] 1.4 `SlaveReplicationClient.handleResponse` switch 新增 `case HANDSHAKE_PSYNC -> handlePsyncResponse`，激活死代码
- [ ] 1.5 完善 `handlePsyncResponse`：解析 `+FULLRESYNC <replid> <offset>` 触发 `callback.onFullSync(replid, offset)` 并进入 `FULL_SYNC`；解析 `+CONTINUE [replid]` 触发 `callback.onPartialSync(replid)` 并进入 `PARTIAL_SYNC`
- [ ] 1.6 验证 `callback.onOnline()` 在 `handleSyncData` 完成 RDB/backlog 加载后被调用，slave 进入 `ONLINE` 状态
- [ ] 1.7 验证 `sendAck()` 在 slave ONLINE 后被心跳调度器周期调用，发送 `REPLCONF ACK <offset>`
- [ ] 1.8 重新启用 `ReplicationIntegrationTest`（移除 `@Disabled`），修复因状态机改造导致的测试失败
- [ ] 1.9 `ReplicationCommandHandler` 新增 `setReplicationCoordinator(ReplicationCoordinator)` setter 方法
- [ ] 1.10 `ReplicationCoordinator` 构造 `ReplicationCommandHandler` 后调用 setter 注入自身
- [ ] 1.11 `ReplicationCommandHandler.handleSlaveof` 实现 `SLAVEOF host port`：解析参数调用 `coordinator.startSlave(address)`；`SLAVEOF NO ONE` 调用 `coordinator.stopSlave()`
- [ ] 1.12 在 `MasterReplicationManager.handlePsync` 全量同步分支记录 `snapshotBaseOffset = backlog.getMasterReplOffset()`
- [ ] 1.13 `MasterReplicationManager.performFullSync` RDB 传输完成后、`setState(ONLINE)` 之前，调用 `backlog.getBacklogData(snapshotBaseOffset)` 重放窗口期命令
- [ ] 1.14 重放期间保持 slave `SLAVE_FLAG_SYNCING` 状态，避免 `propagateCommand` 并发直发；重放完成后才 `setState(ONLINE)`
- [ ] 1.15 验证 `MasterReplicationManager` 的 `slave.updateOffset` 在 `REPLCONF ACK` 分支（161 行）正确更新，`getSyncedSlavesCount` 返回真实值
- [ ] 1.16 验证 `WAIT` 命令基于真实 slave offset 统计已同步副本数（`slave.getOffset() >= currentOffset`）
- [ ] 1.17 验证 `INFO replication` 的 `slave0:...,offset=<n>` 反映真实偏移量

## 2. 批次 B：持久化子系统（C3/C10/C11，C3 是 C11 前提）

C3 必须先修让 AOF 写入链路通起来；C10 独立；C11 依赖 C3 的接口扩展。

- [ ] 2.1 `PersistService` 接口新增 `default void recordCommand(String command, String[] args) {}` 默认空实现
- [ ] 2.2 `AofPersistService` override `recordCommand`，修复方法体使用 `command` 作为 args[0] 写入 RESP 数组
- [ ] 2.3 在 `RedisServerHandler` 命令执行后（响应写出前）插入 `persistService.recordCommand(cmd, args)` 调用，用写命令白名单过滤读命令
- [ ] 2.4 `SELECT` 不记录到 AOF（加载侧维护当前 db 上下文）；`FLUSHALL`/`FLUSHDB` 记录
- [ ] 2.5 `CommonCommandHandler.handleBgrewriteaof` 接入 `aofPersistService.rewrite(memoryStore)`，真正触发重写
- [ ] 2.6 编写 AOF 写入集成测试：SET 命令后 AOF 文件含对应 RESP；重启加载恢复数据
- [ ] 2.7 `RdbPersistService` 新增 `RDB_OPCODE_EXPIRETIME_MS = (byte) 0xFC` 和 `RDB_OPCODE_EXPIRETIME = (byte) 0xFD` 常量
- [ ] 2.8 `writeKeyValue` 每个 value 写完后，若 `memoryStore.ttl(db, key) > 0`，计算 `expireAt = System.currentTimeMillis() + pttl`，按规则写 0xFD（整秒且<1h）或 0xFC（毫秒）
- [ ] 2.9 `readKeyValue` peek 下一个 byte，若是 0xFC/0xFD 读取时间戳，换算 `remaining = expireAt - now`，<=0 不加载，否则 `pexpire(db, key, remaining)`
- [ ] 2.10 加载侧无 expire opcode 时按永久键加载（向后兼容旧格式）
- [ ] 2.11 编写 RDB TTL 测试：SET EX 后 RDB 持久化+重启恢复 TTL；已过期键不复活；旧格式向后兼容
- [ ] 2.12 `AofPersistService.writeKeyValueCommand` 重构为 `writeRebuildCommand`，按 `memoryStore.type(db, key)` 分支：string->SET、list->RPUSH、set->SADD、hash->HSET、zset->ZADD
- [ ] 2.13 stream 类型 rewrite：逐条 `XADD` 恢复数据 + `XGROUP CREATE` 恢复消费者组 + 扫描 PEL 结构逐条 `XCLAIM` 完整恢复 PEL（与 RDB 侧 `writeStream` 逻辑对齐）
- [ ] 2.14 带 TTL 的键在重建命令后追加 `PEXPIREAT key <timestampMs>`
- [ ] 2.15 所有字节数据用 ISO-8859-1 编码保证二进制安全
- [ ] 2.16 编写 AOF rewrite 测试：各类型键 rewrite 后重启加载保留类型与数据；带 TTL 键保留过期时间

## 3. 批次 C：集群子系统（C1/C7/C8/C9，相对独立）

四个缺陷互相独立，可并行推进。

- [ ] 3.1 `RedisServerHandler.extractKeyFromCommand` 返回类型从 `String` 改为 `List<String>`，单键命令返回单元素列表
- [ ] 3.2 扩展 `extractKeyFromCommand` 覆盖所有多键命令的键位置：MGET/MSET/MSETNX/DEL/EXISTS/UNLINK/TOUCH/SUNION/SINTER/SDIFF/SMOVE/SDIFFSTORE/SINTERSTORE/SUNIONSTORE/ZUNIONSTORE/ZINTERSTORE/BITOP/SORT STORE
- [ ] 3.3 RENAME/RENAMENX/COPY 源+目标型命令：`extractKeyFromCommand` 返回 [srcKey, dstKey]
- [ ] 3.4 新增 `checkCrossSlot(List<String> keys)`：所有键 hash 到同一 slot 否则返回 `-CROSSSLOT Keys in request don't hash to the same slot`
- [ ] 3.5 `checkSlotAndRedirect` 改为接受 `List<String>`：先 CROSSSLOT 校验，再校验首键是否在本节点（MOVED）
- [ ] 3.6 保持 `EVAL`/`EVALSHA` 的 `checkCrossSlotForScript` 逻辑不变（向后兼容）
- [ ] 3.7 非集群模式（`cluster-enabled no`）跳过 CROSSSLOT 校验
- [ ] 3.8 编写 CROSSSLOT 测试：MGET/MSET/DEL 跨槽被拒；同槽正常；RENAME 源目标不同槽被拒；EVAL 校验不变；非集群模式不校验
- [ ] 3.9 新增 `MigrateKeysMessage` 批量键消息类，包含所有键的 dump 数据
- [ ] 3.10 `MigrateCommandHandler.migrateMultipleKeys` 改为一次性发送 `MigrateKeysMessage`，目标端批量原子 RESTORE
- [ ] 3.11 目标端全部 ACK 后源端统一 DEL；任一失败源端不删，返回 `-ERR partial migration`
- [ ] 3.12 单消息 64MB 上限校验，超限返回 `-ERR command keys batch too large`
- [ ] 3.13 COPY 模式不删除源
- [ ] 3.14 编写 MIGRATE 原子性测试：全成功删源；部分失败源不删；COPY 模式不删；超限拒绝
- [ ] 3.15 `ReplicationLifecycleListener` 接口新增 `long getReplicationOffset()` 方法
- [ ] 3.16 `ReplicationCoordinator` 实现 `getReplicationOffset()` 返回真实 master_repl_offset
- [ ] 3.17 `FailoverManager` 构造 `FailoverAuthRequestMessage` 时第 4 参数填入 `listener.getReplicationOffset()`
- [ ] 3.18 `tryStartElection` 退避计算改为基于 replOffset rank：`delay = gracePeriod + rank * 500ms`
- [ ] 3.19 `onAuthRequest` 同纪元多候选时比较 `replicationOffset`，offset 大者优先获票
- [ ] 3.20 编写 Failover 偏移量选举测试：偏移量大者优先；已投票后拒绝同纪元其他候选
- [ ] 3.21 将 `busClient.broadcast(FailoverResultMessage)` 从 `performFailoverAndBroadcast` 下沉到 `performFailover` 共用方法内
- [ ] 3.22 `performManualFailover` 补 `masterNode.setConfigEpoch(currentEpoch)` 对齐自动路径
- [ ] 3.23 移除 `performFailoverAndBroadcast` 内的重复广播（避免自动路径广播两次）
- [ ] 3.24 验证 `onFailoverResult` 的幂等/纪元裁决能正确处理（重复广播安全）
- [ ] 3.25 编写手动 failover 广播测试：FORCE/TAKEOVER 广播 FailoverResult；原 master configEpoch 对齐；自动路径不重复广播

## 4. 批次 D：核心数据结构（C12，独立）

- [ ] 4.1 `DefaultMemoryStore.ZSetStore.scoreMembers` 值类型从 `ConcurrentHashMap.KeySetView<String, Boolean>` 改为 `ConcurrentSkipListSet<String>`
```

Full source: openspec/changes/fix-p0-data-safety-redis7/tasks.md

## openspec/changes/fix-p0-data-safety-redis7/specs/cluster-automatic-failover/spec.md

- Source: openspec/changes/fix-p0-data-safety-redis7/specs/cluster-automatic-failover/spec.md
- Lines: 1-58
- SHA256: ffe2f9ccc8b8c91e2434fc3ecfae7446e13871d383a52b991d994fb46fcf00de

```md
## ADDED Requirements

### Requirement: Failover 选举使用复制偏移量 tiebreak

Slave 发起故障转移选举时，`FailoverAuthRequestMessage` MUST 携带本节点真实的复制偏移量（`master_repl_offset`），而非硬编码 0。`ReplicationLifecycleListener` 接口 MUST 新增 `getReplicationOffset()` 方法供 `FailoverManager` 获取本节点偏移量。Slave 发起选举的退避延迟 MUST 基于复制偏移量 rank 计算：`delay = gracePeriod + rank * 500ms`，其中 rank 为本节点在所有同 master 的 slave 中按偏移量升序的排名（偏移量最大者 rank=0，最先发起选举）。Master 节点投票时，若同一选举纪元收到多个 slave 的 AUTH_REQUEST，MUST 优先投票给复制偏移量最大的 slave（数据最新）。

#### Scenario: 偏移量最新的 slave 优先选举

- **WHEN** Master M 宕机，其下有 slave S1（offset=1000）和 S2（offset=800）
- **THEN** S1 的 rank=0，退避延迟更短，先发起 AUTH_REQUEST
- **AND** S1 的 AUTH_REQUEST 携带 replicationOffset=1000

#### Scenario: Master 投票偏好偏移量大者

- **WHEN** 同一选举纪元内，master（其他健康 master）先后收到 S1（offset=1000）和 S2（offset=800）的 AUTH_REQUEST
- **AND** master 尚未投票
- **THEN** master 投票给 S1（偏移量大者优先）

#### Scenario: 已投票后拒绝同纪元其他候选

- **WHEN** master 已对 S1（offset=1000）投票
- **AND** 同纪元又收到 S2（offset=800）的 AUTH_REQUEST
- **THEN** master 拒绝投票给 S2

#### Scenario: getReplicationOffset 接口可用

- **WHEN** `FailoverManager` 构造 AUTH_REQUEST 需要本节点偏移量
- **THEN** 通过注入的 `ReplicationLifecycleListener.getReplicationOffset()` 获取
- **AND** `ReplicationCoordinator` 实现该方法返回真实 master_repl_offset

### Requirement: 手动 failover 广播 FailoverResult

`CLUSTER FAILOVER`、`CLUSTER FAILOVER FORCE`、`CLUSTER FAILOVER TAKEOVER` 三种手动 failover 模式执行 `performFailover` 后，MUST 广播 `FailoverResultMessage` 通知全网拓扑变更，使其他节点立即收敛而非等待 gossip 传播。广播职责 MUST 收敛到 `performFailover` 共用方法内，避免自动 failover 路径重复广播。手动 failover 还 MUST 对齐自动路径，补 `masterNode.setConfigEpoch(currentEpoch)`，使原 master 的 configEpoch 与新主一致。

#### Scenario: 手动 failover 后全网立即收敛

- **WHEN** Slave S 执行 `CLUSTER FAILOVER FORCE` 成功提升为 master
- **THEN** S 广播 `FailoverResultMessage(winner=S, newConfigEpoch, slots)`
- **AND** 其他节点收到后立即更新拓扑（S 为新 master，原 master M 降为 slave）
- **AND** 后续请求被 MOVED 到 S 而非 M

#### Scenario: TAKEOVER 也广播

- **WHEN** Slave S 执行 `CLUSTER FAILOVER TAKEOVER`（不经选举授权）
- **THEN** S 仍广播 `FailoverResultMessage`
- **AND** 其他节点收到后接受新拓扑（基于 configEpoch 仲裁）

#### Scenario: 原 master configEpoch 对齐

- **WHEN** 手动 failover 提升 slave S
- **THEN** 原 master M 的 `configEpoch` 被设为 `clusterConfig.getCurrentEpoch()`
- **AND** 与 S 的 configEpoch 一致，避免 gossip 收敛时 epoch 冲突

#### Scenario: 自动 failover 不重复广播

- **WHEN** 自动 failover 走 `performFailoverAndBroadcast` 路径
- **THEN** `performFailover` 内广播一次 FailoverResult
- **AND** `performFailoverAndBroadcast` 不再单独广播（避免两次）
```

## openspec/changes/fix-p0-data-safety-redis7/specs/cluster-slot-integrity/spec.md

- Source: openspec/changes/fix-p0-data-safety-redis7/specs/cluster-slot-integrity/spec.md
- Lines: 1-67
- SHA256: 2a551878437d8043375ab05091d9533656d4cae26eac6f9366976446c42cb947

```md
## ADDED Requirements

### Requirement: 集群模式下多键命令的 CROSSSLOT 校验

集群模式（`cluster-enabled yes`）下，所有涉及多个键的命令 MUST 在执行前校验全部键落在同一 hash slot，否则 MUST 返回 `-CROSSSLOT Keys in request don't hash to the same slot`。校验覆盖的命令包括但不限于：`MGET`、`MSET`、`MSETNX`、`DEL`、`EXISTS`、`UNLINK`、`TOUCH`、`SUNION`、`SINTER`、`SDIFF`、`SMOVE`、`SDIFFSTORE`、`SINTERSTORE`、`SUNIONSTORE`、`ZUNIONSTORE`、`ZINTERSTORE`、`BITOP`、`SORT ... STORE`。对于源+目标型命令（`RENAME`、`RENAMENX`、`COPY`），MUST 同时校验源键和目标键落在同一 slot。`EVAL`/`EVALSHA` 的 CROSSSLOT 校验（按 `numkeys` 遍历 KEYS）保持现有行为不变。

#### Scenario: MGET 跨槽被拒绝

- **WHEN** 集群模式下客户端发送 `MGET key1 key2`，且 `key1` 与 `key2` 落在不同 hash slot
- **THEN** 系统返回 `-CROSSSLOT Keys in request don't hash to the same slot`
- **AND** 不执行任何键的读取

#### Scenario: MSET 同槽正常执行

- **WHEN** 集群模式下客户端发送 `MSET {tag}k1 v1 {tag}k2 v2`，两键通过 hash tag 落在同一 slot
- **THEN** 命令正常执行并返回 `+OK`

#### Scenario: DEL 多键跨槽被拒绝

- **WHEN** 集群模式下客户端发送 `DEL key1 key2 key3`，其中 `key2` 落在不同 slot
- **THEN** 系统返回 `-CROSSSLOT`
- **AND** 不删除任何键

#### Scenario: RENAME 源目标不同槽被拒绝

- **WHEN** 集群模式下客户端发送 `RENAME srcKey dstKey`，两键落在不同 slot
- **THEN** 系统返回 `-CROSSSLOT`
- **AND** 不执行重命名

#### Scenario: EVAL 的 CROSSSLOT 校验保持不变

- **WHEN** 集群模式下客户端发送 `EVAL script 2 key1 key2`，两键不同 slot
- **THEN** 系统返回 `-CROSSSLOT`（沿用现有 `checkCrossSlotForScript` 逻辑）

#### Scenario: 非集群模式不校验 CROSSSLOT

- **WHEN** 非集群模式（`cluster-enabled no`）下客户端发送 `MGET key1 key2`，两键不同 slot 也无所谓
- **THEN** 命令正常执行，不返回 CROSSSLOT 错误

### Requirement: MIGRATE 多键迁移的原子性

`MIGRATE host port "" dest-db timeout [COPY] [REPLACE] KEYS k1 k2 ...` 多键迁移 MUST 保证原子性：要么全部键成功迁移到目标节点，要么全部不迁移（源端不删除）。源端 DEL 操作 MUST 在目标端全部键 ACK 成功后统一执行。任一阶段失败时，源端 MUST NOT 删除已 dump 的键，由调用方决定重试。单条批量迁移消息大小 MUST 限制在 64MB 以内，超限时返回 `-ERR command keys batch too large` 并提示分批。

#### Scenario: 全部成功迁移并删除源

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 KEYS k1 k2 k3`，目标端全部 RESTORE 成功
- **THEN** 返回 `+OK`
- **AND** 源端 k1、k2、k3 被统一删除（在全部 ACK 后）

#### Scenario: 部分失败时源端不删除

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 KEYS k1 k2 k3`，目标端 k2 RESTORE 失败
- **THEN** 返回 `-ERR partial migration: 2 succeeded, 1 failed` 或类似错误
- **AND** 源端 k1、k2、k3 均不被删除（即使 k1、k3 在目标端已落地）
- **AND** 调用方可重试整个 MIGRATE（目标端 RESTORE 幂等，REPLACE 模式覆盖）

#### Scenario: COPY 模式不删除源

- **WHEN** 客户端发送 `MIGRATE host port "" 0 5000 COPY KEYS k1 k2`，目标端全部成功
- **THEN** 返回 `+OK`
- **AND** 源端 k1、k2 保留不删除

#### Scenario: 批量消息超限被拒绝

- **WHEN** 客户端发送的 KEYS 列表 dump 总大小超过 64MB
- **THEN** 返回 `-ERR command keys batch too large`
- **AND** 不发起任何网络传输，源端不删除
```

## openspec/changes/fix-p0-data-safety-redis7/specs/memory-store/spec.md

- Source: openspec/changes/fix-p0-data-safety-redis7/specs/memory-store/spec.md
- Lines: 1-48
- SHA256: 2269cc5b968fd091620b8afef9f95a5a59866b5d917a71980156e687b8d0ab6e

```md
## ADDED Requirements

### Requirement: ZSet 同分成员按字典序排序

ZSet 中分数相同的成员，MUST 按成员名的字典序（lexicographic，按 unsigned byte 比较）排序。此顺序适用于所有按 score 范围或排名返回成员的命令，包括：`ZRANGE`、`ZRANK`、`ZREVRANGE`、`ZREVRANK`、`ZRANGEBYSCORE`、`ZREVRANGEBYSCORE`、`ZPOPMIN`、`ZPOPMAX`、`ZSCAN`、`ZREMRANGEBYRANK`。对于正向命令（`ZRANGE`、`ZRANK`、`ZPOPMIN`），同分成员按字典序**升序**；对于反向命令（`ZREVRANGE`、`ZREVRANK`、`ZPOPMAX`），同分成员按字典序**降序**。`ZPOPMIN` 在同分时 MUST 弹出字典序最小的成员；`ZPOPMAX` 在同分时 MUST 弹出字典序最大的成员。ZSet 内部存储同分成员集合的数据结构 MUST 使用并发安全的字典序结构（如 `ConcurrentSkipListSet<String>`），保证多线程读写下的排序正确性。

#### Scenario: ZRANGE 同分成员字典序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0`（同分）
- **AND** 客户端执行 `ZRANGE myzset 0 -1`
- **THEN** 返回 `a, b, c`（字典序升序）

#### Scenario: ZREVRANGE 同分成员反向字典序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0`（同分）
- **AND** 客户端执行 `ZREVRANGE myzset 0 -1`
- **THEN** 返回 `c, b, a`（字典序降序）

#### Scenario: ZPOPMIN 同分弹字典序最小

- **WHEN** ZSet `myzset` 有成员 `banana=2.0, apple=2.0, cherry=2.0`（同分）
- **AND** 客户端执行 `ZPOPMIN myzset 1`
- **THEN** 弹出 `apple`（同分中字典序最小）

#### Scenario: ZPOPMAX 同分弹字典序最大

- **WHEN** ZSet `myzset` 有成员 `banana=2.0, apple=2.0, cherry=2.0`（同分）
- **AND** 客户端执行 `ZPOPMAX myzset 1`
- **THEN** 弹出 `cherry`（同分中字典序最大）

#### Scenario: ZRANK 同分成员排名正确

- **WHEN** ZSet `myzset` 有成员 `a=1.0, c=1.0, b=1.0, d=2.0`
- **AND** 客户端执行 `ZRANK myzset b`
- **THEN** 返回 `:1`（b 在同分组 a,b,c 中排第 2，rank=1）

#### Scenario: ZINCRBY 改分后同分组重新排序

- **WHEN** ZSet `myzset` 有成员 `a=1.0, b=1.0`
- **AND** 客户端执行 `ZINCRBY myzset 0 c`（新增 c=1.0）
- **THEN** 同分组变为 `a, b, c`（字典序）
- **AND** `ZRANGE myzset 0 -1` 返回 `a, b, c`

#### Scenario: 多线程并发 ZADD 排序正确

- **WHEN** 多个线程并发对同一 ZSet 执行 `ZADD` 添加同分成员
- **THEN** 最终 `ZRANGE` 返回的同分成员仍按字典序
- **AND** 不抛 `ConcurrentModificationException`
```

## openspec/changes/fix-p0-data-safety-redis7/specs/persistence-data-integrity/spec.md

- Source: openspec/changes/fix-p0-data-safety-redis7/specs/persistence-data-integrity/spec.md
- Lines: 1-127
- SHA256: 9c0e264b7914aa440fff35140766c0855b4ce5af01044630647f10eb23306881

[TRUNCATED]

```md
## ADDED Requirements

### Requirement: AOF 写入接入命令分发路径

所有写命令（修改数据的命令）执行后，系统 MUST 通过 `PersistService.recordCommand(byte[] respFrame)` 将命令的原始 RESP 字节记录到 AOF（当 `appendonly yes` 时）。`respFrame` 是命令的原始 RESP 序列化字节，与复制传播使用的 `rawRespFrame` 是同一份数据，保证二进制安全且与复制完全一致。读命令（如 `GET`、`EXISTS`、`TTL`、`TYPE`、`SCAN`）MUST NOT 被记录。`SELECT` 命令 MUST 被记录到 AOF 作为 db 上下文标记（与 Redis 一致），加载 AOF 时按 SELECT 切换当前 db，后续命令加载到对应 db。`FLUSHALL`/`FLUSHDB` MUST 被记录。`PersistService` 接口 MUST 提供 `recordCommand(byte[] respFrame)` 的 default 空实现，使非 AOF 实现（如 `RdbPersistService`）无需修改。AOF 写入 MUST 复用命令分发层的 `shouldPropagate` 判定（已有 `isReadOnlyCommand` 白名单），保证 AOF 记录与复制传播的写命令集合一致。

#### Scenario: 写命令被记录到 AOF

- **WHEN** `appendonly yes` 时客户端执行 `SET mykey hello`
- **THEN** AOF 文件追加 `SET mykey hello` 的原始 RESP 字节（`*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$5\r\nhello\r\n`）
- **AND** 后续重启加载 AOF 能恢复 mykey=hello

#### Scenario: 读命令不记录

- **WHEN** 客户端执行 `GET mykey`
- **THEN** AOF 文件不追加任何内容

#### Scenario: SELECT 记录为 db 上下文标记

- **WHEN** 客户端执行 `SELECT 1` 后 `SET k1 v1`
- **THEN** AOF 记录 `SELECT 1` 的 RESP 字节（`*2\r\n$6\r\nSELECT\r\n$1\r\n1\r\n`）
- **AND** AOF 记录 `SET k1 v1` 的 RESP 字节
- **AND** 加载 AOF 时，先 SELECT 到 db 1，k1 被加载到 db 1

#### Scenario: FLUSHALL 被记录

- **WHEN** 客户端执行 `FLUSHALL`
- **THEN** AOF 文件追加 `FLUSHALL` 的 RESP 字节
- **AND** 重启加载后所有 db 为空

#### Scenario: 非 AOF 模式不记录

- **WHEN** `appendonly no` 时客户端执行 `SET mykey hello`
- **THEN** AOF 文件不追加任何内容（`recordCommand` 为 no-op）

#### Scenario: AOF 记录与复制传播一致

- **WHEN** 写命令执行后，`shouldPropagate` 返回 true
- **THEN** 该命令既被 `propagateCommand` 传播到复制 backlog，也被 `recordCommand` 记录到 AOF
- **AND** 两者使用同一份 `rawRespFrame` 字节，保证数据一致

### Requirement: RDB 持久化保存键的 TTL

RDB 序列化时，对于设置了过期时间的键，MUST 在键值对之后写入 expire opcode 和绝对过期时间戳。剩余 TTL 换算为绝对时间戳：`expireAt = System.currentTimeMillis() + pttl`。剩余 TTL < 3600000ms（1 小时）且为整秒时，使用 `0xFD` opcode + 4 字节秒级时间戳（小端序）；否则使用 `0xFC` opcode + 8 字节毫秒级时间戳（小端序）。加载 RDB 时，MUST 识别 0xFC/0xFD opcode，读取时间戳并换算回剩余 TTL（`remaining = expireAt - now`），若 `remaining <= 0` 则不加载该键（已过期），否则调用 `pexpire(db, key, remaining)` 恢复 TTL。无 expire opcode 的键按永久键加载（向后兼容旧格式）。

#### Scenario: 带 TTL 的键重启后保留过期时间

- **WHEN** 客户端执行 `SET k1 v1 EX 3600` 后触发 RDB 持久化
- **AND** 重启加载 RDB
- **THEN** k1 仍存在且 TTL 约为 3600 秒（扣除持久化到重启的耗时）

#### Scenario: 已过期键不复活

- **WHEN** RDB 持久化时键 k1 的剩余 TTL 为 1 秒
- **AND** 重启加载 RDB 时已过去 5 秒（`expireAt < now`）
- **THEN** k1 不被加载到内存

#### Scenario: 永久键向后兼容

- **WHEN** 加载不含 expire opcode 的旧 RDB 文件
- **THEN** 键按永久键加载，TTL = -1

#### Scenario: 毫秒级 TTL 用 0xFC

- **WHEN** 键的剩余 TTL 为 1500ms（非整秒）
- **THEN** RDB 写入 `0xFC` opcode + 8 字节毫秒时间戳

#### Scenario: 复制全量同步保留 TTL

- **WHEN** Master 执行 `performFullSync` 生成 RDB 快照，其中包含带 TTL 的键
- **THEN** 传输给 slave 的 RDB 包含 expire opcode
- **AND** slave 加载后该键的 TTL 被恢复

### Requirement: AOF rewrite 按数据类型生成重建命令

AOF rewrite 时，MUST 根据键的数据类型生成对应的重建命令，而非统一用 `SET key toString()`。各类型的重建命令：
- string：`SET key value`
- list：`RPUSH key v1 v2 ...`（一次性追加所有元素）
- set：`SADD key m1 m2 ...`
- hash：`HSET key f1 v1 f2 v2 ...`
```

Full source: openspec/changes/fix-p0-data-safety-redis7/specs/persistence-data-integrity/spec.md

## openspec/changes/fix-p0-data-safety-redis7/specs/replication-sync-state-machine/spec.md

- Source: openspec/changes/fix-p0-data-safety-redis7/specs/replication-sync-state-machine/spec.md
- Lines: 1-108
- SHA256: 0900fef0607e0d79dddb5db219bb4aaccd9c5f0889ad441a71ecee1ae6b8ba8a

[TRUNCATED]

```md
## ADDED Requirements

### Requirement: Slave 复制握手的 PSYNC 响应路由

Slave 发送 `PSYNC` 命令后，MUST 进入 `HANDSHAKE_PSYNC` 状态，并将 `+FULLRESYNC`/`+CONTINUE` 响应路由到 PSYNC 专用处理逻辑，而非 `REPLCONF` 通用响应处理器。收到 `+FULLRESYNC <replid> <offset>` 时，MUST 解析 replid 和 offset，触发 `onFullSync` 回调并进入 `FULL_SYNC` 状态。收到 `+CONTINUE [replid]` 时，MUST 解析可选 replid，触发 `onPartialSync` 回调并进入 `PARTIAL_SYNC` 状态。`REPLCONF` 三连发（PORT/IP/CAPA）MUST 改为逐条发送并等待 `+OK` 响应后再发下一条，避免 Netty 异步下发导致响应错位。

#### Scenario: Full sync 握手完整走通

- **WHEN** Slave 连接 master，完成 PING/AUTH/REPLCONF 三连发后发送 `PSYNC ? -1`
- **AND** master 返回 `+FULLRESYNC <replid> <offset>` 并开始传输 RDB
- **THEN** Slave 解析 replid 和 offset
- **AND** 触发 `onFullSync(replid, offset)` 回调
- **AND** Slave 进入 `FULL_SYNC` 状态等待 RDB 数据

#### Scenario: Partial sync 握手完整走通

- **WHEN** Slave 发送 `PSYNC <replid> <offset>`，master 返回 `+CONTINUE`
- **THEN** Slave 触发 `onPartialSync(replid)` 回调
- **AND** Slave 进入 `PARTIAL_SYNC` 状态接收 backlog 增量数据

#### Scenario: REPLCONF 逐条等待响应

- **WHEN** Slave 发送 `REPLCONF listening-port <port>`
- **THEN** Slave 等待 master 返回 `+OK` 后才发送下一条 `REPLCONF ip-address <ip>`
- **AND** 三条 REPLCONF 全部 `+OK` 后才发送 PSYNC

#### Scenario: Slave 进入 ONLINE 状态

- **WHEN** Full sync 的 RDB 加载完成（或 partial sync 的 backlog 重放完成）
- **THEN** Slave 调用 `callback.onOnline()`
- **AND** Slave 进入 `ONLINE` 状态
- **AND** 开始周期性发送 `REPLCONF ACK <offset>` 心跳

### Requirement: Full sync 窗口期命令缓冲与重放

Master 在执行 `performFullSync` 期间，MUST 记录 RDB 快照生成时刻的 `snapshotBaseOffset`（即当时的 `backlog.getMasterReplOffset()`）。RDB 传输完成后、slave 进入 ONLINE 之前，MUST 从 backlog 重放 `snapshotBaseOffset` 到当前 master offset 之间的窗口期命令。重放期间 slave MUST 保持 `SLAVE_FLAG_SYNCING` 状态，避免 `propagateCommand` 并发直发导致命令乱序。重放完成后 slave 才进入 ONLINE，后续命令走正常 `propagateCommand` 路径。

#### Scenario: 窗口期写入不丢失

- **WHEN** Master 在 RDB 传输期间收到 `SET k1 v1`、`SET k2 v2` 两个写命令
- **AND** 这些命令被写入 backlog 但 slave 当时处于 SYNCING 未收到
- **AND** RDB 传输完成
- **THEN** Master 从 backlog 重放这两个命令给该 slave
- **AND** slave 最终包含 k1=v1、k2=v2

#### Scenario: 重放期间不并发直发

- **WHEN** Master 正在重放窗口期命令给 slave（slave 仍 SYNCING）
- **AND** 此时又有新命令 `SET k3 v3` 到达 master
- **THEN** `SET k3 v3` 被写入 backlog 但不直接发给该 slave（因 SYNCING）
- **AND** 该命令在重放循环中被一并重放（重放读到当前 offset）

#### Scenario: 重放完成后转 ONLINE 接收增量

- **WHEN** 窗口期命令重放完成
- **THEN** slave 进入 ONLINE 状态
- **AND** 后续 master 的写命令通过 `propagateCommand` 直接发送

### Requirement: 运行时 SLAVEOF/REPLICAOF 启动复制

运行时执行 `SLAVEOF host port` 或 `REPLICAOF host port` 命令时，MUST 解析 `host` 和 `port` 参数，调用复制协调器的 `startSlave(address)` 真正发起复制连接，而非仅设置只读标志。`SLAVEOF NO ONE` / `REPLICAOF NO ONE` MUST 调用 `stopSlave()` 断开与 master 的复制连接并恢复可写状态。集群模式下（`cluster-enabled yes`）执行 `SLAVEOF host port` 仍 MUST 返回 `-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead`。

#### Scenario: SLAVEOF 启动复制连接

- **WHEN** 客户端发送 `SLAVEOF 192.168.1.10 6379`
- **THEN** 系统调用 `startSlave("192.168.1.10:6379")`
- **AND** 返回 `+OK`
- **AND** 后台开始与 192.168.1.10:6379 建立复制连接

#### Scenario: REPLICAOF 等价于 SLAVEOF

- **WHEN** 客户端发送 `REPLICAOF 192.168.1.10 6379`
- **THEN** 行为与 `SLAVEOF 192.168.1.10 6379` 完全一致

#### Scenario: SLAVEOF NO ONE 断开复制

- **WHEN** 客户端发送 `SLAVEOF NO ONE`，且当前节点是 slave
- **THEN** 系统调用 `stopSlave()` 断开与 master 的连接
- **AND** 节点恢复可写状态（清除只读标志）
- **AND** 返回 `+OK`
```

Full source: openspec/changes/fix-p0-data-safety-redis7/specs/replication-sync-state-machine/spec.md

