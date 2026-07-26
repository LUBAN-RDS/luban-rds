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

### D4: AOF 写入接入采用"PersistService 接口扩展 + 命令分发后置钩子"

**决策**：
1. `PersistService` 接口新增 `default void recordCommand(String command, String[] args) {}` 默认空实现（非 AOF 实现为 no-op）
2. `AofPersistService` override 该方法，修复方法体使用 `command` 参数（作为 args[0]）
3. 在 `RedisServerHandler` 命令执行后（响应写出前）插入 `persistService.recordCommand(cmd, args)` 调用，仅对写命令触发（用 `commandRequiresKey` 或白名单过滤读命令）
4. `BGREWRITEAOF` 空壳接入 `aofPersistService.rewrite(memoryStore)`

**为什么**：`PersistService` 是命令处理层已持有的引用（通过 `NettyRedisServer` 注入），接口扩展是最小侵入。default 方法避免破坏 `RdbPersistService`/`CompositePersistService` 等现有实现。

**备选方案**：
- (A) 新增独立的 `AofRecorder` 接口单独注入：增加一个依赖，但 `CompositePersistService` 已实现组合模式，用 `PersistService` 接口更统一
- (B) 在每个 CommandHandler 内调用：散落各处，易遗漏

**过滤策略**：参考 Redis `propagateNow` 的写命令判定，用 `CommandFlags` 标注或维护写命令集合，过滤 `GET/EXISTS/TTL/TYPE` 等读命令，`SELECT/FLUSHALL/FLUSHDB` 特殊处理（SELECT 不记录到 AOF，FLUSHALL 记录）。

### D5: RDB TTL 用 Redis 标准 opcode 但保留自研长度编码

**决策**：
1. `writeKeyValue` 每个 value 写完后，若 `memoryStore.ttl(db, key) > 0`，写入 `0xFD`（秒，4 字节小端）或 `0xFC`（毫秒，8 字节小端），内容为**绝对过期时间戳**（`System.currentTimeMillis() + remaining`）
2. 加载侧 `readKeyValue` 读 value 后 peek 下一个 byte，若是 0xFC/0xFD 则读取时间戳，换算回剩余 TTL 调用 `memoryStore.pexpire(db, key, remainingMs)`
3. 选择秒 vs 毫秒：剩余 TTL < 1 小时且为整秒用 0xFD（省 4 字节），否则用 0xFC

**为什么**：Redis 标准 RDB 用 0xFC/0xFD opcode，语义清晰。绝对时间戳避免"剩余 TTL 在序列化期间衰减"问题。保留自研长度编码是因为改造为 Redis 标准长度编码属于"RDB 格式兼容"（审计 P2），超出 P0 范围。

**备选方案**：
- (A) 全部用 0xFC 毫秒：简单但浪费 4 字节/键
- (B) 自定义 opcode：与 Redis 不兼容，无收益

### D6: AOF rewrite 按类型生成重建命令

**决策**：`writeKeyValueCommand` 改为 `writeRebuildCommand`，按 `memoryStore.type(db, key)` 分支：
- string: `SET key value`
- list: `RPUSH key v1 v2 ...`（一次性追加所有元素）
- set: `SADD key m1 m2 ...`
- hash: `HSET key f1 v1 f2 v2 ...`
- zset: `ZADD key s1 m1 s2 m2 ...`
- stream: 逐条 `XADD key id field value` + `XGROUP CREATE` 恢复消费者组 + `XPENDING` 详细格式 + `XCLAIM`/`XAUTOCLAIM` 完整恢复 PEL（参考 RDB 侧 `writeStream` 673-744 行）

带 TTL 的键在重建命令后追加 `PEXPIREAT key timestamp`。所有字节用 ISO-8859-1 编码保证二进制安全（与 `recordCommand` 197 行一致）。

