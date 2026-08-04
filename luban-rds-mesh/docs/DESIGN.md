# luban-rds-mesh 设计文档

> 3 节点 Raft 强一致集群 — 管理面自研协议 + 数据面 RESP 兼容

| 字段 | 内容 |
|------|------|
| 模块名 | `luban-rds-mesh` |
| 包路径 | `com.janeluo.luban.rds.mesh` |
| 协议族 | Raft-like（自研） + RESP（复用） |
| 节点数 | 固定 3 |
| 一致性 | 强一致（多数派 ACK） |
| 文档版本 | v1.2（2026-08-04） |

**修订记录（v1.1 → v1.2）**：基于第二轮评审（P0×3 + P1×8 + 代码事实订正×6），定案/补充 —— ① **快照传输改为 chunked INSTALL_SNAPSHOT**（默认 chunk 4MB），修复"v1 一次性传输"与单帧 body ≤ 16MB 的硬矛盾；② **dump.rdb 写者归属**：mesh 模式禁用 server 原 RDB save，dump.rdb 唯一写者 = SnapshotManager；③ 补充 MESHDOWN 客户端退避、lastIncludedIndex 与 dump.rdb 非原子写的常态容错、read-index 退化细化、PreVote、写吞吐预期；④ 订正代码事实：SlotUtils 路径（core 无此文件 → 改用 `luban-rds-common`）、nodeId "40 字符 hex"、initClusterMode 行号 338-486、写方法 50+/36 个有返回值。

**修订记录（v1.0 → v1.1）**：基于设计评审结论定案 5 项关键问题 —— ① 写路径拦截点从 store 装饰器改为 **handler 命令层 gate**（原始 RESP 帧直接入 Raft，apply 响应字节直写客户端）；② 持久化模型定案（`lastIncludedIndex` + dump.rdb 快照落盘 + 重启恢复顺序）；③ **AOF 在 mesh 模式退役**（Raft log 即 WAL）；④ 新增 **CLUSTER SLOTS/NODES/INFO** 客户端引导命令；⑤ 读路径定案为 **Leader Lease 心跳租约**。同步新增 MULTI/EXEC 单条目、BLOCK 命令禁用、fsync 时序、周期快照等决策。

---

## 一、模块定位

### 1.1 目标

为 igbp-luban-rds 提供一个**只需 3 台机器**就能满足高可用的集群模式，替代现有的 6 节点 Redis Cluster 模式，降低部署成本与资源冗余。

### 1.2 核心卖点

| 维度 | Redis Cluster（现有） | Mesh（本模块） |
|------|----------------------|----------------|
| 机器数 | 6+（3 主 3 从） | **3**（互为副本） |
| 数据分片 | 16384 Slot 分片 | 全量数据，无分片 |
| 一致性 | 最终一致（异步复制） | **强一致**（多数派 ACK） |
| 切换时丢数据 | 可能（master→slave 异步） | 不会（未 commit 写入被覆盖） |
| 客户端兼容 | Redis Cluster aware 客户端 | **集群感知客户端零侵入**（JedisCluster / lettuce cluster 经 `CLUSTER SLOTS` + MOVED 自动跟随）；普通客户端需连 Leader 或自行处理 MOVED |

### 1.3 设计原则

1. **协议方案 Z**：管理面自研（心跳/选举/日志复制）+ 数据面兼容 RESP
2. **节点拓扑**：3 节点互为副本（mesh），每节点既是 Leader 候选，也是其他节点的 Follower
3. **强一致优先**：写入必须多数派（2/3）确认并**落盘**才返回 OK
4. **代码独立性**：不依赖 cluster 模块，独立模块边界
5. **拦截点在命令层**：写命令以**原始 RESP 帧**（客户端发来的那份字节）入 Raft 日志，不做 store 方法级重编码（杜绝语义漂移）
6. **apply 只用 raw store**：日志应用阶段直接调 `DefaultCommandHandler.handle(..., rawMemoryStore)`，**绝不经过任何拦截层**（防止递归 propose）

---

## 二、节点拓扑与角色

### 2.1 拓扑

```
        ┌──────────────────┐
        │  MeshNode A      │  ← Leader（任一时刻最多 1 个）
        │  role: LEADER    │
        │  term: T         │
        └──┬──────────┬────┘
           │          │
   AppendEntries  AppendEntries
           │          │
   ┌───────▼──┐   ┌───▼────────┐
   │ MeshNode │   │ MeshNode C │  ← Followers
   │ role:    │   │ role:      │
   │ FOLLOWER │   │ FOLLOWER   │
   │ term: T  │   │ term: T    │
   └──────────┘   └────────────┘
```

- 3 节点互连（mesh 拓扑），每节点到其他两节点都有独立 Netty 长连接
- 任一时刻只有 1 个 Leader，通过 Raft 选举保证（多数派投票）
- 强一致写：写入必须多数派（2/3）确认**并落盘**才能 apply 并返回客户端 OK

### 2.2 角色

| 角色 | 职责 |
|------|------|
| **FOLLOWER** | 默认状态；被动接收 AppendEntries；选举超时后转为 CANDIDATE |
| **CANDIDATE** | 选举中；发起 RequestVote；获得多数票转 LEADER |
| **LEADER** | 处理所有客户端写入；向 Followers 复制日志；维持心跳与租约 |

---

## 三、状态机

### 3.1 节点状态字段

每个 `MeshNode` 维护以下状态：

