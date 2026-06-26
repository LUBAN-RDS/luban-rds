---
title: 集群部署
---

# 集群部署

本指南介绍如何部署、初始化、扩缩容和运维 Luban-RDS 集群模式（Redis Cluster 协议）。协议层组件、槽位算法与重定向语义参见 [功能架构 - Redis Cluster 集群](../architecture/features.md#17-redis-cluster-集群)；配置项参考参见 [配置指南 - 集群模式配置](./configuration.md#95-集群模式配置)。

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

以最小 3 主节点集群（端口 9736/9737/9738）为例。

### 2.1 启动各节点

每个节点使用独立的配置文件与数据目录，分别启动：

```bash
# node-1
java -jar luban-rds.jar /etc/luban-rds/node-1.conf

# node-2
java -jar luban-rds.jar /etc/luban-rds/node-2.conf

# node-3
java -jar luban-rds.jar /etc/luban-rds/node-3.conf
```

此时三个节点相互独立，`CLUSTER INFO` 显示 `cluster_state:fail`、`cluster_known_nodes:1`、`cluster_slots_assigned:0`。

### 2.2 节点互连（CLUSTER MEET）

在任一节点上对其他两个节点执行 `CLUSTER MEET`：

```bash
redis-cli -h 192.168.1.10 -p 9736 CLUSTER MEET 192.168.1.11 9737
redis-cli -h 192.168.1.10 -p 9736 CLUSTER MEET 192.168.1.12 9738
```

也可在每个节点上分别 `MEET` 其余两个（实现全连接）。验证：

```bash
redis-cli -h 192.168.1.10 -p 9736 CLUSTER NODES
```

应看到 3 个节点，`connected` 标志均为 `connected`。

### 2.3 分配槽位

为三个主节点平均分配 16384 个槽位（5461 / 5461 / 5462）：

```bash
# 节点 1：0-5460
redis-cli -h 192.168.1.10 -p 9736 CLUSTER ADDSLOTS $(seq 0 5460)

# 节点 2：5461-10922
redis-cli -h 192.168.1.11 -p 9737 CLUSTER ADDSLOTS $(seq 5461 10922)

# 节点 3：10923-16383
redis-cli -h 192.168.1.12 -p 9738 CLUSTER ADDSLOTS $(seq 10923 16383)
```

### 2.4 验证集群健康

```bash
redis-cli -h 192.168.1.10 -p 9736 CLUSTER INFO
```

期望关键字段：

```
cluster_state:ok
cluster_slots_assigned:16384
cluster_known_nodes:3
cluster_size:3
```

## 3. 添加从节点

新增节点 `192.168.1.13:9739` 作为 `192.168.1.10:9736` 的从节点：

```bash
# 1. 在新节点加入集群
redis-cli -h 192.168.1.10 -p 9736 CLUSTER MEET 192.168.1.13 9739

# 2. 在新节点上执行 REPLICATE 指定主节点
redis-cli -h 192.168.1.13 -p 9739 CLUSTER REPLICATE <master-node-id>
```

`<master-node-id>` 可通过 `CLUSTER NODES` 在主节点标志为 `master` 的行第一列获取。

验证：

```bash
redis-cli -h 192.168.1.13 -p 9739 CLUSTER NODES
```

应看到该节点角色为 `slave`，主节点字段对应 `192.168.1.10:9736` 的节点 ID。

## 4. 扩容

新增主节点 `192.168.1.14:9740`，从其他主节点迁移部分槽位过来：

```bash
# 1. 加入集群
redis-cli -h 192.168.1.10 -p 9736 CLUSTER MEET 192.168.1.14 9740

# 2. 在源主节点上标记待迁移槽位为迁移状态
redis-cli -h 192.168.1.10 -p 9736 CLUSTER SETSLOT 5000 MIGRATING <new-master-id>

# 3. 在目标主节点上标记为导入状态
redis-cli -h 192.168.1.14 -p 9740 CLUSTER SETSLOT 5000 IMPORTING <source-master-id>

# 4. 逐键迁移（生产建议按批执行并限速）
redis-cli -h 192.168.1.10 -p 9736 MIGRATE 192.168.1.14 9740 "" 0 5000 <timeout>

# 5. 槽位转移完成
redis-cli -h 192.168.1.10 -p 9736 CLUSTER SETSLOT 5000 NODE <new-master-id>
redis-cli -h 192.168.1.14 -p 9740 CLUSTER SETSLOT 5000 NODE <new-master-id>
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

```java
Set<HostAndPort> nodes = new HashSet<>();
nodes.add(new HostAndPort("192.168.1.10", 9736));
nodes.add(new HostAndPort("192.168.1.11", 9737));
nodes.add(new HostAndPort("192.168.1.12", 9738));

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

### 8.3 nodes.conf 恢复

`cluster-config-file` 在每次集群状态变更时自动持久化。节点重启时自动加载：

- **健康重启**：节点重启后自动重新加入集群
- **磁盘损坏**：删除 `nodes.conf` 后重启会以新节点 ID 启动，需 `CLUSTER MEET` 与 `CLUSTER NODES` 重新加入
- **脑裂恢复**：多数派节点选举新纪元（`config-epoch`），少数派重启后被 `FORGET` 重新加入

### 8.4 跨机房部署

建议：
- 通过 `cluster-announce-ip` 使用对外 IP 而非内网 IP
- 控制 `cluster-node-timeout` 至少为 RTT × 3
- 至少保证一个机房拥有多数主节点（≥ N/2 + 1）以避免脑裂

## 9. 下一步

- [配置指南 - 集群模式配置](./configuration.md#95-集群模式配置)
- [监控维护](./monitoring.md)
- [故障排查](./troubleshooting.md)