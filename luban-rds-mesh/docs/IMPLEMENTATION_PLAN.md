# luban-rds-mesh 实施计划

> 13 阶段渐进实施，每阶段独立可验证。本计划与 [DESIGN.md](DESIGN.md) v1.2 完全对齐。

| 字段 | 内容 |
|------|------|
| 总阶段数 | 13 |
| 预计代码量 | ~3,300 行 Java |
| 预计文件数 | ~30 个 |
| 文档版本 | v1.2（2026-08-04） |

**v1.0 → v1.2 修订说明**：v1.0 基于"store 装饰器"拦截方案，已在 DESIGN v1.1 评审中推翻。v1.2 全面对齐 DESIGN v1.2 的定案决策——① 拦截点改为 **handler 命令层 gate（MeshWriteGate）**，删除 30+ 方法覆盖与代码生成脚本；② **AOF 在 mesh 模式退役**（Raft log 即 WAL），LogApplier 不写 AOF；③ MOVED 用 **key 真实 CRC16 slot** + service 端口；④ 读路径实现 **完整 Leader Lease + read-index 退化**；⑤ 持久化定案 **lastIncludedIndex + dump.rdb + tail 回放**，fsync 必须在确认路径上；⑥ 新增 CLUSTER SLOTS/NODES/INFO、MULTI/EXEC 单条目、BLOCK 禁用、chunked INSTALL_SNAPSHOT、dump.rdb 写者归属等阶段。

---

## 阶段总览

| 阶段 | 名称 | 预计代码量 | 依赖 |
|------|------|-----------|------|
| 1 | 项目骨架与传输层（MeshBus） | ~500 | — |
| 2 | 状态机与 RPC 协议 | ~550 | 1 |
| 3 | 选举、心跳与租约 | ~450 | 1, 2 |
| 4 | 日志复制与 apply（handler gate 写路径） | ~400 | 2, 3 |
| 5 | MeshWriteGate（handler 级门面） | ~300 | 4 |
| 6 | 客户端重定向（MOVED / MESHDOWN） | ~150 | 5 |
| 7 | Leader 读路径（Lease + read-index） | ~200 | 3, 5 |
| 8 | CLUSTER SLOTS / NODES / INFO | ~200 | 5 |
| 9 | MULTI/EXEC 单条目与 BLOCK 命令禁用 | ~250 | 4, 5 |
| 10 | Snapshot（chunked 传输）与 dump.rdb 归属 | ~300 | 4 |
| 11 | 持久化与启动加载 | ~400 | 2, 10 |
| 12 | 装配入口与 server 集成 | ~350 | 1-11 |
| 13 | 测试与文档 | — | 1-12 |

---

## 阶段 1：项目骨架与传输层（MeshBus）

### 目标

建立 `luban-rds-mesh` Maven 模块，实现独立 Netty 总线传输层。

### 任务

1. **创建 Maven 模块**

   - 文件：`luban-rds-mesh/pom.xml`
   - 依赖（**AOF 退役，不引入 AofPersistService**）：
     - `luban-rds-core`（MemoryStore、DefaultCommandHandler）
     - `luban-rds-protocol`（RESP 解析）
     - `luban-rds-persistence`（PersistService，仅用于 dump.rdb 落盘，不接 AOF）
     - `luban-rds-replication`（RdbSnapshotGenerator、RdbDataLoader）
   - 在项目根 `pom.xml` 加入 `<module>luban-rds-mesh</module>`

2. **实现 MeshBus（Netty 传输层）**

   帧格式与 cluster 总线同构（已核实：`GossipMessage.java` `HEADER_LENGTH=45`、`NODE_ID_LENGTH=40`，大端 4 字节 length，body ≤ 16MB）：

   ```
   ┌────────────────────────────────────────────┐
   │ 40B senderNodeId │ 1B type │ 4B len(BE)    │ = 45B 帧头，无 term
   ├────────────────────────────────────────────┤
   │ body...（≤ 16MB，与 cluster MAX_BODY_LENGTH 对齐）│
   └────────────────────────────────────────────┘
   ```

   - `bus/MessageType.java`：`APPEND_ENTRIES(0x60)` / `APPEND_ENTRIES_RESP(0x61)` / `REQUEST_VOTE(0x62)` / `REQUEST_VOTE_RESP(0x63)` / `INSTALL_SNAPSHOT(0x64)`。cluster 模块实际码段 0x40–0x4C（已核实 `GossipMessageType`），与 0x60–0x64 不冲突。
   - `MeshBusCodec.java`：帧编解码（40B nodeId + 1B type + 4B length + body）；term 在 body 内传递（Raft 要求每个 RPC 携带 term，不在帧头）。
   - `MeshBusServer.java`：Netty 服务端，bind busPort（参考 `ClusterBusServer.java`）。
   - `MeshBusClient.java`：出站连接，**去重 / 退避 / keepalive**（参考 `ClusterBusClient`）；**过滤自身 nodeId**（peers 列表通常含自身，避免节点自连）。
   - `MeshBusHandler.java`：入站分发（先简单 echo，等阶段 2 完善）。

