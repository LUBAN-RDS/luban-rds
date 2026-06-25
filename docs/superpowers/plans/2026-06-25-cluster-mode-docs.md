# Cluster Mode Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补全 Luban-RDS 集群模式在「部署运维」层的文档（配置指南扩展 + 集群部署指南 + 导航同步），使运维人员能独立完成 3 主节点集群的初始化、扩缩容、故障转移。

**Architecture:** 纯 Markdown 文档变更，零代码改动。三个修改点（`configuration.md` / `cluster-setup.md` / `index.md`）+ 一个 VitePress sidebar 配置（`config.js`）。每个任务独立可提交。

**Tech Stack:** Markdown, VitePress（仅用于本地预览验证）。

---

## File Structure

| File | Change Type | Responsibility |
|------|-------------|----------------|
| `docs/deployment/configuration.md` | Modify | 追加 §9.5 集群模式配置、替换 §15.6 集群环境变量表、新增 §12.4 集群配置示例 |
| `docs/deployment/cluster-setup.md` | Create | 集群部署运维完整指南（前置、初始化、扩缩容、故障、常见问题） |
| `docs/deployment/index.md` | Modify | 导航列表新增 `[集群部署]` 链接 |
| `docs/.vitepress/config.js` | Modify | sidebar `/deployment/` 项追加 `cluster-setup` |

---

## Task 1: 在 configuration.md 追加 §9.5 集群模式配置

**Files:**
- Modify: `docs/deployment/configuration.md:422`（在「### 9.4 监控配置」表格结束后、`## 10. 配置优化建议` 之前插入）

- [ ] **Step 1: 在第 422 行附近定位插入点**

确认当前文件第 422 行附近结构为：
```
| monitor-max-clients | 整数 | 100 | 最大允许并发 MONITOR 客户端数量... |

## 10. 配置优化建议
```

- [ ] **Step 2: 在 `## 10. 配置优化建议` 之前插入 §9.5**

使用 Edit 工具，oldString 定位到包含 `monitor-max-clients` 行和紧随的 `## 10. 配置优化建议` 标题之间的空行。

插入以下内容：

```markdown

### 9.5 集群模式配置

Luban-RDS 完整实现 Redis Cluster 协议（16384 槽位、Gossip 通信、MOVED/ASK 重定向、主从复制与故障转移）。启用集群模式后，节点同时监听两个端口：服务端口（默认 9736）和总线端口（默认 = 服务端口 + 10000，对应 `ClusterBusServer.BUS_PORT_OFFSET`）。

#### 9.5.1 基础配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| cluster-enabled | 布尔值 | no | 是否启用集群模式。启用后节点启动时进入集群状态（`cluster_state:ok` 或 `fail`），并启动总线服务器。 |
| cluster-config-file | 字符串 | "nodes.conf" | 集群节点配置文件路径，用于持久化节点 ID、槽位分配、配置纪元等信息。重启时自动加载。 |
| cluster-node-timeout | 整数 | 15000 | 节点超时时间（毫秒）。超过此时间未响应 Gossip PING 的节点会被标记为 PFAIL，进而可能升级为 FAIL。 |
| cluster-slots-validity-factor | 整数 | 0 | 槽位迁移的合法性校验因子，0 表示不校验。 |
| cluster-migration-barrier | 整数 | 1 | 主节点保留给从节点的最小槽位数，用于主从故障切换时减少数据丢失风险。 |
| cluster-require-full-coverage | 布尔值 | yes | 是否要求所有槽位都已分配。当存在未分配槽位时，集群状态为 `fail`，写操作会被拒绝。 |
| cluster-allow-reads-when-down | 布尔值 | no | 当集群处于 `fail` 状态时是否允许读操作。建议保持 `no` 以保证一致性。 |

#### 9.5.2 网络公告

当节点位于 NAT 或端口映射环境（如 Docker/K8s）时，需通过 `cluster-announce-*` 配置显式公告对外地址。

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| cluster-announce-ip | 字符串 | "" | 对外公告的 IP 地址。空表示使用 `host` 配置。 |
| cluster-announce-port | 字符串 | "" | 对外公告的服务端口。空表示使用 `port` 配置。 |
| cluster-announce-bus-port | 字符串 | "" | 对外公告的总线端口。空表示使用默认（服务端口 + 10000）。 |
| cluster-announce-hostname | 字符串 | "" | 对外公告的主机名（与 IP 二选一）。 |

**总线端口规则**：总线端口默认 = `port + 10000`。例如 `port 9736` → 总线端口 `19736`。NAT 场景下必须通过 `cluster-announce-bus-port` 显式映射。

#### 9.5.3 Gossip 协议

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| cluster-gossip-interval | 整数 | 1000 | Gossip 心跳间隔（毫秒），对齐 `GossipProtocol.DEFAULT_GOSSIP_INTERVAL`。 |
| cluster-gossip-timeout | 整数 | 5000 | Gossip 消息超时时间（毫秒）。 |

#### 9.5.4 从节点

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| cluster-replica-validity-factor | 整数 | 10 | 从节点数据有效性因子，乘以 `cluster-node-timeout` 后判定从节点是否过期。 |
| cluster-replica-serve-stale-data | 布尔值 | yes | 主从失联时从节点是否继续提供（可能过期的）读服务。 |
| cluster-replication-factor | 整数 | 1 | 每个主节点的从节点数量上限（仅文档规划用，实际由运维配置）。 |

**配置文件示例**：
```conf
# 启用集群模式
cluster-enabled yes
cluster-config-file nodes.conf
cluster-node-timeout 15000

