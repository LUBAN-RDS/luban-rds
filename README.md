# Luban-RDS

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-blue.svg)](https://maven.apache.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.11-green.svg)](https://spring.io/projects/spring-boot)
[![Netty](https://img.shields.io/badge/Netty-4.2.10.Final-orange.svg)](https://netty.io/)
[![Redis Compatible](https://img.shields.io/badge/Redis-Protocol%20Compatible-red.svg)](https://redis.io/)
[![GitHub Stars](https://img.shields.io/github/stars/LUBAN-RDS/luban-rds?style=social)](https://github.com/LUBAN-RDS/luban-rds)
[![GitHub Forks](https://img.shields.io/github/forks/LUBAN-RDS/luban-rds?style=social)](https://github.com/LUBAN-RDS/luban-rds)

## 📖 项目简介

Luban-RDS 是一款完全兼容 Redis 协议的轻量级高性能内存数据库，采用 Java 语言开发，基于 Maven 构建。支持作为独立服务运行，也可无缝嵌入到 Spring Boot 应用程序中使用。

## ✨ 主要特性

- **完全兼容 Redis 协议**：支持标准 Redis 客户端（redis-cli、Jedis、Lettuce、Redisson）连接
- **轻量级设计**：核心依赖精简，易于集成和部署
- **丰富的数据结构**：完整支持 String、Hash、List、Set、ZSet、Stream 六大核心数据类型
- **键过期机制**：采用惰性删除与定期清理相结合的高效过期策略
- **内存淘汰策略**：支持 LRU、Random、TTL 等多种淘汰算法，灵活应对不同场景
- **持久化支持**：提供 RDB 异步快照和 AOF 追加日志两种持久化方案，完整保留 ZSet 分数精度
- **安全认证**：支持 AUTH 命令进行密码验证，保障数据安全
- **多数据库支持**：支持 SELECT 命令切换数据库，默认提供 16 个独立数据库
- **高性能网络**：基于 Netty NIO 框架构建，支持高并发连接和高效数据传输
- **多线程 I/O 模型**：三层线程架构（Boss → Worker → Business），线程数可配置，显著提升吞吐量
- **内存池优化**：集成 Netty PooledByteBufAllocator，有效减少 GC 压力
- **内存碎片整理**：支持自动和手动碎片整理，优化长期运行的内存稳定性
- **Spring Boot 集成**：提供开箱即用的自动配置和 RedisTemplate 支持
- **线程安全**：基于 ConcurrentHashMap 和 Caffeine 实现内存存储，分段锁机制保证并发安全
- **性能优化**：协议解析优化、响应缓存、数据结构直接操作、原子性批量操作
- **易于扩展**：模块化架构设计，支持自定义命令和数据结构扩展
- **发布/订阅**：完整支持 SUBSCRIBE、UNSUBSCRIBE、PUBLISH、PSUBSCRIBE、PUNSUBSCRIBE 命令
- **Lua 脚本支持**：支持 EVAL、EVALSHA、SCRIPT 命令族，完全兼容 Redis Lua 脚本规范，新增 struct 库支持 Lc0/Ic0/ic0 格式
- **事务支持**：完整支持 MULTI、EXEC、DISCARD、WATCH、UNWATCH 事务命令
- **实时监控**：支持 MONITOR 命令，采用 MPSC 无锁环形缓冲区实现高性能命令监控（开销 < 40ns）
- **慢查询日志**：支持 SLOWLOG 命令记录和分析慢查询
- **内存分析**：支持 MEMORY 命令族进行内存诊断和优化
- **分布式追踪**：内置 TraceId 全链路追踪能力，自动注入日志，多线程环境下自动传递
- **Stream 数据类型**：完整支持 Stream 相关命令（XADD、XLEN、XRANGE、XREVRANGE、XREAD、XGROUP、XREADGROUP 等）
- **Redis Cluster 集群**：完整实现 Redis Cluster 协议，支持 16384 槽位分配、MOVED/ASK 重定向、Gossip 协议、故障检测
- **集群一键搭建**：内置 `redis-cli --cluster create` 兼容的 CLI 工具 `RedisCliMain`，一行命令完成多节点集群创建与主从划分
- **主从复制**：完整支持主从复制功能，包括全量同步和增量同步
- **健壮的网络层**：NETTY 客户端与服务端协议解析器均修复了 TCP 半包/粘包问题，能够正确处理跨段 RESP 响应与多响应合包

## 🚀 快速开始

### 环境要求

- **JDK**：17 或更高版本
- **Maven**：3.6 或更高版本
- **Git**：任意版本

### 安装步骤

```bash
# 克隆代码
git clone <repository-url>
cd luban-rds

# 构建项目
mvn clean install
```

### 基本使用

#### 启动服务器

**方式一：使用 Maven 插件**

```bash
mvn -pl luban-rds-server exec:java -Dexec.mainClass="com.janeluo.luban.rds.server.NettyRedisServer"
```

**方式二：使用可执行 JAR 包**

```bash
# 构建 JAR 包
mvn clean package -DskipTests

# 运行服务器
java -jar luban-rds-bin/target/luban-rds-jar-with-dependencies.jar
```

#### 连接服务器

使用标准 Redis 客户端连接服务器：

```bash
# 使用 redis-cli 连接
redis-cli -h localhost -p 9736

# 测试连接
127.0.0.1:9736> PING
PONG

# 字符串操作示例
127.0.0.1:9736> SET test "Hello Luban-RDS"
OK
127.0.0.1:9736> GET test
"Hello Luban-RDS"
```

### Spring Boot 集成

#### 添加依赖

在项目的 `pom.xml` 中添加以下依赖：

```xml
<dependency>
    <groupId>com.janeluo.luban</groupId>
    <artifactId>luban-rds-spring-boot-starter</artifactId>
    <version>1.0.3</version>
</dependency>
```

#### 配置参数

在 `application.properties` 或 `application.yml` 中配置服务器参数：

```properties
# 服务器基础配置
luban.rds.server.enabled=true
luban.rds.server.host=localhost
luban.rds.server.port=9736

# 持久化配置
luban.rds.server.persist-mode=rdb
luban.rds.server.data-dir=./data
luban.rds.server.rdb-save-interval=60

# 内存配置
luban.rds.server.maxmemory=0
luban.rds.server.maxmemory-policy=noeviction

# 认证配置（可选）
luban.rds.server.requirepass=

# 数据库数量
luban.rds.server.databases=16
```

#### 使用示例

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.janeluo.luban.rds.spring.boot.template.RedisTemplate;

@RestController
@RequestMapping("/redis")
public class RedisController {

    @Autowired
    private RedisTemplate redisTemplate;

    @PostMapping("/set")
    public String set(@RequestParam String key, @RequestParam String value) {
        redisTemplate.set(key, value);
        return "OK";
    }

    @GetMapping("/get")
    public String get(@RequestParam String key) {
        return redisTemplate.get(key);
    }
}
```

## 🛠️ 技术栈

| 技术组件 | 版本 | 说明 |
|---------|------|------|
| Java | 17+ | 核心开发语言 |
| Maven | 3.6+ | 项目构建管理 |
| Netty | 4.2.10.Final | 高性能网络通信框架 |
| Spring Boot | 3.4.11 | 自动配置和集成支持 |
| Caffeine | 3.2.3 | 高性能缓存库 |
| Guava | 33.5.0-jre | 工具类库 |
| SLF4J | 1.7.36 | 日志框架 |
| LuaJ | 3.0.1 | Lua 脚本引擎 |
| Kryo | 5.6.0 | 高性能序列化框架 |

## 📁 项目结构

```
luban-rds/
├── luban-rds-common/              # 通用模块
│   └── src/main/java/.../common/
│       ├── config/                # 配置类
│       ├── constant/              # 常量定义
│       ├── context/               # 上下文管理（含分布式追踪）
│       ├── exception/             # 异常类
│       └── util/                  # 工具类
├── luban-rds-core/                # 核心模块
│   └── src/main/java/.../core/
│       ├── store/                 # 内存存储实现
│       ├── handler/               # 命令处理器
│       └── slowlog/               # 慢查询日志
├── luban-rds-protocol/            # 协议模块
│   └── src/main/java/.../protocol/
│       ├── Command.java           # 命令对象
│       └── RedisProtocolParser.java  # RESP 协议解析器
├── luban-rds-persistence/         # 持久化模块
│   └── src/main/java/.../persistence/
│       ├── PersistService.java    # 持久化服务接口
│       └── impl/                  # RDB/AOF 实现
├── luban-rds-server/              # 服务器模块
│   └── src/main/java/.../server/
│       ├── NettyRedisServer.java  # Netty 服务器实现
│       ├── RedisServerHandler.java  # 连接处理器
│       ├── PubSubManager.java     # 发布订阅管理
│       └── MonitorManager.java    # 监控管理
├── luban-rds-spring-boot-starter/ # Spring Boot 启动器
│   └── src/main/java/.../spring/boot/
│       ├── autoconfigure/         # 自动配置
│       └── template/              # RedisTemplate
├── luban-rds-client/              # 客户端模块
│   └── src/main/java/.../client/
│       ├── RedisClient.java       # 客户端接口
│       └── NettyRedisClient.java  # Netty 客户端实现
├── luban-rds-bin/                 # 可执行程序模块
│   └── scripts/                   # 启动脚本
├── luban-rds-benchmark/           # 性能测试模块
├── luban-rds-cluster/             # 集群模块
│   └── src/main/java/.../cluster/
│       ├── node/                  # 节点数据模型
│       ├── slot/                  # 槽位管理
│       ├── config/                # 集群配置
│       ├── gossip/                # Gossip 协议
│       ├── bus/                   # 集群总线
│       └── handler/               # CLUSTER 命令处理器
├── luban-rds-replication/         # 主从复制模块
│   └── src/main/java/.../replication/
│       ├── MasterReplicationManager.java  # 主节点复制管理器
│       ├── SlaveReplicationService.java   # 从节点复制服务
│       └── ReplicationBacklog.java        # 复制积压缓冲区
├── luban-rds-sentinel/            # 哨兵模块
│   └── src/main/java/.../sentinel/
│       ├── SentinelManager.java   # 哨兵管理器
│       └── handler/               # 哨兵命令处理器
├── docker/                        # Docker 部署配置
│   ├── entrypoint.sh              # 容器入口脚本
│   ├── healthcheck.sh             # 健康检查脚本
│   ├── kubernetes.yaml            # Kubernetes 部署清单
│   └── luban-rds.conf             # Docker 默认配置
├── docs/                          # 文档目录（VitePress）
│   ├── architecture/              # 架构文档
│   ├── guide/                     # 使用指南
│   ├── api/                       # API 文档
│   ├── deployment/                # 部署文档
│   └── development/               # 开发文档
└── pom.xml                        # 父项目 POM 文件
```

## 📚 命令参考

### 字符串命令
`SET` `SETNX` `GET` `GETSET` `MSET` `MGET` `INCR` `DECR` `INCRBY` `DECRBY` `APPEND` `STRLEN` `SETRANGE` `GETRANGE` `PSETEX`

### 哈希命令
`HSET` `HSETNX` `HMSET` `HGET` `HMGET` `HGETALL` `HDEL` `HEXISTS` `HKEYS` `HVALS` `HLEN` `HINCRBY` `HSCAN`

### 列表命令
`LPUSH` `RPUSH` `LPOP` `RPOP` `LLEN` `LRANGE` `LREM` `LINDEX` `LSET` `LTRIM` `BLPOP` `BRPOP`

### 集合命令
`SADD` `SREM` `SMEMBERS` `SISMEMBER` `SCARD` `SPOP` `SRANDMEMBER` `SMOVE` `SINTER` `SUNION` `SDIFF`

### 有序集合命令
`ZADD` `ZREM` `ZRANGE` `ZREVRANGE` `ZRANGEBYSCORE` `ZSCORE` `ZCARD` `ZRANK` `ZREVRANK` `ZCOUNT` `ZINCRBY`

### Stream 命令
`XADD` `XLEN` `XRANGE` `XREVRANGE` `XDEL` `XTRIM` `XREAD` `XINFO` `XGROUP` `XREADGROUP` `XACK` `XPENDING` `XCLAIM` `XAUTOCLAIM`

### 监控命令
`MONITOR [DB dbid] [MATCH pattern]` - 实时命令监控，支持数据库和模式过滤

### 认证命令
`AUTH`

### 客户端命令
`CLIENT KILL` `CLIENT LIST` `CLIENT GETNAME` `CLIENT PAUSE` `CLIENT SETNAME`

### 配置命令
`CONFIG GET` `CONFIG SET`

### 发布/订阅命令
`SUBSCRIBE` `UNSUBSCRIBE` `PUBLISH` `PSUBSCRIBE` `PUNSUBSCRIBE` `SSUBSCRIBE` `SUNSUBSCRIBE`

### Lua 脚本命令
`EVAL` `EVALSHA` `SCRIPT LOAD` `SCRIPT EXISTS` `SCRIPT FLUSH` `SCRIPT KILL`

### 事务命令
`MULTI` `EXEC` `DISCARD` `WATCH` `UNWATCH`

> **事务行为说明**：
> - 使用 WATCH 监视的键在 EXEC 前如果发生变更，EXEC 返回 Null Array（RESP: `*-1\r\n`），事务不执行
> - 事务入队阶段若存在参数错误，EXEC 返回 EXECABORT 并丢弃整个事务

### 通用命令
`PING` `ECHO` `DEL` `EXISTS` `EXPIRE` `PEXPIRE` `TTL` `PTTL` `PERSIST` `TYPE` `FLUSHALL` `FLUSHDB` `DBSIZE` `SCAN` `SELECT` `INFO` `TIME` `LASTSAVE` `BGREWRITEAOF` `BGSAVE` `KEYS` `QUIT`

### 慢查询日志命令
`SLOWLOG GET [count]` `SLOWLOG LEN` `SLOWLOG RESET`

### 内存管理命令
`MEMORY USAGE` `MEMORY STATS` `MEMORY PURGE` `MEMORY MALLOC-STATS` `MEMORY DOCTOR` `MEMORY HELP`

### 集群命令
`CLUSTER INFO` `CLUSTER NODES` `CLUSTER MEET` `CLUSTER FORGET` `CLUSTER ADDSLOTS` `CLUSTER DELSLOTS` `CLUSTER SETSLOT` `CLUSTER KEYSLOT` `CLUSTER COUNTKEYSINSLOT` `CLUSTER GETKEYSINSLOT` `CLUSTER REPLICATE` `CLUSTER FAILOVER` `CLUSTER RESET` `CLUSTER SAVECONFIG` `CLUSTER SLAVES` `CLUSTER REPLICAS` `CLUSTER MYID` `CLUSTER SLOTS` `CLUSTER COUNTFAILUREREPORTS` `ASKING` `READONLY` `READWRITE`

#### 集群一键搭建 CLI（v1.0.3+）

Luban-RDS 自带 `RedisCliMain`，对齐 `redis-cli --cluster create` 子集，可一键完成多节点集群创建、主从划分与槽位分配：

```bash
# 启动 6 个独立节点（端口 9736–9741，均启用 cluster-enabled yes）

# 一键创建 3 主 3 从集群
java -cp luban-rds-client.jar com.janeluo.luban.rds.client.cli.RedisCliMain \
     --cluster create \
     192.168.8.161:9736 192.168.8.161:9737 192.168.8.161:9738 \
     192.168.8.161:9739 192.168.8.161:9740 192.168.8.161:9741 \
     --cluster-replicas 1

# 仅创建 3 主 0 从
java -cp luban-rds-client.jar com.janeluo.luban.rds.client.cli.RedisCliMain \
     --cluster create \
     127.0.0.1:9736 127.0.0.1:9737 127.0.0.1:9738

# 帮助信息
java -cp luban-rds-client.jar com.janeluo.luban.rds.client.cli.RedisCliMain --help
```

也可在 Java 代码中以编程方式调用：

```java
import com.janeluo.luban.rds.client.cli.ClusterSetupCommand;
import com.janeluo.luban.rds.client.cli.NodeAddress;

List<NodeAddress> nodes = List.of(
    new NodeAddress("127.0.0.1", 9736),
    new NodeAddress("127.0.0.1", 9737),
    new NodeAddress("127.0.0.1", 9738),
    new NodeAddress("127.0.0.1", 9739),
    new NodeAddress("127.0.0.1", 9740),
    new NodeAddress("127.0.0.1", 9741)
);
// verbose=false 进入静默模式；适用于脚本/批量部署
ClusterSetupCommand.createCluster(nodes, /*replicas*/ 1, /*verbose*/ false);
```

### 复制命令
`SLAVEOF` `PSYNC` `REPLCONF` `ROLE`

#### 发布/订阅示例
```bash
# 终端 A：订阅频道
127.0.0.1:9736> SUBSCRIBE news sports
1) "subscribe"
2) "news"
3) "1"
1) "subscribe"
2) "sports"
3) "2"

# 终端 B：发布消息
127.0.0.1:9736> PUBLISH news "hello"
(integer) 1

# 终端 A：收到推送
1) "message"
2) "news"
3) "hello"

