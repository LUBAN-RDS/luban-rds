# Design: 修复集群故障转移后槽位归属不一致

## 修复方案

在 `FailoverManager` 的两处槽位转移代码中，补充对 `ClusterConfig.setSlotOwner()` 的调用，确保 `ClusterConfig.slotAssignment[]` 与 `DefaultSlotManager.slotOwners[]` 保持一致。

### 修改点 1：`performFailover()` 方法

在 `FailoverManager.performFailover()` 的槽位转移循环中增加 `clusterConfig.setSlotOwner()` 调用：

```java
// 当前代码 (line 368-371)
for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
    slaveNode.addSlot(i);
    slotManager.setSlotOwner(i, slaveNode.getNodeId());
}

// 修复后
for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
    slaveNode.addSlot(i);
    slotManager.setSlotOwner(i, slaveNode.getNodeId());
    clusterConfig.setSlotOwner(i, slaveNode.getNodeId());  // 新增
}
```

### 修改点 2：`onFailoverResult()` 方法

在 `FailoverManager.onFailoverResult()` 的槽位转移循环中增加 `clusterConfig.setSlotOwner()` 调用：

```java
// 当前代码 (line 422-423)
for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
    slotManager.setSlotOwner(i, winner.getNodeId());
}

// 修复后
for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
    slotManager.setSlotOwner(i, winner.getNodeId());
    clusterConfig.setSlotOwner(i, winner.getNodeId());  // 新增
}
```

## 影响范围

- 仅修改 1 个文件：`FailoverManager.java`
- 修改 2 处：各新增 1 行代码
- 不涉及接口变更
- 不涉及新的依赖

## 风险分析

- `clusterConfig.setSlotOwner()` 内部已正确处理槽位所有权变更（包括更新 `ClusterNode.slots` 和 `assignedSlotCount`），幂等安全
- 修复后 `clusterConfig.slotAssignment[]`、`DefaultSlotManager.slotOwners[]`、`ClusterNode.slots` 三者保持一致
- `performFailover()` 中额外调用 `clusterConfig.setSlotOwner()` 会再次执行 `node.addSlot(slot)`（第 269-272 行），但 `addSlot` 内部只是 `BitSet.set(slot)`，重复调用无副作用
