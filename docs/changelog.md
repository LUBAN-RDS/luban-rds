---
title: 更新日志
last_updated: 2026-07-07
version: 1.0.3
---
# 更新日志

Luban-RDS 是一款轻量级、高性能、完全兼容 RESP 协议的 Java 内存数据库，易于嵌入和扩展。

## [1.0.3] - 2026-07-07

### 新增功能

- **Redis 集群创建 CLI 工具**（`RedisCliMain`）
  - 模仿 `redis-cli --cluster create` 子集，支持远程编排集群搭建
  - 通过 `--cluster create <host:port> ... [--cluster-replicas N]` 参数创建 3 主 + N 从的集群
  - 自动完成 `CLUSTER MEET`、主从划分、16384 槽位均分、状态校验
  - 提供 `ClusterSetupCommand` 静态方法 `createCluster(...)` 便于程序化嵌入调用
  - 新增 `verbose` 参数支持静默模式（用于脚本/批量场景）

### 修复

- **客户端半包/粘包问题**（`NettyRedisClient`）
  - 添加 `ByteBuf` 累积缓冲区，正确处理跨 TCP 段的 RESP 响应
  - 实现循环解析机制，处理同一 TCP 段中的多个完整响应
  - 通过 `readerIndex` 标记区分半包与完整响应的解析状态
  - 连接关闭时正确释放累积缓冲区，避免内存泄漏
- **协议解析器半包处理逻辑**（`RedisProtocolParser`）
  - `parseBulkStringBytes` 方法添加 reader index 回退机制处理半包
  - 为所有解析分支（简单字符串、错误、整数、批量字符串、数组、映射、集合等）统一添加 null 检查
  - 解析失败时重置缓冲区读取索引，避免协议解析死循环
  - 完善 CRLF 检测逻辑，确保半包数据能正确等待后续字节
  - 优化 `parseResp` 错误处理流程，提升解析稳定性

### 兼容性

- 与 Jedis / Lettuce / Redisson / `redis-cli --cluster create` 全流程验证通过
- `CLUSTER NODES` / `CLUSTER SLOTS` / `CLUSTER INFO` 输出格式与 Redis 官方一致

## [1.0.2] - 2026-07-07

### 新增功能

- **CLUSTER SLOTS 命令**：完整实现 Redis `CLUSTER SLOTS` 命令，返回当前槽位分布数组
- **集群节点过滤优化**：在节点列表中过滤下线/未握手节点，避免返回陈旧拓扑

### 修复

- **修复 `redis-cli --cluster create` 卡在 "Waiting for the cluster to join"**
  - Gossip 发现新节点后主动建连/`MEET`，保证拓扑收敛
  - `GossipTask` 心跳不再跳过 `HANDSHAKE` 状态节点，握手流程正常推进
  - Gossip 消息携带槽位所有权信息，`cluster_state` 能够正确转为 `ok`
- **修复 CLUSTER NODES 行尾符导致 Redisson 解析异常**
  - `ClusterCommandHandler.clusterNodes()` 每行改用裸 `\n` 结尾（对齐真实 Redis 行为）
  - 解决 Redisson `ClusterNodesDecoder` 因残留 `\r` 而抛 `NumberFormatException` 导致集群初始化失败的问题
- **修复 `CLUSTER MEET` 命令在集群模式下装配缺陷**：在集群模式下正确路由 `CLUSTER MEET` 到集群命令处理器
- **修复集群节点握手协议和临时 ID 解析机制**：`MEET` 消息识别握手阶段返回的临时 ID，避免误判为已知节点
- **补全 `CLUSTER INFO` 与 `INFO` 的 `cluster_enabled` 字段**：使第三方监控/健康检查能正确判定集群模式开关
- **禁用集群模式时跳过 CLUSTER 命令拦截**：避免 `cluster-enabled=no` 时仍拦截 CLUSTER 命令带来的语义混淆

### 优化

- 集群调试日志级别调整为 `TRACE`，降低生产环境日志开销

## [1.0.1] - 2026-03-24

### 新增功能

- **Redis Cluster 集群模式**：完整实现 Redis Cluster 协议兼容
  - 16384 槽位分配与管理（BitSet 优化）
  - MOVED/ASK 重定向机制
  - Gossip 协议心跳检测
  - PFAIL/FAIL 故障检测
  - 槽位迁移（IMPORTING/MIGRATING 状态）
  - 集群总线协议（端口 + 10000）
  - Hash Tag 语法支持 `{tag}`
  - Jedis/Lettuce/Redisson 客户端兼容性测试
- **主从复制**：完整支持 Redis 主从复制协议
  - 全量同步（RDB 传输）
  - 增量同步（基于复制积压缓冲区）
  - 复制状态管理
  - 从节点只读模式
