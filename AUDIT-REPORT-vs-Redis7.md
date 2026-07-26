# Luban-RDS 与 Redis 7.x 功能对比综合审计报告

> **审计目标**：对 Luban-RDS 项目的核心功能模块、数据结构实现、命令处理逻辑、持久化机制、集群功能、高可用方案及性能优化策略进行多轮次、系统性审计，严格参照 Redis 7.x 官方规范作为基准。
>
> **审计限制**：仅进行代码阅读与功能分析，未对项目代码进行任何修改、重构或优化操作。

---

## 0. 审计元信息

| 项 | 值 |
|---|---|
| 审计对象 | Luban-RDS（d:\workspaces_idea\igbp-luban-rds） |
| 对比基准 | Redis 7.x 官方文档与实现规范 |
| 审计轮次 | 3 轮递进式 |
| 审计日期 | 2026-07-26 |
| 审计范围 | 全部 11 个 Maven 模块 |
| 审计方式 | 静态代码阅读 + 子代理并行深度分析 |

### 审计轮次规划

| 轮次 | 范围 | 重点 |
|---|---|---|
| **R1** | 核心数据结构、命令处理、Lua/Stream/ACL | DefaultMemoryStore 内部实现、命令覆盖度、Lua 沙箱 |
| **R2** | 持久化（RDB/AOF）、复制、集群 | PSYNC2、RDB 格式、Cluster CROSSSLOT |
| **R3** | 服务器、Pub/Sub、事务、Sentinel、Spring/Benchmark | RESP3、HELLO、EXEC 原子性、Sentinel 选举 |

### 模块清单

| 模块 | 状态 | 主要类 |
|---|---|---|
| luban-rds-core | 已实现 | DefaultMemoryStore、各 *CommandHandler、LuaCommandHandler、Stream/ACL/SlowLog |
| luban-rds-protocol | 已实现 | RedisProtocolParser、Command、RespType |
| luban-rds-server | 已实现 | NettyRedisServer、RedisServerHandler、PubSubManager、MonitorManager |
| luban-rds-persistence | 已实现（严重缺陷） | RdbPersistService、AofPersistService |
| luban-rds-replication | 已实现（严重缺陷） | MasterReplicationManager、SlaveReplicationClient、ReplicationBacklog |
| luban-rds-cluster | 已实现 | ClusterCommandHandler、GossipProtocol、FailoverManager、SlotMigrationManager |
| luban-rds-sentinel | 部分实现 | Sentinel、FailoverManager（leader 选举为 stub） |
| luban-rds-client | 已实现 | NettyRedisClient |
| luban-rds-common | 已实现 | Constants、Utils、SlotUtils、TraceContext |
| luban-rds-spring-boot-starter | 已实现（属性透传缺陷） | LubanRdsAutoConfiguration |
| luban-rds-benchmark | 已实现 | LubanBenchmarkMain |

---

## 1. 执行摘要

Luban-RDS 是一个架构清晰、模块划分合理的 Java 版 Redis 协议兼容服务器，**协议层、基础数据结构、Stream 命令、Lua 沙箱、Cluster 拓扑与 Gossip 协议**的整体框架已经搭建完成，具备学习/参考级实现价值。但与 Redis 7.x 生产级标准对比，存在 **多处致命的正确性缺陷** 和 **大量功能缺失**：

**最严重的 10 个问题（按影响排序）：**

