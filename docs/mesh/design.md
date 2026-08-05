---
title: Mesh 协议设计要点
last_updated: 2026-08-05
version: 1.0.15
---

# Mesh 协议设计要点

> 本节是 [luban-rds-mesh/docs/DESIGN.md](../../luban-rds-mesh/docs/DESIGN.md) v1.2 的摘要，配套阅读以获得完整的协议设计细节（拓扑、状态机、RPC、时序、关键决策 17 条等）。

## 1. 节点拓扑

```
        ┌──────────────────┐
        │  MeshNode A      │  ← Leader（任一时刻最多 1 个）
        │  role: LEADER    │
        │  term: T         │
        └──┬──────────┬────┘
           │          │
   AppendEntries  AppendEntries     (Leader 向 Followers 复制日志 + 心跳)
           │          │
   ┌───────▼──┐   ┌───▼────────┐
   │ MeshNode │   │ MeshNode C │  ← Followers（被动接收 AppendEntries）
   │ role:    │   │ role:      │
   │ FOLLOWER │   │ FOLLOWER   │
   │ term: T  │   │ term: T    │
   └──────────┘   └────────────┘
```

- 3 节点互连（mesh 拓扑），每节点到其他两节点有独立 Netty 长连接
- 节点间通信走私有协议（`MeshBusCodec` + `MeshFrame`），与 Redis 协议隔离
- service 端口（默认 9736）与 bus 端口（默认 11000）**分离**：客户端只连 service，节点间 RPC 只走 bus

## 2. 状态机

每个 `MeshNode` 在任一时刻处于三种角色之一（`MeshRole` 枚举）：

| 角色 | 触发条件 | 主要行为 |
|------|----------|----------|
| **FOLLOWER** | 启动默认 / Leader 卸任 / Candidate 落选 | 被动接收 AppendEntries；选举超时后转 Candidate |
| **CANDIDATE** | 选举超时（默认 150~300ms 随机化） | 先 PreVote 探测（**不自增 term**）；多数派预投通过后正式增 term 并发起 RequestVote |
| **LEADER** | 获 2/3 投票 | 处理客户端写入；向 Followers 复制日志；维持心跳与读租约 |

辅助状态枚举（`MeshState`）：

- `LOOKING`：选举中（Candidate 或 PreVote）
- `FOLLOWING`：Follower 稳定态
- `LEADING`：Leader 稳定态

**状态切换触发**：

- FOLLOWER 超时（electionTimer 触发）→ LOOKING（Candidate + 增 term 之前先 PreVote）
- LOOKING 获多数票 → LEADING
- LOOKING 收到更高 term 的 AppendEntries / RequestVote → FOLLOWING
- LEADING 发现更高 term → FOLLOWING（step down）

## 3. RPC 协议

`MeshRpcMessage` 是 RPC 消息基类，子类 5 类：

| RPC | 方向 | 用途 |
|-----|------|------|
| `AppendEntriesMessage` | Leader → Follower | 日志复制 + 心跳 |
| `AppendEntriesResponse` | Follower → Leader | 接受 / 拒绝（含当前 term / index） |
| `RequestVoteMessage` | Candidate → Follower | 正式投票（含 term / 候选 ID / 候选 log up-to-date） |
| `RequestVoteResponse` | Follower → Candidate | 投票授予 / 拒绝（含投票者持槽 + voted_time 校验） |
| `InstallSnapshotMessage` | Leader → Follower | chunked snapshot 传输（防日志无界增长） |

**关键约束**（v1.0.13 R2 审计沉淀，mesh 也遵循）：

- 消息码从 0x40 起（避免与 Redis 现有消息码冲突，仿 cluster 模式）
- 投票者校验：必须拥有当前 epoch 槽位 + voted_time，防止伪造投票
- 候选 epoch 裁决：冲突 epoch 时取 lastVoteEpoch 大者

## 4. 选举（PreVote + Lease）

### 4.1 PreVote 防 term 膨胀

- Follower 在选举超时后**先**发 `RequestVote` 探测（term 不增）
- 多数派预投通过 → 正式增 term 发起 `RequestVote`
- PreVote 失败退避区间翻倍封顶（v1.0.15 `d4dc1ad` 修复 kill follower 后选举风暴）

### 4.2 Lease 心跳租约

- Leader 每 100ms（`mesh-heartbeat-interval-ms`）发送 AppendEntries（同时承担心跳）
- 默认租约时长 = 2 × electionTimeout（600ms）
- Follower 跟踪 Leader 的 `lastHeartbeatTime`，超时认为 Leader 失联

## 5. 日志复制

```
客户端写命令
       │
       ▼
 MeshWriteGate（handler 级门面）
       │
       ▼ (仅 Leader)
 LogEntry 构造（term / index / payload / clientRequestId）
       │
       ▼
 AppendEntries → Follower A / Follower B（并行）
       │
       ▼
 多数派 ACK + 本地落盘 → commit
       │
       ▼
 LogApplier 顺序应用到状态机 → 返回客户端 OK
```

**未 commit 的写入在 Leader 切换时被丢弃**——这是"已确认写入永不丢失"的保证（commit 前不返回 OK）。

## 6. 读写路径

### 6.1 写（Leader 视角）

1. 客户端连接 Leader 发 `SET foo bar`
2. MeshWriteGate 校验当前角色（必须是 Leader）
3. 构造 `LogEntry` 追加到本地 Raft log
4. `LogReplicator` 并行发 `AppendEntries` 到两个 Follower
5. 等待多数派 ACK（Follower A 返回 OK 即满足 2/3）
6. 本地落盘（持久化 Raft log）
7. `commit` index 推进 → `LogApplier` apply 到状态机
8. 返回客户端 `+OK`

