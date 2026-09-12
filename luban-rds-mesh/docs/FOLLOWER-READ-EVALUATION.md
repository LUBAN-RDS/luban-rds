# 从节点读（Follower Read）可行性评估

| 字段 | 内容 |
|------|------|
| 评估对象 | mesh 模式是否允许 **Follower 本地服务读命令**（GET/HGET/… 及只读 EVAL） |
| 评估日期 | 2026-09-12 |
| 基线版本 | v1.0.25（master `a848d88`） |
| 关联文档 | `AUDIT-REPORT-MESH-READ-PATH.md`（§6.3 方案 B / §7 F1-F4）、`docs/DESIGN.md` §5.7 |
| 代码改动 | **无**（本文件为评估，不涉及实现） |

---

## 0. 结论

**可以做，而且是正确的方向；但不能"直接放开"**——`MeshWriteGate.read` 里那句
`if (!meshNode.isLeader()) throw MovedToLeaderException` 如果只是删掉，Follower 会拿自己可能陈旧的
本地状态作答，强一致卖点当场破产（这正是 Leader Lease 当初要解决的问题，见 DESIGN §5.7）。

放开 Follower 读需要三件东西同时到位：

1. **readIndex 获取**：Follower 向 Leader 要一个"读点"（= Leader 的 `commitIndex`），并且这个读点必须
   来自一个**已被证明仍是真 Leader** 的节点；
2. **apply 屏障**：Follower 拿到读点后，必须等自己 `lastApplied >= readIndex` 才能本地执行；
3. **超时回落 MOVED**：等不到（或没有 Leader）就照旧 MOVED 到 Leader —— **最坏退化成今天的行为，
   绝不返回陈旧值**。这条是整套方案的安全底线。

收益（有实测支撑）：消除**当前 100% 的读双跳**、把读负载从 Leader（现在承担 100%）分摊到两个 Follower、
集群日志量立降约 38%（详见 §2）。

成本：每次读多一个很小的总线 RPC（可用"Leader 租约窗口缓存"摊薄到约 10 次/秒，从而净赚一个 RTT）、
新增一个 `ApplyBarrier` 组件、以及一致性关键路径上的测试成本。

**建议**：作为独立 change 做（full workflow），**默认关闭**，分阶段：前置项 → `readindex` 模式 →
时钟无关版（顺带修审计 F2）→ 可选的陈旧读模式。

---

## 1. 为什么"无条件本地读"是错的（正确性论证）

| 问题 | 说明 |
|------|------|
| Follower 的 `commitIndex` 不是它自己算出来的 | 它只是 Leader 通过 AppendEntries 捎带的 `leaderCommit` 的下界（`RaftStateMachine.java:367-368`：`commitIndex = min(leaderCommit, lastLogIndex)`）。它无法自证"我仍在多数派里"。 |
| 分区 Follower 无法自我纠正 | 一个被隔离的 Follower，`commitIndex`/`lastApplied` 可以无限期停在旧值，而它自己**没有任何信息能判断自己落后**。 |
| "读己之写"会被破坏 | 客户端在 Leader 上写入并收到 +OK（该条目已 commit 且 apply），随即从某个落后的 Follower 读 → 读到旧值。对**同一会话键做读-改-写**的应用（本项目的 OA 应用就是，见 §4）会产生业务级错误。 |

所以 Follower 读的正确形态是标准 Raft 的 **ReadIndex**：**读点由 Leader 给出，执行由 Follower 完成，
且 Follower 必须先追上该读点**。

---

## 2. 客户端现状（为什么这个改动收益很大）

上一轮审计已实测（报告 §10，窗口 21:45:00–21:47:00）：

- 读命令到达分布是 **Leader:Follower:Follower = 2:1:1**——即 **每一次读都先打到 Follower，被 MOVED
  后才在 Leader 上执行**（100% 双跳）；EVAL 同理 96% 双跳。
- 根因已用字节码实证：`org.redisson.config.BaseMasterSlaveServersConfig` 构造器里
  `readMode = ReadMode.SLAVE` 是**默认值**（`javap -c` 可见 `getstatic ReadMode.SLAVE; putfield readMode`），
  而 `ClusterServersConfig extends BaseMasterSlaveServersConfig`；同时 mesh 的 `CLUSTER NODES`
  已经把两个 Follower 标成 `slave <leaderNodeId>`（`MeshClusterCommands.java:378`）。
  两者叠加 → **Redisson 默认就会把读发到 Follower**。
