# Luban-RDS 集群模块全面评审报告（第二轮，HEAD 33248b2）

> **评审目标**：对 `luban-rds-cluster` 模块做第二轮全面评审——验证 2026-08-03 首轮审计（AUDIT-REPORT-CLUSTER-MODULE.md）列出的 P0×4 + P1×24 + P2×26 + P3×20 在批次 1-6 修复后的真实状态，并发现新增/遗留问题及与 Redis 7.x cluster 协议不兼容之处。
> **评审方式**：6 个并行深度评审子代理（总线/消息编解码、failover/故障检测、配置持久化、迁移、gossip/节点状态、路由/命令协议面）+ 关键路径人工逐行复核（FailoverManager 选举/手动 failover、GossipProtocol PING/PONG 处理、ClusterConfig.syncSlotsFromNode、NettyRedisServer 恢复链、RedisServerHandler 重定向/门控、ClusterBusClient ACK 匹配、迁移序列化）。
> **评审日期**：2026-08-04
> **基线**：master @ 33248b2（含 46fdb7d 批次 1-6 修复 + 8fde4f8 epoch 回归保护）
> **基准**：Redis 7.x 官方语义（cluster.c 7.2 源码逐行核对）

---

## 0. 总体结论

批次 1-6 修复**整体质量高**：P0 四项全部到位，P1 中约半数已彻底修复（迁移重定向语义、cluster_state 门控、slave 写保护、requestId 机制、PFAIL 票治理、FORGET 黑名单等），且多数修复有测试覆盖。但**修复引入了 2 个新的 P0 级回归**，并有 3 个 P1 级闭环断裂（修复了一半、另一半失效）：

**本轮新发现/确认的 P0（3 个）：**

1. **手动 failover 后 master 写永久冻结**——MFStart 的 `writePauseGate.pause()` 只有 slave 侧状态机能 resume，master 永不恢复，直到进程重启；
2. **默认配置下自动 failover 永久失效**——`cluster-slave-validity-factor=10`（默认）下，MYSELF 的 replOffset 恒为 0（全库无写入点），validity 校验恒失败 → 所有 slave 永远无法发起选举；
3. **MIGRATE 源端删除/目标端导入均不进入复制与 AOF 流**——failover 后"已迁移键"从 slave 复活（幽灵键），副本数据分叉。

**协议不兼容面（未修复的存量 + 新确认）：** 总线 wire 格式与 Redis clusterMsg 完全不同且消息类型码 0x08-0x0C 全部与 Redis 7 冲突；nodes.conf 读方向不互操作（vars 段、迁移方括号）、写方向 ping/pong=0 会让真实 Redis 把全节点误判 PFAIL；CLUSTER 子命令缺 SHARDS/LINKS/RESET 等 8 个；XREAD/XREADGROUP 键提取错误导致已实现命令在集群中静默错路由；WATCH/MULTI/EXEC 完全绕过集群路由与 CROSSSLOT。

**整体判断**：批次 1-6 后代码质量显著提升，但**当前状态不建议上线**——两个 P0 直接禁用高可用（自动 failover 失效、手动 failover 冻结写），迁移存在副本分叉。修复优先级明确：先补 MYSELF replOffset 写入与手动 failover resume 路径，再做迁移复制传播。

---

## 1. 首轮审计问题修复验证总表

### 1.1 P0 系列 —— 全部已修复 ✅

| 编号 | 状态 | 验证依据 |
|---|---|---|
| P0-1 MIGRATE 端到端不可用 | ✅ 已修复 | `importKey` 改校验 `SlotManager.isSlotImporting`（SlotMigrationManager.java:282）；CLUSTER SETSLOT IMPORTING/MIGRATING 写同一张表（ClusterCommandHandler.java:886/906），生产路径一致 |
| P0-2 ASK 死循环 | ✅ 已修复 | `checkSlotAndRedirect` 对 IMPORTING 槽位跳过 MOVED（RedisServerHandler.java:2883-2885），`checkAskRedirect` 无 ASKING → `-ASK` 回源（:2974-2987） |
| P0-3 MIGRATING 端 ASKING 误放行 | ✅ 已修复 | MIGRATING 键缺失**无条件** `-ASK` 且不消费 ASKING（RedisServerHandler.java:2999-3010）；ASKING 仅对 IMPORTING 放行 |
| P0-4 ACK 广播无 candidateId → 双主 | ✅ 已修复 | `FailoverAuthAckMessage` 新增 `candidateId`（:45，40 字节，decode 兼容旧 24 字节体）；`onAuthAck` 校验 `candidateId==本节点 ID` 才计数（FailoverManager.java:584-590），并校验投票者健康 master、voteEpoch 一致 |

### 1.2 P1 系列 —— 12 项彻底修复 / 8 项部分修复 / 4 项未修复

