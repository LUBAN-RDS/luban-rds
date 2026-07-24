# Comet Design Handoff

- Change: fix-keyset-removed-on-caffeine-replace
- Phase: design
- Mode: compact
- Context hash: 2cbab1482dd8d574a623b9202ea2da133cf842bdb7a24a1ed931cf9520831698

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/fix-keyset-removed-on-caffeine-replace/proposal.md

- Source: openspec/changes/fix-keyset-removed-on-caffeine-replace/proposal.md
- Lines: 1-51
- SHA256: 602beac53e4e54a492d121c3367f4c1f92038afa3479194969da7ee5ced42be1

```md
# Proposal: 修复 Caffeine removalListener 在 REPLACED 时误删 keySet 导致 RDB 持久化丢数据

## 问题背景

集群模式下，Lua 脚本通过 `HSET` 写入的 hash key（如 `dpl-master:session:attr:*`）无法保存到 RDB，但同类 zset key（如 `redisson__idle__set:*`）能正常保存。用户报告 `dpl-master:session:attr:{...}` 开头的 key 没保存上，`redisson__idle__set:{dpl-master:ShiroJwtRealm.authorizationCache}` 却保存上了。

## 根因

`DefaultMemoryStore.DatabaseStore` 用 Caffeine cache 作为 `storage`，并用独立的 `keySet`（ConcurrentHashMap）跟踪所有 key，供 `scan`/`dbsize`/RDB 持久化遍历使用。Caffeine 配置了 `removalListener`（`DefaultMemoryStore.java:291-298`），在**任何** removal 时都执行 `keySet.remove(key)` —— **包括 `put` 覆盖已有 entry 时的 `RemovalCause.REPLACED`**。

`pexpire`（`:1190`）和 `lrem`（`:2035`）调用 `storage.put` 覆盖已有条目，触发 removalListener 把 key 从 keySet 移除，但**没有重新 `keySet.put`**。

**Lua 脚本典型序列**：`HSET KEYS[2] ARGV[1] ARGV[2]` 紧接 `PEXPIRE KEYS[2] newTimeout`：
1. HSET 创建 key（isNew=true）-> `set()` -> `storage.put` + `keySet.put` ✓
2. PEXPIRE -> `storage.put` 覆盖旧条目 -> 触发 removalListener(REPLACED) -> `keySet.remove(key)` ✗
3. 结果：key 在 `storage` 里还在（`get`/`exists`/`pttl`/`pexpire` 都正常返回），但 `keySet` 里没有了
4. RDB 持久化用 `scan` 遍历 `keySet` -> 扫不到该 key -> 不保存

## 复现证据

复现测试输出（`RdbHsetHashKeyPersistenceTest`，已临时验证后删除）：
```
DEBUG after hset:    dbsize=1, scan count=1 ✓   ← hset 后 keySet 有 key
PTTL: -1
DEBUG after pexpire: dbsize=0                   ← pexpire 后 keySet 被清空
  exists=true                                    ← 但 storage 里 key 还在
scan result size: 0                              ← scan 扫不到
```

## 为什么 zset key 能保存

`redisson__idle__set:*` 是 zset，Redisson 用 `zadd` 写入。`zadd` 走 `set`/`setWithExpire` 路径，`storage.put` 后紧跟 `keySet.put` 重新加回；且其过期由 Redisson 框架在单独脚本管理，没有紧跟的 pexpire 覆盖，所以 keySet 不丢失。

## 目标

- **修复根因**：让 Caffeine `removalListener` 在 `RemovalCause.REPLACED` 时**不**移除 keySet，仅对真实驱逐（EXPLICIT/COLLECTED/EXPIRED/SIZE）移除
- **保证数据一致性**：修复后 `keySet` 与 `storage` 保持一致，`scan`/`dbsize`/RDB 持久化能正确遍历所有 key
- **回归覆盖**：补充测试覆盖 hset+pexpire 后 RDB 持久化、scan 可见性、dbsize 准确性

## 范围

- **核心修改**：`DefaultMemoryStore.DatabaseStore` 构造函数中 Caffeine `removalListener` 的 cause 判断（`luban-rds-core`）
- **回归测试**：`luban-rds-persistence` 模块新增 hset+pexpire+RDB 持久化测试；`luban-rds-core` 模块补充 keySet 一致性测试
- **影响面评估**：scan、dbsize、RDB 持久化、集群 slot 索引（slotToKeys 同样在 removalListener 中维护）

## 非目标

- 不改动 `pexpire`/`lrem` 等方法的 `storage.put` 调用方式（removalListener 忽略 REPLACED 已足够治本）
- 不重构 keySet 跟踪机制（不引入 Caffeine 原生 keySet 替代方案）
- 不修复集群脑裂拓扑问题（日志显示的 9739/9740/9741 slave 角色混乱是独立问题，本次不处理）
- 不改动 d7dd973 提交的 EVAL/EVALSHA slot 重定向逻辑（已验证有效）
```

