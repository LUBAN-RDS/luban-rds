---
change: fix-keyset-removed-on-caffeine-replace
design-doc: docs/superpowers/specs/2026-07-25-keyset-caffeine-replace-design.md
base-ref: d7dd973c4ca213949a6df478c209ae5711eee67a
archived-with: 2026-07-25-fix-keyset-removed-on-caffeine-replace
---

# 实施计划：修复 Caffeine removalListener 在 REPLACED 时误删 keySet

## 概述

修复 `DefaultMemoryStore.DatabaseStore` 的 Caffeine `removalListener` 在 `RemovalCause.REPLACED` 时误删 `keySet` 的 bug，导致 `pexpire`/`lrem` 的 `storage.put` 覆盖后 key 在 storage 里但 keySet 里没有，scan/dbsize/RDB 持久化扫不到。

设计文档：`docs/superpowers/specs/2026-07-25-keyset-caffeine-replace-design.md`

## 任务拆分

### Task 1: 核心修复 removalListener 忽略 REPLACED

**文件**：`luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java`
**位置**：`:289-300`（DatabaseStore 构造函数 Caffeine removalListener）

修改 `onRemoval` 方法，在方法开头加判断：
```java
if (cause == RemovalCause.REPLACED) {
    return;
}
```

`RemovalCause` 已在 `:16` import，无需补充。

**验证**：编译通过，现有测试不回归。

**提交**：`fix(core): Caffeine removalListener 忽略 REPLACED cause 避免误删 keySet`

### Task 2: 新增 RdbHsetHashKeyPersistenceTest

**文件**：`luban-rds-persistence/src/test/java/com/janeluo/luban/rds/persistence/impl/RdbHsetHashKeyPersistenceTest.java`

3 个测试用例：
- `testHsetThenPexpireThenPersist`：hset + pexpire 后 RDB 能保存 hash key（原复现场景）
- `testMultipleHsetFieldsThenPersist`：多次 hset 同 key 不同字段后持久化
- `testLremAfterPersist`：lrem 修改 list 后 RDB 能保存该 list key

**验证**：3 个测试用例通过。

**提交**：`test(persistence): 新增 hset+pexpire/lrem 后 RDB 持久化测试`

### Task 3: 新增 MemoryStoreKeySetConsistencyTest

**文件**：`luban-rds-core/src/test/java/com/janeluo/luban/rds/core/store/MemoryStoreKeySetConsistencyTest.java`

5 个测试用例：
- `testKeySetConsistentAfterPexpire`：pexpire 后 dbsize/scan/exists 一致
- `testKeySetConsistentAfterLrem`：lrem 后 dbsize/scan/exists 一致
- `testKeySetRemovedOnExplicitInvalidate`：显式 del 后 keySet 正确移除
- `testKeySetRemovedOnExpire`：过期后 keySet 经惰性清理正确移除
- `testKeySetConsistentAfterMultiplePexpire`：多次 pexpire 刷新过期时间后 keySet 仍一致

**验证**：5 个测试用例通过。

**提交**：`test(core): 新增 keySet 与 storage 一致性测试`

### Task 4: 回归验证

- 运行 `luban-rds-core` 全部测试
- 运行 `luban-rds-persistence` 全部测试
- 运行 `luban-rds-cluster`、`luban-rds-server` 集群相关测试
- 全量 `mvn clean install`

**验证**：全部测试通过。

### Task 5: 更新 tasks.md 勾选 + 文档

- 勾选 tasks.md 所有任务
- 更新 AGENTS.md（如适用）

## 执行顺序

Task 1 -> Task 2 -> Task 3 -> Task 4 -> Task 5

每个 Task 完成后提交代码并勾选 tasks.md。
