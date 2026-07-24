# Design: fix-cluster-eval-slot-redirect

## 修复方案

在 `RedisServerHandler` 中，为 `EVAL`/`EVALSHA` 增加集群专用的 slot 校验路径，对齐 Redis 原生集群语义。

### 方案要点

1. **从 `NO_KEY_COMMANDS` 移除 `EVAL`/`EVALSHA`**，使其进入集群重定向检查分支。但 EVAL 的 key 提取规则与普通命令不同（`EVAL script numkeys key [key ...]`），因此不能复用 `extractKeyFromCommand` 的默认分支，需保留并完善已有的 EVAL 专用提取逻辑（`RedisServerHandler.java:2458-2471`）。

2. **多 key 同 slot 校验（CROSSSLOT）**：在 `extractKeyFromCommand` 提取出第一个 key 后、执行 `checkSlotAndRedirect` 前，对 `numkeys > 1` 的情况校验所有 KEYS 是否属于同一 slot。若存在不同 slot，返回 `-CROSSSLOT` 错误，不执行脚本。

   这是 Redis 原生集群的硬性约束：EVAL 中所有 KEYS 必须落在同一 hash slot（通常通过 `{tag}` hash tag 保证）。

3. **MOVED/ASK 重定向**：所有 KEYS 同 slot 后，以 KEYS[1] 的 slot 做 `checkSlotAndRedirect` 与 `checkAskRedirect`，逻辑与普通命令一致。

4. **numkeys=0 的脚本**：无 key 的脚本（如 `EVAL "return 1" 0`）不涉及 slot，直接执行，不做重定向。`extractKeyFromCommand` 对 numkeys=0 已返回 null，现有 `commandRequiresKey` 分支会跳过，需确保此路径仍通畅。

### 实现位置

`luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java`：

- 删除 `NO_KEY_COMMANDS.add("EVAL")` 与 `NO_KEY_COMMANDS.add("EVALSHA")` 两行
- 在集群重定向检查块（line 720-749）中，针对 EVAL/EVALSHA 增加 CROSSSLOT 校验

### 边界与兼容

- **单机模式**（`clusterEnabled=false`）：`commandRequiresKey` 分支不进入，行为不变。
- **numkeys=0**：`extractKeyFromCommand` 返回 null，跳过重定向，行为不变。
- **EVALSHA**：与 EVAL 共用同一 key 提取与校验路径（args 结构一致）。
- **SCRIPT 命令**（LOAD/EXISTS/FLUSH/KILL）：仍保留在 `NO_KEY_COMMANDS`，不参与重定向（SCRIPT 不操作具体 key，对齐 Redis）。

### 风险评估

- 低风险：改动集中于集群重定向检查入口，不触碰命令执行与存储层。
- 向后兼容：之前"错误地在本节点执行跨 slot 脚本"的行为本就是 bug，修复后会正确返回 MOVED/CROSSSLOT，客户端（Redisson）能正确处理重定向。
