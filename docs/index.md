---
title: Luban-RDS 文档
last_updated: 2026-07-30
version: 1.0.10
---

<div align="center">

# ⚡ Luban-RDS

**轻量级 · 高性能 · 嵌入式 Java 内存数据库**

<p style="display: flex; justify-content: center; gap: 8px; flex-wrap: wrap;">
  <img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License">
  <img src="https://img.shields.io/badge/Java-17+-green.svg" alt="Java">
  <img src="https://img.shields.io/badge/Maven-3.6+-blue.svg" alt="Maven">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.4.11-green.svg" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Netty-4.2.10.Final-orange.svg" alt="Netty">
  <img src="https://img.shields.io/badge/Redis-Protocol%20Compatible-red.svg" alt="Redis Compatible">
</p>

---

**完全兼容 Redis 协议** | **嵌入式零依赖** | **生产级高性能**

</div>

---

## 🎯 项目简介

**Luban-RDS** 是一款轻量级、高性能、完全兼容 Redis 协议的嵌入式 Java 内存数据库。专为需要嵌入式缓存或内存数据库的场景设计，无需独立部署 Redis 服务，即可在 JVM 应用中享受完整的 Redis 功能。

### 核心优势

| 特性 | 描述 |
|:---:|:---|
| 🚀 **高性能** | 基于 Netty NIO 框架，优化的内存管理，纳秒级命令执行延迟 |
| 🔌 **嵌入式** | 无需独立进程，直接嵌入 JVM 应用，零运维成本 |
| 📡 **协议兼容** | 完整支持 RESP 协议，兼容所有标准 Redis 客户端 |
| 💾 **持久化** | 支持 RDB 快照和 AOF 日志双重持久化机制 |
| 🔒 **安全可控** | Lua 脚本沙箱模式，细粒度权限控制，脚本超时保护 |
| 📊 **可观测** | 内置 MONITOR、SLOWLOG、MEMORY 监控命令，实时性能追踪 |
| 🌱 **Spring 集成** | 提供 Spring Boot Starter，自动配置，开箱即用 |

---

## 📚 文档导航

### 🚀 快速入门
**[开始使用 →](./guide/quickstart.md)** — 5 分钟快速上手 Luban-RDS  
环境要求与安装 · 基本配置与启动 · 第一个示例程序

### 🏗️ 架构设计
**[深入了解 →](./architecture/index.md)** — 系统架构与设计原理  
[系统架构](./architecture/system.md) · [核心特性](./architecture/features.md) · [设计文档](./architecture/design.md)

### 📖 使用指南
**[详细教程 →](./guide/index.md)** — 完整的使用说明与最佳实践  
[安装指南](./guide/installation.md) · [基本用法](./guide/basic-usage.md) · [高级功能](./guide/advanced.md) · [代码示例](./guide/examples.md)

### 📡 API 参考
**[查阅文档 →](./api/index.md)** — 完整的 API 文档与命令参考  
[命令列表](./api/commands.md) · [核心接口](./api/core.md) · [协议说明](./api/protocol.md)

### 📜 Lua 脚本
**[脚本编程 →](./lua/index.md)** — Lua 脚本引擎详细说明  
[脚本 API](./lua/api.md) · [使用指南](./lua/usage.md)

### 🛠️ 部署运维
**[运维指南 →](./deployment/index.md)** — 生产环境部署与维护  
[配置指南](./deployment/configuration.md) · [安装部署](./deployment/installation.md) · [监控维护](./deployment/monitoring.md) · [故障排查](./deployment/troubleshooting.md)

---

## ✨ 版本特性

### v1.0.10 (已发布 · 2026-07-30)

#### 🛡️ 集群持久化并发安全
- ✅ **修复并发保存 `nodes.conf` 的竞态条件**: `ClusterConfigPersister` 对拓扑变更与周期 `clusterSaveConfigIfNeeded` 共路径场景下的并发刷盘加锁与串行化，避免半写文件与状态丢失

### v1.0.9 (已发布 · 2026-07-30)

#### 🛡️ P0 数据安全补遗
- ✅ **C2 PSYNC / REPLCONF 链路**: 激活 PSYNC 响应路由，REPLCONF 顺序等待 + 超时，重新启用 `ReplicationIntegrationTest`
- ✅ **C3 AOF `recordCommand` 接口 + SELECT db 标记**: 补齐 AOF 落盘链路，`CompositePersistService` 委托实现
- ✅ **C4 SLAVEOF 启动复制**: `SLAVEOF` 命令实际触发复制链路
- ✅ **C5 全量同步窗口回放**: 全量同步期间的增量写入在同步窗口完成后正确回放
- ✅ **C6 从节点 offset + WAIT**: 校验从节点 replication offset，`WAIT N` 行为对齐 Redis
- ✅ **C10 RDB TTL 持久化**: 补齐 RDB TTL 编码（`0xFD`）

