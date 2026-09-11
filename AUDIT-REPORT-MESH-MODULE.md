# Luban-RDS mesh 模块专项审计报告（三轮全量）

> **审计目标**：对 `luban-rds-mesh` 模块做全面深度审计，找出所有正确性、一致性、活性、性能与协议面问题。
> **审计方式**：三轮递进——① 2026-09-11 生产日志（11/12/19 三节点 RDS.log/app_ig.log/gc.log）逐分钟定量分析定位现场；② 持久化与线程模型定向代码审查（2 个并行子代理）；③ 全模块深度审计（4 个并行子代理：Raft 协议正确性 / apply 确定性与状态一致性 / 网关命令路由与协议面 / 总线传输与装配生命周期）。
> **验证方式**：所有 P0 结论均经人工到行号核实；P1/P2 由子代理给出具体行号、影响判断项人工抽查；生产数字均由日志原始数据重新计算。
> **审计日期**：2026-09-11
> **基线**：master @ 5fcfcd3（v1.0.23）
> **基准**：Raft 论文（§5.2/§5.4 选举与提交、Fig 8）与 Redis 7.x 客户端可见语义

---

## 0. 总体结论

mesh 的单命令写路径（`gate.write → Raft propose → 多数派提交 → 三节点 apply`）闭环成立，历史修复（fsync 拆 persistExecutor、NACK 节流、快照重传 30s 冷却、AE 帧 256 条/2MB 上限、isWritable 背压、notifyPeerAlive、写失败关连接、setMessageConsumer、stop 5s 停顿）全部在位。但**"强一致"的承诺在事务、崩溃恢复、快照重同步三条路径上不成立**：事务完全不经 Raft、启动重放可产生幽灵提交、快照安装会固化分歧。这些问题平时被"follower 不对外服务读"掩盖，切主或重同步时爆发。

三轮共发现 **P0 × 8、P1 × 19、P2/P3 × 约 25**，集中五类：

1. **一致性正确性缺口（P0×4 + P1×7）**——MULTI/EXEC 绕过 Raft、启动重放架空 §5.4.2、过期投票响应计入正式选举（同 term 双 Leader）、INSTALL_SNAPSHOT 抬 term 不降级、快照合并不清空、votedFor 持久化失败被吞、apply 异常静默分叉、EVALSHA 缓存易失；
2. **活性与选举稳定性（P0×1 + P1×2）**——心跳与 apply 共用单线程 raftExecutor（写积压 ~200ms 即可触发最敏感 follower PreVote）、follower 在 raft 线程同步 fsync、新 Leader 无 no-op；
3. **持久化生命周期（P0×1 + P1×2）**——周期快照生产代码零调用 → WAL/内存 log 无界增长且写路径成本随运行时长线性上升（"越跑越慢"的代码根因）、persistExecutor 无界队列零监控；
4. **客户端可见协议面（P1×5 + P2 若干）**——follower 拒绝一切读但 CLUSTER NODES 又宣称 slave（Redisson 默认 readMode=SLAVE 下几乎每条命令 MOVED 双跳）、MESHDOWN 非标准码、LeaseInvalid 落 -ERR 不可重试、pub/sub 三节点不一致、未认证可执行事务；
5. **传输与运维面（P1×3 + P2 若干）**——总线零认证零成员校验、入站无界队列、启动失败僵尸进程。

整体判断：**内网三节点、无事务使用、follower 不承载读、写负载 ≤ ~84 写/s 的当前用法下可用；事务路径不可用（数据安全）、故障恢复与重同步路径有正确性破洞、写吞吐与延迟随运行时长劣化、规模化与跨网段部署不可用。**

---

## 1. 生产现场证据（2026-09-11 日志实测）

审计的触发背景是 9/11 生产"应用启动后逐渐变慢"。以下实测数字是后文多个性能类问题的现实载体，全部来自三节点 RDS.log 的逐分钟统计。