| 字段 | 类型 | 含义 | 持久化 |
|------|------|------|--------|
| `currentTerm` | long | 当前任期号，单调递增 | ✅ 重启恢复 |
| `votedFor` | String | 当前任期投票给的候选者 nodeId | ✅ 重启恢复 |
| `log[]` | List\<LogEntry\> | 日志条目数组（含快照截断后的 tail） | ✅ 重启恢复 |
| `lastIncludedIndex` | long | 最近一次快照包含的最后日志索引（快照截断的边界） | ✅ 重启恢复 |
| `lastIncludedTerm` | long | lastIncludedIndex 对应的任期 | ✅ 重启恢复 |
| `commitIndex` | long | 已提交的日志索引 | ❌ 重启后由快照 + 日志重放重建 |
| `lastApplied` | long | 已应用到状态机的索引 | ❌ 重启后由快照 + 日志重放重建 |
| `role` | Enum | FOLLOWER/CANDIDATE/LEADER | ❌ 运行时 |
| `leaderId` | String | 当前已知 Leader 的 nodeId | ❌ 运行时 |

**Leader 额外字段**：

| 字段 | 类型 | 含义 |
|------|------|------|
| `nextIndex[]` | Map\<nodeId, Long\> | 下一个要发给某节点的日志索引 |
| `matchIndex[]` | Map\<nodeId, Long\> | 某节点已确认的最高日志索引 |
| `lease` | LeaseManager | 心跳租约（见 §5.7 读路径） |

### 3.2 LogEntry 结构

```java
class LogEntry {
    long term;            // 创建时的任期号
    long index;           // 日志中的位置（1-based）
    byte[] respPayload;   // 完整 RESP 命令帧（如 "*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n"）
    int dbIndex;          // 命令作用的 db（apply 时传给 handler 的 database 参数）
    byte[] extra;         // 可选扩展载荷：MULTI/EXEC 事务为"命令帧序列"；WATCH 事务为版本快照（见 §5.8）
}
```

**为什么是 RESP 帧**：

- 与客户端发来的字节**完全一致**（handler 在解析处已捕获原始帧），apply 阶段直接走现有 RESP 解析 → `DefaultCommandHandler.handle(...)` → `MemoryStore`，零重编码、零语义漂移
- 复用 100% 业务路径：**apply 的返回值就是客户端的响应字节**（`+OK\r\n`、`:5\r\n`、数组等），无需任何返回值转换管线

---

## 四、RPC 协议

### 4.1 消息类型

| 消息 | 编码 | 方向 | 含义 |
|------|------|------|------|
| `APPEND_ENTRIES` | `0x60` | Leader → Follower | 心跳 + 日志复制（entries 为空即心跳） |
| `APPEND_ENTRIES_RESP` | `0x61` | Follower → Leader | 复制结果（success=true 同时作为租约确认） |
| `REQUEST_VOTE` | `0x62` | Candidate → All | 选举投票请求 |
| `REQUEST_VOTE_RESP` | `0x63` | All → Candidate | 投票结果 |
| `INSTALL_SNAPSHOT` | `0x64` | Leader → Follower | 快照传输 |

消息码 0x60-0x64 与 cluster 模块（0x40 起）不冲突；两侧总线端口 +10000 / +11000 也不冲突。

### 4.2 帧格式

与 `luban-rds-cluster` 的 `GossipMessage` 帧同构（参考 `ClusterBusCodec.java` 编解码循环）：

```
┌─────────────────────────────────────────────────────┐
│ 40B senderNodeId │ 1B type │ 4B messageLength (BE)  │
│                  │         │ = body 长度            │
├─────────────────────────────────────────────────────┤
│ body...（消息体，长度 ≤ 16MB，与 cluster 帧上限对齐）│
└─────────────────────────────────────────────────────┘
```

帧头共 45 字节（40B nodeId + 1B type + 4B length），**无 term 字段**——term 在消息体内传递（Raft 要求每个 RPC 携带 term）。nodeId 为 **40 字符 hex**（SHA-1，160 bit = 20 字节，编码为 40 个 ASCII 字节，与 cluster 的 `NODE_ID_LENGTH=40` 一致），发送方可直接获知消息来源，无需 fromNodeIdHash。body 单帧上限 16MB（与 cluster `MAX_BODY_LENGTH` 对齐），**大快照走 chunked INSTALL_SNAPSHOT**（见 §5.4）。

### 4.3 消息体字段

#### AppendEntriesMessage
```java
{
    long term;              // Leader 当前任期
    String leaderId;        // Leader nodeId
    long prevLogIndex;      // 上次同步到的日志索引
    long prevLogTerm;       // prevLogIndex 对应的任期
    List<LogEntry> entries; // 本次推送的日志条目（心跳时为空）
    long leaderCommit;      // Leader 已提交的索引
}
```

#### AppendEntriesResponse
```java
{
    long term;              // Follower 当前任期（用于 Leader 更新自己）
    boolean success;        // 是否接受（任期/日志一致性校验 + 已落盘）
    long matchIndex;        // Follower 已确认的最高索引
}
```

#### RequestVoteMessage
```java
{
    long term;              // Candidate 任期
    String candidateId;     // Candidate nodeId
    long lastLogIndex;      // Candidate 日志最后索引
    long lastLogTerm;       // 最后索引对应的任期
}
```

#### RequestVoteResponse
```java
{
    long term;              // 投票者当前任期
    boolean voteGranted;    // 是否投票
}
```

#### InstallSnapshotMessage
```java
{
    long term;                      // Leader 任期
    String leaderId;                // Leader nodeId
    long lastIncludedTerm;          // 快照对应的最后任期
    long lastIncludedIndex;         // 快照对应的最后索引
    long offset;                    // 数据偏移（chunked 传输用，v1 固定 0，一次性传输）
    byte[] data;                    // RDB 字节
    boolean done;                   // 是否最后一个 chunk
}
```

---

## 五、关键流程时序

### 5.1 场景 1：客户端写请求（handler gate 路径）

