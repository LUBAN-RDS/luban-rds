## ADDED Requirements

### Requirement: AOF 写入接入命令分发路径

所有写命令（修改数据的命令）执行后，系统 MUST 通过 `PersistService.recordCommand(byte[] respFrame)` 将命令的原始 RESP 字节记录到 AOF（当 `appendonly yes` 时）。`respFrame` 是命令的原始 RESP 序列化字节，与复制传播使用的 `rawRespFrame` 是同一份数据，保证二进制安全且与复制完全一致。读命令（如 `GET`、`EXISTS`、`TTL`、`TYPE`、`SCAN`）MUST NOT 被记录。`SELECT` 命令 MUST 被记录到 AOF 作为 db 上下文标记（与 Redis 一致），加载 AOF 时按 SELECT 切换当前 db，后续命令加载到对应 db。`FLUSHALL`/`FLUSHDB` MUST 被记录。`PersistService` 接口 MUST 提供 `recordCommand(byte[] respFrame)` 的 default 空实现，使非 AOF 实现（如 `RdbPersistService`）无需修改。AOF 写入 MUST 复用命令分发层的 `shouldPropagate` 判定（已有 `isReadOnlyCommand` 白名单），保证 AOF 记录与复制传播的写命令集合一致。

#### Scenario: 写命令被记录到 AOF

- **WHEN** `appendonly yes` 时客户端执行 `SET mykey hello`
- **THEN** AOF 文件追加 `SET mykey hello` 的原始 RESP 字节（`*3\r\n$3\r\nSET\r\n$5\r\nmykey\r\n$5\r\nhello\r\n`）
- **AND** 后续重启加载 AOF 能恢复 mykey=hello

#### Scenario: 读命令不记录

- **WHEN** 客户端执行 `GET mykey`
- **THEN** AOF 文件不追加任何内容

#### Scenario: SELECT 记录为 db 上下文标记

- **WHEN** 客户端执行 `SELECT 1` 后 `SET k1 v1`
- **THEN** AOF 记录 `SELECT 1` 的 RESP 字节（`*2\r\n$6\r\nSELECT\r\n$1\r\n1\r\n`）
- **AND** AOF 记录 `SET k1 v1` 的 RESP 字节
- **AND** 加载 AOF 时，先 SELECT 到 db 1，k1 被加载到 db 1

#### Scenario: FLUSHALL 被记录

- **WHEN** 客户端执行 `FLUSHALL`
- **THEN** AOF 文件追加 `FLUSHALL` 的 RESP 字节
- **AND** 重启加载后所有 db 为空

#### Scenario: 非 AOF 模式不记录

- **WHEN** `appendonly no` 时客户端执行 `SET mykey hello`
- **THEN** AOF 文件不追加任何内容（`recordCommand` 为 no-op）

#### Scenario: AOF 记录与复制传播一致

- **WHEN** 写命令执行后，`shouldPropagate` 返回 true
- **THEN** 该命令既被 `propagateCommand` 传播到复制 backlog，也被 `recordCommand` 记录到 AOF
- **AND** 两者使用同一份 `rawRespFrame` 字节，保证数据一致

### Requirement: RDB 持久化保存键的 TTL

RDB 序列化时，对于设置了过期时间的键，MUST 在键值对之前（type byte 之前，遵循 Redis 标准 RDB 格式）写入 expire opcode 和绝对过期时间戳。剩余 TTL 换算为绝对时间戳：`expireAt = System.currentTimeMillis() + pttl`。剩余 TTL < 3600000ms（1 小时）且为整秒时，使用 `0xFD` opcode + 4 字节秒级时间戳（小端序）；否则使用 `0xFC` opcode + 8 字节毫秒级时间戳（小端序）。加载 RDB 时，MUST 识别 0xFC/0xFD opcode，读取时间戳并换算回剩余 TTL（`remaining = expireAt - now`），若 `remaining <= 0` 则不加载该键（已过期），否则调用 `pexpire(db, key, remaining)` 恢复 TTL。无 expire opcode 的键按永久键加载（向后兼容旧格式）。

#### Scenario: 带 TTL 的键重启后保留过期时间

- **WHEN** 客户端执行 `SET k1 v1 EX 3600` 后触发 RDB 持久化
- **AND** 重启加载 RDB
- **THEN** k1 仍存在且 TTL 约为 3600 秒（扣除持久化到重启的耗时）

#### Scenario: 已过期键不复活

