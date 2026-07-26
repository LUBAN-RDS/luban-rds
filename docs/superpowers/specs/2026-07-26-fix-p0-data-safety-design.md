---
comet_change: fix-p0-data-safety-redis7
role: technical-design
canonical_spec: openspec
---

# Design Doc: fix-p0-data-safety-redis7

> 本文档是 Superpowers 技术设计 RFC，基于 OpenSpec 产物（proposal/design/specs/tasks）做深度实现设计。
> OpenSpec delta spec 是能力规格的事实源，本文档不重复需求定义，聚焦实现细节、边界条件、测试策略。
> 上游交接包：`openspec/changes/fix-p0-data-safety-redis7/.comet/handoff/design-context.md`

## 1. 设计背景

Luban-RDS 经三轮审计对照 Redis 7.x 发现 12 个 P0 致命缺陷（C1-C12），经四个并行子代理逐行核实全部确认存在。本变更一次性修复全部 12 个缺陷，跨 replication/persistence/cluster/core 四个子系统。OpenSpec `design.md` 已有 11 个高层决策（D1-D11），本文档细化其实现方案。

## 2. 深度实现设计

### 2.1 复制状态机改造（C2，对应 D1）

#### 实现方案：状态机 + 回调驱动（方案 A）

`SlaveReplicationClient` 握手改造为纯异步事件驱动，不阻塞任何线程：

```
sendReplConf() 发 PORT
  -> setState(HANDSHAKE_REPLCONF_PORT)
  -> 启动 5s scheduled timeout
  -> handleReplconfResponse 收到 +OK
     -> 取消 timeout
     -> 发 IP
     -> setState(HANDSHAKE_REPLCONF_IP)
     -> 启动 5s timeout
     -> handleReplconfResponse 收到 +OK
        -> 取消 timeout
        -> 发 CAPA
        -> setState(HANDSHAKE_REPLCONF_CAPA)
        -> 启动 5s timeout
        -> handleReplconfResponse 收到 +OK
           -> 取消 timeout
           -> startPsync()
           -> setState(HANDSHAKE_PSYNC)
           -> 启动 5s timeout
           -> handlePsyncResponse 收到 +FULLRESYNC/+CONTINUE
              -> 取消 timeout
              -> 解析 replid/offset
              -> callback.onFullSync / onPartialSync
              -> setState(FULL_SYNC / PARTIAL_SYNC)
```

#### 边界条件

- **超时处理**：任一阶段的 5s timeout 触发 -> `setState(DISCONNECTED)` -> 清理 pending timeout -> 调度重连（`SlaveReplicationService` 已有重连逻辑）。不引入完整 `repl-timeout` 机制（P0 不修 H6），仅保证握手不永久卡死。
- **错误响应**：若 master 返回 `-ERR` 而非 `+OK` -> 日志记录 + `setState(DISCONNECTED)` + 重连。
- **REPLCONF ACK 心跳**：slave 进入 ONLINE 后，`SlaveReplicationService` 的心跳调度器（196 行 `if (isOnline()) client.sendAck()`）周期发送 `REPLCONF ACK <offset>`。C2 修好后 `isOnline()` 为真，该路径自动激活。

#### 涉及文件

- `SlaveReplicationClient.java`：新增 `HANDSHAKE_PSYNC` 状态处理、改造 `sendReplConf`/`handleReplconfResponse`/`handlePsyncResponse`、新增 timeout 机制
- `ReplicationState.java`：新增 `HANDSHAKE_PSYNC` 枚举值
- `SlaveReplicationService.java`：验证 `onOnline`/`sendAck` 回调链可达

### 2.2 Full sync 窗口期重放（C5，对应 D2）

#### offset 记录时机

`snapshotBaseOffset` 在 `RdbSnapshotGenerator.generateTempRdbFile` 返回后（`persistSync` 完成、RDB 文件落盘）记录。需改 `generateAndTransfer` 接口：

**接口改造**：`generateAndTransfer` 返回一个结果对象（或通过回调）包含 `transferredBytes` 和 `snapshotOffset`：

```java
// 新增内部类或用现有 TransferProgressTracker 携带
public static class SnapshotResult {
    long transferredBytes;
    long snapshotOffset;  // RDB 落盘时刻的 backlog.getMasterReplOffset()
}
```

