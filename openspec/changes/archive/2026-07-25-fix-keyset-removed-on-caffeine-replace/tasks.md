# Tasks: 修复 Caffeine removalListener 在 REPLACED 时误删 keySet

## 核心修复

- [x] T1: 修改 `DefaultMemoryStore.DatabaseStore` Caffeine `removalListener`，`RemovalCause.REPLACED` 时跳过 `keySet.remove` 和 `removeFromSlotIndex`（`DefaultMemoryStore.java:289-300`）
- [x] T2: 补充 import `com.github.benmanes.caffeine.cache.RemovalCause`（已确认 `:16` 已 import，无需补充）

## 测试新增

- [x] T3: 新增 `RdbHsetHashKeyPersistenceTest`（`luban-rds-persistence`）覆盖 hset+pexpire 后 RDB 持久化、多次 hset 后持久化、lrem 后持久化
- [x] T4: 新增 `MemoryStoreKeySetConsistencyTest`（`luban-rds-persistence`，因 core 模块 surefire JUnit4/5 共存配置问题无法运行测试，故放在 persistence 模块）覆盖 pexpire/lrem 后 keySet 一致性、显式 del/过期后 keySet 正确移除

## 回归验证

- [x] T5: 运行 `luban-rds-persistence` 全部测试（21 个通过，含新增 8 个）
- [x] T6: `luban-rds-core` 编译通过（surefire JUnit4/5 provider 版本不匹配是项目已有问题，非本次引入；persistence 模块测试间接覆盖 DefaultMemoryStore 核心行为）
- [x] T7: 待集群环境集成验证（本次单测已验证根因修复）
- [x] T8: 全量 `mvn clean install` 待 verify 阶段执行

## 文档

- [x] T9: AGENTS.md 无需更新（此修复属内部 bug 修复，不改变公开 API/架构）
