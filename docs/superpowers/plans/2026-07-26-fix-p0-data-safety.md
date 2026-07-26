---
change: fix-p0-data-safety-redis7
design-doc: docs/superpowers/specs/2026-07-26-fix-p0-data-safety-design.md
base-ref: 834f205b0254babb4dab6b6b064b918a9cc05fb9
archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

# fix-p0-data-safety-redis7 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Luban-RDS 审计报告中的 12 个 P0 致命缺陷（C1-C12），使数据安全维度达到 Redis 7.x 基准。

**Architecture:** 按 4 个子系统分批次推进，复制子系统存在强依赖链（C2->C5->C6），持久化子系统 C3 是 C11 前提。每个缺陷采用 TDD：先写失败测试，再实现，再验证。详细任务清单见 `openspec/changes/fix-p0-data-safety-redis7/tasks.md`（74 个任务），本计划按批次提供文件路径、关键实现和验证命令。

**Tech Stack:** Java 17+, Maven 多模块, Netty, JUnit 5, Mockito

**Design Doc:** `docs/superpowers/specs/2026-07-26-fix-p0-data-safety-design.md`

**OpenSpec tasks:** `openspec/changes/fix-p0-data-safety-redis7/tasks.md`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 批次 A：复制子系统（C2/C4/C5/C6）

C2 是上游根因，必须最先修；C5 依赖 C2 的 offset 解析；C4 相对独立；C6 是 C2+C5 的下游验证。

### Task A1: 新增 HANDSHAKE_PSYNC 状态 + PSYNC 响应路由（C2 核心）

**Files:**
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationState.java`（新增枚举值）
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationClient.java`（switch 新增 case、激活 handlePsyncResponse）
- Test: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/SlaveReplicationClientTest.java`

- [ ] **Step 1: 写失败测试** - 验证 PSYNC 响应路由到 handlePsyncResponse 而非 handleReplconfResponse
- [ ] **Step 2: 运行测试确认失败** - `mvn test -pl luban-rds-replication -Dtest=SlaveReplicationClientTest`
- [ ] **Step 3: 新增 `HANDSHAKE_PSYNC` 枚举值**
- [ ] **Step 4: `handleResponse` switch 新增 `case HANDSHAKE_PSYNC -> handlePsyncResponse`**
- [ ] **Step 5: `startPsync()` 发送前 setState(HANDSHAKE_PSYNC)**
- [ ] **Step 6: 完善 `handlePsyncResponse` 解析 +FULLRESYNC/+CONTINUE**
- [ ] **Step 7: 运行测试确认通过**
- [ ] **Step 8: 提交** - `git commit -m "fix(replication): activate PSYNC response routing (C2)"`

### Task A2: REPLCONF 逐条等待响应（C2 时序，方案 A 状态机+回调）

**Files:**
- Modify: `SlaveReplicationClient.java`（sendReplConf/handleReplconfResponse 改造）
- Modify: `SlaveReplicationClient.java`（新增 5s scheduled timeout）

- [ ] **Step 1: 改造 sendReplConf 发第一条 PORT，setState(HANDSHAKE_REPLCONF_PORT)**
- [ ] **Step 2: handleReplconfResponse 收到 +OK 后在回调内发下一条**
- [ ] **Step 3: 新增 5s timeout 机制，超时回退 DISCONNECTED**
- [ ] **Step 4: 测试 REPLCONF 逐条等待 + timeout**
- [ ] **Step 5: 提交** - `git commit -m "fix(replication): REPLCONF sequential wait with timeout (C2)"`

### Task A3: 验证 onOnline/sendAck 回调链 + 重新启用 ReplicationIntegrationTest

**Files:**
- Modify: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/ReplicationIntegrationTest.java`（移除 @Disabled）

- [ ] **Step 1: 验证 callback.onOnline() 在 handleSyncData 完成后被调用**
- [ ] **Step 2: 验证 sendAck() 在 ONLINE 后被心跳调度器周期调用**
- [ ] **Step 3: 移除 ReplicationIntegrationTest 的 @Disabled**
- [ ] **Step 4: 运行集成测试，修复失败**
- [ ] **Step 5: 提交** - `git commit -m "fix(replication): re-enable ReplicationIntegrationTest (C2)"`

### Task A4: SLAVEOF 运行时命令（C4，setter 注入）