`generateTempRdbFile` 内 `rdbPersistService.persistSync(memoryStore)` 返回后立即记录 `snapshotOffset = backlog.getMasterReplOffset()`，然后复制文件、传输。这样 `snapshotOffset` 精确对应"RDB 枚举完成"的时刻。

#### 重放流程

```
performFullSync 异步任务:
  1. SnapshotResult result = snapshotGenerator.generateAndTransfer(...)
  2. if (result.transferredBytes > 0):
       long replayEndOffset = backlog.getMasterReplOffset()
       byte[] windowData = backlog.getBacklogData(result.snapshotOffset)
       // 重放窗口期命令（snapshotOffset 到 replayEndOffset）
       if (windowData != null && windowData.length > 0):
           channel.writeAndFlush(Unpooled.wrappedBuffer(windowData))
       // 重放完成，转 ONLINE
       slave.setState(ONLINE)
       slave.addFlag(SLAVE_FLAG_ONLINE)
       slave.removeFlag(SLAVE_FLAG_SYNCING)
```

#### 边界条件

- **重放期间并发**：重放在 `asyncExecutor` 线程执行，`propagateCommand` 在 business 线程执行。重放期间 slave 是 SYNCING，`propagateCommand` 的 `slave.isOnline()` 检查跳过该 slave，不会并发直发。`ReplicationBacklog.getBacklogData` 有读锁保护（201-211 行），与 `append` 写锁互斥，线程安全。
- **setState(ONLINE) 窗口**：重放完成到 setState(ONLINE) 之间，若 `propagateCommand` 到达，因 slave 仍 SYNCING 被跳过。该命令在 backlog 中（offset > replayEndOffset），但不会被重放（重放只到 replayEndOffset）。**依赖 REPLCONF ACK 兜底**：slave 进入 ONLINE 后发送 ACK 上报自身 offset，master 检测 slave offset 落后于 master offset 时，可在后续传播中补发。当前 `propagateCommand` 无补发机制，接受这个小窗口延迟（Redis 也依赖类似机制）。
- **backlog 不足**：若 `snapshotOffset` 已超出 backlog 范围（窗口期数据被覆盖），`getBacklogData` 返回 null -> 日志警告 + slave 重新发起全量同步。

#### 涉及文件

- `RdbSnapshotGenerator.java`：`generateAndTransfer` 返回 `SnapshotResult`（含 `snapshotOffset`）
- `MasterReplicationManager.java`：`performFullSync` 重放逻辑、`handlePsync` 全量同步分支记录基准
- `ReplicationBacklog.java`：验证 `getBacklogData` 线程安全（已有读锁）

### 2.3 SLAVEOF 运行时命令（C4，对应 D3）

#### 实现方案：setter 注入 ReplicationCoordinator

- `ReplicationCommandHandler` 新增 `private ReplicationCoordinator coordinator;` 字段和 `setReplicationCoordinator` setter
- `ReplicationCoordinator`（server:105）构造 `ReplicationCommandHandler` 后调用 `setReplicationCoordinator(this)`
- `handleSlaveof` 实现：
  - `SLAVEOF NO ONE` / `REPLICAOF NO ONE`：`coordinator.stopSlave()` + `readOnlyModeManager.setSlave(false)` + `setReadOnly(false)`
  - `SLAVEOF host port`：校验集群模式 -> `coordinator.startSlave(host + ":" + port)` + `readOnlyModeManager.setSlave(true)`
  - 复用 `ReplicationCoordinator.normalizeAddress` 支持 `host port` 和 `host:port` 格式

#### 边界条件

- **跨模块依赖**：`ReplicationCommandHandler`（replication 模块）需引用 `ReplicationCoordinator`（server 模块）。经核实 server 依赖 replication（无循环依赖），`ReplicationCoordinator` 构造 `ReplicationCommandHandler`，setter 注入可行。
- **重复 SLAVEOF**：`startSlave` 已有幂等检查（相同目标不重复建连）。
- **SLAVEOF NO ONE 未建连**：`stopSlave` 需容忍未建连状态（无操作返回 +OK）。

#### 涉及文件

- `ReplicationCommandHandler.java`：新增字段、setter、`handleSlaveof` 实现
- `ReplicationCoordinator.java`：构造后 setter 注入

### 2.4 AOF 写入接入（C3，对应 D4）

