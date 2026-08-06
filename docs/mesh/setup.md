---
title: Mesh 快速上手
last_updated: 2026-08-06
version: 1.0.17
---

# Mesh 集群快速上手

> 本节配套 [luban-rds-mesh/README.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/README.md) 一起阅读——本节强调**站点侧配置 + 启动 + 客户端 + 运维**全流程，`luban-rds-mesh/README.md` 更深入讲解模块内部状态。

## 1. 前置要求

### 1.1 节点数量

- **固定 3 节点**——mesh 设计即 3 节点 Raft 强一致（多数派 = 2/3），扩容到 5/7 节点不在 v1 范围
- **互为副本**：3 节点各持全量数据，无主从之分；任一节点都是其他两节点的副本

### 1.2 端口规划

每个 mesh 节点需要 **2 个端口**：

| 端口 | 用途 | 默认值 |
|------|------|--------|
| service 端口 | 客户端 RESP 通信 | 9736（由全局 `port` 配置或 `mesh-service-port` 覆盖） |
| bus 端口 | 节点间 Raft RPC | `servicePort + 11000` 或显式 `mesh-bus-port` |

确保防火墙同时开放两个端口。

### 1.3 节点 ID

- nodeId 为 40 字符十六进制（SHA-1 风格）；生产建议手工指定并保持稳定
- 配 `mesh-self-node-id <id>`；未配则取 `mesh-peers` 列表第一个条目
- 集群发现：`parsePeers` 启动期会校验所有 peers 节点 ID 不塌缩到同一地址；v1.0.15 `286abf8` + `170d35d` 修复了单机多实例 + `MOVED` 自重定向死循环

### 1.4 时间同步

- Leader Lease 读依赖时钟（租约时长内本地读）
- 节点间时钟漂移过大需切 `mesh-read-consistency READ_INDEX`（默认 `LEASE`）

## 2. 配置文件（luban-rds.conf）

mesh 配置走 `luban-rds.conf`，字段为 `mesh-*` 中划线风格（与 `cluster-*` 风格一致）：

```ini
# ===== mesh 集群配置（与 cluster-enabled 互斥） =====
mesh-enabled yes

# peers 列表：nodeId@host:busPort 逗号分隔（含自身）
mesh-peers a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d@10.0.0.1:11000,\
c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6@10.0.0.2:11000,\
e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718@10.0.0.3:11000

# 本节点 nodeId（未配置时取 peers 列表第一个条目；生产建议显式指定）
mesh-self-node-id a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d

# 以下参数均有默认值，可不配：
# mesh-election-timeout-min-ms 150      # 选举超时下限（随机化 150-300ms）
# mesh-election-timeout-max-ms 300
# mesh-heartbeat-interval-ms 100        # Leader 心跳周期
# mesh-lease-duration-ms 600            # 读租约时长（= 2 × electionTimeout）
# mesh-read-consistency LEASE           # 读模式：LEASE（默认）/ READ_INDEX
# mesh-read-lease-wait-ms 1000          # 租约失效时等待续租的上限
# mesh-snapshot-log-threshold 100000    # 每 N 条日志触发周期快照
# mesh-bus-port 0                       # 0 = 按 peers 条目取
# mesh-service-port 0                   # 0 = 用全局 port（单机多实例必配为不同值）
```

> **互斥约束**：`mesh-enabled yes` 与 `cluster-enabled yes` 不能同时启用，启动时校验中止。

## 3. 启动 3 节点

`luban-rds-bin` 支持 mesh CLI 参数（阶段 12 已实现）：

```bash
# 节点 A（10.0.0.1）
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers "a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d@10.0.0.1:11000,c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6@10.0.0.2:11000,e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718@10.0.0.3:11000" \
  --mesh-self-node-id a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d \
  --mesh-bus-port 11000

# 节点 B（10.0.0.2）—— 仅 --mesh-self-node-id 不同
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers "a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d@10.0.0.1:11000,c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6@10.0.0.2:11000,e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718@10.0.0.3:11000" \
  --mesh-self-node-id c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6 \
  --mesh-bus-port 11000

# 节点 C（10.0.0.3）—— 仅 --mesh-self-node-id 不同
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers "a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d@10.0.0.1:11000,c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6@10.0.0.2:11000,e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718@10.0.0.3:11000" \
  --mesh-self-node-id e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718 \
  --mesh-bus-port 11000
```

也可用 `--config /path/to/luban-rds.conf` 指向写好 `mesh-*` 配置的文件，免去命令行参数。

### 3.1 CLI 参数全集

| 参数 | 说明 |
|------|------|
| `--mesh-enabled` | 启用 mesh 模式（无需参数值，存在即 yes；与 `--cluster-enabled` 互斥） |
| `--mesh-peers <peers>` | peers 列表（`nodeId@host:busPort` 逗号分隔） |
| `--mesh-self-node-id <id>` | 本节点 nodeId（未指定取 peers 首个） |
| `--mesh-bus-port <port>` | mesh 总线端口 |

