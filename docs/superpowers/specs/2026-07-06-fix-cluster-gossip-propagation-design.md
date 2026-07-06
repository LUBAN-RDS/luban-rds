---
comet_change: fix-cluster-gossip-propagation
role: technical-design
canonical_spec: openspec
---

# Design Doc — fix-cluster-gossip-propagation

> 本文档为 Comet change `fix-cluster-gossip-propagation` 的技术设计。上游事实源为 `openspec/changes/fix-cluster-gossip-propagation/` 下的 proposal/design/tasks 与 delta spec。

## 1. 背景与根因

`redis-cli --cluster create` 卡在 `Waiting for the cluster to join`，经定位三个叠加根因：

| # | 根因 | 现象 | 状态 |
|---|------|------|------|
| 1 | `GossipProtocol.processGossipNodes` 发现新节点后仅以 `HANDSHAKE` 加入配置，不主动建连/发 MEET | gossip 拓扑不收敛，从节点只认识 MEET 来源 | 已修复 |
| 2 | `GossipTask.sendHeartbeats` 跳过 `HANDSHAKE` 节点 | HANDSHAKE 节点永远无法被推动完成握手 | 已修复 |
| 3 | Gossip 消息不携带槽位所有权 | 各节点只知道自己的 slot，`cluster_slots_assigned` 远小于 16384，`cluster_state:fail` | 本设计重点 |

根因 1、2 的修复已合入并附单元测试（见 `GossipProtocol.initiateMeetForDiscoveredNode`、`GossipTask.sendHeartbeats`）。本设计聚焦根因 3。

## 2. 关键代码现状

- **总线序列化**：`ClusterBusClient`/`ClusterBusServer` 使用 Netty `ObjectEncoder`/`ObjectDecoder`（Java Serializable）。`ClusterBusCodec` 与 `GossipMessage.encode()/decode()`、`GossipNodeInfo.encode()/decode()` 均为 dead code，未被总线引用。**因此新增字段只需声明为可序列化字段即可，无需手写编解码。**
- **槽位存储**：
  - `ClusterConfig.slotAssignment: String[16384]`（slot -> ownerId）+ `assignedSlotsBitSet` + `assignedSlotCount`。
  - `ClusterNode.slots: BitSet(16384)`（该节点拥有的 slot 集合）。
  - `ClusterConfig.setSlotOwner(slot, nodeId)` 内部会同步更新对应 `ClusterNode.addSlot/removeSlot`。
- **Gossip 消息结构**：`PingMessage`/`PongMessage`/`MeetMessage` 均含 `List<GossipNodeInfo> gossipNodes`。`GossipNodeInfo` 当前含 `nodeId/ip/port/busPort/configEpoch/flags`，**不含 slots**。
- **`selectGossipNodes`** 排除 `myself`，所以本节点信息不会出现在 gossip section 中 → 本节点的 slots 无法通过 gossip section 传播给对端。

## 3. 修复方案

### 3.1 GossipNodeInfo 新增 slots 字段

```java
// GossipNodeInfo.java
private BitSet slots; // 该节点拥有的槽位集合（可为 null 表示未知/不携带）
public BitSet getSlots() { return slots; }
public void setSlots(BitSet slots) { this.slots = slots; }
```

- 同时更新 `encode()/decode()` 以保持一致性（即使当前未被总线使用），格式：在末尾追加 `int slotsLengthBytes` + `byte[] slotsBits`（BitSet.toByteArray）。
- `toString()` 增加 slots 摘要（cardinality）便于调试。

### 3.2 Ping/Pong/Meet 消息新增 senderSlots 字段

由于 `selectGossipNodes` 排除 myself，本节点 slots 无法经 gossip section 传播。需在消息头显式携带发送方自己的 slots：

```java
// PingMessage / PongMessage / MeetMessage
private BitSet senderSlots; // 发送方（myNode）拥有的槽位
public BitSet getSenderSlots() { return senderSlots; }
public void setSenderSlots(BitSet senderSlots) { this.senderSlots = senderSlots; }
```

- Java Serializable 自动处理，无需改 encode/decode（dead code 保持或同步更新均可）。

### 3.3 GossipProtocol 填充与同步

#### 发送侧
- `sendPing(node)`：构造 `PingMessage` 后，`ping.setSenderSlots(myNode.getSlots())`。
- `handlePing(ping)` 构造 PONG 时：`pong.setSenderSlots(myNode.getSlots())`。
- `sendMeet(ip, port)`：`meet.setSenderSlots(myNode.getSlots())`。
- `convertToGossipNodeInfo(node)`：`info.setSlots(node.getSlots())`，让第三方节点 slots 随 gossip section 扩散。

#### 接收侧
- `updateNodeFromPingMessage` / `updateNodeFromPongMessage` / `updateNodeFromMeetMessage`：在完成握手后，调用新方法 `syncSenderSlots(senderNodeId, senderSlots, senderConfigEpoch)` 同步发送方槽位。
- `processGossipNodes`：对每个 gossip 节点，若 `nodeInfo.getSlots() != null`，调用 `clusterConfig.syncSlotsFromNode(nodeId, nodeInfo.getSlots(), nodeInfo.getConfigEpoch())` 同步第三方节点槽位。

### 3.4 ClusterConfig 新增 syncSlotsFromNode

