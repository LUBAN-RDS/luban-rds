---
comet_change: fix-cluster-restart-demote
role: technical-design
canonical_spec: openspec
---

# Design Doc: 集群故障转移后旧主重启自降级

## 1. 问题与根因（确认）

集群主节点故障转移后，原主重启未降级为新主的 slave，导致双主冲突。日志（`D:\tmp\luban-rds\rds-9737`）确认：9737 重启时 `从配置文件恢复集群状态: currentEpoch=4, configEpoch=0`，而集群已演进到 `currentEpoch=9`（新主 9740）。重启后 gossip 交互未触发降级，9737 仍以 master 上线。

5 个耦合缺口（源码确认）：

| # | 缺口 | 位置 |
|---|------|------|
| 1 | `processGossipNodes` 显式跳过 MYSELF | `GossipProtocol.java:1030-1031` |
| 2 | `syncSenderRole` 只改对端，从不降级 MYSELF | `GossipProtocol.java:1244-1269` |
| 3 | `FailoverResultMessage` 广播仅一次，重启节点错过 | `FailoverManager.performFailoverAndBroadcast` / `onFailoverResult:510-523` |
| 4 | `restoreClusterFromConfig` 盲信本地 nodes.conf | `NettyRedisServer.java:478` |
| 5 | PING/PONG 不携带 `currentEpoch`（仅 MEET 同步） | `PingMessage.java` / `GossipProtocol.java:990` |

## 2. 关键认知修正

**gossip section 可以携带接收者 MYSELF 的记录。** `selectGossipNodes`（`GossipProtocol.java:816`）只排除*发送方*的 MYSELF（`!node.isMyself()`）。因此当存活节点 A（已应用 FailoverResult，知道重启节点 R 现在是 SLAVE）向 R 发送 PONG 时，A 的 gossip section 可以包含 R 的记录（R 不是 A 的 MYSELF）。R 收到该 PONG 后，`processGossipNodes` 因 `nodeId == myNodeId` 而 `continue` 跳过--这正是缺口 1。

修正方向：移除该跳过，对 MYSELF 应用与第三方节点相同的 epoch 仲裁角色切换逻辑。

## 3. 实现方案

### 3.1 gossip 接收侧 MYSELF 自降级（核心）

改造 `GossipProtocol.processGossipNodes`（约 `:1025-1131`）：

```
for (GossipNodeInfo nodeInfo : gossipNodes) {
    String nodeId = nodeInfo.getNodeId();
    boolean isMyselfEntry = nodeId != null && nodeId.equals(clusterConfig.getMyNodeId());

    // 不再无条件 continue；MYSELF 走自降级分支
    ClusterNode node = isMyselfEntry ? clusterConfig.getMyNode() : clusterConfig.getNode(nodeId);

    if (!isMyselfEntry && node == null) {
        // 原有 HANDSHAKE 发现逻辑不变
        ...
    }

    long epochBaseline = node.getConfigEpoch();
    node.setConfigEpochIfGreater(nodeInfo.getConfigEpoch());

    Set<ClusterNodeState> flags = nodeInfo.getFlags();
    long gossipEpoch = nodeInfo.getConfigEpoch();

    if (isMyselfEntry) {
        // 自降级：仅当 gossipEpoch > localEpochBaseline 且 flags 含 SLAVE 且本地仍是 MASTER
        if (gossipEpoch > epochBaseline
                && flags.contains(ClusterNodeState.SLAVE)
                && node.isMaster()
                && nodeInfo.getMasterNodeId() != null) {
            applySelfDemotion(nodeInfo.getMasterNodeId(), gossipEpoch);
        }
        // MYSELF 的 slots 同步交由 applySelfDemotion 处理（清空本地 slots）
        continue;  // MYSELF 不走下方第三方节点分支
    }

    // 下方原有第三方节点角色切换/slots 同步逻辑不变
    ...
}
```

### 3.2 线程安全：通过 FailoverManager 路由自降级

`processGossipNodes` 运行在 Netty event loop 线程，与命令处理、复制、`onFailoverResult` 并发。自降级必须原子完成（角色切换 + slots 清空 + masterNodeId 设置 + 复制方向切换 + 持久化）。新增 `FailoverManager.applySelfDemotion`，与 `onFailoverResult` 共用同一 `synchronized` 监视器：