3. **包结构建立**

   `src/main/java/com/janeluo/luban/rds/mesh/` 下：`core/`、`rpc/`、`bus/`、`election/`、`replication/`、`gateway/`、`client/`、`lifecycle/`，各放 `package-info.java`。

### 验证标准

- [ ] Maven 编译通过：`mvn -pl luban-rds-mesh -am compile`
- [ ] 3 个本地进程互发空 `APPEND_ENTRIES`，各端日志显示连接成功
- [ ] 进程崩溃后指数退避重连成功
- [ ] 端口隔离：mesh 不占用 cluster 的 port+10000
- [ ] MeshBusClient 不连接自身 nodeId

---

## 阶段 2：状态机与 RPC 协议

### 目标

定义 Raft 核心数据结构与 5 种 RPC 消息类型。

### 任务

1. **核心数据结构**

   - `core/MeshRole.java`：`FOLLOWER / CANDIDATE / LEADER`
   - `core/LogEntry.java`（含 DESIGN §3.2 的 `dbIndex` 与 `extra`，v1.0 缺这两个字段）：

     ```java
     public class LogEntry {
         final long term;
         final long index;            // 1-based
         final byte[] respPayload;    // 完整 RESP 命令帧（事务时为 MULTI 帧）
         final int dbIndex;           // apply 时传给 handler 的 database 参数
         final byte[] extra;          // 事务：命令帧序列 + WATCH 版本快照；普通写为 null
     }
     ```

   - `core/MeshState.java`（含 `lastIncludedIndex/Term`，v1.0 缺这两个字段）：

     ```java
     public class MeshState {
         volatile long currentTerm;
         volatile String votedFor;
         final List<LogEntry> log;            // 仅含 tail（快照截断后）
         volatile long lastIncludedIndex;     // 快照边界
         volatile long lastIncludedTerm;
         volatile long commitIndex;           // 重启后由快照 + 重放重建
         volatile long lastApplied;
         volatile String leaderId;
         volatile MeshRole role;
         // 读写锁保护
     }
     ```

2. **RPC 消息类**（term 在消息体内）

   - `rpc/MeshRpcMessage.java`（基类）、`AppendEntriesMessage/Response`、`RequestVoteMessage/Response`、`InstallSnapshotMessage`
   - 每类提供 `encode()` / `decode()`（基于 ByteBuffer）
   - `InstallSnapshotMessage` 字段含 `offset/done`，为阶段 10 chunked 传输预留

3. **完善 MeshBus 分发**

   - `MeshBusHandler` 按 `MessageType` 反序列化为具体 RPC 类，转发给 `MeshNode`

### 验证标准

- [ ] `LogEntryTest`：序列化往返一致（含 dbIndex/extra）
- [ ] `RpcMessageTest`：5 种消息 encode/decode 往返一致
- [ ] 旧任期消息被拒绝（返回新任期）

---

## 阶段 3：选举、心跳与租约

### 目标

实现 Leader 选举、心跳维持、**LeaseManager（心跳租约）**。

### 任务

1. **选举定时器** `election/ElectionTimer.java`：随机超时 150–300ms，超时触发 `MeshNode.becomeCandidate()`。

2. **投票收集** `election/VoteCollector.java`：并行 RequestVote，达到多数派（2/3 含自己）→ `becomeLeader()`，初始化 nextIndex/matchIndex，立即发空 AppendEntries 建立权威。

3. **LeaseManager** `election/LeaseManager.java`（DESIGN §5.7/§7.5）：

   ```java
   public class LeaseManager {
       volatile long leaseExpireAt;
       void refreshOnMajorityAck(long now);   // 心跳多数派 success=true 续租
       boolean isValid(long now);
       boolean awaitValid(long timeoutMs);     // 失效时阻塞至下一轮续租（供读路径）
   }
   ```

   - `leaseDuration = 2 × electionTimeout`（默认 600ms，可配置）
   - Leader 每轮心跳（100ms）广播 AppendEntries，收到多数派 `success=true` 即 `leaseExpireAt = now + leaseDuration`