**为什么**：Redis 原生 AOF rewrite 就是按类型生成重建命令，这是唯一能保留类型语义的方式。stream 的 PEL 恢复复杂度高，但 RDB 侧已有 `writeStream` 实现可参考复用扫描逻辑。**用户确认 P0 范围内完整恢复 PEL**（不降级为已知限制），因为 PEL 丢失会导致消费者重复消费已分配消息，属于数据一致性问题。

**stream PEL 恢复方案**：rewrite 时对每个消费者组扫描 PEL 结构，获取所有 pending 消息的 `(id, consumer, idleTime, deliveryCount)`，逐条用 `XCLAIM key group consumer idleTime id` 重建 PEL。优先直接访问 store 内部结构（与 RDB 侧 `writeStream` 一致），而非命令模拟。

**风险**：stream PEL 恢复涉及 `XPENDING` 详细格式 + `XCLAIM`/`XAUTOCLAIM` 参数语义，需与 RDB 侧 `writeStream` 的 PEL 序列化逻辑对齐验证，两路径产出须一致。

### D7: CROSSSLOT 用"键提取返回列表 + 校验遍历"

**决策**：
1. `extractKeyFromCommand` 返回 `List<String>`（单键命令返回单元素列表）
2. 新增 `checkCrossSlot(List<String> keys)`：所有键 hash 到同一 slot 否则返回 `-CROSSSLOT`
3. `checkSlotAndRedirect` 改为接受 `List<String>`：先做 CROSSSLOT 校验，再校验首键是否在本节点（MOVED）
4. 为 RENAME/RENAMENX/COPY（源+目标型）同时校验两端 slot，要求源和目标同 slot（Redis 原生要求）

**为什么**：Redis 原生用 `getKeysFromCommand` 返回键位置表，本实现简化为按命令名返回键列表。RENAME/COPY 要求源目标同 slot 是 Redis 强制约束（避免原子操作跨节点）。

**备选方案**：
- (A) 维护命令表（命令名 -> 键位置规格）：更通用但工作量大，留待 P2
- (B) 仅对 `extractKeyFromCommand` 现有的多键分支扩展：最小改动，但 RENAME/COPY 等未特判的命令仍漏

**采纳**：扩展 `extractKeyFromCommand` 覆盖所有多键命令 + 源目标型命令，返回列表。

### D8: MIGRATE 原子化用"批量消息 + 两阶段提交"

**决策**：
1. 新增 `MigrateKeysMessage`（批量键消息），包含所有键的 dump 数据
2. `migrateMultipleKeys` 一次性发送 `MigrateKeysMessage` 到目标端
3. 目标端用单次事务（`MULTI/EXEC` 语义或内存锁）批量 `RESTORE` 所有键
4. 目标端全部 ACK 后，源端统一 DEL 所有键
5. 任一阶段失败：源端不删，返回 `-ERR`，由调用方重试

**为什么**：Redis 原生 MIGRATE 用 `RESTORE-ASKING` 批量 + 原子 DEL，源端 DEL 在目标全部 ACK 后执行，保证"要么全成功要么全失败"。本实现当前逐键发送+删源是半提交，连接中断导致数据分裂。

**风险**：批量消息可能过大（单消息含所有键的 dump）。Redis 用 `MIGRATE ... KEYS` 时也是单次传输，但有限制（`bulk` 大小）。本实现加 64MB 单消息上限，超限则拒绝并提示分批。

### D9: Failover 选举偏移量 tiebreak 通过扩展 ReplicationLifecycleListener 获取

**决策**：
1. `ReplicationLifecycleListener` 接口（cluster 模块）新增 `long getReplicationOffset()` 方法，返回本节点当前复制偏移量（slave 视角的 master_repl_offset）。`ReplicationCoordinator`（server 模块，已实现该接口）override 返回真实值。
2. `FailoverAuthRequestMessage` 第 4 参数填入 `listener.getReplicationOffset()`
3. `tryStartElection` 退避计算改为基于 replOffset rank：`delay = gracePeriod + rank * 500ms`（rank 越大退避越久，offset 最新者 rank=0 先发起）
4. `onAuthRequest` 同纪元多候选时，比较 `replicationOffset`，offset 大者优先获票

