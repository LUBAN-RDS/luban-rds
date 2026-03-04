# 路线图

本文档展示了 Luban-RDS 项目的发展路线图，包括已完成的功能、正在进行的工作以及未来计划的功能。

> **当前版本**: 1.0.0-SNAPSHOT
> **最后更新**: 2026-03-04

## 已完成的功能 (Completed)

### 核心功能
- [x] **Redis 协议 (RESP) 支持**: 完整的请求解析与响应编码。
- [x] **内存存储**: 支持 String, List, Set, Hash, ZSet 五大基础数据类型。
- [x] **键过期时间**: 支持 `EXPIRE`, `TTL`, `PERSIST` 等命令。
- [x] **发布订阅 (Pub/Sub)**: 支持 `SUBSCRIBE`, `UNSUBSCRIBE`, `PUBLISH`。
- [x] **Lua 脚本支持**: 集成 LuaJ，支持 `EVAL`, `EVALSHA`, `SCRIPT LOAD` 等，包含沙箱模式。
- [x] **事务支持**: 完整实现 `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`，支持乐观锁机制。
- [x] **管道 (Pipeline)**: 基于 Netty 的 ByteBuf 处理，原生支持管道化请求。
- [x] **网络服务**: 基于 Netty 4.1 的高性能 NIO 服务器。
- [x] **客户端实现**: 提供 Java 客户端 `luban-rds-client`。
- [x] **Spring Boot 集成**: 提供 `luban-rds-spring-boot-starter`，支持自动配置。

### 持久化
- [x] **RDB 持久化**: 支持内存快照保存与加载（`SAVE`, `BGSAVE`）。
- [x] **AOF 持久化**: 支持追加写日志与重写（`BGREWRITEAOF`）。

### 性能与监控
- [x] **集合操作优化**: 直接修改集合对象，避免不必要的数据复制。
- [x] **Lua 脚本优化**: 脚本缓存、执行超时控制、指令计数。
- [x] **实时监控 (MONITOR)**: 基于 MPSC Ring Buffer 实现的高性能零内存分配监控。
- [x] **内存分析 (MEMORY)**: 支持 `MEMORY USAGE`, `MEMORY STATS`, `MEMORY DOCTOR` 等命令。
- [x] **INFO 命令重构**: 提供可扩展的服务器状态信息聚合框架。

### 安全特性
- [x] **Lua 沙箱**: 限制 Lua 脚本对系统资源的访问。
- [x] **基础认证**: 支持 `AUTH` 命令进行简单密码验证。
- [x] **命令超时控制**: 防止长时间运行的命令阻塞服务器。

## 正在开发的功能 (In Progress)

### 核心功能增强
- [x] **批量命令完善**: 
    - 已支持: `DEL` (多键), `LPUSH`/`RPUSH`/`SADD`/`ZADD` (多元素), `MSET`, `MGET`, `HMSET`, `HMGET`。
- [ ] **集群支持 (Cluster)**:
    - 当前状态: 仅保留 `CLUSTER SLOTS` 占位符。
    - 计划: 实现分片逻辑、节点间通信 (Gossip)、重定向机制。

### 性能优化
- [ ] **多线程 I/O**: 探索 Netty 的多线程模型以进一步提升网络吞吐量。
- [ ] **内存碎片整理**: 优化内存分配策略，减少长期运行后的碎片。

### 安全特性
- [ ] **访问控制列表 (ACL)**: 实现细粒度的用户权限控制（命令级、Key 模式级）。
- [ ] **传输加密 (TLS/SSL)**: 支持 SSL/TLS 加密连接，保障数据传输安全。

## 计划中的功能 (Planned)

### 高级数据类型
- [ ] **地理空间索引 (Geo)**: 支持 `GEOADD`, `GEODIST`, `GEORADIUS` 等。
- [ ] **位图 (Bitmap)**: 支持 `SETBIT`, `GETBIT`, `BITCOUNT`, `BITOP`。
- [ ] **超日志 (HyperLogLog)**: 支持 `PFADD`, `PFCOUNT`, `PFMERGE`。
- [ ] **流 (Stream)**: 支持完整的 Redis 5.0 Stream 数据结构。

### 高可用性
- [ ] **主从复制**: 实现全量复制与增量复制机制。
- [ ] **哨兵模式 (Sentinel)**: 监控主从拓扑，实现自动故障转移。

### 云原生与运维
- [ ] **Docker 容器化**: 提供官方 Docker 镜像。
- [ ] **Kubernetes Operator**: 简化在 K8s 环境下的部署与运维。
- [ ] **Prometheus Exporter**: 导出监控指标供 Prometheus 采集。

## 版本规划

### 1.0.x 系列 (当前)
- **目标**: 稳定基础功能，完善单元测试与文档。
- **1.0.0**: 核心功能发布（五大数据类型、持久化、Lua、事务）。
- **1.0.1**: 批量命令补全 (`MSET`, `MGET` 等) 与 Bug 修复。
- **1.0.2**: 安全性增强 (ACL 初步支持)。

### 1.1.x 系列
- **目标**: 高可用性与高级数据结构。
- **1.1.0**: 主从复制与哨兵模式。
- **1.1.1**: Bitmaps 与 HyperLogLog 支持。
- **1.1.2**: Geo 与 Stream 支持。

### 2.0.x 系列
- **目标**: 分布式与云原生。
- **2.0.0**: 完整的 Redis Cluster 支持。
- **2.1.0**: 云原生套件 (Docker/K8s)。

## 技术栈与依赖

- **语言**: Java 8+
- **构建工具**: Maven
- **核心框架**:
    - Netty 4.1.63.Final (网络层)
    - Spring Boot 2.3.12.RELEASE (集成支持)
    - Guava 30.1.1-jre (工具库)
    - Caffeine 2.9.3 (高性能缓存)
    - LuaJ (Lua 脚本引擎)
    - JUnit 4/5 (测试)

## 贡献

如果您对路线图有任何建议或想参与开发，请参考 [贡献指南](../development/contributing.md)。

---

### 文档更新记录

| 日期 | 版本 | 修改人 | Commit 范围 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| 2026-03-04 | 1.1 | Trae AI | HEAD | 同步代码库状态，更新已完成功能（事务、监控、Lua），调整版本规划 |
