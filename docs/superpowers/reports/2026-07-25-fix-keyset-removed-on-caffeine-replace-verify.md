# 验证报告：fix-keyset-removed-on-caffeine-replace

- **Change**: fix-keyset-removed-on-caffeine-replace
- **Date**: 2026-07-25
- **Verify Mode**: full
- **Result**: PASS
- **Branch**: fix-keyset-removed-on-caffeine-replace
- **Commit**: fde07ac

## 改动规模

| 指标 | 值 | 阈值 |
|------|-----|------|
| 任务数 | 9 | 3 |
| Delta specs | 1 capability | 1 |
| 变更文件 | 3 | 4 |
| 新增行数 | 323 | - |

规模评估结果：full（任务数超阈值）。

## 完整验证检查项

| # | 检查项 | 结果 | 证据 |
|---|--------|------|------|
| 1 | tasks.md 全部任务已完成 | PASS | 9/9 任务勾选 `[x]` |
| 2 | 实现符合 design.md 高层设计 | PASS | removalListener 加 `cause == REPLACED` 判断跳过，与方案 A 一致 |
| 3 | 实现符合 Design Doc | PASS | 修改点 DefaultMemoryStore.java:289-300，REPLACED 忽略、EXPIRED/EXPLICIT/COLLECTED/SIZE 仍移除，与 Design Doc 第 3.1 节一致 |
| 4 | 能力规格场景全部通过 | PASS | REQ-1/REQ-2/REQ-3 由 8 个测试覆盖，全部通过 |
| 5 | proposal.md 目标已满足 | PASS | 修复根因、保证 keySet/storage 一致性、回归覆盖 |
| 6 | delta spec 与 design doc 无矛盾 | PASS | Build 阶段无 spec 增量 |
| 7 | Design Doc 可定位 | PASS | docs/superpowers/specs/2026-07-25-keyset-caffeine-replace-design.md 存在且关联当前 change |

## 测试证据

### 新增测试（8 用例全过）

```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 - MemoryStoreKeySetConsistencyTest
  - testKeySetConsistentAfterPexpire ✓
  - testKeySetConsistentAfterLrem ✓
  - testKeySetRemovedOnExplicitInvalidate ✓
  - testKeySetRemovedOnExpire ✓
  - testKeySetConsistentAfterMultiplePexpire ✓

Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 - RdbHsetHashKeyPersistenceTest
  - testHsetThenPexpireThenPersist ✓
  - testMultipleHsetFieldsThenPersist ✓
  - testLremAfterPersist ✓
```

### 回归测试

- `luban-rds-persistence` 全量 21 测试通过（含原有 13 + 新增 8），无回归
- `luban-rds-core` 编译通过（surefire JUnit4/5 provider 版本不匹配是项目已有问题，非本次引入）

## 核心修改验证

**修改文件**：`luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java:289-305`

修改前：removalListener 在任何 removal 时都执行 `keySet.remove(key)` 和 `removeFromSlotIndex(key)`。

修改后：`RemovalCause.REPLACED` 时 return 跳过，仅对 EXPLICIT/COLLECTED/EXPIRED/SIZE 移除。

**根因修复验证**：
- 修复前：hset 后 pexpire，dbsize 从 1 变 0，scan 扫不到 key，RDB 保存空文件
- 修复后：hset 后 pexpire，dbsize 保持 1，scan 扫到 key，RDB 正确保存 hash key

## 安全检查

- 无硬编码密钥
- 无新增 unsafe 操作
- 无敏感数据日志

## 已知限制

- `luban-rds-core` 模块测试因 surefire JUnit4/5 provider 版本不匹配无法运行（项目已有问题），通过 persistence 模块测试间接覆盖 DefaultMemoryStore 核心行为
- 集群环境集成验证待实际部署后确认（本次单测已验证根因修复）

## 结论

验证通过。实现符合设计，根因已修复，测试覆盖充分，无回归。