4. **AppendEntries 接收处理（Follower 侧）**：任期校验、prevLogIndex/prevLogTerm 一致性校验、追加截断、重置 ElectionTimer、返回 `success/matchIndex`。**落盘完成后才返回 success=true**（阶段 11 详述 fsync 时序）。

5. **PreVote（可选，v1 推荐实现）**：被隔离的单节点不断自增 term 重新选举，恢复后用大 term 踢掉正常运行 Leader，加剧抖动。PreVote 在不自增 term 的前提下探测能否赢，防 term 膨胀。若 v1 不实现需在文档注明风险。

### 验证标准

- [ ] kill Leader 后，2 个 Follower 中一个能在 ~300ms 内成为新 Leader
- [ ] `ElectionTest`：投票 / 任期 / 多数派判定
- [ ] `LeaseManagerTest`：续租 / 失效 / awaitValid 等待语义

---

## 阶段 4：日志复制与 apply（handler gate 写路径）

### 目标

实现 `propose → 多数派 ACK → commit → apply → 响应客户端` 完整链路（DESIGN §5.1）。

### 任务

1. **`MeshNode.propose(byte[] respPayload, int dbIndex, byte[] extra)`**：

   ```java
   public CompletableFuture<byte[]> propose(byte[] respPayload, int dbIndex, byte[] extra) {
       if (role != MeshRole.LEADER) throw new MovedToLeaderException(leaderServiceAddr);
       long index = lastIncludedIndex + state.log.size() + 1;   // 含快照偏移
       LogEntry entry = new LogEntry(currentTerm, index, respPayload, dbIndex, extra);
       state.log.add(entry);
       persistentStateStore.appendAndPersist(entry);             // fsync 完成后才继续
       replicator.replicate(entry, future);                       // 异步追多数派
       return future;
   }
   ```

2. **LogReplicator**：并行给 Follower 发 AppendEntries，收集 matchIndex ACK，检测多数派 → commitIndex 推进 → `applyCommittedEntries()`。Leader 自身日志**落盘后才 complete future 回客户端**。

3. **LogApplier** `replication/LogApplier.java`（**不写 AOF**——Raft log 即 WAL）：

   ```java
   public Object apply(LogEntry entry) {
       // 1. RESP 解析
       Object[] command = parseResp(entry.respPayload);
       // 2. 命令分发（apply 只用 raw store，绝不经过任何拦截层）
       Object response = commandHandler.handle(commandName, entry.dbIndex, args, rawMemoryStore);
       // 3. 不调 persistService.recordCommand（AOF 退役）
       // 4. 更新 lastApplied
       state.lastApplied = entry.index;
       return response;   // = 客户端响应对象（+OK\r\n / :5\r\n / 数组…）
   }
   ```

   **关键**：已核实 `DefaultCommandHandler.handle`（`DefaultCommandHandler.java:81`）返回 `Object` 且不写 Channel——apply 返回值即客户端响应对象，零转换管线（DESIGN v1.1 最大优点）。

4. **持久化时序（防"已确认写入丢失"）**：Follower 必须在**日志落盘（fsync）完成后**才返回 `success=true`；Leader 必须在**自身日志落盘后**才 complete future。落盘用原子写（tmp + fsync + ATOMIC_MOVE，复用 `ClusterConfigPersister` 模式，已核实属实）。**批量 flush 只用于日志压缩等非关键路径，不出现在确认路径上**。

5. **Future 完成**：apply 完成后 `future.complete(applyResponse)`；下一轮 AppendEntries 携带 `leaderCommit`，Follower apply 推进 lastApplied（响应对象丢弃）。

### 验证标准

- [ ] 客户端 SET → 多数派确认 → 3 节点全部可读到新值
- [ ] Leader 切换后未提交写入被覆盖（一致性 > 可用性）
- [ ] 并发 SET 同一 key：多数派提交后所有节点值一致
- [ ] **无 AOF 文件产生**（mesh 模式 AOF 退役验证）

---

## 阶段 5：MeshWriteGate（handler 级门面，替代 store 装饰器）

### 目标

用 handler 命令层门面拦截写命令，替代 v1.0 的 store 装饰器方案（DESIGN §7.2）。

### 任务

