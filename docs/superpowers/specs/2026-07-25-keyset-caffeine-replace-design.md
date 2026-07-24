---
comet_change: fix-keyset-removed-on-caffeine-replace
role: technical-design
canonical_spec: openspec
archived-with: 2026-07-25-fix-keyset-removed-on-caffeine-replace
status: final
---

# Design Doc: 修复 Caffeine removalListener 在 REPLACED 时误删 keySet

> 本文档为技术设计 RFC，需求事实源为 OpenSpec delta spec：
> `openspec/changes/fix-keyset-removed-on-caffeine-replace/specs/memory-store/spec.md`

## 1. 问题与根因（已确认）

### 现象
集群模式 Lua 脚本 `HSET` 写入的 hash key（如 `dpl-master:session:attr:*`）无法保存到 RDB，但同类 zset key（`redisson__idle__set:*`）能正常保存。

### 根因
`DefaultMemoryStore.DatabaseStore` 用 Caffeine cache 作 `storage`，另用 `keySet`（ConcurrentHashMap）跟踪 key 供 `scan`/`dbsize`/RDB 遍历。Caffeine `removalListener`（`DefaultMemoryStore.java:291-298`）在**任何** removal 时都执行 `keySet.remove(key)`，**包括 `put` 覆盖已有 entry 时的 `RemovalCause.REPLACED`**。

`pexpire`（`:1190`）和 `lrem`（`:2035`）调用 `storage.put` 覆盖已有条目，触发 removalListener 把 key 从 keySet 移除，但没有重新 `keySet.put`。结果：key 在 storage 里还在（get/exists/pttl 正常），但 keySet 里没有了，scan/dbsize/RDB 持久化扫不到。

### 复现证据
```
hset 后:  dbsize=1, scan count=1 ✓
pexpire 后: dbsize=0, exists=true, scan count=0 ✗
```

## 2. 方案选型

| 方案 | 描述 | 评估 |
|------|------|------|
| **A. removalListener 忽略 REPLACED** | onRemoval 判断 cause，REPLACED 时跳过 keySet.remove | ✅ 选用。治本，一处改动修复所有 put 覆盖路径 |
| B. pexpire/lrem 后重新 keySet.put | 在 :1190/:2035 的 put 后补 keySet.put | ❌ 治标，未来新增 put 覆盖路径易遗漏 |
| C. 两者都做 | A + B 双保险 | ❌ 冗余，增加维护成本 |

**选 A 的理由**：Caffeine `RemovalCause.REPLACED` 语义是"entry 被新值覆盖"，key 仍存在于 cache，不应从 keySet 移除。removalListener 设计意图是"key 真正离开 cache 时清理辅助索引"，REPLACED 不属于此场景。

## 3. 技术设计

### 3.1 核心修改

**文件**：`luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java`
**位置**：`:289-300`（DatabaseStore 构造函数 Caffeine removalListener）

修改前：
```java
.removalListener(new RemovalListener<String, StoreValue>() {
    @Override
    public void onRemoval(String key, StoreValue value, RemovalCause cause) {
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

`RemovalCause` 已在 `:16` import，无需补充 import。

### 3.2 RemovalCause 处理决策

| Cause | 含义 | keySet 移除 | 说明 |
|-------|------|------------|------|
| EXPLICIT | 显式 invalidate/remove | ✅ 是 | del/惰性清理路径 |
| COLLECTED | GC 回收 weak/soft | ✅ 是 | 本项目未用 weak/soft，不会触发 |
| EXPIRED | 过期淘汰 | ✅ 是 | 本项目 StoreValue 自管 expireTime，Caffeine 不主动触发；保留处理以防未来配置变化 |
| SIZE | 容量淘汰 | ✅ 是 | 本项目未配 maximumSize，不会触发 |
| **REPLACED** | **put 覆盖** | **❌ 否** | **本次修复核心** |

### 3.3 影响面分析

6 处 `storage.put` 调用点：

| 方法 | 行号 | put 后已 keySet.put | 修复后 |
|------|------|---------------------|--------|
| `set` | :918 | 是 | 无变化（REPLACED 被忽略，keySet.put 幂等） |
| `setWithExpire` | :949 | 是 | 无变化 |
| `setWithExpireAt` | :980 | 是 | 无变化 |
| `mset` 批量 | :1022 | 是 | 无变化 |
| `pexpire` | :1190 | **否** | **修复：key 保留在 keySet** |
| `lrem` | :2035 | **否** | **修复：key 保留在 keySet** |

### 3.4 数据流（修复后）

```
HSET KEYS[2] field value
  └─ hset(isNew=true) -> set() -> storage.put + keySet.put
     └─ 首次 put 无旧值，不触发 REPLACED
  keySet: {key} ✓  storage: {key} ✓

PEXPIRE KEYS[2] ms
  └─ pexpire -> storage.put 覆盖
     └─ removalListener(REPLACED) -> 跳过，不移除 keySet ✓
  keySet: {key} ✓  storage: {key} ✓

RDB 持久化
  └─ doPersist -> scan 遍历 keySet -> 找到 key -> get -> writeKeyValue ✓
  RDB 包含该 key ✓
```

## 4. 测试策略

### 4.1 新增测试类

**`RdbHsetHashKeyPersistenceTest`**（`luban-rds-persistence/src/test/java/.../impl/`）：
- `testHsetThenPexpireThenPersist`：hset + pexpire 后 RDB 能保存 hash key（原复现场景）
- `testMultipleHsetFieldsThenPersist`：多次 hset 同 key 不同字段后持久化
- `testLremAfterPersist`：lrem 修改 list 后 RDB 能保存该 list key

**`MemoryStoreKeySetConsistencyTest`**（`luban-rds-core/src/test/java/.../store/`）：
- `testKeySetConsistentAfterPexpire`：pexpire 后 dbsize/scan/exists 一致
- `testKeySetConsistentAfterLrem`：lrem 后 dbsize/scan/exists 一致
- `testKeySetRemovedOnExplicitInvalidate`：显式 del 后 keySet 正确移除
- `testKeySetRemovedOnExpire`：过期后 keySet 经惰性清理正确移除
- `testKeySetConsistentAfterMultiplePexpire`：多次 pexpire 刷新过期时间后 keySet 仍一致

### 4.2 回归测试

- `luban-rds-core` 全部测试（MemoryStore/HashCommandHandler/LuaCommandHandler）
- `luban-rds-persistence` 全部测试（RdbPersistServiceTest）
- `luban-rds-cluster` slot 相关测试（SlotUtilsTest 等）
- `luban-rds-server` 集群重定向测试（ClusterEvalSlotRedirectTest 等）
- 全量 `mvn clean install`

## 5. 风险评估

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| REPLACED 忽略后 keySet 残留已不存在的 key | 低 | scan/dbsize 计数偏差 | scan/dbsize 内部已有惰性清理（发现 storage 无 key 时 keySet.remove） |
| 现有依赖 removalListener REPLACED 行为的代码 | 极低 | 行为变化 | grep 确认仅 DatabaseStore 一处 removalListener |
| slotToKeys 一致性 | 低 | slot 查询偏差 | slotToKeys 同在 removalListener 维护，修复后与 keySet 行为一致 |

## 6. 非目标

- 不改动 pexpire/lrem 等方法的 storage.put 调用方式
- 不重构 keySet 跟踪机制
- 不修复集群脑裂拓扑问题（独立问题）
- 不改动 d7dd973 的 EVAL/EVALSHA slot 重定向逻辑（已验证有效）
