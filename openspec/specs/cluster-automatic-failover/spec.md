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

`CLUSTER FAILOVER`、`CLUSTER FAILOVER FORCE`、`CLUSTER FAILOVER TAKEOVER` 三种手动 failover 模式执行 `performFailover` 后，MUST 广播 `FailoverResultMessage` 通知全网拓扑变更，使其他节点立即收敛而非等待 gossip 传播。广播职责 MUST 收敛到自动/手动两条路径共用的方法（实现取 Option B：抽取 `broadcastFailoverResult(newMaster, oldMaster)` 共用方法，由两条路径在 epoch 自增与 `setConfigEpoch` 之后再调用，避免 `performFailover` 在 epoch 自增前广播导致消息携带过期 epoch；不直接下沉到 `performFailover` 内），避免自动 failover 路径重复广播。手动 failover 还 MUST 对齐自动路径，补 `masterNode.setConfigEpoch(currentEpoch)`，使原 master 的 configEpoch 与新主一致。

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