# 终端 A：退订
127.0.0.1:9736> UNSUBSCRIBE news
1) "unsubscribe"
2) "news"
3) "1"

# 模式订阅
127.0.0.1:9736> PSUBSCRIBE news:*
```

## ⚙️ 配置选项

### 基础配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `luban.rds.server.enabled` | 是否启用服务器 | `true` |
| `luban.rds.server.host` | 服务器监听地址 | `localhost` |
| `luban.rds.server.port` | 服务器监听端口 | `9736` |
| `luban.rds.server.databases` | 数据库数量 | `16` |
| `luban.rds.server.requirepass` | 密码认证（空表示不需要） | `""` |

### 持久化配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `luban.rds.server.persist-mode` | 持久化模式（rdb/aof） | `rdb` |
| `luban.rds.server.data-dir` | 数据存储目录 | `./data` |
| `luban.rds.server.rdb-save-interval` | RDB 保存间隔（秒） | `60` |

### 内存配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `luban.rds.server.maxmemory` | 最大内存限制（字节，0 表示无限制） | `0` |
| `luban.rds.server.maxmemory-policy` | 内存淘汰策略 | `noeviction` |
| `use-pool` | 是否使用内存池 | `yes` |
| `memory-frag-threshold` | 内存碎片率阈值（%） | `30` |

### 线程配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `io-threads` | I/O 线程数（Boss Group） | CPU 核心数 |
| `worker-threads` | Worker 线程数 | CPU 核心数 × 2 |
| `business-threads` | 业务线程数 | CPU 核心数 |

### Lua 脚本配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `lua-timeout` | 脚本执行超时时间（毫秒） | `10000` |
| `lua-sandbox-enabled` | 是否启用沙箱模式 | `yes` |
| `lua-max-script-bytes` | 脚本最大字节数 | `65536` |
| `lua-max-return-bytes` | 脚本最大返回字节数 | `1048576` |

### 集群配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `cluster-enabled` | 是否启用集群模式 | `false` |
| `cluster-config-file` | 集群配置文件路径 | `nodes.conf` |
| `cluster-node-timeout` | 节点超时时间（毫秒） | `15000` |
| `cluster-announce-ip` | 对外宣布的 IP | `""` |
| `cluster-announce-port` | 对外宣布的端口 | `0` |
| `cluster-announce-bus-port` | 对外宣布的总线端口 | `0` |

### 复制配置

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `replicaof` | 主节点地址（host:port） | `""` |
| `masterauth` | 主节点认证密码 | `""` |
| `repl-timeout` | 复制超时时间（秒） | `60` |
| `repl-backlog-size` | 复制积压缓冲区大小 | `1MB` |

## 💾 持久化

Luban-RDS 提供两种持久化方案，确保数据安全：

### RDB 持久化

- **原理**：异步快照机制，定期将内存数据保存到磁盘
- **优势**：文件紧凑，恢复速度快，适合备份和灾难恢复
- **配置**：
  ```properties
  luban.rds.server.persist-mode=rdb
  luban.rds.server.rdb-save-interval=60
  ```

### AOF 持久化

- **原理**：追加日志方式，记录所有写操作
- **优势**：数据安全性高，可读性好，适合数据完整性要求高的场景
- **配置**：
  ```properties
  luban.rds.server.persist-mode=aof
  ```

## 📊 监控与管理

Luban-RDS 提供丰富的监控和管理功能：

### 实时监控

```bash
# 监控所有命令
MONITOR