1. **MeshWriteGate** `gateway/MeshWriteGate.java`：

   ```java
   public class MeshWriteGate {
       private final MeshNode meshNode;
       private final MemoryStore rawStore;        // 真实 DefaultMemoryStore——apply 唯一目标
       private final DefaultCommandHandler handler;

       /** 写命令/事务：propose 并阻塞至 commit+apply，返回 apply 产生的响应字节 */
       public byte[] write(byte[] rawRespFrame, int dbIndex, byte[] extra);

       /** 读命令：租约有效则本地执行并返回响应字节；非 Leader/租约失效走 MOVED 或等待续租 */
       public byte[] read(int dbIndex, String[] args);

       /** MOVED/MESHDOWN 响应生成（key 真实 CRC16 slot + service 地址） */
       public String redirectResponse(String key);
   }
   ```

2. **写命令判定**：维护写命令集合（白名单：SET/DEL/INCR/HSET/LPUSH/ZADD/XADD …）。读命令走 `read()`，写命令走 `write()`，动态命令（EVAL/EVALSHA）统一当写。

3. **rawRespFrame 来源**（已核实 `RedisServerHandler.java:346-360`）：`channelRead` 阶段在 parse 前后记录 readerIndex，差值即完整 RESP 命令帧，从 `clientInfo.getInboundBuf()` 取出，作为 `rawRespFrame`（`byte[]`）透传到 `processCommand(ctx, clientInfo, command, rawRespFrame)`（`RedisServerHandler.java:363`）。gate 直接用这份原始字节入 Raft，零重编码。

4. **apply 响应回客户端**：gate.write 拿到 apply 返回的响应对象 → RESP 序列化 → 写回客户端 Channel。

5. **为什么不做 store 装饰器**（已核实）：MemoryStore 有 50+ 写方法、**36 个有返回值**（`incrby→long`、`lpop→String`、`xadd→StreamId`…）。装饰器需逐方法做"帧→返回值"反射管线，还伴随 apply 递归风险。命令层 gate 只需 `commandName/args/rawFrame` 三样，响应天然是 apply 返回值，零转换。**删除 v1.0 的代码生成脚本与 30+ 方法覆盖**。

### 验证标准

- [ ] `MeshWriteGateTest`：写分流（SET 走 propose）、读分流（GET 走本地）、MOVED 生成、事务单条目
- [ ] 集成测试：SET/HSET/LPUSH/XADD 都在日志中可见 AppendEntries
- [ ] 读方法不拦截：GET/HGET 直接走 rawStore，性能不退化

---

## 阶段 6：客户端重定向（MOVED / MESHDOWN）

### 目标

Follower 收到写请求时返回 MOVED，无 Leader 时返回 MESHDOWN（DESIGN §5.3）。

### 任务

1. **`MovedToLeaderException`** `client/MovedToLeaderException.java`：携带 leader 的 **service 地址**（非 bus 地址）。**全仓库当前不存在此类，待新增**（已核实）。

2. **`MeshClientRedirector`** `client/MeshClientRedirector.java`：
   - leaderId 已知 → `-MOVED <key真实CRC16 slot> <leader-ip>:<leader-service-port>\r\n`
   - leaderId 未知 → `-MESHDOWN The mesh cluster has no leader\r\n`
   - **slot 用 key 的真实 CRC16**（复用 `luban-rds-common.util.SlotUtils.getSlot`，0–16383），**不用固定占位值**——部分客户端依赖 slot 更新路由缓存
   - **地址用 service 端口**（6379/6380/6381），不是 bus 端口（11000）

3. **修改 server 模块（最小侵入）**：在 `RedisServerHandler.java` 命令执行的通用 `catch (Exception e)`（**line 847**，已核实）**之前**新增 `catch (MovedToLeaderException e)`：

   ```java
   } catch (MovedToLeaderException e) {
       String resp = meshClientRedirector.formatResponse(e.getLeaderAddr(), e.getKey());
       ctx.writeAndFlush(resp);
       return;
   }
   ```

   同一 catch 分支覆盖 EXEC 事务内逐条命令的异常路径。

4. **MESHDOWN 客户端退避**：文档建议客户端收到 MESHDOWN 后指数退避重试（默认 200ms 起步，上限 2s）；非集群感知客户端无此行为，由用户自行处理。

### 验证标准

- [ ] 连 Follower 发 SET，收到 `-MOVED <slot> ip:port`（slot 为真实 CRC16）
- [ ] JedisCluster 收到 MOVED 后刷新拓扑并重连 Leader 写入成功
- [ ] 无 Leader 时收到 `-MESHDOWN`
- [ ] Follower 拒绝写 < 1ms

