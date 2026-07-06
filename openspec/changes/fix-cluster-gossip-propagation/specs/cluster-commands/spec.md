# cluster-commands

## MODIFIED Requirements

### Requirement: CLUSTER REPLICATE 清空从节点槽位

当节点通过 `CLUSTER REPLICATE` 转为从节点时，必须清空自身持有的槽位所有权，避免从节点仍被识别为 slot owner，并通过 Gossip 传播后造成槽位归属错误。

#### Scenario: REPLICATE 后从节点不再持有槽位
- **WHEN** 节点执行 `CLUSTER REPLICATE <masterNodeId>` 成功
- **THEN** 该节点的 `slots` BitSet 应被清空
- **AND** `ClusterConfig.slotAssignment` 中所有原本归属该 nodeId 的 slot 应被置为 null（无 owner）
- **AND** 后续该节点发出的 PING/PONG/MEET 的 senderSlots 应为空 BitSet

#### Scenario: CLUSTER NODES 反映正确主从关系
- **WHEN** 集群拓扑与槽位已通过 Gossip 收敛
- **THEN** `CLUSTER NODES` 应正确显示每个节点的 master/slave 标志与 masterNodeId
- **AND** 仅 master 节点行末尾显示其负责的 slot 区间
- **AND** `CLUSTER INFO` 的 `cluster_state` 为 `ok`，`cluster_slots_assigned` 为 16384
