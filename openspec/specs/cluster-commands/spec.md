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

## ADDED Requirements

### Requirement: CLUSTER NODES 线协议行尾符

`CLUSTER NODES` 响应的 RESP bulk string payload 中，每个节点行 MUST 以裸 `\n`（LF）结尾，不得包含 `\r`（CR），以对齐真实 Redis 的 `clusterGenNodesDescription` 行为。RESP bulk string 的框架（`$<len>\r\n` 头与尾部 `\r\n`）仍按 RESP 规范封装，但 payload 内部行分隔符 MUST 为 `\n`。

#### Scenario: 集群客户端可解析 CLUSTER NODES 的 slot 字段

- **WHEN** 集群拓扑收敛且存在持有 slot 区间的 master 节点
- **AND** 客户端（如 Redisson）发送 `CLUSTER NODES` 并用 `response.split("\n")` 切行
- **THEN** 每行末尾不得残留 `\r`
- **AND** 末尾 slot 字段（如 `0-5460`）可被 `Integer.parseInt` 成功解析
- **AND** 不抛出 `NumberFormatException`

#### Scenario: 持有非连续 slot 的节点行可被正确解析

- **WHEN** 某 master 节点持有非连续 slot（如 slot 0 与 slot 100）
- **AND** `CLUSTER NODES` 该行末尾字段为 `0 100`（空格分隔多段 slot 区间）
- **THEN** 该行以 `\n` 结尾，每段 slot token（`0`、`100`）不含 `\r`
- **AND** 客户端可逐段 `Integer.parseInt` 解析成功