**为什么**：经核实，`FailoverManager` 通过 `setReplicationLifecycleListener(replicationCoordinator)` 注入 listener（NettyRedisServer:412），`ReplicationCoordinator` 已实现该接口。`luban-rds-cluster` 只依赖 core，通过 listener 接口反向回调 server 获取偏移量是干净的依赖反转，无循环依赖。`ClusterNode` 无 offset 字段，但本节点偏移量从 `ReplicationCoordinator` 获取即可（不需要存在 ClusterNode 上）。

Redis 原生 `clusterGetSlaveRank` 按 data age（replOffset）排序，offset 最新者优先选举，避免陈旧 slave 胜选丢失已提交写入。当前 `0L` 硬编码使任何 slave 都能赢。

**备选方案**：
- (A) 在 `ClusterNode` 新增 `replOffset` 字段：需 gossip 传播，复杂度高，且本节点偏移量不需存 ClusterNode
- (B) 不改退避，仅改投票比较：可能多个 slave 同时发起选举，靠投票比较择优，但选举冲突率高
- (C) 引入 Raft term：过度设计，当前已是类 Raft 选举

### D10: 手动 failover 广播收敛到 performFailover

**决策**：将 `busClient.broadcast(FailoverResultMessage)` 从 `performFailoverAndBroadcast`（385-390 行）下沉到 `performFailover`（419-452 行）共用方法内。同时补 `masterNode.setConfigEpoch(clusterConfig.getCurrentEpoch())`。移除 `performFailoverAndBroadcast` 内的重复广播（避免自动路径广播两次）。

**为什么**：`performFailover` 被手动/自动路径共用，在其中统一广播最简洁。`onFailoverResult`（462 行）的幂等/纪元裁决逻辑已能正确处理重复广播，所以收敛安全。

**TAKEOVER 语义**：Redis 原生 TAKEOVER 不要求授权但仍通过 gossip 传播新 configEpoch。本实现"接管即广播"与 Redis 原生行为一致，TAKEOVER 与 FORCE/普通模式都广播 FailoverResult。

### D11: ZSet 字典序用 ConcurrentSkipListSet

**决策**：`ZSetStore.scoreMembers` 值类型从 `ConcurrentHashMap.KeySetView<String, Boolean>` 改为 `ConcurrentSkipListSet<String>`（自然字典序，并发安全，弱一致迭代器）。
- `add`/`remove` 维护逻辑复用（移除旧分集合/加入新分集合/空集合清理）
- `zpopmax`/`zrevrange` 用 `descendingSet()` 实现反向字典序
- `zrank` 同分定位改为 `ConcurrentSkipListSet` 的线性扫描（仍 O(n)，但字典序正确；O(log n) rank 属于 P3 性能优化）
- 同步调整 `estimateMemorySize`（237-247 行）的 ZSetStore 估算常量（跳表节点比 CHM 桶节点重）

**为什么**：`ConcurrentSkipListSet` 是 JDK 内置的并发安全字典序集合，与 `ConcurrentSkipListMap<Double, ...>` 的并发模型一致（都是 skip list）。无需引入第三方依赖。`TreeSet` 需要外部锁，与现有无锁设计冲突。

**备选方案**：
- (A) 自定义 skiplist + span（同时解决 ZRANK O(n)）：属于 P3 性能优化，超出 P0 范围
- (B) 用 `Collections.synchronizedSortedSet(new TreeSet<>())`：锁粒度粗，并发性能差

## Risks / Trade-offs

