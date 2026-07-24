# Comet Design Handoff

- Change: add-cluster-automatic-failover
- Phase: design
- Mode: compact
- Context hash: 840dfed52519d25b1328bd2e60c612be87023b7c1a3032dd65f9c3c12d26b6cd

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/add-cluster-automatic-failover/proposal.md

- Source: openspec/changes/add-cluster-automatic-failover/proposal.md
- Lines: 1-36
- SHA256: a98ce679e820ac99ad7bbe84d236f7b92782dffe4edb9be89c8292c71ad33c15

```md
## Why

当前 3 主 3 从的集群模式下，任一 master 宕机会导致整个集群对外不可用：故障检测链路（PFAIL → FAIL 共识）能正常工作，但 FAIL 之后没有任何 slave 被自动提升为新 master。线上日志（172.16.83.11/12/19）显示 master 节点被反复标记为 PFAIL/FAIL，却始终没有"slave promoted to master"的记录，应用层因槽位无主而抛出 `WriteRedisConnectionException`。根因是 `GossipProtocol` 只实现了手动 `CLUSTER FAILOVER`，缺少对齐 Redis Cluster 的 slave election 自动故障转移机制，使集群失去了 master 容错这一核心高可用能力。

## What Changes

- 新增**从节点选举触发**：slave 在其 master 被集群多数派确认为 FAIL 后，启动选举（带 AUTH_REQUEST 广播 + 退避抖动，避免多 slave 同时发起）。
- 新增**多数派授权投票**：其余 master 收到 AUTH_REQUEST 后，依据 `configEpoch` 竞争规则与"每纪元每 master 仅投一票"约束，回送 AUTH_ACK；候选 slave 收到过半数授权后胜选。
- 新增**胜选提升流程**：胜选 slave 复用现有 `performFailover` 逻辑提升为 master、继承槽位、自增 `currentEpoch/configEpoch`，并通过 gossip 广播拓扑变更，使全网收敛。
- 启用**已存在但未被使用的死代码**：`FailoverAuthRequestMessage` / `FailoverAuthAckMessage`（含编解码）正式接入 `ClusterBusHandler.handleMessage` 的分发分支。
- 新增 **`FailoverManager`** 作为选举状态机持有者，由 `GossipTask` 定时驱动，避免把选举状态散落到 `GossipProtocol`。
- 增补测试：单 master 故障自动提升、多 slave 竞争唯一胜选、原 master 回归后降级为 slave、重复投票去重、纪元裁决。

## Capabilities

### New Capabilities
- `cluster-automatic-failover`: master 节点被判定 FAIL 后，其 slave 自动发起选举、获得多数派授权、提升为新 master 并广播拓扑收敛的能力。

### Modified Capabilities
（无现有 spec 需修改 —— 当前 `openspec/specs/` 为空，本变更新建首个 capability spec）

## Impact

- **代码**：
  - `luban-rds-cluster`：
    - 新增 `gossip/FailoverManager.java`（选举状态机、退避、投票收集、胜选裁决）。
    - 修改 `gossip/GossipProtocol.java`（增 `handleFailoverAuthRequest` / `handleFailoverAuthAck` / `broadcastFailoverResult`，注入 FailoverManager）。
    - 修改 `gossip/GossipTask.java`（每轮调用 `failoverManager.tick()` 触发候选/超时检查）。
    - 修改 `bus/ClusterBusHandler.java`（`handleMessage` 增加 `FAILOVER_AUTH_REQUEST` / `FAILOVER_AUTH_ACK` / `FAILOVER_RESULT` 分支）。
    - 修改 `config/ClusterConfig.java`（新增 `getSlavesOfMaster(masterNodeId)` 辅助方法）。
    - 复用 `handler/ClusterCommandHandler.performFailover` 抽取为可被 FailoverManager 调用的公共方法。
    - 新增 `gossip/FailoverResultMessage.java`（胜选广播，触发全网拓扑更新）。
  - 测试：新增 `FailoverManagerTest`，扩展 `integration/ClusterFailoverTest` 覆盖自动场景。
- **协议**：集群总线新增 3 种已规划消息类型的实际使用（FAILOVER_AUTH_REQUEST/ACK 已有，新增 FAILOVER_RESULT），**对老版本节点不向后兼容**（老节点收到未知类型会走 default 分支告警丢弃，不影响其自身运行，但无法参与新选举 —— 视为可接受的滚动升级约束）。
- **配置**：新增可选配置项 `cluster-failover-grace-period`（slave 退避窗口，默认 `0`，对齐 Redis 的 `CLUSTER_MFAIL_TIMEOUT` 简化）。
- **运维**：修复后集群具备单 master 容错，`CLUSTER FAILOVER` 手动命令语义保持不变。
```

