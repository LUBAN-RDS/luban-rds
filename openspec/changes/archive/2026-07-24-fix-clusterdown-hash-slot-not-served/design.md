## 修复方案

### 问题分析

Gossip 协议中 `masterNodeId` 是 FailoverResult 丢包时的后备收敛机制，也是 CLUSTER NODES 输出中确定 master-slave 关系的关键字段。当前代码存在两处同步缺陷：

**缺陷 1：角色切换时 masterNodeId 未同步**

`processGossipNodes()` (L1064-1067) 和 `syncSenderRole()` (L1212-1215) 在检测到 MASTER→SLAVE 角色切换时，仅修改了状态标志（`removeState(MASTER)` + `addState(SLAVE)`），但未同步设置 `masterNodeId`。虽然后续有回填逻辑（L1072-1074 / L1219-1221），但回填仅在 `masterNodeId != null` 时生效。若 gossip 消息中的 `masterNodeId` 因编码或传输问题为 null，节点会处于 SLAVE 状态但 `masterNodeId` 为 null 的非法状态。

**缺陷 2：decode() 未清除旧值**

`GossipNodeInfo.decode()` 当 `hasMasterId == 0` 时未显式设置 `this.masterNodeId = null`。在当前代码中，消息解码始终创建新 `GossipNodeInfo` 对象，故该问题仅为潜伏风险，尚未被触发。

### 修复内容

**修改 1：GossipProtocol.processGossipNodes() — 角色切换时设置 masterNodeId**

在 L1064-1067 的 MASTER→SLAVE 切换块中，增加 `node.setMasterNodeId(nodeInfo.getMasterNodeId())`：

```java
if (flags.contains(ClusterNodeState.SLAVE) && node.isMaster()) {
    node.removeState(ClusterNodeState.MASTER);
    node.addState(ClusterNodeState.SLAVE);
    node.setMasterNodeId(nodeInfo.getMasterNodeId());  // 新增
}
```

**修改 2：GossipProtocol.syncSenderRole() — 同上**

在 L1212-1215 的 MASTER→SLAVE 切换块中，增加 `sender.setMasterNodeId(masterNodeId)`：

```java
if (flags.contains(ClusterNodeState.SLAVE) && sender.isMaster()) {
    sender.removeState(ClusterNodeState.MASTER);
    sender.addState(ClusterNodeState.SLAVE);
    sender.setMasterNodeId(masterNodeId);  // 新增
}
```

**修改 3：GossipNodeInfo.decode() — 显式清除 masterNodeId**

在 `hasMasterId == 0` 分支中增加 `this.masterNodeId = null`：

```java
if (hasMasterId == 1) {
    // ... existing code ...
} else if (hasMasterId != 0) {
    throw new IllegalArgumentException(...);
}
// 新增 else 分支：
else {
    this.masterNodeId = null;
}
```

### 替代方案

无。此修复是最小化改动，直接消除已识别的缺陷。

### 风险评估

- **低风险**：修改仅在特定条件分支内增加字段设置，不影响正常主路径
- 现有测试 `ClusterReplicateGossipTest`、`GossipProtocolTest` 覆盖相关场景，修复后应继续通过
