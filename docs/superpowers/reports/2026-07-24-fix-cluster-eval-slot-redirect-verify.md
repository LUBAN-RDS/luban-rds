# 验证报告: fix-cluster-eval-slot-redirect

- **Change**: fix-cluster-eval-slot-redirect
- **Workflow**: hotfix
- **Verify mode**: full（任务数 4 超阈值；无 delta spec，spec/design-doc 相关项 N/A）
- **Date**: 2026-07-24
- **Commit**: d7dd973

## 1. 修复概述

集群模式下 `EVAL`/`EVALSHA` 脚本命令被加入 `NO_KEY_COMMANDS`，跳过 slot 重定向检查，导致脚本数据写到错误节点、读取时路由到正确节点却读不回（表现为"保存不上数据"，单机正常）。

修复：从 `NO_KEY_COMMANDS` 移除 `EVAL`/`EVALSHA`；新增 `checkCrossSlotForScript` 校验多 key 脚本所有 KEYS 同 slot，跨 slot 返回 `-CROSSSLOT`，对齐 Redis 原生集群语义。

## 2. 根因验证（日志佐证）

- `D:\tmp\luban-rds\rds-9740\logs\luban-rds.log`：9740 为 9738 的 slave（无 slot），却执行了 `EVAL ... dpl-master:session:info:{ec0816ec-...}`（slot=11433，应属 9738）。
- 修复前：不返回 `-MOVED`，直接本地执行，`PTTL` 返回 -2，`RedissonSessionDao.doReadSession` 判定 `remainTimeToLive > 0` 为 false，返回 null -> session 丢失。
- 修复后：该请求会返回 `-MOVED 11433 <9738地址>`，Redisson 客户端正确重定向到 9738。

## 3. 验证检查项

| # | 检查项 | 结果 | 说明 |
|---|--------|------|------|
| 1 | tasks.md 全部 `[x]` | PASS | T1-T4 全部完成 |
| 2 | 实现符合 design.md 高层设计 | PASS | 移除黑名单 + 新增 CROSSSLOT 校验，与 design.md 一致 |
| 3 | 编译通过 | PASS | `mvn -pl luban-rds-server -am compile` 通过 |
| 4 | 相关测试通过 | PASS | 见下表 |
| 5 | 无明显安全问题 | PASS | 无硬编码密钥、无 unsafe 操作 |
| 6 | 能力规格场景 | N/A | hotfix 无 delta spec |
| 7 | delta spec 与 design doc 一致性 | N/A | hotfix 无 delta spec / superpowers design doc |
| 8 | proposal.md 目标已满足 | PASS | 4 项目标全部达成 |

### 测试结果

| 测试类 | 用例数 | 结果 |
|--------|--------|------|
| ClusterEvalSlotRedirectTest（新增） | 7 | 全部通过 |
| ClusterRedirectIntegrationTest（回归） | 7 | 全部通过 |
| ClusterCommandIntegrationTest（回归） | 6 | 全部通过 |
| LuaBinaryDataDirectTest 等 Lua 测试（回归） | 9 | 全部通过 |

新增测试覆盖场景：
- 单 key EVAL，key 在本节点 -> 正常执行
- 单 key EVAL，key 不在本节点 -> `-MOVED`
- 多 key EVAL，同 slot（hash tag）-> 正常执行
- 多 key EVAL，跨 slot -> `-CROSSSLOT`
- numkeys=0 EVAL -> 不重定向，正常执行
- EVALSHA 单 key 本地 -> 正常执行
- 单机模式 EVAL -> 不重定向，正常执行

## 4. 改动文件

- `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java`（+60/-4）
- `luban-rds-server/src/test/java/com/janeluo/luban/rds/server/cluster/ClusterEvalSlotRedirectTest.java`（新增）

## 5. 兼容性

- 单机模式（`clusterEnabled=false`）：行为不变
- numkeys=0 脚本：行为不变
- SCRIPT 命令（LOAD/EXISTS/FLUSH/KILL）：仍在 `NO_KEY_COMMANDS`，不参与重定向（对齐 Redis）

## 6. 结论

验证通过。根因已消除，修复对齐 Redis 原生集群语义，无回归。