```
Client → Leader (SET k v)
   │
   ├── 1. RedisServerHandler.processCommand 解析命令：
   │        原始帧 rawRespFrame 已捕获（channelRead 阶段）
   │        mesh 模式下写命令 → MeshWriteGate.propose()
   │
   ├── 2. gate.propose(rawRespFrame, dbIndex):
   │     - meshNode.isLeader()? 否 → MovedToLeaderException（见场景 3）
   │     - log.append({term, index, respPayload, dbIndex}) + 持久化（fsync 完成后）
   │     - 并行发送 AppendEntries 给 2 个 Follower
   │     - 当前线程阻塞等待（CompletableFuture，apply 完成后由 LogApplier 唤醒）
   │
   ├── 3. Follower.handleAppendEntries:
   │     - 任期/日志一致性校验（prevLogIndex/prevLogTerm）
   │     - 追加日志 + 持久化（fsync 完成后才返回 success=true）
   │
   ├── 4. Leader 收到多数派 (含自己 = 2/3) matchIndex:
   │     - commitIndex 推进
   │     - applyLogToStateMachine()（串行、用 raw store，绝不过拦截层）:
   │         * RESP 解析 → DefaultCommandHandler.handle → DefaultMemoryStore 写
   │         * 捕获 handler 返回值 = 客户端响应对象（如 "+OK\r\n"）
   │     - future.complete(apply 产生的响应字节)
   │     - 下一轮 AppendEntries 携带 leaderCommit
   │
   ├── 5. Follower 收到带 leaderCommit 的 AppendEntries:
   │     - apply 到 raw store（响应对象丢弃，仅推进 lastApplied）
   │
   └── 6. gate 拿到响应字节 → 写回客户端 Channel
```

**强一致保证**：步骤 4 之前 client 不会收到 OK，因此 Leader 切换时未提交的写入会被新 Leader 覆盖（一致性 > 可用性，符合 Raft 语义）。

**持久化时序**（防"已确认写入丢失"）：Follower 必须在**日志落盘（fsync）完成后**才返回 `success=true`；Leader 必须在**自身日志落盘后**才 complete future 回客户端。落盘采用原子写（临时文件 + fsync + rename，复用 `ClusterConfigPersister` 模式），不允许批量延迟 flush 出现在确认路径上（批量 flush 只用于日志压缩等非关键路径）。

**AOF 退役**：mesh 模式**不写 AOF**——Raft log 即 WAL、dump.rdb 即快照（见 §5.4/§5.5）。server handler 中 AOF 记录与复制传播段（`RedisServerHandler.java:805-812` 附近，含 `propagateCommand`）在 mesh 模式整体跳过，防止双写与 backlog 干扰。

### 5.2 场景 2：Leader 选举

```
初始：3 节点全为 FOLLOWER, term=T, 都投过票给 Leader X
事件：X 心跳超时（election timeout 150-300ms 随机）
   │
   ├── A: currentTerm++, role=CANDIDATE, votedFor=self
   │     投自己 1 票
   │
   ├── A: 并行 RequestVote 给 B, C
   │     body: {term=T+1, candidateId=A, lastLogTerm, lastLogIndex}
   │
   ├── B: 收到 RequestVote
   │     - 任期 ≥ T+1? ✅
   │     - candidate 日志 ≥ 自己日志? ✅
   │     - 没投过别人? ✅
   │     - 投 A, votedFor=A（持久化）, 返回 granted
   │
   ├── C: 同上，投 A
   │
   ├── A: 收到 2 张票（自己 + B = 多数派 = 2/3）
   │     role=LEADER
   │     初始化 nextIndex/matchIndex、启动 LeaseManager
   │     立即发空 AppendEntries（心跳）建立权威 + 首轮续租
   │
   └── B, C: 收到 A 的 AppendEntries, term=T+1
         - role=FOLLOWER, leaderId=A
```

**PreVote（v1 推荐）**：被网络隔离的单节点无法收到心跳，会不断自增 term 发起选举；恢复后用大 term 踢掉正常运行 Leader，加剧抖动。PreVote 在**不自增 term** 的前提下先探测"能否赢"，赢不了则不推进 term，防止 term 膨胀。v1 若不实现需在文档注明此风险，v2 补全。

### 5.3 场景 3：Follower 收到写请求（MOVED 重定向）

```
Client → Follower (SET k v)
   │
   ├── 1. gate.propose() 检测 role=FOLLOWER
   │     抛 MovedToLeaderException(leaderAddr)
   │
   ├── 2. RedisServerHandler 新增专用 catch（在通用异常 catch 之前）:
   │     - leaderId 已知 → -MOVED <key真实CRC16 slot> <leader-service-ip>:<leader-service-port>\r\n
   │     - leaderId 未知（未选出 Leader）→ -MESHDOWN The mesh cluster has no leader\r\n
   │
   └── 3. 集群感知客户端收到 MOVED 后刷新拓扑并重连 Leader
```

**关键点**：
- **slot 用 key 的真实 CRC16 slot**（复用 `luban-rds-common` 的 `SlotUtils.getSlot`，返回 0–16383；mesh 模块不依赖 cluster，故不复用 cluster 的 `SlotUtils.keyHashSlot`），不用固定占位值——部分客户端依赖 slot 更新路由缓存
- **地址必须是 service 端口**（6379/6380/6381），不是 bus 端口（11000）；MeshConfig 维护 nodeId → serviceAddr ↔ busAddr 映射
- 同一 catch 分支同时覆盖 EXEC 事务内逐条命令的异常路径
- **MESHDOWN 客户端退避**：建议集群感知客户端收到 MESHDOWN 后指数退避重试（默认 200ms 起步，上限 2s），避免无 Leader 期间雪崩；普通客户端无此行为，由用户自行处理

### 5.4 场景 4：节点落后追平（chunked Snapshot + 落盘）