---

## 阶段 7：Leader 读路径（Lease + read-index）

### 目标

实现完整 Leader Lease 读路径，非 Leader 一律 MOVED（DESIGN §5.7）。**v1.0 的"简化版/第一版不实现 lease"已废弃**。

### 任务

1. **gate 读路径**：
   ```
   - meshNode.isLeader()? 否 → MovedToLeaderException
   - lease.isValid(now)? 是 → 本地读 rawStore → 响应字节
                        否 → lease.awaitValid(timeout) 阻塞至下一轮续租后读
   ```

2. **租约机制**（阶段 3 的 LeaseManager）：
   - Leader 每轮心跳广播 AppendEntries，**收到多数派（含自己）`success=true` 即续租**
   - 读仅在租约有效期内本地执行
   - **前置条件**：节点间时钟偏差 < leaseDuration/2（部署要求 NTP 对齐）

3. **read-index 退化（时钟不可靠环境）**：租约不可信时，读前向多数派发一次心跳校验任期，`commitIndex ≥ lastApplied` 之后才读。**区别于 LeaseManager.awaitValid**：read-index 主动发心跳 + 校验 commitIndex；awaitValid 是被动等下一轮续租。两者由配置切换（`mesh-read-consistency = lease | readindex`）。

4. **并发安全**：读在 handler 线程执行，与 apply 线程并发访问 rawStore。已核实 `DefaultMemoryStore` 用并发容器 + 1024 分段锁，整批写路径 `synchronized(store)` 由 apply 串行保证互斥，并发基本安全。

### 验证标准

- [ ] 连 Leader 读性能 ≈ 直连 MemoryStore
- [ ] 连 Follower 读被 MOVED
- [ ] 旧 Leader 被分区隔离后租约过期，不再服务读（防陈旧读）
- [ ] `LeaseManagerTest`：续租 / 失效 / awaitValid / read-index 退化切换

---

## 阶段 8：CLUSTER SLOTS / NODES / INFO

### 目标

实现集群感知客户端（JedisCluster / lettuce cluster）的引导命令（DESIGN §5.6）。**这是"集群感知客户端零侵入"的成立前提**——没有 CLUSTER SLOTS，这些客户端无法引导。

### 任务

1. **`MeshClusterCommands`** `client/MeshClusterCommands.java`：
   - `CLUSTER SLOTS` → `[[0, 16383, [leaderServiceIp, leaderServicePort, leaderNodeId]]]`（16384 全 slot 指向当前 Leader，mesh 无分片）
   - `CLUSTER NODES` → 3 节点一行一个，对齐 CLUSTER NODES 格式
   - `CLUSTER INFO` → `cluster_state:ok`、`cluster_known_nodes:3` 等

2. **server 模块接管**：`RedisServerHandler` 的 CLUSTER 命令分支在 mesh 模式由 `MeshClusterCommands` 接管，cluster 模式原有逻辑不受影响。

3. **Leader 变更**：客户端收到 MOVED → 刷新拓扑（再调 CLUSTER SLOTS）→ 重连新 Leader。

### 验证标准

- [ ] JedisCluster / lettuce cluster 能通过 CLUSTER SLOTS 引导并连上 Leader
- [ ] MOVED 后客户端自动刷新拓扑重连
- [ ] `MeshClusterCommandsTest`：SLOTS/NODES/INFO 格式正确

---

## 阶段 9：MULTI/EXEC 单条目与 BLOCK 命令禁用

### 目标

保证事务原子性、消除阻塞命令与 Raft apply 的冲突（DESIGN §5.8、§9 风险表）。

### 任务

1. **MULTI/EXEC 整事务单条 LogEntry**（DESIGN §5.8）：
   - MULTI 起：命令照常入连接级事务队列（+QUEUED），不过 gate
   - EXEC 到达 gate：构造单条 LogEntry，`payload = MULTI 帧`，`extra = 队列内各命令帧序列 + WATCH 版本快照（db|key → getKeyVersion，MULTI 后捕获）`
   - apply：先按 extra 中版本快照做 WATCH 校验 → 不匹配返回 `*-1` → 按序执行队列内命令，收集响应组装 RESP 数组
   - 响应数组 = 客户端 EXEC 的响应，由 LogApplier 捕获返回

   **为什么必须单条目**：已核实 `handleExecCommand`（`RedisServerHandler.java:1754`，循环 1848-1936）事务内逐条调 `memoryStore`（INCR@1883、SET@1890、DEL@1902）**完全不经过集群重定向门**。若每条各成一条日志，中途故障会事务部分生效，破坏原子性。单条目使整个事务要么全生效要么全不生效。

