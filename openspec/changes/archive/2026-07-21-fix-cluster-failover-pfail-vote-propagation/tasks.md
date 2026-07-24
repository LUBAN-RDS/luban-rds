# Tasks: 修复集群自动故障转移 PFAIL 投票不传播

## 1. 完善 FailureDetector.processGossipPfailVote 方法

- [x] 修改 `FailureDetector.processGossipPfailVote(GossipNodeInfo nodeInfo)` 签名为 `processGossipPfailVote(GossipNodeInfo nodeInfo, String voterNodeId)`
- [x] 当 `nodeInfo.isPfail()` 为真且 `voterNodeId` 非空时，调用 `recordPfailVote(nodeInfo.getNodeId(), voterNodeId)`
- [x] 跳过自投票（`voterNodeId.equals(targetNodeId)`）
- [x] 补充日志输出 voter 信息便于排查

## 2. 在 GossipProtocol.processGossipNodes 中传递发送方投票

- [x] 修改 `processGossipNodes(List<GossipNodeInfo>)` 签名为 `processGossipNodes(List<GossipNodeInfo>, String senderNodeId)`
- [x] 在节点信息处理循环中调用 `failureDetector.processGossipPfailVote(nodeInfo, senderNodeId)`
- [x] 更新 `handlePing` 调用点，传入 `ping.getSenderNodeId()`
- [x] 更新 `handlePong` 调用点，传入 `pong.getSenderNodeId()`
- [x] 检查并更新 MEET 消息路径中所有 `processGossipNodes` 调用点

## 3. 补充单元测试

- [x] 在 `FailureDetectorTest` 中新增测试：`processGossipPfailVote_WhenPfail_ShouldRecordVote`
- [x] 新增测试：`processGossipPfailVote_WhenNotPfail_ShouldNotRecordVote`
- [x] 新增测试：`processGossipPfailVote_WhenSelfVote_ShouldSkip`
- [x] 新增测试：`isMajorityAgreed_WhenMultipleVoters_ShouldReturnTrue`

## 4. 构建与回归验证

- [x] 运行 `mvn clean install -pl luban-rds-cluster -am`（编译通过）
- [x] 确认 `FailureDetectorTest`、`FailoverManagerTest`、`ClusterFailoverTest` 全部通过（337 测试通过 / 0 失败 / 3 跳过）
- [x] 提交代码，commit message: `fix: 修复集群 PFAIL 投票通过 Gossip 不传播导致自动故障转移失效`