# 监控特定数据库
MONITOR DB 0

# 监控特定模式的命令
MONITOR MATCH SET*
```

### 慢查询日志

```bash
# 获取最近 10 条慢查询
SLOWLOG GET 10

# 查看慢查询日志数量
SLOWLOG LEN

# 清空慢查询日志
SLOWLOG RESET
```

### 内存分析

```bash
# 查看键的内存使用
MEMORY USAGE key

# 查看内存统计信息
MEMORY STATS

# 执行内存碎片整理
MEMORY PURGE
```

### 服务器信息

```bash
# 查看服务器信息
INFO

# 查看特定部分信息
INFO memory
INFO replication
INFO cluster
```

## 🔍 分布式追踪

Luban-RDS 内置完整的分布式追踪支持，每个请求自动生成唯一的 TraceId，贯穿整个请求处理链路，便于问题排查和性能分析。

### TraceId 格式

```
格式：{时间戳}-{机器标识}-{进程ID}-{序列号}
示例：1704067200000-a1b2c3d4-1234-000001
```

### 日志输出示例

```log
14:30:45.123 [nioEventLoopGroup-3-1] [traceId:1704067200000-a1b2c3d4-1234-000001] DEBUG c.j.l.r.server.RedisServerHandler - Processing command: SET
```

### API 使用

```java
// 获取当前 TraceId
String traceId = TraceContext.getTraceId();

