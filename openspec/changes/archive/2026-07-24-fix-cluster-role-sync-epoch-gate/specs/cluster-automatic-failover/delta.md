# Delta Spec: cluster-automatic-failover

## ADDED Requirements

### Requirement: 集群角色经 gossip 可靠收敛

集群中 master/slave 角色变更（含 `CLUSTER REPLICATE` 与故障转移提升）必须能通过 PING/PONG/MEET 消息头携带的发送方角色信息（`senderFlags`、`senderMasterNodeId`、`senderConfigEpoch`）在全网收敛。接收方在处理消息时，若消息携带的发送方配置纪元严格大于接收方本地原有纪元快照，必须采纳消息中的角色（MASTER/SLAVE）与 masterNodeId。

#### Scenario: slave 经 MEET 同步角色

- **WHEN** 节点 A 执行 `CLUSTER REPLICATE <masterId>` 后 `configEpoch` 提升，并向节点 B 发送 MEET，携带 `senderFlags={SLAVE}`、`senderMasterNodeId=<masterId>`、`senderConfigEpoch=N`
- **THEN** 节点 B 在完成握手并把 A 的本地 `configEpoch` 提升到 N 后，仍能基于"提升前的本地纪元快照"判断 N 严格大于基线，将 A 从 MASTER 切换为 SLAVE 并设置 `masterNodeId`

#### Scenario: 陈旧纪元不回退角色

- **WHEN** 节点 B 已知节点 A 为 SLAVE（`configEpoch=N`），收到 A 的陈旧 PING 携带 `senderFlags={MASTER}`、`senderConfigEpoch=M`（M < N）
- **THEN** 节点 B 不应把 A 切换回 MASTER

### Requirement: `CLUSTER ADDSLOTS` 更新节点配置纪元

`CLUSTER ADDSLOTS` 执行后，当前节点必须把自身 `configEpoch` 设置为集群 `currentEpoch`（`incrementEpoch` 之后的新值），与 `CLUSTER REPLICATE` 和故障转移路径保持一致，确保基于纪元的槽位/角色冲突裁决可靠。

#### Scenario: ADDSLOTS 后 configEpoch 非零

- **WHEN** 节点执行 `CLUSTER ADDSLOTS 0-5460`
- **THEN** 该节点的 `configEpoch` 等于执行后的 `currentEpoch`（> 0）

### Requirement: 支持 `CLUSTER SET-CONFIG-EPOCH` 命令

`ClusterCommandHandler` 必须支持 `CLUSTER SET-CONFIG-EPOCH <epoch>` 子命令，用于 `redis-cli --cluster create` 建立初始配置纪元。执行后设置当前节点的 `configEpoch` 为指定值，并把集群 `currentEpoch` 提升到至少该值。

#### Scenario: SET-CONFIG-EPOCH 设置纪元

- **WHEN** 节点执行 `CLUSTER SET-CONFIG-EPOCH 4`
- **THEN** 该节点的 `configEpoch` 变为 4，集群 `currentEpoch` 至少为 4，命令返回 `+OK`