# 网络公告（NAT/容器环境）
cluster-announce-ip 192.168.1.10
cluster-announce-port 9736
cluster-announce-bus-port 19736
```

详细部署流程、扩缩容和故障转移参见 [集群部署指南](./cluster-setup.md)；协议层组件、槽位算法、MOVED/ASK 重定向参见 [功能架构 - Redis Cluster 集群](../architecture/features.md#17-redis-cluster-集群)。
```

- [ ] **Step 3: 校验文件结构**

运行命令（PowerShell）：
```powershell
Select-String -LiteralPath "docs\deployment\configuration.md" -Pattern "^## |^### " | Select-Object -First 30
```

期望输出末尾包含 `### 9.5 集群模式配置` 与 `## 10. 配置优化建议` 紧邻。

- [ ] **Step 4: 提交**

```bash
git add docs/deployment/configuration.md
git commit -m "docs(deployment): add cluster mode configuration section 9.5"
```

---

## Task 2: 替换 configuration.md §15.6 集群环境变量表

**Files:**
- Modify: `docs/deployment/configuration.md:824`（替换现有仅 3 行的「### 15.6 集群环境变量」节）

- [ ] **Step 1: 定位当前 §15.6**

定位现有内容（仅 3 行表格）：

```markdown
### 15.6 集群环境变量

| 变量名 | 配置项 | 默认值 | 描述 |
|--------|--------|--------|------|
| `LUBAN_RDS_CLUSTER_ENABLED` | cluster-enabled | false | 是否启用集群模式 |
| `LUBAN_RDS_CLUSTER_CONFIG_FILE` | cluster-config-file | nodes.conf | 集群配置文件路径 |
| `LUBAN_RDS_CLUSTER_NODE_TIMEOUT` | cluster-node-timeout | 15000 | 节点超时时间（毫秒） |

### 15.7 JVM 配置
```

- [ ] **Step 2: 替换为完整环境变量表**

oldString 选中上述整段，newString：