## openspec/changes/fix-keyset-removed-on-caffeine-replace/design.md

- Source: openspec/changes/fix-keyset-removed-on-caffeine-replace/design.md
- Lines: 1-124
- SHA256: a92272928133102fb501f284dea959822c22b285246dc03b00ab8189863f237c

[TRUNCATED]

```md
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
```

Full source: openspec/changes/fix-keyset-removed-on-caffeine-replace/design.md

## openspec/changes/fix-keyset-removed-on-caffeine-replace/tasks.md

- Source: openspec/changes/fix-keyset-removed-on-caffeine-replace/tasks.md
- Lines: 1-22
- SHA256: 45ccd489fb3fd0067b8325230ea84475afe59c5587210984ad9615d587c6503f

```md
# Tasks: 修复 Caffeine removalListener 在 REPLACED 时误删 keySet

## 核心修复

- [ ] T1: 修改 `DefaultMemoryStore.DatabaseStore` Caffeine `removalListener`，`RemovalCause.REPLACED` 时跳过 `keySet.remove` 和 `removeFromSlotIndex`（`DefaultMemoryStore.java:289-300`）
- [ ] T2: 补充 import `com.github.benmanes.caffeine.cache.RemovalCause`（如未存在）

## 测试新增

- [ ] T3: 新增 `RdbHsetHashKeyPersistenceTest`（`luban-rds-persistence`）覆盖 hset+pexpire 后 RDB 持久化、多次 hset 后持久化、lrem 后持久化
- [ ] T4: 新增 `MemoryStoreKeySetConsistencyTest`（`luban-rds-core`）覆盖 pexpire/lrem 后 keySet 一致性、显式 del/过期后 keySet 正确移除

## 回归验证

- [ ] T5: 运行 `luban-rds-core` 全部测试
- [ ] T6: 运行 `luban-rds-persistence` 全部测试
- [ ] T7: 运行 `luban-rds-cluster`、`luban-rds-server` 集群相关测试
- [ ] T8: 全量 `mvn clean install` 验证

## 文档

- [ ] T9: 更新 AGENTS.md 记录此修复（如适用）
```

## openspec/changes/fix-keyset-removed-on-caffeine-replace/specs/memory-store/spec.md

- Source: openspec/changes/fix-keyset-removed-on-caffeine-replace/specs/memory-store/spec.md
- Lines: 1-34
- SHA256: 0891713637faaf0d471945cc542a0515fe9aa8caf5a48bd7c5bf20e39e0d2409

```md
# Spec Delta: memory-store

## 修改：keySet 与 storage 一致性保证

### 背景

`DefaultMemoryStore.DatabaseStore` 用 Caffeine cache 作为 `storage`，并用独立 `keySet`（ConcurrentHashMap）跟踪所有 key 供 `scan`/`dbsize`/RDB 持久化遍历。Caffeine `removalListener` 负责在 key 离开 cache 时同步清理 `keySet` 和 `slotToKeys`。

### 要求

#### REQ-1: removalListener 忽略 REPLACED cause

Caffeine `removalListener` 在 `RemovalCause.REPLACED` 时（即 `storage.put` 覆盖已有 entry）**不得**移除 `keySet` 或 `slotToKeys` 中的条目，因为此时 key 仍存在于 cache 中。

仅以下 cause 触发 keySet/slotToKeys 清理：
- `EXPLICIT`（显式 invalidate/remove）
- `COLLECTED`（GC 回收 weak/soft 引用）
- `EXPIRED`（过期淘汰）
- `SIZE`（容量淘汰）

#### REQ-2: put 覆盖路径保持 keySet 一致

调用 `storage.put` 覆盖已有 entry 的方法（包括但不限于 `pexpire`、`lrem`）在修复后无需额外 `keySet.put`，因为 removalListener 不再误删。key 在整个 put 覆盖生命周期内始终保留在 keySet 中。

#### REQ-3: scan/dbsize/RDB 持久化可见性

任何通过 `storage.put` 写入或覆盖的 key，必须能被 `scan`、`dbsize`、RDB 持久化（`RdbPersistService.doPersist` 遍历 scan）正确遍历到，只要该 key 未被显式删除或过期。

### 验证场景

- `hset` 创建 hash key 后 `pexpire` 设置过期：scan 必须包含该 key，dbsize 必须计数该 key，RDB 持久化必须保存该 key
- `lrem` 修改 list 后：scan/dbsize/RDB 必须包含该 list key
- 显式 `del` key 后：keySet 必须移除该 key
- key 过期后（惰性清理路径）：keySet 必须移除该 key
```

