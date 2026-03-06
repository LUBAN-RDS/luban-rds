---
title: 系统架构
---

# 系统架构

本部分详细介绍了 Luban-RDS 的整体系统架构，包括模块关系、数据流和核心组件。

## 1. 整体架构

### 1.1 架构图

```
+---------------------+
|    客户端 (Redis)    |
+---------------------+
          |
          v
+---------------------+
|  网络层 (Netty)     |
| - NettyRedisServer  |
| - RedisServerHandler|
+---------------------+
          |
          v
+---------------------+
|  协议层             |
| - RedisProtocolParser|
| - Command           |
+---------------------+
          |
          v
+---------------------+
|  命令处理层          |
| - CommandHandler    |
| - LuaCommandHandler |
+---------------------+
          |
          v
+---------------------+
|  存储层             |
| - MemoryStore       |
| - DefaultMemoryStore|
+---------------------+
          |
          v
+---------------------+
|  持久化层            |
| - PersistService    |
| - RdbPersistService |
| - AofPersistService |
+---------------------+
```

### 1.2 核心模块

| 模块 | 职责 | 关键组件 |
|------|------|----------|
| **luban-rds-core** | 核心业务逻辑 | `CommandHandler`, `MemoryStore`, `LuaCommandHandler`, `SlowLogManager` |
| **luban-rds-protocol** | 协议解析 | `RedisProtocolParser`, `Command`, `RespType` |
| **luban-rds-server** | 网络服务 | `NettyRedisServer`, `RedisServerHandler`, `PubSubManager`, `MonitorManager` |
| **luban-rds-persistence** | 持久化 | `PersistService`, `RdbPersistService`, `AofPersistService` |
| **luban-rds-client** | Java 客户端 | `RedisClient`, `NettyRedisClient` |
| **luban-rds-common** | 公共工具 | `Constants`, `Utils`, `RuntimeConfig`, `InfoProvider` |
| **luban-rds-bin** | 启动和基准测试 | `RedisServerMain`, `PerformanceBenchmark` |
| **luban-rds-spring-boot-starter** | Spring Boot 集成 | 自动配置类 |
| **luban-rds-benchmark** | 性能测试 | 性能测试工具 |

## 2. 数据流

### 2.1 请求处理流程

1. **客户端请求**：客户端发送 Redis 命令（RESP 格式）
2. **网络层**：`NettyRedisServer` 接收连接，`RedisServerHandler` 处理请求
3. **协议解析**：`RedisProtocolParser` 将 RESP 数据解析为 `Command` 对象
4. **命令分发**：根据命令类型分发给对应的 `CommandHandler`
5. **命令执行**：`CommandHandler` 调用 `MemoryStore` 执行操作
6. **结果返回**：将执行结果编码为 RESP 格式，通过 Netty 通道返回给客户端

### 2.2 数据流向图

```
客户端 → Netty 服务器 → 协议解析 → 命令分发 → 命令执行 → 存储操作 → 结果编码 → 客户端
```

## 3. 网络层

### 3.1 Netty 服务器

- **基于 Netty 4.2**：使用 NIO 非阻塞 IO，支持高并发
- **事件驱动**：通过 `RedisServerHandler` 处理网络事件
- **连接管理**：维护客户端连接池，支持连接超时和心跳检测
- **端口配置**：默认监听 9736 端口，可通过配置修改

### 3.2 RedisServerHandler

- **通道初始化**：设置解码器和编码器
- **数据读取**：处理客户端发送的数据
- **命令执行**：执行解析后的命令
- **结果返回**：将执行结果写回客户端

#### 入站缓冲与循环解析
- 每连接维护入站 ByteBuf，channelRead 到来的数据先累积以处理半包/粘包。
- 使用 while 循环解析缓冲区中的完整 RESP 帧并依次执行，支持 pipeline。
- 参考代码：[RedisServerHandler.java:L69-L88](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L69-L88)

### 3.3 线程模型

- **Boss 线程**：负责接受新连接
- **Worker 线程**：负责处理 IO 操作
- **Business 线程**：负责执行命令（可配置线程池大小）

## 4. 协议层

### 4.1 RedisProtocolParser

- **RESP 解析**：解析 Redis 序列化协议格式的数据
- **命令构建**：将解析后的数据构建为 `Command` 对象
- **数据类型**：支持 Simple String、Error、Integer、Bulk String、Array 等类型
- **RESP3 支持**：支持 RESP3 协议的 HELLO 命令协商

### 4.2 Command

- **命令封装**：封装命令名称和参数
- **参数验证**：验证命令参数的数量和类型
- **执行上下文**：提供命令执行的上下文信息

## 5. 命令处理层

### 5.1 CommandHandler