## openspec/changes/add-cluster-automatic-failover/design.md

- Source: openspec/changes/add-cluster-automatic-failover/design.md
- Lines: 1-93
- SHA256: 015863133d7edae2c41b6c77c4108070dac7018db45015f3f1907973b8d60713

[TRUNCATED]

```md
## Context

Luban-RDS 集群模式已实现 Redis Cluster 兼容的故障检测：`FailureDetector` 做超时判定（PFAIL），`GossipTask` 通过 PING gossip 段交换投票，达到多数派后 `broadcastFail` 把节点标记为 FAIL。但**整个链路在 FAIL 之后戛然而止** —— 没有任何代码把 fail 的 master 的某个 slave 提升为新 master。

证据：
- `GossipProtocol` 没有 `handleFailoverAuth*` 方法，`ClusterBusHandler.handleMessage` 没有 `FAILOVER_AUTH_*` 分支。
- `FailoverAuthRequestMessage` / `FailoverAuthAckMessage` 类已存在并含完整编解码，但从未被构造、发送或分发（死代码）。
- 唯一的提升逻辑 `ClusterCommandHandler.performFailover` 只能由客户端发送 `CLUSTER FAILOVER [FORCE|TAKEOVER]` 触发，master 意外宕机时无人调用。

线上 3 主 3 从（172.16.83.11/12/19，每机 3 实例共 9 节点）在单机宕机时，对应 master 的槽位永久无主，应用 Redisson 抛 `WriteRedisConnectionException`，整个服务不可用。

约束：
- 必须与现有手动 `CLUSTER FAILOVER` 语义共存，不破坏。
- 必须复用已有的 `currentEpoch` / `configEpoch` 纪元机制裁决冲突，避免脑裂。
- 多 slave 场景下必须保证**仅有一个** slave 胜选。
- 嵌入式部署（IgRdsCacheContextInitializer 启动多实例），不能依赖外部协调者。

## Goals / Non-Goals

**Goals:**
- master 被集群判定 FAIL 后，其 slave 在秒级内自动发起选举、获得多数派授权、提升为新 master 并接管槽位。
- 多 slave 竞争时，通过 `configEpoch` + 复制偏移量 + nodeId 确定性排序保证唯一胜选。
- 胜选后通过 gossip 广播拓扑变更，全网收敛槽位归属。
- 原 master 回归后自动降级为新 master 的 slave，不触发二次选举。

**Non-Goals:**
- 不实现完整的复制偏移量同步（当前复制链路 `luban-rds-replication` 与集群解耦，本变更以 `nodeId` 字典序作为偏移量的确定性替代，后续可扩展）。
- 不实现手动 `CLUSTER FAILOVER` 的"请求主节点授权"正常流程（保持现有简化）。
- 不处理 split-brain 后的数据合并（依赖现有 RDB/AOF 持久化与人工介入）。
- 不引入新外部依赖。

## Decisions

### D1. 选举算法：复用 Redis Cluster 的 `currentEpoch` 投票模型

**选择**：候选 slave 自增 `currentEpoch`，广播携带 `(currentEpoch, configEpoch, slaveNodeId)` 的 `FailoverAuthRequestMessage`；每个 master 在 `(currentEpoch, 被选 slave)` 维度上"每纪元仅投一票"，依据 `(configEpoch 大者, replicationOffset 大者, nodeId 小者)` 顺序择优投票；候选 slave 收到过半数 master 授权即胜选。

**理由**：Redis Cluster 经过多年生产验证的算法；本项目的 `FailoverAuthRequestMessage` 字段（configEpoch/currentEpoch/replicationOffset）正是为此设计，直接对齐降低实现风险。

**备选**：Paxos/Raft —— 引入完整共识算法过重，且与现有 Gossip 单线程调度器架构不匹配，否决。

### D2. 选举状态机独立为 `FailoverManager`，不污染 `GossipProtocol`

**选择**：新增 `gossip/FailoverManager.java`，持有：选举状态（IDLE/REQUESTING/ELECTED/FAILED）、本节点收到的授权票数、本轮 `electionEpoch`、退避截止时间。由 `GossipTask.run()` 每轮调用 `tick()` 驱动；`GossipProtocol` 仅作为消息收发门面（`handleFailoverAuthRequest`/`handleFailoverAuthAck`/`broadcastFailoverResult` 委托给它）。

**理由**：`GossipProtocol` 已 995 行，再塞选举状态机会破坏单一职责；独立类便于单测（参考 `FailureDetector` 的拆分模式）。

**备选**：把状态机塞进 `GossipProtocol` —— 否决，可测试性差。

### D3. 退避抖动避免多 slave 同时发起

**选择**：slave 检测到 master FAIL 后，等待 `clusterFailoverGracePeriod`（默认 0，可配）+ 随机抖动 `(0, 500ms]` 再广播 AUTH_REQUEST。抖动种子 = `(masterSlotCount, replicationOffset, nodeId)` 的哈希，使不同 slave 抖动值不同但确定。

**理由**：Redis 用复制偏移量决定优先级，本项目复制偏移量尚未接入集群层（见 Non-Goals），退避抖动是轻量替代，保证多 slave 不同时广播、降低冲突概率；即便同时广播，D1 的"每纪元每 master 一票"仍能保证唯一胜选。

### D4. 胜选广播：新增 `FailoverResultMessage`

**选择**：胜选 slave 自增 `currentEpoch` 与自身 `configEpoch`，调用 `performFailover` 提升后，广播 `FailoverResultMessage(winnerNodeId, newConfigEpoch, inheritedSlots)`。收到此消息的节点：更新 winner 为 master、继承槽位、把原 master 标记为该 winner 的 slave（若原 master 回归）、清除 winner 的 FAIL。

**理由**：现有 `broadcastFail` 只能传播"谁挂了"，无法传播"谁接班了"；拓扑收敛必须有一条显式结果消息。

### D5. `performFailover` 抽取为公共方法

**选择**：把 `ClusterCommandHandler.performFailover(slaveNode, masterNode)` 提取到 `FailoverManager`（或 `ClusterConfig` 的工具方法），供手动命令和自动选举共用，避免逻辑重复。

### D6. 投票去重与纪元竞争

**选择**：master 节点维护 `Map<String, Long> votesCast`（key = 被投 slaveId, value = 投票时的 currentEpoch）；收到 AUTH_REQUEST 时：
1. 若 `request.currentEpoch < myCurrentEpoch` → 拒绝（旧纪元请求）。
2. 若已对该 slave 投过票且 `recordedEpoch == request.currentEpoch` → 重发 ACK（幂等）。
3. 若已对该 slave 投过票且 `recordedEpoch < request.currentEpoch` → 视为新纪元，允许重新评估。
4. 若本纪元已投给其他 slave → 拒绝。
5. 否则按 D1 择优规则投票，记录 `votesCast[slaveId] = currentEpoch`。

## Risks / Trade-offs

- **[风险] 多 slave 同时胜选导致脑裂** → D1 的"每纪元每 master 一票" + D3 退避抖动 + D4 结果广播共同保证：同一 currentEpoch 内任何 master 最多投一张票，故最多一个 slave 能拿到过半票。
- **[风险] 选举期间槽位短暂无主，客户端收到 MOVED 到旧 master** → 这是 Redis Cluster 本身的固有窗口（秒级），客户端按 `-MOVED` 重试即可收敛到新 master；不额外处理。
- **[风险] 老 master 回归后仍认为自己是 master，与新 master 冲突** → D4 的结果消息会把老 master 强制降级为 slave；若老 master 在结果消息到达前已恢复并广播，其 `configEpoch` 较小会在现有 `syncSlotsFromNode` 纪元裁决中败北（已有机制）。
- **[风险] 复制偏移量缺失导致数据不一致** → 本变更 Non-Goals 明确接受此风险，胜选 slave 可能不是数据最新的；运维应确保 slave 复制延迟可控。
```