#### 实现方案：rawRespFrame 原始字节 + 复用 shouldPropagate

**接口改造**：`PersistService.recordCommand` 签名改为接收原始 RESP 字节：

```java
// PersistService 接口
default void recordCommand(byte[] respFrame) {}

// AofPersistService override
@Override
public void recordCommand(byte[] respFrame) {
    if (!isRunning || aofWriter == null || respFrame == null) return;
    try {
        aofWriter.write(new String(respFrame, StandardCharsets.ISO_8859_1), 0, respFrame.length);
        if (fsyncInterval == 0) flush();
    } catch (IOException e) { ... }
}
```

**接入点**：`RedisServerHandler` 764 行 `propagateCommand(rawRespFrame)` 旁边：

```java
if (rawRespFrame != null && shouldPropagate(commandName, response)) {
    propagateCommand(rawRespFrame);
    persistService.recordCommand(rawRespFrame);  // 新增
}
```

复用现有 `shouldPropagate`（1387 行）判定：错误响应不记录、只读命令不记录、默认写命令记录。

#### SELECT 处理

SELECT 命令记录到 AOF 作为 db 上下文标记（与 Redis 一致）。`shouldPropagate` 当前对 SELECT 的处理需确认：SELECT 不在 `isReadOnlyCommand` 白名单，默认会被传播。但 SELECT 的 `rawRespFrame` 在 `processCommand` 内部调用时为 null（跳过），仅客户端发起的 SELECT 才有 rawRespFrame。

**SELECT AOF 写入策略**：
- 客户端发起 `SELECT db` 时，记录 `*2\r\n$6\r\nSELECT\r\n$<n>\r\n<db>\r\n` 到 AOF
- 加载 AOF 时，按 SELECT 切换当前 db，后续命令加载到对应 db
- 这样避免"加载侧推断 db"的脆弱性

需调整 `shouldPropagate` 或在 AOF 接入点对 SELECT 特殊处理：SELECT 虽非"写命令"但需记录到 AOF 维护 db 上下文。

#### 边界条件

- **BGREWRITEAOF 接入**：`CommonCommandHandler.handleBgrewriteaof`（429-432 行）从空壳改为调用 `aofPersistService.rewrite(memoryStore)`，异步执行。
- **appendfsync 配置**：当前 `aof-fsync-interval` 秒数配置生效。`appendfsync always/everysec/no` 的支持属于 P2，P0 范围内 AOF 写入 + 定时 fsync 即可保证数据持久化。
- **二进制安全**：`rawRespFrame` 是原始字节，用 ISO-8859-1 编码写入/读取保证二进制安全。

#### 涉及文件

- `PersistService.java`：`recordCommand` 签名改为 `byte[]`
- `AofPersistService.java`：override 新签名、接入 `rewrite`
- `RedisServerHandler.java`：764 行新增 `recordCommand` 调用、SELECT 处理
- `CommonCommandHandler.java`：`handleBgrewriteaof` 接入

### 2.5 RDB TTL 持久化（C10，对应 D5）

#### 实现方案：Redis 标准 opcode + 绝对时间戳

**写入侧**（`RdbPersistService.writeKeyValue`）：

```java
private void writeKeyValue(DataOutputStream dos, int db, String key, Object value, MemoryStore memoryStore) throws IOException {
    String type = memoryStore.type(db, key);
    switch (type) {
        case "string":
            dos.writeByte(RDB_TYPE_STRING);
            writeString(dos, key);
            writeString(dos, value.toString());
            break;
        // ... 其他类型
    }
    // 写入 TTL（如有）
    long pttl = memoryStore.pttl(db, key);
    if (pttl > 0) {
        long expireAt = System.currentTimeMillis() + pttl;
        if (pttl < 3600000L && pttl % 1000 == 0) {
            // 秒级，<1 小时，用 0xFD 省 4 字节
            dos.writeByte(RDB_OPCODE_EXPIRETIME);  // 0xFD
            dos.writeInt((int)(expireAt / 1000));
        } else {
            // 毫秒级，用 0xFC
            dos.writeByte(RDB_OPCODE_EXPIRETIME_MS);  // 0xFC
            dos.writeLong(expireAt);
        }
    }
}
```

**加载侧**（`readKeyValue`）：读完 value 后 peek 下一个 byte：