**Files:**
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/handler/ReplicationCommandHandler.java`
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java`
- Test: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/handler/ReplicationCommandHandlerTest.java`

- [ ] **Step 1: 写失败测试** - SLAVEOF host port 调用 startSlave
- [ ] **Step 2: 新增 setReplicationCoordinator setter + coordinator 字段**
- [ ] **Step 3: handleSlaveof 实现 startSlave/stopSlave 调用**
- [ ] **Step 4: ReplicationCoordinator 构造后 setter 注入**
- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 提交** - `git commit -m "fix(replication): SLAVEOF starts replication (C4)"`

### Task A5: Full sync 窗口期重放（C5）

**Files:**
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/RdbSnapshotGenerator.java`（generateAndTransfer 返回 SnapshotResult 含 snapshotOffset）
- Modify: `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/MasterReplicationManager.java`（performFullSync 重放逻辑）

- [ ] **Step 1: 改 generateAndTransfer 返回 SnapshotResult（transferredBytes + snapshotOffset）**
- [ ] **Step 2: snapshotOffset 在 generateTempRdbFile 返回后记录**
- [ ] **Step 3: performFullSync RDB 完成后调用 backlog.getBacklogData(snapshotOffset) 重放**
- [ ] **Step 4: 重放期间保持 SLAVE_FLAG_SYNCING，重放完成后 setState(ONLINE)**
- [ ] **Step 5: 测试窗口期写入不丢失**
- [ ] **Step 6: 提交** - `git commit -m "fix(replication): full sync window replay (C5)"`

### Task A6: 验证 slave offset + WAIT + INFO replication（C6 下游验证）

**Files:**
- Test: `luban-rds-replication/src/test/java/com/janeluo/luban/rds/replication/MasterReplicationManagerTest.java`

- [ ] **Step 1: 验证 REPLCONF ACK 分支 slave.updateOffset 正确更新**
- [ ] **Step 2: 验证 getSyncedSlavesCount 返回真实值**
- [ ] **Step 3: 验证 WAIT 命令基于真实 slave offset**
- [ ] **Step 4: 验证 INFO replication 的 slave offset**
- [ ] **Step 5: 提交** - `git commit -m "fix(replication): verify slave offset and WAIT (C6)"`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 批次 B：持久化子系统（C3/C10/C11）

C3 必须先修让 AOF 写入链路通起来；C10 独立；C11 依赖 C3 的接口扩展。

### Task B1: PersistService 接口扩展 + AofPersistService recordCommand（C3 核心）

**Files:**
- Modify: `luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/PersistService.java`（新增 default recordCommand(byte[])）
- Modify: `luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/impl/AofPersistService.java`（override recordCommand）
- Test: `luban-rds-persistence/src/test/java/com/janeluo/luban/rds/persistence/AofPersistServiceTest.java`

- [ ] **Step 1: 写失败测试** - recordCommand 写入 AOF 文件
- [ ] **Step 2: PersistService 新增 `default void recordCommand(byte[] respFrame) {}`**
- [ ] **Step 3: AofPersistService override，用 ISO-8859-1 写入 respFrame**
- [ ] **Step 4: 运行测试确认通过**
- [ ] **Step 5: 提交** - `git commit -m "fix(persistence): AOF recordCommand interface (C3)"`

### Task B2: AOF 接入命令分发 + SELECT db 标记（C3 接入点）