| 指标 | 数值 |
|---|---|
| mesh 全天状态 | 零选举、零 WARN/ERROR、无任何静默断层（全天每分钟记录数 ≥326）、ZGC 堆 516–706MB/12GB、Pause<0.1ms、Allocation Stall 0 |
| Raft 条目量 | 全天 52,050 条 apply，其中 46,730 条（90%）集中在 10:00–10:59 一小时，99.8% 为会话键操作 |
| 峰值提交速率 | 5,057 条/分钟 ≈ **84 commits/s**（三节点 apply 次数逐条相等：5,058/5,062/5,053） |
| 写延迟 | 空闲 avg 8.3–10.5ms（max 11–22ms）→ 峰值 avg 12.5–16.7ms（max 78–103ms），burst 停止立即回落 |
| apply 间隔 | 峰值分钟相邻 apply 间隔直方图在 4–6ms 处有 2,600+ 样本的硬地板（单线程已连续排满） |
| MOVED 双跳 | HGET 100% 先落 follower（4,714+4,727 ≈ Leader 9,441）；EVAL ~95% 先落 follower（22,261+22,249 vs 46,730）——几乎每条客户端命令 2 个来回 |
| 写放大源头 | 门户应用每个 HTTP 请求 2 条 EVAL（`return PTTL` 纯读 + 会话续期写 `HSET+2×PEXPIRE`），且 `MeshWriteGate.isWriteCommand` 对 EVAL/EVALSHA 恒判写（`MeshWriteGate.java:560-561`）→ 纯读也进 Raft 日志 |

"逐渐变慢"的完整成因 = 应用侧会话写放大（每请求 2 条 EVAL）× mesh 恒判写 × 单线程串行 × WAL 无界增长的趋势性成本 × MOVED 双跳，五个因素叠加。

---

## 2. P0 —— 数据丢失 / 一致性破坏 / 选举双活 / 活性根因

### P0-1 MULTI/EXEC 完全不经 Raft：事务写只落在收到命令的那一个节点

- **定位**：`RedisServerHandler.java:590-593`（EXEC 前置分支）、`RedisServerHandler.java:2023-2263`（`handleExecCommand` 全文无 mesh 分支）、`TransactionPayload.java:139-163`、`MeshNode.java:400/404`（extra 参数）、`RedisServerHandler.java:901`（`gate.write(rawRespFrame, currentDatabase, null)` extra 恒 null）
- **问题**：EXEC 在 mesh gate 分支（`RedisServerHandler.java:890`）**之前**被前置分支截走本地执行；`handleExecCommand` 对事务内命令逐条 `commandHandler.handle`/直调 `memoryStore`，只作用于本节点 store。专门建设的事务 Raft 基建（`MeshNode.propose` 的 extra 参数、`TransactionPayload.encode`、`LogApplier.applyTransaction`）**没有任何生产调用方，是死代码**；`MeshWriteGate.java:138` 白名单里的 MULTI/EXEC/DISCARD/WATCH 条目永远到不了判定处。
- **影响**：follower 上 EXEC 本地执行全部事务写，不 MOVED、不复制——三节点数据发散，切主即丢整批事务数据。WATCH 校验也只对本地 store，跨节点竞态不可见。
- **修复**：短期在 mesh 模式直接禁用 MULTI/EXEC（返回 `-ERR`，比静默丢数据好）；中期把 EXEC 在 mesh 分支改为组帧走 `propose(..., extra=TransactionPayload.encode(...))` 接通既有死代码，事务内 SELECT 切库需一并设计（apply 端固定用 `entry.dbIndex`）。

### P0-2 启动重放把未提交条目标为已提交（幽灵提交），并经 leaderCommit 污染全集群

- **定位**：`MeshStartupLoader.java:248-250`（`state.commitIndex = state.lastApplied`，注释自述"重放的都视为已提交"）、`RaftStateMachine.java:361-363`（follower `commitIndex = min(leaderCommit, lastLogIndex)` 直推）、`MeshNode.java:197-198`（`durableIndex = state.getLastLogIndex()`）
- **问题**：WAL 落盘 ≠ Raft 已提交。`LogReplicator.maybeAdvanceCommitIndex` 的 §5.4.2 Fig-8 保护（`LogReplicator.java:487-491`，实现正确且有测试 `LogReplicatorTest:269`）只约束"推进"，约束不了被启动加载抬高的初值。
- **可达路径（已逐步推演验证）**：旧 Leader A(term5) propose index10 落盘成功但复制未完成即崩溃（该条目永远不可能获多数派）→ A 重启重放 6..10 并把 commitIndex 抬到 10 → A 凭 (term5,10) 日志最新赢 term6 选举（当选合法）→ 以 leaderCommit=10 广播 → B 在 `RaftStateMachine.java:361-363` 采纳 → **从未获多数派确认的写在 A、B 持久化生效**；若 B 后续与 A 分区、C 与 A 重组，C 的正确历史被幽灵记录覆盖。
- **修复**：启动重放只推进 `lastApplied`，`commitIndex` 保持 lastIncludedIndex 初值不动（由新 Leader 的 leaderCommit 收敛）；follower 侧 leaderCommit 采纳保持现状（该语义本身正确）。