```java
// 读 value 后
int nextByte = dis.readByte() & 0xFF;
if (nextByte == 0xFC) {
    long expireAt = dis.readLong();
    long remaining = expireAt - System.currentTimeMillis();
    if (remaining <= 0) {
        // 已过期，不加载
        return;
    }
    memoryStore.set(currentDb, key, value);
    memoryStore.pexpire(currentDb, key, remaining);
} else if (nextByte == 0xFD) {
    long expireAt = dis.readInt() & 0xFFFFFFFFL * 1000;
    long remaining = expireAt - System.currentTimeMillis();
    if (remaining <= 0) return;
    memoryStore.set(currentDb, key, value);
    memoryStore.pexpire(currentDb, key, remaining);
} else {
    // 无 expire opcode（旧格式），nextByte 是下一个键的 type
    // 回退处理：把 nextByte 作为下一个 opcode 继续
    memoryStore.set(currentDb, key, value);
    // 需要把 nextByte 推回流或在外层循环处理
}
```

#### 边界条件

- **peek 推回**：DataInputStream 不支持 unread。改用 `PushbackInputStream` 包装，或在外层 opcode 循环中处理（读 type byte 后判断是否 0xFC/0xFD，若是读 expire 再读下一个 type）。
- **DataInputStream 小端序**：Redis RDB 用小端序，Java `writeInt`/`writeLong` 是大端序。需用 `writeByte` 逐字节写小端，或用 `ByteBuffer.order(LITTLE_ENDIAN)`。
- **复制全量同步**：`RdbSnapshotGenerator` 用 `persistSync` 生成 RDB，自动包含 TTL（C10 修复后）。
- **向后兼容**：旧 RDB 文件无 expire opcode，加载时按永久键处理。

#### 涉及文件

- `RdbPersistService.java`：新增 opcode 常量、`writeKeyValue` 写 TTL、`readKeyValue` 读 TTL、小端序处理

### 2.6 AOF rewrite 按类型（C11，对应 D6）

#### 实现方案：按类型生成重建命令

`writeKeyValueCommand` 重构为 `writeRebuildCommand`，按 `memoryStore.type(db, key)` 分支：

```java
private void writeRebuildCommand(Writer writer, int db, String key, MemoryStore memoryStore) throws IOException {
    String type = memoryStore.type(db, key);
    switch (type) {
        case "string":
            writeResp(writer, "SET", key, memoryStore.get(db, key).toString());
            break;
        case "list":
            List<String> items = memoryStore.lrange(db, key, 0, -1);
            String[] listArgs = new String[items.size() + 1];
            listArgs[0] = key;
            for (int i = 0; i < items.size(); i++) listArgs[i+1] = items.get(i);
            writeResp(writer, "RPUSH", listArgs);
            break;
        case "set":
            Set<String> members = memoryStore.smembers(db, key);
            // SADD key m1 m2 ...
            break;
        case "hash":
            Map<String,String> fields = memoryStore.hgetAll(db, key);
            // HSET key f1 v1 f2 v2 ...
            break;
        case "zset":
            // ZADD key s1 m1 s2 m2 ...
            break;
        case "stream":
            // 逐条 XADD + XGROUP CREATE + XCLAIM 恢复 PEL
            writeStreamRebuild(writer, db, key, memoryStore);
            break;
    }
    // 带 TTL 的键追加 PEXPIREAT
    long pttl = memoryStore.pttl(db, key);
    if (pttl > 0) {
        long expireAt = System.currentTimeMillis() + pttl;
        writeResp(writer, "PEXPIREAT", key, String.valueOf(expireAt));
    }
}
```

#### Stream PEL 完整恢复

```java
private void writeStreamRebuild(Writer writer, int db, String key, MemoryStore memoryStore) throws IOException {
    // 1. 逐条 XADD 恢复数据（参考 RdbPersistService.writeStream 673-744 行）
    // 2. XGROUP CREATE 恢复消费者组
    // 3. 扫描 PEL 结构，逐条 XCLAIM 恢复 pending 消息
    //    对每个消费者组的 PEL：
    //    for (PendingEntry pe : pel) {
    //        writeResp(writer, "XCLAIM", key, group, pe.consumer,
    //                   String.valueOf(pe.idleTime), pe.id);
    //    }
}
```

直接访问 store 内部 Stream 结构获取 PEL（与 RDB 侧 `writeStream` 一致），而非命令模拟。