- **命令处理接口**：所有命令处理器的父接口
- **命令分发**：根据命令名称分发到具体的处理器
- **结果处理**：处理命令执行结果，转换为 RESP 格式

### 5.2 具体命令处理器

| 处理器 | 处理的命令 |
|--------|------------|
| **StringCommandHandler** | SET, SETNX, GET, GETSET, MSET, MGET, INCR, DECR, INCRBY, DECRBY, APPEND, STRLEN, SETRANGE, GETRANGE, PSETEX |
| **HashCommandHandler** | HSET, HSETNX, HMSET, HGET, HMGET, HGETALL, HDEL, HEXISTS, HKEYS, HVALS, HLEN, HINCRBY, HSCAN |
| **ListCommandHandler** | LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE, LREM, LINDEX, LSET |
| **SetCommandHandler** | SADD, SREM, SMEMBERS, SISMEMBER, SCARD, SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF |
| **ZSetCommandHandler** | ZADD, ZREM, ZRANGE, ZREVRANGE, ZRANGEBYSCORE, ZSCORE, ZCARD, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY |
| **CommonCommandHandler** | EXISTS, DEL, EXPIRE, PEXPIRE, TTL, PTTL, PERSIST, TYPE, FLUSHALL, FLUSHDB, ECHO, SELECT, INFO, SCAN, DBSIZE, TIME, LASTSAVE, BGREWRITEAOF, BGSAVE, KEYS |
| **AuthCommandHandler** | AUTH |
| **ClientCommandHandler** | CLIENT KILL, CLIENT LIST, CLIENT GETNAME, CLIENT PAUSE, CLIENT SETNAME |
| **LuaCommandHandler** | EVAL, EVALSHA, SCRIPT |
| **SelectCommandHandler** | SELECT |
| **RdsMemoryCommandHandler** | MEMORY USAGE, MEMORY STATS, MEMORY PURGE, MEMORY MALLOC-STATS, MEMORY DOCTOR, MEMORY HELP |
| **SlowLogCommandHandler** | SLOWLOG GET, SLOWLOG LEN, SLOWLOG RESET |

#### 事务语义
- MULTI 后普通命令返回 QUEUED 并入队；入队错误导致 EXEC 返回 EXECABORT。
- WATCH 检测到监视键版本变更时，EXEC 返回 Null Array（`*-1\r\n`），事务不执行。
- 事务内 `SELECT` 更新客户端数据库状态，影响后续命令上下文。
- 参考代码：[RedisServerHandler.java:L654-L661](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L654-L661)、[RedisServerHandler.java:L695-L701](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L695-L701)

### 5.3 LuaCommandHandler

- **Lua 脚本执行**：使用 LuaJ 执行 Lua 脚本
- **脚本缓存**：缓存脚本的 SHA1 哈希值
- **沙箱模式**：支持 Lua 脚本沙箱
- **超时控制**：支持脚本执行超时设置
- **Redis API**：支持 `redis.call()`, `redis.pcall()`, `redis.error_reply()`, `redis.status_reply()`, `redis.sha1hex()`

## 6. 存储层

### 6.1 MemoryStore

- **核心存储接口**：定义了所有数据类型的操作方法
- **多数据库**：支持多个逻辑数据库
- **过期时间**：支持键的过期时间设置
- **直接操作**：针对集合操作的直接修改优化
- **版本控制**：支持键版本控制，用于 WATCH 机制
- **ZSet 分数查询**：支持获取 ZSet 所有成员及其分数（zgetAllWithScores）

### 6.2 DefaultMemoryStore

- **内存存储实现**：使用 Java 集合存储数据
- **数据结构**：
  - String：直接存储字符串
  - List：使用 LinkedList
  - Set：使用 HashSet
  - Hash：使用 HashMap
  - ZSet：使用跳表结构（ZSetStore），保存成员与分数的映射
- **过期时间管理**：维护过期键的集合，定期清理

### 6.3 并发安全

- **分段锁机制**：使用 1024 个分段锁替代 String.intern()，避免内存泄漏
- **原子性操作**：MSET 等批量操作使用同步块保证原子性
- **竞态条件处理**：过期键检查使用双重检查锁定机制

### 6.4 过期键清理策略

- **惰性删除**：访问键时检查是否过期，过期则删除
- **主动清理**：后台定时任务（每 100ms）扫描并清理过期键
- **清理限制**：每次最多清理 100 个过期键，避免阻塞主线程

### 6.5 存储优化

- **直接修改**：集合操作直接修改底层集合，避免数据复制
- **内存管理**：支持最大内存限制和淘汰策略
- **LRU 优化**：优化采样算法，避免遍历所有键

## 7. 持久化层