- 现状下这些读到 Follower 只能拿 MOVED；Follower 上 94% 的日志行就是这些"收到即拒绝"的记录。

**含义：放开 Follower 读不需要改客户端**——Redisson 已经在往 Follower 发读了，只是现在被回绝。
反过来，如果只按 P1-7 做运维规避（`readMode=MASTER`），读会全部压回 Leader，等于放弃读扩展。

---

## 3. 方案设计

### 3.1 三个必要构件

**① readIndex 获取（Follower → Leader，新增一条小 RPC）**

- 新增消息类型 `READ_INDEX_REQ` / `READ_INDEX_RESP`（建议码位 `0x66` / `0x67`；
  注意 `MessageType.java` 的解码范围校验当前写死 0x60-0x64，需同步放宽）。
- Leader 侧收到请求时：若自己不是 Leader → 回 `success=false` + 已知 Leader 地址；
  若是 Leader 且**租约有效** → 回 `readIndex = state.commitIndex` + `term`。
- **租约有效作为"我是真 Leader"的证明**：这与 DESIGN §5.7 现行 Leader 读用的是同一个假设，
  **不新增时钟假设**。而且已经核实过一条更强的性质（见审计 §7 F4）：`onAppendEntriesResponse` 内
  `maybeAdvanceCommitIndex → applyCommittedEntries → leaseRefresher.run()` 的顺序 + P1-2 的
  `matchIndex >= commitIndex` 前提，保证"租约有效 ⇒ 该时刻已提交条目均已 apply"。
- 若部署环境时钟不可靠：Leader 侧改为"收到 REQ 时主动发一轮心跳收多数派 ACK 再回"（见 §4 方案 B2，
  顺带把审计 F2——`readindex` 模式名不副实的实现——一并修掉）。

**② apply 屏障（Follower 本地，新组件）**

- 需要 `awaitApplied(readIndex, timeoutMs)`。当前**没有任何等待 apply 推进的机制**
  （全模块只有 `LeaseManager` 有 `Condition`，`state.lastApplied` 只是个 volatile 字段，由 raft 线程推进）。
- 建议新增 `ApplyBarrier`（`ReentrantLock` + `Condition`），在以下时机 signal：
  `LogReplicator.applyCommittedEntries()` 每成功 apply 一条之后 / 快照安装完成 /
  角色切换（becomeLeader、TO_FOLLOWER）。
- 判定用绝对索引：`appliedIndex = state.lastApplied`；快照安装后 `lastApplied` 会跳到
  `lastIncludedIndex`，因此 `readIndex <= lastIncludedIndex` 天然满足，无需特例（但必须 signal 唤醒等待者）。

**③ 超时回落 MOVED（安全底线）**

- 任一环节失败——取不到 readIndex、term 变了、等待超时——一律走现有 `redirectResponse(key)`
  返回 MOVED 给 Leader。
- **这条保证"Follower 读永远不会返回未达 readIndex 的状态"**，同时可用性不降级：最坏情况就是今天的双跳。

### 3.2 时序

```
client ──GET k──▶ Follower
                   ├─ 1. 取 readIndex：本地缓存命中？→ 直接用
                   │                 否则 READ_INDEX_REQ → Leader（租约有效则回 commitIndex+term）
                   ├─ 2. awaitApplied(readIndex, maxWaitMs)  ← ApplyBarrier
                   ├─ 3. 本地 handler.handle(upperName, db, args, rawStore) → 响应字节
                   └─ 任一步失败/超时 → -MOVED <slot> <leader ip:port>
```

### 3.3 readIndex 缓存（性能关键，必须做）

> **实施修正（2026-09-12，`fix-mesh-follower-read` 设计阶段）**：本节原建议"缓存粒度跟随 Leader 租约窗口
> （1200ms）"，与 §3.5「Follower 读 = 线性一致读」和 §4「会话键读-改-写不能陈旧读」自相矛盾——缓存 1200ms
> 意味着读点可能比一次已返回 +OK 的写更旧。实施决策改为**短窗口缓存**：默认 **100ms**
> （`mesh-follower-read-cache-ms`），并以**请求发出时刻**为年龄基准，使陈旧上界
> = 缓存有效期 + 网络往返，可被审计；`=0` 关闭缓存恢复严格线性一致。
> 理由：`off`/`cache-ms=0` 的 ReadIndex 路径仍是 1 个客户端 RTT + 1 个**集群内** RTT，
> 对比今天 MOVED 的 2 个**客户端** RTT 已是净赚；缓存主要用于消除集群内 RTT 与 RPC 计数，
> 不值得为它放弃线性一致（有界陈旧是**已记录并接受的语义代价**，不再是"顺带得到"）。
> 另：§3.3 的"净收益 = 省掉一次 MOVED 往返"框架不准确——省掉的第二个 RTT 是客户端↔服务端的，
> 换成 Follower↔Leader 的集群内 RTT，两者量级不同。