// 设置自定义 TraceId
TraceContext.setTraceId("custom-trace-id");

// 手动生成 TraceId
String newTraceId = TraceContext.generateTraceId();
```

### 异步场景传递

```java
// 包装 Runnable（TraceId 自动传递到子线程）
executor.submit(TraceableRunnable.wrap(() -> {
    String traceId = TraceContext.getTraceId();
    // 业务逻辑
}));

// 包装 Callable
Future<String> future = executor.submit(TraceableCallable.wrap(() -> {
    return TraceContext.getTraceId();
}));

// 包装 Executor
Executor traceableExecutor = TraceableExecutor.wrap(rawExecutor);
```

## 🌐 部署指南

### 独立部署

```bash
# 1. 构建项目
mvn clean install

# 2. 运行服务器
java -jar luban-rds-bin/target/luban-rds-jar-with-dependencies.jar

# 3. 配置防火墙，开放端口
firewall-cmd --add-port=9736/tcp --permanent
firewall-cmd --reload
```

### Docker 部署

#### 使用 Docker Compose（推荐）

```bash
# 克隆项目
git clone https://github.com/LUBAN-RDS/luban-rds.git
cd luban-rds

# 配置环境变量
cp .env.example .env

# 启动服务
docker-compose up -d

# 查看日志
docker-compose logs -f