```
节点重启后 term/log 落后太多
   │
   ├── 1. 启动时通过 AppendEntries 心跳发现 matchIndex 不匹配
   │
   ├── 2. Leader.sendInstallSnapshot(followerId) —— chunked 传输:
   │     按 offset 切片发多个 INSTALL_SNAPSHOT（默认 chunk 4MB）：
   │       body: {term, leaderId, lastIncludedTerm, lastIncludedIndex, offset, data=chunkBytes, done}
   │     （单帧 body ≤ 16MB，几百 MB 的快照无法单帧传输，故 v1 必须 chunked；
   │        RDB 字节来自 RdbSnapshotGenerator 文件生成路径——其 generateTempRdbFile
   │        已具备落盘能力但是 private，需提可见性或新增 public 切片读取 API；
   │        现有 generateAndTransfer 走 channel 流式，不直接返回字节）
   │
   ├── 3. Follower.handleInstallSnapshot —— 累积拼装:
   │     - 按 offset 将 chunk 写入临时 RDB 文件
   │     - done=true 时整体校验 lastIncludedTerm/Index 与任期
   │     - 调 RdbDataLoader.startLoading(MemoryStore, monitor) → writeChunk(ByteBuf) → finishLoading(MemoryStore)
   │       （加载内存 + 落盘 dump.rdb；注意修复 keysLoaded 恒为 0 的统计 bug——
   │        当前实现只 set(0) 无 incrementAndGet）
   │     - 截断本地 log，保留 lastIncludedIndex 之后的条目
   │     - 更新 commitIndex = lastApplied = lastIncludedIndex
   │     - 持久化 MeshState（含 lastIncludedIndex/lastIncludedTerm）到 raft-nodes.conf
   │
   └── 4. Follower 恢复正常，继续接收 AppendEntries
```

**dump.rdb 写者归属（v1.2 定案）**：mesh 模式**禁用 server 原 RDB save（BGSAVE / PersistService save 路径）**——dump.rdb 的唯一写者 = SnapshotManager。server 侧 save 相关路径在 `mesh-enabled` 时整体 gate，与"AOF 退役"（§5.1）同处理。否则两条路径互相覆盖 dump.rdb，会导致 §5.5 的"dump.rdb 快照索引 = lastIncludedIndex"校验失效。

**周期快照（防日志无界增长）**：各节点独立按阈值触发（每 N 条日志或累计 M 字节，如 10 万条 / 256MB），生成快照 → 落盘 dump.rdb → 截断日志 → 更新并持久化 lastIncludedIndex/Term。Leader 侧的周期快照同样落盘本地 dump.rdb。**lastIncludedIndex 与 dump.rdb 非原子写**：二者分别落盘，若衔接不上（如进程在两者之间崩溃），降级为"标记本地状态不可信，选举后由 Leader INSTALL_SNAPSHOT 全量追平"（见 §5.5）——此为**常态容错**而非异常。

### 5.5 场景 5：节点重启恢复（快照 + 日志回放）

```
1. 读 config.mesh.* → 建立 peers 连接信息（peers 列表通常含自身 nodeId，
   MeshBusClient 过滤自身，避免节点自连）
2. 加载 raft-nodes.conf → MeshState（currentTerm/votedFor/logTail/lastIncludedIndex/lastIncludedTerm）
3. 若磁盘存在 dump.rdb 且其快照索引 = lastIncludedIndex:
     - RdbDataLoader 载入内存（快照作为状态地基）
   else（无快照 / 衔接不上）:
     - 内存为空，标记"本地状态不可信"
4. 将 logTail 中 index > lastIncludedIndex 的条目**按序 apply 到内存之上**
   （快照 + tail 重放 = 完整的已提交状态）
5. 启动 MeshBus + ElectionTimer，参与选举；若本地状态不可信，
   选举后由 Leader 以 INSTALL_SNAPSHOT 全量追平
```

**为什么必须这样**：log 被快照截断后，raft-nodes.conf 里只有 logTail；如果跳过第 3 步直接在空库上重放 tail，`INCRBY/HSET/DEL` 等依赖前置数据的命令会产出错误状态，节点带着错误状态参与选举会污染整个集群。**绝不回放 AOF**（mesh 模式无 AOF）。

### 5.6 场景 6：集群感知客户端引导（CLUSTER SLOTS）

```
Client（JedisCluster / lettuce cluster）→ 任意节点
   │
   ├── 1. 客户端启动引导：CLUSTER SLOTS
   │
   ├── 2. MeshClusterCommands 响应（mesh 模式下由 RedisServerHandler 的
   │      CLUSTER 命令分支接管，cluster 模式原有逻辑不受影响）:
   │      [[0, 16383, [leaderServiceIp, leaderServicePort, leaderNodeId]]]
   │      —— 16384 个 slot 全部指向当前 Leader（mesh 无分片）
   │
   ├── 3. 客户端据此把所有命令直发 Leader（写零重定向）
   │
   └── 4. Leader 变更后：客户端收到 MOVED → 刷新拓扑（再调 CLUSTER SLOTS）→ 重连新 Leader
```

同时实现 `CLUSTER NODES`（3 节点一行一个，对齐 CLUSTER NODES 格式）、`CLUSTER INFO`（`cluster_state:ok`、`cluster_known_nodes:3` 等）。**这是"集群感知客户端零侵入"的成立前提**：没有 CLUSTER SLOTS，JedisCluster/lettuce cluster 无法引导。普通客户端（`new Jedis()`/redis-cli）不跟随 MOVED，需连 Leader 或自行处理重定向——README 卖点措辞与此一致。

### 5.7 场景 7：客户端读请求（Leader Lease）

```
Client → Leader (GET k)
   │
   ├── 1. gate 读路径：
   │     - meshNode.lease.isValid(now)?
   │         ✅ 有效 → 本地读（raw store）→ 响应字节回客户端
   │         ❌ 失效/未知 → 阻塞至下一轮心跳多数派 ACK 续租后读
   │
   ├── 2. 非 Leader → MovedToLeaderException → MOVED（同场景 3）
   │
   └── 3. 读在 handler 线程执行，与 apply 线程并发访问 raw store
         （DefaultMemoryStore 并发容器 + 1024 分段锁支撑；整批写路径
          synchronized(store) 由 apply 串行保证互斥）
```

