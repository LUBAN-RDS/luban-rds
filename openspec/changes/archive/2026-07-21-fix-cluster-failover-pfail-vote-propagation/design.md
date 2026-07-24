# Design: 修复集群自动故障转移 PFAIL 投票不传播

## 上下文

参考 Redis 官方集群协议中 Gossip section 的 PFAIL 传播机制：

> Each time a Cluster node pings another node, it also sends some random information about a few other nodes it knows. The PFAIL flag is also propagated via gossip. When a node receives PFAIL flags via gossip, it records the vote.

Luban-RDS 已实现了对应的 `pfailVotes` 数据结构与 `recordPfailVote` 方法，但 **gossip 接收侧的接线断裂**，导致跨节点投票永远进不来。

## 修复方案（单一方案）

### 改动 1：`FailureDetector.processGossipPfailVote` 接收发送方投票

修改方法签名，显式接收"投票人"参数，并在目标节点处于 PFAIL 状态时调用 `recordPfailVote`。

```java
/**
 * 从 Gossip 消息中处理 PFAIL 投票。
 * <p>
 * Gossip section 携带的是"消息发送方"对某节点的看法：
 * 如果发送方认为该节点是 PFAIL，则把发送方记入该节点的投票集合。
 * </p>
 *
 * @param nodeInfo       Gossip section 中描述的节点信息
 * @param voterNodeId    消息发送方节点 ID（即投票人）
 */
public void processGossipPfailVote(GossipNodeInfo nodeInfo, String voterNodeId) {
    if (nodeInfo == null || voterNodeId == null) {
        return;
    }

    if (nodeInfo.isPfail()) {
        String targetNodeId = nodeInfo.getNodeId();
        // 跳过对自己投票（本节点的 PFAIL 投票在 checkNodeTimeout 中已经记录）
        if (voterNodeId.equals(targetNodeId)) {
            return;
        }
        recordPfailVote(targetNodeId, voterNodeId);
        logger.debug("处理 Gossip PFAIL 投票: targetNodeId={}, voterNodeId={}",
                targetNodeId, voterNodeId);
    }
}
```

**设计要点**：
- `voterNodeId` 即心跳消息（PING/PONG/MEET）的 `senderNodeId`，发送方在该消息中夹带自己看世界的视图。
- 只有当 `nodeInfo.isPfail()` 为真时才登记，避免污染投票集。
- 跳过自投（targetNodeId == voterNodeId），因为本节点对自己的 PFAIL 投票无意义。

### 改动 2：`GossipProtocol.processGossipNodes` 把发送方传入

当前方法签名是 `processGossipNodes(List<GossipNodeInfo> gossipNodes)`，调用方（`handlePing`、`handlePong`、`handleMeet`）都能拿到 `senderNodeId`。需要把发送方 ID 一路传进来。

**改动方案**：新增重载或修改签名。考虑当前 `processGossipNodes` 是 `private` 且只在 3 处调用，直接修改签名最清晰：

```java
private void processGossipNodes(List<GossipNodeInfo> gossipNodes, String senderNodeId) {
    // ... 原有逻辑不变 ...

    for (GossipNodeInfo nodeInfo : gossipNodes) {
        // ... 原有节点同步逻辑 ...

        // 新增：将发送方的 PFAIL 投票登记到 FailureDetector
        failureDetector.processGossipPfailVote(nodeInfo, senderNodeId);
    }
}
```

三个调用点同步更新：
- `handlePing` (L339)：`processGossipNodes(ping.getGossipNodes(), ping.getSenderNodeId())`
- `handlePong` (L380)：`processGossipNodes(pong.getGossipNodes(), pong.getSenderNodeId())`
- `handleMeet` 路径（`updateNodeFromMeetMessage` 或类似入口，需检查是否也调用 `processGossipNodes`）

### 为什么只让 master 的投票有效

Redis 协议中只有 master 的 PFAIL 投票在多数派判定中才有效。本修复在 `recordPfailVote` 时不做角色过滤，但在 `isMajorityAgreed` 中以 `masterCount` 为基数计算多数——slave 的投票进来了也会被自然忽略（因为 `pfailVotes` 集合可能包含 slave ID，但 `masterCount` 只数 master，多数阈值不变）。

**潜在优化**（不在本次范围）：在 `recordPfailVote` 中校验 voter 是否为 master。本次保持最小改动，先打通链路。

## 边界情况

1. **节点恢复**：`clearNodeFailState` 会清空 `pfailVotes[nodeId]`，节点恢复后重新累计投票，符合预期。
2. **重复投票**：`pfailVotes` 用 `Set<String>` 存储，幂等。
3. **消息乱序**：PFAIL 投票没有版本号，但 FAIL 判定是"达到阈值即触发"，乱序不影响正确性，最多延迟一轮 gossip（1s）。
4. **自投票过滤**：避免本节点把自己的 PFAIL 看法重复登记。

## 不做的事

- 不修改 `GossipNodeInfo` 序列化结构。
- 不引入新的 gossip 字段。
- 不调整 `nodeTimeout`、`gossipInterval` 等配置默认值。
- 不重构 `FailureDetector`。
- 不修改 `FailoverManager`、`ClusterBusHandler`（它们已经正确，只是上游 FAIL 没设置）。

## 测试策略

1. **单元测试**（`FailureDetectorTest`）：
   - `processGossipPfailVote` 收到 PFAIL 标志 + voter 时，`pfailVotes` 应登记该投票。
   - `processGossipPfailVote` 收到非 PFAIL 时不应登记。
   - 跳过自投。
   - 多 voter 累计后 `isMajorityAgreed` 返回 true。
2. **回归测试**：运行 `luban-rds-cluster` 模块现有测试，确保不破坏 `FailureDetectorTest`、`FailoverManagerTest`、`ClusterFailoverTest`。
3. **构建验证**：`mvn clean install -pl luban-rds-cluster -am` 通过。