| 编号 | 状态 | 验证依据 |
|---|---|---|
| P1-1 双表分叉 | 🟡 部分修复 | 路由主路径改读 `clusterConfig.getSlotOwner`（RedisServerHandler.java:2892-2898），但 **:2897 保留 slotManager 回退**：gossip 把槽位置空而 slotManager 仍记本节点时，回退读到陈旧 owner → "越权服务"窗口仍在；gossip 路径（syncSlotsFromNode）仍不写 slotManager；FailoverManager.java:809-810 注释声称"setSlotOwner 内部会同步 SlotManager"与实际不符（实际靠显式双写） |
| P1-2 SETSLOT NODE epoch 收敛 | 🟡 部分修复 | SETSLOT NODE 已提升 configEpoch（ClusterCommandHandler.java:956-961）；syncSlotsFromNode 已支持删除（ClusterConfig.java:377-390）。**但删除守卫 `configEpoch >= node.getConfigEpoch()` 在 PING/PONG 路径恒真**（见 §2.2 N-1）→ 守卫被绕过，且"gossip 删槽"本身偏离 Redis 语义 |
| P1-3 FORGET 黑名单 | ✅ 已修复 | 黑名单上移共享对象 `ClusterConfig.forgetBlacklist`（TTL 60s）；ClusterCommandHandler.java:642/656 写入；MEET/gossip 双闸门（GossipProtocol.java:610/1115）；GossipTask:109 周期清理。旧 `forgetNodes` 表变死代码 |
| P1-4 gossip 不置脏不持久化 | ❌ 未修复 | `syncSlotsFromNode`（ClusterConfig.java:340-391）全方法**无一处 markDirty**；角色切换、epoch 推进、第三方 FAIL 应用后均不置脏。仅"新节点发现/握手完成"触发保存。nodes.conf 长期陈旧，全集群重启回退旧拓扑 |
| P1-5 PING/PONG configEpoch 不落地 | 🟡 部分修复 | MEET 路径已修（GossipProtocol.java:1060 先提升本地纪元，:1070-1071 用 header epoch 仲裁）；**PING/PONG 路径未修**（:964-965/:1007-1008 传本地陈旧纪元，且缺 `setConfigEpochIfGreater(header epoch)`）→ 叠加 N-1 使槽位删除守卫恒真 |
| P1-6 投票 offset/rank/validity | 🟡 部分修复（闭环断裂→P0） | rank 退避与 validity 校验代码完整（FailoverManager.java:291-293/320-367），**但 MYSELF 的 replOffset 全库无写入点**（setReplOffset 仅对远端节点：GossipProtocol.java:976/1018/1078/1221）→ rank 反转 + 默认 factor=10 下选举被永久阻塞（§2.1 P0-2）。另：经 Redis 7.2 cluster.c 核对，**"首投即定"与 Redis 7.2 语义一致**（Redis 7.2 `clusterSendFailoverAuthIfNeeded` 同纪元无条件拒投，改票是 Redis 8 行为），首轮审计此条表述需修正 |
| P1-7 slave 票计入多数 | ✅ 已修复 | FailureDetector 三处 master 门控 + `countMasterVotes` 过滤 |
| P1-8 PFAIL 票永不过期 | ✅ 已修复 | 票带时间戳（`Map<String, Map<String, Long>>`），`cleanupStaleFailureReports`（2×nodeTimeout，GossipTask 每轮触发），PFAIL 恢复时撤销全部报告 |
| P1-9 心跳 O(N) | ✅ 已修复 | `selectPriorityPingTarget`（GossipTask.java:194-215）优先 PING pong 最老节点，nodeTimeout/2 门控。残留小缺陷：从未 PING 过的节点被跳过（getTimeSinceLastPing 返回 0）、发送失败也刷新 lastPingTime |
| P1-10 FAIL 无 myself 守卫 | ❌ 未修复 | `handleFail`（GossipProtocol.java:708-737）对 failedNodeId==MYSELF 无拦截，可直接把自己标 FAIL 失去投票/选举权；Redis 7.2 明确 `!(failing->flags & CLUSTER_NODE_MYSELF)` |
| P1-11 FailoverResult 信任模型 | 🟡 部分修复 | 已加旧纪元拒绝、相等纪元裁决、winner 已知节点校验（FailoverManager.java:879-906）。**仍缺**：sender==winner 校验、slots 来源校验（inheritedSlots 整体直接应用 :912-919）、`setCurrentEpoch(msg epoch)` 可被任意节点无限抬高（:959）→ 伪造 FailoverResult 可窃取全部槽位 |
| P1-12 手动 FAILOVER 三模式 | 🟡 部分修复（引入 P0） | MF_START/MF_OFFSET 状态机 + WritePauseGate + 三模式语义门控全部实现；**但 master 侧 pause 后永不 resume**（§2.1 P0-1）→ 写永久冻结 |
| P1-13 cluster_state 不参与路由 | ✅ 已修复 | `checkClusterStateGate`（RedisServerHandler.java:2789-2801）返回 `-CLUSTERDOWN The cluster is down`，`cluster-allow-reads-when-down` 已消费（:257）。残留：**双公式写者并存**（§2.3 N-3），gate 读的 `state` 字段取决于谁最后写 |
| P1-14 slave 无写保护 | ✅ 已修复 | `-READONLY You can't write against a read only replica.`（:2829-2864）+ READONLY/READWRITE 实现（:3041-3060）+ 读默认 MOVED 到 master |
| P1-15 SORT 键提取 | ❌ 未修复 | 仍 `trailingKeys(args, 1)`（RedisServerHandler.java:2603-2605），与自身注释"仅以 args[1] 作为键"矛盾；BY/GET/LIMIT/STORE 全被当键 → 必误报 CROSSSLOT（SORT 命令本身未注册，当前潜伏） |
| P1-16 多键命令清单 | ❌ 未修复 | XREAD/XREADGROUP/XINFO 等已实现命令落入默认单键分支，路由键取 args[1]（`"COUNT"`/`"GROUP"`/`"STREAM"`）→ 读静默错节点返回空，多流 CROSSSLOT 不校验（§2.2 N-4） |
| P1-17 MIGRATE 语义 | ✅ 已修复（6 项全过） | 单键缺失 `+NOKEY`、timeout=0 用默认 5000ms、AUTH/AUTH2 解析、key+KEYS 语法错误、BUSYKEY（ImportResult 状态 + 源端映射）、requestId 匹配。**新发现**：zset/stream 值类型不可序列化、destination-db 被丢弃（§2.3 N-5/N-6） |
| P1-18 nodes.conf 互操作 | 🟡 部分修复 | 写方向字段顺序/槽位缩写基本对齐；**读方向仍丢 vars 段与 `[slot->-id]` 迁移方括号**（当成坏行丢弃）；**ping/pong 字段仍写 0**——真实 Redis 加载后 `mstime()-pong_received > node_timeout` 立即把全部节点判 PFAIL → 全集群 CLUSTERDOWN 窗口 |
| P1-19 lastVoteEpoch 落盘 | 🟡 部分修复（生产路径失效） | persister 层完整（写 `# Last Vote Epoch` 注释 + 解析 + recordVoteEpoch 置脏）；**但 `NettyRedisServer.restoreClusterFromConfig`（:504-535）不恢复 lastVoteEpoch** → 重启后恒 0，同纪元二次投票防护失效 |
| P1-20 requestId | ✅ 已修复（引入新 P1） | 严格匹配 + finally 清理 + close 取消，测试覆盖。**新风险**：ACK 无来源校验（§2.3 N-7）；滚动升级期旧格式 ACK（requestId=0）被丢弃 → MIGRATE 必超时且目标端已导入，重试 BUSYKEY 卡死 |
| P1-21 重连风暴 | 🟡 部分修复 | 去重窗口+指数退避+HANDSHAKE 清理到位；**残留**：connect 无条件重登记端点可复活已删除节点（僵尸重连循环）、CLUSTER FORGET 不 disconnect、并发 connect 无互斥泄漏连接 |
| P1-22 半开连接 | 🟡 部分修复 | SO_KEEPALIVE 已加但默认间隔 ~2h 实际无效；半开靠 FAIL→failover 兜底；FAIL 确认后不主动 close 该连接，15-30min 窗口内 link-state 失真+写积压 |
| P1-23 CLUSTER 子命令缺失 | ❌ 未修复 | 仍缺 SHARDS/LINKS/RESET/COUNT-FAILURE-REPORTS/ADDSLOTSRANGE/DELSLOTSRANGE/REFRESH/HELP；`redis-cli --cluster create`（ADDSLOTSRANGE）与 Redisson/Lettuce 新版（SHARDS）不可用；默认分支缺 `Try CLUSTER HELP.` 后缀 |
| P1-24 配置解析零消费 | 🟡 部分修复 | allow-reads-when-down（:257）、slave-validity-factor（FailoverManager 构造）已消费；**cluster-require-full-coverage 仍只解析不消费**（isClusterOk 硬编码全量覆盖）、**cluster-migration-barrier 仍零消费** |

