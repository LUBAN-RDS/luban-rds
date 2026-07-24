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