2. **BLOCK 类命令禁用**（v1）：BLPOP/BRPOP/XREAD BLOCK 返回 `-ERR BLOCK commands are not supported in mesh mode`。已核实两条阻塞唤醒路径均**绕过拦截层**：
   - BLPOP/BRPOP 唤醒：`BlockingRequestManager.tryWakeUpWithPop`（`BlockingRequestManager.java:267-297`）接收 lambda，真正直调 `memoryStore.lpop/rpop` 在 `RedisServerHandler.java:2082-2086`，绕过集群重定向门与 AOF/传播段
   - XREAD BLOCK：唤醒在 `RedisServerHandler` 的 Stream 等待器机制（line 2132/2146/2220-2466），**不在 BlockingRequestManager**，同样绕拦截层

   v2 支持"立即命中"语义 + Raft 化唤醒。

### 验证标准

- [ ] MULTI/EXEC 中途 kill 节点，验证全有或全无
- [ ] BLPOP/BRPOP/XREAD BLOCK 返回明确错误
- [ ] `MULTIExecTest`：WATCH 不匹配返回 `*-1`、事务原子性

---

## 阶段 10：Snapshot（chunked 传输）与 dump.rdb 写者归属

### 目标

节点落后太多时通过 chunked RDB snapshot 追平；明确 mesh 模式 dump.rdb 的唯一写者（DESIGN §5.4）。

### 任务

1. **chunked INSTALL_SNAPSHOT（破 16MB 矛盾）**：单帧 body ≤ 16MB（已核实 cluster `MAX_BODY_LENGTH`）。几百 MB 快照无法单帧传输，**v1 必须实现 chunked**（非 v1.0 的"一次性传输"）：
   - 默认 chunk 4MB，Leader 按 offset 切片发，Follower 按 offset 累积拼装到临时文件，`done=true` 时整体加载
   - `InstallSnapshotMessage.offset/done` 字段启用

2. **RDB 字节 API（新增）**：已核实 `RdbSnapshotGenerator` 现有 `generateAndTransfer`（走 channel 流式，`RdbSnapshotGenerator.java:88`），**无返回字节的 generate 方法**；`generateTempRdbFile` 已具备落盘路径但是 **private**（`RdbSnapshotGenerator.java:139`）。需提可见性或新增 `public byte[] generateChunk(MemoryStore, long offset, int chunkSize)` / 复用 `generateTempRdbFile` 切片读取。

3. **Follower 接收**：校验 lastIncludedTerm/Index → 累积写临时 RDB → done 后 `RdbDataLoader.startLoading/writeChunk/finishLoading`（已核实签名：`startLoading(MemoryStore, LoadProgressMonitor)`@78、`writeChunk(ByteBuf)`@115、`finishLoading(MemoryStore)`@151）。**修复 keysLoaded 恒为 0 的统计 bug**（已核实：`keysLoaded` 仅 set(0)，无 incrementAndGet）。截断 log 保留 lastIncludedIndex 之后条目，更新 commitIndex=lastApplied=lastIncludedIndex，持久化 MeshState。

4. **dump.rdb 写者归属（P0-3 定案）**：**mesh 模式禁用 server 原 RDB save（BGSAVE / PersistService save 路径），dump.rdb 的唯一写者 = SnapshotManager**。server 侧 save 相关路径在 `mesh-enabled` 时整体 gate，与"AOF 退役"同处理，防止两条路径互相覆盖 dump.rdb 导致 §5.5 的"dump.rdb 快照索引 = lastIncludedIndex"校验失效。

5. **周期快照（防日志无界增长）**：各节点独立按阈值触发（每 N 条 / 累计 M 字节，如 10 万条 / 256MB）→ 生成快照 → 落盘 dump.rdb → 截断日志 → 更新持久化 lastIncludedIndex/Term。**lastIncludedIndex 与 dump.rdb 非原子写**：二者分别落盘，衔接不上时降级为"标记本地状态不可信，选举后由 Leader INSTALL_SNAPSHOT 追平"（§5.5 常态容错，非异常）。

### 验证标准