### 1.3 P2/P3 系列状态摘要

**已修复**：P2-6（SETSLOT NODE 清迁移状态，但无条件清除、Redis 仅方向匹配时清除——小偏差）、P2-1（同 epoch 双 winner 字典序决胜，但方向与 Redis 相反——Redis 是小 ID 保纪元、大 ID bump 后胜出，本实现大 ID 直接胜且双方纪元保持相等，破坏 configEpoch 唯一性不变式）、P3-12（GETKEYSINSLOT 负 count 错误串）、P3-16（encode null 防护）。

**未修复（P2）**：P2-3 isStaleMaster 降级过宽（任何无槽低纪元 master 被降级）、P2-4 votesCast 清理不完整（gossip/结果消息抬升纪元后不清票 → 新纪元首投被误拒）、P2-5 选举退避缺 2×nodeTimeout 基数且无 4×nodeTimeout 重试冷却（超时后 1s 即重开选举 → 选举风暴）、P2-7 MEET 应答裸 PONG（无 gossip/槽位/纪元）、P2-8 UPDATE 空壳（零发送方）、P2-9 消息码冲突（未改且 0x0B/0x0C 新增仍撞 SHARD_MIGRATE/SHARD_ACK）、P2-10 gossip 条目全量 2048 字节位图（消息膨胀 ~20 倍/心跳 6-8KB）、P2-11 背压缺失、P2-12 ClusterLink.connected 不回写 + outboundBufferSize 死字段、P2-13 busPort 仅通告不消费、P2-14 ping/pong 时间戳死字段、P2-15 双 cluster_state 公式、P2-16 ADDSLOTS/REPLICATE/DELSLOTS 无条件 bump epoch + SET-CONFIG-EPOCH 无前置校验、P2-17 拓扑变更即同步全量写盘（且事件循环线程做磁盘 IO）、P2-18/19 CLUSTER INFO/NODES 输出字段缺失/时序错误、P2-20 孤儿连接竞态、P2-21 failTime 绕过（setState/reset 无生产调用方，当前潜伏）、P2-22 ClusterNode.slots 双记账、P2-23 总线服务端无连接上限、P2-24 锁内文件 IO/外部回调、P2-25 SlotMigrationManager 死代码（exportKey 仍读私有表，与 importKey 修复不对称）、P2-26 批量迁移删除阶段无条件 del（可删新值）。

**未修复（P3）**：P3-1 ordinal 作 wire 码、P3-2 decode 不校验 nodeId（且非法串穿透到 ClusterNode 构造，整条消息处理中止）、P3-3 lastInteractionTimes 无界增长、P3-4 scheduleAtFixedRate 单线程积压、P3-5 transient NPE 潜伏、P3-6 stats 非原子、P3-8 toString 无锁、P3-9 缺 NOFAILOVER、P3-11 progress>100%（生产不可达）、P3-14 setMigrating 校验、P3-17 InterruptedException、P3-18 集群未启用也占线程、P3-19 sendAndWait 2×timeout、P3-20 flags 输出顺序（HashSet 桶序）+ 注释与 Redis 实际相反。

---

## 2. 新发现问题（本轮确认，含首轮未覆盖）

### 2.1 P0 —— 数据安全 / 高可用整体失效

#### P0-新1：手动 failover 后 master 写永久冻结（集群级写冻结）

- **定位**：`FailoverManager.java:716`（`onManualFailoverStart` 中 `writePauseGate.pause()`）↔ `:788`（唯一 `resume()` 在 `abortManualFailover` 内）
- **问题**：master 收到 MFStart 后 `pause()` 并**不设置任何 master 侧状态**；`abortManualFailover` 只能由 `advanceManualFailover` 到达（要求 `manualState != NONE`，是 slave 侧状态，master 恒为 NONE）。成功路径同样冻结——slave 追平提权后 resume 的是 **slave 自己的** gate（未暂停，no-op）。后果链：master 写路径（RedisServerHandler.java:834-838）对暂停返回 `-LOADING cluster failover in progress, writes temporarily paused`——**master 一旦收到 MFStart，写永久拒绝直到进程重启**；旧 master 日后再次提权仍带 paused=true。
- **放大因素**：`OFFSET_CATCHUP_TOLERANCE = 0`（FailoverManager.java:145，注释"避免死锁"但值恒 0）；master pause 后 in-flight 写仍可能推进 offset → slave 追不平 → 30s 超时 abort → master 仍冻结。
- **修复**：master 侧记录暂停时刻，tick 中超过 2×nodeTimeout 自动 resume；或 slave 的 abort/提升路径向 master 发显式 MF_ABORT 消息；并在 performFailover/onFailoverResult/applySelfDemotion 角色变更点兜底 resume()。