### P0-3 过期/PreVote 投票响应计入正式选举：可选出无真实多数派的 Leader（同 term 双主）

- **定位**：`MeshNode.java:960-966`（响应处理只判 `resp.term > currentTerm`，不判相等）、`VoteCollector.java:159-171`（`onVoteReceived` 只按 fromNodeId 去重，不校验 resp.term、不分轮次）、`rpc/RequestVoteResponse.java`（无 preVote/轮次标识）
- **问题**：PreVote 阶段（term N）与正式选举（term N+1）共用同一响应结构；PreVote 的迟到赞成票在 `runRealElection` 替换 collector 后落入新 collector 被计票。自己 1 票 + 1 张幽灵票 = "2/3 多数"当选，而票主可能此刻正把正式票投给另一候选者 → 同 term 双 Leader。
- **影响**：选举正确性破洞，split brain；触发条件是两阶段切换间的网络时延抖动，概率不低（两阶段毫秒级衔接）。
- **修复**：`RequestVoteResponse` 增加 `preVote` 标识与 `electionTerm` 字段；响应处理校验 `resp.term == state.currentTerm && resp.preVote == 当前阶段` 才计票。

### P0-4 INSTALL_SNAPSHOT 抬高任期但不降级、不停心跳、不持久化

- **定位**：`SnapshotManager.java:455-460`（直接改 `currentTerm/votedFor/leaderId`，注释称"降级为 Follower 由上层 MeshNode 统一处理"）、`MeshNode.java:908-914`（dispatch 只转发，无任何副作用处理）、`RaftStateMachine.java:311-314`（follower 对同 term AppendEntries 无条件改认 leaderId）
- **问题**：一个仍自认 Leader 的旧节点收到更高 term 的 INSTALL_SNAPSHOT 后，term 被抬升但 role 仍 LEADER、心跳任务继续跑，随后以新 term 广播心跳，follower 同 term 无条件认主 → **同 term 双 Leader 窗口**。term/votedFor 的修改也未落盘（`runPersistHook` 仅在快照完成后执行）。
- **触发条件**：旧 Leader 分区恢复后，新 Leader 的首帧恰好是快照（NACK≥128 触发重同步）而非心跳——窗口窄但存在；对 follower/candidate 收到高 term 快照同样绕过了选举定时器复位等全部副作用。
- **修复**：INSTALL_SNAPSHOT 的 term 抬升必须走 `becomeFollower` 转换路径（复用 `applyFollowerSideEffects`），并在 dispatch 层处理 Transition。

### P0-5 快照安装是"合并"不是"替换"：分歧被重同步固化

- **定位**：`luban-rds-persistence/.../impl/RdbPersistService.java:291+`（`loadWithKeyCount` 只逐键 `set`，全链路无 flush/清空，已核实）、`SnapshotManager.java:560-595`（`loadIncomingSnapshot` 不清 rawStore）
- **问题**：快照本来就是节点落后/分歧后才发送的。follower store 中那些 leader 已删除、快照里不存在的 key，在"全量追平"后继续存在——重同步机制不但没修复分歧，反而把它焊死；叠加 P2 的"三节点无状态校验和"，该分歧永久不可检测，切主后幻影键对外可见。
- **修复**：安装快照前对 rawStore 全量清空（mesh 模式下 SnapshotManager 是唯一写者，时机安全），或改为"标记删除快照未覆盖键"的对账式加载。

### P0-6 心跳/选举定时器与 apply 共用单线程 raftExecutor：写积压可推翻健康 Leader

- **定位**：`MeshNode.java:190`（`this.scheduler = this.raftExecutor`）、`MeshNode.java:802`（心跳 fixedRate 100ms 排同一队列）、`MeshNode.java:185-189`（单线程无界 FIFO，无优先级）
- **问题**：apply（含 Lua 同步执行）与心跳/选举超时/投票收集/RPC 处理全部串行。按生产实测 4–6ms/apply 的地板，**队列前面积压 33–50 条 apply（约 200ms）即可让最敏感 follower（选举超时随机 300–600ms，`MeshConfig.java:144-145`）触发 PreVote**；一条 >200ms 的慢 Lua 或一次 raft 线程 fsync 长尾同样可达。放大回路：PreVote RequestVote 到达 Leader 后也排在 apply 后，且投票授予路径在 raft 线程同步 fsync（`MeshNode.java:940`）进一步挤占心跳——8/6 选举风暴的同构根因，当时只拆走了 propose 的 fsync。
- **修复**：心跳/选举定时器独立单线程调度器（任务极轻，不与 apply 抢占）；或 apply 批量执行并在批次间让出。