- **[复制状态机改造影响 Netty 异步时序]** -> 逐条等待响应 + 状态机显式切换，新增 `HANDSHAKE_PSYNC` 状态；需重启用 `ReplicationIntegrationTest` 端到端验证
- **[Full sync 重放期间并发新命令]** -> 单次重放 + 后续走 propagateCommand，接受重放期间小窗口延迟；重放期间保持 SYNCING 避免乱序
- **[SLAVEOF 跨模块依赖]** -> 需确认 `luban-rds-replication` 是否已依赖 `luban-rds-server`，若无则调整 pom 或下沉 Coordinator 接口；design 阶段验证
- **[AOF 写入接入点选择]** -> 在 `RedisServerHandler` 命令执行后插入，需精确过滤读命令；用写命令白名单 + `SELECT`/`FLUSHALL` 特殊处理
- **[RDB TTL 绝对时间戳与时钟漂移]** -> 加载时换算回剩余 TTL，若已过期则不加载（Redis 行为一致）；分布式环境下 master/slave 时钟需同步（已有约束）
- **[AOF rewrite stream PEL 恢复]** -> 完整恢复 stream 数据 + 消费者组 + PEL，优先直接访问 store 内部结构（与 RDB 侧 `writeStream` 一致）；需与 RDB 路径的 PEL 序列化逻辑对齐验证两路径产出一致
- **[CROSSSLOT 改动签名影响调用链]** -> `extractKeyFromCommand` 返回类型变化牵动 `commandRequiresKey`/`NO_KEY_COMMANDS`/ASK 重定向；需回归测试现有 EVAL CROSSSLOT 用例
- **[MIGRATE 批量消息大小]** -> 64MB 单消息上限，超限拒绝并提示分批；不实现流式传输（Redis 也不支持）
- **[Failover 偏移量获取路径]** -> 需确认 `ClusterNode` 或 `ReplicationLifecycleListener` 是否已暴露偏移量接口，若无则新增
- **[ZSet 内存估算失准]** -> 跳表节点比 CHM 桶节点重，`estimateMemorySize` 常量需同步调整，否则 `MEMORY USAGE`/`maxmemory` OOM 误判

## Migration Plan

本变更是缺陷修复，不涉及数据迁移。但需注意：

1. **RDB 文件**：修复后写入的 RDB 含 TTL opcode，旧版本（无 opcode）仍可加载（加载侧 peek byte 判断）。向前兼容，无需迁移。
2. **AOF 文件**：修复后 AOF 含完整写命令。若存在旧 AOF 文件（之前为空或残留），加载时按现有解析器处理，不强制 rewrite。
3. **配置项**：不新增配置项。`appendonly yes`/`appendfsync` 等现有配置在修复后真正生效。
4. **回滚策略**：所有修复通过 git revert 回滚。RDB/AOF 文件格式向前兼容，回滚后旧版本仍可加载新格式文件（忽略未知 opcode）。

## Open Questions

1. ~~`luban-rds-replication` 模块的 pom 是否已依赖 `luban-rds-server`？~~ **已核实**：replication 不依赖 server，但 `ReplicationCommandHandler` 由 `ReplicationCoordinator`（server:105）构造，server 依赖 replication（无循环依赖）。D3 改用 setter 注入。
2. ~~`ClusterNode` 是否已持有复制偏移量字段？~~ **已核实**：无 offset 字段。但本节点偏移量从 `ReplicationCoordinator` 获取即可，不需存 ClusterNode。D9 扩展 `ReplicationLifecycleListener` 接口加 `getReplicationOffset()`。
3. ~~`ReplicationLifecycleListener` 接口是否已暴露 `getReplicationOffset()`？~~ **已核实**：未暴露。D9 新增该方法，`ReplicationCoordinator` override。
4. ~~stream AOF rewrite 的 PEL 恢复是否可在 P0 范围内完成，还是降级为"仅恢复 stream 数据 + 消费者组，PEL 标注为已知限制"？~~ **已确认**：用户选择完整恢复 PEL。D6 已更新为完整恢复方案（扫描 PEL 结构 + `XCLAIM` 重建），优先直接访问 store 内部结构与 RDB 侧 `writeStream` 对齐。
