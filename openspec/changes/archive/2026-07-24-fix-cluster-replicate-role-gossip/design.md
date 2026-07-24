# Design: 修复 CLUSTER REPLICATE 从节点角色无法经 Gossip 传播

## 方案

核心思路：**让节点发出的每条 PING/PONG/MEET 消息头携带发送方自身角色与 masterNodeId**，接收方据此同步发送方节点角色。这是 Redis 官方的做法（cluster bus 消息头含 `flags` 字段），且与现有 `processGossipNodes` 中"基于 configEpoch 同步第三方节点角色"的逻辑对称——只是把同步对象从"gossip 里的第三方节点"扩展到"消息发送方本身"。

## 改动点

### 1. 消息头扩展（`PingMessage` / `PongMessage` / `MeetMessage`）

在现有消息头字段基础上新增：
- `senderFlags`：`Set<ClusterNodeState>`，发送方角色标志（MASTER/SLAVE，以及 FAIL/PFAIL 视需要）。
- `senderMasterNodeId`：`String`，发送方 masterNodeId（仅从节点有意义，主节点为 null）。
- `senderConfigEpoch`：发送方 configEpoch（用于角色同步的纪元裁决）。

> `MeetMessage` 现已携带 `senderConfigEpoch`，复用即可；PING/PONG 需新增 epoch 字段。

编解码：在消息二进制 `encode`/`decode` 中追加这些字段的序列化（flags 用枚举 ordinal 位图或简单可变编码；masterNodeId 用 40 字符定长 ASCII；epoch 用 8 字节 long）。保持与既有 `GossipNodeInfo` 编码风格一致。

### 2. 发送侧（`GossipProtocol.sendPing` / `handlePing` 构造 PONG / `sendMeet`）

构造消息时填充发送方自身信息：
```java
ping.setSenderFlags(convertToFlags(myNode));      // MASTER 或 SLAVE
ping.setSenderMasterNodeId(myNode.getMasterNodeId());
ping.setSenderConfigEpoch(myNode.getConfigEpoch());
```

### 3. 接收侧（`updateNodeFromPingMessage` / `updateNodeFromPongMessage` / `updateNodeFromMeetMessage`）

在同步发送方槽位之后，新增"发送方角色同步"步骤，**复用 `processGossipNodes` 中已验证的防陈旧策略**：
- 仅当 `senderConfigEpoch > localEpoch` 时切换发送方角色（MASTER↔SLAVE），防止旧消息回退已提升的 master。
- 仅当 `senderConfigEpoch >= localEpoch && senderMasterNodeId != null && node.isSlave()` 时同步 masterNodeId。

将该逻辑抽取为私有方法 `syncSenderRole(ClusterNode sender, Set<ClusterNodeState> flags, String masterNodeId, long configEpoch)`，供 PING/PONG/MEET 三处复用。

### 4. 不改动的部分

- `CLUSTER REPLICATE` 本身仍只做本地状态变更（不发广播），符合 Redis 语义——角色传播交给 Gossip。
- `selectGossipNodes` 排除自身的策略不变（节点自身角色现在通过消息头传播，无需 gossip 自己）。
- `completeHandshake` 默认 MASTER 的逻辑不变（仅对无角色的新握手节点生效，新逻辑会在收到该节点后续 PING 时按其真实角色纠正）。
- 故障转移选举/槽位重分配逻辑（`FailoverManager.performFailover` / `onFailoverResult`）无需改动，已正确。

## 风险与权衡

- **协议兼容**：集群总线消息新增字段，旧版本节点解析会出错。当前部署为同版本，按不兼容处理；未来如需混部，可在消息头加版本号或长度前缀。本修复不引入版本协商，仅确保同版本正确。
- **防陈旧回退**：严格沿用 `processGossipNodes` 的 epoch 裁决（严格大于才切角色），与既有故障转移保护一致，不会撤销已完成的 failover。
- **性能**：消息头增加约 50 字节（flags + 40 字符 masterNodeId + 8 字节 epoch），对 gossip 心跳开销可忽略。

## 验证策略

新增集成测试 `ClusterReplicateGossipTest`，使用 `EmbeddedCluster` 真实总线：
1. 建 2 主 + 各 1 从（4 节点），分配槽位。
2. 对从节点发 `CLUSTER REPLICATE`。
3. 等待 gossip 传播，断言：主节点视角中从节点 `isSlave()` 且 `masterNodeId` 正确；`CLUSTER NODES` 从节点行显示 `slave` + 其主节点 ID。
4. 标记主节点 FAIL，等待选举，断言从节点提升为 MASTER 并接管槽位，`isClusterOk()` 恢复 true。
