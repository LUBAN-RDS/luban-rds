# Comet Design Handoff

- Change: fix-cluster-gossip-propagation
- Phase: design
- Mode: compact
- Context hash: 96f547ddc74090c976f248266f8a18f234207be65959c97bb165520980fea2ee

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/fix-cluster-gossip-propagation/proposal.md

- Source: openspec/changes/fix-cluster-gossip-propagation/proposal.md
- Lines: 1-34
- SHA256: f1733df3c15b85050e316b9b783a33e958f539895847b701bbb48d4cdbeb0947

```md
## Why

`redis-cli --cluster create` 在执行 `CLUSTER MEET` 后卡在 `Waiting for the cluster to join`。经定位存在三个相互叠加的根因：

1. **Gossip 节点发现不传播**：`processGossipNodes` 发现新节点后仅以 `HANDSHAKE` 状态加入本地配置，不主动建连/发 MEET；`GossipTask.sendHeartbeats` 又跳过 `HANDSHAKE` 节点。结果是 gossip 拓扑无法收敛，每个从节点只认识 MEET 来源节点，redis-cli 永远等不到所有节点互相可见。
2. **`CLUSTER REPLICATE` 无法被触发**：因根因 1，redis-cli 卡在 join 阶段，永远到不了 REPLICATE 步骤，所有节点始终保持 master。
3. **槽位所有权不随 Gossip 传播**：`GossipNodeInfo` / PING / PONG / MEET 消息均不携带槽位信息，节点之间无法同步"谁拥有哪些 slot"。即便拓扑收敛，每个节点仍只知道自己的槽位，`cluster_slots_assigned` 远小于 16384，`cluster_state` 始终为 `fail`，redis-cli 永远等不到 `cluster_state:ok`。

## What Changes

- **Gossip 节点发现推动握手**：`GossipProtocol.processGossipNodes` 在发现新节点（真实 nodeId 已知、HANDSHAKE 状态）后，主动调用 `ClusterBusClient.connect(realNodeId, ip, port)` 并发送 `MEET`；新增 `initiateMeetForDiscoveredNode(ClusterNode)` 公共方法，幂等（已连接则跳过）。
- **心跳覆盖 HANDSHAKE 节点**：`GossipTask.sendHeartbeats` 不再排除 HANDSHAKE 节点，改为对 HANDSHAKE 节点调用 `initiateMeetForDiscoveredNode` 发送 MEET，对正常节点维持随机 PING。
- **Gossip 消息携带槽位所有权**：在 `GossipNodeInfo` 增加 `slots` 字段（位图压缩的槽位区间），`convertToGossipNodeInfo` 填充节点拥有的槽位；`processGossipNodes` 在收到 gossip 节点信息时，将槽位归属同步到 `ClusterConfig`（仅在配置纪元更大或本地无归属时覆盖，避免冲突）。
- **`CLUSTER REPLICATE` 可达**：上述修复使 redis-cli 能进入 REPLICATE 阶段；`clusterReplicate` 既有实现正确，无需改动，但需验证从节点状态经 gossip 传播后，`CLUSTER NODES` 显示正确的 master/slave 关系。

## Capabilities

### New Capabilities
<!-- 无新增 capability -->

### Modified Capabilities
- `cluster-gossip`: 节点发现后必须主动 MEET 以推动握手；Gossip 消息必须携带并同步槽位所有权，使各节点对全局槽位分配达成一致。
- `cluster-commands`: `CLUSTER REPLICATE` 后从节点状态需经 Gossip 传播，`CLUSTER NODES`/`CLUSTER INFO` 应反映正确的 master/slave 与 `cluster_state:ok`。

## Impact

- **受影响代码**：
  - `luban-rds-cluster/.../gossip/GossipProtocol.java`（`processGossipNodes`、`convertToGossipNodeInfo`、新增 `initiateMeetForDiscoveredNode`）
  - `luban-rds-cluster/.../gossip/GossipTask.java`（`sendHeartbeats` 区分 HANDSHAKE / 非 HANDSHAKE）
  - `luban-rds-cluster/.../gossip/GossipNodeInfo.java`（新增 `slots` 字段及编解码）
  - `luban-rds-cluster/.../config/ClusterConfig.java`（提供按 nodeId 批量同步槽位归属的能力，含纪元比较）
  - 测试：`GossipProtocolTest`、`GossipTaskTest`、`GossipNodeInfoTest`（新增）
- **协议兼容性**：GossipNodeInfo 编码格式变更（新增 slots 字段），新老节点混部不兼容，需整体升级。
- **运行时影响**：6 节点 `--cluster create` 可在数十秒内完成 `[OK] All 16384 slots covered`，`cluster_state:ok`，3 主 3 从拓扑正确。
```

