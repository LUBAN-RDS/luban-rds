## Why

`redis-cli --cluster create` 建立集群后，所有 slave 节点（9739/9740/9741）在 gossip 传播中始终被识别为 master，导致 Redisson 客户端只能看到 slot 0-5460（首个 master 的范围），访问其他 slot 时收到 `CLUSTERDOWN Hash slot not served`，应用启动失败。

根因有三处：

1. **`syncSenderRole` 纪元门控被破坏（主因）**：`updateNodeFromMeetMessage` / `updateNodeFromPingMessage` / `updateNodeFromPongMessage` 在调用 `syncSenderRole` 之前，已通过 `setConfigEpochIfGreater`（或先前消息）把本地节点的 `configEpoch` 提升到与消息携带的 `senderConfigEpoch` 相等。`syncSenderRole` 内部 `configEpoch > localEpoch` 判断因此永远为 false，MASTER->SLAVE 角色切换永不发生。上一个 hotfix（`fix-cluster-replicate-role-gossip`）引入了 `syncSenderRole`，但未注意到 MEET 处理路径中 `setConfigEpochIfGreater` 先于它执行，导致该方法对新加入的 slave 完全失效。`processGossipNodes` 中同步第三方节点角色存在同类缺陷：`setConfigEpochIfGreater` 先于角色判断执行，`gossipEpoch > localEpoch` 恒为 false。

2. **`CLUSTER ADDSLOTS` 未设置 `myNode.configEpoch`（次因）**：`clusterAddslots` 只调用 `clusterConfig.incrementEpoch()`，不设置 `myNode.setConfigEpoch(...)`。而 `clusterReplicate` 和故障转移路径都正确设置了 `configEpoch`。这导致 master 节点的 `configEpoch` 始终为 0，使基于纪元的槽位/角色冲突裁决不可靠。

3. **`CLUSTER SET-CONFIG-EPOCH` 命令未实现（次因）**：`redis-cli --cluster create` 会为每个节点发送 `CLUSTER SET-CONFIG-EPOCH <n>` 来建立初始纪元，但 `ClusterCommandHandler` 的 switch 没有该分支，返回 `Unknown subcommand`，使每个节点的初始 `configEpoch` 无法正确建立。

## What Changes

- 修正 `syncSenderRole` 的纪元比较语义：角色切换应基于"消息携带的发送方角色是否比本地视图更新"，而非严格大于已被提前更新的本地纪元。改为在调用 `syncSenderRole` 前捕获本地纪元快照传入，避免 `setConfigEpochIfGreater` 的副作用。
- 修正 `processGossipNodes` 中同步第三方节点角色的同类缺陷：在 `setConfigEpochIfGreater` 之前捕获本地纪元基线，角色切换基于基线判断。
- `CLUSTER ADDSLOTS` 执行后设置 `myNode.setConfigEpoch(clusterConfig.getCurrentEpoch())`，与 `clusterReplicate` / 故障转移路径保持一致。
- 实现 `CLUSTER SET-CONFIG-EPOCH <epoch>` 子命令，设置 `myNode.configEpoch` 与 `currentEpoch`（取较大值），对齐 Redis 行为。
- 补充针对性单元测试：验证 slave 角色能经 MEET/PING/PONG 及 Gossip section 正确同步；验证 ADDSLOTS 后 configEpoch 非零；验证 SET-CONFIG-EPOCH 生效。

## Capabilities

### New Capabilities

### Modified Capabilities
- `cluster-automatic-failover`: 集群拓扑（master/slave 角色）必须能通过 gossip 可靠收敛，slot 归属与角色状态在 `redis-cli --cluster create` 后对客户端可见。

## Impact

- 影响 `GossipProtocol`（`updateNodeFromMeetMessage` / `updateNodeFromPingMessage` / `updateNodeFromPongMessage` / `syncSenderRole`）与 `ClusterCommandHandler`（`clusterAddslots` + 新增 `SET-CONFIG-EPOCH`）。
- 不改变 gossip 二进制协议或 RESP 接口；仅修正角色同步语义与补齐缺失命令。
- 不引入新依赖；纯逻辑修复 + 测试。
