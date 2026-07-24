# Proposal: 修复集群节点宕机后整个服务不可用

## 问题描述

集群模式下，当一台节点宕机后，即使自动故障转移（failover）成功将 slave 提升为新 master，集群服务整体仍然不可用。

## 根因分析

故障转移在 `FailoverManager` 中更新槽位归属时，存在**双重槽位追踪不一致**的问题：

系统中存在两套独立的槽位归属追踪：
1. **`DefaultSlotManager.slotOwners[]`** — 用于命令路由（`RedisServerHandler.checkSlotAndRedirect` 读取）
2. **`ClusterConfig.slotAssignment[]`** — 用于 `CLUSTER SLOTS` 响应、`ClusterStateManager.isClusterOk()` 判断、Gossip 槽位同步

在故障转移执行过程中：

### 缺陷 1：`performFailover()` 未同步 ClusterConfig

`FailoverManager.performFailover()` (line 362-382) 执行槽位转移时：
- ✅ 调用了 `slotManager.setSlotOwner(i, slaveNode.getNodeId())`
- ✅ 调用了 `slaveNode.addSlot(i)`
- ❌ **未调用** `clusterConfig.setSlotOwner(i, slaveNode.getNodeId())`

### 缺陷 2：`onFailoverResult()` 未同步 ClusterConfig

`FailoverManager.onFailoverResult()` (line 392-453) 在所有节点接收 FailoverResult 时：
- ✅ 调用了 `slotManager.setSlotOwner(i, winner.getNodeId())`
- ✅ 调用了 `winner.setSlots((BitSet) inherited.clone())`
- ❌ **未调用** `clusterConfig.setSlotOwner(i, winner.getNodeId())`

### 影响链

1. 故障转移后 `slotManager` 正确更新 → 命令路由（MOVED 重定向）正确 → 单个命令可正确路由到新 master
2. 但 `ClusterConfig.slotAssignment[]` 仍指向旧 FAIL master → 以下功能异常：
   - `CLUSTER SLOTS` 返回旧 master 地址（客户端拓扑发现获取到错误路由表）
   - `ClusterStateManager.isClusterOk()` → `config.getSlotOwnerNode(slot)` 返回旧 FAIL master → `isFail()` = true → 永远返回 false
   - `CLUSTER INFO` 始终显示 `cluster_state:fail`
   - `stateManager.updateClusterState()` 将集群状态置为 "fail"

由于智能 Redis 客户端（Jedis, Redisson 等）依赖 `CLUSTER SLOTS` 做拓扑发现和路由表刷新，`CLUSTER SLOTS` 返回错误信息导致客户端将请求路由到已宕机节点，表现为**整个服务不可用**。

## 修复目标

1. `performFailover()` 中同步更新 `clusterConfig.setSlotOwner()`
2. `onFailoverResult()` 中同步更新 `clusterConfig.setSlotOwner()`
3. 确保故障转移后 `ClusterConfig.slotAssignment[]` 与 `DefaultSlotManager.slotOwners[]` 一致
