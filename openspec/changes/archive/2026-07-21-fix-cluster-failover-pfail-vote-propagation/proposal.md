# Proposal: 修复集群自动故障转移 PFAIL 投票不传播

## 问题描述

在 3 主 3 从的 Luban-RDS 集群中（每台物理服务器承载 1 主 1 从），当任意一台物理服务器宕机（其上的主节点和从节点同时消失）时，集群不会触发自动故障转移，而是对外持续抛出 `CLUSTERDOWN` 错误。

例如：服务器 A 宕机后，其上承载的 master M 与 M 的 slave S 都消失。M 对应的槽位（slots 0-5461）无人接管，客户端访问这些槽位的 key 时，存活节点应通过 `-MOVED` 重定向到新提升的 master，但因为 S（在其他服务器上的副本）从未被提升为新 master，客户端持续收到 `CLUSTERDOWN Hash slot not served` / `CLUSTERDOWN Slot owner not found` 错误。

## 根因分析

故障链路（Redis Cluster 标准协议）应为：

```
节点超时 → 标记 PFAIL → 多数 master 投票确认 → 标记 FAIL → slave 检测 master FAIL → 发起选举 → 提升为新 master
```

代码中 `FailureDetector` 已经实现了这条链路，但存在 **断点**：

### 根因：`FailureDetector.processGossipPfailVote()` 是空实现

文件：`luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/gossip/FailureDetector.java:227`

```java
public void processGossipPfailVote(GossipNodeInfo nodeInfo) {
    if (nodeInfo == null) {
        return;
    }
    String targetNodeId = nodeInfo.getNodeId();
    if (nodeInfo.isPfail()) {
        logger.debug("处理 Gossip PFAIL 信息: targetNodeId={}", targetNodeId);
        // ⚠️ 只打日志，没有调用 recordPfailVote()
    }
}
```

### 后果

1. `pfailVotes` 这个 Map 中**永远只包含本节点自己投的一票**（仅在 `checkNodeTimeout()` 中通过 `recordPfailVote(nodeId, myNodeId)` 写入）。
2. `isMajorityAgreed()` 计算需要 `masterCount/2 + 1` 票。3 主集群需要 2 票，但只能收到 1 票（自己）。
3. `getNodesToBroadcastFail()` 返回空集 → 永远不会有节点被标记为 `FAIL`。
4. `FailoverManager.tryStartElection()` 要求 `master.isFail()` 为真才进入 `REQUESTING`，由于 FAIL 永远不会被设置，**自动选举永远不会触发**。
5. slot 仍归已下线的 master 所有 → `RedisServerHandler.checkSlotAndRedirect` 返回 `CLUSTERDOWN`。

### 进一步验证

通过 Grep 确认 `recordPfailVote` 的所有调用点：
- `FailureDetector.checkNodeTimeout()` (L98)：只记录本节点投票
- `FailureDetector.recordPfailVote()` 自身定义
- **`processGossipPfailVote` 从不调用 `recordPfailVote`** ← 断点

同时 `GossipProtocol.processGossipNodes()` (L814) 在处理 PFAIL 标志时只设置了本地节点的 `PFAIL` 状态位，但没有把"消息发送方对这个节点投了 PFAIL 票"这一信息传递给 `FailureDetector`。

## 修复目标

1. 让 `FailureDetector` 能够接收并累计来自**其他 master 节点**通过 Gossip 传播的 PFAIL 投票。
2. 让 `GossipProtocol.processGossipNodes()` 在处理 gossip 节点信息时，把消息**发送方**（一个 master）对该节点的 PFAIL 投票登记到 `FailureDetector`。
3. 不引入新接口、不改协议消息、不改架构。

## 修复范围

- **影响文件**：2 个
  - `FailureDetector.java`：完善 `processGossipPfailVote` 方法签名与实现
  - `GossipProtocol.java`：在 `processGossipNodes` 中将发送方投票传递给 `FailureDetector`
- **不涉及**：协议消息、序列化、配置、数据库、新模块。

## 影响评估

- **向后兼容**：完全兼容。`GossipNodeInfo` 已携带 PFAIL 标志位，序列化格式不变。
- **风险**：低。修复仅填充已有的投票数据结构，激活已设计但未接通的代码路径。
- **测试**：补充 `FailureDetectorTest` 覆盖 Gossip 投票传播；考虑 `ClusterFailoverTest` 集成验证。
