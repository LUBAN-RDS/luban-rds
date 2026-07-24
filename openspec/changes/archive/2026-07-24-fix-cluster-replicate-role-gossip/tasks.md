# Tasks: 修复 CLUSTER REPLICATE 从节点角色无法经 Gossip 传播

## 1. 消息头扩展：新增发送方角色字段

- [x] 1.1 `PingMessage`：新增 `senderFlags`（`Set<ClusterNodeState>`）、`senderMasterNodeId`（`String`）、`senderConfigEpoch`（`long`）字段及 getter/setter，更新 `getEncodedLength`/`encode`/`decode` 编解码（flags 用与 `GossipNodeInfo` 一致的方式编码；masterNodeId 40 字符定长，null 用空串占位；epoch 8 字节 long）。
- [x] 1.2 `PongMessage`：同 PingMessage 新增三个字段及编解码。
- [x] 1.3 `MeetMessage`：已有 `senderConfigEpoch`；新增 `senderFlags`、`senderMasterNodeId` 字段及编解码（复用相同编码方式）。

## 2. 发送侧：填充发送方角色信息

- [x] 2.1 `GossipProtocol.sendPing`：构造 PingMessage 后设置 `senderFlags`（来自 myNode 角色）、`senderMasterNodeId`、`senderConfigEpoch`。
- [x] 2.2 `GossipProtocol.handlePing`：构造 PongMessage 响应时同样填充 myNode 角色信息。
- [x] 2.3 `GossipProtocol.sendMeet` 及 `initiateMeetForDiscoveredNode`：构造 MeetMessage 时填充 `senderFlags`、`senderMasterNodeId`（senderConfigEpoch 已有）。

## 3. 接收侧：同步发送方角色

- [x] 3.1 抽取私有方法 `syncSenderRole(ClusterNode sender, Set<ClusterNodeState> flags, String masterNodeId, long configEpoch)`，复用 `processGossipNodes` 中既有的纪元裁决策略（严格大于才切角色、大于等于且为 slave 才同步 masterNodeId）。
- [x] 3.2 `updateNodeFromPingMessage`：在 `syncSlotsFromNode` 之后调用 `syncSenderRole(senderNode, ping.getSenderFlags(), ping.getSenderMasterNodeId(), ping.getSenderConfigEpoch())`。
- [x] 3.3 `updateNodeFromPongMessage`：同上调用 `syncSenderRole`。
- [x] 3.4 `updateNodeFromMeetMessage`：同上调用 `syncSenderRole`（configEpoch 用 `meet.getSenderConfigEpoch()`）。

## 4. 测试

- [x] 4.1 新增 `ClusterReplicateGossipTest`（集成测试，使用 `EmbeddedCluster`）：2 主 + 2 从，分配槽位，对从节点发 `CLUSTER REPLICATE`，等待 gossip 传播，断言主节点视角中从节点 `isSlave()` 且 `masterNodeId` 正确，`CLUSTER NODES` 从节点行显示 `slave` + 其主节点 ID。
- [x] 4.2 在同一测试类补充交叉验证场景：master1 视图中 slave0（非自身从节点）也能通过 Gossip 识别为 SLAVE 且 masterNodeId 正确。
- [x] 4.3 运行 `luban-rds-cluster` 模块全部测试：349 项，3 项为预存环境失败（Jedis/Lettuce/Redisson 客户端连接 setUp，在干净 master 上同样失败，与本修复无关），其余 346 项全部通过，无回归。

## 5. 构建

- [x] 5.1 `mvn -pl luban-rds-cluster -am test`（Java17）通过（排除预存失败项后 BUILD SUCCESS）。