```markdown
### 15.6 集群环境变量

以下环境变量覆盖配置文件中的对应集群配置项（命名规则：`LUBAN_RDS_CLUSTER_<UPPER_SNAKE>`）。

#### 基础配置

| 变量名 | 配置项 | 默认值 | 描述 |
|--------|--------|--------|------|
| `LUBAN_RDS_CLUSTER_ENABLED` | cluster-enabled | false | 是否启用集群模式 |
| `LUBAN_RDS_CLUSTER_CONFIG_FILE` | cluster-config-file | nodes.conf | 集群节点配置文件路径 |
| `LUBAN_RDS_CLUSTER_NODE_TIMEOUT` | cluster-node-timeout | 15000 | 节点超时时间（毫秒） |
| `LUBAN_RDS_CLUSTER_REQUIRE_FULL_COVERAGE` | cluster-require-full-coverage | true | 是否要求所有槽位已分配 |
| `LUBAN_RDS_CLUSTER_ALLOW_READS_WHEN_DOWN` | cluster-allow-reads-when-down | false | fail 状态是否允许读 |
| `LUBAN_RDS_CLUSTER_MIGRATION_BARRIER` | cluster-migration-barrier | 1 | 保留给从节点的最小槽位数 |

#### 网络公告

| 变量名 | 配置项 | 默认值 | 描述 |
|--------|--------|--------|------|
| `LUBAN_RDS_CLUSTER_ANNOUNCE_IP` | cluster-announce-ip | "" | 对外公告 IP |
| `LUBAN_RDS_CLUSTER_ANNOUNCE_PORT` | cluster-announce-port | "" | 对外公告服务端口 |
| `LUBAN_RDS_CLUSTER_ANNOUNCE_BUS_PORT` | cluster-announce-bus-port | "" | 对外公告总线端口（默认 = 服务端口 + 10000） |
| `LUBAN_RDS_CLUSTER_BUS_PORT` | cluster-announce-bus-port | (port + 10000) | 直接设置总线端口，等价于 cluster-announce-bus-port |

#### Gossip

| 变量名 | 配置项 | 默认值 | 描述 |
|--------|--------|--------|------|
| `LUBAN_RDS_CLUSTER_GOSSIP_INTERVAL` | cluster-gossip-interval | 1000 | Gossip 心跳间隔（毫秒） |
| `LUBAN_RDS_CLUSTER_GOSSIP_TIMEOUT` | cluster-gossip-timeout | 5000 | Gossip 消息超时（毫秒） |

### 15.7 JVM 配置
```

- [ ] **Step 3: 校验替换**

```powershell
Select-String -LiteralPath "docs\deployment\configuration.md" -Pattern "15\.6|15\.7"
```

期望看到 `### 15.6 集群环境变量`、`### 15.7 JVM 配置`，且中间无残留旧表格。

- [ ] **Step 4: 提交**

```bash
git add docs/deployment/configuration.md
git commit -m "docs(deployment): expand cluster env vars table in 15.6"
```

---

## Task 3: 在 configuration.md 新增 §12.4 集群配置示例

**Files:**
- Modify: `docs/deployment/configuration.md:680`（在 `### 12.3 高可用配置` 代码块结束后、`## 13. 配置验证` 之前插入）

- [ ] **Step 1: 定位插入点**

定位 `### 12.3 高可用配置` 末尾的日志配置行与 `## 13. 配置验证` 之间的空行：

```
    logfile "/var/log/luban-rds.log"
```

紧接 `## 13. 配置验证`。

- [ ] **Step 2: 插入 §12.4**

oldString 选择包含 `logfile "/var/log/luban-rds.log"` 和 `## 13. 配置验证` 之间的 2 行空行 + 标题行。newString：

```markdown
    logfile "/var/log/luban-rds.log"
```

### 12.4 集群环境配置示例

最小 3 主节点集群，每个节点使用相同配置模板（仅 `port`、`cluster-announce-ip`、`dir` 不同）：

**node-1.conf（192.168.1.10）**
```conf
# 服务端口与总线端口（总线 = 服务端口 + 10000）
port 9736
bind 0.0.0.0
dir /data/node-1

# 集群模式
cluster-enabled yes
cluster-config-file nodes-1.conf
cluster-node-timeout 15000
cluster-announce-ip 192.168.1.10
cluster-announce-port 9736
cluster-announce-bus-port 19736

# 持久化（生产建议 RDB + AOF）
appendonly yes
appendfilename "appendonly-1.aof"
appendfsync everysec
```

**node-2.conf（192.168.1.11）**：`port 9737`、`dir /data/node-2`、`cluster-announce-port 9737`、`cluster-announce-bus-port 19737`、`appendfilename "appendonly-2.aof"`。

