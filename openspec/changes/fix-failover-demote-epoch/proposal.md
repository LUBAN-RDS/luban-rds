# Proposal: fix-failover-demote-epoch

## Problem

前次修复 `fix-cluster-restart-demote` 引入了经 gossip 心跳的 MYSELF 自降级机制（`handleMyselfGossipEntry`），使用严格 epoch 门控 `gossipEpoch > localEpochBaseline` 防止回退。

但生产验证发现：故障转移后旧主重启仍不降级。日志显示：
```
MYSELF 以本地配置恢复为 master, configEpoch=0, currentEpoch=6, 等待 gossip 对齐
```
之后无任何自降级日志输出。

## Root Cause

`FailoverManager.onFailoverResult()` 将旧 master 降级为 SLAVE 时（第 510-523 行），**未提升其 configEpoch**：

```java
// 旧 master 降级为 winner 的 slave
node.clearSlots();
node.removeState(ClusterNodeState.MASTER);
node.addState(ClusterNodeState.SLAVE);
node.setMasterNodeId(winner.getNodeId());
// 缺失: node.setConfigEpoch(msg.getNewConfigEpoch());
```

而 gossip section 中该节点的 configEpoch 取自本地 ClusterNode 记录，因此传出的 gossipEpoch 等于旧 master 的原始 configEpoch（如 2）。当旧主重启后其 `myNode.configEpoch` 也是 2，导致 `handleMyselfGossipEntry` 中 `2 > 2` = false，自降级永不触发。

同时 `ClusterConfigPersister.save()` 的 `# My Config Epoch` header 写入的是 `config.getConfigEpoch()`（一个独立维护的 AtomicLong，始终为 0），而非 MYSELF 节点的实际 configEpoch，导致诊断日志 `configEpoch=0` 产生误导。

## Fix

1. **`FailoverManager.onFailoverResult()`**：旧 master 降级时同步设置 `node.setConfigEpoch(msg.getNewConfigEpoch())`，使 gossip 传播的 configEpoch 反映最新故障转移纪元，触发 `handleMyselfGossipEntry` 的严格门控。
2. **`ClusterConfigPersister.save()`**：`# My Config Epoch` 写入 MYSELF 节点的实际 configEpoch，消除 header 与实际节点条目的不一致。

## Scope

- 2 files, no API change, no architectural change
- Backward compatible
