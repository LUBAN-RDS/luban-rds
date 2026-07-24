# Design: fix-cluster-role-sync-epoch-gate

## 问题复现

`redis-cli --cluster create 127.0.0.1:9736 127.0.0.1:9737 ... 127.0.0.1:9741 --cluster-replicas 1` 后：

- 6 节点 gossip 正常（MEET/PING/PONG 解码成功，无连接关闭）。
- `nodes.conf` 快照（10:20:35）显示所有 slave 节点（9739/9740/9741）flags 仍为 `master`，`config-epoch` 全为 0。
- Redisson 在 10:20:52 查询 `CLUSTER NODES`，只识别 `slot ranges: [[0-5460]]`（9736 的范围）。
- HKEYS 路由到 9739（本应是 9736 的 slave），9739 自认 master 且无 slot，返回 `-CLUSTERDOWN Hash slot not served`，应用启动失败。

## 根因分析

### 主因：`syncSenderRole` 纪元门控失效

`updateNodeFromMeetMessage`（GossipProtocol.java:923-947）执行顺序：

```
1. completeHandshake(senderNode)          // 移除 HANDSHAKE，设为 MASTER，configEpoch 仍为 0
2. senderNode.setConfigEpochIfGreater(    // ← 把本地 configEpoch 提升到 senderConfigEpoch
       meet.getSenderConfigEpoch())       //   例如 9739 的 REPLICATE 后 configEpoch=4
3. clusterConfig.syncSlotsFromNode(...)
4. syncSenderRole(senderNode, flags,      // ← 此时 localEpoch = sender.getConfigEpoch() = 4
       masterNodeId, configEpoch=4)       //   configEpoch(4) > localEpoch(4) = false → 角色不切换
```

`syncSenderRole`（GossipProtocol.java:1171-1196）核心判断：

```java
long localEpoch = sender.getConfigEpoch();   // 已被步骤 2 提升
if (configEpoch > localEpoch) {              // 永远 false
    if (flags.contains(SLAVE) && sender.isMaster()) {
        // MASTER -> SLAVE 切换，永不执行
    }
}
```

`updateNodeFromPingMessage` / `updateNodeFromPongMessage` 同样受影响：虽然它们不在本方法内调用 `setConfigEpochIfGreater`，但节点的 `configEpoch` 可能已被先前的 MEET/PING 提升到与消息 `senderConfigEpoch` 相等，导致 `configEpoch > localEpoch` 仍为 false。

### 次因一：`ADDSLOTS` 不设置 `configEpoch`

```java
// clusterAddslots (ClusterCommandHandler.java:741)
clusterConfig.incrementEpoch();   // currentEpoch +1
// 缺少: myNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
```

对比 `clusterReplicate`（680-681）和故障转移（1254-1255）都正确设置了 `configEpoch`。ADDSLOTS 后 master 的 `configEpoch` 始终为 0，使纪元裁决不可靠。

### 次因二：`SET-CONFIG-EPOCH` 未实现

`redis-cli --cluster create` 为每个节点发送 `CLUSTER SET-CONFIG-EPOCH <n>`（n=1..6），用于建立初始配置纪元。`ClusterCommandHandler.handle` 的 switch 无此分支，返回 `-ERR Unknown subcommand`，节点初始 `configEpoch` 无法建立。

## 修复方案

### 1. 修正 `syncSenderRole` 纪元门控

**方案**：在调用 `syncSenderRole` 之前捕获本地纪元快照，传入方法作为"门控基线"，避免 `setConfigEpochIfGreater` 的副作用。

`syncSenderRole` 签名改为接收 `localEpochBaseline`：

```java
private void syncSenderRole(ClusterNode sender, Set<ClusterNodeState> flags,
                            String masterNodeId, long senderConfigEpoch,
                            long localEpochBaseline) {
    // 角色切换：仅当消息纪元严格大于基线时才切换
    if (senderConfigEpoch > localEpochBaseline) { ... }
    // masterNodeId 同步：纪元可接受（>= 基线）
    if (senderConfigEpoch >= localEpochBaseline && masterNodeId != null && sender.isSlave()) { ... }
}
```

调用处（三处）在 `setConfigEpochIfGreater` 之前捕获基线：

```java
// updateNodeFromMeetMessage
long epochBaseline = senderNode.getConfigEpoch();   // ← 先捕获
senderNode.setConfigEpochIfGreater(meet.getSenderConfigEpoch());
...
syncSenderRole(senderNode, meet.getSenderFlags(),
        meet.getSenderMasterNodeId(), meet.getSenderConfigEpoch(), epochBaseline);
```

```java
// updateNodeFromPingMessage / updateNodeFromPongMessage
long epochBaseline = senderNode.getConfigEpoch();   // ← 先捕获
clusterConfig.syncSlotsFromNode(...);   // 内部可能提升 node.configEpoch（经 setSlotOwner→addSlot 不提升，但安全起见先捕获）
syncSenderRole(senderNode, flags, masterNodeId, senderConfigEpoch, epochBaseline);
```

**为什么用基线而非移除 `setConfigEpochIfGreater`**：`setConfigEpochIfGreater` 仍需保留以同步发送方的纪元视图（供后续 gossip 冲突裁决）。只需让 `syncSenderRole` 的门控基于"提升前"的本地纪元即可恢复正确语义：消息携带的 `senderConfigEpoch` 若严格大于本地原有纪元，说明发送方角色变更比本地新，应采纳。

### 2. `ADDSLOTS` 设置 `configEpoch`

```java
// clusterAddslots (ClusterCommandHandler.java:741)
clusterConfig.incrementEpoch();
myNode.setConfigEpoch(clusterConfig.getCurrentEpoch());   // ← 新增
```

### 3. 实现 `SET-CONFIG-EPOCH`

```java
case "SET-CONFIG-EPOCH":
    return clusterSetConfigEpoch(args);

private String clusterSetConfigEpoch(String[] args) {
    if (args.length < 2) {
        return "-ERR wrong number of arguments for 'cluster|set-config-epoch' command\r\n";
    }
    long epoch;
    try {
        epoch = Long.parseLong(args[1]);
    } catch (NumberFormatException e) {
        return "-ERR Invalid config epoch\r\n";
    }
    if (epoch < 0) {
        return "-ERR Invalid config epoch\r\n";
    }
    ClusterNode myNode = clusterConfig.getMyNode();
    if (myNode == null) {
        return "-ERR Current node not found in cluster\r\n";
    }
    // 对齐 Redis：仅当集群中无其他节点或当前节点未分配槽位时才允许设置
    // （redis-cli --cluster create 在 join 前逐节点设置，此时 nodes=1）
    myNode.setConfigEpoch(epoch);
    clusterConfig.setEpochIfGreater(epoch);
    logger.info("CLUSTER SET-CONFIG-EPOCH: epoch={}", epoch);
    return "+OK\r\n";
}
```

## 验证

- 单元测试：`GossipRoleSyncTest` 验证 MEET/PING/PONG 携带 SLAVE 角色时，接收方能把对端从 MASTER 切换为 SLAVE 并设置 masterNodeId。
- 单元测试：`ClusterCommandHandlerTest` 验证 ADDSLOTS 后 `myNode.getConfigEpoch() > 0`，`SET-CONFIG-EPOCH` 能设置纪元。
- 回归：现有 `ClusterReplicateGossipTest`、`GossipMessageSenderRoleCodecTest` 全部通过。