### P0-7 follower 在 raft 线程同步 fsync：磁盘抖动直接阻塞心跳应答

- **定位**：`MeshNode.java:974` → `RaftStateMachine.java:348-351`（`decideAppendEntries` 内联 `persistHook.run()`）→ `MeshConfigPersister.java:310-319`（`channel.force(true)`）
- **问题**：Leader 的 fsync 已拆到独立 persistExecutor（8/7 修复），follower 这条路径没拆——每收一批 AppendEntries/心跳都在 raft 线程等磁盘。follower 磁盘一抖，心跳应答与选举定时器同时被卡，直接传导为 Leader 的写尾延迟（9/11 实测 78–103ms 尾部的头号嫌疑），也是 P0-6 的触发放大器。
- **修复**：follower 追加与落盘对称化——先内存追加回 ACK 前置校验，WAL force 挪 persistExecutor，durableIndex 语义沿用 Leader 侧既有机制。

### P0-8 周期快照生产代码零调用：WAL 与内存 log 无界增长，写路径成本随运行时长线性上升

- **定位**：`SnapshotManager.java:302`（`takePeriodicSnapshotIfNeeded` 实现完整、默认阈值 10 万条 `SnapshotManager.java:184`/`RdsConfig.java:365`，但全模块 grep 仅测试调用）、`MeshBootstrap.java:156-171`（装配了 SnapshotManager 却从未调度）、`MeshConfigPersister.java:241`（每次 save `new ArrayList<>(state.log)` 全量拷贝）、`MeshStartupLoader.java:182`（重启全量重放）
- **问题**：2026-08-06 已记录的"快照触发缺口"至今未修。WAL 无 compaction、内存 log 无截断，84 写/s ≈ 每天 700 万行；每次落盘的全量拷贝与 toAppend 扫描成本随条数线性上升——这是 9/11 实测写延迟 9ms→15ms 中"趋势性爬升"成分的代码根因；重启恢复时间同步线性恶化（与 8/19 记忆"WAL 重放 48 万条约 3.5–3.8 分钟"一致）。
- **修复**：装配后定时（或按 commitIndex 推进）调用 `takePeriodicSnapshotIfNeeded`；`save` 的全量拷贝改为基于 lastPersistedIndex 的增量视图。

---

## 3. P1 —— 正确性边界 / 可观测的性能与语义问题

### A. Raft 协议边界

- **P1-1 `persistStateSafe` 吞掉 votedFor 持久化失败**（`MeshNode.java:344-355`，已核实）：catch 仅 `logger.warn` 后继续，内存已投票、响应已发出、磁盘未写；崩溃恢复后 votedFor 为旧值 → 同 term 二次投票 → 双 Leader。javadoc 自称"由 Raft 任期裁决自愈"对 term 成立、对 votedFor 不成立。修复：持久化失败应拒绝投票（fail-stop）。
- **P1-2 过期 AppendEntriesResponse 不丢弃且无条件续租**（`MeshNode.java:1013-1025` 无 `resp.term < currentTerm` 分支，已核实；`LogReplicator.java:354-356` `leaseRefresher.run()` 无 matchIndex 前提）：旧任期迟到 success 推高 matchIndex/nextIndex 并刷新 lease——租约虚高（旧数据可读窗口）+ 复制跳段（靠后续 NACK 自愈）。修复：term 不等的响应直接丢弃；续租加 matchIndex 前提。
- **P1-3 新 Leader 不提交 no-op + 单候选 commit 策略**（`MeshNode.java:764-786` `onWinElection` 无 no-op，已核实；`LogReplicator.java:488-490` term 不匹配即放弃、不向更小 N 回退）：无新写入时旧 term 未确认条目永远无法间接提交，一致性窗口无限拉长。修复：becomeLeader 追加 no-op entry（标准做法）。

### B. apply 确定性与状态一致性