#### P0-新2：默认配置下自动 failover 永久失效（MYSELF replOffset 恒 0）

- **定位**：`FailoverManager.java:320/351`（读 `me.getReplOffset()`）；`setReplOffset` 全部写入点仅在 GossipProtocol.java:976/1018/1078/1221——**全部是远端节点**，MYSELF 的 replOffset 全库无写入方（含 NettyRedisServer/ReplicationCoordinator，grep 证实）；`RdsConfig.java:258` 默认 `cluster-slave-validity-factor=10`、`cluster-node-timeout=15000`
- **问题**：`skipValidityCheck`（:350-367）：`myOffset=0`、兄弟 gossip offset 一旦超过 `allowedLag = 150000` 字节（正常流量极快达到），返回 false → `tryStartElection`（:274）**永久阻止所有 slave 发起选举** → 主节点宕机后集群不可写，高可用整体失效。rank 退避同步失效且**反转**（`myOffset=0` → 数据最新鲜的 slave rank 最大退避最久，陈旧 slave rank=0 先广播，配合"首投即定"陈旧数据胜选丢数据）。
- **修复**：GossipTask.tick 或 tryStartElection 内 `myNode.setReplOffset(replicationLifecycleListener.getReplicationOffset())`；rank/validity 直接读 listener 实时值。测试（FailoverRankBackoffTest:190 手工 setReplOffset）掩盖了该缺口，需补"MYSELF offset 由 listener 回填"的集成测试。

#### P0-新3：MIGRATE 源端删除/目标端导入不进入复制与 AOF 流 → 幽灵键

- **定位**：`RedisServerHandler.java:663-684`（MIGRATE 分发后直接 return，不经过 :847 的传播/AOF 路径）；源端删除在 `MigrateCommandHandler.java:242` 直接 `memoryStore.del`；目标端 `GossipProtocol.java:782-812` handleMigrateKey 直接写 memoryStore
- **问题**：源 master 删除已迁键后其 **slave 仍保留该键**，任意一次 failover 后"已迁移键"复活（幽灵键，数据双写分叉根因）；目标 master 导入的键 slave 缺失，failover 后丢键。Redis 7：MIGRATE 成功（非 COPY）时向副本/AOF 传播 DEL；目标端以 RESTORE 进入正常传播流。
- **修复**：源端删除走与普通写命令相同的传播路径（构造 DEL 传播帧）；目标端 importKey 成功后以等价帧进入复制与 AOF。

### 2.2 P1 —— 正确性 / 协议语义偏差

#### A. gossip 与槽位仲裁

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-1 | **PING/PONG 槽位仲裁用本地陈旧纪元 + 删除守卫恒真**：`syncSlotsFromNode(senderId, slots, senderNode.getConfigEpoch())` 传本地记录而非 `ping.getSenderConfigEpoch()`，且无 `setConfigEpochIfGreater`（对比 MEET 路径 :1060-1071）；删除守卫 `configEpoch >= node.getConfigEpoch()` 恒真 | `GossipProtocol.java:964-965/1007-1008` + `ClusterConfig.java:380-390` | ① failover 后新 master 的高纪元无法经其自身 PING/PONG 直接到达，双 master 观感窗口拉长；② 发送方广告位图缺哪些槽，本节点无条件删哪些槽（含自己刚 ADDSLOTS 的槽）——Redis 的 `clusterUpdateSlotsConfigWith` 从不因 gossip 删槽 |
| N-2 | **入站消息无 MYSELF 守卫**：伪造本节点 ID 的 PING/PONG/MEET 可经 `syncSenderRole`（flags=SLAVE）直接把自己降级、经 `syncSlotsFromNode(空位图)` 删光自己槽位；`handleFail` 可自标 FAIL | `GossipProtocol.java:410-478/598-668/708-737` | Redis 对 sender==myself 有专门忽略分支。自降级/删槽/自标 FAIL 三种攻击都不需要任何密钥，仅需能连总线端口 |
| N-3 | **slots 位图无 16384 上限**：decode 端只校验不越界（上限 16MB），`syncSlotsFromNode` 对 slot≥16384 抛异常 → 槽位同步**中途半应用**（已转移的生效、剩余中止）+ 连接关闭 | `GossipNodeInfo.java:523-530`、`PingMessage.java:403-413` + `ClusterConfig.java:465-469` | 坏/恶意节点可致本地槽位表不一致状态；Redis 对 gossip 条目有固定 104B 上限 |
| N-4 | **非法 nodeId 穿透**：decode 端校验失败保留非法串 → `processGossipNodes` 构造 ClusterNode 时抛异常，整条 PING/PONG/MEET 处理中止，发送方收不到 PONG 被误 PFAIL | `GossipNodeInfo.java:460-465` + `GossipProtocol.java:1138-1145` | 一条坏条目瘫痪一个方向的消息处理；应逐条目 try-catch 隔离 |
| N-5 | **FAIL 节点被排除出 gossip 段**：`selectGossipNodes` 过滤 `isFail()`，FAIL 知识只依赖一次性 `broadcastFail`（只发给当前已连接节点、不重试）→ 分区恢复/当时未连接的节点永远只看到 PFAIL，其 slave 永不触发选举 | `GossipProtocol.java:885/895` | Redis 会把 FAIL/PFAIL 节点包含在 gossip 段作为丢包兜底传播通道 |
| N-6 | **gossip FAIL 标志绕过多数判定**：gossip 段 FAIL 直接应用（仅 epoch 门控），Redis 是先转 failure report（sender 须为 master）再多数判定 | `GossipProtocol.java:1176-1179` | 单节点错误视图可经 gossip 直推全网 FAIL |
| N-7 | **ACK 伪造 → 源键误删**：requestId 单调从 1 递增完全可预测，`completeResponse` 只查 requestId 不校验 ACK 的 sender/通道；总线无认证，任何能连 bus 端口的对端伪造 MIGRATE_KEY_ACK 即可命中在途请求 → 源端删除从未导入的键（数据丢失，本实现独有攻击面） | `ClusterBusClient.java:103/463-474` + `MigrateCommandHandler.java:239-243` | Redis MIGRATE 走客户端端口且无"ACK 即删源键"路径；修复：sendAndWait 记录 (requestId→目标 nodeId)，completeResponse 校验来源；计数器初值随机化 |
| N-8 | **消息类型码与 Redis 7 全冲突**：0x00-0x07 恰好与 Redis 前 8 码相同，0x08-0x0C 占 Redis 的 MFSTART/MODULE/PUBLISHSHARD/SHARD_MIGRATE/SHARD_ACK | `GossipMessageType.java:16-78` | 混布时：Redis 把 FAILOVER_RESULT(0x08) 当 MFSTART 暂停 master 写；本实现把 Redis 的 MFSTART 当 FAILOVER_RESULT 误触发胜选。建议自定义码从 0x40 起 |