- 缓存粒度由 `mesh-follower-read-cache-ms` 决定（默认 100ms）：窗口内 Follower 复用同一个 readIndex。
  每次 RPC 的计数因此从"每次读一次"降到"每窗口一次"。
- 缓存必须带：**剩余窗口 TTL**（过期即失效）、**Leader term**（term 变化即失效）、
  Leader 变更/收到更高 term 帧/apply halt 时立即整体失效。
- 并发 miss 用 single-flight 合并（`MeshBusClient.send` 是 fire-and-forget，故响应靠 requestId 关联）。
- 若配置为 0（不缓存）：等于用 1 次集群内 RPC 换掉 1 次 MOVED 双跳，赚负载分担与集群内 RTT 差，
  但失去吞吐上限收益——需要严格线性一致时应选此项。

### 3.4 必须处理的边界

| 场景 | 处理方式 |
|------|----------|
| **启动重放中**（WAL 重放 + 快照加载） | **必须先拒读 → MOVED**。当前模块**没有**重放/就绪标志（已核实无 `ready`/`keysLoaded` 门），放开 Follower 读后这会变成"用半加载的 store 作答"，是本方案新增的**必修前置项**。 |
| 快照安装（`lastIncludedIndex` 跳跃） | 安装完成即 signal 屏障；`readIndex <= lastIncludedIndex` 直接判满足。 |
| Leader 变更 / 收到更高 term | 清空 readIndex 缓存 → MOVED / 等新 Leader 稳定。 |
| 无 Leader（CLUSTERDOWN 窗口） | 与今天一致（MOVED / CLUSTERDOWN，客户端退避重试）。 |
| **apply fail-stop（审计 F1）** | `lastApplied` 冻结 → 屏障必然超时 → 回落 MOVED。**Follower 侧因此比 Leader 侧更安全**（Leader 侧现在的 F1 缺陷会静默返回陈旧值）。但 F1 仍必须作为前置项先修，否则 Leader 读仍是坏的。 |
| 只读 EVAL / EVALSHA | Follower 本地执行要求脚本已在**本地**缓存。脚本表已随快照复制（P1-4 修复）且 `SCRIPT LOAD` 走 Raft，但 `EVALSHA` 仍可能未命中——**命中不了必须回落 MOVED，绝不能回 `-NOSCRIPT`**（那会让应用看到错误而不是读到一个值）。 |
| 写命令 | 不受影响，继续 MOVED 到 Leader（含 `EXEC`——mesh 已禁用事务）。 |
| `updateAccessTime` / 懒过期 | Follower 读会开始产生访问时间与懒删（本地、不复制）。淘汰输入差异属于**已记录的 P1-6**（`maxmemory>0` 才触发，默认 0 休眠）→ 接受并在文档标注。 |

### 3.5 一致性语义（写进文档的承诺）

- `readindex` + `cache-ms=0`：**Follower 读 = 线性一致读**，强度与现行 Leader 租约读相同（同样依赖 NTP 前提）。
- `readindex` + `cache-ms=N`（默认 100）：**有界陈旧读**，上界 = N + 网络往返；
  窗口内可能读不到一个已返回 +OK 的写。这是**已记录并接受的语义代价**（实施修正见 §3.3）。
- `readindex-strict`（时钟无关版）下：额外不依赖时钟，代价是每次 readIndex 取用要一次多数派心跳。
- **任何情况下都不提供无上限陈旧读**；不做心跳 `leaderCommit` 直取（方案 C）。

---

## 4. 方案变体对比