### v1.0.8 (已发布 · 2026-07-27)

#### 🛡️ P0 数据安全与 Redis 7 兼容性修复
- ✅ **C1 CROSSSLOT 校验**: 集群模式下对多键命令执行 CROSSSLOT 验证，避免数据路由到错误槽位
  - `ClusterCommandHandler` 新增多键命令槽位归属校验
  - 不在同一槽位的多键命令返回标准的 `-CROSSSLOT Keys in request don't hash to the same slot`
- ✅ **C7 MIGRATE 原子性**: 多键槽位迁移改用一次性 RESTORE，保证迁移过程原子化
- ✅ **C8 故障转移选举**: 选举算法改用真实复制偏移（replication offset）替代旧的随机/固定值
- ✅ **C9 手动故障转移广播**: `CLUSTER FAILOVER` 完成后通过 `FAILOVER_RESULT` 消息向全集群广播结果
- ✅ **C11 AOF 重写按类型**: AOF rewrite 改为按数据类型分别重写，二进制安全；AOF 加载同样二进制安全
- ✅ **C12 ZSet 同分数排序**: ZSet 相同分数成员的排序改为字典序（与 Redis 官方一致）

### v1.0.7 (已发布)

#### 🔧 集群通信与复制路径增强
- ✅ **集群通信重构**: 重构集群总线通信机制，提升 Gossip 与 PING/PONG 可靠性
- ✅ **`CLUSTER SET-CONFIG-EPOCH`**: 新增命令支持，`ADDSLOTS` 后自动同步 configEpoch
- ✅ **槽位归属一致性**: 全面修复 `slotManager` / `clusterConfig` / `ClusterNode` 三重槽位归属不一致
- ✅ **故障转移后 `CLUSTER SLOTS`**: 修复 `ClusterConfig.slotAssignment` 同步，避免路由信息错乱
- ✅ **Gossip 携带 masterNodeId**: `GossipNodeInfo` 新增字段，传播 master-slave 关系
- ✅ **CLUSTER REPLICATE 角色传播**: 从节点角色经 Gossip 协议正确扩散
- ✅ **ClusterNode 线程安全**: 关键读写方法加 `synchronized`

### v1.0.6 (已发布)

#### 🔧 集群 PFAIL / 自动故障转移联动
- ✅ **PFAIL 投票经 Gossip 传播**: 修复 PFAIL 状态无法通过 Gossip 扩散的问题，恢复自动故障转移链路
- ✅ **FailoverManager 状态机**: 新增状态机 + 消息分发接线 + NettyRedisServer 注入
- ✅ **`FAILOVER_RESULT` 消息类型**: 新增 0x08 消息类型（`FailoverResultMessage`），优雅通知故障转移结果
- ✅ **`ClusterConfig.getSlavesOfMaster`**: 暴露主从关系查询
- ✅ **gracePeriod 配置**: 故障转移宽限期可配置

### v1.0.5 (已发布)

#### 🚀 集群自动故障转移（initial）
- ✅ **FailoverManager 骨架**: 引入自动选举 / 状态切换的基础组件
- ✅ **Gossip 状态整合**: 为后续 PFAIL→FAIL 升级与投票链路预留接入点
- 🛠️ **工程内部**: 累积若干调试日志与可观测性改进

### v1.0.4 (已发布)

#### 🛠️ 集群配置持久化与节点恢复
- ✅ **nodes.conf 自动持久化**: 集群拓扑变更（MEET/FORGET/ADDSLOTS 等）后自动落盘
  - 引入 dirty flag 机制，避免每次操作都同步刷盘
  - 实现类 Redis 7 `clusterSaveConfigIfNeeded` 的周期性检查机制
- ✅ **节点状态恢复**: 节点重启后从 `nodes.conf` 加载完整集群拓扑
  - 复用已有节点 ID，避免重启后拓扑分裂
  - 自动重建 `SlotManager` 槽位表，启动后即可正常服务
  - 启动时主动连接已知节点，避免全集群重启后节点成孤岛
  - 兼容旧版含 `fail` 标志的 `nodes.conf` 文件

### v1.0.3 (已发布)

