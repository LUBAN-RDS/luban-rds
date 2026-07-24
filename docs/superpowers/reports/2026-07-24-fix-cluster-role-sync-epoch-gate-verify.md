# Verify Report: fix-cluster-role-sync-epoch-gate

**Date**: 2026-07-24
**Change**: fix-cluster-role-sync-epoch-gate
**Workflow**: hotfix
**Base ref**: b97c6df1749a37544c95094ba3af85a84c1a1b87
**Result**: PASS

## 问题描述

`redis-cli --cluster create` 建立集群后，所有 slave 节点在 gossip 传播中始终被识别为 master，导致 Redisson 客户端只能看到 slot 0-5460，访问其他 slot 时收到 `CLUSTERDOWN Hash slot not served`，应用启动失败。

## 根因

1. **`syncSenderRole` 纪元门控失效（主因）**：`updateNodeFromMeetMessage` / `updateNodeFromPingMessage` / `updateNodeFromPongMessage` 在调用 `syncSenderRole` 前，已通过 `setConfigEpochIfGreater` 把本地 `configEpoch` 提升到与消息 `senderConfigEpoch` 相等，导致 `syncSenderRole` 内 `configEpoch > localEpoch` 恒为 false，MASTER->SLAVE 切换永不发生。

2. **`processGossipNodes` 同类缺陷**：第三方节点角色同步存在相同问题——`setConfigEpochIfGreater` 先于角色判断执行，`gossipEpoch > localEpoch` 恒为 false。

3. **`CLUSTER ADDSLOTS` 未设置 `myNode.configEpoch`**：`clusterAddslots` 只调用 `incrementEpoch()`，不设置 `myNode.setConfigEpoch(...)`，导致 master 的 `configEpoch` 始终为 0。

4. **`CLUSTER SET-CONFIG-EPOCH` 未实现**：`redis-cli --cluster create` 发送的 `SET-CONFIG-EPOCH` 命令返回 `Unknown subcommand`，初始纪元无法建立。

## 修复内容

### GossipProtocol.java
- `syncSenderRole` 签名新增 `localEpochBaseline` 参数，角色切换与 masterNodeId 同步基于基线判断，避免 `setConfigEpochIfGreater` 的副作用。
- `updateNodeFromMeetMessage` / `updateNodeFromPingMessage` / `updateNodeFromPongMessage`：在 `setConfigEpochIfGreater`（或 `syncSlotsFromNode`）之前捕获 `epochBaseline`，传入 `syncSenderRole`。
- `processGossipNodes`：在 `setConfigEpochIfGreater` 之前捕获 `epochBaseline`，角色切换 `gossipEpoch > localEpoch` 改为基于基线判断。

### ClusterCommandHandler.java
- `clusterAddslots`：在 `incrementEpoch` 后调用 `myNode.setConfigEpoch(clusterConfig.getCurrentEpoch())`，与 `clusterReplicate` / 故障转移路径一致。
- 新增 `SET-CONFIG-EPOCH` 子命令分支与 `clusterSetConfigEpoch` 方法，设置 `myNode.configEpoch` 与 `currentEpoch`（取较大值）。

## 测试结果

### 新增测试
- `GossipRoleSyncTest`：7/7 PASS
  - MEET 携带 SLAVE 角色时接收方切换对端为 SLAVE ✅
  - PING 携带 SLAVE 角色时接收方切换对端为 SLAVE ✅
  - PONG 携带 SLAVE 角色时接收方切换对端为 SLAVE ✅
  - 陈旧纪元（< 基线）不触发角色切换 ✅
  - MEET 携带 MASTER 角色时从 SLAVE 提升为 MASTER（故障转移）✅
  - 相等于基线的纪元不切换角色 ✅
  - Gossip section 中第三方节点 SLAVE 角色同步（processGossipNodes 路径）✅

- `ClusterCommandHandlerTest`：42/42 PASS（含 3 个新增测试）
  - ADDSLOTS 后 `myNode.getConfigEpoch() > 0` ✅
  - SET-CONFIG-EPOCH 能设置纪元 ✅
  - SET-CONFIG-EPOCH 无效参数校验 ✅

### 回归测试
- `GossipMessageSenderRoleCodecTest`：5/5 PASS ✅
- `GossipProtocolTest`：13/13 PASS ✅
- `ClusterReplicateGossipTest`（上一个 hotfix 的端到端集成测试）：2/2 PASS ✅
- `ClusterFailoverTest`（故障转移场景）：16/16 PASS ✅
- `ClusterIntegrationTest`：13/13 PASS ✅
- 总计回归：91/91 PASS

### 预先存在的无关失败（非本次修改引入）
- `luban-rds-core` 的 ACL 测试（`ACLIntegrationTest`、`ACLPerformanceTest`、`ACLPermissionCheckerTest`）有 3 个失败，与集群模块无关。
- `RedissonClusterCompatibilityTest` / `LettuceClusterCompatibilityTest` 需要外部服务器连接，环境相关失败，与本次修改无关。

## 结论

4 处根因全部修复，所有相关测试通过，回归无破坏。修复不影响 gossip 二进制协议或 RESP 接口，仅修正角色同步语义与补齐缺失命令。
