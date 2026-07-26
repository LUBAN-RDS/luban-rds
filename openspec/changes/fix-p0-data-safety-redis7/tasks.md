# Tasks: fix-p0-data-safety-redis7

按依赖顺序分 4 个批次推进。每批次内任务可并行，批次间存在依赖。

## 1. 批次 A：复制子系统（C2/C4/C5/C6，存在强依赖链）

C2 是上游根因，必须最先修；C5 依赖 C2 的 offset 解析；C4 相对独立；C6 是 C2+C5 的下游验证。

- [x] 1.1 新增 `ReplicationState.HANDSHAKE_PSYNC` 状态枚举值，并在状态机文档/注释中更新 DISCONNECTED -> ... -> HANDSHAKE_PSYNC -> FULL_SYNC/PARTIAL_SYNC -> ONLINE 流转
- [x] 1.2 改造 `SlaveReplicationClient.sendReplConf()`：从同步串发 3 条改为"发一条 REPLCONF -> 等待 +OK -> 发下一条"，每条成功后再推进状态
- [x] 1.3 改造 `SlaveReplicationClient.startPsync()`：发送 PSYNC 前先把状态切到 `HANDSHAKE_PSYNC`
- [x] 1.4 `SlaveReplicationClient.handleResponse` switch 新增 `case HANDSHAKE_PSYNC -> handlePsyncResponse`，激活死代码
- [x] 1.5 完善 `handlePsyncResponse`：解析 `+FULLRESYNC <replid> <offset>` 触发 `callback.onFullSync(replid, offset)` 并进入 `FULL_SYNC`；解析 `+CONTINUE [replid]` 触发 `callback.onPartialSync(replid)` 并进入 `PARTIAL_SYNC`
- [x] 1.6 验证 `callback.onOnline()` 在 `handleSyncData` 完成 RDB/backlog 加载后被调用，slave 进入 `ONLINE` 状态
- [x] 1.7 验证 `sendAck()` 在 slave ONLINE 后被心跳调度器周期调用，发送 `REPLCONF ACK <offset>`
- [x] 1.8 重新启用 `ReplicationIntegrationTest`（移除 `@Disabled`），修复因状态机改造导致的测试失败
- [x] 1.9 `ReplicationCommandHandler` 新增 `setReplicationCoordinator(ReplicationCoordinator)` setter 方法
- [x] 1.10 `ReplicationCoordinator` 构造 `ReplicationCommandHandler` 后调用 setter 注入自身
- [x] 1.11 `ReplicationCommandHandler.handleSlaveof` 实现 `SLAVEOF host port`：解析参数调用 `coordinator.startSlave(address)`；`SLAVEOF NO ONE` 调用 `coordinator.stopSlave()`
- [x] 1.12 在 `MasterReplicationManager.handlePsync` 全量同步分支记录 `snapshotBaseOffset = backlog.getMasterReplOffset()`
- [x] 1.13 `MasterReplicationManager.performFullSync` RDB 传输完成后、`setState(ONLINE)` 之前，调用 `backlog.getBacklogData(snapshotBaseOffset)` 重放窗口期命令
- [x] 1.14 重放期间保持 slave `SLAVE_FLAG_SYNCING` 状态，避免 `propagateCommand` 并发直发；重放完成后才 `setState(ONLINE)`
- [x] 1.15 验证 `MasterReplicationManager` 的 `slave.updateOffset` 在 `REPLCONF ACK` 分支（161 行）正确更新，`getSyncedSlavesCount` 返回真实值
- [x] 1.16 验证 `WAIT` 命令基于真实 slave offset 统计已同步副本数（`slave.getOffset() >= currentOffset`）
- [x] 1.17 验证 `INFO replication` 的 `slave0:...,offset=<n>` 反映真实偏移量

## 2. 批次 B：持久化子系统（C3/C10/C11，C3 是 C11 前提）

C3 必须先修让 AOF 写入链路通起来；C10 独立；C11 依赖 C3 的接口扩展。