```java
// FailoverManager.java
public synchronized void applySelfDemotion(String newMasterNodeId, long newConfigEpoch) {
    ClusterNode myNode = clusterConfig.getMyNode();
    if (myNode == null || !myNode.isMaster()) {
        return;  // 幂等：已是 slave 则跳过
    }
    ClusterNode newMaster = clusterConfig.getNode(newMasterNodeId);
    if (newMaster == null) {
        logger.warn("自降级跳过: 新主节点未在本地配置中, newMasterId={}", newMasterNodeId);
        return;  // 等下一轮心跳发现新主后再降级
    }
    // 清空 MYSELF slots（从 SlotManager 与 ClusterConfig 移除归属）
    BitSet oldSlots = myNode.getSlots();
    if (oldSlots != null) {
        for (int i = oldSlots.nextSetBit(0); i >= 0; i = oldSlots.nextSetBit(i + 1)) {
            slotManager.setSlotOwner(i, newMasterNodeId);
            clusterConfig.setSlotOwner(i, newMasterNodeId);
        }
    }
    myNode.clearSlots();
    myNode.removeState(ClusterNodeState.MASTER);
    myNode.addState(ClusterNodeState.SLAVE);
    myNode.setMasterNodeId(newMasterNodeId);
    myNode.setConfigEpoch(newConfigEpoch);
    clusterConfig.setCurrentEpoch(newConfigEpoch);

    replicationLifecycleListener.demoteToSlave(newMaster);
    notifyTopologyChanged();
    logger.warn("MYSELF 经 gossip 自降级为 slave: newMaster={}, configEpoch={}",
            newMasterNodeId, newConfigEpoch);
}
```

`GossipProtocol.applySelfDemotion` 委托给 `FailoverManager`（若已注入）；若未注入（测试路径），回退到内联降级（仅 ClusterNode 变更，不触发复制切换）。

### 3.3 PING/PONG 携带 currentEpoch（向后兼容）

`PingMessage`/`PongMessage`：

- `encodeBody`：在末尾追加 8 字节 `senderCurrentEpoch`（大端序）。总长度计算 `+8`。
- `decodeBody`：在现有解码后，若 `offset + 8 <= body.length` 则读取 `senderCurrentEpoch`；否则保留默认值 0。
- 新增字段 `private long senderCurrentEpoch;` + getter/setter。

`GossipProtocol.sendPing`/`sendPong`（对称）：
```java
ping.setSenderCurrentEpoch(clusterConfig.getCurrentEpoch());
```

`updateNodeFromPingMessage`/`updateNodeFromPongMessage`：
```java
clusterConfig.setEpochIfGreater(ping.getSenderCurrentEpoch());
```

**向后兼容性证明**：
- 旧节点解码新消息：`decodeBody` 按`已知字段顺序`解码到 `senderMasterNodeId` 后停止，尾部多出的 8 字节被忽略。
- 新节点解码旧消息：读到 `senderMasterNodeId` 后 `offset + 8 > body.length`，`senderCurrentEpoch` 保持默认 0，`setEpochIfGreater(0)` 无副作用。
- `getEncodedLength()` 需更新以包含新字段，保证 gossip section 长度计算一致。

### 3.4 启动恢复软对齐

`NettyRedisServer.restoreClusterFromConfig`（`:478`）与 `initCurrentNode`（`:563`）保持不变（本地恢复 MYSELF 角色、slots、configEpoch 作为初始值）。仅增加诊断日志：当恢复的 MYSELF 为 master 且本地 `configEpoch` 低于 `currentEpoch` 时，输出 `"MYSELF 以本地配置恢复为 master, configEpoch={}, 等待 gossip 对齐"`。

依赖 gossip 心跳自然纠正：首个携带更高 configEpoch 的 PONG 触发自降级。在收敛前，写入请求因 slots 已属于新主（经 `syncSlotsFromNode` 同步）而返回 MOVED 重定向。

## 4. 数据流

