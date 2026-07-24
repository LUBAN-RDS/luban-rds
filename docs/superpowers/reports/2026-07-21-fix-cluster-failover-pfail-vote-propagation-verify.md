# Verify Report: fix-cluster-failover-pfail-vote-propagation

- **Date**: 2026-07-21
- **Change**: fix-cluster-failover-pfail-vote-propagation
- **Workflow**: hotfix
- **Verify mode**: light
- **Commit**: 113d0d5
- **Result**: ✅ PASS

## 问题背景

3 主 3 从集群（每台物理机 1 主 1 从）中任一物理机宕机时，集群不自动故障转移，持续抛 `CLUSTERDOWN`。

## 根因

`FailureDetector.processGossipPfailVote()` 为空实现，导致 Gossip 心跳中携带的其他 master 节点 PFAIL 投票永远进不来 `pfailVotes`。`isMajorityAgreed()` 永远只有本节点自己 1 票，无法达到 `masterCount/2 + 1`，节点永不进入 FAIL 状态，`FailoverManager.tryStartElection()` 的 `master.isFail()` 条件永假，自动故障转移无法触发。

## 修复

1. `FailureDetector.processGossipPfailVote(GossipNodeInfo, String voterNodeId)` 在目标节点 PFAIL 时调用 `recordPfailVote`，跳过自投票。
2. `GossipProtocol.processGossipNodes(List, String senderNodeId)` 末尾把发送方投票传递给 `FailureDetector`。
3. 同步更新 `handlePing` / `handlePong` / `handleMeet` 三处调用点。

## 改动规模

| 项 | 数值 |
|---|---|
| 改动文件 | 3（2 main + 1 test） |
| 增量行 | +135 / -13 |
| Delta spec | 0 |
| 跨模块 | 否（单 luban-rds-cluster 模块） |
| 架构变更 | 否 |

## 5 项轻量验证

| # | 检查项 | 结果 | 证据 |
|---|---|---|---|
| 1 | tasks.md 全部任务完成 | PASS | 16/16 `[x]` |
| 2 | 改动与 tasks 一致 | PASS | 3 文件对应 Task 1-3 范围 |
| 3 | 编译通过 | PASS | `mvn clean install -pl luban-rds-cluster -am -DskipTests` BUILD SUCCESS |
| 4 | 相关测试通过 | PASS | `mvn test -pl luban-rds-cluster`：337 通过 / 0 失败 / 0 错误 / 3 跳过 |
| 5 | 无安全问题 | PASS | 无新增 I/O / 网络操作 / 硬编码密钥 / unsafe |

## 测试细节

新增 4 个单元测试（`FailureDetectorTest`）：
- `testProcessGossipPfailVoteWhenPfailShouldRecordVote`：PFAIL 标志 + voter 时登记投票
- `testProcessGossipPfailVoteWhenNotPfailShouldNotRecordVote`：非 PFAIL 不登记
- `testProcessGossipPfailVoteWhenSelfVoteShouldSkip`：自投票跳过
- `testProcessGossipPfailVoteMultipleVotersReachesMajority`：多 voter 累计达多数

回归测试覆盖：
- `FailureDetectorTest`：15/15 通过
- `FailoverManagerTest`：全部通过
- `ClusterFailoverTest`：全部通过
- `luban-rds-cluster` 模块总计：337 通过 / 3 跳过

## 根因消除验证

通过 grep 确认 `recordPfailVote` 调用链已贯通：

```
FailureDetector.checkNodeTimeout() ──→ recordPfailVote(nodeId, myNodeId)           [本节点投票]
GossipProtocol.processGossipNodes() ──→ processGossipPfailVote(nodeInfo, sender)
                                          └──→ recordPfailVote(targetNodeId, voter)  [跨节点投票]
```

修复前 `pfailVotes` 只含本节点 1 票，修复后可累计达 `masterCount/2+1`，FAIL 共识链路完整接通。

## 分支处理

直接在 master 提交（hotfix 流程允许），未创建独立分支。