### 7.1 PersistService

- **持久化接口**：定义了持久化的核心方法
- **备份恢复**：支持数据备份和恢复

### 7.2 RdbPersistService

- **RDB 持久化**：将内存数据以二进制格式保存到磁盘
- **保存策略**：支持定时保存和手动保存
- **压缩存储**：使用 Kryo 序列化框架进行高效存储
- **ZSet 分数保留**：完整保存和恢复 ZSet 成员的分数值
- **数据类型支持**：完整支持 String、List、Set、ZSet、Hash 五种数据类型

### 7.3 AofPersistService

- **AOF 持久化**：将写命令追加到 AOF 文件
- **同步策略**：支持 always、everysec、no 三种同步策略
- **重写机制**：支持 AOF 文件重写，减小文件大小
- **命令解析增强**：支持 20+ 种命令类型的完整解析
  - 字符串：SET、SETEX、PSETEX、SETNX、APPEND、INCR、DECR、INCRBY、DECRBY
  - 哈希：HSET、HMSET、HSETNX、HINCRBY、HINCRBYFLOAT、HDEL
  - 列表：LPUSH、RPUSH、LPOP、RPOP、LSET、LREM、LTRIM
  - 集合：SADD、SREM、SPOP
  - 有序集合：ZADD、ZREM、ZINCRBY
  - 通用：DEL、EXPIRE、PEXPIRE、EXPIREAT、PEXPIREAT、SELECT

### 7.4 持久化流程

1. **触发持久化**：根据配置的策略触发持久化
2. **数据收集**：收集需要持久化的数据
3. **格式转换**：将数据转换为持久化格式
4. **写入磁盘**：将数据写入磁盘文件
5. **文件同步**：根据同步策略同步到磁盘

## 8. 发布订阅

### 8.1 PubSubManager

- **频道管理**：管理频道和订阅关系
- **双向映射**：
  - 频道 → 订阅者（Channel 集合）
  - 订阅者 → 频道（String 集合）
- **模式订阅**：支持通配符模式订阅（PSUBSCRIBE）
- **流订阅**：支持流订阅（SSUBSCRIBE）
- **消息广播**：支持向频道的所有订阅者广播消息

### 8.2 订阅流程

1. **订阅频道**：客户端发送 SUBSCRIBE 命令
2. **添加订阅**：`PubSubManager` 添加订阅关系
3. **进入模式**：连接进入 Pub/Sub 模式
4. **发布消息**：客户端发送 PUBLISH 命令
5. **消息广播**：`PubSubManager` 向订阅者广播消息
6. **取消订阅**：客户端发送 UNSUBSCRIBE 命令
7. **移除订阅**：`PubSubManager` 移除订阅关系

### 8.3 消息格式

- **订阅确认**：`["subscribe", "channel", count]`
- **取消订阅确认**：`["unsubscribe", "channel", count]`
- **消息推送**：`["message", "channel", "message"]`
- **模式订阅确认**：`["psubscribe", "pattern", count]`
- **模式消息推送**：`["pmessage", "pattern", "channel", "message"]`

## 9. 监控系统

### 9.1 MonitorManager

- **实时监控**：提供 MONITOR 命令实现
- **MPSC Ring Buffer**：采用无锁环形缓冲区处理监控事件
- **零内存分配**：预分配事件对象与 StringBuilder 内存池
- **异步 Worker**：独立的 Worker 线程负责日志格式化与网络广播
- **历史快照**：新连接的监控客户端可立即获取最近的命令历史
- **服务端过滤**：支持按数据库 ID 或命令模式在服务端过滤

### 9.2 SlowLogManager

- **慢查询记录**：记录执行时间超过阈值的命令
- **日志管理**：支持获取、清空慢查询日志
- **性能分析**：帮助识别性能瓶颈

## 10. 客户端连接管理

### QUIT 响应与关闭流程
- QUIT 写回 `+OK\r\n` 后主动关闭连接。
- 参考代码：[RedisServerHandler.java:L752-L761](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L752-L761)

## 11. 配置系统

### 11.1 RuntimeConfig

- **配置管理**：管理运行时配置
- **配置加载**：从配置文件或命令行参数加载配置
- **动态调整**：部分配置支持运行时调整

### 11.2 配置项

- **服务器配置**：端口、主机、数据库数量等
- **内存配置**：最大内存限制、过期策略等
- **Lua 脚本配置**：超时时间、沙箱设置等
- **持久化配置**：RDB 和 AOF 相关设置
- **网络配置**：TCP 保活、超时设置等

## 12. 监控和统计

### 12.1 性能指标