```
重启节点 R (master, epoch=4, 持有旧 slots)
    │
    │  收到 A 的 PONG (A 已应用 FailoverResult)
    │  PONG 头: senderCurrentEpoch=9  → R.setEpochIfGreater(9) → R.currentEpoch=9
    │  PONG gossip section 含 R 的记录: {configEpoch=9, SLAVE, masterNodeId=9740}
    │
    ▼
processGossipNodes:
    │  isMyselfEntry=true, gossipEpoch=9 > localEpochBaseline=4, flags含SLAVE, R.isMaster()
    ▼
FailoverManager.applySelfDemotion(newMasterId=9740, newConfigEpoch=9) [synchronized]
    │  清空 R.slots → SlotManager/ClusterConfig 转移到 9740
    │  R: MASTER->SLAVE, masterNodeId=9740, configEpoch=9
    │  replicationLifecycleListener.demoteToSlave(9740) → R 向 9740 发起同步
    │  notifyTopologyChanged() → 持久化 nodes.conf
    ▼
R 现为 9740 的 slave, cluster nodes 一致
```

## 5. 边界条件

| 场景 | 处理 |
|------|------|
| gossip section 不含 MYSELF 记录（随机选 ≤3 节点未命中） | 多个心跳周期后收敛（秒级）；currentEpoch 经 PONG 头已同步，门控可生效 |
| `gossipEpoch == localEpoch` | 不降级（防回退已提升 master） |
| `gossipEpoch < localEpoch` | 忽略 |
| MYSELF 已是 SLAVE | `applySelfDemotion` 幂等返回 |
| 新主记录不在本地（R 的旧 nodes.conf 无 9740） | `applySelfDemotion` 检测 `newMaster == null` 跳过；等 `processGossipNodes` 发现 9740（HANDSHAKE->MEET）后，下一轮心跳再降级 |
| 并发 `onFailoverResult` 与 `applySelfDemotion` | 共用 `synchronized` 监视器，串行化 |
| 旧版本节点互通 | PING/PONG 尾部追加字段向后兼容（见 3.3） |

## 6. 测试策略

### 单元测试（`luban-rds-cluster`）

| 测试 | 覆盖 |
|------|------|
| `GossipProtocolSelfDemoteTest` | 自降级主场景：MYSELF 收到更高 configEpoch + SLAVE 视图 → 角色切换/slots 清空/masterNodeId 设置/demoteToSlave 调用 |
| 同上 | epoch 门控：`==` 不降级、`<` 忽略 |
| 同上 | 幂等：MYSELF 已是 SLAVE 时不重复降级 |
| 同上 | 新主记录缺失时跳过降级（等发现后再降级） |
| `GossipMessageCodecTest`（扩展） | PING/PONG 携带 currentEpoch 编解码 |
| 同上 | 旧消息（无 currentEpoch 字段）解码向后兼容，不抛异常 |
| `GossipProtocolTest`（扩展） | `setEpochIfGreater` 同步集群级 currentEpoch |

### 集成测试

| 测试 | 覆盖 |
|------|------|
| `ClusterRestartDemoteTest`（EmbeddedCluster） | 模拟故障转移 + 旧主重启 → 验证 `cluster nodes` 一致、旧主降为 slave、slots 归属正确、MOVED 重定向到新主 |

### 端到端验证

在 `D:\tmp\luban-rds` 6 节点集群复现：杀 9737 → 等 9740 提升 → 重启 9737 → 验证 `cluster nodes` 显示 9737 为 9740 的 slave。

## 7. 非目标

- 不改变故障转移选举/投票/广播时机。
- 不引入新消息类型或新持久化格式。
- 不改变 `nodes.conf` 格式或公开命令语义。
- 不处理 `CLUSTER FAILOVER TAKEOVER` 特殊语义。

## 8. 风险

- **gossip 收敛延迟**：随机选 ≤3 节点可能需多个心跳。可接受（秒级），且 currentEpoch 同步不受此限。
- **协议扩展兼容性**：尾部追加 + 长度校验 + 缺字段默认 0，向后兼容；需测试覆盖。
- **并发竞态**：通过 `synchronized applySelfDemotion` 与 `onFailoverResult` 共用监视器串行化。