Full source: openspec/changes/add-cluster-automatic-failover/design.md

## openspec/changes/add-cluster-automatic-failover/tasks.md

- Source: openspec/changes/add-cluster-automatic-failover/tasks.md
- Lines: 1-53
- SHA256: 59a0c362afd1a197205e87448368696229ba81b1417beb74d0f59587211b5480

```md
## 1. 基础设施

- [ ] 1.1 新增 `GossipMessageType.FAILOVER_RESULT (0x07)`，并在 `GossipMessage.createMessage` 工厂注册。
- [ ] 1.2 新增 `gossip/FailoverResultMessage.java`（含 winnerNodeId、newConfigEpoch、inheritedSlots BitSet 字段 + 编解码）。
- [ ] 1.3 `ClusterConfig` 新增 `getSlavesOfMaster(String masterNodeId)` 辅助方法（返回该 master 的所有 slave 列表）。

## 2. FailoverManager 选举状态机

- [ ] 2.1 新增 `gossip/FailoverManager.java`：定义 `FailoverState` 枚举（IDLE/REQUESTING/ELECTED/FAILED）、字段（state、electionEpoch、authVotes、requestDeadline、timeoutDeadline）。
- [ ] 2.2 实现 `tick(ClusterConfig, FailureDetector)`：检测本节点是否 slave 且其 master 处于 FAIL，满足则进入 REQUESTING 并计算退避截止时间（`gracePeriod + nodeIdHash % 500ms`）。
- [ ] 2.3 实现退避到期后广播 AUTH_REQUEST：自增 currentEpoch、构造 `FailoverAuthRequestMessage`、`busClient.broadcast`。
- [ ] 2.4 实现 `onAuthAck(FailoverAuthAckMessage)`：累计去重的不同 master 授权票数，达 `masterCount/2+1` 后调用 `performFailoverAndBroadcast`。
- [ ] 2.5 实现选举超时回退：`REQUESTING` 态超过 `2 * cluster-node-timeout` 则回 IDLE 并清票数。
- [ ] 2.6 实现 `performFailoverAndBroadcast`：复用 `ClusterCommandHandler.performFailover` 逻辑（抽取为 `ClusterConfig` 或工具方法），自增 currentEpoch/configEpoch，广播 `FailoverResultMessage`。

## 3. 投票授权（master 侧）

- [ ] 3.1 FailoverManager（或新 `VoteRegistry`）维护 `votesCast: Map<String, Long>`（被投 slaveId → currentEpoch）。
- [ ] 3.2 实现 `onAuthRequest(FailoverAuthRequestMessage)`：仅 master 节点处理，校验纪元、每纪元每 slave 一票、择优规则（configEpoch > replicationOffset > nodeId），授权则广播 `FailoverAuthAckMessage`。
- [ ] 3.3 幂等处理：同 currentEpoch 重复请求重发 ACK，不重复自增 currentEpoch。

## 4. 消息分发接入

- [ ] 4.1 `GossipProtocol` 新增 `handleFailoverAuthRequest` / `handleFailoverAuthAck` / `handleFailoverResult`，委托给 `FailoverManager`。
- [ ] 4.2 `GossipProtocol` 构造时创建 `FailoverManager`，提供 `getFailoverManager()` 访问器。
- [ ] 4.3 `ClusterBusHandler.handleMessage` 增加 `FAILOVER_AUTH_REQUEST` / `FAILOVER_AUTH_ACK` / `FAILOVER_RESULT` 三个 case 分支。
- [ ] 4.4 `GossipTask.run` 增加 `failoverManager.tick()` 调用（在 `checkAndBroadcastFail` 之后，确保 FAIL 状态已更新）。

## 5. 结果收敛

- [ ] 5.1 `FailoverManager.handleFailoverResult`：纪元裁决、winner 标记 MASTER、槽位重分配、原 master 降级 SLAVE、清 FAIL/PFAIL、自增本地 currentEpoch。
- [ ] 5.2 触发 `onTopologyChanged` 回调，持久化 nodes.conf。

## 6. 配置项

- [ ] 6.1 在集群配置解析处新增 `cluster-failover-grace-period`（默认 0），传入 `FailoverManager`。
- [ ] 6.2 在 `CLUSTER INFO` 输出补 `cluster_failover_grace_period` 字段（可选，便于排查）。

## 7. 测试

- [ ] 7.1 `FailoverManagerTest`：单元测试 IDLE→REQUESTING→ELECTED 状态流转、退避抖动、超时回退。
- [ ] 7.2 `FailoverManagerTest`：投票授权场景（首投、重复幂等、本纪元已投他 slave 拒绝、过期纪元拒绝）。
- [ ] 7.3 `FailoverManagerTest`：胜选后 performFailover 调用、currentEpoch/configEpoch 自增、FailoverResult 广播。
- [ ] 7.4 `FailoverManagerTest`：FailoverResult 收敛（winner 提权、原 master 降级、槽位转移、旧纪元忽略）。
- [ ] 7.5 扩展 `integration/ClusterFailoverTest`：单 master FAIL 后 slave 自动提升的端到端场景。
- [ ] 7.6 扩展 `integration/ClusterFailoverTest`：多 slave 竞争唯一胜选（构造两 slave 同时进入 REQUESTING）。
- [ ] 7.7 扩展 `integration/ClusterFailoverTest`：手动 `CLUSTER FAILOVER TAKEOVER` 与自动选举共存、互不干扰。
- [ ] 7.8 运行 `mvn test -pl luban-rds-cluster` 全量通过。

## 8. 文档

- [ ] 8.1 更新 `AGENTS.md` 第 10 节 Cluster 章节，补充自动故障转移流程与新增配置项。
- [ ] 8.2 更新 `luban-rds-bin/src/main/resources/luban-rds.conf` 模板，加入 `cluster-failover-grace-period` 注释。
```

