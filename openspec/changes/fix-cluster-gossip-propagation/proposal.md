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