#### 边界条件

- **二进制安全**：所有字节数据用 ISO-8859-1 编码（与 `recordCommand` 一致）。
- **空集合**：空 list/set/zset/hash 不写重建命令（Redis 行为一致，空集合不持久化）。
- **BGREWRITEAOF 触发**：C3 修复后 `handleBgrewriteaof` 接入 `rewrite`。

#### 涉及文件

- `AofPersistService.java`：`writeRebuildCommand` 按类型分支、`writeStreamRebuild`、`rewrite` 方法接入

#### 实现期发现并修复的两项关键缺陷（不在原 spec 范围，由 B4 测试暴露）

1. **AOF rewrite 在 Windows 下静默丢数据**：`AofPersistService.rewrite` 原使用 `try-with-resources OutputStreamWriter tempWriter = new FileOutputStream("appendonly.aof.tmp")`，
   `aofWriter` 以追加模式持有 `appendonly.aof`；之后调用 `Files.move(tmp -> appendonly.aof, REPLACE_EXISTING)`。
   - Windows 下 `Files.move(REPLACE_EXISTING)` 先删除目标文件再尝试原子移动，目标 aof 被打开导致移动失败；
   - 失败后 aof 已被删除、tmp 文件还存在，外层 `catch (Exception e) { logger.error(...); }` 被 NOP logger 吞掉，finally 块继续删除 tmp → 重写后 AOF 文件不存在（数据全部丢失）。
   - **修复**：move 前显式关闭 `tempWriter`、`aofWriter`、`aofOutputStream`；move 成功后重建 `aofWriter`；失败路径也重建 `aofWriter` 以便继续追加写。

2. **AOF load 完全无法恢复数据**：原 `load` 用 `BufferedReader.readLine()` 逐行读取 AOF，
   `parseRespArray(line)` 内 `line.split("\\r\\n")` 解析 RESP 帧。但 `BufferedReader.readLine()` 已经把行尾 `\r\n` 剥掉，
   单行内根本没有 `\r\n`，`split` 永远只剩 1 个元素，`parseRespArray` 解析不出任何参数 → 命令全部被丢弃，AOF 重启数据全丢。
   - **修复**：`load` 切换为基于 `DataInputStream` 的标准 RESP 帧读取：先读 `*N` 数组头，再按 `$L\r\n<L 字节>\r\n` 解码每个参数，结果用 ISO-8859-1 解码为字符串保证二进制安全；
   - 将原 `parseAndExecuteCommand(line)` 拆分：指令分发逻辑提取为 `executeCommand(List<String> args, MemoryStore)`，行解析路径保留为兼容入口。

这两项修复使 B4 测试（`AofRewriteByTypeTest` 14 项）全部通过，同时使 AOF 在 Windows 与二进制安全前提下真正可靠。

### 2.7 CROSSSLOT 校验（C1，对应 D7）

#### 实现方案：extractKeyFromCommand 返回 List + checkCrossSlot 遍历

```java
private List<String> extractKeysFromCommand(String[] args) {
    String cmd = args[0].toUpperCase();
    List<String> keys = new ArrayList<>();
    switch (cmd) {
        case "MGET": case "DEL": case "EXISTS": case "UNLINK": case "TOUCH":
            for (int i = 1; i < args.length; i++) keys.add(args[i]);
            break;
        case "MSET": case "MSETNX":
            for (int i = 1; i < args.length; i += 2) keys.add(args[i]);
            break;
        case "SUNION": case "SINTER": case "SDIFF":
            for (int i = 1; i < args.length; i++) keys.add(args[i]);
            break;
        case "SDIFFSTORE": case "SINTERSTORE": case "SUNIONSTORE":
        case "ZUNIONSTORE": case "ZINTERSTORE":
            keys.add(args[1]);  // 目标
            for (int i = 3; i < args.length; i++) keys.add(args[i]);  // 源
            break;
        case "RENAME": case "RENAMENX": case "COPY":
            keys.add(args[1]);  // 源
            keys.add(args[2]);  // 目标
            break;
        case "SMOVE":
            keys.add(args[1]);  // 源
            keys.add(args[2]);  // 目标
            break;
        case "BITOP":
            keys.add(args[2]);  // 目标
            for (int i = 3; i < args.length; i++) keys.add(args[i]);  // 源
            break;
        // ... SORT STORE 需解析 STORE 子句
        default:
            if (args.length >= 2) keys.add(args[1]);
    }
    return keys;
}

private String checkCrossSlotAndRedirect(List<String> keys) {
    if (!config.isClusterEnabled()) return null;  // 非集群不校验
    if (keys.isEmpty()) return null;
    int firstSlot = SlotUtils.keyHashSlot(keys.get(0));
    // MOVED 检查首键
    ClusterNode owner = slotManager.getSlotOwner(firstSlot);
    if (owner == null) return "-CLUSTERDOWN Hash slot not served\r\n";
    if (!owner.getNodeId().equals(myNodeId)) {
        return "-MOVED " + firstSlot + " " + owner.getIp() + ":" + owner.getPort() + "\r\n";
    }
    // CROSSSLOT 检查所有键
    for (int i = 1; i < keys.size(); i++) {
        if (SlotUtils.keyHashSlot(keys.get(i)) != firstSlot) {
            return "-CROSSSLOT Keys in request don't hash to the same slot\r\n";
        }
    }
    return null;
}
```