1. **Cluster 缺少 CROSSSLOT 检查** —— `MGET/MSET/DEL/EXISTS/RENAME/COPY` 等多键命令会**静默地把键写到错误节点**，造成数据损坏。([RedisServerHandler.java:2456-2460](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java#L2456-L2460))
2. **复制 Slave 状态机断裂** —— `SlaveReplicationClient.handlePsyncResponse` 是死代码，slave 永远无法进入 `ONLINE`，`callback.onOnline()` 在生产代码中从不被调用。([SlaveReplicationClient.java:296](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/SlaveReplicationClient.java#L296))
3. **AOF 持久化完全失效** —— `AofPersistService.recordCommand` 在整个代码库中**零调用点**，AOF 文件永不写入，重启即丢失全部数据。([AofPersistService.java:184](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/impl/AofPersistService.java#L184))
4. **`SLAVEOF host port` 是空操作** —— 只设置 readonly 标志，**不调用 `replicationCoordinator.startSlave()`**，复制永不启动。([ReplicationCommandHandler.java:79-99](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/handler/ReplicationCommandHandler.java#L79-L99))
5. **EXEC 非原子** —— 队列命令在 business 线程顺序执行，但**没有全局锁**，其他客户端命令可插入执行。([RedisServerHandler.java:1732-1815](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/janeluo/luban/rds/server/RedisServerHandler.java#L1732-L1815))
6. **ZSet 同分排序不确定** —— `ConcurrentHashMap.newKeySet()` 哈希桶序迭代，违反 Redis 字典序保证。([DefaultMemoryStore.java:2521-2628](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java#L2521-L2628))
7. **RDB 格式与 Redis 不兼容** —— 自定义长度编码、无 CRC64 校验、无 LZF 压缩、不保存 TTL、无 AUX 字段，`redis-check-rdb` 无法读取。([RdbPersistService.java:583-869](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/impl/RdbPersistService.java#L583-L869))
8. **BGSAVE / BGREWRITEAOF 是桩** —— 客户端看到 `+Background saving started` 但**什么都不发生**；`LASTSAVE` 返回当前时间。([CommonCommandHandler.java:429-437](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-core/src/main/java/com/janeluo/luban/rds/core/handler/CommonCommandHandler.java#L429-L437))
9. **PSYNC2 故障转移后部分同步失效** —— `resetReplId()` 仅在单测中调用，`replId2` 永远为 null，slave 提升后其他 slave 无法部分同步。([ReplicationBacklog.java:218-228](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/ReplicationBacklog.java#L218-L228))
10. **MIGRATE 非原子** —— 多键迁移逐个发送并删除源，连接中断会**永久丢失键**。([MigrateCommandHandler.java:226-266](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/migration/MigrateCommandHandler.java#L226-L266))

---

## 2. 第一轮审计：核心数据结构与命令处理

### 2.1 数据结构实现对比

| Redis 类型 | Redis 7.x 底层实现 | Luban-RDS 实现 | 对比结论 |
|---|---|---|---|
| String | sds（embstr/raw/int 编码） | `java.lang.String` | **不一致**：无编码优化，无 int 共享 |
| List | quicklist + listpack | `CopyOnWriteArrayList<String>` | **不一致**：每次写 O(n) 数组拷贝，不适合大 List |
| Hash | listpack / hashtable | `ConcurrentHashMap<String,String>` | **部分一致**：无 listpack 小哈希优化 |
| Set | intset / listpack / hashtable | `ConcurrentHashMap.newKeySet()` | **部分一致**：无 intset 整数集合优化 |
| ZSet | listpack / skiplist+dict | `ConcurrentHashMap` + `ConcurrentSkipListMap<Double, KeySetView>` | **不一致**：同分成员顺序不确定（严重） |
| Stream | listpack | `ConcurrentSkipListMap<StreamId,StreamEntry>` | **部分一致**：功能完整，无 listpack 紧凑编码 |
| Bitmap | String 复用 | **未实现** | **未实现** |
| HyperLogLog | String 复用 | **未实现** | **未实现** |
| Geo | ZSet 复用 | **未实现** | **未实现** |

### 2.2 关键正确性缺陷

#### 2.2.1 ZSet 同分排序不确定（严重）

`ZSetStore` 使用 `ConcurrentHashMap.newKeySet()` 存储同分成员集合，迭代顺序为哈希桶序，**违反 Redis 对同分成员按字典序保证**：

```java
// DefaultMemoryStore.java:2523-2527
final ConcurrentHashMap<String, Double> memberScores = new ConcurrentHashMap<>();
final ConcurrentSkipListMap<Double, ConcurrentHashMap.KeySetView<String, Boolean>> scoreMembers =
        new ConcurrentSkipListMap<>();
```

影响命令：`ZRANGE`、`ZRANK`、`ZREVRANGE`、`ZRANGEBYSCORE`、`ZPOPMIN/MAX`（同分时弹出非确定性成员）。

#### 2.2.2 SCAN 游标非逆向位（严重）

`scan()` 使用简单整数偏移 `newCursor = cursor + processed`，非 Redis 的 reverse-bit dict cursor：

- **非增量**：每次调用从头迭代并跳过 cursor 个元素，**O(n²) 总复杂度**
- **并发修改不安全**：`ConcurrentHashMap.keySet()` 弱一致迭代，键可被跳过或重复访问
- **glob→regex 转换错误**：`replace("{", "{")` 是空操作，`[...]` 字符类未支持

#### 2.2.3 ZRANK/ZREVRANK 为 O(n)

`zrank` 通过遍历 `scoreMembers.headMap(score, false)` 累加 size 实现，复杂度 O(n)；Redis 通过 skiplist span 元数据实现 O(log n)。

#### 2.2.4 BLPOP/BRPOP 非阻塞

`blpop`/`brpop` 注释明确写"非阻塞实现"，立即返回 null 而非等待 timeout。`XREAD BLOCK`、`XREADGROUP BLOCK` 同样忽略 block 参数。

#### 2.2.5 hincrby 竞态

`hincrby` 未加 per-key 锁（`incrby` 加了），并发 `HINCRBY` 同字段会丢失更新。

#### 2.2.6 keyVersions 无 GC

`ConcurrentHashMap<String, AtomicLong> keyVersions` 在键删除后**永不清理**，高 churn 工作负载下无界增长。

#### 2.2.7 主动过期策略弱

- 固定 100ms 周期，**全局 100 键上限**（跨所有 DB），大 DB 下跟不上
- 线性迭代 `keySet.keySet()`，非随机采样
- 无 Redis 的 `ACTIVE_EXPIRE_CYCLE_FAST/SLOW` 双模式
- 无 25% 过期率阈值继续采样机制

#### 2.2.8 淘汰策略缺失 LFU

支持的策略：`noeviction/allkeys-lru/volatile-lru/allkeys-random/volatile-random/volatile-ttl`。**缺失**：`allkeys-lfu/volatile-lfu`，无 `lfu-log-factor/lfu-decay-time`，无 `OBJECT FREQ`。

随机淘汰和 TTL 淘汰均为 **O(total keys)** 每次淘汰一个键，Redis 为 O(1) 采样。

### 2.3 命令覆盖度审计

#### 2.3.1 命令分派机制缺陷（严重）

**CONFIG / DEBUG / CLUSTER SLOTS 分派断裂**：`RdsCommandConstant` 定义了带空格的多词常量（`"CONFIG GET"`），但 `RedisProtocolParser` 只取 `args[0]`（即 `"CONFIG"`）作为分派键，导致：

- `CONFIG GET maxmemory` → `-ERR unknown command 'CONFIG'`
- `DEBUG SLEEP 5` → `-ERR unknown command 'DEBUG'`
- `COMMAND COUNT/INFO/GETKEYS` → 静默落入 `handleCommand` 返回 `*0\r\n`

**ACLCommandHandler 未注册**：`DefaultCommandHandler.registerHandlers()` 不构造 `ACLCommandHandler`（需 `ACLManager` 依赖），所有 `ACL *` 命令返回 `unknown command`。

**SelectCommandHandler 覆盖正确实现**：注册顺序导致 `SelectCommandHandler`（返回非 RESP 格式字符串）覆盖 `CommonCommandHandler` 的正确 `SELECT`。

#### 2.3.2 命令清单对比

| 类别 | Redis 7.x 命令数 | Luban-RDS 实现数 | 主要缺失 |
|---|---|---|---|
| String | ~25 | 15 | `SETEX/GETEX/GETDEL/INCRBYFLOAT/LCS/COPY/SUBSTR`、`SET` 的 `EXAT/PXAT/KEEPTTL/GET` 选项 |
| List | ~27 | 12 | `LINSERT/LPOS/LMOVE/BLMOVE/LMPUSH/LPUSHX/RPUSHX`、`LPOP/RPOP count` 参数 |
| Hash | ~18 | 13 | `HINCRBYFLOAT/HRANDFIELD/HSTRLEN`、`HSCAN NOVALUES` |
| Set | ~18 | 9 | `SPOP/SRANDMEMBER/SMOVE/SINTERSTORE/SUNIONSTORE/SDIFFSTORE/SINTERCARD`（SPOP 等在 KNOWN_COMMANDS 列出但无 handler 实现） |
| ZSet | ~35 | 16 | `ZRANGEBYLEX/ZREVRANGEBYLEX/ZLEXCOUNT/ZRANGESTORE/ZUNIONSTORE/ZINTERSTORE/ZDIFFSTORE/ZUNION/ZINTER/ZDIFF/ZINTERCARD/ZRANDMEMBER/ZMPOP/BZPOPMIN/BZPOPMAX/ZMSCORE`、`ZADD NX/XX/GT/LT/CH/INCR`、`ZRANGE BYSCORE/BYLEX/REV/LIMIT` 统一形式 |
| Stream | ~22 | 14 | `XGROUP CREATECONSUMER/XSETID`、`XINFO STREAM FULL`、`XCLAIM IDLE/TIME/RETRYCOUNT` 实际不生效 |
| Key | ~25 | 8 | `EXPIREAT/PEXPIREAT/PERSIST/RENAME/RENAMENX/COPY/DUMP/RESTORE/OBJECT/TOUCH/UNLINK/MOVE/RANDOMKEY/KEYS/SORT/SORT_RO`、`EXPIRE NX/XX/GT/LT` |
| Server | ~40 | ~15 | `SAVE/SHUTDOWN/RESET/COMMAND DOCS/COMMAND LIST/LATENCY/FAILOVER`、`CONFIG` 仅支持 ~10 个自定义参数 |
| Bit | 7 | 0 | **完全未实现** |
| Geo | 8 | 0 | **完全未实现** |
| HyperLogLog | 3 | 0 | **完全未实现** |
| Pub/Sub | 9 | 7 | `PUBSUB/SPUBLISH` |
| Cluster | ~25 | 19 | `LINKS/RESET/COUNTFAILUREREPORTS/SHARDS/FAILOVER ABORT` |

#### 2.3.3 实现良好的部分

- **Stream 命令覆盖最完整**：`XADD` 支持 `NOMKSTREAM/MAXLEN/MINID/LIMIT/~`，`XREAD/XREADGROUP` 支持 `COUNT/BLOCK/NOACK`，`XCLAIM` 支持 `JUSTID/FORCE`，`XAUTOCLAIM`、`XPENDING` 双格式、`XINFO` 三视图
- **Lua 沙箱** 可配置禁用模块（os/io/package/luajava），支持 blocked-functions 列表，cjson/struct 库
- **错误消息格式** 大多匹配 Redis（`-ERR`、`-WRONGTYPE`、`-OOM`、`-BUSYGROUP`、`-NOGROUP`、`-NOSCRIPT`、`-NOTBUSY`）

### 2.4 Lua 脚本审计

| 项 | 状态 | 备注 |
|---|---|---|
| Lua 引擎 | LuaJ（Lua 5.1 + 5.2 兼容垫片） | 非 Lua 5.1 原生 |
| EVAL/EVALSHA/SCRIPT LOAD/EXISTS/FLUSH/KILL | ✅ | |
| 脚本缓存 | ✅ ConcurrentHashMap + SHA1 | |
| `redis.call/pcall/error_reply/status_reply/sha1hex` | ✅ | |
| `redis.log/replicate_commands/set_repl` | ❌ | |
| **脚本原子性** | ❌ **严重** | 脚本在独立 `new Thread` 执行，**其他客户端命令可插入**，违反 Redis 原子性保证 |
| **效果复制** | ❌ | 无 effects-based 复制，`EVALSHA` 在 slave 可能因缓存缺失失败 |
| Functions（FCALL/FUNCTION） | ❌ | Redis 7.0+ 完全未实现 |
| `cjson.decode` 返回数字为字符串 | ❌ 偏差 | Redis 返回数字类型 |
| `redis.pcall` 错误表格式 | ❌ 偏差 | 返回 `{1="err",2=msg}` 而非 `{err=msg}` |
| `SCRIPT KILL` 用 `Thread.interrupt` | ⚠️ | LuaJ 可能不响应，死循环脚本无法终止 |

### 2.5 Stream 审计

| 项 | 状态 | 备注 |
|---|---|---|
| XADD NOMKSTREAM/MAXLEN/MINID/LIMIT | ✅ | 但 `~` 近似裁剪标志**被解析但不生效**（始终精确裁剪） |
| XADD maxLen 转 int | ⚠️ | `maxLen.intValue()` 在 maxLen > 2^31 时溢出 |
| XGROUP CREATE/DESTROY/DELCONSUMER/SETID | ✅ | **CREATECONSUMER 缺失**（Redis 6.2+） |
| XREAD/XREADGROUP COUNT/BLOCK/NOACK | ✅ | BLOCK 经 `BlockingResult` 处理 |
| XCLAIM IDLE/TIME/RETRYCOUNT/FORCE/JUSTID | ⚠️ | 选项被解析但 **IDLE/TIME/RETRYCOUNT 未透传到 store**，不生效 |
| XAUTOCLAIM | ✅ | 返回三元素数组，匹配 Redis 7.x |
| XPENDING 摘要+详细 | ✅ | 详细格式 4 字段匹配 |
| XINFO STREAM/GROUPS/CONSUMERS | ✅ | **缺 Redis 7.x 字段**：`max-deleted-entry-id/entries-added/recorded-first-entry-id/entries-read/lag/bytes-per-message`，无 `FULL` 选项 |
| Stream ID 生成 | ✅ | ms-sequence，时间回滚处理正确 |
| XSETID 独立命令 | ❌ | Redis 6.2+ 未实现 |

### 2.6 ACL 审计

| 项 | 状态 |
|---|---|
| 用户/密码（SHA256）/规则 | ✅ |
| 命令分类（+@read/-@write 等） | ✅ 20 类（缺 `@bitmap/@cluster/@search`） |
| `ACL SETUSER/DELUSER/GETUSER/LIST/CAT/GENPASS/WHOAMI/HELP` | ✅（但 **WHOAMI 硬编码返回 "default"**） |
| `ACL LOAD/SAVE/LOG/USERS/DRYRUN` | ❌ stub 或缺失 |
| key-pattern（`~/%R~/%W~`） | ✅ |
| **selectors（Redis 7.0+）** | ❌ `clearselectors` 为 TODO no-op |
| **明文密码额外存储** | ❌ 偏差 | Redis 仅存哈希，本实现 `Set<String> passwords` 存明文 |
| **ACLCommandHandler 未注册** | ❌ 严重 | 所有 ACL 命令在生产中返回 `unknown command` |
| `aclfile` 持久化 | ❌ |

### 2.7 SlowLog 审计

| 项 | 状态 | 备注 |
|---|---|---|
| SLOWLOG GET/LEN/RESET | ✅ | |
| SLOWLOG HELP | ❌ | |
| `slowlog-log-slower-than` 默认 10000us | ✅ | 匹配 Redis |
| `slowlog-max-len` 默认 128 | ✅ | 匹配 Redis |
| 条目格式（6 元素） | ✅ | 匹配 Redis 5.0+ |
| args 截断 | ❌ | Redis 截断到 128 参数，本实现无截断 |

---

## 3. 第二轮审计：持久化、复制、集群

### 3.1 持久化审计

#### 3.1.1 RDB 持久化

| 项 | Redis 7.x | Luban-RDS | 对比结论 |
|---|---|---|---|
| RDB 版本 | 11 | 9 | **不一致**（注释误称"6.0+"） |
| 文件格式 | 标准 RDB | 自定义 | **不一致**：长度编码、类型 opcode、double 编码均不匹配，`redis-check-rdb` 无法读取 |
| CRC64 校验 | 强制 | **伪造**（写入 `System.currentTimeMillis()`） | **不一致**：损坏文件静默加载 |
| LZF 压缩 | 支持 | 不支持 | **未实现** |
| BGSAVE fork/COW | fork 子进程 | 线程，无快照隔离 | **不一致**：扫描期间并发写产生撕裂/缺失 |
| SAVE 命令 | 支持 | 未实现 | **未实现** |
| BGSAVE 命令 | 异步保存 | **桩**：返回 `+Background saving started` 但不调用 persist | **不一致** |
| `save N M` 规则 | 多规则 | 单一 `rdb-save-interval 60` | **不一致** |
| 所有数据类型 | listpack/quicklist 等新编码 | 仅遗留 0x00-0x05 | **部分一致** |
| **过期时间保存** | 是（0xFC/0xFD opcode） | **否** | **不一致**：TTL 在 RDB 中丢失，重载后键变永久 |
| **AUX 字段** | redis-ver/bits/ctime/used-mem/repl-id/repl-offset | **无** | **不一致**：slave 重启无法部分同步 |
| `stop-writes-on-bgsave-error` | 是 | 否 | **未实现** |
| `rdbchecksum/rdbcompression` 配置 | 是 | 否 | **未实现** |
| `dbfilename/appendfilename` 配置生效 | 是 | **否**（硬编码 `dump.rdb`/`appendonly.aof`） | **不一致** |

#### 3.1.2 AOF 持久化（严重失效）

| 项 | Redis 7.x | Luban-RDS | 对比结论 |
|---|---|---|---|
| `appendfsync` always/everysec/no | 支持 | **配置被解析后丢弃**，仅用 `aof-fsync-interval` 秒数 | **不一致** |
| AOF 写入路径 | `feedAppendOnlyFile` 每条写命令 | **`recordCommand` 零调用点** | **未实现**（AOF 文件永不写入） |
| AOF rewrite | fork | 同步线程，**无调用者** | **不一致**（死代码） |
| 重写数据类型保持 | RPUSH/SADD/HSET/ZADD 逐元素 | **全部 `SET key toString()`** | **不一致**：hash/list/set/zset 重载后变 string |
| 自动重写阈值 | `auto-aof-rewrite-percentage/min-size` | 无 | **未实现** |
| RDB-preamble AOF | 默认是 | 否 | **未实现** |
| **Redis 7.x 多部分 AOF** | manifest + base RDB + incr AOF | 单文件 | **未实现** |
| `aof-load-truncated` | 是 | 否（解析失败即中止） | **未实现** |
| **AOF 加载解析器** | RESP 完整解析 | `BufferedReader.readLine()` 逐行 | **不一致**（每行只收到 `*3` 或 `$3`，命令永远无法完整解析） |
| BGREWRITEAOF 命令 | 异步重写 | **桩**：返回 `+Background append only file rewriting started` 但不做任何事 | **不一致** |
| redis-check-aof 工具 | 提供 | 未提供 | **未实现** |

**数据丢失场景：**
1. AOF-only 模式：重启即丢全部数据（recordCommand 从不调用）
2. RDB-only 模式：崩溃丢失最近 `rdb-save-interval`（默认 60s）的写入
3. TTL 在 RDB 中丢失，重载后键变永久
4. 已过期键可能被扫描到 RDB 中，重载后"复活"
5. `BGSAVE` 桩让运维误以为备份成功，实际未保存
6. `LASTSAVE` 返回当前时间，备份监控失效
7. 复制 full sync 覆盖 master 的 `dump.rdb`
8. 无 `repl-id/repl-offset` AUX，slave 重启永远 full sync

#### 3.1.3 混合持久化

- **RDB-preamble AOF**：未实现
- **Redis 7.x 多部分 AOF**：未实现
- `persist-mode both`：AOF 部分为空，仅 RDB 生效
- `persist-mode mixed`：文档提及但工厂拒绝，抛 `IllegalArgumentException`

### 3.2 复制审计

#### 3.2.1 PSYNC2 实现

| 项 | 状态 | 备注 |
|---|---|---|
| `+FULLRESYNC replid offset` | ✅ | |
| `+CONTINUE [replid]` | ✅ | |
| backlog 环形缓冲区 | ✅ | `byte[]` + `ReentrantReadWriteLock`，默认 1MB |
| `replId/replId2` 双 ID | ✅ 字段存在 | **`resetReplId()` 仅在单测调用**，生产中 `replId2` 永远 null |
| replid 生成 | 32 随机 hex + 8 个 `0` 填充 | 偏差：Redis 用 CSPRNG 生成 40 随机 hex |

#### 3.2.2 复制状态机（致命断裂）

```
DISCONNECTED → CONNECTING → HANDSHAKE_PING → HANDSHAKE_AUTH → 
HANDSHAKE_REPLCONF_PORT/IP/CAPA → FULL_SYNC → LOADING_RDB → 
PARTIAL_SYNC → ONLINE
```

**致命缺陷**：`SlaveReplicationClient.handleResponse` 的 switch（191-214 行）**没有 case 调用 `handlePsyncResponse`**。`handlePsyncResponse`（296 行）是死代码，grep 确认零调用点。

**后果链：**
1. slave 发送 `PSYNC` 后永远停留在 `HANDSHAKE_REPLCONF_CAPA` 状态
2. `+FULLRESYNC`/`+CONTINUE` 响应被错误路由到 `handleReplconfResponse`，只检查 `+OK` 后丢弃
3. `handleSyncData` 永不触发，RDB 数据不加载
4. `callback.onOnline()` 永不调用（仅单测调用）
5. `sendAck()` 永不发送，master 永远不知道 slave 偏移量
6. `WAIT` 命令永远返回 0
7. 复制滞后永远显示为最大值

#### 3.2.3 Full Sync 数据丢失（严重）

- `performFullSync` 期间 slave 非 online，`propagateCommand` 把命令写入 backlog 但**不发送给同步中的 slave**
- RDB 传输完成后**无 backlog 重放**，期间所有写入对该 slave 永久丢失
- `slave.updateOffset(0)` 后永不更新，master 记录 slave 偏移为 0

#### 3.2.4 其他复制缺陷

| 缺陷 | 严重度 |
|---|---|
| `REPLCONF` 三连发不等待 `+OK` 响应，状态机错位 | 严重 |
| `handleSyncData` 把 RDB 字节数加到 replicationOffset，破坏偏移语义 | 严重 |
| RDB 二进制数据用 UTF-8 解码为 String 做状态分派 | 严重 |
| `RdbSnapshotGenerator` 用 `persistSync` 写生产 `dump.rdb`，与正常持久化竞态 | 严重 |
| `isGenerating` AtomicBoolean 限制**仅一个并发 full sync**，第二个 slave 永远等待 | 严重 |
| `checkSlaveTimeout` 定义但从不调用，`repl-timeout` 不生效 | 严重 |
| `min-replicas-to-write/min-replicas-max-lag` 未实现 | 严重 |
| Diskless sync 未实现 | 中 |
| `WAIT 0` 返回 0 而非当前 ACK 副本数 | 中 |
| `INFO replication` 的 `lag=` 报告字节数而非秒数 | 中 |
| `REPLCONF ip-address` 硬编码 `127.0.0.1` | 中 |
| `ReplicationBacklog.clear()` 重置 `masterReplOffset` 为 0（Redis 永不重置） | 中 |
| Lua `SCRIPT LOAD` 不传播到 slave，`EVALSHA` 在 slave 可能失败 | 中 |
| 主动过期键的 `DEL` 不传播到 slave，slave 保留过期键 | 中 |
| `ReplicationIntegrationTest` 被 `@Disabled`，端到端失效未被捕获 | 严重 |

### 3.3 集群审计

#### 3.3.1 拓扑与槽位

- 40 字符 hex 节点 ID（SHA1 生成）✅
- `nodes.conf` 兼容行格式 ✅
- 原子写入（temp + `Files.move(ATOMIC_MOVE)`）✅
- CRC16-CCITT（poly 0x1021）✅
- `{tag}` 哈希标签 ✅（7 个边界 case 与 Redis 一致）
- 16384 槽位 BitSet ✅

**偏差**：
- **双重槽所有权表**：`ClusterConfig.slotAssignment[]`（权威）与 `DefaultSlotManager.slotOwners[]`（本地）不同步，代码注释承认"slotManager 的 slotOwners[] 仅启动时同步一次，后续 Gossip 更新不传播，导致 slave 返回 CLUSTERDOWN"
- 无 `cluster-require-full-coverage` 配置
- 无 `cluster-allow-reads-when-down` 配置
- IPv6 不支持

#### 3.3.2 CROSSSLOT 缺失（致命）

`extractKeyFromCommand` 对 `MGET/MSET/MSETNX/DEL/EXISTS` **只返回第一个键**，`checkSlotAndRedirect` 只校验该键：

```java
// RedisServerHandler.java:2456-2460
if ("MGET".equals(cmd) || "MSET".equals(cmd) || "MSETNX".equals(cmd) || 
    "DEL".equals(cmd) || "EXISTS".equals(cmd)) {
    return args.length >= 2 ? args[1] : null;
}
```

**后果**：`MSET key1 key2 val1 val2` 中 `key2` 属于其他节点时，命令仍执行，**key2 被写到错误节点，静默数据损坏**。CROSSSLOT 检查仅对 `EVAL/EVALSHA` 实现（证明团队知道该约束但未扩展到原生多键命令）。

影响命令：`MGET/MSET/MSETNX/DEL/EXISTS/RENAME/RENAMENX/COPY/UNLINK/TOUCH/SDIFFSTORE/SINTERSTORE/SUNIONSTORE/ZUNIONSTORE/ZINTERSTORE/BITOP/SORT STORE/XREAD 多流/BLPOP/BRPOP 多键`

#### 3.3.3 Failover 缺陷

| 项 | 状态 | 备注 |
|---|---|---|
| Raft 选举（slave 广播 AUTH_REQUEST，master 投票） | ✅ | |
| `currentEpoch` 原子递增 | ✅ | |
| 选举超时 `2 * nodeTimeout` | ✅ | |
| 手动 `CLUSTER FAILOVER [FORCE\|TAKEOVER]` | ✅ | |
| 仲裁预检（master 多数可达） | ✅ | |
| 同 epoch 冲突按 nodeId 字典序解决 | ✅ | |
| **复制偏移量不参与选举 tiebreak** | ❌ 严重 | `FailoverAuthRequestMessage` 传 `0L`，任何 slave 都能赢，**陈旧 slave 可能被提升，丢失已提交写入** |
| **手动 failover 不广播 `FailoverResult`** | ❌ 严重 | 其他节点仅通过后续 Gossip 得知，**双主窗口** |
| `votesCast` 仅内存 | ❌ 严重 | master 重启丢失投票历史，可能同 epoch 重复投票 |
| `cluster-failover-grace-period` 默认 0ms | ⚠️ | 所有 slave 同时开始选举，仅靠 0-500ms jitter 区分 |
| `broadcastAuthRequest` fire-and-forget | ⚠️ | ACK 全丢则选举静默超时，Redis 每 cron 重发 |

#### 3.3.4 MIGRATE 缺陷

- **非原子多键迁移**：逐个发送 + 删除源，连接中断**永久丢键**
- **Java 序列化** 而非 RDB 格式：迁移值只能被同 JDK + 同类版本的 Luban-RDS 节点反序列化
- `ObjectInputFilter` 允许 `java.util.*` 包前缀，存在已知反序列化 gadget 链风险
- `finishMigration` 不 bump configEpoch，陈旧节点可通过 epoch 仲裁重申旧槽所有权
- `MIGRATE` 的 dest-db 参数被解析但忽略，所有键落到 DB 0

#### 3.3.5 Sharded Pub/Sub（未真正分片）

`SSUBSCRIBE/SUNSUBSCRIBE` handler 存在，但：
- **`SPUBLISH` 完全缺失**（grep 无匹配）
- 无 cluster 端 sharded channel 实现
- 无 `SHARDCHANNELS/SHARDNUMSUB` cluster 消息类型
- `SSUBSCRIBE` 仅本地生效，节点 A 订阅的 shard channel 收不到节点 B 发布的消息
- **完全违背 Redis 7.x Sharded Pub/Sub 设计意图**

#### 3.3.6 缺失的 CLUSTER 子命令

`LINKS/RESET/COUNTFAILUREREPORTS/SHARDS/FAILOVER ABORT` 未实现。

#### 3.3.7 非标准错误消息

`-CLUSTERDOWN Slot owner not found` / `-CLUSTERDOWN No cluster config` 是非标准字符串，严格客户端（Redisson）可能无法识别。Redis 统一使用 `-CLUSTERDOWN Hash slot not served`。

---

## 4. 第三轮审计：服务器、Pub/Sub、事务、Sentinel

### 4.1 RESP 协议

| 项 | 状态 | 备注 |
|---|---|---|
| RESP2 | ✅ | 但 `Command.args` 是 `String[]` 非 `byte[][]`，二进制安全有损 |
| RESP3 类型 | 部分 | MAP/SET/ATTRIBUTE/NULL/DOUBLE/BOOLEAN/BIG_NUMBER 支持；**缺 verbatim string(`=`) 和 push type(`>`)** |
| **HELLO 命令** | 部分 | 仅 `HELLO 3` 经预解析器处理；无 `HELLO 2`、无 `HELLO` 无参、无 `AUTH/SETNAME` 选项；响应 `id` 用 Netty channel id 字符串非数字 |
| **RESP3 与 RESP2 混用** | ❌ 严重 | INFO/EXEC/SCAN/TIME/CONFIG 等手工构造 RESP2 字符串，绕过协议版本感知序列化器，RESP3 客户端收到 RESP2 帧 |
| Pipelining | ✅ | |
| **Inline commands** | ❌ | 非 `*` 开头的输入被当作不完整数据，telnet 客户端永远阻塞 |

### 4.2 Netty 服务器

| 项 | 状态 | 备注 |
|---|---|---|
| Boss/Worker/Business 三层线程模型 | ✅ | Boss=1，Worker/Business 可配置 |
| 业务命令在 businessGroup 执行 | ✅ | 不阻塞 I/O 线程 |
| `PooledByteBufAllocator` | ✅ | `use-pool` 可配置 |
| 泄露检测级别可配置 | ✅ | |
| **`TCP_NODELAY`** | ❌ | 未设置，Nagle 算法未禁用 |
| `SO_BACKLOG` | ✅ | 默认 511 |
| `SO_KEEPALIVE` | ✅ 布尔 | 但间隔（默认 300s）未应用到 socket |
| **`IdleStateHandler`** | ❌ | `timeout` 配置存储但从不使用，idle 连接永不回收 |
| **SSL/TLS** | ❌ | 无 `tls-port/tls-cert-file/tls-key-file` |
| `maxclients` | ❌ | 配置存在但 `channelActive` 不校验，超额连接仍接受 |
| **Protected mode** | ❌ | 未实现 |
| **SIGTERM 处理** | ❌ | 无 `Runtime.addShutdownHook`，`kill` 不保存 RDB/nodes.conf |

### 4.3 Pub/Sub

| 项 | 状态 | 备注 |
|---|---|---|
| SUBSCRIBE/UNSUBSCRIBE/PUBLISH/PSUBSCRIBE/PUNSUBSCRIBE | ✅ | |
| glob 模式匹配 | ✅ | `?/*/[abc]/[a-z]/\` 转义 |
| **`PUBSUB` 命令** | ❌ | CHANNELS/NUMSUB/NUMPAT/SHARDCHANNELS/SHARDNUMSUB 全缺失 |
| **`SPUBLISH`** | ❌ | 见 §3.3.5 |
| **Keyspace 通知** | 部分 | 仅 `StringCommandHandler` 12 处发布 `__keyspace@__`/`__keyevent@__`；DEL/EXPIRE/HSET/LPUSH/SADD/ZADD/XADD 等均不发布；eviction/expiration 不发布 |
| **`notify-keyspace-events` 配置** | ❌ | 通知永远开启，无 KEA/Kg/$/l/s/h/z/x/e/A/d 过滤 |
| 订阅者上限 | ❌ | 无 `pubsub-channel-maxlen` |

### 4.4 事务

| 项 | 状态 | 备注 |
|---|---|---|
| MULTI/EXEC/DISCARD/WATCH 基本流程 | ✅ | |
| 嵌套 MULTI 拒绝 | ✅ | |
| WATCH 在 MULTI 中拒绝 | ✅ | |
| WATCH 1000 键上限 | ✅ | |
| 脏 WATCH 返回 `*-1\r\n` | ✅ | |
| EXECABORT 错误 | ✅ | |
| **EXEC 原子性** | ❌ 严重 | 无全局锁，队列命令在 business 线程顺序执行，**其他客户端命令可插入** |
| EXEC 结果编码 | ⚠️ | 手工拼接 RESP，`str` 直接拼而 length 用 `ISO_8859_1` 字节数，可能不匹配；忽略 RESP3 |
| `isRespFormatted` 误判 | ⚠️ | 任何以 `+ - : $ *` 开头的数据值被误判为已格式化 RESP |
| EXEC 内特殊重实现 INCR/SET 等 | ⚠️ | 绕过 `commandHandler.handle`，跳过 keyspace 通知 |

### 4.5 Monitor

| 项 | 状态 | 备注 |
|---|---|---|
| MONITOR 命令 | ✅ | 支持 `MONITOR [DB dbid] [MATCH pattern]` 扩展 |
| 多 monitor 客户端 | ✅ | `ConcurrentHashMap<Channel, MonitorContext>` |
| `monitor-max-clients` 配置 | ✅ | |
| 格式匹配 Redis | ✅ | `"seconds.microseconds" [db addr] "cmd" "arg1"` |
| 历史回放 | ✅ | Redis 6.0+ |
| 时间戳用 `System.currentTimeMillis()` | ⚠️ | 非单调，NTP 跳变 |
| 异步广播队列满丢事件 | ⚠️ | Redis 阻塞处理线程，本实现丢弃 |
| 历史回放过滤不一致 | ⚠️ | `passesStringFilter` 按 db 前缀字符串匹配，与 live `shouldSend` 按 command 模式不一致 |

### 4.6 CLIENT 命令

| 子命令 | 状态 |
|---|---|
| KILL | ⚠️ 桩：返回 `+OK` 但不杀连接 |
| LIST | ⚠️ 桩：返回硬编码 mock 字符串 |
| GETNAME | ❌ 永远返回 `$-1\r\n`（尽管 `ClientInfo.name` 存在） |
| SETNAME | ❌ 桩：返回 `+OK` 但不实际设置 `ClientInfo.name` |
| PAUSE | ❌ 桩：不暂停任何东西 |
| ID/INFO/UNPAUSE/REPLY/TRACKING/TRACKINGINFO/CACHING/GETREDIR/NO-EVICT/NO-TOUCH/SETINFO | ❌ 全缺失 |
| 裸 `CLIENT` 命令 | ❌ 抛 `ArrayIndexOutOfBoundsException` |

### 4.7 INFO 命令准确性

| 字段 | 状态 |
|---|---|
| `redis_mode` | ❌ 硬编码 `standalone`，集群模式不报告 `cluster` |
| `role` | ❌ 硬编码 `master`，不反映 slave 状态 |
| `connected_slaves` | ❌ 硬编码 0 |
| `master_replid` | ❌ 全 0 |
| `repl_backlog_*` | ❌ 全 0 |
| `blocked_clients` | ❌ 硬编码 0（`BlockingRequestManager` 存在但未查询） |
| `instantaneous_ops_per_sec` | ❌ 硬编码 0 |
| `cmdstat_*` | ❌ 11 个命令全 `calls=0,usec=0` 桩 |
| `expires/avg_ttl` | ❌ 永远 0 |
| `hz/configured_hz` | ❌ 硬编码 10 |
| `gcc_version` | ❌ 硬编码 `0.0.0`（JVM 上无意义） |
| `used_memory` | ✅ 来自 `DefaultMemoryStore.getUsedMemory()` |
| `used_memory_rss` | ✅ `runtime.totalMemory()` |

### 4.8 CONFIG 命令

- `CONFIG GET` 仅支持 ~10 个自定义参数（lua-*/slowlog/maxmemory/monitor-max-clients），**缺失** maxclients/timeout/tcp-keepalive/tcp-backlog/databases/requirepass/appendonly/appendfsync/save/dir/dbfilename/cluster-enabled/cluster-node-timeout/replicaof/masterauth/notify-keyspace-events 等 ~100 个标准参数
- `CONFIG SET` 对**未知参数返回 `+OK`** 而非 `-ERR Unsupported CONFIG parameter`
- `CONFIG REWRITE` 是桩
- `CONFIG RESETSTAT` ✅

### 4.9 Sentinel 审计

| 项 | 状态 | 备注 |
|---|---|---|
| 实现级别 | 部分实现（~2400 行） | 非 skeleton，但多处 stub |
| SENTINEL 命令覆盖 | ~55% | 13/23 子命令；缺 FLUSHCONFIG/SIMULATE-FAILURE/INFO-CACHE/MYID/PENDING-SCRIPTS/DEBUG/HELP/REPLICAS |
| 监控间隔默认值 | ✅ | PING 1s/INFO 10s/health 1s/hello 2s/down-after 30s 全匹配 Redis |
| s_down 检测 | ⚠️ | 用 `lastPongTime` 而非 Redis 的 `last_ok_ping_reply` |
| **O_DOWN/quorum** | ❌ 严重 | `votedMasterDown` 字段仅由 `QuorumChecker.updateSentinelVote` 写入，**该方法零调用点**；多 sentinel 部署中 downVotes 永远为 1，O_DOWN 永不达成（除非 quorum=1） |
| **Leader 选举** | ❌ 严重 stub | `tryBecomeLeader` 无条件返回 true 并自任 leader，未实现 sentinel 间投票 |
| Slave 选举算法 | ✅ | priority→offset→run_id 三级排序匹配 Redis |
| Slave 提升 | ⚠️ | 发 `SLAVEOF NO ONE` 后 `Thread.sleep(1000)` 盲等，不验证角色切换 |
| **配置持久化** | ❌ | `configFile` 字段未用，无 reader/writer，无 FLUSHCONFIG，重启丢失全部运行时状态 |
| **Sentinel 间发现** | ❌ 严重 | 仅 PUBLISH `__sentinel__:hello`，**无 SUBSCRIBE handler**，发现单向且失效 |
| **客户端通知** | ❌ | 无 `+sdown/+odown/+switch-master` 等 pub/sub 通知 |
| 通知脚本 | ❌ | 无 `notification-script/client-reconfig-script` |
| **RESP 数组编码错误** | ❌ 严重 | `SentinelServerHandler.sendResponse` 把 `*N\r\n...` 字符串作为 `SimpleStringRedisMessage` 发送，MASTERS/SLAVES/SENTINELS 等数组返回命令全部畸形 |
| `updateMasterInfo` 设 state 为 null | ❌ | 后续 `master.getState().getName()` NPE |
| TILT 模式 | ❌ | 硬编码 `sentinel_tilt:0` |
| AUTH 到监控的 master | ❌ | `authPassword` 字段未用 |

### 4.10 Spring Boot Starter

| 项 | 状态 | 备注 |
|---|---|---|
| 嵌入式服务器启动 | ✅ | `EmbeddedRedisServer` 在 autoconfig 构造器中启动 |
| `RedisClient` bean | ✅ | 带重试（5×500ms） |
| **属性透传** | ❌ 严重 | `LubanRdsProperties` 定义 20 个属性，但 `new EmbeddedRedisServer(properties.getPort())` **仅传 port**，其余 17 个（bossThreads/workerThreads/businessThreads/maxConnections/idleTimeout/password/databases/rdbEnabled/aofEnabled/luaScriptTimeout 等）全部装饰性，对嵌入式服务器无影响 |
| `RedisConnectionFactory` 适配 | ❌ | 不替换 Spring Data Redis 的 Lettuce/Jedis |
| `LubanRdsBootstrapAutoConfigurationRegistrar` | ❌ 死代码 | 未实现 `ImportSelector`，未注册，永不调用 |
| Sentinel 自动配置 | ❌ | 不自动配置 Sentinel |

### 4.11 Benchmark

| 项 | 状态 | 备注 |
|---|---|---|
| 吞吐量（ops/sec） | ✅ | |
| 平均延迟 | ✅ | |
| **p50/p95/p99 百分位** | ❌ | redis-benchmark 有 |
| 直方图 | ❌ | |
| 工作负载 | 8/11 接入 CLI | SET/GET/INCR/LPUSH/LRANGE/HSET/HGET/SADD；Latency/MultiThread/MemoryFragmentation/MemoryStability 未接入 |
| Pipeline 模式 | ✅ | `--pipeline N` |
| 连接池 | ✅ | `--pool N` |
| 随机键空间 `-r` | ❌ | |
| CSV/quiet 输出 | ❌ | |
| Cluster 模式 | ❌ | cluster/ 目录有完整套件但未通过 CLI 暴露 |
| 内存监控 | ✅ | 每 5s 轮询 `INFO memory`，redis-benchmark 无 |
| HTML/Markdown 报告 | ⚠️ | 实现但未通过 CLI 暴露 |

---

## 5. 功能匹配度总览

### 5.1 功能匹配度评分

| 维度 | 匹配度 | 说明 |
|---|---|---|
| **协议（RESP2）** | 85% | 基本完整，二进制安全有损 |
| **协议（RESP3）** | 40% | 类型不全，HELLO 不完整，与 RESP2 混用 |
| **String 命令** | 60% | 缺 SET 高级选项、GETEX/GETDEL/INCRBYFLOAT/LCS |
| **List 命令** | 45% | 缺 LINSERT/LPOS/LMOVE/BLMOVE/LMPUSH/LPUSHX/RPUSHX/count 参数 |
| **Hash 命令** | 70% | 缺 HINCRBYFLOAT/HRANDFIELD/HSTRLEN |
| **Set 命令** | 50% | 缺 SPOP/SRANDMEMBER/SMOVE/*STORE/SINTERCARD |
| **ZSet 命令** | 45% | 缺 LEX 系列/*STORE 系列/UNION/INTER/DIFF/ZMPOP/BZPOP/ZRANDMEMBER/ZMSCORE/ZADD 选项 |
| **Stream 命令** | 85% | 最完整，缺 XGROUP CREATECONSUMER/XSETID/XINFO FULL |
| **Key 命令** | 30% | 缺大量：EXPIREAT/PEXPIREAT/PERSIST/RENAME/COPY/DUMP/RESTORE/OBJECT/TOUCH/UNLINK/MOVE/RANDOMKEY/KEYS/SORT |
| **Bit 命令** | 0% | 完全未实现 |
| **Geo 命令** | 0% | 完全未实现 |
| **HyperLogLog 命令** | 0% | 完全未实现 |
| **Lua 脚本** | 60% | EVAL/EVALSHA/SCRIPT 完整，但**非原子**、缺 redis.log/set_repl、无 Functions |
| **事务** | 70% | 流程完整，**EXEC 非原子** |
| **Pub/Sub** | 60% | 基础完整，缺 PUBSUB/SPUBLISH/可配置通知 |
| **RDB 持久化** | 20% | 格式不兼容、无校验/压缩/TTL/AUX、BGSAVE 是桩 |
| **AOF 持久化** | 5% | **recordCommand 零调用**，加载解析器坏掉，rewrite 死代码 |
| **复制** | 30% | **slave 状态机断裂**，端到端失效 |
| **集群** | 65% | 框架完整，**CROSSSLOT 缺失致数据损坏**，sharded pubsub 未真正分片 |
| **Sentinel** | 35% | **leader 选举 stub、quorum 失效、配置不持久化** |
| **ACL** | 50% | 框架完整，**handler 未注册**，缺 selectors/LOAD/SAVE/LOG |
| **监控（MONITOR）** | 85% | 基本完整 |
| **服务器配置** | 30% | CONFIG 仅 ~10 参数，大量标准配置缺失 |
| **连接管理** | 25% | CLIENT 多为桩，maxclients/timeout/protected-mode 未实现 |
| **Spring 集成** | 60% | 嵌入式可用，**17/20 属性装饰性** |

### 5.2 数据结构实现对比表

| Redis 7.x 特性 | Luban-RDS 状态 | 影响 |
|---|---|---|
| embstr/raw/int 编码优化 | ❌ | 内存浪费，小键开销大 |
| intset 整数集合 | ❌ | 纯整数 set 内存翻倍 |
| listpack 紧凑编码 | ❌ | 小 hash/list/set/zset 内存翻倍 |
| quicklist | ❌ | List 大对象性能差（CopyOnWriteArrayList O(n) 写） |
| skiplist + span | ❌ | ZRANK O(n) 而非 O(log n) |
| 字典渐进式 rehash | ❌ | Java ConcurrentHashMap 内部 rehash，行为不同于 Redis |
| 共享对象池 | ❌ | 无 small int 共享 |
| 主动碎片整理 | ❌ | `defragment()` 仅 `System.gc()`，非真实碎片整理 |
| LFU 淘汰 | ❌ | 无 allkeys-lfu/volatile-lfu |
| 内存精确计量 | ⚠️ | 硬编码常量估算，ZSet 双重计费，碎片率永远 0 |

---

## 6. 潜在问题清单（按严重度）

### 6.1 致命（Critical，数据损坏/丢失）

| # | 问题 | 文件:行 |
|---|---|---|
| C1 | Cluster 缺 CROSSSLOT 检查，多键命令静默写错节点 | RedisServerHandler.java:2456-2460 |
| C2 | 复制 slave 状态机断裂，`handlePsyncResponse` 死代码 | SlaveReplicationClient.java:296 |
| C3 | AOF `recordCommand` 零调用，AOF 模式全丢数据 | AofPersistService.java:184 |
| C4 | `SLAVEOF host port` 不启动复制，仅设 readonly 标志 | ReplicationCommandHandler.java:79-99 |
| C5 | Full sync 期间命令不缓冲不重放，slave 永久丢失期间写入 | MasterReplicationManager.java:292-308 |
| C6 | Full sync 后 slave offset 永远为 0，`WAIT` 永远返回 0 | MasterReplicationManager.java:217 |
| C7 | MIGRATE 非原子，连接中断永久丢键 | MigrateCommandHandler.java:226-266 |
| C8 | 复制偏移量不参与 failover 选举，陈旧 slave 可能被提升 | FailoverManager.java:221-225 |
| C9 | 手动 failover 不广播 FailoverResult，双主窗口 | FailoverManager.java:408-413 |
| C10 | RDB 不保存 TTL，重载后键变永久 | RdbPersistService.java:401 |
| C11 | AOF rewrite 把所有类型 `SET key toString()`，类型丢失 | AofPersistService.java:569 |
| C12 | ZSet 同分排序不确定 | DefaultMemoryStore.java:2521-2628 |

### 6.2 严重（High，功能失效/安全）

| # | 问题 | 文件:行 |
|---|---|---|
| H1 | EXEC 非原子，无全局锁 | RedisServerHandler.java:1732-1815 |
| H2 | Lua 脚本非原子，独立线程执行 | LuaCommandHandler.java:421 |
| H3 | BGSAVE/BGREWRITEAOF 是桩，运维误判备份成功 | CommonCommandHandler.java:429-437 |
| H4 | LASTSAVE 返回当前时间 | CommonCommandHandler.java:423 |
| H5 | `resetReplId()` 生产中从不调用，failover 后部分同步失效 | ReplicationBacklog.java:218-228 |
| H6 | `repl-timeout` 不生效（checkSlaveTimeout 零调用） | MasterReplicationManager.java:331 |
| H7 | ACLCommandHandler 未注册，所有 ACL 命令失效 | DefaultCommandHandler.java:53-71 |
| H8 | CONFIG/DEBUG/COMMAND 分派断裂 | RdsCommandConstant.java:128-155 |
| H9 | Sentinel leader 选举是 stub | FailoverManager.java:135-145 |
| H10 | Sentinel quorum 失效（votedMasterDown 零写入） | QuorumChecker.java:136-148 |
| H11 | Sentinel 配置不持久化，重启丢状态 | SentinelConfig.java:75 |
| H12 | Sentinel RESP 数组编码畸形 | SentinelServerHandler.java:120-122 |
| H13 | Sentinel 间发现单向（PUBLISH 无 SUBSCRIBE） | NodeMonitor.java:183-217 |
| H14 | maxclients 不强制 | RedisServerHandler.java:828-837 |
| H15 | timeout 不强制 | RedisServerHandler.java:225 |
| H16 | protected-mode 未实现 | - |
| H17 | 无 TLS/SSL | - |
| H18 | 无 SIGTERM shutdown hook | NettyRedisServer.java |
| H19 | XCLAIM 的 IDLE/TIME/RETRYCOUNT 不透传 | StreamGroupCommandHandler.java:617-625 |
| H20 | `ObjectInputFilter` 允许 `java.util.*` 包前缀 | SlotMigrationManager.java:530-545 |
| H21 | Lua 沙箱禁用时暴露 `luajava`（任意 Java 调用） | LuaCommandHandler.java:328-330 |

### 6.3 中等（Medium，偏差/缺失）

| # | 问题 |
|---|---|
| M1 | SCAN 非逆向位游标，O(n²)，并发修改不安全 |
| M2 | BLPOP/BRPOP/XREAD BLOCK 非阻塞 |
| M3 | ZRANK/ZREVRANK O(n) |
| M4 | List 用 CopyOnWriteArrayList，写 O(n) |
| M5 | hincrby 竞态（无锁） |
| M6 | keyVersions 无 GC |
| M7 | 主动过期策略弱（100 键上限、线性扫描） |
| M8 | 随机/TTL 淘汰 O(total keys) |
| M9 | RESP3 与 RESP2 在 INFO/EXEC/SCAN 混用 |
| M10 | HELLO 仅 `HELLO 3`，无 AUTH/SETNAME |
| M11 | 缺 verbatim string/push type RESP3 类型 |
| M12 | 无 inline commands |
| M13 | Keyspace 通知仅 String 命令发布 |
| M14 | 无 `notify-keyspace-events` 配置 |
| M15 | `PUBSUB` 命令缺失 |
| M16 | Sharded Pub/Sub 未真正分片 |
| M17 | INFO 多字段硬编码/桩 |
| M18 | CONFIG 仅 ~10 参数，未知参数静默接受 |
| M19 | CLIENT 子命令多为桩 |
| M20 | `finishMigration` 不 bump configEpoch |
| M21 | 双重槽所有权表不同步 |
| M22 | 集群缺 14 个配置选项 |
| M23 | Sentinel 缺 8 个子命令 |
| M24 | Spring Boot 17/20 属性装饰性 |
| M25 | Lua `cjson.decode` 数字返回字符串 |
| M26 | Lua `pcall` 错误表格式偏差 |
| M27 | AOF 加载解析器逐行读，命令永远无法完整解析 |
| M28 | RDB 无 AUX 字段，slave 重启无法部分同步 |
| M29 | `min-replicas-to-write/max-lag` 未实现 |
| M30 | Diskless sync 未实现 |

### 6.4 低（Low，风格/小问题）

| # | 问题 |
|---|---|
| L1 | `cluster_enabled:1` 硬编码 |
| L2 | `*.bak` 测试文件存在于源码树 |
| L3 | IPv6 不支持 |
| L4 | `TCP_NODELAY` 未设置 |
| L5 | `gcc_version` 硬编码 `0.0.0` |
| L6 | `hz/configured_hz` 硬编码 10 |
| L7 | `WAIT 0` 返回 0 而非当前 ACK 数 |
| L8 | `INFO replication` `lag=` 报字节数而非秒数 |
| L9 | `REPLCONF ip-address` 硬编码 `127.0.0.1` |
| L10 | replid 仅 32 随机 hex + 8 个 `0` |
| L11 | `ReplicationBacklog.clear()` 重置 offset（Redis 永不重置） |
| L12 | Benchmark 无百分位延迟 |
| L13 | Benchmark cluster 套件未通过 CLI 暴露 |
| L14 | `LubanRdsBootstrapAutoConfigurationRegistrar` 死代码 |
| L15 | `FailoverProcess.java` 整文件死代码 |
| L16 | `toHumanReadable` 死代码 |
| L17 | SelectCommandHandler 覆盖正确 SELECT 实现 |
| L18 | `ClientCommandHandler` 裸 CLIENT 抛数组越界 |
| L19 | `handleEcho` 多参数用空格拼接（Redis 要求单参数） |
| L20 | `MEMORY PURGE` 永远返回 `:1` |

---

## 7. 性能对比分析

### 7.1 性能瓶颈

| 操作 | Redis 7.x 复杂度 | Luban-RDS 复杂度 | 影响 |
|---|---|---|---|
| ZRANK | O(log n) | O(n) | 大 ZSet 排名查询慢 |
| ZREVRANK | O(log n) | O(n) + zrank 调用 | 翻倍慢 |
| LPUSH/RPUSH | O(1) | O(n)（CopyOnWriteArrayList） | 大 List 写入慢 |
| LPOP/RPOP | O(1) | O(n)（数组拷贝） | 大 List 弹出慢 |
| LSET | O(n) | O(n) | 同 |
| LINSERT | O(n) | 未实现 | - |
| SCAN（每页） | O(1) | O(n)（跳过 cursor 项） | 大 DB SCAN 总 O(n²) |
| DBSIZE | O(1) | O(n)（遍历 keySet） | 大 DB 慢 |
| 随机淘汰 | O(1) | O(total keys) | 淘汰慢 |
| TTL 淘汰 | O(1) | O(total keys) | 淘汰慢 |
| 主动过期采样 | O(1) per sample | O(1) per key 但线性扫描 | 大 DB 跟不上 |
| MSET | O(n) | O(n) + `synchronized(store)` | 全局序列化 |

### 7.2 内存效率

| 项 | Redis 7.x | Luban-RDS | 倍数 |
|---|---|---|---|
| 小 String（3 字节） | ~56 字节（embstr） | ~152 字节（BASE_ENTRY_OVERHEAD 128 + STRING_OVERHEAD 24） | ~2.7x |
| 小 Hash（2 字段） | listpack ~30 字节 | ConcurrentHashMap + 2 Entry ~256 字节 | ~8x |
| 小 Set（3 整数） | intset ~28 字节 | ConcurrentHashMap.KeySetView ~200 字节 | ~7x |
| 小 ZSet（3 成员） | listpack ~50 字节 | 2x ConcurrentHashMap + ConcurrentSkipListMap ~600 字节 | ~12x |
| 键字符串存储 | 1 份 | 3 份（keySet + slotToKeys + Caffeine key） | 3x |

### 7.3 并发模型

| 项 | Redis 7.x | Luban-RDS |
|---|---|---|
| 主线程模型 | 单线程（命令串行） | 多线程（business pool） |
| 命令原子性 | 天然（单线程） | 需显式锁（多数未加） |
| 锁粒度 | 无锁（单线程） | 1024 stripe per-key（部分操作） |
| I/O 模型 | io_threads（多线程 I/O） | Netty Worker 多线程 I/O |
| 后台任务 | bio 线程池 | ScheduledExecutor + persistExecutor |

**注意**：Luban-RDS 的多线程模型在**无锁正确性**方面有优势，但也带来竞态风险（如 hincrby、EXEC 非原子、set 检查-然后-行动竞态）。

---

## 8. 测试覆盖度评估

| 模块 | 测试文件数 | 端到端测试 | 关键缺陷未被覆盖 |
|---|---|---|---|
| core | 多 | 部分 | ZSet 同分顺序、SCAN 竞态、BLPOP 非阻塞 |
| persistence | 1（factory） | ❌ | AOF recordCommand 零调用、RDB 格式不兼容 |
| replication | 12 | `@Disabled` | slave 状态机断裂（端到端测试被禁用） |
| cluster | 多 | 部分（格式正则匹配） | CROSSSLOT 缺失（仅 EVAL 测过）、非原子 MIGRATE |
| sentinel | 9 | ❌ | leader 选举 stub、quorum 失效（无 FailoverManager 测试） |
| server | 多 | 部分 | EXEC 非原子、SLAVEOF no-op |
| protocol | 3 | 部分 | RESP3 混用 |

AGENTS.md 报告的复制覆盖率（ReplicationBacklog 100%、ReplicationCommandHandler 95.5%）**不等于正确性**：断裂的 `SlaveReplicationClient.handlePsyncResponse` 不在覆盖率表中，且唯一的端到端测试 `ReplicationIntegrationTest` 被 `@Disabled`。

---

## 9. 增量审计轮次验证

### 9.1 R2 对 R1 发现的验证

| R1 发现 | R2 验证结果 |
|---|---|
| BLPOP/BRPOP 非阻塞 | ✅ 确认：`MemoryStore.blpop` 立即返回 null，`XREAD BLOCK` 同样 |
| ZSet 同分顺序不确定 | ✅ 确认：`scoreMembers` 用 `ConcurrentHashMap.KeySetView`，无字典序 |
| SCAN 非逆向位 | ✅ 确认：`newCursor = cursor + processed` |
| ACL handler 未注册 | ✅ 确认：`DefaultCommandHandler.registerHandlers()` 不构造 ACLCommandHandler |
| CONFIG 分派断裂 | ✅ 确认：`commandHandlers.get("CONFIG")` 返回 null |
| Lua 非原子 | ✅ 确认：`new Thread(task, "luban-rds-lua").join(timeout)` |

### 9.2 R3 对 R1/R2 发现的验证

| R1/R2 发现 | R3 验证结果 |
|---|---|
| AOF recordCommand 零调用 | ✅ 确认：全代码库 grep 零调用点 |
| Slave 状态机断裂 | ✅ 确认：`handlePsyncResponse` 死代码，`ReplicationIntegrationTest` 被 `@Disabled` |
| CROSSSLOT 缺失 | ✅ 确认：`extractKeyFromCommand` 仅对 EVAL/EVALSHA 实现 cross-slot 检查 |
| SLAVEOF no-op | ✅ 确认：`handleSlaveof` 仅 `setSlave(true)`，不调用 `startSlave` |
| EXEC 非原子 | ✅ 确认：`handleExecCommand` 顺序执行无全局锁 |
| BGSAVE 是桩 | ✅ 确认：`handleBgsave` 返回 `+Background saving started\r\n` 但不调用 persist |
| RESP3 混用 | ✅ 确认：INFO/EXEC 手工拼 RESP2 字符串 |

### 9.3 新发现（R3 增量）

- Sentinel leader 选举是 stub（`tryBecomeLeader` 无条件返回 true）
- Sentinel quorum 失效（`votedMasterDown` 零写入）
- Sentinel RESP 数组编码畸形（`SimpleStringRedisMessage` 发 `*N\r\n...`）
- Spring Boot 17/20 属性装饰性
- Benchmark 无百分位延迟
- `LubanRdsBootstrapAutoConfigurationRegistrar` 死代码
- `FailoverProcess.java` 整文件死代码
- 无 TLS/SSL
- 无 SIGTERM shutdown hook
- `protected-mode` 未实现

---

## 10. 结论与建议

### 10.1 总体评估

Luban-RDS 是一个**架构设计清晰、模块划分合理**的 Java 版 Redis 协议服务器实现，具备以下**亮点**：

- ✅ 完整的模块化设计（11 个 Maven 模块）
- ✅ Netty NIO + 三层线程模型（Boss/Worker/Business）
- ✅ Stream 命令覆盖最完整（85% 匹配 Redis 7.x）
- ✅ Lua 沙箱可配置
- ✅ Cluster Gossip + Failover 框架完整
- ✅ CRC16 槽位算法与 Redis 字节级一致
- ✅ 漏桶监控、SlowLog、TraceContext 等基础设施齐全
- ✅ Spring Boot 嵌入式集成可用

但与 Redis 7.x 生产级标准对比，存在**多处致命缺陷**和**大量功能缺失**，**不可直接用于生产环境**：

- ❌ 12 个致命问题（数据损坏/丢失）
- ❌ 21 个严重问题（功能失效/安全）
- ❌ 30 个中等问题（偏差/缺失）
- ❌ 20 个低级问题（风格/小问题）
- ❌ Bit/Geo/HyperLogLog 三大命令族完全未实现
- ❌ 持久化端到端失效（AOF 零写入、RDB 不兼容）
- ❌ 复制端到端失效（slave 状态机断裂）
- ❌ Cluster 数据完整性风险（CROSSSLOT 缺失）
- ❌ Sentinel 高可用失效（leader 选举 stub、quorum 失效）

### 10.2 优先修复建议（按影响排序）

**P0（数据安全，必须立即修复）：**
1. Cluster CROSSSLOT 检查（C1）
2. 复制 slave 状态机 `handlePsyncResponse` 接入（C2）
3. AOF `recordCommand` 接入写路径（C3）
4. `SLAVEOF host port` 调用 `startSlave`（C4）
5. Full sync 期间命令缓冲与重放（C5）
6. Full sync 后 slave offset 更新（C6）
7. MIGRATE 原子化（C7）
8. Failover 选举使用复制偏移量（C8）
9. 手动 failover 广播 FailoverResult（C9）
10. RDB 保存 TTL（C10）
11. AOF rewrite 类型保持（C11）
12. ZSet 同分字典序（C12）

**P1（核心功能，尽快修复）：**
- EXEC 全局锁（H1）
- Lua 脚本原子性（H2）
- BGSAVE/BGREWRITEAOF 实际执行（H3）
- `resetReplId` 在 failover 时调用（H5）
- `repl-timeout` 强制（H6）
- ACLCommandHandler 注册（H7）
- CONFIG/DEBUG 分派修复（H8）
- Sentinel leader 选举实现（H9）
- Sentinel quorum 修复（H10）
- Sentinel 配置持久化（H11）
- maxclients/timeout/protected-mode（H14/H15/H16）
- SIGTERM shutdown hook（H18）

**P2（功能补全，中期规划）：**
- 补齐 ZSet LEX/STORE/UNION/INTER/DIFF 系列
- 补齐 List LINSERT/LPOS/LMOVE/LMPUSH
- 补齐 Key EXPIREAT/PEXPIREAT/PERSIST/RENAME/COPY/OBJECT
- 实现 Bit/Geo/HyperLogLog 命令族
- 实现 Functions（FCALL/FUNCTION）
- 实现 `PUBSUB` 命令
- 实现 Sharded Pub/Sub 真正分片
- 完善 RESP3（verbatim/push 类型、HELLO 完整选项）
- 完善 CONFIG 参数（标准 Redis 参数）
- 完善 CLIENT 子命令
- 完善 INFO 准确性
- 实现 `notify-keyspace-events` 配置
- 实现 TLS/SSL

**P3（性能优化，长期规划）：**
- ZSet 改用 skiplist + span（ZRANK O(log n)）
- List 改用 quicklist 等价结构
- 小对象 listpack/intset 编码优化
- LFU 淘汰策略
- SCAN 逆向位游标
- 主动过期随机采样 + 25% 阈值
- 真实内存碎片整理
- Diskless replication

### 10.3 适用场景建议

**当前状态适用：**
- 学习 Redis 协议与内部原理的教学/参考项目
- 单机开发/测试环境的轻量级 KV 缓存（不依赖持久化、不依赖复制、不依赖集群）
- Spring Boot 应用内嵌的本地缓存（接受重启丢数据）

**当前状态不适用：**
- 生产环境数据存储（持久化失效）
- 高可用部署（复制/集群/Sentinel 多处失效）
- 多键跨槽操作（CROSSSLOT 数据损坏）
- 数据一致性要求高的场景（EXEC 非原子、Lua 非原子）
- 安全敏感场景（无 TLS、无 protected-mode、Lua 沙箱禁用暴露 luajava）

---

## 附录 A：审计文件清单

### R1 读取文件
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/MemoryStore.java`
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/store/DefaultMemoryStore.java`（4384 行）
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/handler/` 全部 17 个 handler 文件
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/acl/` 全部 5 个文件
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/stream/` 全部 8 个文件
- `luban-rds-core/src/main/java/com/janeluo/luban/rds/core/slowlog/` 全部 2 个文件

### R2 读取文件
- `luban-rds-persistence/src/main/java/com/janeluo/luban/rds/persistence/` 全部 5 个文件
- `luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/` 全部 18 个文件
- `luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/` 全部 ~40 个文件

### R3 读取文件
- `luban-rds-server/src/main/java/com/janeluo/luban/rds/server/` 全部 8 个文件
- `luban-rds-protocol/src/main/java/com/janeluo/luban/rds/protocol/` 全部 3 个文件
- `luban-rds-sentinel/src/main/java/com/janeluo/luban/rds/sentinel/` 全部 ~20 个文件
- `luban-rds-spring-boot-starter/src/main/java/com/janeluo/luban/rds/autoconfigure/` 全部 4 个文件
- `luban-rds-benchmark/src/main/java/com/janeluo/luban/rds/benchmark/LubanBenchmarkMain.java`

**审计过程中未修改任何文件。**

---

## 附录 B：Redis 7.x 主要新特性对比

| Redis 7.x 特性 | Luban-RDS 状态 |
|---|---|
| Functions（FCALL/FUNCTION） | ❌ 未实现 |
| Sharded Pub/Sub（SPUBLISH/SSUBSCRIBE） | ❌ 未真正分片 |
| Multi-part AOF（manifest + base + incr） | ❌ 未实现 |
| ACL Selectors | ❌ 未实现 |
| Command introspection（COMMAND DOCS/LIST） | ❌ 未实现 |
| LATENCY history | ❌ 未实现 |
| Cluster Shards | ❌ 未实现 |
| Cluster Links | ❌ 未实现 |
| Cluster preferred endpoint type | ❌ 未实现 |
| Cluster hostname announcement | ❌ 未实现 |
| Cluster TLS | ❌ 未实现 |
| RESP3 verbatim string / push type | ❌ 未实现 |
| XADD empty fields | ✅ 实现 |
| XAUTOCLAIM | ✅ 实现 |
| ZADD COUNT（7.4） | ❌ 未实现 |
| SINTERCARD | ❌ 未实现 |
| ZINTERCARD | ❌ 未实现 |
| LMOVE/BLMOVE | ❌ 未实现 |
| LMPUSH/BLMPUSH | ❌ 未实现 |
| HRANDFIELD | ❌ 未实现 |
| ZRANDMEMBER | ❌ 未实现 |
| SRANDMEMBER count | ❌ 未实现 |
| GETEX/GETDEL | ❌ 未实现 |
| COPY | ❌ 未实现 |
| LCS | ❌ 未实现 |
| RESET（7.2） | ❌ 未实现 |
| FAILOVER（master→slave handoff） | ❌ 未实现 |

---

## 附录 C：对比结论统计

| 结论类别 | 数量 | 占比 |
|---|---|---|
| 完全一致 | ~15 | 12% |
| 部分一致 | ~45 | 36% |
| 不一致 | ~40 | 32% |
| 未实现 | ~25 | 20% |
| **合计** | **~125** | **100%** |

---

*报告生成时间：2026-07-26 14:30 Asia/Shanghai*
*审计工具：静态代码阅读 + 并行子代理深度分析*
*审计限制：仅代码阅读与功能分析，未修改任何项目文件*

---

## 修复说明（fix-p0-data-safety-redis7，2026-07-27）

本报告标记的 **P0 级（Critical）缺陷 C1–C12 已全部修复**，变更名 `fix-p0-data-safety-redis7`，分支同名。

### 修复清单

| 缺陷 | 子系统 | 修复要点 | 主要提交 |
|------|--------|---------|---------|
| C1 跨槽多键命令无 CROSSSLOT 校验 | cluster | `RedisServerHandler.extractKeysFromCommand` 返回所有键列表，新增 `checkCrossSlot(List<String>)`，dispatch 先 CROSSSLOT 后 MOVED 后 ASK；EVAL 仍由 `checkCrossSlotForScript` 处理保持向后兼容 | c09903e |
| C2 slave 状态机 PSYNC 路由死代码 | replication | 新增 `HANDSHAKE_PSYNC` 状态，`handleResponse` 路由到 `handlePsyncResponse`；`handleSyncData` 完成后 `transitionToOnline` | a529d46, ec14154 |
| C3 AOF `recordCommand` 接入缺失 | persistence | `PersistService.recordCommand(byte[])` 二进制安全接口 + `RedisServerHandler` 在命令分发与 EXEC 路径同位置调用；`CompositePersistService` 委派 | ec07bd0, d9a3858, bc0ef7f |
| C4 `SLAVEOF` 不触发复制 | replication | `ReplicationController` 接口抽象 + `ReplicationCoordinator` 实现，`handleSlaveof` 调用 `startSlave/stopSlave` | 3acaa07 |
| C5 全量同步窗口期写入丢失 | replication | 快照枚举完成后记录 `snapshotOffset`，`performFullSync` 在 SYNCING 状态下重放 backlog 窗口命令到固定 offset，再置 ONLINE | e852780, 3c0604a |
| C6 slave `replicationOffset=0` 导致 WAIT 失败 | replication | 修复 C2+C5 后下游症状消失；`SlaveReplicationClient` 正确维护 offset，新增验证测试 | 3c0604a |
| C7 `MIGRATE` 多键非原子 | cluster | `migrateMultipleKeys` 改两阶段：先 dump+transfer 全部键并记录 ACK，仅在全部成功时统一 DEL；任一失败源不删；64MB 上限校验；COPY 模式不删 | 18c834d |
| C8 failover 选举不携带真实 offset | cluster | `ReplicationLifecycleListener.getReplicationOffset()` default 方法 + `ReplicationCoordinator` 实现；`broadcastAuthRequest` 填真实 offset；`onAuthRequest` 首投即定（rank=0 简化，slave offset gossip 传播不在 C8 范围） | 9fed095 |
| C9 手动 failover 不广播 FailoverResult | cluster | 抽取 `broadcastFailoverResult(newMaster, oldMaster)` 共用方法；`performManualFailover` 补 `masterNode.setConfigEpoch` 并广播；`performFailoverAndBroadcast` 移除内联重复广播 | 278b294 |
| C10 RDB 不持久化 TTL | persistence | `writeExpireTime` 在 type byte 前写 0xFC(ms)/0xFD(sec) opcode（Redis 标准）；load 端 `pendingExpireAtMs` 暂存 + `applyExpireIfAny`；已过期不复活 | 29d99e5, 31a604e |
| C11 AOF rewrite 全类型用 SET 丢类型 | persistence | `writeRebuildCommand` 按 `type()` 分支（string/list/set/hash/zset/stream）；stream 逐条 XADD + XGROUP CREATE + XCLAIM FORCE 完整恢复 PEL；带 TTL 追加 PEXPIREAT；ISO-8859-1 二进制安全。**实现期还修复了 rewrite Windows 文件锁丢数据、load 二进制安全解析两项关键缺陷** | 21b8774, c3f3208 |
| C12 ZSet 同分非字典序 | core | `ZSetStore.scoreMembers` 值类型 `KeySetView` -> `ConcurrentSkipListSet<String>`；`zpopmax/zrevrange` 用 `descendingSet()`；`estimateMemorySize` 单成员估算 64L -> 72L | 252851c |

### 验证

- 全模块编译通过（`mvn clean install -DskipTests` BUILD SUCCESS）。
- 新增/更新的单元测试：
  - 复制：`ReplicationIntegrationTest`（重启启用）
  - 持久化：`AofRecordCommandTest`、`AofWriteHookTest`、`RdbTtlPersistenceTest`、`AofRewriteByTypeTest`（14 项）
  - 集群：`ClusterCrossSlotTest`（9 项）、`MigrateAtomicityTest`（5 项）、`FailoverOffsetElectionTest`（5 项）、`ManualFailoverBroadcastTest`（6 项）
  - 核心：`ZSetOrderingTest`（12 项）
- 测试基线对比：在 `834f205`（修复前基线）上 `MonitorManagerTest`/`PubSubManagerTest`/`ClusterStartupTest`/`TestClusterTest`/`ACLIntegrationTest`/`ACLPerformanceTest`/`ACLPermissionCheckerTest` 等已存在的失败用例与本变更无关，本次修复未引入新增失败。

### 范围说明

- 仅修复 P0（C1–C12），不含 H1–H21 严重级与 M1–M30 中等级问题。
- 3.18（rank 退避）按设计 §2.9 允许的 rank=0 简化处理，slave offset gossip 传播属于后续工作。
- C7 选用 Option B（不新增批量 wire 消息），源端两阶段 + 全有/全无 DEL 已满足原子性语义。