**租约机制**（v1 完整版，非"仅靠选举身份"）：

- Leader 每轮心跳（100ms）广播 AppendEntries，**收到多数派（含自己）`success=true` 即续租**：`leaseExpireAt = now + leaseDuration`
- `leaseDuration = 2 × electionTimeout`（默认 300ms×2 = 600ms，可配置）
- 读命令仅在租约有效期内本地执行；过期则等待下一轮续租
- **前置条件**：节点间时钟偏差 < leaseDuration/2（部署要求 NTP 对齐）
- 时钟不可靠的环境退化为 **read-index**：读前**主动向多数派发一次心跳**校验任期，确认 `commitIndex ≥ lastApplied` 之后才读。注意 read-index 与 `LeaseManager.awaitValid` 是**两种不同机制**——awaitValid 是被动等下一轮续租（不发额外心跳），read-index 是主动发心跳 + 校验 commitIndex；二者由配置 `mesh-read-consistency = lease | readindex` 切换

**为什么需要租约**：仅靠"我是 Leader"的身份不防分区——旧 Leader 被隔开后不知情，仍会本地回答读请求，客户端经 MOVED 连上它读到陈旧数据，破坏强一致卖点。租约保证"能读 = 仍在与多数派通信 = 仍是真 Leader"。

### 5.8 场景 8：MULTI/EXEC 事务（单条日志）

```
Client → Leader (MULTI / SET a 1 / INCR b / EXEC)
   │
   ├── 1. MULTI 起：命令照常入连接级事务队列（+QUEUED），不过 gate
   │
   ├── 2. EXEC 到达 gate:
   │     - 构造单条 LogEntry：payload = MULTI 帧，extra = 队列内各命令帧序列
   │       + WATCH 版本快照（db|key → getKeyVersion，MULTI 后捕获）
   │     - propose（与普通写同路径：多数派 commit + apply）
   │
   ├── 3. apply（raw store，各节点一致执行）:
   │     - 先按 extra 中版本快照做 WATCH 校验 → 不匹配返回 *-1
   │     - 按序执行队列内命令，收集各命令响应 → 组装成 RESP 数组
   │     - 响应数组 = 客户端 EXEC 的响应（由 LogApplier 捕获返回）
   │
   └── 4. Leader 响应字节直写客户端；事务内命令不再逐条产生日志
```

**为什么必须单条目**：若事务内每条命令各成一条日志，中途故障/分区时事务部分生效，破坏原子性；WATCH 校验与 commit 异步也会产生竞态。单条目使整个事务在任意节点要么全部生效要么全部不生效。

---

## 六、模块文件树

```
luban-rds-mesh/
├── pom.xml                                        依赖 core/protocol/persistence/replication(RDB)
├── README.md                                      快速上手
└── src/main/java/com/janeluo/luban/rds/mesh/
    ├── MeshCluster.java                           顶层集群实例，持有 3 个 MeshNode + 配置
    ├── MeshNode.java                              节点主体，状态机入口（propose/read/lease）
    ├── MeshConfig.java                            配置（electionTimeout/heartbeat/busPort=service+11000/
    │                                              serviceAddr↔busAddr 映射/租约时长/快照阈值/dbDir）
    │
    ├── core/
    │   ├── MeshRole.java                          FOLLOWER/CANDIDATE/LEADER 枚举
    │   ├── MeshState.java                         currentTerm/votedFor/log/lastIncludedIndex/
    │   │                                          lastIncludedTerm/commitIndex/lastApplied/leaderId
    │   ├── LogEntry.java                          {term, index, respPayload, dbIndex, extra}
    │   ├── PersistentStateStore.java              term/votedFor/logTail/lastIncluded* → raft-nodes.conf
    │   └── RaftStateMachine.java                  状态机转换逻辑
    │
    ├── rpc/
    │   ├── MeshRpcMessage.java                    RPC 消息基类（含 type/term/fromNodeId）
    │   ├── AppendEntriesMessage.java              term, leaderId, prevLogIndex, prevLogTerm, entries[], leaderCommit
    │   ├── AppendEntriesResponse.java             term, success, matchIndex
    │   ├── RequestVoteMessage.java                term, candidateId, lastLogIndex, lastLogTerm
    │   ├── RequestVoteResponse.java               term, voteGranted
    │   ├── InstallSnapshotMessage.java            term, leaderId, lastIncludedTerm, lastIncludedIndex, offset, data, done
    │   └── MessageType.java                       0x60-0x64 枚举
    │
    ├── bus/                                       与 cluster/bus 同构的独立实现（帧头含 40B nodeId）
    │   ├── MeshBusServer.java                     Netty 服务端，bind busPort
    │   ├── MeshBusClient.java                     出站连接（去重/退避/keepalive，参考 ClusterBusClient）
    │   ├── MeshBusCodec.java                      帧编解码（40B nodeId + 1B type + 4B length + body）
    │   └── MeshBusHandler.java                    入站分发（反序列化为 RPC 类 → MeshNode）
    │
    ├── election/
    │   ├── ElectionTimer.java                     随机超时 150-300ms（避免 split vote）
    │   ├── VoteCollector.java                     收集投票 + 多数派判定
    │   └── LeaseManager.java                      心跳租约（续租/校验/等待续租，见 §5.7）
    │
    ├── replication/
    │   ├── LogReplicator.java                     Leader 侧：nextIndex/matchIndex + 批量 AppendEntries
    │   ├── LogApplier.java                        通用：apply LogEntry 到 raw store（RESP 解析 →
    │   │                                          DefaultCommandHandler.handle → raw MemoryStore，
    │   │                                          返回响应对象；严禁经过任何拦截层）
    │   └── SnapshotManager.java                   Leader 侧触发（含周期快照）+ Follower 侧接收落盘
    │
    ├── gateway/
    │   └── MeshWriteGate.java                     handler 级门面：写/事务 propose、读路径租约校验、
    │                                              MOVED 响应生成（见 §7.2）
    │
    ├── client/
    │   ├── MeshClientRedirector.java              MOVED/MESHDOWN 响应生成（真实 slot + service 端口）
    │   ├── MovedToLeaderException.java            内部异常，RedisServerHandler 专用 catch 捕获
    │   └── MeshClusterCommands.java               CLUSTER SLOTS/NODES/INFO 响应生成（16384 全 slot → Leader）
    │
    └── lifecycle/
        ├── MeshLifecycleListener.java             role 变更回调（启停某些功能）
        ├── MeshBootstrap.java                     启动装配入口（仿 initClusterMode；节点恢复顺序见 §5.5）
        └── MeshConfigPersister.java               raft-nodes.conf 原子读写（tmp + fsync + ATOMIC_MOVE）
```