- **P1-4 EVALSHA 脚本缓存易失且不受快照/重放覆盖**（`LuaCommandHandler.java:145-149` 未命中返回 -NOSCRIPT 不执行；`MeshStartupLoader.java:172-186` 快照截断后 ≤ 边界的 SCRIPT LOAD 永久丢失）：重启+截断后 follower apply EVALSHA 全部静默跳过而 leader 曾执行 → 分歧。另：脚本内 `redis.call('EVALSHA'/'SCRIPT','LOAD')` 走 `SHARED_COMMAND_HANDLER`（`LuaCommandHandler.java:51,616`）的独立缓存，与顶层缓存互不可见。
- **P1-5 apply 异常静默吞掉且 lastApplied 照常推进**（`LogApplier.java:162-165` 转 -ERR 串；`LogReplicator.java:538-543` 异常仍推进 lastApplied）：三节点对同一条目一成一败（节点级 OOM `DefaultMemoryStore.java:961/992/1023/1063`、Lua 挂钟超时）时状态分叉不可见，无重试/对账/告警——leader 侧失败成"幻影写"（切主后复活）、follower 侧失败成"切主丢写"。
- **P1-6 maxmemory>0 时淘汰非确定**（`DefaultMemoryStore.java:417` 每节点独立 `new Random()`，:788/:847 LRU 采样与 evictByRandom；:936 `updateAccessTime` 仅读路径调用而 follower 拒读）：三节点会淘汰不同键。默认 maxMemory=0/noeviction 休眠，属配置触发型。

### C. 客户端可见协议面

- **P1-7 follower 拒绝一切读 + CLUSTER NODES 宣称 slave → MOVED 双跳**（`MeshWriteGate.java:381-387` 已核实；`MeshClusterCommands.java:377` follower 标 slave）：Redisson 集群模式默认 `readMode=SLAVE`，读全部打 follower 被 MOVED 再回 Leader——9/11 实测 HGET 100%、EVAL ~95% 双跳，几乎每条命令 2 个来回，两台 follower 对数据流量零贡献。READONLY 返回 +OK 但读仍 MOVED。**运维速效：应用侧 Redisson 设 `readMode=MASTER`。**
- **P1-8 LeaseInvalidException 落通用 catch → `-ERR Error handling command`**（`RedisServerHandler.java:1007` 通用 catch；专用 catch 只有 :972 MOVED 与 :996 TRYAGAIN）：设计意图"让客户端重试"落空，Redisson 对 ERR 不重试，租约抖动直接变成业务失败。与 8/7 修过的 ERR→TRYAGAIN 同类，当时漏了此异常。
- **P1-9 写超时不 cancel future → TRYAGAIN 重试双写**（`MeshWriteGate.java:317-323` `get(5000ms)`，:320-324 注释自认 entry 仍会 commit）：非幂等 EVAL 被执行两次，无幂等 token。
- **P1-10 pub/sub 三节点不一致**（`RedisServerHandler.java:836-857` gate 前本地处理，`PUB_SUB_MANAGER` 单 JVM 静态单例）：follower 上 PUBLISH 成功返回（订阅数常为 0）但消息只达本节点订阅者，不 MOVED 不复制。
- **P1-11 未认证可执行 MULTI/EXEC/WATCH/CLUSTER**（`RedisServerHandler.java:578-606`、:718 前置分支均排在 NOAUTH 检查 :805 之前）：配置 requirepass 后未认证客户端仍可执行完整事务（在 follower 上还会落地写）并获取集群拓扑。

### D. 传输与运维

- **P1-12 总线零认证、零成员校验、零连接上限**（`MeshBusServer.java:66-106` 绑定所有网卡无握手；`MeshNode.java:868-892` 不校验 `peerNodeIds.contains(fromNodeId)`；唯一防线是 `MeshBusHandler.java:73-83` 拦"自称是我"的帧）：任何能连 busPort 的主体可注入高 term AppendEntries 持续复位选举定时器（永久选不出 Leader 的 DoS）并设置任意 leaderId（MOVED 劫持）；伪造 INSTALL_SNAPSHOT 可覆盖数据。内网可信环境可接受，跨网段部署即升级为 P0。
- **P1-13 入站帧无界排队进 raftExecutor**（`MeshNode.java:885`）：对端重发风暴（或 P1-12 的伪造流量）下堆内存无界增长，无丢弃策略/水位/指标。
- **P1-14 `start()` 吞异常不 rethrow → 僵尸进程**（`NettyRedisServer.java:981-984` catch 后仅 stop+error；`RedisServerMain.java:37-48` 照常打"启动成功"并 `join()` 永久阻塞）：bus 端口被占时进程不退出、不服务。
- **P1-15 写在业务线程阻塞最长 5s**（`MeshWriteGate.java:78,313-319`；handler 挂共享 businessGroup，`NettyRedisServer.java:948`）：pipeline N 条写串行 N×5s 占住业务线程，极端情况耗尽线程池（已核实非 Netty EventLoop 阻塞——业务组隔离了 I/O）。
- **P1-16 persistExecutor 无界队列、零监控**（`MeshNode.java:192`）：fsync 慢于写速率时 durableIndex 停滞 → commit/apply 无限滞后且不可观测（`LogReplicator.java:482-484` 门控）。