#### B. failover 与故障检测

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-9 | **FailoverResult 可伪造全槽位接管**：缺 sender==winner 校验；`getInheritedSlots()` 整体直接应用无"槽位应属被降级旧 master"交叉校验；`setCurrentEpoch(msg epoch)` 无条件执行（:959） | `FailoverManager.java:875-984` | 任意已知节点可伪造 {winner=自己, epoch=当前+1, slots=全 16384} → 全网槽位被接管 + currentEpoch 被无限抬高，合法选举全被"过期纪元"拒绝 |
| N-10 | **FAIL 消息无 MYSELF 守卫**（P1-10 确认未修） | `GossipProtocol.java:708-737` | 已知节点（含 slave）可声明本节点 FAIL → 失去投票/选举权并自我传播；Redis 明确排除 MYSELF |
| N-11 | **选举无 4×nodeTimeout 重试冷却**：超时后 resetElectionState 下一轮 tick（1s）即可重开选举 | `FailoverManager.java:383-386` | 选举风暴/重复广播；Redis 为 `auth_retry_time = 2×auth_timeout = 4×node_timeout`；且退避缺固定 500ms 基数（rank 步长 500 vs Redis 1000） |
| N-12 | **votesCast 陈旧条目拒新纪元首投**：gossip/结果消息抬升 currentEpoch 后不清 votesCast → 新纪元首个合法投票被误拒（选举停滞 2×nodeTimeout+） | `FailoverManager.java:495/522` + `GossipProtocol.java:973/1015` | 自愈型但延迟可观 |
| N-13 | **isStaleMaster 降级过宽**：任何"无槽位 + 低纪元"的 master（新建空 master、reshard 迁空者）被任意 FailoverResult 降级为 winner 的 slave | `FailoverManager.java:939-941` | Redis 只降级与 inherited slots 有交集的旧 master |
| N-14 | **投票者未要求持槽 + 无 voted_time 冷却**：Redis 要求投票者 `myself->numslots > 0`（cluster.c:4033）且投票后有 2×nodeTimeout 冷却；本实现无对应门 | `FailoverManager.java:457-534` | 选举节奏比 Redis 激进 |
| N-15 | **候选 configEpoch 不参与投票裁决**：AUTH_REQUEST 的 configEpoch 仅日志使用，Redis 会与槽位 owner 的 configEpoch 比较拒绝陈旧候选（cluster.c:4090-4099） | `FailoverManager.java:488` | 陈旧候选防护缺失 |

#### C. 路由 / 命令协议面

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-16 | **WATCH/MULTI/EXEC 完全绕过集群路由与 CROSSSLOT**：WATCH 提前分流（:500-502）；事务入队只校验命令名与 arity（:696-715）；EXEC 直接执行无路由检查（:1817-1905） | `RedisServerHandler.java` | 跨槽事务在错误节点**静默执行**（写后键"消失"）；WATCH 在错误节点监视不存在键，EXEC 永不中止。Redis 入队/执行路径有完整集群校验 |
| N-17 | **XREAD/XREADGROUP/XINFO 键提取错误**：默认分支取 args[1]（"COUNT"/"GROUP"/"STREAM"）作路由键 | `RedisServerHandler.java:2625-2628` | 已实现命令在集群中读被 MOVED 到无关节点静默返回空；多流 CROSSSLOT 不校验。Redis 为 `xreadGetKeys`（STREAMS 后）与 XINFO 子命令取键 |
| N-18 | **只读白名单缺 SUNION/SINTER/SDIFF/XREADGROUP** | `RedisServerHandler.java:1502-1585` | slave 上 READONLY 客户端执行被误判写 → 错误 `-READONLY`；fail 时 allow-reads-when-down 被误拒；且被当作写传播到 backlog/AOF 污染复制流 |
| N-19 | **CLUSTER 子命令仍缺 8 个**（SHARDS/LINKS/RESET/COUNT-FAILURE-REPORTS/ADDSLOTSRANGE/DELSLOTSRANGE/REFRESH/HELP） | `ClusterCommandHandler.java:190-234` | 现代客户端/redis-cli create 不可用 |
| N-20 | **错误串中文泄漏**：槽越界/非数字返回中文 `-ERR`（"槽位号必须在0-16383范围内"），客户端按错误串匹配即失败 | `ClusterCommandHandler.java:740/809/868/1012/1057` + `SlotUtils.java:172-177` | Redis 为 `-ERR Invalid slot specified` / `value is not an integer or out of range` |
| N-21 | **SETSLOT NODE 无前置校验**：不校验"本地拥有/IMPORTING/MIGRATING"，任意节点可把任意槽直接指给任意节点 | `ClusterCommandHandler.java:920-965` | Redis 报 `I'm not the owner of hash slot X`；误操作可打乱槽位表 |
| N-22 | **SETSLOT IMPORTING 无"非 owner"校验**：对本地已拥有槽位误设 IMPORTING 后，该槽所有请求无 ASKING 时被 ASK 回源，本节点正常读写被破坏（MIGRATING 分支有 :900-902 的 owner 校验，IMPORTING 没有） | `ClusterCommandHandler.java:874-888` | 运维误操作即自毁 |
| N-23 | **ADDSLOTS busy 校验读陈旧 slotManager**：与路由权威源 clusterConfig 不一致；gossip 已把槽分给别人而 slotManager 陈旧为空时 ADDSLOTS"成功"造成双 owner | `ClusterCommandHandler.java:747` vs `:940`/`RedisServerHandler.java:2894` | 槽位归属分叉的新入口 |

