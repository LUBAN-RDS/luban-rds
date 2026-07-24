# Proposal: fix-cluster-eval-slot-redirect

## 问题现象

集群模式下，通过 Lua 脚本（`EVAL`/`EVALSHA`）读写的数据"保存不上"：写入看似成功，但后续读取返回空。单机模式一切正常。

复现场景：Redisson 客户端（如 `RedissonSessionDao` 保存 Shiro session）在集群模式下用 `EVAL` 执行 `READ_SCRIPT`（`return redis.call('PTTL', KEYS[1])`）读取 session，返回 -2（key 不存在），导致 `doReadSession` 判定 `remainTimeToLive > 0` 为 false，返回 null，session 丢失。

## 根因分析

`RedisServerHandler` 将 `EVAL`/`EVALSHA` 加入 `NO_KEY_COMMANDS` 集合（`RedisServerHandler.java:2408-2409`）：

```java
NO_KEY_COMMANDS.add("EVAL");
NO_KEY_COMMANDS.add("EVALSHA");
```

而集群重定向检查逻辑（`RedisServerHandler.java:722`）依赖 `commandRequiresKey()` 判断：

```java
if (clusterEnabled && commandRequiresKey(commandName)) { ... MOVED/ASK 检查 ... }
```

`commandRequiresKey()` 对 `NO_KEY_COMMANDS` 中的命令直接返回 false，导致 **EVAL/EVALSHA 在集群模式下完全跳过 slot 重定向检查**。

后果：客户端把 EVAL 发到任意节点（包括无 slot 的 slave 节点、或 slot 不属于该节点的 master），luban-rds 不返回 `-MOVED`，而是直接在本地 `MemoryStore` 执行。数据写进了错误节点的本地内存，读取时路由到正确节点却读不到，表现为"保存不上数据"。

日志佐证（`D:\tmp\luban-rds\rds-9740\logs\luban-rds.log`）：
- 9740 是 9738 的 slave，无 slot 分配
- 却收到并执行了 `EVAL ... 1, dpl-master:session:info:{ec0816ec-...}`（该 key slot=11433，应属 9738）
- 9740 本地无此 key，`PTTL` 返回 -2 -> session 读不到

这与 Redis 原生集群行为不符：Redis 集群要求 EVAL 必须校验所有 KEYS 属于同一 slot，并按 KEYS[1] 所在 slot 路由，跨 slot 时返回 `-CROSSSLOT`，slot 不在本节点时返回 `-MOVED`。

## 修复目标

1. 集群模式下，`EVAL`/`EVALSHA` 必须参与 slot 重定向检查，行为对齐 Redis 原生集群。
2. 多 key 脚本（`numkeys > 1`）需校验所有 KEYS 同 slot，否则返回 `-CROSSSLOT`。
3. 单 key 或 numkeys=0 的脚本按现有 KEYS[1]（或无 key）逻辑处理。
4. 不破坏单机模式（`clusterEnabled=false`）现有行为。

## 影响范围

- `luban-rds-server` 的 `RedisServerHandler`（集群重定向检查逻辑）
- 不涉及数据结构、持久化、复制等模块
- 不新增 public API
