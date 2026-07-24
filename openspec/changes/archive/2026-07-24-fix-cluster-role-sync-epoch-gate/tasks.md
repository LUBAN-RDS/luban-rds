# Tasks: fix-cluster-role-sync-epoch-gate

## 1. 修正 `syncSenderRole` 纪元门控
- [x] 1.1 修改 `syncSenderRole` 签名，新增 `localEpochBaseline` 参数，角色切换与 masterNodeId 同步基于基线判断
- [x] 1.2 `updateNodeFromMeetMessage`：在 `setConfigEpochIfGreater` 之前捕获 `epochBaseline`，传入 `syncSenderRole`
- [x] 1.3 `updateNodeFromPingMessage`：捕获 `epochBaseline`，传入 `syncSenderRole`
- [x] 1.4 `updateNodeFromPongMessage`：捕获 `epochBaseline`，传入 `syncSenderRole`
- [x] 1.5 更新 `syncSenderRole` 的 Javadoc，说明基线语义

## 2. 修正 `processGossipNodes` 第三方节点角色同步同类缺陷
- [x] 2.1 在 `setConfigEpochIfGreater` 之前捕获 `epochBaseline`，角色切换 `gossipEpoch > localEpoch` 改为基于基线判断
- [x] 2.2 masterNodeId 同步的 `gossipEpoch >= localEpoch` 同样基于基线

## 3. `ADDSLOTS` 设置 `configEpoch`
- [x] 3.1 `clusterAddslots` 在 `incrementEpoch` 后调用 `myNode.setConfigEpoch(clusterConfig.getCurrentEpoch())`

## 4. 实现 `CLUSTER SET-CONFIG-EPOCH`
- [x] 4.1 在 `ClusterCommandHandler.handle` switch 新增 `SET-CONFIG-EPOCH` 分支
- [x] 4.2 实现 `clusterSetConfigEpoch`：校验参数，设置 `myNode.configEpoch` 与 `currentEpoch`（取较大值）

## 5. 测试
- [x] 5.1 `GossipRoleSyncTest`：验证 MEET 携带 SLAVE 角色时接收方切换对端为 SLAVE
- [x] 5.2 `GossipRoleSyncTest`：验证 PING/PONG 携带 SLAVE 角色时接收方切换对端为 SLAVE
- [x] 5.3 `GossipRoleSyncTest`：验证陈旧纪元（< 基线）不触发角色切换
- [x] 5.4 `GossipRoleSyncTest`：验证 Gossip section 中第三方节点 SLAVE 角色同步（processGossipNodes 路径）
- [x] 5.5 `GossipRoleSyncTest`：验证 MEET 携带 MASTER 角色时从 SLAVE 提升为 MASTER（故障转移）
- [x] 5.6 `ClusterCommandHandlerTest`：验证 ADDSLOTS 后 `myNode.getConfigEpoch() > 0`
- [x] 5.7 `ClusterCommandHandlerTest`：验证 SET-CONFIG-EPOCH 能设置纪元
- [x] 5.8 回归：`ClusterReplicateGossipTest`(2/2)、`GossipMessageSenderRoleCodecTest`(5/5)、`GossipProtocolTest`(13/13)、`ClusterFailoverTest`(16/16) 全部通过

## 6. 验证
- [x] 6.1 `mvn-java17.bat` 编译 cluster 模块通过
- [x] 6.2 运行 cluster 模块核心测试通过（GossipRoleSyncTest 7/7, ClusterCommandHandlerTest 42/42, 回归 91/91）