#### D. 配置 / 持久化

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-24 | **restoreClusterFromConfig 不恢复 lastVoteEpoch**（P1-19 生产路径失效） | `NettyRedisServer.java:504-535`（对照 ClusterConfig.setLastVoteEpoch :506） | 投票节点重启后同纪元内可重投 → 同一纪元两张票 → 双主分脑 |
| N-25 | **applySelfDemotion 无条件回退 currentEpoch**：`setCurrentEpoch(newConfigEpoch)` 可把被 ADDSLOTS/选举推高的 currentEpoch 回退 | `FailoverManager.java:1040`（对照 onFailoverResult :959 用 setEpochIfGreater） | 防回放/投票门控被削弱，重启后可能同纪元重投 |
| N-26 | **双 cluster_state 公式并存且结论可相反**：GossipTask（quorum+全槽覆盖，PFAIL master 计入 available、FAIL 计入分母）vs ClusterStateManager.isClusterOk（全槽+owner 非 FAIL，无 quorum）交替写同一 `state` 字段；CLUSTER INFO 与命令门控读的是谁最后写的值 | `GossipTask.java:243-275` + `ClusterStateManager.java:53-74/272-275` | 例：FAIL master 持槽但多数主可达 → 两公式一个 ok 一个 fail，状态抖动；Redis 公式：PFAIL master 不计 available、分母也不含 FAIL/PFAIL |
| N-27 | **save 后无条件 clearDirty 竞态**：保存期间发生仅 markDirty 的变更（recordVoteEpoch :520-526 不触发回调）则脏被抹掉，该票永不落盘 | `NettyRedisServer.java:663` + `ClusterConfig.java:520-526` | 崩溃后同纪元重投 |
| N-28 | **写盘无 fsync**：FileWriter 写毕直接 Files.move，断电可得空/截断 nodes.conf，load 又只 warn 不拒绝 → 残缺拓扑启动 | `ClusterConfigPersister.java:65-148` | Redis rewriteConfig 会 fsync |
| N-29 | **nodes.conf ping/pong 字段写 0**（P1-18 确认未修） | `ClusterConfigPersister.java:305-311` | 真实 Redis 加载后全节点立即 PFAIL |

#### E. 迁移

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-30 | **destination-db 解析后被丢弃**：`MIGRATE host port key 3 5000` 的 db 参数未进消息，importKey 硬编码 db0 → 静默错库 | `MigrateCommandHandler.java:214-216/267-269/425-427` + `MigrateKeyMessage.java:60-66` + `SlotMigrationManager.java:299-301` | 源 db3 键被删、目标 db0 出现键——数据可达性完全错乱 |
| N-31 | **ZSET/STREAM 键不可迁移**：值类型 ZSetStore（DefaultMemoryStore.java:2522，未实现 Serializable）、Stream（core/stream/Stream.java:38，含锁/Logger 不可序列化）→ `NotSerializableException` → dumpKey 返回 null → 单键 `-ERR error dumping key`。batch6 加的 ObjectInputFilter 白名单（core.stream.*/core.store.*）**只作用于反序列化端，序列化端先失败**，白名单无效 | `SlotMigrationManager.java:511-567` + `MigrateCommandHandler.java:391-407` | P1-17 声称"使 zset/stream 可跨节点迁移"未达成 |
| N-32 | **MIGRATE 绕过认证、cluster_state 门控与写暂停门控**：分发在 RedisServerHandler.java:663，早于 AUTH（:717）、cluster_state（:781）、写暂停（:834） | `RedisServerHandler.java:663-684` | 未认证客户端可发起 MIGRATE 删本节点任意键；fail/暂停期间 MIGRATE 仍删键 |
| N-33 | **批量 KEYS 模式缺键语义与 Redis 相反**：缺失键计入失败 → 整批失败源端不删任何键；部分键已导入目标端 → 重试不带 REPLACE 必 BUSYKEY 死锁 | `MigrateCommandHandler.java:285-288/336-354` | Redis 对缺失键跳过（redis-cli reshard 依赖此行为） |
| N-34 | **无 DUMP 版本/CRC 校验**：裸字节传输，跨版本 serialVersionUID 变更/损坏会静默导入错误数据 | `MigrateKeyMessage.java:101-153` + `SlotMigrationManager.java:532-568` | Redis 有 verifyDumpPayload（RDB 版本+CRC64） |
| N-35 | **ASKING 生命周期偏差**：跨非键命令存活（`ASKING; PING; GET` 仍放行）、EXEC 不清除、MULTI 内 ASKING 立即执行而非排队 | `RedisServerHandler.java:2976/2994/3015/1742-1950/1064-1069` | Redis 每条命令执行后清除一次性标志 |
| N-36 | **重定向兜底静默放行**：IMPORTING 源未知/MIGRATING 目标未知时 return null 放行（无 ASKING 的写可产生孤儿键） | `RedisServerHandler.java:2980-2982/3001-3003` | Redis 在目标节点未知时回错误而非放行 |

#### F. 总线 / 连接管理