| 方案 | 一致性 | 读的额外往返 | 实现量 | 结论 |
|------|--------|-------------|--------|------|
| **A. 维持现状**（Follower MOVED） | 强一致 | +1 跳（实测 100%） | 0 | 现状基线 |
| **B1. ReadIndex + 短窗口缓存**（推荐） | **有界陈旧**（上界 = 窗口 + RTT） | 窗口内 0 | 中 | **推荐，Stage 1 默认 100ms** |
| **B1'. ReadIndex 不缓存**（`cache-ms=0`） | **严格线性一致** | 每次 1 个集群内 RTT | 中（B1 的子集） | B1 的逃生舱，会话键敏感时用 |
| B2. ReadIndex（每次读做一轮多数派确认） | 线性一致、**不依赖时钟** | 每次 1 RTT | 中+ | 时钟不可靠部署；**顺带修 F2** |
| C. 有界陈旧读（拿心跳 `leaderCommit` + 安全延迟直接判） | 非线性（有界陈旧） | 0 | 小 | **对本项目风险高，见下** |
| D. 读也进 Raft 复制 | 强一致 | 1 RTT + fsync | 小 | 否决（审计 §6.1） |
| E. 仅客户端 `readMode=MASTER` | 强一致 | 消除双跳 | **0** | 短期止血（运维项，不是本方案替代） |

**C 为什么对本项目风险高**：日志证据显示该应用**每个请求都会对同一个 Shiro 会话键做"读-改-写"**
（`IGPROJ_BASE_PDZX:session:info:{...}` / `session:attr:{...}`，脚本里同时出现 `PTTL`/`HEXISTS`/`HGET`
与 `PEXPIRE`）。如果会话读被允许陈旧，同一请求内可能读到尚未更新的会话状态（例如 `stopTimestamp`
还没可见）→ 直接引发登录/会话逻辑错误。mesh 没有键级路由，**只能整体开关**，无法"会话键走 Leader、
缓存键走 Follower"。所以对本项目应选 B1/B2，而不是 C。

> 如果确实要 C，务实的做法是客户端侧区分：会话脚本用 `RScript.Mode.READ_WRITE` 强制走 Leader，
> 只有纯缓存键的读走 replica；这是应用侧配置，不改变服务端语义。

---

## 5. 前置项（必须先做，否则放开即引入缺陷）

1. **修审计 F1**：apply fail-stop 后必须停止服务读（暴露 `isApplyHalted`，gate **读**入口短路 `-TRYAGAIN`）。
   否则"Leader 侧静默陈旧读"仍然存在，且屏障的失败会被误读为"只是慢"。写入口不拦（避免新的写可用性风险）。
2. **启动就绪门**：`MeshStartupLoader` 已算出 `isTrusted`（`dump.rdb` 衔接是否可信），
   但 `MeshBootstrap.loadStartupState()` **只取了 `state` 把它丢弃**——全模块无人消费。
   放开 Follower 读前必须接通：`isTrusted=false`（store 为空、等 INSTALL_SNAPSHOT）期间**拒读**，
   快照安装完成后转就绪。未就绪时只拦读，写不拦。
3. **`ApplyBarrier` 组件** + 单测（等待/唤醒/超时/快照跳变/角色切换）。
4. **readIndex RPC 的校验**：term 校验（更高 term → 按 Raft 收敛并丢弃读点）+ 来源成员校验
   （复用已有的 `unknownPeerFrames` 丢弃逻辑），过期/伪造响应直接丢弃。

---

## 6. 验收与测试计划

| 层级 | 用例 |
|------|------|
| 单测 | 屏障：等待被唤醒、超时、快照跳变 signal、角色切换 signal；readIndex 缓存：窗口内复用 / 过期失效 / term 变化失效 / single-flight 合并 / `=0` 时每次取新读点；gate 分支：开关 off → MOVED，on → 本地读 |
| 集成（3 节点内存总线） | **`cache-ms=0` 时写 ack 后立即从 Follower 读必须看到新值**（严格读己之写）；并发写下 Follower 读返回的版本号单调不减 |
| 集成（有界陈旧窗口） | `cache-ms>0` 时窗口内复用同一读点且陈旧不超过配置上界；窗口过期后读到新值（锁定语义代价，防窗口无界漂移） |
| 故障注入 | 掐断某 Follower 的心跳后写入新值 → 该 Follower **缓存有效期过后绝不返回旧值**（只能等待后 MOVED）；kill Leader → Follower 读回落 MOVED → 新 Leader 恢复服务；不可信/重放未完成的节点拒读 |
| 回归 | mesh 全量（当前 432 绿）+ 新增前置项用例；`off` 默认路径逐字节等价 |
| 性能 | 3 节点并发读吞吐 vs 单 Leader（预期 ~2-3×）；读 p99 延迟（`cache-ms=0` 与默认 100ms 两组对比） |

---

## 7. 实施清单（文件级，供估算）