### E. 事务与持久化交叉

- **P1-17 EXEC 残留旧 AOF/复制传播**（`RedisServerHandler.java:2188-2196`；persistService 在 mesh 下被无条件注入 `NettyRedisServer.java:939`）：appendonly 开启时事务写进 AOF 却不进 Raft log，双持久化源冲突。
- **P1-18 BLOCK 类禁用可被 MULTI 绕过**（`isBlockCommand` 仅 gate 分支检查 `RedisServerHandler.java:892`；事务入队 :784-804 与 EXEC 执行不查）：`MULTI; BLPOP k 0; EXEC` 在 mesh 下本地阻塞执行。
- **P1-19 MEMORY USAGE 等白名单命令在 follower 本地执行**（`shouldUseMeshGate` 返回 false 的命令不过 gate 不 MOVED，`RedisServerHandler.java:1056/:924`）：follower 静默返回陈旧数据，绕过租约读一致性。

---

## 4. P2 / P3 —— 健壮性 / 性能 / 可维护性

| 级别 | 问题 | 位置 |
|---|---|---|
| P2 | Follower NACK 上报 `max(lastLogIndex, prevLogIndex-1)` 落后时虚高，Leader 加速回退永久失效，逐格线性回退 O(n) | RaftStateMachine.java:324-325 + LogReplicator.java:366-368 |
| P2 | 冲突截断 `truncateAfter` 无 `idx > commitIndex` 保护断言，安全性完全寄望上游不变量（P0-2/P0-4 任一破洞下已提交条目可被静默截断） | RaftStateMachine.java:335-337 |
| P2 | 快照 RDB 全量加载在 raftExecutor 同步执行，大快照安装期间本节点心跳/选举/RPC 停摆 | SnapshotManager.java:527 经 MeshNode.java:911 |
| P2 | 快照会话无超时清理、丢 chunk 无逐段重传（一次性流水发送），丢一个 chunk 整轮失败，仅靠 3 次重试 + 30s 冷却兜底 | SnapshotManager.java:487-491 + LogReplicator.java:96-97 |
| P2 | 投出选票后不重置选举定时器（VoteDecision.resetElectionTimer 被调用方忽略），偏离 §5.2，影响选举活性 | RaftStateMachine.java:251 vs MeshNode.java:933-936 |
| P2 | follower 截断后 persistHook 失败返回 success=false 但内存截断不回滚，崩溃窗口内被截条目可从旧 WAL 复活 | RaftStateMachine.java:349-356 |
| P2 | ElectionTimer 首次 start 的 slot 竞态：回调可能先于 `slot.set(future)` 被判 null 丢弃，首轮选举超时静默丢失 | ElectionTimer.java:198-211 |
| P2 | `mesh-persist=no` 时 persistHook 为 no-op 但 durableIndex 照常初始化/推进，commit 持久性门控被架空（"凭空 durable"） | MeshNode.java:107/197 + MeshBootstrap.java:143 |
| P2 | KEYS 误判写进 Raft（不在 @read/READ_SUPPLEMENT）；XREADGROUP、SCRIPT EXISTS、一切未知命令（含拼写错误）都生成 Raft 日志条目——读放大+日志垃圾 | MeshWriteGate.java:554-577 |
| P2 | `MESHDOWN` 为自造错误码、无 Leader 时 CLUSTER SLOTS 返回 `*0` 而非 `-CLUSTERDOWN`：主流客户端不识别不重试；空槽映射致集群客户端初始化失败而非退避引导 | MeshClientRedirector.java:48 + MeshClusterCommands.java:159-163 |
| P2 | CLUSTER 仅支持 SLOTS/NODES/INFO，MYID/SHARDS/KEYSLOT/GETKEYSINSLOT 等一律 ERR | RedisServerHandler.java:1090-1100 |
| P2 | connect 建连进行中（listener 未回调，最长 5s）并发 connect 重复建连，被覆盖旧 channel 永不关闭（连接泄漏） | MeshBusClient.java:148-181 |
| P2 | 无应用层 idle 检测：对端僵死仅靠 SO_KEEPALIVE（~2h）与写失败才断，入站方向无超时清理 | MeshBusClient.java:164 + MeshBusServer.java:80 |
| P2 | Encoder 对 >16MB 帧静默丢弃且 writeAndFlush 判 success：单条超大 entry（collectEntriesFrom 首条无条件保留）陷入 100ms 重发-被丢死循环，propose future 永久悬挂 | MeshBusCodec.java:57-61 + LogReplicator.java:299 |
| P2 | nodeId 无字符集校验，US_ASCII 把非 ASCII 替换为 '?' 可碰撞（身份混淆）；RdsConfig.java:318 "40 字符十六进制"注释已过时 | MeshBusCodec.java:74 |
| P2 | stop() 路径不触及 SnapshotManager（无 close 生命周期），半途快照的输出流与临时 chunk 文件残留 | NettyRedisServer.java:1081-1103 |
| P2 | 入站 body 反序列化（最大 16MB/4MB chunk）在 Netty IO 线程执行，阻塞同 eventloop 全部连接 | MeshBusHandler.java:88 |
| P2 | AE 响应处理每次双拷贝 nextIndex/matchIndex HashMap（热路径纯开销） | MeshNode.java:1031-1032 |
| P2 | WAL 每条追加都 open/force/close 不复用 channel，常数开销偏高 | MeshConfigPersister.java:310-319 |
| P2 | Lua `math.random`（LuaJ standardGlobals 无播种）与 `redis.call('TIME')` 参与写值时三节点存的内容不同——通用确定性缺口（生产 ARGV 客户端传入时暂无实害） | LuaCommandHandler.java:315,616 |
| P2 | 节点时钟偏差直接平移 follower 过期时刻；快照恢复按 follower 本地时钟丢弃"已到期"键，时钟快的 follower 少数据 | DefaultMemoryStore.java:1233 + RdbPersistService.java:633-642 |
| P2 | bootstrap 异常路径不 close busClient 的 NioEventLoopGroup（非 daemon 线程泄漏） | MeshBootstrap.java:111 vs :126 |
| P3 | `case "acl"` / `"acl"` 小写字面量对大写命令名永不匹配（ACL 全走 Raft 后 ERR；handler 本身也未注册） | RedisServerHandler.java:1058 + MeshWriteGate.java:165 |
| P3 | CONFIG 子命令整系不可用：handler 注册键带空格（"CONFIG GET" 等）而分发按单词 args[0] 匹配——改配置只能改文件重启；DEBUG OBJECT/SEGFAULT 同为死键 | RdsCommandConstant.java:137-156 |
| P3 | redirector 的 "Leader 不可达" 守卫 `containsValue` 恒真（死守卫）；`MeshWriteGate.redirectResponse` 整体为生产不可达死代码 | MeshClientRedirector.java:128,145-148 + MeshWriteGate.java:496-515 |
| P3 | EVAL/EVALSHA 的 MOVED 用脚本文本当 key 算 slot；`-MOVED redirector not configured` 兜底响应无 slot 非法 | MeshNode.java:508-536 + RedisServerHandler.java:989 |
| P3 | 事务内 AUTH 不更新认证态；WAIT 恒返回 `:0`（谎报）；SHUTDOWN/SWAPDB/EXPIREAT/COPY/GETDEL 白名单有或默认写但无 handler 实现，Raft 空转 ERR | RedisServerHandler.java:939-943/:664-684 |

