# Design: 修复 Caffeine removalListener 在 REPLACED 时误删 keySet

## 架构决策

### 方案选型

评估了三个方案，选择方案 A：

| 方案 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **A. removalListener 忽略 REPLACED** | 在 `onRemoval` 里判断 `cause`，仅对 EXPLICIT/COLLECTED/EXPIRED/SIZE 移除 keySet，REPLACED 时跳过 | 治本，一处改动修复所有 `storage.put` 覆盖路径（pexpire/lrem/未来新增）；语义清晰对齐 Caffeine 设计意图 | 需确认 REPLACED 不遗漏其他需要清理的场景 |
| B. pexpire/lrem 后重新 keySet.put | 在 `:1190` 和 `:2035` 的 `storage.put` 后补 `keySet.put` | 改动小 | 治标不治本，未来新增 put 覆盖路径易遗漏；与 removalListener 语义重复 |
| C. 两者都做 | removalListener 忽略 REPLACED + 所有 put 覆盖路径补 keySet.put | 双保险 | 冗余，增加维护成本 |

**选择 A 的理由**：Caffeine 的 `RemovalCause.REPLACED` 语义是"entry 被新值覆盖"，此时 key 仍然存在于 cache 中，不应从 keySet 移除。removalListener 的设计意图是"key 真正离开 cache 时清理辅助索引"，REPLACED 不属于此场景。方案 A 一处改动即可修复所有现有和未来的 put 覆盖路径。

### Caffeine RemovalCause 语义对照

| Cause | 含义 | keySet 应否移除 |
|-------|------|----------------|
| EXPLICIT | 显式 invalidate/remove | 是 |
| COLLECTED | GC 回收 weak/soft 引用 | 是 |
| EXPIRED | 过期淘汰 | 是 |
| SIZE | 容量淘汰 | 是 |
| **REPLACED** | **put 覆盖已有 entry** | **否（key 仍在 cache 中）** |

## 修改点

### 核心修改：`DefaultMemoryStore.DatabaseStore` 构造函数

**文件**：`luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java`
**位置**：`:289-300`（Caffeine `removalListener`）

修改前：
```java
.removalListener(new RemovalListener<String, StoreValue>() {
    @Override
    public void onRemoval(String key, StoreValue value, RemovalCause cause) {
        // 当键被移除时，从keySet和slotToKeys中也移除
        keySet.remove(key);
        removeFromSlotIndex(key);
    }
})
```

修改后：
```java
.removalListener(new RemovalListener<String, StoreValue>() {
    @Override
    public void onRemoval(String key, StoreValue value, RemovalCause cause) {
        // REPLACED 表示 entry 被新值覆盖（如 pexpire/lrem 的 storage.put），
        // key 仍在 cache 中，不应从 keySet/slotToKeys 移除。
        // 仅在 key 真正离开 cache 时清理辅助索引。
        if (cause == RemovalCause.REPLACED) {
            return;
        }
        keySet.remove(key);
        removeFromSlotIndex(key);
    }
})
```

### 影响面分析

| 调用 `storage.put` 的方法 | 行号 | put 后是否已 `keySet.put` | 修复后行为 |
|--------------------------|------|--------------------------|-----------|
| `set` | :918 | 是 | 无变化（REPLACED 被忽略，keySet.put 仍执行，幂等） |
| `setWithExpire` | :949 | 是 | 无变化 |
| `setWithExpireAt` | :980 | 是 | 无变化 |
| `mset` 批量 | :1022 | 是 | 无变化 |
| `pexpire` | :1190 | **否** | **修复：REPLACED 不再移除 keySet，key 保留** |
| `lrem` | :2035 | **否** | **修复：REPLACED 不再移除 keySet，key 保留** |

修复后，pexpire/lrem 路径的 keySet 与 storage 保持一致，scan/dbsize/RDB 持久化能正确遍历。

## 数据流

### 修复后 Lua HSET+PEXPIRE 序列

```
1. HSET KEYS[2] field value
   └─ hset(isNew=true) -> set() -> storage.put + keySet.put
      └─ removalListener(REPLACED? 否，首次 put 无旧值，不触发) 
   keySet: {key} ✓  storage: {key} ✓

2. PEXPIRE KEYS[2] ms
   └─ pexpire -> storage.put 覆盖
      └─ removalListener(REPLACED) -> 跳过，不移除 keySet ✓
   keySet: {key} ✓  storage: {key} ✓

3. RDB 持久化
   └─ doPersist -> scan 遍历 keySet -> 找到 key -> get -> writeKeyValue ✓
   RDB 包含该 key ✓
```

## 测试策略

### 新增测试

1. **`RdbHsetHashKeyPersistenceTest`**（`luban-rds-persistence`）：
   - `testHsetThenPexpireThenPersist`：hset + pexpire 后 RDB 能保存 hash key（原复现场景）
   - `testMultipleHsetFieldsThenPersist`：多次 hset 同 key 不同字段后持久化
   - `testLremAfterPersist`：lrem 后 list key 仍能被 RDB 保存

2. **`MemoryStoreKeySetConsistencyTest`**（`luban-rds-core`）：
   - `testKeySetConsistentAfterPexpire`：pexpire 后 dbsize/scan/exists 一致
   - `testKeySetConsistentAfterLrem`：lrem 后 dbsize/scan/exists 一致
   - `testKeySetRemovedOnExplicitInvalidate`：显式 del 后 keySet 正确移除
   - `testKeySetRemovedOnExpire`：过期后 keySet 正确移除（惰性清理路径）

### 回归测试

- 运行 `luban-rds-core` 全部测试（MemoryStore/HashCommandHandler/LuaCommandHandler）
- 运行 `luban-rds-persistence` 全部测试（RdbPersistServiceTest）
- 运行 `luban-rds-cluster` slot 相关测试（SlotUtilsTest 等）
- 运行 `luban-rds-server` 集群重定向测试（ClusterEvalSlotRedirectTest 等）

## 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| REPLACED 忽略后，某些本应清理的场景遗漏 | 低 | keySet 残留已不存在的 key | scan/dbsize 内部已有惰性清理（发现 storage 无 key 时 keySet.remove） |
| 现有依赖 removalListener REPLACED 行为的代码 | 低 | 需排查 | grep 确认仅 DatabaseStore 构造函数一处 removalListener |
| slot 索引（slotToKeys）一致性 | 低 | slot 查询偏差 | slotToKeys 同在 removalListener 维护，修复后与 keySet 行为一致 |