#### 边界条件

- **EVAL/EVALSHA**：保持现有 `checkCrossSlotForScript` 不变（按 numkeys 遍历 KEYS）。
- **SORT STORE**：需解析 `STORE` 子句提取目标键，与源键一起校验。
- **单键命令**：返回单元素列表，CROSSSLOT 校验自然通过。
- **非集群模式**：直接返回 null，不校验。

#### 涉及文件

- `RedisServerHandler.java`：`extractKeyFromCommand` -> `extractKeysFromCommand` 返回 List、新增 `checkCrossSlotAndRedirect`、调用点改造

### 2.8 MIGRATE 原子化（C7，对应 D8）

#### 实现方案：批量消息 + 两阶段提交

新增 `MigrateKeysMessage`（批量键消息），包含所有键的 dump 数据。`migrateMultipleKeys` 改为：

```java
private String migrateMultipleKeys(host, port, keys, ...) {
    // 1. dump 所有键，检查总大小
    List<KeyDump> dumps = new ArrayList<>();
    long totalSize = 0;
    for (String key : keys) {
        byte[] valueBytes = dumpKey(key);
        long ttl = memoryStore.pttl(db, key);
        dumps.add(new KeyDump(key, valueBytes, ttl));
        totalSize += valueBytes.length;
    }
    if (totalSize > MAX_BATCH_SIZE) {  // 64MB
        return "-ERR command keys batch too large\r\n";
    }
    
    // 2. 一次性发送批量消息
    MigrateKeysMessage msg = new MigrateKeysMessage(senderNodeId, dumps, replace);
    boolean success = busClient.sendAndWait(targetNodeId, msg, timeout);
    
    // 3. 全部 ACK 后统一 DEL（非 COPY 模式）
    if (success && !copy) {
        for (KeyDump kd : dumps) {
            memoryStore.del(DEFAULT_DATABASE, kd.key);
        }
    }
    
    return success ? "+OK\r\n" : "-ERR migration failed\r\n";
}
```

#### 边界条件

- **部分失败**：目标端批量 RESTORE 原子（全部成功或全部失败），源端仅在全部 ACK 后 DEL。不存在半迁移。
- **COPY 模式**：不 DEL 源。
- **64MB 上限**：超限拒绝，不发起传输。
- **目标端 RESTORE 幂等**：REPLACE 模式覆盖，重试安全。

#### 涉及文件

- `MigrateKeysMessage.java`：新增批量消息类
- `MigrateCommandHandler.java`：`migrateMultipleKeys` 改造
- `ClusterBusClient.java`：批量消息处理（目标端 RESTORE）

### 2.9 Failover 偏移量选举（C8，对应 D9）

#### 实现方案：扩展 ReplicationLifecycleListener + rank 退避

```java
// ReplicationLifecycleListener 接口新增
long getReplicationOffset();

// ReplicationCoordinator 实现
@Override
public long getReplicationOffset() {
    return replicationBacklog != null ? replicationBacklog.getMasterReplOffset() : 0;
}
```

`FailoverManager` 改造：