# 停止服务
docker-compose down
```

#### 使用 Docker 命令

```bash
# 构建镜像
docker build -t luban-rds:1.0.3 .

# 基础运行
docker run -d \
  --name luban-rds \
  -p 9736:9736 \
  -v luban-rds-data:/data \
  -e LUBAN_RDS_PORT=9736 \
  -e LUBAN_RDS_PERSIST_MODE=rdb \
  -e JAVA_OPTS="-Xms256m -Xmx512m" \
  luban-rds:1.0.3

# 带密码运行
docker run -d \
  --name luban-rds \
  -p 9736:9736 \
  -v luban-rds-data:/data \
  -e LUBAN_RDS_REQUIREPASS=your-secure-password \
  luban-rds:1.0.3
```

#### Docker 环境变量

| 变量名 | 描述 | 默认值 |
|--------|------|--------|
| `LUBAN_RDS_PORT` | 服务端口 | `9736` |
| `LUBAN_RDS_BIND` | 绑定地址 | `0.0.0.0` |
| `LUBAN_RDS_DATA_DIR` | 数据目录 | `/data` |
| `LUBAN_RDS_PERSIST_MODE` | 持久化模式 | `rdb` |
| `LUBAN_RDS_MAXMEMORY` | 最大内存 | `0`（无限制） |
| `LUBAN_RDS_DATABASES` | 数据库数量 | `16` |
| `LUBAN_RDS_REQUIREPASS` | 访问密码 | 空 |
| `JAVA_OPTS` | JVM 参数 | `-Xms256m -Xmx512m` |

### Kubernetes 部署

```bash
# 应用 Kubernetes 配置
kubectl apply -f docker/kubernetes.yaml

