## MODIFIED Requirements

### Requirement: FAIL 状态保护期

节点被集群共识标记为 FAIL 后，MUST 在至少 `NODE_TIMEOUT * 2` 时间内保持 FAIL 状态。在此保护期内，来自该节点的 PING/PONG 消息 MUST NOT 清除其 FAIL 状态（PFAIL 可正常清除）。保护期过后，若节点确实恢复（收到 PING/PONG），方可清除 FAIL 状态。此保护期确保 slave 有足够窗口完成 failover 选举，避免 master 短暂抖动导致选举被取消。

`ClusterNode` MUST 记录节点被标记 FAIL 的时刻（`failTime`），并在清除 FAIL 状态时清零。`FailureDetector.clearNodeFailState()` MUST 在节点处于 FAIL 且 `System.currentTimeMillis() - failTime < 2 * nodeTimeout` 时拒绝清除 FAIL（仅清除 PFAIL）。failover 提升路径（`performFailover`/`onFailoverResult` 中角色变更导致的 `removeState(FAIL)`）不受保护期约束，因为那是拓扑变更而非节点恢复判定。

#### Scenario: 保护期内 PONG 不清除 FAIL

- **WHEN** 节点 N 被标记 FAIL，且 `failTime` 距今 < `2 * NODE_TIMEOUT`
- **AND** 收到来自 N 的 PONG 消息触发 `clearNodeFailState(N)`
- **THEN** N 的 FAIL 状态保持不变
- **AND** 日志记录"FAIL 保护期内，拒绝清除 FAIL 状态"

#### Scenario: 保护期后 PONG 清除 FAIL

- **WHEN** 节点 N 被标记 FAIL，且 `failTime` 距今 >= `2 * NODE_TIMEOUT`
- **AND** 收到来自 N 的 PONG 消息触发 `clearNodeFailState(N)`
- **THEN** N 的 FAIL 状态被清除
- **AND** `confirmedFailNodes` 与 `pfailVotes` 中 N 的记录被清除

#### Scenario: 保护期内 PFAIL 仍可清除

- **WHEN** 节点 N 同时处于 PFAIL 和 FAIL，且在 FAIL 保护期内
- **AND** 收到来自 N 的 PONG 消息
- **THEN** N 的 PFAIL 状态被清除
- **AND** N 的 FAIL 状态保持不变

#### Scenario: master 宕机时 failover 不被取消

- **WHEN** Master M 宕机被标记 FAIL
- **AND** Slave S 进入选举 REQUESTING 状态（退避窗口 ≤ 500ms）
- **AND** 保护期内收到来自 M 的短暂 PONG 恢复
- **THEN** M 的 FAIL 状态保持（保护期未过）
- **AND** S 的 `handleRequestingState` 检测 `master.isFail()` 为 true
- **AND** S 继续选举流程，不被"原 master 已恢复"取消

#### Scenario: failover 提升路径不受保护期约束

- **WHEN** Slave S 胜选后执行 `performFailover` 将原 master M 降级为 slave
- **THEN** M 的 FAIL 状态被显式清除（`removeState(FAIL)`）
- **AND** 此清除不受 FAIL 保护期约束（角色变更路径，非节点恢复判定）