| # | 问题 | 定位 | 与 Redis 7 偏差 / 后果 |
|---|---|---|---|
| N-37 | **cluster-announce-bus-port 仅通告不消费**：总线服务端永远绑定 servicePort+10000；出站连接永远连对端 servicePort+10000，`node.getBusPort()` 从不参与 | `NettyRedisServer.java:617-619` + `ClusterBusServer.java:78` + `ClusterBusClient.java:173` | NAT/防火墙自定义总线端口场景（该配置的唯一意义）集群无法组建 |
| N-38 | **16MB 解码上限 + 编码无预检**：大键迁移（>16MB 序列化载荷）直接断开整条总线连接 → 该连接上所有心跳/迁移中断 + 重连 churn | `ClusterBusCodec.java:72/98-101` + `MigrateCommandHandler.java:302` | Redis MIGRATE 走客户端端口流式传输无此限制 |
| N-39 | **重连端点复活**：connect() 无条件 `nodeEndpoints.put`，已删除节点被在途重连任务复活 → 僵尸重连循环；CLUSTER FORGET 不调用 busClient.disconnect | `ClusterBusClient.java:178` + `ClusterCommandHandler.java:638-660` | 删除的节点永远"杀不死" |
| N-40 | **并发 connect 无互斥**：重复连接泄漏（先写通道不进映射也不关闭） | `ClusterBusClient.java:166-206` | 每条节点可累计多条永久打开连接 |
| N-41 | **重业务在总线 EventLoop 同步执行**：importKey（反序列化+存储）在 Netty 事件循环上同步跑，大键导入阻塞该 EventLoop 全部心跳 → 放大 PFAIL 误判；sendAndWait 阻塞最长 2×timeout | `ClusterBusHandler.java:150-181` + `SlotMigrationManager.java:276-311` + `ClusterBusClient.java:429/439` | 单键迁移可拖垮整个事件循环 |
| N-42 | **MIGRATE 在 Netty IO 线程同步等待**：handle() 由 channelRead 直接调用，批量 N 键 × 5s 默认超时 = 阻塞事件循环 10N 秒 | `RedisServerHandler.java:663-684` + `ClusterBusClient.java:429/439` | 一个慢/断目标节点卡死该 event loop 上所有客户端命令 |
| N-43 | **对未知发送方回 PONG 泄露拓扑**：任何能连 bus 端口的 TCP 对端可枚举全集群节点/槽位/纪元并可注入任意节点视图 | `ClusterBusHandler.java:284-297` + `GossipProtocol.java:410-450` | Redis 对未知发送方非 MEET 消息直接关闭连接 |

---

## 3. 与 Redis 7.x 协议不兼容专项清单（客户端/工具可观测面）

| # | 不兼容点 | 现状 | 影响 |
|---|---|---|---|
| 1 | 总线 wire 格式：自定义 45 字节头（40 sender + 1 type + 4 len），无 Redis `clusterMsg` 的 `CLUB` 签名/校验和/固定字段 | 设计如此，文档未明示 | 与真实 Redis 节点混布双向互踢；第三方集群工具不可用 |
| 2 | 消息类型码 0x08-0x0C 与 Redis 7 冲突（MFSTART/MODULE/PUBLISHSHARD/SHARD_MIGRATE/SHARD_ACK） | 未修 | 混布时误判对方消息语义 |
| 3 | nodes.conf 无 `vars currentEpoch lastVoteEpoch` 段；`[slot->-id]` 迁移方括号不支持；ping/pong 写 0；flags 顺序非 Redis 规范序；重复槽位不报错 | 部分修（写方向字段基本对齐） | 文件不可互操作；真实 Redis 加载后 epoch 归零 + 全节点 PFAIL 误判 |
| 4 | CLUSTER 子命令缺 SHARDS/ADDSLOTSRANGE/DELSLOTSRANGE/RESET/LINKS/COUNT-FAILURE-REPORTS/REFRESH/HELP | 未修 | redis-cli --cluster create/check、Lettuce/Redisson 新版拓扑发现不可用 |
| 5 | XREAD/XREADGROUP/XINFO 键提取错误 → 错路由/漏 CROSSSLOT | 未修 | 已实现命令在集群模式静默错乱 |
| 6 | WATCH/MULTI/EXEC 绕过集群路由 | 未修 | 跨槽事务静默执行，数据一致性与 Redis 语义不符 |
| 7 | 错误串偏差：槽越界中文 `-ERR`；FAILOVER 用 `-MASTERDOWN`（Redis 为 `-ERR`）；非集群模式 CLUSTER 返回 `unknown command`（Redis 为 `cluster support disabled`）；GETKEYSINSLOT 已修 | 部分修 | 按错误串匹配的客户端/脚本失效 |
| 8 | CLUSTER INFO：缺 per-type 消息计数与 total_cluster_links_*；cluster_my_epoch 输出陈旧死字段 | 未修 | 监控/工具解析字段缺失 |
| 9 | CLUSTER NODES：跳过 NOADDR 节点；flags 顺序 fail?/fail 颠倒、master,slave 可并存；ping-sent/pong-recv 输出绝对时间戳（Redis 为相对毫秒） | 未修 | 解析兼容性 |
| 10 | CLUSTER SLOTS 不输出 importing/migrating 槽段（Redis 7 有） | 未修 | 迁移中拓扑感知缺失 |
| 11 | MIGRATE：KEYS 缺键不跳过；destination-db 丢弃；无 DUMP/CRC；载荷为 Java 序列化（与真实 Redis 不可互操作） | 部分修 | reshard 工具行为差异 + 内部数据编码不兼容 |
| 12 | 选举时序：退避无 500ms 基数/rank 步长 500 vs 1000/无 4×nodeTimeout 重试冷却；投票者无持槽要求/无 voted_time 冷却 | 未修 | 选举节奏与 Redis 差异，风暴场景 |
| 13 | 手动 failover 写暂停期返回 `-LOADING ... writes temporarily paused`（Redis 用 CLIENT PAUSE 语义阻塞客户端） | 设计偏差 | 客户端可见行为差异（次要） |
| 14 | gossip 永不删槽 vs 本实现 syncSlotsFromNode 删除语义（且守卫失效） | 自创语义 | 与 Redis "删除只发生在 SETSLOT/failover" 的结构性差异 |