## openspec/changes/add-cluster-automatic-failover/specs/cluster-automatic-failover/spec.md

- Source: openspec/changes/add-cluster-automatic-failover/specs/cluster-automatic-failover/spec.md
- Lines: 1-143
- SHA256: 9a9219c34cd7f6ff2ca3b5f4ccddab69b939838aa8a599e83c1e8ad703e91f85

[TRUNCATED]

```md
# cluster-automatic-failover

## Purpose

定义 Luban-RDS 集群在 master 节点被多数派判定 FAIL 后，由其 slave 自动发起选举、获得授权、提升为新 master 并广播拓扑收敛的能力。目标是单 master 宕机时集群在秒级自愈，对外持续可用。

## ADDED Requirements

### Requirement: slave 在 master 被判 FAIL 后自动触发选举

The FailoverManager MUST transition a slave into `REQUESTING` state when its master is marked `FAIL`, and MUST broadcast `FailoverAuthRequestMessage` after the backoff window (`clusterFailoverGracePeriod` + random jitter ≤ 500ms) elapses. 当 slave 观察到其 master 在集群配置中被标记为 `FAIL` 状态时，FailoverManager 状态机必须进入 `REQUESTING` 态，并在退避窗口到期后广播 `FailoverAuthRequestMessage`。

#### Scenario: slave 检测到 master FAIL 启动选举

- **WHEN** slave S 的 master M 被 `FailureDetector` + 多数派投票标记为 `FAIL`
- **AND** S 处于 `IDLE` 态且自身非 FAIL/PFAIL
- **THEN** FailoverManager 转入 `REQUESTING` 态
- **AND** 记录选举起始时间与退避截止时间
- **AND** 不立即广播，等待退避窗口

#### Scenario: slave 在退避窗口到期后广播 AUTH_REQUEST

- **WHEN** FailoverManager 处于 `REQUESTING` 态且当前时间 ≥ 退避截止时间
- **THEN** 自增 `currentEpoch`
- **AND** 构造 `FailoverAuthRequestMessage(myNodeId, myConfigEpoch, currentEpoch, replicationOffset=0)`
- **AND** 通过 `ClusterBusClient.broadcast` 广播给所有 master 节点

#### Scenario: 非 slave 节点不触发选举

- **WHEN** master 节点或其他 slave 的 master 未 FAIL
- **THEN** FailoverManager 保持在 `IDLE` 态，不广播任何选举消息

### Requirement: master 节点按纪元与择优规则投票授权

每个 master 节点收到 `FailoverAuthRequestMessage` 后，MUST 依据"每 currentEpoch 每被投 slave 仅投一票"约束和择优顺序决定是否授权，授权则 MUST 回送 `FailoverAuthAckMessage`。

#### Scenario: master 对首个有效请求投票

- **WHEN** master Ma 收到来自 slave S 的 AUTH_REQUEST，且 `request.currentEpoch >= Ma.currentEpoch`
- **AND** Ma 在本 currentEpoch 内尚未给任何 slave 投过票
- **THEN** Ma 自增自身 `currentEpoch` 至少到 `request.currentEpoch`（若落后）
- **AND** 记录 `votesCast[S] = request.currentEpoch`
- **AND** 广播 `FailoverAuthAckMessage(Ma.nodeId, request.currentEpoch, S.nodeId)` 给 S

#### Scenario: 重复请求触发幂等 ACK

- **WHEN** Ma 收到 S 的 AUTH_REQUEST，且 `votesCast[S] == request.currentEpoch`
- **THEN** 重发 `FailoverAuthAckMessage`（幂等）
- **AND** 不重复自增 currentEpoch

#### Scenario: 本纪元已投他 slave 则拒绝

- **WHEN** Ma 在 `request.currentEpoch` 已给 S' 投票，且 `S' != S`
- **THEN** 不回送 ACK
- **AND** 静默丢弃（DEBUG 日志记录）

