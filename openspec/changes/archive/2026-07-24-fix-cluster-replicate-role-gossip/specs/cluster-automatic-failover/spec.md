# cluster-automatic-failover

## MODIFIED Requirements

### Requirement: 从节点角色与主从关系必须经 Gossip 传播

`CLUSTER REPLICATE` 仅在接收节点本地变更角色，MUST 通过集群总线消息（PING/PONG/MEET 头）携带发送方 MASTER/SLAVE 角色标志、`masterNodeId` 与 `configEpoch`，使其它节点能据此同步发送方角色。角色同步 MUST 遵循 configEpoch 裁决（严格大于才切换角色、大于等于且节点已是 slave 才同步 masterNodeId），防止陈旧消息回退已完成的故障转移。

#### Scenario: CLUSTER REPLICATE 后从节点角色经 Gossip 传播

- **WHEN** 节点 S 执行 `CLUSTER REPLICATE <masterId>` 成为 master M 的从节点
- **AND** S 随后向其它节点发送 PING/PONG
- **THEN** 消息头携带 `senderFlags={SLAVE}`、`senderMasterNodeId=<M>`、`senderConfigEpoch=<S 自增后的 epoch>`
- **AND** 接收方 R 将本地 S 节点视图同步为 SLAVE 且 `masterNodeId=<M>`（当 `senderConfigEpoch >= R 中 S 的本地 epoch`）

#### Scenario: 主节点视角中从节点显示为 slave

- **WHEN** 从节点 S 经 Gossip 传播其角色后
- **THEN** 主节点 M 本地 `clusterConfig.getNode(S).isSlave()` 为 true
- **AND** `getNode(S).getMasterNodeId()` 等于 M 的 nodeId
- **AND** `CLUSTER NODES` 输出中 S 行的 flags 含 `slave`、master 字段为 M 的 nodeId

#### Scenario: 陈旧消息不回退已提升的 master

- **WHEN** 节点 S 已通过故障转移提升为 MASTER（configEpoch=E1）
- **AND** 收到关于 S 的陈旧 gossip 消息（flags={SLAVE}, configEpoch=E0 < E1）
- **THEN** 不将 S 回退为 SLAVE
- **AND** 不覆盖 S 的 masterNodeId

#### Scenario: 主节点 FAIL 后从节点成功发起故障转移并接管槽位

- **WHEN** 主节点 M 被标记 FAIL
- **AND** M 的从节点 S 已被其它节点正确识别为 SLAVE（经 Gossip 传播）
- **THEN** S 的 `FailoverManager.tryStartElection` 满足 `me.isSlave() && me.getMasterNodeId()==M` 前置条件
- **AND** S 进入 `REQUESTING` 态并广播 `FailoverAuthRequestMessage`
- **AND** 获得多数派授权后 S 提升为 MASTER 并接管 M 的槽位
- **AND** 集群 `isClusterOk()` 恢复 true，所有 16384 槽位被可用 master 覆盖