- **命令执行次数**：统计各命令的执行次数
- **执行时间**：监控命令执行时间分布
- **内存使用**：跟踪内存使用情况
- **连接数**：监控活跃连接数

### 12.2 脚本统计

- **脚本执行次数**：统计脚本执行次数
- **执行时长**：监控脚本执行时间
- **错误计数**：统计脚本执行错误
- **缓存统计**：跟踪脚本缓存使用情况

### 12.3 INFO 命令

INFO 命令经过全面增强，采用 `InfoProvider` 架构聚合多源数据，提供更丰富、一致的系统状态：

- **Server**: 版本、OS、JVM 信息、进程 ID 等
- **Clients**: 连接数、阻塞客户端等
- **Memory**: 堆内存、Lua 内存、碎片率等
- **Persistence**: RDB/AOF 状态、最后保存时间、文件大小等
- **Stats**: 命令总数、网络流量、键命中率等
- **CPU**: 系统负载、CPU 使用率等
- **Keyspace**: 各数据库键数量、过期键数量等

## 13. 安全机制

### 13.1 Lua 沙箱

- **模块限制**：可配置允许使用的 Lua 模块
- **函数限制**：可配置禁止使用的危险函数
- **内存限制**：限制脚本大小和返回值大小
- **执行时间限制**：防止脚本长时间运行

### 13.2 访问控制

- **认证机制**：支持密码认证
- **命令限制**：可配置禁用危险命令
- **网络限制**：可配置绑定地址和端口

### 13.3 输入验证

- **参数验证**：验证命令参数的数量和类型
- **脚本验证**：验证脚本大小和内容
- **键名验证**：验证键名的长度和格式

## 14. 扩展性

### 14.1 命令扩展

- **CommandHandler 接口**：通过实现该接口添加新命令
- **命令注册**：在 `DefaultCommandHandler` 中注册新命令

### 14.2 存储扩展

- **MemoryStore 接口**：可实现自定义存储后端
- **数据结构扩展**：支持添加新的数据类型

### 14.3 插件系统

- **Spring Boot 集成**：通过 starter 模块集成到 Spring Boot 应用
- **自定义配置**：支持通过配置文件扩展功能

## 15. 性能优化

### 15.1 内存优化

- **紧凑数据结构**：使用高效的数据结构存储数据
- **内存回收**：及时回收过期键占用的内存
- **内存限制**：支持设置最大内存使用限制

### 15.2 网络优化

- **Netty 优化**：配置 Netty 线程池和缓冲区大小
- **连接复用**：支持连接池，减少连接建立开销
- **批量操作**：支持 pipeline 批量执行命令

### 15.3 执行优化

- **直接修改**：集合操作的直接修改，避免数据复制
- **脚本缓存**：缓存 Lua 脚本，提高执行效率
- **并发控制**：细粒度的并发控制，减少锁竞争

## 16. 高可用

### 16.1 主从复制

（计划支持）通过主从复制提高系统可用性：

- **主节点**：处理写操作，复制数据到从节点
- **从节点**：处理读操作，从主节点复制数据
- **故障转移**：主节点故障时，从节点提升为主节点

### 16.2 哨兵模式

（计划支持）通过哨兵实现自动故障转移：

- **哨兵节点**：监控主从节点的健康状态
- **故障检测**：检测主节点是否故障
- **故障转移**：当主节点故障时，自动选择从节点提升为主节点

## 17. 部署架构

### 17.1 单机部署

- **独立服务**：作为独立的 Redis 兼容服务运行
- **嵌入式部署**：通过 `EmbeddedRedisServer` 嵌入到应用中

### 17.2 集群部署

（计划支持）通过集群模式提高系统容量和可用性：

- **数据分片**：将数据分散到多个节点
- **负载均衡**：请求自动分发到不同节点
- **故障容错**：部分节点故障不影响整个系统

## 18. 总结

Luban-RDS 采用分层架构设计，具有以下特点：

- **模块化**：清晰的模块划分，便于维护和扩展
- **高性能**：基于 Netty 的 NIO 服务器，支持高并发
- **兼容性**：完全兼容 Redis 协议，可直接使用 Redis 客户端
- **可靠性**：支持持久化、备份和恢复机制
- **安全性**：Lua 脚本沙箱，超时控制，操作计数
- **扩展性**：支持命令扩展、存储扩展和插件系统

这种架构设计使得 Luban-RDS 既适合作为独立的 Redis 兼容服务，也适合嵌入到应用中使用，为用户提供了灵活的部署选择。

## 19. 下一步

- **[功能架构](./features.md)**：深入了解各功能模块的详细设计
- **[设计决策](./design.md)**：了解重要设计选择的理由和权衡
- **[部署指南](../deployment/)**：学习如何部署和配置 Luban-RDS