- [x] 2.1 `PersistService` 接口新增 `default void recordCommand(String command, String[] args) {}` 默认空实现
- [x] 2.2 `AofPersistService` override `recordCommand`，修复方法体使用 `command` 作为 args[0] 写入 RESP 数组
- [x] 2.3 在 `RedisServerHandler` 命令执行后（响应写出前）插入 `persistService.recordCommand(cmd, args)` 调用，用写命令白名单过滤读命令
- [x] 2.4 `SELECT` 不记录到 AOF（加载侧维护当前 db 上下文）；`FLUSHALL`/`FLUSHDB` 记录
- [x] 2.5 `CommonCommandHandler.handleBgrewriteaof` 接入 `aofPersistService.rewrite(memoryStore)`，真正触发重写
- [x] 2.6 编写 AOF 写入集成测试：SET 命令后 AOF 文件含对应 RESP；重启加载恢复数据
- [x] 2.7 `RdbPersistService` 新增 `RDB_OPCODE_EXPIRETIME_MS = (byte) 0xFC` 和 `RDB_OPCODE_EXPIRETIME = (byte) 0xFD` 常量
- [x] 2.8 `writeKeyValue` 每个 value 写完后，若 `memoryStore.ttl(db, key) > 0`，计算 `expireAt = System.currentTimeMillis() + pttl`，按规则写 0xFD（整秒且<1h）或 0xFC（毫秒）
- [x] 2.9 `readKeyValue` peek 下一个 byte，若是 0xFC/0xFD 读取时间戳，换算 `remaining = expireAt - now`，<=0 不加载，否则 `pexpire(db, key, remaining)`
- [x] 2.10 加载侧无 expire opcode 时按永久键加载（向后兼容旧格式）
- [x] 2.11 编写 RDB TTL 测试：SET EX 后 RDB 持久化+重启恢复 TTL；已过期键不复活；旧格式向后兼容
- [x] 2.12 `AofPersistService.writeKeyValueCommand` 重构为 `writeRebuildCommand`，按 `memoryStore.type(db, key)` 分支：string->SET、list->RPUSH、set->SADD、hash->HSET、zset->ZADD
- [x] 2.13 stream 类型 rewrite：逐条 `XADD` 恢复数据 + `XGROUP CREATE` 恢复消费者组 + 扫描 PEL 结构逐条 `XCLAIM` 完整恢复 PEL（与 RDB 侧 `writeStream` 逻辑对齐）
- [x] 2.14 带 TTL 的键在重建命令后追加 `PEXPIREAT key <timestampMs>`
- [x] 2.15 所有字节数据用 ISO-8859-1 编码保证二进制安全
- [x] 2.16 编写 AOF rewrite 测试：各类型键 rewrite 后重启加载保留类型与数据；带 TTL 键保留过期时间
- [x] 2.17（新增）修复 AOF rewrite 缺陷：Windows 下 `aofWriter` 持有 appendonly.aof 时 `Files.move(REPLACE_EXISTING)` 失败，旧 catch 由 NOP logger 吞错，导致重写数据全部丢失；改为 move 前先关闭 aofWriter/aofOutputStream 与 tempWriter，move 成功后再重建 aofWriter
- [x] 2.18（新增）修复 AOF load 缺陷：原 `BufferedReader.readLine()` + `line.split("\\r\\n")` 解析永不成立（readLine 已剥离 \r\n），导致重启加载后键全部丢失；改为基于 `DataInputStream` 的标准 RESP 帧解析（`*N` 数组头 + 逐个 `$L\r\n<bytes>\r\n`），ISO-8859-1 解码保证二进制安全；将原 `parseAndExecuteCommand` 拆分为 `executeCommand(List<String>)` 与行解析 wrapper

## 3. 批次 C：集群子系统（C1/C7/C8/C9，相对独立）

四个缺陷互相独立，可并行推进。

