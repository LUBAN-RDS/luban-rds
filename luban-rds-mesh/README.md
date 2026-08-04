# luban-rds-mesh

> 3 节点 Raft 强一致集群 — 管理面自研协议 + 数据面 RESP 兼容

[![Status](https://img.shields.io/badge/status-design--phase-yellow.svg)]()
[![Nodes](https://img.shields.io/badge/nodes-3-blue.svg)]()
[![Consistency](https://img.shields.io/badge/consistency-strong-green.svg)]()
[![Compatible](https://img.shields.io/badge/client-Redis%20Compatible-red.svg)]()

---

## 📖 模块简介

`luban-rds-mesh` 是 igbp-luban-rds 的**高可用集群模块**，用 Raft 协议实现 3 节点强一致集群。

### 核心卖点

| 维度 | 描述 |
|------|------|
| 🖥️ **3 台机器** | 互为副本，部署成本减半 |
| 🔒 **强一致** | 写入必须多数派（2/3）确认才返回 |
| 🔄 **零切换丢数据** | 未 commit 写入被覆盖，不会丢失 |
| 🌐 **集群感知客户端零侵入** | JedisCluster / lettuce cluster 经 CLUSTER SLOTS + MOVED 自动跟随；普通客户端（Jedis 单机 / redis-cli）需连 Leader 或自行处理 MOVED |
| 🧩 **独立模块** | 不依赖 cluster 模块，可独立启用 |

### 与 Redis Cluster 模式对比

| 维度 | Redis Cluster（现有） | Mesh（本模块） |
|------|----------------------|----------------|
| 机器数 | 6+（3 主 3 从） | **3**（互为副本） |
| 数据分片 | 16384 Slot 分片 | 全量数据，无分片 |
| 一致性 | 最终一致（异步复制） | **强一致**（多数派 ACK） |
| 切换时丢数据 | 可能 | **不会** |
| 客户端兼容 | Cluster aware 客户端 | **集群感知客户端零侵入**（JedisCluster/lettuce）；普通客户端需连 Leader |

---

## 🏗️ 架构

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

3 节点互连，每节点既是 Leader 候选，也是其他节点的 Follower。任一时刻只有 1 个 Leader，通过 Raft 选举保证（多数派投票）。

---

## ⚙️ 配置

```yaml
# mesh 节点配置（每个节点一份，仅 nodeId 不同）
# 字段名与 DESIGN §6 一致：中划线风格（mesh-enabled / mesh-peers …）
mesh-enabled: true
mesh-node-id: "auto-generate-40hex-on-first-boot"   # 40 字符 hex（SHA-1），或手工指定
mesh-peers: "10.0.0.1:11000,10.0.0.2:11000,10.0.0.3:11000"   # 含自身，MeshBusClient 自动过滤
mesh-election-timeout-ms: 300    # 150-300ms 随机
mesh-heartbeat-interval-ms: 100  # Leader 心跳周期
mesh-lease-duration-ms: 600      # 租约时长（= 2 × electionTimeout）
mesh-log-persist-path: "./raft-nodes.conf"
mesh-bus-port: 0                 # 0 = service port + 11000
mesh-snapshot-threshold: 100000  # 每 N 条日志触发周期快照
```

> **CLI 现状**：`luban-rds-bin` 当前命令行解析仅支持 `--config/--port/--help`。下文启动示例中的 `--mesh-*` 参数需在阶段 12 扩展 CLI 后才生效；现阶段请通过 `--config` 指向的 `luban-rds.conf` 写入上述 `mesh-*` 配置项。

---

## 🚀 启动方式

### 1. 3 节点本地测试

```bash
# 节点 A
java -jar luban-rds-bin.jar \
  --port 6379 \
  --mesh.enabled=true \
  --mesh.peers=127.0.0.1:11000,127.0.0.1:11001,127.0.0.1:11002 \
  --mesh.busPort=11000

# 节点 B
java -jar luban-rds-bin.jar \
  --port 6380 \
  --mesh.enabled=true \
  --mesh.peers=127.0.0.1:11000,127.0.0.1:11001,127.0.0.1:11002 \
  --mesh.busPort=11001

# 节点 C
java -jar luban-rds-bin.jar \
  --port 6381 \
  --mesh.enabled=true \
  --mesh.peers=127.0.0.1:11000,127.0.0.1:11001,127.0.0.1:11002 \
  --mesh.busPort=11002
```

### 2. Docker Compose（生产）

```yaml
version: '3.8'
services:
  mesh-a:
    image: luban-rds:latest
    ports:
      - "6379:6379"
      - "11000:11000"
    environment:
      - MESH_ENABLED=true
      - MESH_PEERS=mesh-a:11000,mesh-b:11000,mesh-c:11000

  mesh-b:
    image: luban-rds:latest
    ports:
      - "6380:6379"
      - "11001:11000"
    environment:
      - MESH_ENABLED=true
      - MESH_PEERS=mesh-a:11000,mesh-b:11000,mesh-c:11000

  mesh-c:
    image: luban-rds:latest
    ports:
      - "6381:6379"
      - "11002:11000"
    environment:
      - MESH_ENABLED=true
      - MESH_PEERS=mesh-a:11000,mesh-b:11000,mesh-c:11000
```

### 3. 客户端连接

```java
// 集群感知客户端（JedisCluster / lettuce cluster）：自动经 CLUSTER SLOTS 引导 + MOVED 重定向
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("127.0.0.1", 6379));
try (JedisCluster jedis = new JedisCluster(nodes)) {
    jedis.set("foo", "bar");
    System.out.println(jedis.get("foo"));
}

// 普通客户端（new Jedis / redis-cli）：不跟随 MOVED，需直连 Leader，或自行处理 -MOVED 重定向
try (Jedis jedis = new Jedis("127.0.0.1", 6379)) {   // 6379 若是 Leader 则直接成功
    jedis.set("foo", "bar");
}
```

```bash
# redis-cli 直连 Leader 测试（不跟随 MOVED）
redis-cli -h 127.0.0.1 -p 6379 SET foo bar
# 若连到 Follower，会收到 -MOVED <slot> <leader-ip>:<leader-port>，需手动重连 Leader
```

---

## 📊 状态字段

| 角色 | 职责 |
|------|------|
| **FOLLOWER** | 默认状态；被动接收 AppendEntries；选举超时后转为 CANDIDATE |
| **CANDIDATE** | 选举中；发起 RequestVote；获得多数票转 LEADER |
| **LEADER** | 处理所有客户端写入；向 Followers 复制日志；维持心跳 |

---

## 🔧 运维命令

```bash
# 查看集群状态
redis-cli -p 6379 MESH INFO          # 类似 CLUSTER INFO
redis-cli -p 6379 MESH NODES         # 类似 CLUSTER NODES

# 查看当前 Leader
redis-cli -p 6379 MESH LEADER

# 触发手动 failover（待实现）
redis-cli -p 6379 MESH FAILOVER
```

---

## 📚 文档

| 文档 | 内容 |
|------|------|
| [docs/DESIGN.md](docs/DESIGN.md) | 完整协议设计文档（状态机、RPC、时序） |
| [docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) | 11 阶段实施计划 |

---

## 🎯 适用场景

| 场景 | 推荐度 |
|------|--------|
| 中小规模生产部署（数据 < 100GB） | ⭐⭐⭐⭐⭐ |
| 金融/订单等强一致需求 | ⭐⭐⭐⭐⭐ |
| 跨机房容灾（3 机房各 1 节点） | ⭐⭐⭐⭐ |
| 超大规模数据（> 500GB） | ⭐⭐（建议 Redis Cluster 分片） |
| 频繁动态扩缩容 | ⭐⭐（建议 Redis Cluster） |

---

## 🚧 当前状态

**设计阶段**（v1.2）：

- ✅ 协议设计完成（DESIGN v1.2，经两轮评审定案：handler gate / AOF 退役 / Leader Lease / CLUSTER SLOTS / chunked snapshot / dump.rdb 写者归属）
- ✅ 模块文件树规划
- ✅ 13 阶段实施计划（PLAN v1.2，与 DESIGN 完全对齐）
- ⏳ 阶段 1：项目骨架（待开始）

实施进度见 [IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md)。

---

## 📄 许可证

Apache License 2.0