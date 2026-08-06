---
title: 集群部署
last_updated: 2026-08-06
version: 1.0.17
---

# 集群部署

本指南介绍如何部署、初始化、扩缩容和运维 Luban-RDS 集群模式（Redis Cluster 协议）。协议层组件、槽位算法与重定向语义参见 [功能架构 - Redis Cluster 集群](../architecture/features.md#17-redis-cluster-集群)；配置项参考参见 [配置指南 - 集群模式配置](./configuration.md#95-集群模式配置)。

> **v1.0.3 新增**：Luban-RDS 自带 `redis-cli --cluster create` 兼容 CLI，可一键完成集群搭建，跳过下文的 `MEET` + `ADDSLOTS` 手动编排（详见 [§2.5 一键搭建集群](#25-一键搭建集群-v103)）。
>
> **v1.0.15 新增**：除 Redis Cluster 外，Luban-RDS 还提供 **3 节点 Raft 强一致 mesh 集群**作为替代方案——3 台机器替代 6 节点，强一致保证已确认写入不丢。详见 [§10 Mesh 集群部署](#10-mesh-集群部署-v1015) 与 [docs/mesh/setup.md](../mesh/setup.md)。

## 1. 前置要求

### 1.1 节点数量

- **最小部署**：3 个主节点（保证 `cluster-require-full-coverage=yes` 时所有 16384 槽位可分配）
- **推荐部署**：3 主 + 3 从（共 6 节点），每个主节点配 1 从节点以实现故障自动转移
- **生产建议**：奇数主节点（3/5/7），主从比 1:1 或 1:2

### 1.2 端口规划

每个集群节点需要 **2 个端口**：

| 端口 | 用途 | 默认值 |
|------|------|--------|
| 服务端口 | 客户端 RESP 通信 | 9736（由 `port` 配置） |
| 总线端口 | 节点间 Gossip 通信 | 服务端口 + 10000（`BUS_PORT_OFFSET`） |

确保防火墙同时开放两个端口。NAT / Docker / Kubernetes 环境需通过 `cluster-announce-*` 显式映射（参见 [配置指南 9.5.2](./configuration.md#952-网络公告)）。

### 1.3 节点 ID

节点首次以集群模式启动时自动生成 40 位十六进制节点 ID（持久化到 `cluster-config-file`）。重启后保持不变。可通过 `CLUSTER MYID` 查看。

### 1.4 时间同步

所有节点的系统时间偏差应小于 1 秒（Gossip 心跳间隔的默认 1000ms 内）。生产环境推荐部署 NTP 服务。

## 2. 初始化流程

以最小 3 主节点集群（端口 9736/9737/9738）为例，下文示例使用 `192.168.8.161` 作为演示地址，请按实际部署替换。

### 2.1 启动各节点

每个节点使用独立的配置文件与数据目录，分别启动：

```bash
# node-1
java -jar luban-rds-jar-with-dependencies.jar --config /app/config/node-1.conf

# node-2
java -jar luban-rds-jar-with-dependencies.jar --config /app/config/node-2.conf

# node-3
java -jar luban-rds-jar-with-dependencies.jar --config /app/config/node-3.conf
```

此时三个节点相互独立，`CLUSTER INFO` 显示 `cluster_state:fail`、`cluster_known_nodes:1`、`cluster_slots_assigned:0`。

### 2.2 节点互连（CLUSTER MEET）

在任一节点上对其他两个节点执行 `CLUSTER MEET`：

```bash
redis-cli -h 192.168.8.161 -p 9736 CLUSTER MEET 192.168.8.161 9737
redis-cli -h 192.168.8.161 -p 9736 CLUSTER MEET 192.168.8.161 9738
```

也可在每个节点上分别 `MEET` 其余两个（实现全连接）。验证：

```bash
redis-cli -h 192.168.8.161 -p 9736 CLUSTER NODES
```

应看到 3 个节点，`connected` 标志均为 `connected`。

### 2.3 分配槽位

为三个主节点平均分配 16384 个槽位（5461 / 5461 / 5462）：

```bash
# 节点 1：0-5460
redis-cli -h 192.168.8.161 -p 9736 CLUSTER ADDSLOTS $(seq 0 5460)

# 节点 2：5461-10922
redis-cli -h 192.168.8.161 -p 9737 CLUSTER ADDSLOTS $(seq 5461 10922)

# 节点 3：10923-16383
redis-cli -h 192.168.8.161 -p 9738 CLUSTER ADDSLOTS $(seq 10923 16383)
```

### 2.4 验证集群健康

```bash
redis-cli -h 192.168.8.161 -p 9736 CLUSTER INFO
```

期望关键字段：

```
cluster_state:ok
cluster_slots_assigned:16384
cluster_known_nodes:3
cluster_size:3
```

### 2.5 一键搭建集群 (v1.0.3+)

Luban-RDS 自带 `RedisCliMain`，对齐 `redis-cli --cluster create` 子集，能一次性完成第 2.2–2.4 节的所有步骤，适合自动化部署。

**前置条件**：所有节点都已启动且启用了 `cluster-enabled yes`（`MEET` 与槽位分配会自动完成）。

#### 命令行使用

```bash
# 启动 6 个节点（端口 9736–9741），分别使用各自配置文件
# 节点启动脚本示例：java -jar luban-rds-jar-with-dependencies.jar --config /app/config/node-N.conf

# 创建 3 主 + 3 从集群
java -cp luban-rds-jar-with-dependencies.jar com.janeluo.luban.rds.client.cli.RedisCliMain \
     --cluster create \
     192.168.8.161:9736 192.168.8.161:9737 192.168.8.161:9738 \
     192.168.8.161:9739 192.168.8.161:9740 192.168.8.161:9741 \
     --cluster-replicas 1
```

CLI 会按顺序执行：

1. `CLUSTER MEET`：全连接所有节点
2. 主从划分：按 `--cluster-replicas` 交错分配 slave
3. 槽位分配：16384 槽均分给所有 master
4. `CLUSTER INFO` 校验：`cluster_state:ok` 且 `cluster_slots_assigned:16384`

**常用参数**：

| 参数 | 含义 | 默认值 |
|------|------|--------|
| `--cluster create` | 子命令（当前唯一支持的子命令） | 必填 |
| `--cluster-replicas N` | 每个主节点的从节点数量 | `0` |
| `-h` / `--help` | 打印使用说明 | - |

#### Java 代码嵌入调用

```java
import com.janeluo.luban.rds.client.cli.ClusterSetupCommand;
import com.janeluo.luban.rds.client.cli.NodeAddress;
import java.util.List;

List<NodeAddress> nodes = List.of(
    new NodeAddress("127.0.0.1", 9736),
    new NodeAddress("127.0.0.1", 9737),
    new NodeAddress("127.0.0.1", 9738),
    new NodeAddress("127.0.0.1", 9739),
    new NodeAddress("127.0.0.1", 9740),
    new NodeAddress("127.0.0.1", 9741)
);
// verbose = true（默认）打印每步进度；false 用于批量/脚本静默执行
ClusterSetupCommand.createCluster(nodes, /*replicas*/ 1, /*verbose*/ false);
```

#### 常见错误

| 错误 | 原因 | 处理 |
|------|------|------|
| `节点连接失败` | 节点未启动或 `port` / `requirepass` 与配置不一致 | 检查 `port`、`requirepass` 是否生效；防火墙是否放行 |
| `集群状态校验失败: cluster_state=ok 但 cluster_slots_assigned < 16384` | Gossip 拓扑未收敛 | v1.0.4/v1.0.8 已修复 |
| `--cluster-replicas 的值必须为整数` | 参数格式错误 | 传整数 N |
| `节点数 (N) 与 replicas (M) 不匹配` | N ≤ M 或 (N - M*master) ≠ 0 | 调整节点数，确保 `N / (1 + M) ≥ 3` |

> 历史版本在 `redis-cli --cluster create` 场景下会卡在 `Waiting for the cluster to join`。v1.0.4 / v1.0.8 通过多项修复（Gossip 主动建连、`GossipTask` 不再跳过 HANDSHAKE、Gossip 携带槽位所有权）彻底解决。

## 3. 添加从节点

新增节点 `192.168.8.161:9739` 作为 `192.168.8.161:9736` 的从节点：

```bash
# 1. 在新节点加入集群
redis-cli -h 192.168.8.161 -p 9736 CLUSTER MEET 192.168.8.161 9739

# 2. 在新节点上执行 REPLICATE 指定主节点
redis-cli -h 192.168.8.161 -p 9739 CLUSTER REPLICATE <master-node-id>
```

`<master-node-id>` 可通过 `CLUSTER NODES` 在主节点标志为 `master` 的行第一列获取。

验证：

```bash
redis-cli -h 192.168.8.161 -p 9739 CLUSTER NODES
```

应看到该节点角色为 `slave`，主节点字段对应 `192.168.8.161:9736` 的节点 ID。

## 4. 扩容

新增主节点 `192.168.8.161:9740`，从其他主节点迁移部分槽位过来：

```bash
# 1. 加入集群
redis-cli -h 192.168.8.161 -p 9736 CLUSTER MEET 192.168.8.161 9740

# 2. 在源主节点上标记待迁移槽位为迁移状态
redis-cli -h 192.168.8.161 -p 9736 CLUSTER SETSLOT 5000 MIGRATING <new-master-id>

# 3. 在目标主节点上标记为导入状态
redis-cli -h 192.168.8.161 -p 9740 CLUSTER SETSLOT 5000 IMPORTING <source-master-id>

# 4. 逐键迁移（生产建议按批执行并限速）
redis-cli -h 192.168.8.161 -p 9736 MIGRATE 192.168.8.161 9740 "" 0 5000 <timeout>

# 5. 槽位转移完成
redis-cli -h 192.168.8.161 -p 9736 CLUSTER SETSLOT 5000 NODE <new-master-id>
redis-cli -h 192.168.8.161 -p 9740 CLUSTER SETSLOT 5000 NODE <new-master-id>
```

对每个待迁移槽位重复 2–5 步。

## 5. 缩容

下线从节点直接 `FORGET`；下线主节点需先迁移走全部槽位和从节点。

```bash
# 下线从节点
redis-cli -h <any-node> CLUSTER FORGET <slave-node-id>

# 下线主节点前置：迁移所有槽位到其他主节点
# （参考 §4 扩容流程反向操作）
# 然后 FORGET
redis-cli -h <any-node> CLUSTER FORGET <master-node-id>
```

**注意**：`FORGET` 仅从集群视图移除节点，重启该节点后需先清空 `cluster-config-file` 再以非集群模式启动或重新 `MEET`。

## 6. 故障转移

### 6.1 自动故障转移

Gossip 协议检测流程（参考 [功能架构 17.6](../architecture/features.md#176-gossip-协议)）：

1. 节点超过 `cluster-node-timeout` 未响应 → 标记 `PFAIL`（可能下线）
2. 多数主节点确认 → 升级为 `FAIL`（下线）
3. 该主节点的从节点发起选举
4. 赢得多数投票的从节点晋升为新主

客户端可在 `cluster-node-timeout × 2` 内收到重定向或临时错误，建议实现重试逻辑。

### 6.2 手动故障转移

在从节点上执行强制转移（适用于主节点不可达但需立即切换）：

```bash
redis-cli -h <slave-host> -p <slave-port> CLUSTER FAILOVER
```

带选项：

| 选项 | 行为 |
|------|------|
| （无） | 正常故障转移，需主节点确认 |
| `FORCE` | 强制故障转移，不与主节点通信 |
| `TAKEOVER` | 直接接管（集群脑裂风险，仅紧急使用） |

## 7. MOVED / ASK 重定向

客户端命令如命中非本节点槽位，节点返回：

- **`-MOVED slot ip:port`**：槽位稳定归属另一节点。客户端应更新本地槽位缓存并向 `ip:port` 重发。
- **`-ASK slot ip:port`**：槽位正在迁移中。客户端应先发 `ASKING` 再向 `ip:port` 重发（仅本次）。

### Jedis 客户端示例

Maven 依赖：

```xml
<dependency>
    <groupId>redis.clients</groupId>
    <artifactId>jedis</artifactId>
    <version>4.4.0</version>
</dependency>
```

Java 调用示例：

```java
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("192.168.8.161", 9736));
nodes.add(new HostAndPort("192.168.8.161", 9737));
nodes.add(new HostAndPort("192.168.8.161", 9738));

JedisCluster client = new JedisCluster(nodes);
// client 会自动处理 MOVED/ASK 重定向
```

Lettuce、Redisson 同样原生支持，详细参见 [功能架构 17.7](../architecture/features.md#177-客户端兼容性)。

## 8. 常见问题

### 8.1 cluster_state 持续为 fail

**原因**：存在未分配槽位（`cluster_slots_assigned < 16384`）。

**解决**：
- 检查 `cluster-require-full-coverage` 是否设为 `yes`
- 执行 `CLUSTER INFO` 查看 `cluster_slots_assigned` 与 `cluster_slots_ok`
- 补齐缺失槽位或临时设 `cluster-allow-reads-when-down yes`（仅读）

### 8.2 总线端口不通导致节点孤立

**症状**：节点可访问自身服务端口，但 `CLUSTER NODES` 显示其他节点为 `disconnected`。

**解决**：
- 检查节点间总线端口（默认 `port + 10000`）是否互通
- NAT 环境下确认 `cluster-announce-bus-port` 配置正确
- 通过 `CLUSTER INFO` 中 `cluster_stats_messages_sent` 与 `received` 是否增长判断 Gossip 是否正常

### 8.3 nodes.conf 恢复（v1.0.4+）

v1.0.4 起，`cluster-config-file`（默认 `nodes.conf`）由 `ClusterConfigPersister` 在拓扑变更时自动持久化，节点重启时自动加载：

- **健康重启**：节点自动从 `nodes.conf` 加载节点列表、槽位分配、config epoch，复用已有节点 ID，重建 `SlotManager`，启动即可正常服务
- **全集群重启**：节点启动时主动 `MEET` 已知节点，避免全集群重启后节点成孤岛无法恢复
- **磁盘损坏**：删除 `nodes.conf` 后重启会以新节点 ID 启动，需重新执行 `CLUSTER MEET` 与 `CLUSTER ADDSLOTS`
- **脑裂恢复**：多数派节点选举新纪元（`config-epoch`），少数派重启后被 `FORGET` 重新加入
- **版本兼容**：v1.0.0 ~ v1.0.3 生成的含 `fail` 标志的 `nodes.conf` 可平滑升级，解析时会忽略 `fail` 标志

**持久化触发点**：

| 来源 | 触发场景 |
|------|----------|
| `ClusterCommandHandler` | 处理 MEET / FORGET / ADDSLOTS / DELSLOTS / SETSLOT / REPLICATE 等命令 |
| `GossipProtocol` | 节点变更、Gossip 拓扑更新 |
| 周期任务 | 兜底刷新 dirty 配置（类 Redis 7 `clusterSaveConfigIfNeeded`） |

**运维建议**：
- 升级到 v1.0.4 后无需手动迁移，旧版 `nodes.conf` 会被自动兼容
- 全集群同时重启时建议保持节点时钟同步（< `cluster-node-timeout`），避免节点孤立超时

### 8.4 跨机房部署

建议：
- 通过 `cluster-announce-ip` 使用对外 IP 而非内网 IP
- 控制 `cluster-node-timeout` 至少为 RTT × 3
- 至少保证一个机房拥有多数主节点（≥ N/2 + 1）以避免脑裂

## 10. Mesh 集群部署（v1.0.15+）

Luban-RDS 自 v1.0.15 起提供 `luban-rds-mesh` 模块——**3 节点 Raft 强一致集群**，用 3 台机器替代 Redis Cluster 的 6 节点；写入需多数派 ACK + 落盘后才返回 OK，**已确认写入不丢**。本节给出速查，详细文档见 [docs/mesh/setup.md](../mesh/setup.md) 与 [docs/mesh/](../mesh/index.md)。

### 10.1 与 Redis Cluster 的差异

| 维度 | Redis Cluster（本文 1-9 节） | **Mesh（v1.0.15）** |
|------|-----------------------------|----------------------|
| 节点数 | 6+（3 主 3 从） | **3**（互为副本） |
| 数据分布 | 16384 slot 分片 | 全量，无分片 |
| 一致性 | 最终一致 | **强一致**（多数派 ACK + 落盘） |
| 启动编排 | `redis-cli --cluster create` 或手动 MEET+ADDSLOTS | 三节点各自 `mesh-enabled` + `mesh-peers` 静态 meet |
| 客户端接口 | `CLUSTER *` 全套 + `MOVED/ASK` | `CLUSTER SLOTS/NODES/INFO` + `MOVED/MESHDOWN` |
| 互斥关系 | 与 mesh 互斥（启动校验） | 与 cluster 互斥（启动校验） |

### 10.2 快速启动（3 节点示例）

以 3 台机器 `10.0.0.1` / `10.0.0.2` / `10.0.0.3` 为例，各自 `servicePort = 6379`、`busPort = 11000`：

```ini
# 节点 A / B / C 共用的 luban-rds.conf
mesh-enabled yes

# peers 列表：nodeId@host:busPort 逗号分隔，含自身
mesh-peers a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d@10.0.0.1:11000,\
c3d4e5f60718293a4b5c6d7e8f900a1b2c3d4e5f6@10.0.0.2:11000,\
e5f60718293a4b5c6d7e8f900a1b2c3d4e5f60718@10.0.0.3:11000

# 本节点 nodeId（未配取 peers 首个；生产建议显式指定）
mesh-self-node-id a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d

mesh-bus-port 11000
mesh-service-port 6379
```

或用 CLI 参数：

```bash
# 节点 A（10.0.0.1）
java -jar luban-rds-bin.jar --port 6379 \
  --mesh-enabled \
  --mesh-peers "a1b2...@10.0.0.1:11000,c3d4...@10.0.0.2:11000,e5f6...@10.0.0.3:11000" \
  --mesh-self-node-id a1b2c3d4e5f60718293a4b5c6d7e8f900a1b2c3d \
  --mesh-bus-port 11000

# 节点 B / C 类似，仅 --mesh-self-node-id 不同
```

### 10.3 客户端连接

**集群感知客户端（JedisCluster / lettuce / Redisson）— 推荐**

经 `CLUSTER SLOTS` 引导 + `MOVED` 自动跟随，**对客户端零侵入**：

```java
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("10.0.0.1", 6379));
nodes.add(new HostAndPort("10.0.0.2", 6379));
nodes.add(new HostAndPort("10.0.0.3", 6379));
try (JedisCluster jedis = new JedisCluster(nodes)) {
    jedis.set("foo", "bar");
}
```

**普通客户端（new Jedis / redis-cli）**

连到 Follower 时会收到 `MOVED`，需手动重连 Leader：

```bash
redis-cli -h 10.0.0.1 -p 6379 CLUSTER NODES   # 查谁是 Leader（myself,master 行）
redis-cli -h <leader-host> -p 6379 SET foo bar
```

### 10.4 运维命令速查

```bash
redis-cli -p 6379 CLUSTER INFO
# cluster_state:ok           # 有 Leader 时为 ok，选举中为 fail
# cluster_known_nodes:3

redis-cli -p 6379 CLUSTER NODES
# <node-id> 10.0.0.1:6379@11000 myself,master ... connected   # 当前 Leader（linkState 恒 connected）
# <node-id> 10.0.0.2:6379@11000 slave ... connected
# <node-id> 10.0.0.3:6379@11000 slave ... connected

redis-cli -p 6379 CLUSTER SLOTS
# [[0, 16383, ["10.0.0.1", "6379", "a1b2..."]], [], []]       # 包含空 replicas 数组
```

错误响应：

| 场景 | 响应 |
|------|------|
| 写打到 Follower | `-MOVED <slot> <leaderAddr>\r\n`（slot 为 key 的真实 CRC16） |
| 选举中无 Leader | `-MESHDOWN The mesh cluster has no leader\r\n` |

### 10.5 关键约束

- **`mesh-enabled` 与 `cluster-enabled` 互斥**：同一进程只能启用其一
- **BLOCK 命令禁用**：`BLPOP / BRPOP / BLMOVE / WAIT` v1 返回错误（Raft 化阻塞唤醒留待 v2）
- **Lua 当写**：`EVAL / EVALSHA` 统一按写处理走 Raft 复制
- **AOF 退役**：mesh 模式不写 AOF（Raft log 即 WAL）；dump.rdb 唯一写者 = `SnapshotManager`
- **NTP 时钟**：Leader Lease 读依赖时钟；漂移过大需切 `mesh-read-consistency READ_INDEX`

### 10.6 部署文档

- [docs/mesh/setup.md](../mesh/setup.md)：完整快速上手（配置 / 启动 / 客户端 / 运维命令）
- [docs/mesh/design.md](../mesh/design.md)：协议设计要点（拓扑、状态机、RPC、Lease、read-index、chunked snapshot）
- [luban-rds-mesh/README.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/README.md)：模块入口
- [luban-rds-mesh/docs/DESIGN.md](https://github.com/LUBAN-RDS/luban-rds/blob/master/luban-rds-mesh/docs/DESIGN.md) v1.2：完整协议设计

## 9. 下一步

- [配置指南 - 集群模式配置](./configuration.md#95-集群模式配置)
- [监控维护](./monitoring.md)
- [故障排查](./troubleshooting.md)
- [Mesh 集群部署 - 完整版](../mesh/setup.md)