- [x] 3.1 `RedisServerHandler.extractKeyFromCommand` 返回类型从 `String` 改为 `List<String>`，单键命令返回单元素列表
- [x] 3.2 扩展 `extractKeyFromCommand` 覆盖所有多键命令的键位置：MGET/MSET/MSETNX/DEL/EXISTS/UNLINK/TOUCH/SUNION/SINTER/SDIFF/SMOVE/SDIFFSTORE/SINTERSTORE/SUNIONSTORE/ZUNIONSTORE/ZINTERSTORE/BITOP/SORT STORE
- [x] 3.3 RENAME/RENAMENX/COPY 源+目标型命令：`extractKeyFromCommand` 返回 [srcKey, dstKey]
- [x] 3.4 新增 `checkCrossSlot(List<String> keys)`：所有键 hash 到同一 slot 否则返回 `-CROSSSLOT Keys in request don't hash to the same slot`
- [x] 3.5 `checkSlotAndRedirect` 改为接受 `List<String>`：先 CROSSSLOT 校验，再校验首键是否在本节点（MOVED）
- [x] 3.6 保持 `EVAL`/`EVALSHA` 的 `checkCrossSlotForScript` 逻辑不变（向后兼容）
- [x] 3.7 非集群模式（`cluster-enabled no`）跳过 CROSSSLOT 校验
- [x] 3.8 编写 CROSSSLOT 测试：MGET/MSET/DEL 跨槽被拒；同槽正常；RENAME 源目标不同槽被拒；EVAL 校验不变；非集群模式不校验
- [x] 3.9 新增 `MigrateKeysMessage` 批量键消息类，包含所有键的 dump 数据
- [x] 3.10 `MigrateCommandHandler.migrateMultipleKeys` 改为一次性发送 `MigrateKeysMessage`，目标端批量原子 RESTORE
- [x] 3.11 目标端全部 ACK 后源端统一 DEL；任一失败源端不删，返回 `-ERR partial migration`
- [x] 3.12 单消息 64MB 上限校验，超限返回 `-ERR command keys batch too large`
- [x] 3.13 COPY 模式不删除源
- [x] 3.14 编写 MIGRATE 原子性测试：全成功删源；部分失败源不删；COPY 模式不删；超限拒绝
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
- [ ] 4.2 `ZSetStore.add` 中 `scoreMembers.computeIfAbsent(score, k -> ConcurrentHashMap.newKeySet())` 改为 `k -> new ConcurrentSkipListSet<>()`
- [ ] 4.3 验证 `add`/`remove` 的旧分集合移除/新分集合加入/空集合清理逻辑在新结构下正确
- [ ] 4.4 `zpopmax`/`zrevrange` 用 `descendingSet()` 实现反向字典序
- [ ] 4.5 `zpopmin` 验证同分弹字典序最小；`zpopmax` 验证同分弹字典序最大
- [ ] 4.6 `zrank` 同分定位改为 `ConcurrentSkipListSet` 线性扫描（字典序正确，仍 O(n)）
- [ ] 4.7 `range`/`rangeByScore`/`zscan`/`zremrangeByScore`/`zremrangeByRank` 验证字典序迭代
- [ ] 4.8 同步调整 `estimateMemorySize`（237-247 行）的 ZSetStore 估算常量（跳表节点比 CHM 桶节点重）
- [ ] 4.9 编写 ZSet 同分字典序测试：ZRANGE/ZREVRANGE/ZPOPMIN/ZPOPMAX/ZRANK/ZINCRBY 改分重排/多线程并发 ZADD

## 5. 批次 E：集成验证与收尾

- [ ] 5.1 全量回归测试：`mvn clean install` 通过，无新增失败用例
- [ ] 5.2 端到端复制验证：master-slave 全量同步 + 窗口期写入不丢 + slave offset 正确 + WAIT 命令正确
- [ ] 5.3 持久化端到端验证：AOF 模式重启不丢数据 + RDB 模式 TTL 保留 + AOF rewrite 各类型保留
- [ ] 5.4 集群端到端验证：CROSSSLOT 拒绝跨槽 + MIGRATE 原子性 + 手动 failover 全网收敛 + 偏移量选举
- [ ] 5.5 ZSet 排序验证：同分字典序在所有相关命令一致
- [ ] 5.6 更新 `AGENTS.md` 第 9/10 节的测试覆盖率表（如有变化）
- [ ] 5.7 在 `AUDIT-REPORT-vs-Redis7.md` 末尾追加修复说明（标注 C1-C12 已修复，引用 change 名）