```java
// 构造 AUTH_REQUEST 时填入真实偏移量
long myOffset = listener != null ? listener.getReplicationOffset() : 0;
FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
    me.getNodeId(), me.getConfigEpoch(), electionEpoch, myOffset);  // 替换 0L

// tryStartElection 退避基于 rank
int rank = calculateSlaveRank(myOffset);  // offset 最大者 rank=0
long delay = gracePeriod + rank * 500L;

// onAuthRequest 同纪元多候选比较偏移量
if (reqEpoch == lastVoteEpoch) {
    // 已投票，幂等或拒绝
} else {
    // 首投：记录候选偏移量，若后续收到更大偏移量的候选，更新投票
    // （简化：首投即定，偏移量大的先到先得；Redis 实际是 slave 自退避让 offset 大的先发起）
}
```

#### 边界条件

- **rank 计算**：需获取所有同 master slave 的偏移量。当前 `ClusterNode` 无 offset 字段，gossip 不传播 slave offset。简化方案：rank 基于本节点 offset 与 master 最后已知 offset 的差值（data age），差值越小 rank 越小。或固定 rank=0（所有 slave 同时发起，靠投票比较偏移量择优）。
- **投票比较**：`onAuthRequest` 收到 AUTH_REQUEST 时，若本纪元已投票给偏移量较小的候选，且新候选偏移量更大 -> 不改票（Redis 行为：首投即定）。靠 `tryStartElection` 的 rank 退避保证 offset 大的先发起、先获票。

#### 涉及文件

- `ReplicationLifecycleListener.java`：新增 `getReplicationOffset`
- `ReplicationCoordinator.java`：实现该方法
- `FailoverManager.java`：AUTH_REQUEST 填偏移量、退避 rank、投票比较

### 2.10 手动 failover 广播（C9，对应 D10）

#### 实现方案：广播收敛到 performFailover

```java
private void performFailover(ClusterNode slaveNode, ClusterNode masterNode) {
    // ... 现有角色切换逻辑 ...
    
    // 收敛广播职责到此（自动+手动共用）
    FailoverResultMessage result = new FailoverResultMessage(
        slaveNode.getNodeId(), slaveNode.getNodeId(),
        clusterConfig.getCurrentEpoch(), slaveNode.getSlots());
    busClient.broadcast(result);
    
    // 原 master configEpoch 对齐（原仅在自动路径有）
    masterNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
}

// performManualFailover 移除单独的 notifyTopologyChanged，依赖 performFailover 内广播
public synchronized void performManualFailover(ClusterNode slaveNode, ClusterNode masterNode) {
    performFailover(slaveNode, masterNode);
    clusterConfig.incrementEpoch();
    slaveNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
    // 不再单独广播，performFailover 内已广播
}

// performFailoverAndBroadcast 移除重复广播
private void performFailoverAndBroadcast() {
    // ... 
    performFailover(me, oldMaster);
    clusterConfig.incrementEpoch();
    me.setConfigEpoch(clusterConfig.getCurrentEpoch());
    // 不再单独广播，performFailover 内已广播
    notifyTopologyChanged();
}
```

#### 边界条件

- **重复广播安全**：`onFailoverResult`（462 行）已有幂等/纪元裁决，重复广播安全。
- **TAKEOVER 语义**：TAKEOVER 不经选举授权但仍广播 FailoverResult，与 Redis 一致。

#### 涉及文件

- `FailoverManager.java`：`performFailover` 加广播、`performManualFailover`/`performFailoverAndBroadcast` 移除重复广播

### 2.11 ZSet 字典序（C12，对应 D11）

#### 实现方案：ConcurrentSkipListSet 替代 KeySetView

```java
private static class ZSetStore {
    final ConcurrentHashMap<String, Double> memberScores = new ConcurrentHashMap<>();
    // 值类型从 KeySetView 改为 ConcurrentSkipListSet
    final ConcurrentSkipListMap<Double, ConcurrentSkipListSet<String>> scoreMembers =
            new ConcurrentSkipListMap<>();
    
    int add(String member, double score) {
        Double oldScore = memberScores.put(member, score);
        if (oldScore != null) {
            ConcurrentSkipListSet<String> oldSet = scoreMembers.get(oldScore);
            if (oldSet != null) {
                oldSet.remove(member);
                if (oldSet.isEmpty()) scoreMembers.remove(oldScore);
            }
        }
        scoreMembers.computeIfAbsent(score, k -> new ConcurrentSkipListSet<>()).add(member);
        return oldScore == null ? 1 : 0;
    }
}
```

