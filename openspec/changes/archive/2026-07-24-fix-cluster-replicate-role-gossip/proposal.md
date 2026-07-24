## Why

集群模式（6 节点，`redis-cli --cluster create --cluster-replicas 1`）下，当持有槽位的主节点宕机时，其从节点不会被提升，集群持续报 `Not all slots covered! Only 10923 slots are available`，业务无法恢复。

### 根因分析

`CLUSTER REPLICATE` 仅在接收命令的节点本地将自身置为 SLAVE（`ClusterCommandHandler.clusterReplicate`：`myNode.addState(SLAVE)` + `setMasterNodeId`），**不发送任何广播消息**。该从节点角色理论上通过 Gossip 传播给其它节点，但当前实现存在致命缺陷：

1. **PING/PONG 消息头不携带发送方角色信息。** `sendPing`/`handlePing` 仅在消息头里携带 `senderSlots`，不携带发送方的 MASTER/SLAVE 标志或 `masterNodeId`。`updateNodeFromPingMessage` / `updateNodeFromPongMessage` 因此只同步槽位、不同步发送方角色。
2. **节点从不 gossip 自己。** `selectGossipNodes` 显式排除 `node.isMyself()`，因此节点自身的 SLAVE 角色 / masterNodeId 不会出现在它发出的 gossip 列表中。
3. 结果：节点 A 通过 `CLUSTER REPLICATE` 变成从节点后，其它节点永远无法感知 A 的角色变更，本地视图中 A 仍是 MASTER。`CLUSTER NODES` 全部显示为 `master`、master 字段为 `-`（与报错日志完全吻合）。
4. 连锁后果：主节点 9738 宕机被标记 FAIL 后，`FailoverManager.tryStartElection` 要求 `me.isSlave() && me.getMasterNodeId()!=null`，但所有"从节点"在本地仍是 MASTER，无人发起选举；槽位 10923-16383 永久挂在 FAIL 的 9738 上，`ClusterStateManager.isClusterOk` 返回 false，Redisson 校验 `checkSlotsCoverage` 失败。

### 验证依据

- 现有 `ClusterFailoverTest` 全部通过**手动 `addState(SLAVE)` + `setMasterNodeId`** 构造从节点（见该测试 197-200、237-240、264-267 行），从未覆盖"`CLUSTER REPLICATE` 后角色经 Gossip 传播"的真实路径，故缺陷长期未被测试发现。

## What Changes

- 在 PING/PONG（及 MEET）消息头中新增发送方角色标志（MASTER/SLAVE）与 `masterNodeId` 字段，并在编解码中处理。
- `handlePing`/`handlePong`/`handleMeet` 收到消息后，基于发送方携带的角色信息与 configEpoch 同步发送方本地角色与 masterNodeId（沿用 `processGossipNodes` 中既有的"严格大于才切换角色、大于等于才同步 masterNodeId"防陈旧回退策略）。
- 增加集成测试：真实 `CLUSTER REPLICATE` + Gossip 传播后，从节点的 master 视图中该节点为 SLAVE 且 masterNodeId 正确；主节点 FAIL 后从节点成功发起故障转移并接管槽位。

## Capabilities

### New Capabilities

### Modified Capabilities
- `cluster-automatic-failover`: 从节点角色与主从关系必须能通过 Gossip 传播，使主节点能感知其从节点并在主节点故障时由从节点发起选举。

## Impact

- 影响 `luban-rds-cluster` 模块：`PingMessage`/`PongMessage`/`MeetMessage` 编解码、`GossipProtocol` 的 PING/PONG/MEET 处理与发送逻辑。
- 不改变 RESP 协议或 `CLUSTER` 命令对外行为；仅修复集群总线消息内部字段与角色同步。
- 集群总线消息为内部二进制协议，新增字段需同步收发两端编解码，向后兼容旧节点需评估（当前集群为同版本部署，按不兼容处理即可）。