# 查看部署状态
kubectl get pods -n luban-rds

# 查看服务
kubectl get svc -n luban-rds

# 端口转发测试
kubectl port-forward svc/luban-rds 9736:9736 -n luban-rds
```

## 🛠️ 开发与贡献

### 环境要求

- **JDK**：17 或更高版本
- **Maven**：3.6 或更高版本
- **IDE**：推荐 IntelliJ IDEA 或 Eclipse

### 构建与测试

```bash
# 完整构建（包含测试）
mvn clean install

# 跳过测试构建
mvn clean install -DskipTests

# 运行单元测试
mvn test

# 运行指定测试类
mvn test -Dtest=ClassName

# 生成测试覆盖率报告
mvn jacoco:report
```

### 代码规范

- 遵循 Java 标准编码规范
- 使用 4 空格缩进，禁止使用 Tab
- 为公共类和方法添加 Javadoc 注释
- 单行代码长度不超过 120 字符

### 提交规范

- 使用清晰的提交信息
- 遵循 [约定式提交](https://www.conventionalcommits.org/zh-hans/) 规范
- 推荐使用功能分支开发

## ❓ 常见问题

### 1. 端口被占用，启动失败

**问题现象**：服务器启动时报错 `Address already in use`

**解决方案**：

```bash
# Linux/Mac 检查端口占用
lsof -i :9736