---

## 5. 审计确认正常的部分（避免重复排查）

- **§5.4.2 Fig-8 保护已实现**（`LogReplicator.java:487-491`），且有单测佐证（`LogReplicatorTest:269`）；
- prevLog 校验用 **term 相等性**（非存在性，`RaftStateMachine.java:320-327`）；冲突按 term 截断并正确 rewrite WAL（`:335-338`、`MeshConfigPersister.java:267-274`）；截断后 `durableIndex` 正确压低（`MeshNode.java:984-986`）；
- RequestVote 的日志新旧判定为 (term, index) 字典序（`RaftStateMachine.java:261-268`）✓；PreVote 不自增 term、不设 votedFor ✓；
- majority 计算含自己一票、matchIndex≤0 过滤、单节点集群无越权 commit（`MeshConfig.java:130-132`、`VoteCollector.java:114-117`、`LogReplicator.java:462-476`）；
- pipeline 分帧正确：channelRead 每次只取一条完整命令，一个 TCP 包 N 条命令 = N 次独立 propose，不会整包走 Raft（`RedisServerHandler.java:417-444`）；
- 总线编解码：半包/粘包处理标准、16MB 上限超限关连接、非法 type 关连接、body 拷贝为 byte[] 无 ByteBuf 泄漏（`MeshBusCodec.java:110-168`）；
- 历史修复全部在位：notifyPeerAlive 帧级退避重置（`MeshBusClient.java:258-274`）、写失败关连接（`:321`）、setMessageConsumer（`MeshBootstrap.java:137`）、stop 5s 停顿已消除（`NettyRedisServer.java:1181-1186`）、mesh-peers 第三段 servicePort 双解析对齐；
- SPOP/SRANDMEMBER/RANDOMKEY 未实现，反而避开了随机命令分歧；EXPIRE 系列存绝对时间戳、active expire 本地删除不经 Raft，设计正确；
- dump.rdb 唯一写者互斥可靠（mesh 下周期 RDB save 与 BGSAVE/SAVE 均被 gate，`NettyRedisServer.java:1225-1231`、`RedisServerHandler.java:884`）。