- **WHEN** RDB 持久化时键 k1 的剩余 TTL 为 1 秒
- **AND** 重启加载 RDB 时已过去 5 秒（`expireAt < now`）
- **THEN** k1 不被加载到内存

#### Scenario: 永久键向后兼容

- **WHEN** 加载不含 expire opcode 的旧 RDB 文件
- **THEN** 键按永久键加载，TTL = -1

#### Scenario: 毫秒级 TTL 用 0xFC

- **WHEN** 键的剩余 TTL 为 1500ms（非整秒）
- **THEN** RDB 写入 `0xFC` opcode + 8 字节毫秒时间戳

#### Scenario: 复制全量同步保留 TTL

- **WHEN** Master 执行 `performFullSync` 生成 RDB 快照，其中包含带 TTL 的键
- **THEN** 传输给 slave 的 RDB 包含 expire opcode
- **AND** slave 加载后该键的 TTL 被恢复

### Requirement: AOF rewrite 按数据类型生成重建命令

AOF rewrite 时，MUST 根据键的数据类型生成对应的重建命令，而非统一用 `SET key toString()`。各类型的重建命令：
- string：`SET key value`
- list：`RPUSH key v1 v2 ...`（一次性追加所有元素）
- set：`SADD key m1 m2 ...`
- hash：`HSET key f1 v1 f2 v2 ...`
- zset：`ZADD key s1 m1 s2 m2 ...`
- stream：逐条 `XADD key id field value` + `XGROUP CREATE` 恢复消费者组 + 扫描 PEL 结构逐条 `XCLAIM` 完整恢复 PEL（pending 消息的 consumer/idleTime/deliveryCount）

带 TTL 的键 MUST 在重建命令后追加 `PEXPIREAT key <timestampMs>`。所有字节数据 MUST 使用 ISO-8859-1 编码保证二进制安全。`BGREWRITEAOF` 命令 MUST 真正触发 rewrite，而非返回成功但不执行。

#### Scenario: Hash 键 rewrite 后保留字段

- **WHEN** 内存中有 hash 键 `myhash` 包含 `f1=v1, f2=v2`
- **AND** 触发 AOF rewrite
- **THEN** rewrite 产出文件包含 `*4\r\n$4\r\nHSET\r\n$6\r\nmyhash\r\n$2\r\nf1\r\n$2\r\nv1\r\n` 和 f2 的对应记录
- **AND** 重启加载后 `HGET myhash f1` 返回 `v1`

#### Scenario: List 键 rewrite 后保留顺序

- **WHEN** 内存中有 list 键 `mylist` 元素为 `[a, b, c]`
- **AND** 触发 AOF rewrite
- **THEN** rewrite 产出文件包含 `RPUSH mylist a b c`
- **AND** 重启加载后 `LRANGE mylist 0 -1` 返回 `[a, b, c]`

#### Scenario: ZSet 键 rewrite 后保留 score

- **WHEN** 内存中有 zset 键 `myzset` 成员 `m1=1.5, m2=2.5`
- **AND** 触发 AOF rewrite
- **THEN** rewrite 产出文件包含 `ZADD myzset 1.5 m1 2.5 m2`
- **AND** 重启加载后 `ZSCORE myzset m1` 返回 `1.5`

#### Scenario: 带 TTL 的键 rewrite 后保留过期时间

- **WHEN** 内存中有键 `k1` 值 `v1`，TTL 3000ms
- **AND** 触发 AOF rewrite
- **THEN** rewrite 产出文件包含 `SET k1 v1` 后追加 `PEXPIREAT k1 <timestampMs>`

#### Scenario: BGREWRITEAOF 真正执行

- **WHEN** 客户端发送 `BGREWRITEAOF`
- **THEN** 系统异步触发 `rewrite(memoryStore)`
- **AND** 返回 `+Background append only file rewriting started`
- **AND** rewrite 完成后 AOF 文件被替换为 compact 版本

#### Scenario: Stream 键 rewrite 后保留 PEL

- **WHEN** 内存中有 stream 键 `mystream`，消费者组 `g1` 有 pending 消息 `(id=1-0, consumer=c1, deliveryCount=2)`
- **AND** 触发 AOF rewrite
- **THEN** rewrite 产出文件包含 `XADD mystream 1-0 ...` 恢复数据
- **AND** 包含 `XGROUP CREATE mystream g1 ...` 恢复消费者组
- **AND** 包含 `XCLAIM mystream g1 c1 <idleTime> 1-0` 恢复 PEL
- **AND** 重启加载后 `XPENDING mystream g1` 返回该 pending 消息且 consumer 为 c1