#### Scenario: 过期纪元请求被拒绝

- **WHEN** `request.currentEpoch < Ma.currentEpoch`
- **THEN** 不回送 ACK

### Requirement: 候选 slave 收集过半授权后胜选并提升

候选 slave MUST 累计 `FailoverAuthAckMessage`，当不同 master 的授权票数 ≥ `masterCount/2 + 1` 时 MUST 判定胜选，并执行 `performFailover` 提升为新 master。

#### Scenario: 收到过半授权胜选

- **WHEN** 候选 slave S 收到的不同 master 的 ACK 数量 ≥ `(集群 master 总数 / 2) + 1`
- **THEN** FailoverManager 转入 `ELECTED` 态
- **AND** 调用 `performFailover(S, 原 master M)`：S 移除 SLAVE 标志、添加 MASTER、继承 M 的槽位、`slotManager` 更新归属
- **AND** 自增 `currentEpoch` 与 S 的 `configEpoch`，设为新值
- **AND** 把 M（若仍存在）标记为 SLAVE 并 `masterNodeId = S`
- **AND** 广播 `FailoverResultMessage`

#### Scenario: 选举超时回退

- **WHEN** FailoverManager 处于 `REQUESTING` 态超过 `2 * cluster-node-timeout` 仍未获得过半授权
- **THEN** 回退到 `IDLE` 态，清空票数
- **AND** 允许下一轮 `tick()` 重新触发（仍满足 master FAIL 条件时）

```

Full source: openspec/changes/add-cluster-automatic-failover/specs/cluster-automatic-failover/spec.md