---

## 4. 已验证正确 / 做得好的部分（避免误伤）

- ✅ **批次 1-6 核心修复质量高**：P0-1~4 全部闭环且有测试（requestId 往返、NOKEY/BUSYKEY、AS 重定向顺序、candidateId 计数）；P1-7/8（master 票门控 + 票过期 + 恢复撤销）实现严谨；P1-13/14 错误串与 Redis 逐字一致
- ✅ **epoch 门控设计**（严格大于、先捕获基线、setEpochIfGreater）整体严谨；P2-1 同 epoch 决胜已实现（虽方向与 Redis 相反）
- ✅ **CRC16/hash tag/错误串**：MOVED/ASK/CLUSTERDOWN/CROSSSLOT 格式与 Redis 7 一致（首轮已实测验证，本轮未发现回退）
- ✅ **总线 requestId 机制**：分配-注册-发送顺序无丢失窗口；finally 清理覆盖成功/超时/中断/发送失败；close 取消全部
- ✅ **failover 胜选收敛**：epoch 门控 + FailoverResult 广播 + gossip 仲裁 + MYSELF 自降级门控链路完整（8-03 修复成果未回退）
- ✅ **重连治理**：退避封顶 64s、任务执行时校验 closed/节点存在、close 清空映射
- ✅ **ObjectInputFilter 白名单**防反序列化 RCE（虽然 zset/stream 序列化侧先失败）
- ✅ **nodes.conf 原子写**（tmp+rename）、失败保留脏标记不中断运行

---

## 5. 修复优先级建议

| 优先级 | 事项 | 理由 |
|---|---|---|
| **第一批（P0）** | ① MYSELF replOffset 由 replicationListener 回填（P0-新2）——一行接线即恢复自动 failover；② master 侧手动 failover 暂停超时自动 resume + 角色变更点兜底（P0-新1）；③ MIGRATE 源删/目标导入进复制与 AOF 流（P0-新3） | 三个 P0 分别冻结高可用、冻结写、造成副本分叉，是上线硬门槛 |
| **第二批（P1 闭环）** | ① restoreClusterFromConfig 恢复 lastVoteEpoch（N-24）；② PING/PONG 用 header configEpoch + setConfigEpochIfGreater（N-1）+ syncSlotsFromNode markDirty（P1-4）；③ applySelfDemotion 改 setEpochIfGreater（N-25）；④ ACK 来源校验（N-7） | 防双主/分脑/持久化失效，多数是数行修复 |
| **第三批（P1 协议面）** | ① XREAD/XREADGROUP 键提取 + 只读白名单补 SUNION/SINTER/SDIFF/XREADGROUP（N-17/18）；② MULTI/EXEC 集群校验（N-16）；③ MIGRATE destination-db 透传 + zset/stream 序列化（N-30/31）；④ FAIL/MYSELF 守卫（N-2/N-10）；⑤ slots 位图上限 + 非法 nodeId 隔离（N-3/N-4） | 数据一致性 + 已实现命令的集群正确性 |
| **第四批（互操作）** | CLUSTER SHARDS/ADDSLOTSRANGE/DELSLOTSRANGE/HELP（N-19）；nodes.conf vars 段 + ping/pong 真实时间戳 + 迁移方括号（N-29）；消息码重编号（N-8）；错误串英文对齐（N-20） | redis-cli/现代客户端可用性 |
| **第五批（failover 深化）** | 选举 4×nodeTimeout 冷却 + 500ms 基数（N-11）；votesCast 清理（N-12）；FailoverResult sender==winner + slots 来源校验（N-9）；isStaleMaster 收窄（N-13）；投票者持槽要求 + voted_time（N-14/15） | 选举正确性与 Redis 对齐 |
| **后续** | 背压/16MB 大键/总线端口消费（N-37/38/40）；连接治理死角（N-39）；cluster_state 单公式（N-26）；save 节流 + fsync（N-27/28）；CLUSTER INFO/NODES 输出补全 | 规模化与运维可观测性 |

---

## 6. 评审范围与方法附录

- 子代理分工：总线/消息编解码（ClusterBusClient/Server/Handler/Codec/ClusterLink）、failover/故障检测（FailoverManager/FailureDetector/消息类/WritePauseGate）、配置持久化（ClusterConfig/Persister/StateManager/Stats）、迁移（SlotMigrationManager/MigrateCommandHandler/状态类）、gossip/节点状态（GossipProtocol/GossipTask/GossipNodeInfo/各类 Message/ClusterNode）、路由/命令协议面（ClusterCommandHandler/DefaultSlotManager/RedisServerHandler 集群段/NettyRedisServer 接线）
- 人工复核：FailoverManager 240-420（选举/rank/validity）、695-800（手动 failover）；GossipProtocol 940-1020（PING/PONG）、700-740（FAIL）；ClusterConfig 340-395（syncSlotsFromNode）；NettyRedisServer 500-540（恢复链）、655-671（保存）；RedisServerHandler 820-845/2563-2630/2785-3020（门控/键提取/重定向）；ClusterBusClient 455-480（ACK 匹配）；SlotMigrationManager 505-570（序列化）；ClusterNode 190-215/535-560（failTime）
- 与 Redis 7.2 cluster.c 对照项：投票规则（首投即定一致）、FAIL 消息 MYSELF 守卫、gossip 不删槽、选举时序（auth_timeout/auth_retry_time）、投票者条件、FAIL 清除语义、clusterUpdateSlotsConfigWith Rule 3
- 测试基线：cluster 模块 452 测试（batch6 后 0 失败）；server 559 测试仅 Monitor/PubSub 既有污染

*评审方式：6 子代理并行深度评审 + 关键路径人工逐行复核。纯评审，未修改任何代码。*