# Windows 检查端口占用
netstat -ano | findstr 9736

# 使用其他端口启动
java -jar luban-rds.jar --server.port=9737
```

### 2. 客户端无法连接服务器

**问题现象**：客户端连接超时或拒绝连接

**解决方案**：

```bash
# 检查服务器是否运行
ps aux | grep luban-rds

# 检查防火墙规则
firewall-cmd --list-ports

# 检查网络连通性
telnet localhost 9736
```

### 3. 内存使用过高

**问题现象**：服务器占用内存持续增长

**解决方案**：

- 清理不需要的数据：`FLUSHDB` 或 `FLUSHALL`
- 为临时数据设置合理的过期时间：`EXPIRE key seconds`
- 调整内存淘汰策略：`maxmemory-policy allkeys-lru`
- 增加 JVM 内存限制：`-Xmx2g`

### 4. 服务器重启后数据丢失

**问题现象**：重启后数据不存在

**解决方案**：

```properties
# 启用 RDB 持久化
luban.rds.server.persist-mode=rdb
luban.rds.server.rdb-save-interval=60

# 确保数据目录可写
luban.rds.server.data-dir=./data
```

### 5. 集群节点无法通信

**问题现象**：集群节点间心跳失败

**解决方案**：

- 检查集群总线端口（默认为服务端口 + 10000）
- 确保防火墙开放集群端口
- 验证 `cluster-announce-ip` 配置正确

## 📍 版本规划

### v1.0.0 已发布功能

| 功能模块 | 详细说明 |
|---------|---------|
| **持久化** | RDB 异步快照、AOF 追加日志 |
| **内存管理** | LRU/Random/TTL 淘汰策略、自动碎片整理 |
| **数据类型** | String、Hash、List、Set、ZSet、Stream |
| **安全认证** | AUTH 密码验证 |
| **Lua 脚本** | EVAL、EVALSHA、SCRIPT 命令族，沙箱模式 |
| **事务** | MULTI/EXEC/DISCARD/WATCH/UNWATCH |
| **发布订阅** | SUBSCRIBE/PUBLISH/PSUBSCRIBE，模式订阅 |
| **监控** | MONITOR（MPSC 无锁环形缓冲区）、SLOWLOG |
| **内存分析** | MEMORY 命令族 |
| **客户端管理** | CLIENT 命令族 |
| **Pipeline** | 管道化请求支持 |
| **性能优化** | 三层线程模型、内存池、响应缓存 |
| **分布式追踪** | TraceId 全链路追踪 |
| **阻塞命令** | BLPOP/BRPOP 多键等待、超时设置 |
| **Docker 支持** | Dockerfile、Docker Compose、Kubernetes |

### v1.0.1（已发布）

| 功能模块 | 详细说明 |
|---------|---------|
| **Redis Cluster** | 16384 槽位分配、MOVED/ASK 重定向、Gossip 协议、故障检测、槽位迁移、Hash Tag 支持 |
| **主从复制** | 全量同步（RDB 传输）、增量同步（复制积压缓冲区）、复制状态管理、从节点只读模式 |
| **哨兵模式** | 哨兵模式核心功能实现 |

### v1.0.2（已发布）

| 模块 | 详细说明 |
|---------|---------|
| **CLUSTER SLOTS** | 完整实现 `CLUSTER SLOTS` 命令，并优化节点列表的过滤逻辑 |
| **集群 Gossip & 拓扑** | 修复 `redis-cli --cluster create` 卡在 `Waiting for the cluster to join` 的三处叠加根因（Gossip 发现后建连、`GossipTask` 不再跳过 HANDSHAKE、Gossip 携带槽位所有权） |
| **握手协议** | 修复 `CLUSTER MEET` 命令装配缺陷与临时 ID 解析机制 |
| **兼容性** | `CLUSTER NODES` 改用裸 `\n` 行尾，Redisson 解析正常；`cluster_enabled` 字段补全；非集群模式下跳过 CLUSTER 拦截 |

### v1.0.3（已发布）

| 模块 | 详细说明 |
|---------|---------|
| **集群一键搭建 CLI** | 新增 `RedisCliMain`（`--cluster create ... --cluster-replicas N`），对齐 `redis-cli --cluster create`，支持程序化嵌入与静默模式 |
| **TCP 半包/粘包** | `RedisProtocolParser` 修复半包回退、CRLF 检测与解析死循环；`NettyRedisClient` 引入累积缓冲与循环解析，正确处理跨段 RESP 与多响应合包 |

### 开发中

- [ ] 访问控制列表（ACL）
- [ ] 传输加密（TLS/SSL）

### 计划中

- [ ] 高级数据类型（Geo、Bitmap、HyperLogLog）
- [ ] Kubernetes Operator
- [ ] Prometheus Exporter

## 📚 文档资源

- **在线文档**：[https://luban-rds.github.io/luban-rds/](https://luban-rds.github.io/luban-rds/)
- **系统架构**：[docs/architecture/system.md](docs/architecture/system.md)
- **功能架构**：[docs/architecture/features.md](docs/architecture/features.md)
- **快速开始**：[docs/guide/quickstart.md](docs/guide/quickstart.md)
- **命令列表**：[docs/api/commands.md](docs/api/commands.md)
- **部署指南**：[docs/deployment/installation.md](docs/deployment/installation.md)

## 📄 许可证

本项目采用 [Apache License 2.0](LICENSE) 开源许可证。

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request 参与项目贡献！

- **提交 Issue**：[GitHub Issues](https://github.com/LUBAN-RDS/luban-rds/issues)
- **贡献代码**：请参阅 [贡献指南](docs/development/contributing.md)
- **代码规范**：请遵循 [代码风格指南](docs/development/code-style.md)

## 📞 联系方式

- **项目地址**：[https://github.com/LUBAN-RDS/luban-rds](https://github.com/LUBAN-RDS/luban-rds)
- **问题反馈**：[GitHub Issues](https://github.com/LUBAN-RDS/luban-rds/issues)
- **在线文档**：[https://luban-rds.github.io/luban-rds/](https://luban-rds.github.io/luban-rds/)