| # | 文件 | 改动 |
|---|------|------|
| 1 | `bus/MessageType.java` | +`READ_INDEX_REQ`(0x66)、+`READ_INDEX_RESP`(0x67)（`fromCode` 查表反查，无需改范围判断，但注释/文档要同步） |
| 2 | `rpc/ReadIndexRequestMessage.java` / `ReadIndexResponseMessage.java` | 新增（term、requestId、readIndex、success、leaderNodeId） |
| 3 | `MeshNode.java` | `dispatch` 新增两个 case；Leader 侧租约校验后回 readIndex；Follower 侧 `fetchReadIndex(timeout)`；暴露 `isReady` / `isApplyHalted` |
| 4 | `core/ApplyBarrier.java`（新） | lock/condition + `awaitApplied` / `signalApplied` |
| 5 | `LogReplicator.java` / `SnapshotManager.java` | apply 每条后、快照安装完成后 signal |
| 6 | `gateway/MeshWriteGate.read` | 读入口：就绪门 + apply-halt 短路；非 Leader 分支按配置走 readindex 或 MOVED；超时/失败回落 `redirectResponse` |
| 7 | `gateway/ReadIndexCache.java`（新） | 短窗口缓存 + single-flight（requestId 关联） |
| 8 | `MeshConfig` / `ConfigLoader` / `RdsConfig` | `mesh-read-from-follower = off\|readindex`（默认 off）、`mesh-follower-read-max-wait-ms`、`mesh-follower-read-cache-ms`（默认 100，0=关闭） |
| 9 | `MeshBootstrap.java` | 装配屏障/缓存/开关；接通 `isTrusted` 就绪门；INFO 暴露计数 |
| 10 | 测试类 | 约 8-10 个（屏障 / 缓存 / gate / 3 节点一致性 / 有界陈旧窗口 / 故障注入 / 回归） |

估算：**12-16 个文件、约 700-1000 行（含测试）**；一致性关键改动，建议按 **full workflow、brainstorming
不可省**，作为独立 change（参考名称 `fix-mesh-follower-read`）。

---

## 8. 风险与缓解

| 风险 | 影响 | 缓解 |
|------|------|------|
| 屏障实现错误 | 读点未达就作答，陈旧超出声明上界 | 超时回落 MOVED（结构上不可能返回未达 readIndex 的读）+ 分区注入测试 + 版本单调断言 |
| 短窗口缓存放大陈旧 | 窗口内读到窗口开始前的版本（有界陈旧） | 上界可审计（以请求发出时刻为年龄基准）+ 窗口默认仅 100ms + `cache-ms=0` 逃生舱 + E2b 用例锁定上界 |
| readIndex 来自已下台的 Leader | 读点偏小会漏掉刚确认的写 | term 校验 + "Leader 租约有效"前提 + 响应来源成员校验 + 缓存随 term/Leader 变更整体失效 |
| 额外 RPC 压力 | Follower 每读一次发一帧 | 短窗口缓存 + single-flight 合并 + 在途请求数上限（超限直接 MOVED） |
| 只读 EVAL 脚本缓存未命中 | 由"读到值"退化成错误 | 判定无法本地执行 → 回落 MOVED（绝不回 NOSCRIPT） |
| 淘汰 / AccessTime 差异扩大 | 三节点淘汰集合不同（P1-6） | 保持默认 `maxmemory=0`（不触发）；文档标注该模式下风险 |
| 读负载全压两个 Follower | Follower 还要跑 apply + 心跳，CPU 竞争 | 读在业务线程 / apply 在 raft 线程（天然隔离）；压测验证；必要时限流 |
| 新发现：不可信节点可当选 Leader | `isTrusted=false`（store 为空）当前无人拦截其参选 | 本 change 只把 `isTrusted` 用于读就绪门；是否禁止参选记入审计报告，独立评估 |

---

## 9. 与审计报告的关系与建议动作

- 本评估是 `AUDIT-REPORT-MESH-READ-PATH.md` **§6.3 方案 B 的落地论证**，并把审计 **F1** 列为前置项、
  把 **F2** 列为 Stage 2 的顺带修复。
- **短期（零代码）**：应用侧 Redisson `readMode=MASTER` 消双跳；日志级别改 INFO 消刷屏（审计 §10.5）。
- **中期**：按 §7 立独立 change 实现 B1（默认关闭、灰度开启），验证通过后再评估是否默认打开。
- **补充运维观察点**：若只有两个 Follower 分担读，单台 Follower 故障时另一台会承接全部读；
  如需 Leader 也参与分担，可评估客户端 `MASTER_SLAVE` 读模式的实测行为（Redisson `readMode` 的
  故障回退策略以实测为准）。