---

## 6. 修复路线图

**第一组：数据安全，小改动（每条均可被现有 364 个 mesh 测试回归）**
1. mesh 下禁用 MULTI/EXEC（返回 -ERR）——事务基建接通前先止血（P0-1）；
2. 启动重放不再抬 commitIndex（P0-2）；
3. RequestVoteResponse 加 term/轮次校验（P0-3）；
4. 快照加载前清空 rawStore（P0-5）；
5. INSTALL_SNAPSHOT 抬 term 走 becomeFollower（P0-4）；
6. LeaseInvalidException 补专用 catch 转 -TRYAGAIN（P1-8）。

**第二组：选举与活性**
7. 心跳/选举定时器独立线程（P0-6）；
8. follower 落盘挪 persistExecutor 对称化（P0-7）；
9. votedFor 持久化失败拒绝投票（P1-1）；过期 AE 响应丢弃+续租加前提（P1-2）；becomeLeader 加 no-op（P1-3）。

**第三组：性能与运维（9/11 变慢的根治）**
10. 应用侧 Redisson `readMode=MASTER`（配置，立即生效，P1-7）；
11. 周期快照调度 + save 增量化（P0-8）；
12. 只读 EVAL 走本地读（复用 `LuaScriptAnalyzer.isReadOnlyScript`，cluster 从节点路径已有现成实现 `RedisServerHandler.java:1732-1745`）；
13. persistExecutor 积压指标与上限（P1-16）。

**第四组：长期**
14. 事务 Raft 化接通（extra/TransactionPayload）；15. 写幂等 token（P1-9）；16. apply 确定化（random/time 注入）；17. 总线认证与成员校验（P1-12）；18. pub/sub 复制策略（P1-10）。

---

## 附录：行号索引速查（按文件）

- `MeshNode.java`：107/197（persistHook no-op 与 durableIndex 初始化）、185-198（线程池）、348-355（persistStateSafe）、400/404（事务 extra）、413（propose 提交）、457-469（异步落盘）、632/667（commit 推进点）、764-786（onWinElection 无 no-op）、802（心跳）、884-914（dispatch/INSTALL_SNAPSHOT）、939-941（投票前持久化）、960-966（投票响应）、974（AE 处理）、1013-1025（AE 响应）
- `RaftStateMachine.java`：238-248（一票制）、261-268（日志新旧）、299-327（prevLog 校验）、335-338（冲突截断）、348-351（follower 内联 fsync）、361-363（leaderCommit 直推）
- `LogReplicator.java`：118-121（批量上限）、240-270（replicate）、345-356（响应处理/续租）、358-371（回退）、459-495（maybeAdvanceCommitIndex/§5.4.2）、508-546（apply 循环）
- `SnapshotManager.java`：302（周期快照）、455-460（term 抬升）、487-491（丢 chunk）、527-595（加载）
- `MeshStartupLoader.java`：172-186（重放范围）、248-250（commitIndex 抬升）
- `MeshWriteGate.java`：78/313-324（write 阻塞与超时）、131-146（白名单）、381-387（follower 拒读）、554-577（判写）
- `RedisServerHandler.java`：576-606（事务前置分支）、805-817（NOAUTH）、884-912（mesh gate 分支）、890（gate 起点）、901（extra 恒 null）、972/996/1007（catch 链）、1049-1058（shouldUseMeshGate）、1090-1100（CLUSTER 子命令）、2023-2263（handleExecCommand）、1732-1745（cluster 从节点只读判定，可复用）
- `MeshConfigPersister.java`：241（全量拷贝）、263-277（rewrite 分支）、294-319（WAL 追加+force）
- `MeshBusClient.java`：148-181（connect 竞态）、219-227（退避）、258-274（notifyPeerAlive）、296-323（send 失败）
- `MeshBusServer.java`：66-106（无认证绑定）
- `impl/RdbPersistService.java`：291+（loadWithKeyCount 无清空）
