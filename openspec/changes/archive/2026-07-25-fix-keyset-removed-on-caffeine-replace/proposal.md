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
