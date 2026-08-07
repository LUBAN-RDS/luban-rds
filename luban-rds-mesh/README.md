# luban-rds-mesh

> 3 节点 Raft 强一致集群 — 用 3 台机器替代 Redis Cluster 的 6 节点（3 主 3 从），强一致、不丢已确认写入

[![Status](https://img.shields.io/badge/status-implemented-green.svg)]()
[![Nodes](https://img.shields.io/badge/nodes-3-blue.svg)]()
[![Consistency](https://img.shields.io/badge/consistency-strong-green.svg)]()
[![Tests](https://img.shields.io/badge/tests-291-brightgreen.svg)]()
[![Compatible](https://img.shields.io/badge/client-Redis%20Compatible-red.svg)]()

---

## 模块定位

`luban-rds-mesh` 是 igbp-luban-rds 的 **Raft 强一致集群模块**：3 节点互为副本，用 Raft 协议实现强一致高可用，替代 Redis Cluster 的 6 节点（3 主 3 从）部署。任一时刻只有 1 个 Leader 处理写入，写入必须经多数派（2/3）确认并落盘后才返回 OK，**已确认的写入永不丢失**。

### 核心卖点（vs Redis Cluster）

| 维度 | Redis Cluster（现有） | **Mesh（本模块）** |
|------|----------------------|----------------|
| 机器数 | 6+（3 主 3 从） | **3**（互为副本，成本减半） |
| 数据分片 | 16384 Slot 分片 | 全量数据，无分片 |
| 一致性 | 最终一致（异步复制） | **强一致**（多数派 ACK + 落盘） |
| Leader 切换丢数据 | 可能丢未复制的写入 | **不会**（未 commit 的写入不返回 OK） |
| 客户端兼容 | Cluster aware 客户端 | **集群感知客户端零侵入**（JedisCluster / lettuce 经 `CLUSTER SLOTS` 引导 + `MOVED` 自动跟随）；普通客户端（Jedis 单机 / redis-cli）需连 Leader 或自行处理 `-MOVED` |

---

## 架构

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

- 3 节点互连（mesh 拓扑），每节点到其他两节点有独立 Netty 长连接（raft-bus，端口 = servicePort + 11000 或显式配置）
- 任一时刻只有 1 个 Leader，通过 Raft 选举保证（多数派投票 + PreVote 防 term 膨胀）
- **强一致写**：写入必须多数派（2/3）确认**并落盘**才能 apply 并返回客户端 OK
- **线性一致读**：Leader Lease 心跳租约（NTP 前提下租约内本地读；时钟不可靠时退化 read-index）

完整拓扑/状态机/RPC/时序见 [docs/DESIGN.md](docs/DESIGN.md) §2。

---

## 快速上手

### 1. 配置（luban-rds.conf）

mesh 配置走 `luban-rds.conf`，字段为 `mesh-*` 中划线风格（与 `cluster-*` 风格一致）：

```ini
# ===== mesh 集群配置（与 cluster-enabled 互斥） =====
mesh-enabled yes

# peers 列表：nodeId@host:busPort 逗号分隔（含自身）。
# nodeId 为 40 字符十六进制（SHA-1）；生产建议手工指定，测试可用任意唯一串。
# busPort = 节点间 Raft RPC 端口；service 端口用全局 port。
mesh-peers a1b2...@10.0.0.1:11000,c3d4...@10.0.0.2:11000,e5f6...@10.0.0.3:11000

# 本节点 nodeId（未配置时取 peers 列表第一个条目；生产建议显式指定）
mesh-self-node-id a1b2...

# 以下参数均有默认值，可不配：
# mesh-election-timeout-min-ms 300      # 选举超时下限（随机化 300-600ms）
# mesh-election-timeout-max-ms 600
# mesh-heartbeat-interval-ms 100        # Leader 心跳周期
# mesh-lease-duration-ms 1200           # 读租约时长（= 2 × electionTimeout）
# mesh-read-consistency LEASE           # 读模式：LEASE（默认）/ READ_INDEX
# mesh-read-lease-wait-ms 1000          # 租约失效时等待续租的上限
# mesh-snapshot-log-threshold 100000    # 每 N 条日志触发周期快照
# mesh-bus-port 0                       # 0 = 按 peers 条目取
# mesh-service-port 0                   # 0 = 用全局 port
```

> **互斥约束**：`mesh-enabled yes` 与 `cluster-enabled yes` 不能同时启用，启动时校验中止。

### 2. 启动 3 节点

`luban-rds-bin` 支持 mesh CLI 参数（阶段 12 已实现）：

```bash
# 节点 A（10.0.0.1）
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers a1b2...@10.0.0.1:11000,c3d4...@10.0.0.2:11000,e5f6...@10.0.0.3:11000 \
  --mesh-self-node-id a1b2... \
  --mesh-bus-port 11000

# 节点 B（10.0.0.2）
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers a1b2...@10.0.0.1:11000,c3d4...@10.0.0.2:11000,e5f6...@10.0.0.3:11000 \
  --mesh-self-node-id c3d4... \
  --mesh-bus-port 11000

# 节点 C（10.0.0.3）
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers a1b2...@10.0.0.1:11000,c3d4...@10.0.0.2:11000,e5f6...@10.0.0.3:11000 \
  --mesh-self-node-id e5f6... \
  --mesh-bus-port 11000
```

也可用 `--config /path/to/luban-rds.conf` 指向写好 `mesh-*` 配置的文件，免去命令行参数。

**CLI 参数全集**（`java -jar luban-rds-bin.jar --help` 查看）：

| 参数 | 说明 |
|------|------|
| `--mesh-enabled` | 启用 mesh 模式（无需参数值，存在即 yes；与 `--cluster-enabled` 互斥） |
| `--mesh-peers <peers>` | peers 列表（`nodeId@host:busPort` 逗号分隔） |
| `--mesh-self-node-id <id>` | 本节点 nodeId（未指定取 peers 首个） |
| `--mesh-bus-port <port>` | mesh 总线端口 |

### 3. 客户端连接

#### 集群感知客户端（JedisCluster / lettuce cluster）— 推荐

经 `CLUSTER SLOTS` 引导 + `MOVED` 自动重定向，**对客户端零侵入**：

```java
// JedisCluster：自动刷新拓扑、跟随 MOVED 重连新 Leader
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("10.0.0.1", 6379));
nodes.add(new HostAndPort("10.0.0.2", 6379));
nodes.add(new HostAndPort("10.0.0.3", 6379));
try (JedisCluster jedis = new JedisCluster(nodes)) {
    jedis.set("foo", "bar");      // 自动路由到 Leader
    System.out.println(jedis.get("foo"));
}
```

```java
// lettuce cluster
RedisClusterClient client = RedisClusterClient.create(
    RedisURI.create("redis://10.0.0.1:6379"));
try (StatefulRedisClusterConnection<String, String> conn = client.connect()) {
    conn.sync().set("foo", "bar");
    System.out.println(conn.sync().get("foo"));
}
```

#### 普通客户端（new Jedis / redis-cli）— 需连 Leader 或自行处理 MOVED

普通客户端不跟随 `-MOVED`，连到 Follower 时会收到 `MOVED` 错误，需手动重连 Leader：

```java
try (Jedis jedis = new Jedis("10.0.0.1", 6379)) {
    // 若 10.0.0.1 是 Leader，直接成功；若是 Follower，抛 MOVED 异常
    jedis.set("foo", "bar");
}
```

```bash
# redis-cli 直连 Leader 测试（不跟随 MOVED）
redis-cli -h 10.0.0.1 -p 6379 SET foo bar
# 若连到 Follower，收到：-MOVED <slot> <leader-ip>:<leader-port>，需手动重连 Leader
# 可先用 CLUSTER NODES 查询谁是 Leader
```

---

## 运维命令

mesh 复用 `CLUSTER` 命令族（`CLUSTER SLOTS / NODES / INFO`），使集群感知客户端零侵入：

```bash
# 查看槽位映射（16384 全 slot → Leader，单主视图）
redis-cli -p 6379 CLUSTER SLOTS
# → [[0, 16383, ["10.0.0.1", 6379, "a1b2..."]]]

# 查看节点列表（3 节点，复用 Redis master/follower 语义）
redis-cli -p 6379 CLUSTER NODES
# a1b2... 10.0.0.1:6379@11000 myself,master ...
# c3d4... 10.0.0.2:6379@11000 slave ...
# e5f6... 10.0.0.3:6379@11000 slave ...

# 集群状态
redis-cli -p 6379 CLUSTER INFO
# cluster_enabled:1
# cluster_state:ok            # 无 Leader 时为 fail
# cluster_known_nodes:3
# ...
```

### MOVED / MESHDOWN 说明

客户端写请求打到 Follower 或无 Leader 期间，返回 RESP 错误引导客户端：

| 场景 | 响应 | 含义 |
|------|------|------|
| **已知 Leader**（写打到 Follower） | `-MOVED <slot> <leaderServiceAddr>\r\n` | `slot` 为 key 的真实 CRC16；`leaderServiceAddr` = `host:port`。集群感知客户端自动跟随 |
| **未知 / 无 Leader**（选举中） | `-MESHDOWN The mesh cluster has no leader\r\n` | 客户端应退避重试 |

> `MOVED` 中的 slot 用 key 的真实 CRC16（非占位值），部分客户端依赖它更新本地路由缓存。

---

## 关键约束

| 约束 | 说明 |
|------|------|
| **NTP 时钟对齐** | Leader Lease 读依赖时钟（租约时长内本地读）；节点间时钟漂移过大需切 `mesh-read-consistency READ_INDEX` |
| **BLOCK 命令禁用** | `BLPOP / BRPOP / BLMOVE / WAIT` 等 v1 返回错误（Raft 化阻塞唤醒留待 v2）。见 DESIGN §11 决策 17 |
| **Lua 脚本当写** | `EVAL / EVALSHA` 统一按写处理（走 Raft 复制），即使脚本内只有读命令 |
| **cluster / mesh 互斥** | 同一进程只能启用其一（`mesh-enabled` 与 `cluster-enabled` 启动时校验） |
| **AOF 退役** | mesh 模式不写 AOF——Raft log 即 WAL、dump.rdb 即快照（见 DESIGN §5.1） |
| **dump.rdb 唯一写者** | mesh 模式禁用 server 原 RDB save（BGSAVE），dump.rdb 唯一写者 = SnapshotManager（见 DESIGN §5.4） |

---

## 角色

| 角色 | 职责 |
|------|------|
| **FOLLOWER** | 默认状态；被动接收 AppendEntries；选举超时后转为 CANDIDATE |
| **CANDIDATE** | 选举中；先 PreVote 探测（不自增 term），多数派预投后正式选举；获得多数票转 LEADER |
| **LEADER** | 处理所有客户端写入；向 Followers 复制日志；维持心跳与读租约 |

---

## 测试

模块当前 **291 个测试全过**（`mvn -pl luban-rds-mesh test`），覆盖：

| 阶段 | 测试内容 | 测试数（累计） |
|------|----------|--------|
| 1 | MeshBusCodec 编解码 | 10 |
| 2 | LogEntry / 5 种 RPC 序列化 | 34 |
| 3 | 选举 / 租约 / PreVote | 106 |
| 4 | LogApplier / LogReplicator | 149 |
| 5 | MeshWriteGate（读写分流 / MOVED） | 168 |
| 6 | MeshClientRedirector（MOVED/MESHDOWN） | 183 |
| 7 | 读路径（Leader Lease / read-index） | 195 |
| 8 | CLUSTER SLOTS/NODES/INFO | 221 |
| 9 | MULTI/EXEC 事务 / BLOCK 禁用 | 243 |
| 10 | chunked snapshot | 252 |
| 11 | 持久化 / 启动加载 | 278 |
| 12 | 装配（MeshBootstrap） | 286 |
| **13** | **3 节点集成测试（真实选举 + 多数派写 + 一致性）** | **291** |

阶段 13 的 [ThreeNodeIntegrationTest](src/test/java/com/janeluo/luban/rds/mesh/integration/ThreeNodeIntegrationTest.java) 用内存路由总线连接 3 个真实 `MeshNode`，验证：选举出唯一 Leader → Leader 写 SET 经多数派确认 → 3 节点最终一致。

> **3 进程集成测试 / 故障注入**（kill leader、网络分区、时钟偏移）需真实多进程环境，留作手动验证（见 DESIGN §十「测试策略」）。单元 + 内存集成测试已覆盖协议正确性主线。

---

## 适用场景

| 场景 | 推荐度 |
|------|--------|
| 中小规模生产部署（数据 < 100GB） | 强烈推荐 |
| 金融 / 订单等强一致需求 | 强烈推荐 |
| 跨机房容灾（3 机房各 1 节点） | 推荐 |
| 超大规模数据（> 500GB） | 一般（建议 Redis Cluster 分片） |
| 频繁动态扩缩容 | 一般（固定 3 节点静态 meet；建议 Redis Cluster） |

---

## 文档索引

| 文档 | 内容 |
|------|------|
| [docs/DESIGN.md](docs/DESIGN.md) | 完整协议设计（状态机、RPC、时序、关键决策）v1.2 |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | 13 阶段实施计划 v1.2 |

---

## 当前状态

**已实现**（v1，13 阶段全部完成）：

- 协议设计（DESIGN v1.2，经两轮评审定案）
- 全部 13 阶段实现：项目骨架 → 编解码 → 选举/租约/PreVote → 日志复制 → 读写门面 → 客户端重定向 → 读路径 → CLUSTER 命令 → 事务/BLOCK → snapshot → 持久化/启动加载 → 装配 → 测试补全
- 291 测试全过（含 3 节点集成测试）

实施进度详见 [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)。

---

## 许可证

Apache License 2.0