**server 模块配合面**（最小侵入，均在 mesh 模式配置开关下生效）：

1. `RedisServerHandler.processCommand`：在 cluster 重定向门（`RedisServerHandler.java:781-787`）同位置插入 mesh 门——写命令/EXEC → `MeshWriteGate.write()`；读命令 → 租约检查或 MOVED
2. `RedisServerHandler` 异常处理：新增 `MovedToLeaderException` 专用 catch（在通用 `catch (Exception e)` 即 `RedisServerHandler.java:847` **之前**，全仓库当前不存在该类，待新增），生成 MOVED/MESHDOWN 响应
3. `RedisServerHandler:798-812`（命令执行 + AOF 记录 `:810` + 复制传播 `propagateCommand:806`）：mesh 模式整体跳过（AOF 退役）
4. `RedisServerHandler` CLUSTER 命令分支：mesh 模式接管为 `MeshClusterCommands`
5. `NettyRedisServer`：`memoryStore`（`NettyRedisServer.java:78`，当前 `final`）改为可替换（构造时按 `mesh-enabled` 分支），新增 `initMeshMode()` 装配（参考 `initClusterMode()`，实际 `NettyRedisServer.java:338-486`）
6. **RDB save 路径**：mesh 模式禁用 server 原 RDB save（BGSAVE / PersistService save），dump.rdb 唯一写者 = SnapshotManager（见 §5.4）
7. 配置入口走 `luban-rds.conf`（`mesh-enabled yes`、`mesh-peers` 等）；`luban-rds-bin` 的 CLI 参数解析（当前仅 `--config/--port/--help`）扩展为可选 mesh 参数

---

## 七、关键类签名

### 7.1 MeshNode（节点主体）

```java
public class MeshNode {
    private final String nodeId;
    private final MeshRole role;                    // volatile, 由选举/AppendEntries 切换
    private final MeshState state;                  // 持读写锁
    private final MeshBusServer busServer;
    private final MeshBusClient busClient;
    private final ElectionTimer electionTimer;
    private final LogReplicator replicator;
    private final LogApplier applier;               // 只持有 raw store
    private final LeaseManager lease;

    /** 客户端写入口（gate 调用）：propose 后阻塞，apply 完成后 future 携带响应字节 */
    public CompletableFuture<byte[]> propose(byte[] respFrame, int dbIndex, byte[] extra);

    public boolean isLeader();
    public InetSocketAddress getLeaderServiceAddr();   // service 端口，非 bus 端口
    public LeaseManager lease();
    public void start();
    public void stop();
}
```

### 7.2 MeshWriteGate（handler 级门面，替代 store 装饰器）

```java
public class MeshWriteGate {
    private final MeshNode meshNode;
    private final MemoryStore rawStore;        // 真实 DefaultMemoryStore——apply 的唯一执行目标
    private final DefaultCommandHandler handler;

    /** 写命令/事务：propose 并阻塞至 commit+apply，返回 apply 产生的响应字节 */
    public byte[] write(byte[] rawRespFrame, int dbIndex, byte[] extra);

    /** 读命令：租约有效则本地执行并返回响应字节；非 Leader/租约失效走 MOVED 或等待续租 */
    public byte[] read(int dbIndex, String[] args);

    /** MOVED/MESHDOWN 响应生成（key 真实 CRC16 slot + service 地址） */
    public String redirectResponse(String key);
}
```

**为什么不做 store 装饰器**：MemoryStore 有 **50+ 写方法且 36 个有返回值**（`incrby→long`、`lpop→String`、`xadd→StreamId`、`del→boolean`、`hset→int`…），装饰器需要在 apply 前返回这些值——要么逐方法做"帧→返回值"的反射管线，要么重编码 50+ 个方法签名（语义漂移风险），还伴随 apply 递归风险。命令层的 gate 只需要 `commandName/args/rawFrame` 三样东西，响应天然是 apply 的返回值，零转换。

### 7.3 LogEntry

```java
public class LogEntry {
    final long term;
    final long index;
    final byte[] respPayload;    // 完整 RESP 命令帧（事务时为 MULTI 帧）
    final int dbIndex;
    final byte[] extra;          // 事务：命令帧序列 + WATCH 版本快照（可为 null）
}
```

### 7.4 PersistentStateStore

```java
public class PersistentStateStore {
    void persist(MeshState state);  // 原子写 raft-nodes.conf（tmp + fsync + ATOMIC_MOVE）
    MeshState load();               // 启动加载（含 lastIncludedIndex/lastIncludedTerm）
}
```

raft-nodes.conf 格式：

```json
{
  "nodeId": "abc123...",
  "currentTerm": 5,
  "votedFor": "xyz789...",
  "lastIncludedIndex": 100,
  "lastIncludedTerm": 4,
  "logTail": [
    {"term": 5, "index": 101, "dbIndex": 0, "payload": "<base64>"}
  ]
}
```

