## 修复任务清单

- [x] **Task 1**: 修复 `GossipProtocol.processGossipNodes()` — MASTER→SLAVE 角色切换时设置 `masterNodeId`
  - 文件：`luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipProtocol.java`
  - 位置：约 L1064-1067，在 SLAVE 检测分支内增加 `node.setMasterNodeId(nodeInfo.getMasterNodeId())`

- [x] **Task 2**: 修复 `GossipProtocol.syncSenderRole()` — 同上
  - 文件：同上
  - 位置：约 L1212-1215，在 SLAVE 检测分支内增加 `sender.setMasterNodeId(masterNodeId)`

- [x] **Task 3**: 修复 `GossipNodeInfo.decode()` — 当标志为 0 时显式清除 `masterNodeId`
  - 文件：`luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/GossipNodeInfo.java`
  - 位置：约 L503-520，在 `hasMasterId == 0` 的情况下增加 `this.masterNodeId = null`

- [x] **Task 4**: 运行 Gossip 相关测试，确认修复正确且无回归
  - 测试类：`GossipProtocolTest`、`GossipNodeInfoTest`、`ClusterReplicateGossipTest` 等

- [x] **Task 5**: 运行完整项目构建，确认全部测试通过
  - 命令：`mvn test -pl luban-rds-cluster`