## openspec/changes/fix-cluster-gossip-propagation/design.md

- Source: openspec/changes/fix-cluster-gossip-propagation/design.md
- Lines: 1-44
- SHA256: 07497bf129ddcd29e150ccf89251bfe431c93ccb579058ec0d1c0f3ae7ae720d

```md
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
```

## openspec/changes/fix-cluster-gossip-propagation/tasks.md

- Source: openspec/changes/fix-cluster-gossip-propagation/tasks.md
- Lines: 1-21
- SHA256: 981f2384287de154de5c11de7869b3336c362d97e292d72687380a935270b8c2

```md
# Tasks — fix-cluster-gossip-propagation

## 已完成（根因 1、2）

- [x] 1. `GossipProtocol.processGossipNodes`：发现新节点后调用 `initiateMeetForDiscoveredNode(node)` 主动建连并发送 MEET；幂等保护。
- [x] 2. `GossipTask.sendHeartbeats`：对 HANDSHAKE 节点调用 `initiateMeetForDiscoveredNode`，对正常节点维持随机 PING。
- [x] 3. 单元测试：`GossipProtocolTest`（发现新节点触发 connect+MEET、已连接不重复）、`GossipTaskTest`（HANDSHAKE 节点被 MEET 推动、已连接不重复）。
- [x] 4. `luban-rds-cluster` 模块测试通过（296 通过）。

## 待完成（根因 3：槽位所有权传播）

- [ ] 5. `GossipNodeInfo` 新增 `BitSet slots` 字段及 getter/setter；同步更新 `encode/decode`；`toString` 增加 cardinality 摘要。
- [ ] 6. `PingMessage`/`PongMessage`/`MeetMessage` 新增 `BitSet senderSlots` 字段及 getter/setter。
- [ ] 7. `ClusterConfig.syncSlotsFromNode(nodeId, slots, configEpoch)`：按纪元比较策略批量同步槽位归属。
- [ ] 8. `GossipProtocol`：
  - 发送侧：`sendPing`/`handlePing`(PONG)/`sendMeet` 设置 `senderSlots = myNode.getSlots()`；`convertToGossipNodeInfo` 设置 `info.setSlots(node.getSlots())`。
  - 接收侧：`updateNodeFromPingMessage`/`updateNodeFromPongMessage`/`updateNodeFromMeetMessage` 调用 `clusterConfig.syncSlotsFromNode(senderNodeId, senderSlots, senderConfigEpoch)`；`processGossipNodes` 对 gossip 节点调用 `syncSlotsFromNode`。
- [ ] 9. `ClusterCommandHandler.clusterReplicate`：从节点化时 `myNode.clearSlots()` 并清除 `slotAssignment` 中本 nodeId 的所有权。
- [ ] 10. 单元测试：`GossipNodeInfoTest`（slots 序列化/编解码）、`GossipProtocolTest`（senderSlots 与 gossip slots 同步）、`ClusterConfigTest`（syncSlotsFromNode 纪元比较）、`ClusterCommandHandlerTest`（REPLICATE 清空 slots）。
- [ ] 11. 构建 `luban-rds-cluster` 模块测试通过。
- [ ] 12. 本地 6 节点集成验证：`redis-cli --cluster create --cluster-replicas 1` 完成 `[OK] All 16384 slots covered`；`CLUSTER INFO` 显示 `cluster_state:ok`、`cluster_slots_assigned:16384`；`CLUSTER NODES` 显示 3 主 3 从。
```

## openspec/changes/fix-cluster-gossip-propagation/specs/cluster-commands/spec.md

- Source: openspec/changes/fix-cluster-gossip-propagation/specs/cluster-commands/spec.md
- Lines: 1-19
- SHA256: d7c47df6751818571db6ea4122bf6a21abc1a01ae9a87434aabe8818a67c5a24