### 7.5 LeaseManager

```java
public class LeaseManager {
    volatile long leaseExpireAt;          // 租约截止（本地时钟）
    void refreshOnMajorityAck(long now);  // 心跳多数派 ACK 时续租
    boolean isValid(long now);
    boolean awaitValid(long timeoutMs);   // 失效时阻塞至下一轮续租（供读路径使用）
}
```

---

## 八、与 cluster 模块的边界

| 维度 | cluster/ | mesh/ |
|------|----------|-------|
| Maven 依赖 | core, protocol | core, protocol, persistence, replication(RDB only) |
| 总线端口 | port + 10000 | port + 11000（可配置显式 busPort） |
| 节点状态 | HANDSHAKE/MASTER/SLAVE/PFAIL/FAIL/MYSELF | FOLLOWER/CANDIDATE/LEADER |
| 复制语义 | 异步（master→slave） | 同步（propose→多数派 ACK→apply） |
| Slot | 有（CRC16 分片） | 无（全量数据；CLUSTER SLOTS 全量映射到 Leader） |
| 与 NettyRedisServer 关系 | `initClusterMode()` 装配 | 新增 `initMeshMode()` 装配 |
| AOF 接入点 | server handler 命令执行后 | **mesh 模式 AOF 退役**（Raft log 即 WAL）；handler 的 AOF/传播段整体 gate |
| dump.rdb 写者 | server RDB save（BGSAVE） | **mesh 模式禁用 server RDB save**，dump.rdb 唯一写者 = SnapshotManager（见 §5.4） |
| 公共依赖 | `MemoryStore`, `PersistService`, `RdbSnapshotGenerator`, `RdbDataLoader`, `DefaultCommandHandler` | （同上） |

**关键边界**：mesh 模块**不依赖** cluster 模块的任何代码。Maven 依赖只有 core / protocol / persistence / replication（只取 RDB 部分）。cluster 与 mesh 在 server 侧由配置互斥（同一进程只能启用其一）。

---

## 九、风险点与缓解

| 风险 | 影响 | 缓解策略 |
|------|------|----------|
| 写路径 gate 阻塞业务线程直到多数派 commit | 写延迟 = 1 次网络 RTT | 强一致的必要代价；与 Redis Cluster 异步复制换取"不丢已确认写入"；批量/管线命令在 gate 内串行，v2 可做批量日志合并 |
| Lua EVAL/EVALSHA 是动态命令（运行时才能判定写/读） | 一致性风险 | mesh 模块**不识别** Lua 内容；统一当写命令走 Raft（牺牲一点性能换强一致） |
| PUBLISH 是 PubSub 外部状态修改，不在 MemoryStore | 一致性边界 | mesh 模块对 PUBLISH **不做拦截**，走原 PubSub 通道（允许轻微弱一致，与 cluster 一致） |
| 3 节点网络分区时只剩 1 节点 | 可用性 | 标准 Raft 行为：1 节点降级 Follower，拒绝写入，返回错误；恢复后自动收敛 |
| 旧 Leader 分区隔离期间仍服务读请求 | 陈旧读（破坏强一致卖点） | **Leader Lease 租约**：读仅在租约内（多数派心跳 ACK 续租）执行；时钟不可靠退化 read-index（见 §5.7） |
| 租约依赖节点时钟 | 时钟偏移过大时租约失效/误判 | 部署要求 NTP 对齐（偏差 < 租约/2）；超限时读请求退化为等待续租而非直接放行 |
| Raft Log 无界增长 | 重启回放变慢、磁盘膨胀 | **周期快照**（每 N 条/M 字节）+ 截断 + lastIncludedIndex 持久化（见 §5.4） |
| MULTI/EXEC 逐条入日志 | 事务部分生效 | **整事务单条 LogEntry**（见 §5.8），WATCH 版本快照随日志 apply 判定 |
| BLOCK 类命令（BLPOP/BRPOP/XREAD BLOCK）阻塞语义与 Raft apply 冲突 | apply 线程阻塞 / 唤醒路径绕过 Raft | **v1 在 mesh 模式禁用**（返回 `-ERR BLOCK commands are not supported in mesh mode`）；两条唤醒路径均绕拦截层（BLPOP 直调 `memoryStore.lpop` 在 `RedisServerHandler:2082-2086`；XREAD 唤醒在 Stream 等待器机制 `RedisServerHandler:2132/2146/2220-2466`，**非 BlockingRequestManager**）。v2 支持"立即命中"语义 + Raft 化唤醒 |
| 集群感知客户端引导 | 客户端无法连接 | 实现 `CLUSTER SLOTS/NODES/INFO`，16384 全 slot → Leader（见 §5.6） |
| Raft LogEntry 单帧上限 16MB | 大 key 写入 | 单条写命令受 16MB 限制；**快照传输走 chunked INSTALL_SNAPSHOT**（默认 chunk 4MB，见 §5.4），不受单帧限制 |
| 启动时快照与日志衔接不上 | 重启状态错乱 | 标记"本地状态不可信"，选举后由 Leader INSTALL_SNAPSHOT 全量追平（见 §5.5） |
| Leader 切换时客户端连接断开 | 客户端体验 | MOVED 重定向机制：集群感知客户端自动刷新拓扑并重连新 Leader |
| 写吞吐受单 Leader 串行 apply 限制 | 高并发写性能上限 | 写 RTT ≈ 1×网络 RTT（propose→多数派 ACK→apply）；Pipeline/lettuce 异步高并发写在 gate 串行，是强一致的必要代价；v2 可做批量日志合并提升吞吐 |
| 被隔离的单节点不断自增 term 重新选举 | term 膨胀、恢复后踢掉正常 Leader 加剧抖动 | 标准 Raft 能收敛；建议 v1 实现 **PreVote**（不自增 term 探测能否赢），防 term 膨胀（见 §5.2） |
| 重复日志/重复帧（网络重发） | 状态机重复执行 | Raft 幂等语义：Follower 按 prevLogIndex/prevLogTerm 校验截断；commitIndex 单调推进 |

