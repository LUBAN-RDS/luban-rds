## Why

集群模式主节点故障转移后，原主节点重启上线时未降级为从节点，导致双主冲突（split-brain）。

实测现象：3 主 3 从集群中，9737（master，持有 slots 5461-10922）宕机后，其从节点 9740 经选举提升为新主（epoch=9）。9737 重启后，`cluster nodes` 显示它仍以 `master` 身份上线（无 slots，但角色错误），且集群 `currentEpoch` 未对齐——重启节点停留在 epoch=4，而集群已演进到 epoch=9。

根因（5 个耦合缺口，均已通过日志和源码确认）：

1. **`GossipProtocol.processGossipNodes` 显式跳过 MYSELF**（`GossipProtocol.java:1030-1031`）：重启节点收到 PONG 时，gossip section 即便携带了 MYSELF 的正确降级视图（更高 configEpoch + SLAVE 角色 + masterNodeId 指向新主），也被 `continue` 丢弃，MYSELF 永不通过 gossip 自降级。
2. **`syncSenderRole` 只改对端记录**（`GossipProtocol.java:1244-1269`）：epoch 仲裁逻辑只作用于 `sender`（远端节点），从不作用于 MYSELF 自身。
3. **`FailoverResultMessage` 广播仅一次**（`FailoverManager.performFailoverAndBroadcast`）：故障转移时胜选者广播一次，原主此时已死、错过广播；重启后没有任何节点重播，`onFailoverResult` 的降级路径（`FailoverManager.java:510-523`）对重启节点永不触发。
4. **`NettyRedisServer.restoreClusterFromConfig` 盲信本地文件**（`NettyRedisServer.java:478`）：从 `nodes.conf` 恢复 MYSELF 为 MASTER、恢复旧 slots、恢复过时的低 configEpoch，无任何与集群对齐的步骤。
5. **PING/PONG 不携带 `currentEpoch`**：只在 MEET 时通过 `setEpochIfGreater` 同步（`GossipProtocol.java:990`），PING/PONG 仅携带 `senderConfigEpoch`。重启节点连集群级 currentEpoch 都无法通过心跳同步，更难做 epoch 仲裁。

## What Changes

- **gossip 接收侧自降级**：改造 `processGossipNodes`，当 gossip section 携带 MYSELF 的视图且其 `configEpoch` 严格大于本地 MYSELF 的 `configEpoch`、角色为 SLAVE 时，让 MYSELF 采纳该视图——清空自身 slots、切换 MASTER→SLAVE、设置 `masterNodeId` 指向新主，并通过 `ReplicationLifecycleListener.demoteToSlave` 切换复制方向。严格 epoch 门控（>`localEpochBaseline`）防止陈旧 gossip 回退已提升的 master。
- **PING/PONG 携带 `currentEpoch`**：扩展 `PingMessage`/`PongMessage` 协议（向后兼容追加字段），接收侧 `updateNodeFromPingMessage`/`updateNodeFromPongMessage` 调用 `clusterConfig.setEpochIfGreater`，使重启节点能通过心跳同步集群级 currentEpoch。
- **启动恢复对齐**：`restoreClusterFromConfig`/`initCurrentNode` 不再无条件以本地 MYSELF 角色为准恢复 slots；恢复后标记"待对齐"，由首个 PONG 的 epoch 仲裁纠正角色与 slots（与上一条改动配合）。保留本地文件作为节点 ID 与已知节点列表的来源。
- **回归测试**：覆盖 (a) 重启旧主通过 gossip 自降级；(b) currentEpoch 经 PING/PONG 同步；(c) 启动恢复后首个 PONG 触发对齐；(d) epoch 门控防止回退已提升 master。

## Capabilities

### New Capabilities

### Modified Capabilities
- `cluster-automatic-failover`: 故障转移后原主重启必须根据集群最新拓扑（更高 configEpoch）自降级为新主的 slave，并同步 currentEpoch；不得盲信本地 nodes.conf 维持 master 身份。

## Impact

- 影响 `luban-rds-cluster` 的 `GossipProtocol`、`PingMessage`/`PongMessage`（协议向后兼容扩展）、`ClusterConfig`（`setEpochIfGreater` 已有，复用）。
- 影响 `luban-rds-server` 的 `NettyRedisServer.restoreClusterFromConfig`/`initCurrentNode` 启动恢复路径。
- 复用 `ReplicationLifecycleListener.demoteToSlave` 切换复制方向（已有，不新增接口）。
- 不改变 Redis 协议或对外 API；PING/PONG 编码向后兼容（追加字段，旧节点解码时尾部截断安全）。
- 不引入新依赖；仅本地内存与集群总线消息变更。
