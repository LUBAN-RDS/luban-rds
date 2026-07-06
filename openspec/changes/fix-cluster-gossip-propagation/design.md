# Design — fix-cluster-gossip-propagation

> 完整技术设计见 `docs/superpowers/specs/2026-07-06-fix-cluster-gossip-propagation-design.md`。本文件为 OpenSpec 侧的精要设计。

## 根因总览

| # | 根因 | 现象 | 状态 |
|---|------|------|------|
| 1 | `processGossipNodes` 发现新节点后不主动建连/MEET | gossip 拓扑不收敛 | 已修复 |
| 2 | `GossipTask.sendHeartbeats` 跳过 HANDSHAKE 节点 | HANDSHAKE 节点无法完成握手 | 已修复 |
| 3 | Gossip 消息不携带槽位所有权 | cluster_slots_assigned < 16384，cluster_state:fail | 本设计 |

## 根因 1、2 的修复（已实现）

- `GossipProtocol.processGossipNodes`：发现新节点后调用新增公共方法 `initiateMeetForDiscoveredNode(node)`，以真实 nodeId `busClient.connect` 并发送 MEET；`isConnected` 幂等保护。
- `GossipTask.sendHeartbeats`：对 HANDSHAKE 节点调用 `initiateMeetForDiscoveredNode`，对正常节点维持随机 PING。

## 根因 3 的修复方案

### 关键约束
- 总线使用 Java `ObjectEncoder`/`ObjectDecoder`（Serializable），`GossipNodeInfo.encode/decode` 与 `ClusterBusCodec` 为 dead code。新增字段只需可序列化即可。
- `selectGossipNodes` 排除 myself，故本节点 slots 不会出现在 gossip section，需在消息头显式携带。

### 变更点
1. **`GossipNodeInfo`**：新增 `BitSet slots` 字段及 getter/setter；同步更新 `encode/decode` 保持一致性。`convertToGossipNodeInfo` 填充 `node.getSlots()`。
2. **`PingMessage`/`PongMessage`/`MeetMessage`**：新增 `BitSet senderSlots` 字段。发送时填 `myNode.getSlots()`。
3. **`GossipProtocol`**：
   - 发送侧：`sendPing`/`handlePing`(构造PONG)/`sendMeet` 设置 `senderSlots`。
   - 接收侧：`updateNodeFromPingMessage`/`updateNodeFromPongMessage`/`updateNodeFromMeetMessage` 调用 `clusterConfig.syncSlotsFromNode(senderNodeId, senderSlots, senderConfigEpoch)`。
   - `processGossipNodes`：对每个 gossip 节点，若 `nodeInfo.getSlots() != null`，调用 `syncSlotsFromNode` 同步第三方节点槽位。
4. **`ClusterConfig.syncSlotsFromNode(nodeId, slots, configEpoch)`**：按"本地无 owner 直接设；异 owner 且提供方纪元严格更大才覆盖；相等不覆盖"策略批量同步槽位归属。
5. **`ClusterCommandHandler.clusterReplicate`**：从节点化时 `myNode.clearSlots()` 并清除 `slotAssignment` 中本 nodeId 的所有权，避免从节点持有 slots。

### 纪元与一致性
- `ADDSLOTS`/`REPLICATE` 已 `incrementEpoch`，新配置纪元更大，抢占安全。
- `cluster_state` 在 `cluster_slots_assigned == 16384` 且多数 master 可用时变 ok。

### 测试
- `GossipNodeInfoTest`、`GossipProtocolTest`、`ClusterConfigTest`、`ClusterCommandHandlerTest` 新增用例。
- 集成：6 节点 `--cluster create` → `cluster_state:ok`、`cluster_slots_assigned:16384`、3 主 3 从。

### 风险
- 消息体积 +2KB/节点，可接受。
- Java Serializable 新增字段不兼容老版本，需整体升级。