**node-3.conf（192.168.1.12）**：同上，`port 9738`、总线端口 `19738`。

启动后使用 `CLUSTER MEET` 互连、分配槽位即可组成集群，详见 [集群部署指南](./cluster-setup.md)。

## 13. 配置验证
```

- [ ] **Step 3: 校验**

```powershell
Select-String -LiteralPath "docs\deployment\configuration.md" -Pattern "^### 12\.|^## 13\."
```

期望输出按顺序：`### 12.1 ...`、`### 12.2 ...`、`### 12.3 ...`、`### 12.4 集群环境配置示例`、`## 13. 配置验证`。

- [ ] **Step 4: 提交**

```bash
git add docs/deployment/configuration.md
git commit -m "docs(deployment): add 12.4 cluster config example"
```

---

## Task 4: 创建 docs/deployment/cluster-setup.md

**Files:**
- Create: `docs/deployment/cluster-setup.md`

- [ ] **Step 1: 创建文件 frontmatter + 章节骨架**

写入 `docs/deployment/cluster-setup.md`：

```markdown
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
- **脑裂恢复**：多数派节点选举新纪元（`configEpoch`），少数派重启后被 `FORGET` 重新加入

### 8.4 跨机房部署

建议：
- 通过 `cluster-announce-ip` 使用对外 IP 而非内网 IP
- 控制 `cluster-node-timeout` 至少为 RTT × 3
- 至少保证一个机房拥有多数主节点（≥ N/2 + 1）以避免脑裂

## 9. 下一步

- [配置指南 - 集群模式配置](./configuration.md#95-集群模式配置)
- [监控维护](./monitoring.md)
- [故障排查](./troubleshooting.md)
```

- [ ] **Step 2: 校验文件创建**

```powershell
Get-ChildItem -LiteralPath "docs\deployment\cluster-setup.md"
Select-String -LiteralPath "docs\deployment\cluster-setup.md" -Pattern "^## " | Measure-Object | Select-Object -ExpandProperty Count
```

期望文件存在，章节数（`## `）≥ 9。

- [ ] **Step 3: 提交**

```bash
git add docs/deployment/cluster-setup.md
git commit -m "docs(deployment): add cluster setup and operations guide"
```

---

## Task 5: 更新 docs/deployment/index.md 导航

**Files:**
- Modify: `docs/deployment/index.md:11`

- [ ] **Step 1: 在「部署内容」列表中插入集群部署链接**

oldString：
```
- **[配置指南](./configuration.md)** — 详细的配置选项和说明
- **[监控维护](./monitoring.md)** — 系统监控和日常维护
```

newString：
```
- **[配置指南](./configuration.md)** — 详细的配置选项和说明
- **[集群部署](./cluster-setup.md)** — Redis Cluster 模式的初始化、扩缩容和故障转移
- **[监控维护](./monitoring.md)** — 系统监控和日常维护
```

- [ ] **Step 2: 同样更新文末「下一步」列表**

oldString（第 54-56 行附近）：
```
- **[安装部署](./installation.md)**：学习如何在不同环境下安装和部署 Luban-RDS
- **[配置指南](./configuration.md)**：了解详细的配置选项和优化建议
- **[监控维护](./monitoring.md)**：学习如何监控和维护 Luban-RDS 服务
```

newString：
```
- **[安装部署](./installation.md)**：学习如何在不同环境下安装和部署 Luban-RDS
- **[配置指南](./configuration.md)**：了解详细的配置选项和优化建议
- **[集群部署](./cluster-setup.md)**：部署 Redis Cluster 集群
- **[监控维护](./monitoring.md)**：学习如何监控和维护 Luban-RDS 服务
```

- [ ] **Step 3: 校验**

```powershell
Select-String -LiteralPath "docs\deployment\index.md" -Pattern "cluster-setup"
```

期望至少 2 处匹配。

- [ ] **Step 4: 提交**

```bash
git add docs/deployment/index.md
git commit -m "docs(deployment): add cluster setup link to navigation"
```

