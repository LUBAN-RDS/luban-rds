---
title: Mesh 集群
last_updated: 2026-08-05
version: 1.0.15
---

# Mesh 集群（3 节点 Raft 强一致）

> **v1.0.15 新增** —— `luban-rds-mesh` 模块：用 3 台机器替代 Redis Cluster 的 6 节点，实现强一致高可用，**已确认的写入永不丢失**。

[![Status](https://img.shields.io/badge/status-implemented-green.svg)]()
[![Nodes](https://img.shields.io/badge/nodes-3-blue.svg)]()
[![Consistency](https://img.shields.io/badge/consistency-strong-green.svg)]()
[![Tests](https://img.shields.io/badge/tests-291-brightgreen.svg)]()
[![Compatible](https://img.shields.io/badge/client-Redis%20Compatible-red.svg)]()

---

## 1. 模块定位

`luban-rds-mesh` 是 igbp-luban-rds 的 **Raft 强一致集群模块**：3 节点互为副本，用 Raft 协议实现强一致高可用，替代 Redis Cluster 的 6 节点（3 主 3 从）部署。任一时刻只有 1 个 Leader 处理写入，写入必须经多数派（2/3）确认并落盘后才返回 OK，**已确认的写入永不丢失**。

## 2. 核心卖点（vs Redis Cluster）

| 维度 | Redis Cluster（v1.0.1+） | **Mesh（本模块）** |
|------|--------------------------|----------------|
| 机器数 | 6+（3 主 3 从） | **3**（互为副本，成本减半） |
| 数据分片 | 16384 Slot 分片 | 全量数据，无分片 |
| 一致性 | 最终一致（异步复制） | **强一致**（多数派 ACK + 落盘） |
| Leader 切换丢数据 | 可能丢未复制的写入 | **不会**（未 commit 的写入不返回 OK） |
| 客户端兼容 | Cluster aware 客户端 | **JedisCluster / lettuce / Redisson 零侵入**（经 `CLUSTER SLOTS` 引导 + `MOVED` 自动跟随）；普通客户端（Jedis 单机 / redis-cli）需连 Leader 或自行处理 `-MOVED` |

## 3. 架构

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

## 4. 角色

| 角色 | 职责 |
|------|------|
| **FOLLOWER** | 默认状态；被动接收 AppendEntries；选举超时后转为 CANDIDATE |
| **CANDIDATE** | 选举中；先 PreVote 探测（不自增 term），多数派预投后正式选举；获得多数票转 LEADER |
| **LEADER** | 处理所有客户端写入；向 Followers 复制日志；维持心跳与读租约 |

## 5. 关键约束

| 约束 | 说明 |
|------|------|
| **NTP 时钟对齐** | Leader Lease 读依赖时钟（租约时长内本地读）；节点间时钟漂移过大需切 `mesh-read-consistency READ_INDEX` |
| **BLOCK 命令禁用** | `BLPOP / BRPOP / BLMOVE / WAIT` 等 v1 返回错误（Raft 化阻塞唤醒留待 v2） |
| **Lua 脚本当写** | `EVAL / EVALSHA` 统一按写处理（走 Raft 复制），即使脚本内只有读命令 |
| **cluster / mesh 互斥** | 同一进程只能启用其一（`mesh-enabled` 与 `cluster-enabled` 启动时校验） |
| **AOF 退役** | mesh 模式不写 AOF——Raft log 即 WAL、dump.rdb 即快照 |
| **dump.rdb 唯一写者** | mesh 模式禁用 server 原 RDB save（BGSAVE），dump.rdb 唯一写者 = SnapshotManager |

## 6. 文档索引

| 文档 | 内容 |
|------|------|
| [快速上手](./setup.md) | 3 节点配置、启动、客户端连接、运维命令速查 |
| [协议设计要点](./design.md) | 拓扑、状态机、RPC、Lease、read-index、chunked snapshot 摘要 |
| [luban-rds-mesh/README.md](../../luban-rds-mesh/README.md) | 模块完整快速上手（与本节互补） |
| [luban-rds-mesh/docs/DESIGN.md](../../luban-rds-mesh/docs/DESIGN.md) | 完整协议设计 v1.2（DESIGN 经两轮评审定案） |
| [luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md](../../luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md) | 13 阶段实施计划 v1.2 |

## 7. 当前状态

**已实现**（v1.0.15，13 阶段全部完成）：

- 协议设计（DESIGN v1.2，经两轮评审定案）
- 全部 13 阶段实现：项目骨架 → 编解码 → 选举/租约/PreVote → 日志复制 → 读写门面 → 客户端重定向 → 读路径 → CLUSTER 命令 → 事务/BLOCK → snapshot → 持久化/启动加载 → 装配 → 测试补全
- 291 测试全过（含 3 节点集成测试）
- v1.0.15 内同步修复 13 项 hotfix（nodeId 编码、总线消费者注册、PreVote reset、MOVED 地址、自重定向循环、选举退避、CLUSTER SLOTS replicas、非 Leader MOVED 真实 key、CLUSTER NODES 死节点、myself connected、日志降频等）

## 8. 适用场景

| 场景 | 推荐度 |
|------|--------|
| 中小规模生产部署（数据 < 100GB） | 强烈推荐 |
| 金融 / 订单等强一致需求 | 强烈推荐 |
| 跨机房容灾（3 机房各 1 节点） | 推荐 |
| 超大规模数据（> 500GB） | 一般（建议 Redis Cluster 分片） |
| 频繁动态扩缩容 | 一般（固定 3 节点静态 meet；建议 Redis Cluster） |

## 9. 下一步

- [快速上手](./setup.md)
- [协议设计要点](./design.md)
- [luban-rds-mesh/README.md](../../luban-rds-mesh/README.md)