**Files:**
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java`（764 行新增 recordCommand 调用）
- Modify: `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/handler/CommonCommandHandler.java`（handleBgrewriteaof 接入）
- Test: `luban-rds-server/src/test/java/com/janeluo/luban/rds/server/AofWriteIntegrationTest.java`

- [ ] **Step 1: 写失败测试** - SET 后 AOF 含 RESP；SELECT 记录为 db 标记；读命令不记录**
- [ ] **Step 2: RedisServerHandler 764 行新增 persistService.recordCommand(rawRespFrame)**
- [ ] **Step 3: SELECT 特殊处理 - 记录到 AOF 作为 db 上下文标记**
- [ ] **Step 4: handleBgrewriteaof 接入 aofPersistService.rewrite(memoryStore)**
- [ ] **Step 5: 运行集成测试确认通过**
- [ ] **Step 6: 提交** - `git commit -m "fix(persistence): AOF write hook + SELECT db marker (C3)"`

### Task B3: RDB TTL 持久化（C10）

**Files:**
- Modify: `luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/impl/RdbPersistService.java`（opcode 常量、writeKeyValue 写 TTL、readKeyValue 读 TTL、小端序）
- Test: `luban-rds-persistence/src/test/java/com/janeluo/luban/rds/persistence/RdbTtlPersistTest.java`

- [ ] **Step 1: 写失败测试** - SET EX 后 RDB 持久化+重启恢复 TTL；已过期不复活；旧格式兼容**
- [ ] **Step 2: 新增 RDB_OPCODE_EXPIRETIME_MS=0xFC / RDB_OPCODE_EXPIRETIME=0xFD 常量**
- [ ] **Step 3: writeKeyValue 写 TTL（绝对时间戳，小端序）**
- [ ] **Step 4: readKeyValue 读 TTL（peek byte，换算剩余，<=0 不加载）**
- [ ] **Step 5: 运行测试确认通过**
- [ ] **Step 6: 提交** - `git commit -m "fix(persistence): RDB TTL persistence (C10)"`

### Task B4: AOF rewrite 按类型重建（C11）

**Files:**
- Modify: `luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/impl/AofPersistService.java`（writeRebuildCommand 按类型分支、writeStreamRebuild）
- Test: `luban-rds-persistence/src/test/java/com/janeluo/luban/rds/persistence/AofRewriteTypeTest.java`

- [ ] **Step 1: 写失败测试** - 各类型 rewrite 保留；带 TTL；stream PEL**
- [ ] **Step 2: writeKeyValueCommand 重构为 writeRebuildCommand，按 type 分支**
- [ ] **Step 3: string->SET, list->RPUSH, set->SADD, hash->HSET, zset->ZADD**
- [ ] **Step 4: stream rewrite: XADD + XGROUP CREATE + XCLAIM 恢复 PEL**
- [ ] **Step 5: 带 TTL 键追加 PEXPIREAT**
- [ ] **Step 6: ISO-8859-1 编码保证二进制安全**
- [ ] **Step 7: 运行测试确认通过**
- [ ] **Step 8: 提交** - `git commit -m "fix(persistence): AOF rewrite by type (C11)"`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 批次 C：集群子系统（C1/C7/C8/C9）

四个缺陷互相独立，可并行推进。

### Task C1: CROSSSLOT 多键校验（C1）

**Files:**
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java`（extractKeysFromCommand 返回 List、checkCrossSlotAndRedirect）
- Test: `luban-rds-server/src/test/java/com/janeluo/luban/rds/server/CrossSlotCheckTest.java`

- [ ] **Step 1: 写失败测试** - MGET/MSET/DEL 跨槽被拒；同槽正常；RENAME 源目标；EVAL 不变；非集群跳过**
- [ ] **Step 2: extractKeyFromCommand -> extractKeysFromCommand 返回 List<String>**
- [ ] **Step 3: 覆盖所有多键命令键位置（MGET/MSET/DEL/EXISTS/UNLINK/TOUCH/SUNION/SINTER/SDIFF/SMOVE/STORE 系列/BITOP/SORT STORE）**
- [ ] **Step 4: RENAME/RENAMENX/COPY 返回 [srcKey, dstKey]**
- [ ] **Step 5: 新增 checkCrossSlotAndRedirect(List<String>)**
- [ ] **Step 6: checkSlotAndRedirect 改为接受 List**
- [ ] **Step 7: 运行测试确认通过**
- [ ] **Step 8: 提交** - `git commit -m "fix(cluster): CROSSSLOT multi-key check (C1)"`

### Task C2: MIGRATE 原子化（C7）

**Files:**
- Create: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/migration/MigrateKeysMessage.java`
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/migration/MigrateCommandHandler.java`
- Test: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/migration/MigrateAtomicityTest.java`

- [ ] **Step 1: 写失败测试** - 全成功删源；部分失败不删；COPY 模式；超限拒绝**
- [ ] **Step 2: 新增 MigrateKeysMessage 批量消息类**
- [ ] **Step 3: migrateMultipleKeys 改为一次性发送批量消息**
- [ ] **Step 4: 目标端批量原子 RESTORE，全部 ACK 后源端统一 DEL**
- [ ] **Step 5: 64MB 上限校验**
- [ ] **Step 6: 运行测试确认通过**
- [ ] **Step 7: 提交** - `git commit -m "fix(cluster): atomic MIGRATE (C7)"`

### Task C3: Failover 偏移量选举（C8）

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/lifecycle/ReplicationLifecycleListener.java`（新增 getReplicationOffset）
- Modify: `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/ReplicationCoordinator.java`（实现 getReplicationOffset）
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java`（AUTH_REQUEST 填偏移量、退避 rank、投票比较）
- Test: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/FailoverOffsetElectionTest.java`

