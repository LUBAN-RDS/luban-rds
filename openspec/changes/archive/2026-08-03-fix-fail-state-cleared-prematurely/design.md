# Design: 修复 FAIL 状态被过早清除导致 failover 失败

## 方案：FAIL 状态保护期（对齐 Redis Cluster 语义）

### 核心思路

Redis Cluster 中，节点被标记 FAIL 后，会在 `server.cluster_node_timeout * 2` 时间内保持 FAIL 状态，期间收到的 PING/PONG 不会清除 FAIL（见 Redis `cluster.c` 中 `markNodeAsFailing` 与 `clearNodeFailureIfNeeded` 的保护期逻辑）。这保证了 slave 有足够窗口完成选举，避免 master 短暂抖动导致 failover 反复取消。

当前实现缺少此保护期：`GossipProtocol.handlePing/handlePong` 一收到 PING/PONG 就调用 `clearNodeFailState` 无条件清除 FAIL，导致 master 宕机时 failover 永远无法完成。

### 改动点

#### 1. `ClusterNode.java` 新增 FAIL 标记时间

```java
// 新增字段：记录节点被标记为 FAIL 的时刻（0 表示未标记）
private volatile long failTime;

// addState(FAIL) 时记录时间（在 ClusterNode.addState 内部，或由 FailureDetector 在标记时设置）
// removeState(FAIL) 时清零
```

为最小化改动且集中逻辑，在 `FailureDetector` 标记 FAIL 时显式记录时间，在 `clearNodeFailState` 清除时由 `ClusterNode` 内部 `removeState` 清零。采用**由 `ClusterNode.addState/removeState` 维护 `failTime`** 的方式，保证所有路径一致。

#### 2. `FailureDetector.clearNodeFailState` 增加保护期判断

```java
public void clearNodeFailState(String nodeId) {
    ClusterNode node = clusterConfig.getNode(nodeId);
    if (node == null) return;

    // PFAIL 清除不受保护期影响（PFAIL 是本节点主观判断，收到 PONG 即可清除）
    if (node.isPfail()) {
        node.removeState(ClusterNodeState.PFAIL);
        // 注意：不清除 pfailVotes，因为 FAIL 保护期可能仍需要
        logger.info("节点恢复，清除 PFAIL 状态: nodeId={}", nodeId);
    }

    // FAIL 清除受保护期约束
    if (node.isFail()) {
        long failDuration = System.currentTimeMillis() - node.getFailTime();
        if (failDuration < 2L * nodeTimeout) {
            // 保护期内，拒绝清除 FAIL（对齐 Redis：FAIL 至少保持 NODE_TIMEOUT*2）
            logger.debug("FAIL 保护期内，拒绝清除 FAIL 状态: nodeId={}, failDuration={}ms, 保护期={}ms",
                    nodeId, failDuration, 2L * nodeTimeout);
            return;
        }
        node.removeState(ClusterNodeState.FAIL);
        confirmedFailNodes.remove(nodeId);
        pfailVotes.remove(nodeId);
        logger.info("FAIL 保护期已过，节点恢复清除 FAIL 状态: nodeId={}, failDuration={}ms", nodeId, failDuration);
    }
}
```

#### 3. `confirmNodeFail` 标记 FAIL 时记录时间

`FailureDetector.confirmNodeFail` 在 `node.addState(FAIL)` 时，由 `ClusterNode.addState` 自动记录 `failTime = System.currentTimeMillis()`。

### 关键决策

1. **保护期长度 `NODE_TIMEOUT * 2`**：对齐 Redis。当前 `nodeTimeout=15000ms`，保护期 30s，远大于 failover 退避窗口（gracePeriod + jitter ≤ 500ms）与选举超时（2*nodeTimeout），保证 slave 能完成选举。

2. **PFAIL 不受保护期约束**：PFAIL 是单节点主观判断，收到 PONG 即可清除（对齐 Redis `PFAIL` 语义）。仅 FAIL（集群共识）受保护期约束。

3. **保护期内仍允许 PFAIL 重新标记**：`checkNodeTimeout` 中 `if (node.isFail()) continue` 已跳过 FAIL 节点，不会重复标记 PFAIL，无需改动。

4. **`failTime` 由 `ClusterNode.addState/removeState` 维护**：保证所有标记/清除 FAIL 的路径（`handleFail`、`confirmNodeFail`、`onFailoverResult` 降级清除等）一致维护时间戳，无需在每个调用点显式设置。

### 不改动项

- `GossipProtocol.handlePing/handlePong` 仍调用 `clearNodeFailState`，语义由被调用方内部判断，调用方无感知。
- `FailoverManager` 无需改动--保护期保证了 master 在选举窗口内保持 FAIL，`handleRequestingState` 的 `!master.isFail()` 分支不会误触发。
- `handleFail`（gossip FAIL 消息处理）仍直接 `addState(FAIL)`，由 `ClusterNode.addState` 记录时间。

### 风险与缓解

- **风险**：master 真正恢复后，需等待 `2*nodeTimeout` 才清除 FAIL，期间该节点被视为不可用。
- **缓解**：对齐 Redis 行为，且 FAIL 节点本就被视为宕机；保护期后立即清除，延迟可接受（30s）。failover 成功后原 master 降级为 slave 时会显式清除 FAIL（`performFailover`/`onFailoverResult` 中的 `removeState(FAIL)`），不受保护期影响（那是角色变更路径，非恢复路径）。