## 4. 客户端连接

### 4.1 集群感知客户端（JedisCluster / lettuce cluster）— 推荐

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

### 4.2 普通客户端（new Jedis / redis-cli）— 需连 Leader 或自行处理 MOVED

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

## 5. 运维命令

mesh 复用 `CLUSTER` 命令族（`CLUSTER SLOTS / NODES / INFO`），使集群感知客户端零侵入：

```bash
# 查看槽位映射（16384 全 slot → Leader，单主视图）
redis-cli -p 6379 CLUSTER SLOTS
# → [[0, 16383, ["10.0.0.1", "6379", "a1b2..."]], [], []]
# ↑ 含空 replicas 数组（v1.0.15 `0dc88ef` 修复严格解析器挂起）

# 查看节点列表（3 节点，复用 Redis master/follower 语义）
redis-cli -p 6379 CLUSTER NODES
# a1b2... 10.0.0.1:6379@11000 myself,master ... connected     # 死节点显示 disconnected
# c3d4... 10.0.0.2:6379@11000 master ... connected
# e5f6... 10.0.0.3:6379@11000 master ... connected

# 集群状态
redis-cli -p 6379 CLUSTER INFO
# cluster_enabled:1
# cluster_state:ok            # 无 Leader 时为 fail
# cluster_known_nodes:3
# ...
```

### 5.1 MOVED / MESHDOWN 说明

客户端写请求打到 Follower 或无 Leader 期间，返回 RESP 错误引导客户端：

| 场景 | 响应 | 含义 |
|------|------|------|
| **已知 Leader**（写打到 Follower） | `-MOVED <slot> <leaderServiceAddr>\r\n` | `slot` 为 key 的真实 CRC16（v1.0.15 `84eb0aa` 修复 `slot` 恒为 0）；`leaderServiceAddr` = `host:port`。集群感知客户端自动跟随 |
| **未知 / 无 Leader**（选举中） | `-MESHDOWN The mesh cluster has no leader\r\n` | 客户端应退避重试 |

> `MOVED` 中的 slot 用 key 的真实 CRC16（非占位值），部分客户端依赖它更新本地路由缓存。

## 6. 关键约束与运维建议

### 6.1 关键约束

| 约束 | 说明 |
|------|------|
| **NTP 时钟对齐** | Leader Lease 读依赖时钟；漂移过大需切 `mesh-read-consistency READ_INDEX` |
| **BLOCK 命令禁用** | `BLPOP / BRPOP / BLMOVE / WAIT` 等 v1 返回错误 |
| **Lua 脚本当写** | `EVAL / EVALSHA` 统一按写处理（走 Raft 复制） |
| **cluster / mesh 互斥** | 同一进程只能启用其一（启动时校验） |
| **AOF 退役** | mesh 模式不写 AOF——Raft log 即 WAL、dump.rdb 即快照 |
| **dump.rdb 唯一写者** | mesh 模式禁用 server 原 RDB save（BGSAVE），唯一写者 = SnapshotManager |

### 6.2 运维建议

- **单机多实例测试**：必须显式配 `mesh-service-port`（避免多实例地址塌缩导致 MOVED 死循环）
- **节点 ID 稳定**：生产建议固定 `mesh-self-node-id`，避免重启后 ID 漂移
- **时钟同步**：NTP 漂移 < 100ms 为佳；漂移 > 500ms 应主动切 READ_INDEX
- **failover 演练**：v1.0.15 已修复选举风暴 term 飙升问题（`d4dc1ad`），但仍建议在测试环境演练
- **持久化**：mesh 模式重启加载顺序为 `dump.rdb` → `Raft log replay`；AOF 不会被加载

## 7. 常见问题

### 7.1 客户端连不上 mesh 集群

- 检查 `mesh-enabled yes` 是否生效
- 检查 `mesh-service-port` 与客户端连接的端口是否一致（单机多实例必配）
- 检查防火墙是否同时开放 service 与 bus 端口

### 7.2 收到 `-MESHDOWN`

选举中无 Leader（短暂状态），客户端应退避重试；若持续存在：

- 检查 bus 端口连通性（节点间 Raft RPC 走 bus）
- 检查 `parsePeers` 配置：所有 peers 节点地址不能塌缩到同一端口（`parsePeers` 启动期会校验）

### 7.3 集群写入性能不达预期

- 强一致写需多数派 ACK + 落盘，单次写入至少 RTT × 2；如对延迟极敏感可考虑 Redis Cluster（异步复制最终一致）
- `raftExecutor` 单线程为写吞吐上限；如确需更高吞吐，建议升级到 Redis Cluster 分片

## 8. 下一步

- [协议设计要点](./design.md)：状态机、RPC、Lease、read-index、chunked snapshot
- [luban-rds-mesh/README.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/README.md)：模块完整文档
- [luban-rds-mesh/docs/DESIGN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/DESIGN.md) v1.2：完整协议设计