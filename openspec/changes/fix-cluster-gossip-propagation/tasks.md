# Tasks — fix-cluster-gossip-propagation

## 已完成（根因 1、2）

- [x] 1. `GossipProtocol.processGossipNodes`：发现新节点后调用 `initiateMeetForDiscoveredNode(node)` 主动建连并发送 MEET；幂等保护。
- [x] 2. `GossipTask.sendHeartbeats`：对 HANDSHAKE 节点调用 `initiateMeetForDiscoveredNode`，对正常节点维持随机 PING。
- [x] 3. 单元测试：`GossipProtocolTest`（发现新节点触发 connect+MEET、已连接不重复）、`GossipTaskTest`（HANDSHAKE 节点被 MEET 推动、已连接不重复）。
- [x] 4. `luban-rds-cluster` 模块测试通过（296 通过）。

## 待完成（根因 3：槽位所有权传播）

- [x] 5. `GossipNodeInfo` 新增 `BitSet slots` 字段及 getter/setter；同步更新 `encode/decode`；`toString` 增加 cardinality 摘要。
- [x] 6. `PingMessage`/`PongMessage`/`MeetMessage` 新增 `BitSet senderSlots` 字段及 getter/setter。
- [x] 7. `ClusterConfig.syncSlotsFromNode(nodeId, slots, configEpoch)`：按纪元比较策略批量同步槽位归属。
- [x] 8. `GossipProtocol`：
  - 发送侧：`sendPing`/`handlePing`(PONG)/`sendMeet`/`initiateMeetForDiscoveredNode` 设置 `senderSlots = myNode.getSlots()`；`convertToGossipNodeInfo` 设置 `info.setSlots(node.getSlots())`。
  - 接收侧：`updateNodeFromPingMessage`/`updateNodeFromPongMessage` 调用 `clusterConfig.syncSlotsFromNode(senderNodeId, senderSlots, senderNode.getConfigEpoch())`；`updateNodeFromMeetMessage` 调用 `syncSlotsFromNode(senderNodeId, senderSlots, meet.getSenderConfigEpoch())`；`processGossipNodes` 对 gossip 节点调用 `syncSlotsFromNode`。
- [x] 9. `ClusterCommandHandler.clusterReplicate`：经核查，既有实现已在 `myNode.getSlotCount() > 0` 时拒绝 REPLICATE（从节点候选节点不会持有 slots），功能等价于"清空从节点 slots"。无需额外改动。
- [x] 10. 单元测试：`GossipNodeInfoTest`（slots 序列化/编解码）、`GossipProtocolTest`（senderSlots 与 gossip slots 同步）、`ClusterConfigTest`（syncSlotsFromNode 纪元比较）。
- [x] 11. 构建 `luban-rds-cluster` 模块测试通过（306 通过，0 失败）。
- [x] 12. 本地 6 节点集成验证：`redis-cli --cluster create --cluster-replicas 1` 完成 `[OK] All 16384 slots covered`；`CLUSTER INFO` 显示 `cluster_state:ok`、`cluster_slots_assigned:16384`；`CLUSTER NODES` 显示 3 主（各自槽位区间）+ 3 从（myself,slave 视角正确）。

> 验证附注：redis-cli 最终的 Cluster Check 正确识别 3 主 3 从关系；`cluster_state:ok` 与 `cluster_slots_assigned:16384` 达成。从节点自身视角（`CLUSTER NODES` 的 `myself,slave`）正确。跨节点视角下从节点的 master/slave 标志传播、以及非 owner 节点的 MOVED 重定向（当前返回 `CLUSTERDOWN Hash slot not served`）属另一独立问题，不在本 change 范围内，后续另开 change 处理。