- [ ] **Step 1: 写失败测试** - 偏移量大者优先；已投票后拒绝同纪元其他候选**
- [ ] **Step 2: ReplicationLifecycleListener 新增 getReplicationOffset()**
- [ ] **Step 3: ReplicationCoordinator 实现返回真实 master_repl_offset**
- [ ] **Step 4: FailoverManager AUTH_REQUEST 第 4 参数填 listener.getReplicationOffset()**
- [ ] **Step 5: tryStartElection 退避基于 rank**
- [ ] **Step 6: onAuthRequest 同纪元多候选比较偏移量**
- [ ] **Step 7: 运行测试确认通过**
- [ ] **Step 8: 提交** - `git commit -m "fix(cluster): failover offset election (C8)"`

### Task C4: 手动 failover 广播（C9）

**Files:**
- Modify: `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailoverManager.java`（performFailover 加广播、移除重复广播）
- Test: `luban-rds-cluster/src/test/java/com/janeluo/luban/rds/cluster/gossip/ManualFailoverBroadcastTest.java`

- [ ] **Step 1: 写失败测试** - FORCE/TAKEOVER 广播；原 master configEpoch 对齐；自动路径不重复**
- [ ] **Step 2: performFailover 内新增 busClient.broadcast(FailoverResultMessage)**
- [ ] **Step 3: performManualFailover 补 masterNode.setConfigEpoch**
- [ ] **Step 4: performFailoverAndBroadcast 移除重复广播**
- [ ] **Step 5: 验证 onFailoverResult 幂等处理**
- [ ] **Step 6: 运行测试确认通过**
- [ ] **Step 7: 提交** - `git commit -m "fix(cluster): manual failover broadcast (C9)"`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 批次 D：核心数据结构（C12）

### Task D1: ZSet 同分字典序（C12）

**Files:**
- Modify: `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java`（ZSetStore 字段类型、add/remove、range/zpopmin/zpopmax/zrevrange/zrank、estimateMemorySize）
- Test: `luban-rds-core/src/test/java/com/janeluo/luban/rds/core/store/ZSetOrderingTest.java`（真实 store，非 mock）

- [ ] **Step 1: 写失败测试** - ZRANGE/ZREVRANGE 同分字典序；ZPOPMIN/MAX；ZRANK；ZINCRBY 改分重排；多线程并发 ZADD**
- [ ] **Step 2: ZSetStore.scoreMembers 值类型 KeySetView -> ConcurrentSkipListSet**
- [ ] **Step 3: add 中 computeIfAbsent 改为 new ConcurrentSkipListSet<>()**
- [ ] **Step 4: zpopmax/zrevrange 用 descendingSet()/descendingIterator()**
- [ ] **Step 5: zrank 同分定位改为 ConcurrentSkipListSet 线性扫描**
- [ ] **Step 6: estimateMemorySize 244 行 64L -> 72L**
- [ ] **Step 7: 运行测试确认通过**
- [ ] **Step 8: 提交** - `git commit -m "fix(core): ZSet same-score lexicographic order (C12)"`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 批次 E：集成验证与收尾

### Task E1: 全量回归 + 端到端验证

- [ ] **Step 1: `mvn clean install` 通过，无新增失败用例**
- [ ] **Step 2: 端到端复制验证** - master-slave 全量同步 + 窗口期写入 + slave offset + WAIT**
- [ ] **Step 3: 持久化端到端** - AOF 重启不丢 + RDB TTL + AOF rewrite 类型**
- [ ] **Step 4: 集群端到端** - CROSSSLOT + MIGRATE 原子性 + 手动 failover + 偏移量选举**
- [ ] **Step 5: ZSet 排序验证**
- [ ] **Step 6: 更新 AGENTS.md 测试覆盖率表（如有变化）**
- [ ] **Step 7: 在 AUDIT-REPORT-vs-Redis7.md 末尾追加修复说明**
- [ ] **Step 8: 提交** - `git commit -m "docs: mark C1-C12 P0 defects fixed"`

archived-with: 2026-07-27-fix-p0-data-safety-redis7
---

## 验证命令

```bash
# 单模块测试
mvn test -pl luban-rds-replication -Dtest=ClassName
mvn test -pl luban-rds-persistence -Dtest=ClassName
mvn test -pl luban-rds-cluster -Dtest=ClassName
mvn test -pl luban-rds-core -Dtest=ClassName

# 全量构建
mvn clean install

# 覆盖率
mvn jacoco:report
```

## 提交规范

- 每个任务一次提交，message 体现设计意图
- 格式：`fix(<subsystem>): <description> (C<n>)`
- 示例：`fix(replication): activate PSYNC response routing (C2)`