#### 🛠️ 集群一键搭建 CLI
- ✅ **`RedisCliMain`**: 内置 `redis-cli --cluster create` 兼容 CLI
  - 多节点一行命令完成集群创建、主从划分、16384 槽位均分
  - 支持 `verbose` 静默模式，便于脚本化部署
  - Java 程序化嵌入调用：`ClusterSetupCommand.createCluster(...)`

#### 🔧 网络层健壮性
- ✅ **TCP 半包/粘包修复**: `RedisProtocolParser` + `NettyRedisClient` 共同修复
  - 半包回退机制、CRLF 检测与解析死循环防护
  - 累积缓冲 + 循环解析，处理跨段 RESP 与多响应合包

### v1.0.2 (已发布)

#### 🔧 集群兼容性与可靠性
- ✅ **`CLUSTER SLOTS`**: 完整实现，返回当前槽位分布数组
- ✅ **Gossip 拓扑修复**: 解决 `redis-cli --cluster create` 卡在 `Waiting for the cluster to join`
  - Gossip 发现节点后主动建连 / `MEET`
  - `GossipTask` 不再跳过 `HANDSHAKE` 状态节点
  - Gossip 消息携带槽位所有权
- ✅ **`CLUSTER NODES` 行尾**: 改用裸 `\n`，Redisson 解析不再抛 `NumberFormatException`
- ✅ **`CLUSTER MEET` 装配**: 修复握手协议与临时 ID 解析
- ✅ **`cluster_enabled` 字段**: `CLUSTER INFO` / `INFO` 同步返回
- ✅ **非集群模式**: 正确跳过 `CLUSTER` 命令拦截

### v1.0.1 (已发布)

#### 🚀 分布式能力
- ✅ **Redis Cluster**: 完整集群协议实现
  - 16384 槽位分配与管理
  - MOVED/ASK 重定向机制
  - Gossip 心跳检测
  - 集群总线协议
- ✅ **主从复制**: 支持完整的 Redis 主从复制协议
  - 全量同步（RDB 传输）
  - 增量同步（基于复制积压缓冲区）
- ✅ **哨兵模式**: 实现哨兵模式核心功能

### v1.0.0 (已发布)

#### 🎨 核心功能
- ✅ **数据结构**: 完整支持 String、List、Hash、Set、ZSet、Stream 六大核心数据结构
- ✅ **过期机制**: 支持 Key 级别的 TTL 过期策略
- ✅ **事务支持**: MULTI/EXEC/DISCARD/WATCH 事务命令

#### 📡 协议与网络
- ✅ **RESP 协议**: 完整实现 Redis Serialization Protocol，支持 RESP2 和 RESP3
- ✅ **Pipeline**: 支持命令管道，批量执行提升性能
- ✅ **Pub/Sub**: 发布订阅模式，支持频道订阅、模式订阅和流订阅

#### 💾 持久化
- ✅ **RDB 快照**: 内存数据快照持久化，使用 Kryo 序列化
- ✅ **AOF 日志**: 命令追加式持久化

#### 🔧 脚本与扩展
- ✅ **Lua 脚本**: 集成 LuaJ 引擎，支持 EVAL/EVALSHA
- ✅ **沙箱模式**: 可配置的安全脚本执行环境
- ✅ **Redis API**: 完整的 `redis.call()` / `redis.pcall()` / `redis.sha1hex()` 支持
- ✅ **struct 库增强**: 支持 Lc0、Ic0、ic0 等组合格式说明符，变长字符串打包和解包

#### 📊 监控与性能
- ✅ **MONITOR**: 实时命令监控（采用 MPSC 无锁环形缓冲区，<40ns 开销）
- ✅ **SLOWLOG**: 慢查询日志记录
- ✅ **MEMORY**: 内存使用分析和诊断
- ✅ **INFO**: 服务器状态信息聚合
- ✅ **Benchmark**: 内置性能测试工具

#### 🌱 生态集成
- ✅ **Spring Boot**: 官方 Starter 自动配置支持

---

## 🤝 参与贡献

我们欢迎所有形式的贡献！

**[贡献指南 →](./development/index.md)**

- [环境搭建](./development/setup.md)
- [构建指南](./development/build.md)
- [测试指南](./development/testing.md)
- [代码规范](./development/standards.md)
- [贡献流程](./development/contributing.md)

---

## 📄 许可证

Luban-RDS 采用 **[Apache License 2.0](./legal/license.md)** 许可证开源。

---

<div align="center">

**[⬆ 返回顶部](#-luban-rds)**

Made with ❤️ by Luban-RDS Team

</div>