- `zpopmax`/`zrevrange`：用 `scoreMembers.descendingMap()` + 同分集合 `descendingSet()` 或 `descendingIterator()`
- `zpopmin`：`scoreMembers.entrySet()` + 同分集合 `first()`（字典序最小）
- `zrank` 同分定位：`ConcurrentSkipListSet` 线性扫描（字典序正确，O(n)）

#### 内存估算调整

`estimateMemorySize` 244 行 `64L` 调整为 `72L`（跳表节点 + forward 指针平均开销）。

#### 边界条件

- **并发安全**：`ConcurrentSkipListSet` 并发安全，弱一致迭代器，与 `ConcurrentSkipListMap` 一致。
- **zpopmax 收集与删除一致性**：现有"先收集再删除"逻辑不变，字典序结构不影响。
- **NaN score**：`ConcurrentSkipListMap<Double, ...>` 的 NaN 比较问题与本修复正交，不处理。

#### 涉及文件

- `DefaultMemoryStore.java`：`ZSetStore` 字段类型、`add`/`remove`、`range`/`rangeByScore`/`zpopmin`/`zpopmax`/`zrevrange`/`zrank`/`zscan`/`zremrangeBy*`、`estimateMemorySize`

## 3. 测试策略

### 3.1 单元测试

| 缺陷 | 测试类 | 关键场景 |
|------|--------|---------|
| C12 | `ZSetOrderingTest`（新增，真实 store） | 同分字典序、ZPOPMIN/MAX、ZINCRBY 改分重排、多线程并发 ZADD |
| C1 | `CrossSlotCheckTest`（新增） | MGET/MSET/DEL 跨槽、RENAME 源目标、EVAL 不变、非集群跳过 |
| C10 | `RdbTtlPersistTest`（新增） | SET EX 持久化恢复、已过期不复活、旧格式兼容、毫秒/秒 opcode |
| C11 | `AofRewriteTypeTest`（新增） | 各类型 rewrite 保留、带 TTL、stream PEL |

### 3.2 集成测试

| 缺陷 | 测试类 | 关键场景 |
|------|--------|---------|
| C3 | `AofWriteIntegrationTest`（新增） | SET 后 AOF 含 RESP、重启加载恢复、读命令不记录、SELECT db 标记 |
| C2/C4/C5/C6 | `ReplicationIntegrationTest`（重启用） | 全量同步+窗口期写入+slave offset+WAIT 命令 |
| C7 | `MigrateAtomicityTest`（新增） | 全成功删源、部分失败不删、COPY 模式、超限拒绝 |
| C8/C9 | `ClusterFailoverTest`（扩展） | 偏移量选举、手动 failover 广播、TAKEOVER 广播 |

### 3.3 端到端验证

- master-slave 全量同步 + 窗口期写入不丢 + slave offset 正确 + WAIT 返回真实副本数
- AOF 模式重启不丢数据 + RDB 模式 TTL 保留 + AOF rewrite 各类型保留
- 集群 CROSSSLOT 拒绝跨槽 + MIGRATE 原子性 + 手动 failover 全网收敛 + 偏移量选举

## 4. Spec Patch（回写 delta spec）

### persistence-data-integrity spec 调整

1. `recordCommand` 签名从 `(String command, String[] args)` 改为 `(byte[] respFrame)`
2. SELECT 场景明确：记录 SELECT 到 AOF 作为 db 上下文标记（非"不记录"）
3. Stream rewrite PEL 场景已补充（完整恢复）

## 5. 风险与缓解

| 风险 | 缓解 |
|------|------|
| 复制握手 Netty 异步时序 | 方案 A 状态机+回调，5s timeout 兜底 |
| Full sync 重放窗口小延迟 | 接受，依赖 REPLCONF ACK 兜底 |
| RDB 小端序写入 | 用 writeByte 逐字节或 ByteBuffer LITTLE_ENDIAN |
| AOF recordCommand 签名变更（BREAKING） | default 空实现，非 AOF 实现无需改 |
| ZSet 内存估算偏差 | 72L 粗估，精确计量属 P3 |
| Failover rank 计算简化 | 固定 rank=0 或基于 data age，靠投票比较择优 |

## 6. 归档状态

本 Design Doc 在 change 归档时标注 `archived-with` 状态。