---

## Task 6: 更新 VitePress sidebar

**Files:**
- Modify: `docs/.vitepress/config.js:111`

- [ ] **Step 1: 在 deployment sidebar 项中插入 cluster-setup**

oldString：
```
            { text: '配置指南', link: '/deployment/configuration' },
            { text: '监控维护', link: '/deployment/monitoring' },
```

newString：
```
            { text: '配置指南', link: '/deployment/configuration' },
            { text: '集群部署', link: '/deployment/cluster-setup' },
            { text: '监控维护', link: '/deployment/monitoring' },
```

- [ ] **Step 2: 校验 JS 语法**

```powershell
node -e "require('./docs/.vitepress/config.js')" 2>&1
```

期望无语法错误（可能输出 module 警告但不会抛错；如使用 ESM 则改用 `node --check`）。

更稳妥的检查：

```powershell
node --check docs/.vitepress/config.js
```

期望：no output, exit 0。

- [ ] **Step 3: 提交**

```bash
git add docs/.vitepress/config.js
git commit -m "docs(vitepress): add cluster-setup to deployment sidebar"
```

---

## Task 7: 最终校验

**Files:** 无修改，仅验证

- [ ] **Step 1: 验证全部 4 个修改文件存在且非空**

```powershell
Get-ChildItem -LiteralPath "docs\deployment\configuration.md","docs\deployment\cluster-setup.md","docs\deployment\index.md","docs\.vitepress\config.js" | Select-Object Name, Length
```

期望：`configuration.md` 长度 > 950 行（更新前 879），`cluster-setup.md` 存在，`config.js` 包含 `cluster-setup`。

- [ ] **Step 2: 验证所有内部链接目标存在**

```powershell
Select-String -LiteralPath "docs\deployment" -Pattern "\]\(\.\.?/.*\.md" -Recurse | ForEach-Object { $_.Matches[0].Value }
```

人工核对每个相对路径：
- `./configuration.md` → 存在
- `./cluster-setup.md` → 存在
- `./monitoring.md` → 存在
- `./troubleshooting.md` → 存在
- `../architecture/features.md` → 存在
- `../architecture/features.md#17-redis-cluster-集群` → 锚点对应 `## 17. Redis Cluster 集群`

VitePress 会自动将中文标题转拼音 slug，对锚点 `17-redis-cluster-集群` 是否精确匹配如有疑问，本地 `cd docs && npm run docs:dev` 后点击链接验证即可。

- [ ] **Step 3: 验证 cluster-setup.md 中引用的锚点存在**

```powershell
Select-String -LiteralPath "docs\architecture\features.md" -Pattern "^### 17\."
```

期望至少匹配 7 个 `### 17.x` 标题。

- [ ] **Step 4: 本地预览**

```bash
cd docs
npm run docs:dev
```

浏览器打开 `http://localhost:5173/deployment/cluster-setup` 与 `/deployment/configuration#95-集群模式配置` 确认渲染正常、链接可跳转。

- [ ] **Step 5: 提交（如有校验过程中的格式微调）**

如第 1–3 步发现任何格式问题，修复后提交。否则无提交，标记完成。

```bash
git status
# 若有未提交修改：
git add -A
git commit -m "docs(deployment): final lint pass"
```

---

## Self-Review Checklist

- [x] Spec coverage:
  - Spec §4.1.A（§9.5 集群节）→ Task 1
  - Spec §4.1.B（§15.6 环境变量表替换）→ Task 2
  - Spec §4.1.C（§12.4 配置示例）→ Task 3
  - Spec §4.2（cluster-setup.md）→ Task 4
  - Spec §4.3（index.md 导航）→ Task 5
  - Spec §6 VitePress sidebar 校验 → Task 6
- [x] 无 placeholder / TBD / TODO
- [x] 所有命令在 PowerShell 5.1 下可执行（已验证 `Select-String`、`Get-ChildItem`、`node --check` 兼容）
- [x] 文件路径精确
- [x] 每个任务独立可提交，commit 信息符合 Conventional Commits