```md
# cluster-commands

## MODIFIED Requirements

### Requirement: CLUSTER REPLICATE 清空从节点槽位

当节点通过 `CLUSTER REPLICATE` 转为从节点时，必须清空自身持有的槽位所有权，避免从节点仍被识别为 slot owner，并通过 Gossip 传播后造成槽位归属错误。

#### Scenario: REPLICATE 后从节点不再持有槽位
- **WHEN** 节点执行 `CLUSTER REPLICATE <masterNodeId>` 成功
- **THEN** 该节点的 `slots` BitSet 应被清空
- **AND** `ClusterConfig.slotAssignment` 中所有原本归属该 nodeId 的 slot 应被置为 null（无 owner）
- **AND** 后续该节点发出的 PING/PONG/MEET 的 senderSlots 应为空 BitSet

#### Scenario: CLUSTER NODES 反映正确主从关系
- **WHEN** 集群拓扑与槽位已通过 Gossip 收敛
- **THEN** `CLUSTER NODES` 应正确显示每个节点的 master/slave 标志与 masterNodeId
- **AND** 仅 master 节点行末尾显示其负责的 slot 区间
- **AND** `CLUSTER INFO` 的 `cluster_state` 为 `ok`，`cluster_slots_assigned` 为 16384
```

## openspec/changes/fix-cluster-gossip-propagation/specs/cluster-gossip/spec.md

- Source: openspec/changes/fix-cluster-gossip-propagation/specs/cluster-gossip/spec.md
- Lines: 1-39
- SHA256: 31f2ab87f352b9eef1a50eb011342958eaaffd49e3e3ee7add16e30665def806

```md
# cluster-gossip

## MODIFIED Requirements

### Requirement: Gossip 节点发现推动握手完成

当节点通过 Gossip 携带信息（PING/PONG/MEET 的 gossip section）发现一个本地未知的节点时，必须主动向该节点发起总线连接并发送 MEET 消息，推动其完成握手；不应仅以 HANDSHAKE 状态加入本地配置后不再处理。

#### Scenario: 通过 Gossip 发现新节点后发起 MEET
- **WHEN** 节点 A 收到节点 B 的 PONG，其 gossip section 包含节点 C 的信息（nodeId/ip/port/busPort），且 A 本地不存在 nodeId 为 C 的节点
- **THEN** A 应将 C 以 HANDSHAKE 状态加入本地配置，并向 C 的地址发起总线连接、发送 MEET 消息
- **AND** 若 A 已与 C 建立连接，则不再重复发起

#### Scenario: 心跳覆盖 HANDSHAKE 节点
- **WHEN** GossipTask 周期性发送心跳，本地存在处于 HANDSHAKE 状态的节点
- **THEN** 应对该节点发送 MEET（而非 PING）以推动握手完成
- **AND** 对非 HANDSHAKE 节点维持现有随机 PING 心跳逻辑

### Requirement: Gossip 消息携带并同步槽位所有权

Gossip 消息必须携带节点拥有的槽位信息，使各节点对全局槽位分配达成一致，否则 `cluster_state` 无法达到 `ok`。

#### Scenario: PING/PONG/MEET 携带发送方槽位
- **WHEN** 节点发送 PING/PONG/MEET 消息
- **THEN** 消息应包含发送方（myNode）拥有的 slots 集合（BitSet）

#### Scenario: Gossip section 携带第三方节点槽位
- **WHEN** 节点构造 gossip section 中的 GossipNodeInfo
- **THEN** 每个 GossipNodeInfo 应携带对应节点拥有的 slots 集合

#### Scenario: 接收方同步槽位归属
- **WHEN** 节点收到 PING/PONG/MEET，其消息头 senderSlots 非空
- **THEN** 接收方应按"本地该 slot 无 owner 直接设；异 owner 且发送方配置纪元严格更大才覆盖；相等不覆盖"策略，将 senderSlots 中的每个 slot 归属设为发送方
- **AND** 当 gossip section 中某节点的 slots 非空时，按同样策略同步该节点槽位

#### Scenario: 槽位同步后集群状态收敛
- **WHEN** 集群中所有 master 的槽位已通过 Gossip 在各节点间同步
- **THEN** 每个节点的 `cluster_slots_assigned` 应为 16384
- **AND** `cluster_state` 应为 `ok`（在多数 master 可用的前提下）
```

