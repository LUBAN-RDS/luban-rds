# Cluster Mode Configuration & Deployment Docs

**Date**: 2026-06-25
**Status**: Approved
**Scope**: Documentation only (no code changes)

## 1. Background

Luban-RDS 已实现完整的 Redis Cluster 协议（`luban-rds-cluster` 模块，含 `ClusterConfig`、`ClusterBusServer`、`GossipProtocol`、`SlotManager` 等），但 `docs/` 缺乏面向运维的集群配置与部署指南：

- `docs/deployment/configuration.md` 第 15.6 节「集群环境变量」仅有 3 行覆盖（`cluster-enabled`、`cluster-config-file`、`cluster-node-timeout`），缺少网络公告、Gossip、迁移、可达性等关键配置
- 没有专用的集群部署文档（节点初始化、扩缩容、故障转移、配置恢复等操作流程缺失）
- `docs/deployment/index.md` 导航未指向集群文档

而 `docs/architecture/features.md` 第 17 章已对协议层组件、槽位计算、Gossip、MOVED/ASK 重定向、客户端兼容性做了较完整描述（17.1–17.7），不需要在本次范围内同步。

本次需求：补全集群模式在「部署运维」层的文档，与「功能架构」层已有的协议描述形成闭环。

## 2. Goals

- 运维人员能根据文档完成 3 主节点集群的初始化、扩容、缩容、故障恢复
- 配置项查阅体验与现有 `configuration.md` 其他章节一致（conf 示例 + 环境变量表 + 默认值表）
- 不重复 `architecture/features.md` 已有的协议层描述（链接跳转即可）

## 3. Non-Goals

- 不修改 `luban-rds-cluster` 模块任何 Java 代码
- 不修改 `architecture/features.md`（协议层描述已足够）
- 不新增 `api/commands.md` 中的 CLUSTER 命令章节（现有 API 文档维护节奏另行安排）
- 不引入英文版本（保持与现有 `configuration.md` 一致的中文风格）

## 4. File Changes

### 4.1 `docs/deployment/configuration.md`

**A. 在「9. 高级配置」末尾追加 9.5 集群模式配置**（保持原章节序号，不插入到 9.x 中间避免大段位移）

新增内容包含 4 张子表 + 1 个 conf 示例片段：

| 子表 | 配置项 |
|------|--------|
| 基础 | `cluster-enabled`、`cluster-config-file`、`cluster-node-timeout`、`cluster-slots-validity-factor`、`cluster-migration-barrier`、`cluster-require-full-coverage`、`cluster-allow-reads-when-down` |
| 网络公告 | `cluster-announce-ip`、`cluster-announce-port`、`cluster-announce-bus-port`、`cluster-announce-hostname` |
| Gossip | `cluster-gossip-interval`（默认 1000ms，对齐 `GossipProtocol.DEFAULT_GOSSIP_INTERVAL`）、`cluster-gossip-timeout` |
| 从节点 | `cluster-replica-validity-factor`、`cluster-replica-serve-stale-data`、`cluster-replication-factor` |

文档明确：**总线端口默认 = 服务端口 + 10000**（引用 `ClusterBusServer.BUS_PORT_OFFSET = 10000`）；若 `cluster-announce-bus-port` 显式配置则覆盖默认值（适用于 NAT/端口映射场景）。

**B. 替换现有第 15.6 节「集群环境变量」表格**（仅 3 行 → 完整表，覆盖 A 中的全部配置项 + 集群总线端口 `LUBAN_RDS_CLUSTER_BUS_PORT`）

**C. 在第 12 章「配置示例」末尾追加 12.4 集群配置示例**

最小 3 主节点示例，含 `cluster-enabled yes`、`cluster-config-file nodes.conf`、`cluster-node-timeout 15000`、服务端口与总线端口的注释说明。

### 4.2 新增 `docs/deployment/cluster-setup.md`

按运维操作时间线组织：

1. **前置要求**：至少 3 主节点、端口规划（每个节点 2 端口：服务 + 总线）、节点 ID 自动生成说明（`CLUSTER MYID`）
2. **初始化流程**：
   - 多节点启动（每个节点启用 `cluster-enabled`，独立 `nodes.conf`）
   - `CLUSTER MEET ip port` 互连（n×(n-1)/2 次或一个节点连其他全部）
   - 槽位分配：`CLUSTER ADDSLOTS slot [slot ...]`（典型三主节点各分 5461/5461/5462 槽）
   - 验证：`CLUSTER INFO`、`CLUSTER NODES`
3. **添加从节点**：`CLUSTER REPLICATE master-node-id`
4. **扩容**：新节点 → `CLUSTER MEET` → `CLUSTER SETSLOT slot NODE new-id IMPORTING/MIGRATING` + `MIGRATE` 命令 + `CLUSTER SETSLOT slot NODE new-id`
5. **缩容**：`CLUSTER FORGET node-id`（先迁移走槽位/下线从节点再 forget）
6. **故障转移**：
   - 自动：Gossip 检测 → PFAIL → FAIL → 从节点发起选举（引用 `features.md` 17.6）
   - 手动：`CLUSTER FAILOVER [FORCE|TAKEOVER]`
7. **MOVED / ASK 重定向行为**：客户端应如何处理（引用 `features.md` 17.5，给出 JedisCluster 配置示例 `JedisPoolConfig` + `Set<HostAndPort>`）
8. **常见问题**：
   - 槽位未全分配导致集群 `cluster_state:fail`
   - 总线端口不通导致 Gossip 失败
   - 节点失联后 `nodes.conf` 恢复流程（`cluster-config-file` 路径与重启顺序）
   - 跨机房部署的网络公告配置

### 4.3 `docs/deployment/index.md`

**D. 在「部署内容」导航列表中新增一项**：

```
- **[集群部署](./cluster-setup.md)** — Redis Cluster 模式的初始化、扩缩容和故障转移
```

在「部署方式」表格中保留现有行，不新增独立行（集群模式作为独立服务的一种实现）。

在「关键特性」中已有「集群模式」字样，无需修改。

## 5. Style Consistency

- 表格列：`配置项 | 类型 | 默认值 | 说明`（与 `configuration.md` 已有的 2.x/3.x/4.x 表格一致）
- 环境变量列：`变量名 | 配置项 | 默认值 | 描述`（与 15.x 节一致）
- 配置文件示例统一用 ` ```conf ` 代码块
- 命令示例统一用 ` ```bash ` 代码块，命令前缀 `redis-cli -h ... -p ...`
- 引用其他文档使用相对路径 `./configuration.md` 或 `../architecture/features.md`

## 6. Verification

- `docs/.vitepress/config.mjs` 中 sidebar 配置若显式列举 deployment 子文件，需追加 `cluster-setup.md`；若使用自动生成（按目录扫描），则无需修改
- 通过本地 `cd docs && npm run docs:dev` 启动 VitePress，确认 3 个改动文件能正确渲染、链接可跳转
- 检查所有内部链接（`./configuration.md` 配置项锚点、`../architecture/features.md` 章节锚点）目标存在

## 7. Out of Scope

- 不更新 `AGENTS.md` 第 10 节（架构描述，由后续架构文档同步任务处理）
- 不更新 `docs/guide/` 中任何文件（用户使用指南与运维文档分离）
- 不涉及代码示例（所有命令示例均使用标准 `redis-cli`）
