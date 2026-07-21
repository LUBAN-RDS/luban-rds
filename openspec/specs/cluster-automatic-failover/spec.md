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

### Requirement: 胜选结果通过 FailoverResultMessage 全网收敛

新增 `GossipMessageType.FAILOVER_RESULT (0x08)`（0x00-0x07 已被现有消息类型占用），胜选 slave MUST 广播 `FailoverResultMessage`，收到该消息的节点 MUST 按结果更新本地拓扑视图。

#### Scenario: 收到 FailoverResult 更新拓扑

- **WHEN** 节点 N 收到 `FailoverResultMessage(winner, newConfigEpoch, slots)`
- **AND** `newConfigEpoch > N 现存的该槽位 epoch`（纪元裁决）
- **THEN** 把 winner 标记为 MASTER、清除其 FAIL/PFAIL
- **AND** 按 slots 更新 `slotAssignment` 与 winner 的槽位集合
- **AND** 若原 master M 在 N 的配置中存在，则把 M 降级为 SLAVE 且 `masterNodeId = winner`
- **AND** 自增本地 `currentEpoch` 至 `newConfigEpoch`
- **AND** 触发 `onTopologyChanged` 回调持久化 nodes.conf

#### Scenario: 旧纪元结果被忽略

- **WHEN** `newConfigEpoch <= N 已记录的该 winner epoch`
- **THEN** 忽略该消息（防回放）

### Requirement: ClusterBusHandler 分发新消息类型

`ClusterBusHandler.handleMessage` MUST 为 `FAILOVER_AUTH_REQUEST`、`FAILOVER_AUTH_ACK`、`FAILOVER_RESULT` 增加 case 分支，分别委托给 `GossipProtocol` 对应方法。

#### Scenario: 收到 AUTH_REQUEST 委托处理

- **WHEN** `message.type == FAILOVER_AUTH_REQUEST`
- **THEN** 调用 `gossipProtocol.handleFailoverAuthRequest((FailoverAuthRequestMessage) message)`
- **AND** 返回 null（无需立即响应，由投票逻辑决定是否发 ACK）

#### Scenario: 收到 AUTH_ACK 委托处理

- **WHEN** `message.type == FAILOVER_AUTH_ACK`
- **THEN** 调用 `gossipProtocol.handleFailoverAuthAck((FailoverAuthAckMessage) message)`

#### Scenario: 收到 FAILOVER_RESULT 委托处理

- **WHEN** `message.type == FAILOVER_RESULT`
- **THEN** 调用 `gossipProtocol.handleFailoverResult((FailoverResultMessage) message)`

### Requirement: 手动 CLUSTER FAILOVER 与自动选举共存

自动选举的实现 MUST NOT 破坏现有 `CLUSTER FAILOVER [FORCE|TAKEOVER]` 命令的语义和返回值。

#### Scenario: 手动 FAILOVER TAKEOVER 行为不变

- **WHEN** 客户端向 slave 发送 `CLUSTER FAILOVER TAKEOVER`
- **THEN** 立即执行 `performFailover` 提升，返回 `+OK`
- **AND** 不依赖选举投票
- **AND** FailoverManager 状态保持 `IDLE`（手动接管不经过选举状态机）

### Requirement: 配置项 cluster-failover-grace-period

系统 MUST 支持可选配置项 `cluster-failover-grace-period`（毫秒，默认 0），控制 slave 在 master FAIL 后等待多久再发起选举。

#### Scenario: 默认值生效

- **WHEN** 配置文件未指定 `cluster-failover-grace-period`
- **THEN** 使用默认值 `0`（仅保留随机抖动）

#### Scenario: 自定义退避窗口

- **WHEN** 配置 `cluster-failover-grace-period 2000`
- **THEN** slave 在 master FAIL 后等待 `2000ms + 抖动` 再广播 AUTH_REQUEST