- [ ] 节点宕机重启落后 10 万条日志，自动触发 chunked snapshot 追平（>16MB 场景验证）
- [ ] snapshot 完成后节点正常接收 AppendEntries
- [ ] `RdbDataLoader` keysLoaded 计数正确（修复恒 0 bug）
- [ ] mesh 模式下 BGSAVE/save 不写 dump.rdb（唯一写者是 SnapshotManager）

---

## 阶段 11：持久化与启动加载

### 目标

state 持久化到 `raft-nodes.conf`，启动加载保证重启一致性（DESIGN §5.5）。

### 任务

1. **`MeshConfigPersister`** `lifecycle/MeshConfigPersister.java`：原子写（tmp + fsync + ATOMIC_MOVE，复用 `ClusterConfigPersister` 模式，已核实 `ClusterConfigPersister.java:67-164` 实现属实）。格式含 lastIncludedIndex/lastIncludedTerm：

   ```json
   {
     "nodeId": "abc123...",
     "currentTerm": 5,
     "votedFor": "xyz789...",
     "lastIncludedIndex": 100,
     "lastIncludedTerm": 4,
     "logTail": [{"term": 5, "index": 101, "dbIndex": 0, "payload": "<base64>"}]
   }
   ```

2. **持久化时机（fsync 在确认路径上）**：
   - currentTerm 变化（选举 / 收到更高任期）
   - votedFor 设置
   - log append：**每条 fsync**（follower 落盘才 ACK，leader 落盘才 complete future）
   - **删除 v1.0 的"每 N 条 flush 一次"**——批量延迟 flush 出现在确认路径会丢已确认写入（复用 cluster N-27/28 fsync 教训）

3. **启动加载顺序（DESIGN §5.5）**：
   ```
   1. 读 config.mesh.* → 建立 peers（含自身 nodeId，MeshBusClient 过滤自身）
   2. 加载 raft-nodes.conf → MeshState（含 lastIncludedIndex/Term）
   3. 若磁盘存在 dump.rdb 且其快照索引 = lastIncludedIndex:
        RdbDataLoader 载入内存（快照作为状态地基）
      else（无快照/衔接不上）:
        内存为空，标记"本地状态不可信"（常态容错）
   4. 将 logTail 中 index > lastIncludedIndex 的条目按序 apply 到内存之上
      （快照 + tail 重放 = 完整已提交状态；绝不回放 AOF）
   5. 启动 MeshBus + ElectionTimer，参与选举；本地状态不可信则选举后由 Leader INSTALL_SNAPSHOT 全量追平
   ```

   **为什么必须这样**：log 被快照截断后，raft-nodes.conf 只有 logTail；跳过第 3 步在空库重放 tail，`INCRBY/HSET/DEL` 等依赖前置数据的命令会产出错误状态，节点带错误状态参与选举会污染集群。

4. **`PersistentStateStore`**：`core/PersistentStateStore.java`（抽象）+ `FileBasedPersistentStateStore`（实现）。

### 验证标准

- [ ] kill -9 Leader 重启后 term 恢复，能正确响应新选举
- [ ] kill -9 Follower 重启后能继续接收 AppendEntries
- [ ] raft-nodes.conf 损坏时启动失败（不静默重置 term）
- [ ] 快照 + logTail 重放后状态与故障前一致

---

## 阶段 12：装配入口与 server 集成

### 目标

在 `NettyRedisServer` 新增 `initMeshMode()`，通过配置启用（DESIGN §6）。

### 任务

1. **修改 server 模块**（最小侵入，均在 mesh 配置开关下生效）：
   - `NettyRedisServer`：读 `mesh-enabled`（line 281 `isClusterEnabled()` 旁）→ `initMeshMode()`（参考 `initClusterMode()`，实际 `NettyRedisServer.java:338-486`，已核实结束于 486 非 475）
   - `memoryStore` 字段去 final（**`NettyRedisServer.java:78`，已核实 `private final MemoryStore memoryStore`**）→ 构造时按 `mesh-enabled` 分支注入
   - `RedisServerHandler`：在 cluster 重定向门（line 781-787，已核实）同位置插入 mesh 门——写/EXEC → `MeshWriteGate.write()`，读 → `read()`；异常 catch 新增 `MovedToLeaderException`（插在通用 catch line 847 之前）；AOF/传播段（799-812）整体跳过；CLUSTER 分支接管为 MeshClusterCommands

2. **`MeshBootstrap`** `lifecycle/MeshBootstrap.java`：读 mesh 配置 → 创建 MeshNode → 启动 ElectionTimer + MeshBusServer → 按启动加载顺序（§5.5）恢复。