```java
/**
 * 基于配置纪元比较，批量同步某节点的槽位归属。
 * 仅当本地 slot 无 owner，或本地 owner 的 configEpoch <= 提供的 configEpoch 时覆盖。
 *
 * @param nodeId       节点ID
 * @param slots        该节点拥有的槽位集合
 * @param configEpoch  该节点的配置纪元（用于冲突裁决）
 */
public void syncSlotsFromNode(String nodeId, BitSet slots, long configEpoch) {
    if (slots == null) return;
    ClusterNode node = getNode(nodeId);
    if (node == null) return;
    for (int s = slots.nextSetBit(0); s >= 0; s = slots.nextSetBit(s + 1)) {
        String curOwner = slotAssignment[s];
        if (curOwner == null) {
            setSlotOwner(s, nodeId);
        } else if (curOwner.equals(nodeId)) {
            // 已归属该节点，确保 ClusterNode.slots 一致
            node.addSlot(s);
        } else {
            ClusterNode curOwnerNode = getNode(curOwner);
            long curEpoch = curOwnerNode != null ? curOwnerNode.getConfigEpoch() : 0;
            // 仅当提供方纪元严格更大时抢占；相等时不抢占避免抖动
            if (configEpoch > curEpoch) {
                setSlotOwner(s, nodeId);
            }
        }
        if (s == Integer.MAX_VALUE) break;
    }
}
```

> 说明：`setSlotOwner` 内部已处理旧 owner 的 `removeSlot` 与新 owner 的 `addSlot`，并维护 `assignedSlotCount`。`syncSlotsFromNode` 只负责"是否覆盖"的决策。

### 3.5 CLUSTER REPLICATE 后的从节点 slots

`clusterReplicate` 将当前节点改为 slave 时未清空 slots。从节点不应持有 slots。需在 `clusterReplicate` 中：
- `myNode.clearSlots()`；
- 调用 `clusterConfig` 清除本节点作为 owner 的所有 slot（遍历 slotAssignment，把 `== myNodeId` 的置 null）。

这样从节点的 `senderSlots` 为空 BitSet，gossip 传播后对端不会再把 slot 归属给从节点。

### 3.6 纪元与一致性
- `CLUSTER ADDSLOTS` 与 `CLUSTER REPLICATE` 已调用 `clusterConfig.incrementEpoch()` + `setConfigEpoch`。
- 槽位同步采用"纪元更大者赢"策略，避免循环抢占。
- `cluster_state` 由 `GossipTask.updateClusterState` 计算；当 `cluster_slots_assigned == 16384` 且多数 master 可用时变为 `ok`。

## 4. 数据流

```
Node A (master, slots 0-5460)  --PING(senderSlots=A.slots, gossip=[B,C,...])-->  Node B
                                                                       |
Node B 收到后:                                                            |
  1. updateNodeFromPingMessage:                                          |
     - completeHandshake(A)                                              |
     - syncSenderSlots(A, A.slots, A.configEpoch)  --> ClusterConfig     |
       把 slot 0-5460 的 owner 设为 A                                    |
  2. processGossipNodes(gossip):                                         |
     - 对 B、C 等 gossip 节点，syncSlotsFromNode 同步其 slots            |
  3. handlePing 返回 PONG(senderSlots=B.slots, gossip=[...])             |
Node A 收到 PONG: 同样同步 B 的 slots                                    |
最终：所有节点对 16384 slot 的归属达成一致，cluster_state:ok              |
```

## 5. 测试策略

| 层级 | 测试类 | 覆盖点 |
|------|--------|--------|
| 单元 | `GossipNodeInfoTest`（新增） | slots 字段 Java 序列化往返；encode/decode 一致性 |
| 单元 | `GossipProtocolTest` | PONG 携带 senderSlots 后，本地 `clusterConfig.getSlotOwner` 正确同步；gossip section 中第三方节点 slots 同步；从节点 slots 不被同步 |
| 单元 | `ClusterConfigTest` | `syncSlotsFromNode` 纪元比较：无 owner 直接设、同 owner 保持、异 owner 且纪元更大才覆盖、纪元相等不覆盖 |
| 单元 | `ClusterCommandHandlerTest` | `CLUSTER REPLICATE` 后 myNode.slots 被清空、slotAssignment 中该 nodeId 被清除 |
| 集成 | 6 节点本地 | `redis-cli --cluster create --cluster-replicas 1` 完成 `[OK] All 16384 slots covered`；`CLUSTER INFO` 显示 `cluster_state:ok`、`cluster_slots_assigned:16384`；`CLUSTER NODES` 显示 3 主 3 从 |

## 6. 风险与缓解

- **消息体积**：每节点 `BitSet(16384)` ≈ 2KB。6 节点 gossip section ≈ +12KB/消息，心跳 1s/次，可接受。Java Serializable 还会带类描述开销，但同 JVM 同类只编码一次。
- **槽位抢占抖动**：用"纪元严格更大才覆盖"避免相等纪元下的反复抢占。`ADDSLOTS`/`REPLICATE` 都会 `incrementEpoch`，保证新配置纪元更大。
- **协议兼容性**：Java Serializable 字段新增对老版本不兼容（ serialVersionUID 变化或字段缺失）。需整体升级所有节点，不支持灰度。本 change 为修复性发布，可接受。
- **dead code 一致性**：`GossipNodeInfo.encode/decode` 同步更新 slots，避免未来误用导致不一致。

## 7. 非目标
- 不实现 Redis 的 slot 迁移（MIGRATING/IMPORTING 状态）传播。
- 不重构总线序列化（保留 ObjectEncoder/Decoder，不切换到 ClusterBusCodec）。
- 不改变 `cluster-require-full-coverage` 等配置语义。