---

## 十、测试策略

| 阶段 | 测试内容 |
|------|----------|
| 单元测试 | LogEntry 序列化、5 种 RPC 消息编解码、选举算法模拟（split vote/任期裁决）、状态机转换、**LeaseManager**（续租/失效/等待）、**MeshWriteGate**（读写分流/MOVED 生成/事务单条目）、PersistentStateStore 原子写（含损坏文件处理） |
| 集成测试 | 3 节点本地起进程，模拟各种故障（kill leader、kill follower、网络分区、时钟偏移注入） |
| 一致性测试 | 并发 SET 同一 key 多数派语义；**MULTI/EXEC 原子性**（事务中途 kill 节点，验证全有或全无）；**重启恢复**（快照 + logTail 重放后状态与故障前一致） |
| 性能测试 | 单节点 vs 3 节点写 RTT（多数派代价）、读 RTT（租约内本地读）、吞吐、不同 value 大小 |
| 兼容测试 | **JedisCluster / lettuce cluster 引导（CLUSTER SLOTS）**、MOVED 自动跟随、Leader 切换重连；普通客户端（Jedis 单机/redis-cli）行为与文档一致 |
| 故障注入 | kill -9 Leader、kill -9 Follower、partition 网络（含旧 Leader 隔离场景验证租约失效）、partition 恢复、节点重启后追平 |

---

## 十一、关键决策一览

| # | 主题 | 决策 |
|---|------|------|
| 1 | 痛点 | A+C（成本 + 资源冗余） |
| 2 | 协议方案 | Z（管理面自研 + 数据面 RESP 兼容） |
| 3 | 节点角色 | a（3 节点互为副本） |
| 4 | 数据分片 | 不分片，全量数据 |
| 5 | 一致性 | A（强一致） |
| 6 | 脑裂客户端 | i（MOVED 重定向） |
| 7 | 代码改造 | β'：新建独立模块 + **handler 命令层 gate**（原始 RESP 帧入 Raft，非 store 装饰器） |
| 8 | 传输层 | Y（独立 raft-bus，与 cluster/bus 同构，帧头 40B nodeId + 1B type + 4B length） |
| 9 | LogEntry 载荷 | P（完整 RESP 帧，apply 返回值即客户端响应） |
| 10 | AOF 路径 | I'：**AOF 退役**，Raft log 即 WAL、dump.rdb 即快照 |
| 11 | Snapshot | S3（RDB + Raft Log 双层）+ 快照落盘 + lastIncludedIndex 持久化 + 周期快照 |
| 12 | Follower 写请求 | a（MOVED 重定向，slot 用 key 真实 CRC16，地址用 service 端口） |
| 13 | 读路径 | a'：**Leader Lease 心跳租约**（多数派 ACK 续租；NTP 前提；退化 read-index） |
| 14 | 节点扩缩 | a（固定 3 节点，静态 meet） |
| 15 | 客户端引导 | 实现 CLUSTER SLOTS/NODES/INFO（16384 全 slot → Leader），集群感知客户端零侵入 |
| 16 | 事务 | MULTI/EXEC 整事务单条 LogEntry（含 WATCH 版本快照） |
| 17 | BLOCK 命令 | v1 禁用（返回错误），v2 支持立即命中 + Raft 化唤醒 |
| 18 | 持久化时序 | Follower 落盘才 ACK；Leader 落盘才 complete future（fsync 在确认路径上） |
| 19 | 日志压缩 | 周期快照（每 N 条/M 字节）防无界增长 |
| 20 | 配置入口 | luban-rds.conf（mesh-enabled/peers/超时/租约/快照阈值）；bin CLI 参数扩展为可选项 |

---

## 十二、参考资料

- **Raft 论文**：In Search of an Understandable Consensus Algorithm (Diego Ongaro, John Ousterhout, 2014)
- **Redis Cluster 规范**：https://redis.io/docs/reference/cluster-spec/
- **etcd Raft 实现**：https://github.com/etcd-io/etcd/tree/main/server/mvcc/backend
- **现有代码参考**（本仓库）：
  - `luban-rds-cluster/.../bus/GossipMessage.java`（帧结构：40B nodeId + 1B type + 4B length）、`ClusterBusCodec.java`（编解码）、`ClusterBusClient.java`（重连退避）
  - `luban-rds-server/.../RedisServerHandler.java`（`:349-360` rawRespFrame 捕获、`:781` 集群重定向门、`:799-812` 命令执行与 AOF/传播段、`:2969-3040` MOVED 生成）
  - `luban-rds-core/.../store/MemoryStore.java`（45+ 写方法全集）、`DefaultMemoryStore.java`（并发容器 + 1024 分段锁）
  - `luban-rds-replication/.../RdbSnapshotGenerator.java`（`generateAndTransfer` channel 流式；需新增 `generateToBytes`）、`RdbDataLoader.java`（startLoading/writeChunk/finishLoading；keysLoaded 统计 bug 待修复）
  - `luban-rds-cluster/.../config/ClusterConfigPersister.java`（tmp + fsync + ATOMIC_MOVE 原子写）
  - `luban-rds-common/.../util/SlotUtils.java`（`getSlot(key)`，0–16383，mesh 复用此版本；cluster 的同名方法 `keyHashSlot` 在 `luban-rds-cluster.slot.SlotUtils`，但 mesh 不依赖 cluster）
- **现有 cluster 模块**：`luban-rds-cluster/src/main/java/com/janeluo/luban/rds/cluster/`
- **现有 replication 模块**：`luban-rds-replication/src/main/java/com/janeluo/luban/rds/replication/`
