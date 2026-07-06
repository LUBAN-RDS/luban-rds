# Verification Report: fix-cluster-nodes-crlf

**Date:** 2026-07-07
**Change:** fix-cluster-nodes-crlf
**Workflow:** hotfix
**Verify mode:** full
**Result:** PASS

## Summary

| Dimension    | Status |
|--------------|--------|
| Completeness | 5/5 tasks, 1/1 requirements |
| Correctness  | 2/2 scenarios covered |
| Coherence    | Design decisions followed, scope maintained |

## Change Summary

修复 `CLUSTER NODES` 响应 bulk payload 行尾符，由 `\r\n` 改为裸 `\n`，对齐真实 Redis 线协议，消除 Redisson `ClusterNodesDecoder.decode` 解析 slot 字段时的 `NumberFormatException`。

根因：`ClusterCommandHandler.clusterNodes()` 每行用 `\r\n` 结尾，响应经 RESP bulk-string 封装后 `\r\n` 残留在 payload 中。Redisson `ClusterNodesDecoder.decode`（line 49）用 `response.split("\n")` 切行，每行末尾残留 `\r`，使末尾 slot 字段解析为 `"0-5460\r"`，`Integer.valueOf("5460\r")` 抛 `NumberFormatException`，集群连接初始化失败。

## 1. Completeness

### Task Completion
- [x] 1.1 `clusterNodes()` 行尾 `\r\n` → `\n` — 已实现于 `ClusterCommandHandler.java:264`
- [x] 1.2 单元测试覆盖行尾 + slot 字段 — `ClusterCommandHandlerTest.testClusterNodes` / `testClusterNodesNonContiguousSlots`
- [x] 1.3 `luban-rds-cluster` 模块测试通过
- [x] 2.1 cluster 集成测试通过
- [x] 2.2 未改动 `formatNodeInfo` / `ClusterConfigPersister`（范围保持）

**5/5 tasks complete.**

### Spec Coverage
Delta spec `cluster-commands` 新增 1 个 requirement：`CLUSTER NODES 线协议行尾符`，含 2 个 scenario。实现已覆盖（见 Correctness）。

## 2. Correctness

### Requirement: CLUSTER NODES 线协议行尾符

#### Scenario: 集群客户端可解析 CLUSTER NODES 的 slot 字段
- **WHEN** 集群拓扑收敛且存在持有 slot 区间的 master 节点
- **THEN** 每行末尾不得残留 `\r`
- **AND** 末尾 slot 字段（如 `0-5460`）可被 `Integer.parseInt` 成功解析

**实现证据：**
- `ClusterCommandHandler.java:264` `sb.append("\n")` — 行尾为裸 `\n`
- 测试 `testClusterNodes`：分配连续 slot `0-5`，断言 `assertFalse(result.contains("\r"))` 且 `assertTrue(result.endsWith("\n"))` 且 `result.contains("0-5")`

#### Scenario: 持有非连续 slot 的节点行可被正确解析
- **WHEN** 某 master 节点持有非连续 slot（如 slot 0 与 slot 100）
- **THEN** 该行以 `\n` 结尾，每段 slot token 不含 `\r`，可逐段 `Integer.parseInt`

**实现证据：**
- 测试 `testClusterNodesNonContiguousSlots`：分配 `0` 与 `100`，断言 `result.contains("0 100")`，并模拟 Redisson `ClusterNodesDecoder` 的 `split("\n")` + `split(" ")` + `Integer.parseInt` 逐段解析，无异常

**2/2 scenarios covered.**

## 3. Coherence

### Design Adherence
design.md 关键决策：
1. 仅修改 `clusterNodes()` 行尾为 `\n` — **已遵循**（`ClusterCommandHandler.java:264`）
2. 不动 `formatNodeInfo` 与 `ClusterConfigPersister` — **已遵循**（`git diff HEAD~2` 确认 persister 无改动；`formatNodeInfo` 仍用 `\r\n`，但因其以 RESP array of bulk strings 返回，框架 `\r\n` 已分隔条目，非本次范围）

### Code Pattern Consistency
- 修复点注释解释了真实 Redis 行为与 Redisson 解析机制，符合项目代码注释密度
- 测试命名与断言风格与既有 `testClusterNodes` 一致
- 提交信息遵循项目 `fix(cluster): ...` 约定

## 4. 测试结果

| 测试套件 | 结果 |
|---------|------|
| `ClusterCommandHandlerTest`（luban-rds-cluster） | 37 tests, 0 failures, 0 errors |
| `luban-rds-cluster` 全模块 | 307 tests, 0 failures, 0 errors (3 skipped) |
| `ClusterCommandIntegrationTest`（luban-rds-server） | 6 tests, 0 failures, 0 errors |

**预存失败（与本次修复无关）：**
`luban-rds-server` cluster 的 system/e2e 套件存在 5 failures + 1 error（`ClusterStartupTest`、`InterProcessCommTest`、`FaultRecoveryTest`），均为 gossip 收敛 / 多进程网络测试。已在基线（stash 本次改动后）复现相同失败，确认由工作区既有 gossip 日志降噪改动（`ClusterBusHandler`/`GossipProtocol`/`GossipTask`，用户选择保留）引起，与 `CLUSTER NODES` 行尾符无关。

## 5. Security

无硬编码密钥，无新增 unsafe 操作，无外部依赖变更。

## Final Assessment

**All checks passed. No CRITICAL or WARNING issues. Ready for archive.**

- 根因已消除：`clusterNodes()` 行尾为裸 `\n`，payload 无 `\r`
- 客户端兼容性恢复：Redisson 集群连接初始化不再抛 `NumberFormatException`
- 范围最小：仅 2 个代码/测试文件改动 + openspec 制品