- **哨兵模式（Sentinel）**：实现哨兵模式核心功能

### 变更

- 升级 Spring Boot 版本至 3.4.11

## [1.0.0] - 2026-03-04

### 新增功能

- **协议支持**
  - 完整 RESP 协议解析与编码，支持 RESP2 和 RESP3
  - 完整 RESP3 协议支持，包括新数据类型（Map、Set、Null、Boolean、Double、Big Number）
  - 协议版本自动检测和切换，支持 RESP2 和 RESP3 客户端
- **数据结构**
  - 内存数据结构与过期支持（String/List/Set/Hash/ZSet/Stream）
  - Stream 数据类型支持：完整实现 Stream 相关命令（XADD, XLEN, XRANGE, XREVRANGE, XREAD, XGROUP, XREADGROUP 等）
- **Lua 脚本**
  - Lua 脚本执行（EVAL/EVALSHA/SCRIPT），沙箱与执行统计
  - Lua struct 库增强：支持 Lc0、Ic0、ic0 等组合格式说明符，变长字符串打包和解包
- **持久化**
  - RDB 与 AOF 持久化机制
- **网络服务**
  - 基于 Netty 的高并发 NIO 服务器
  - 多线程 I/O 优化：三层线程模型（Boss → Worker → Business）
  - 内存池集成：Netty PooledByteBufAllocator
- **发布订阅**
  - 发布/订阅：频道订阅、模式订阅和流订阅
- **事务支持**
  - 事务支持：MULTI/EXEC/DISCARD/WATCH
  - 键版本控制机制，支持 WATCH 乐观锁
- **集成与扩展**
  - Spring Boot Starter 自动配置集成
- **监控与管理**
  - 内存统计与 MEMORY 命令族
  - 高性能 MONITOR 命令与事件管线（采用 MPSC 无锁环形缓冲区，<40ns 开销）
  - 慢查询日志功能（SLOWLOG GET/LEN/RESET）
  - 分布式追踪支持：基于 TraceId 的全链路追踪，自动注入日志 MDC
- **命令扩展**
  - 批量命令支持：MSET, MGET, HMSET, HMGET, DEL (多键)
  - 多元素推入：LPUSH/RPUSH/SADD/ZADD 支持多元素
  - 扩展字符串命令：SETNX, GETSET, SETRANGE, GETRANGE, PSETEX
  - 扩展集合命令：SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF, SSCAN
  - 扩展有序集合命令：ZREVRANGE, ZRANGEBYSCORE, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY, ZPOPMAX, ZPOPMIN, ZSCAN
  - 扩展列表命令：LINDEX, LSET, LREM, LTRIM
  - 扩展哈希命令：HSETNX, HINCRBY, HSCAN
  - 客户端管理命令：CLIENT LIST, CLIENT KILL, CLIENT SETNAME, CLIENT GETNAME
  - BLPOP/BRPOP 阻塞命令支持：完整实现 Redis 规范的阻塞列表弹出命令
- **内存管理**
  - 内存碎片整理：自动/手动
- **部署支持**
  - Docker 部署支持
  - Kubernetes 部署支持
- **配置**
  - 配置文件 Lua 支持：新增 lua-timeout、lua-sandbox-enabled、lua-max-script-bytes 等配置项

### 变更

- 升级 Netty 版本至 4.2.10.Final
- 升级 Caffeine 版本至 3.2.3
- 升级 Guava 版本至 33.5.0-jre
- 升级 Kryo 版本至 5.6.0
- RDB 持久化改用 Kryo 序列化框架
- MONITOR 命令支持 DB 和 MATCH 过滤参数

### 修复

- 修复事务执行时的响应序列化问题
- 修复 WATCH 机制在多数据库场景下的键版本检查
- 修复 String.intern() 导致的内存泄漏问题，改用分段锁机制
- 修复过期键竞态条件问题，使用双重检查锁定机制
- 修复 STRLEN 命令返回字符长度而非字节长度的问题
- 修复 MSET 命令缺少原子性保证的问题
- 修复 LRU 淘汰策略性能问题，优化采样算法
- 修复 AOF 持久化命令解析不完整问题，支持 20+ 种命令类型
- 修复 RDB 持久化 ZSet 分数丢失问题，完整保存和恢复分数
- 修复 RDB 长度编码错误，添加边界检查
- 添加过期键主动清理机制，避免过期键长期占用内存
- 修复 Lua 脚本中 HSCAN/SSCAN/ZSCAN 嵌套数组解析问题
- 修复 BLPOP/BRPOP 命令未注册问题

### 安全

- 增强 Lua 脚本沙箱安全性