3. **bin CLI 扩展**：已核实 `luban-rds-bin` 当前 CLI 只支持 `--config/--port/--help`。扩展为可选 mesh 参数（`--mesh-enabled`、`--mesh-peers` 等），README 的 `--mesh.*` 参数才有效。

4. **cluster 与 mesh 互斥**：同一进程只能启用其一，启动时校验。

### 验证标准

- [ ] 3 个 `luban-rds-bin` 进程以 mesh 模式启动，集群成功形成
- [ ] JedisCluster 连任一节点可读写（自动重定向）
- [ ] cluster 和 mesh 模式互斥（同时启用启动失败并报错）

---

## 阶段 13：测试与文档

### 任务

1. **单元测试**：LogEntry/RPC 序列化、选举算法（split vote/任期裁决）、状态机转换、LeaseManager（续租/失效/awaitValid/read-index 退化）、MeshWriteGate（读写分流/MOVED/事务单条目）、MeshClusterCommands（SLOTS/NODES/INFO）、PersistentStateStore 原子写（含损坏处理）。

2. **集成测试**（3 进程）：kill leader / kill follower / 网络分区（含旧 Leader 隔离验证租约失效）/ 时钟偏移注入 / 节点重启后追平。

3. **一致性测试**：并发 SET 同 key 多数派语义；MULTI/EXEC 原子性（中途 kill 节点）；重启恢复（快照 + logTail 重放后状态一致）。

4. **兼容测试**：**JedisCluster / lettuce cluster 引导（CLUSTER SLOTS）**、MOVED 自动跟随、Leader 切换重连；普通客户端（Jedis 单机 / redis-cli）行为与文档一致（不跟随 MOVED）。

5. **性能测试**：单节点 vs 3 节点写 RTT（多数派代价）、读 RTT（租约内本地读）、吞吐（单 Leader 串行 apply 上限）、不同 value 大小。

6. **文档**：README（架构图/配置/运维命令）、DESIGN.md、IMPLEMENTATION_PLAN.md（本文档）、主 README 增加 mesh 模块索引。

### 验证标准

- [ ] CI 跑通所有单元测试
- [ ] docker-compose 3 节点环境集成测试通过
- [ ] 端到端 demo：3 节点启停 + 客户端读写 + 故障切换

---

## 风险与回退策略

| 风险 | 应对 |
|------|------|
| handler gate 阻塞业务线程至 commit | 写 RTT ≈ 1×网络 RTT，强一致的必要代价；Pipeline/异步高并发写在 gate 串行，v2 可做批量日志合并 |
| 修改 server 模块影响 cluster 模式 | 通过 `mesh-enabled` 配置 gate，与 cluster 互斥启用 |
| chunked snapshot 传输失败 | Follower 维持旧状态，Leader 按 offset 断点续传重试 |
| 持久化 fsync 性能开销 | **确认路径必须 fsync**（不可批量）；非关键路径（日志压缩）可批量 |
| memoryStore final 字段重构 | 影响小，`NettyRedisServer.java:78` 去 final 即可 |
| MESHDOWN 客户端雪崩 | 文档建议指数退避（200ms 起步，上限 2s） |

---

## 里程碑

| 里程碑 | 包含阶段 | 可演示功能 |
|--------|----------|-----------|
| **M1：基础 Raft** | 1, 2, 3 | 3 节点选举 Leader、维持心跳与租约 |
| **M2：强一致写** | 4, 5 | 客户端 SET 走 handler gate + 多数派确认 |
| **M3：客户端兼容** | 6, 7, 8 | JedisCluster/lettuce 经 CLUSTER SLOTS 引导 + MOVED 重定向 + 租约读 |
| **M4：完整 HA** | 9, 10, 11 | MULTI 原子性、chunked snapshot 追平、重启恢复 |
| **M5：生产就绪** | 12, 13 | initMeshMode 装配 + 完整测试 |

---

## 后续可扩展方向

1. **PreVote**：阶段 3 若未实现，v2 补全防 term 膨胀
2. **Multi-Raft**：MeshCluster 演进为多 Raft Group（按 key 哈希分片）
3. **节点扩缩容**：Joint Consensus 动态成员变更
4. **TLS 加密**：MeshBus 通道加密
5. **BLOCK 命令**：v2 支持立即命中 + Raft 化唤醒
6. **监控指标**：Micrometer 暴露选举次数、apply RTT、日志长度等
