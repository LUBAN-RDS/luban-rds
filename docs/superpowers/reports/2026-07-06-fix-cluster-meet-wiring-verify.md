# Verification Report: fix-cluster-meet-wiring

- **Date:** 2026-07-06
- **Change:** fix-cluster-meet-wiring
- **Workflow:** hotfix
- **Verify mode:** light
- **Result:** PASS

## 1. 修复概述

集群模式下执行 `CLUSTER MEET <ip> <port>` 时，服务端返回 `-ERR cluster command not configured`，客户端立即断开，MEET 未生效。

**根因：** `NettyRedisServer.initChannel` 使用 4 参数构造方法创建 `RedisServerHandler`，未调用 `setClusterCommandHandler(...)`，导致运行时每个 handler 实例的 `clusterCommandHandler == null`。同时 `clusterEnabled=false`、`clusterConfig=null`、`slotManager=null`，集群重定向等逻辑一并失效。

**修复：** 改用 7 参数构造方法注入 `clusterEnabled/clusterConfig/slotManager`，并在集群模式下调用 `setClusterCommandHandler`，与测试装配（`TestNode`、`AbstractClusterHandlerTest` 等）保持一致。

## 2. 改动范围

| 文件 | 改动 |
|------|------|
| `luban-rds-server/.../NettyRedisServer.java` | `initChannel` 内 handler 装配（+9/-2 行） |

- 任务数：2（全部完成）
- 改动文件：1（仅实现文件）+ openspec 产物
- 无 delta spec（修复未改变已有 spec 验收场景）

## 3. 轻量验证 5 项检查

| # | 检查项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | tasks.md 全部任务 `[x]` | PASS | 2/2 完成，0 unchecked |
| 2 | 改动文件与 tasks 描述一致 | PASS | `git show --stat HEAD` 仅 `NettyRedisServer.java` + 产物 |
| 3 | 编译通过 | PASS | `mvn -pl luban-rds-server -am compile` BUILD SUCCESS |
| 4 | 相关测试通过 | PASS | 21/21：ClusterCommandIntegrationTest(6) + ClusterModeIntegrationTest(8) + ClusterRedirectIntegrationTest(7) |
| 5 | 无明显安全问题 | PASS | 无硬编码密钥、无 unsafe 操作 |

## 4. 根因消除确认

- 旧的 4 参数构造 + 无 setter 装配代码已从 `NettyRedisServer.initChannel` 移除（grep 确认 GONE）。
- 新装配使用 7 参数构造 + `setClusterCommandHandler`（grep 确认 line 494/498）。
- 生产装配路径现与测试装配路径（`TestNode.java:85-90`）一致，消除「测试-生产装配不一致」缺陷。

## 5. 测试失败说明（非本次引入）

`luban-rds-server` 的系统测试存在 5 个预存失败，已通过 baseline 复核确认与本次修复无关：

- `ClusterStartupTest.testClusterStartup_3Nodes`
- `ClusterStartupTest.testNodeDiscoveryViaMeet`
- `ClusterStartupTest.testGossipConvergence`
- `InterProcessCommTest.testGossipPingPong`
- `InterProcessCommTest.testNodeInfoPropagation`

**复核方法：** `git stash` 本次修改后，在未修改 baseline 上运行同样 5 个用例，结果 5/5 全部失败，与修复后表现一致 → 非回归。

**根因（测试基础设施）：** `testinfra/TestNode.java:57` 构造 `new GossipProtocol(clusterConfig, null, 15000)`，`busClient` 传 `null`，导致 `GossipProtocol.sendMeet` 中 `busClient == null` 分支静默不发 MEET 消息，多节点 gossip 无法收敛。这是测试桩装配缺陷，独立于本次修复的生产装配 bug。

## 6. 结论

修复通过轻量验证，根因已消除，未引入回归。预存的系统测试失败属测试基础设施问题，建议后续单独处理（为 `TestNode` 装配 `ClusterBusClient`）。