### 6.2 写打到 Follower 的路径

1. Follower 收到写命令 → MeshWriteGate 抛 `MovedToLeaderException`
2. `MeshClientRedirector` 构造 `-MOVED <slot> <leaderAddr>` 返回客户端
3. 集群感知客户端自动跟随（新连接 Leader）

### 6.3 读（Leader Lease + read-index 退化）

- **默认（LEASE）**：Leader Lease 有效期内本地读（O(1)）；超时退化 read-index
- **READ_INDEX**：发送心跳确认仍是当前多数派的最新 Leader 后再读（牺牲延迟换取强一致）
- 配置项：`mesh-read-consistency LEASE | READ_INDEX`

## 7. MOVED / MESHDOWN 语义

| 场景 | 响应 |
|------|------|
| 写打到 Follower（已知 Leader） | `-MOVED <slot> <leaderAddr>`（slot 为 key 真实 CRC16） |
| 选举中（无 Leader） | `-MESHDOWN The mesh cluster has no leader` |
| MOVED 自重定向检测（leaderAddr == self） | `-MESHDOWN ...`（防 MOVED 死循环，v1.0.15 `286abf8`） |

## 8. 持久化（chunked snapshot + dump.rdb）

- **Raft log 即 WAL**：所有写入首先入日志，fsync 后才返回 OK
- **dump.rdb 即快照**：每 `mesh-snapshot-log-threshold`（默认 100000）条日志由 `SnapshotManager` 触发
- **chunked 拆块传输**：`InstallSnapshotMessage` 携带 chunk id + 偏移，Follower 边收边写，避免单帧超大
- **AOF 退役**：mesh 模式**不写 AOF**（避免与 Raft log 双写）；RDB 文件唯一写者 = `SnapshotManager`
- **启动加载**：`MeshStartupLoader` 先加载最新 dump.rdb，再 replay Raft log 中 snapshot index 之后的条目

## 9. 关键决策一览（DESIGN §11 摘要）

> 完整 17 条见 [DESIGN.md §十一](../../luban-rds-mesh/docs/DESIGN.md)；本节列出对外可见性最高的 8 条：

| 决策 | 理由 |
|------|------|
| **D1 3 节点固定** | 多数派 = 2/3，工程最简；5/7 节点扩容不在 v1 范围 |
| **D2 强一致优先** | 已确认写入不丢；金融 / 订单等场景刚需 |
| **D3 BLOCK 命令 v1 禁用** | Raft 化阻塞唤醒复杂，留 v2 解决（避免无限期推迟） |
| **D4 Lua 当写** | 脚本内 read-after-write 一致性无法静态保证，统一按写处理 |
| **D5 AOF 退役** | Raft log 已是 WAL，重复 AOF 写入浪费且引入双写竞态 |
| **D6 chunked snapshot** | 防日志无界增长；大快照不会阻塞单帧传输 |
| **D7 PreVote 防 term 膨胀** | 网络分区恢复后不会出现 term 飙升至 20+ 的选举风暴 |
| **D8 cluster / mesh 互斥** | 同一进程只能启用其一（两套拓扑并存语义不清） |

## 10. 模块文件树

```
luban-rds-mesh/
├── README.md
├── docs/
│   ├── DESIGN.md                # 完整协议设计 v1.2
│   └── IMPLEMENTATION_PLAN.md   # 13 阶段实施计划 v1.2
└── src/
    ├── main/java/com/janeluo/luban/rds/mesh/
    │   ├── MeshNode.java        # 节点入口
    │   ├── MeshConfig.java      # 配置聚合
    │   ├── bus/                 # MeshBusCodec / Client / Server / Frame / MessageType
    │   ├── core/                # LogEntry / RaftStateMachine / MeshRole / MeshState
    │   ├── election/            # ElectionTimer / LeaseManager / VoteCollector
    │   ├── gateway/             # MeshWriteGate
    │   ├── lifecycle/           # MeshBootstrap / MeshStartupLoader / MeshConfigPersister
    │   ├── replication/         # LogApplier / LogReplicator / SnapshotManager
    │   ├── rpc/                 # 5 类 RPC 消息
    │   └── client/              # MeshClientRedirector / MeshClusterCommands / 异常
    └── test/java/com/janeluo/luban/rds/mesh/
        ├── MeshNodeTest
        ├── bus/MeshBusCodecTest
        ├── client/MeshClientRedirectorTest, MeshClusterCommandsTest
        ├── core/LogEntryTest, RaftStateMachineTest
        ├── election/ElectionTimerTest, LeaseManagerTest, VoteCollectorTest
        ├── gateway/BlockCommandTest, MeshReadPathTest, MeshWriteGateTest
        ├── integration/ThreeNodeIntegrationTest      # 阶段 13：3 节点真实选举
        ├── lifecycle/MeshBootstrapTest, MeshConfigPersisterTest, MeshStartupLoaderTest
        ├── perf/MeshPerformanceSuite                  # 性能套件
        ├── replication/LogApplierTest, LogApplierTransactionTest, LogReplicatorTest, MeshNodeProposeTest, SnapshotManagerTest
        └── rpc/RpcMessageTest
```

## 11. 下一步

- [luban-rds-mesh/docs/DESIGN.md](../../luban-rds-mesh/docs/DESIGN.md) v1.2：完整协议设计（含全部 17 条关键决策 + 时序图）
- [luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md](../../luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md) v1.2：13 阶段实施计划
- [Mesh 快速上手](./setup.md)：配置 / 启动 / 客户端 / 运维命令