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
- **集群配置持久化与节点恢复（v1.0.4+）**：`nodes.conf` 自动持久化、节点 ID 复用、槽位表重建、启动主动建连，避免全集群重启后节点成孤岛
- **集群审计加固（v1.0.11 ~ v1.0.13）**：连续 6 批 P0/P1 修复覆盖 failover FAIL 保护期、replOffset 回填、写冻结自动恢复、MIGRATE 复制传播、CLUSTER 子命令/协议面/错误串英文/消息码 0x40+ 等
- **主从复制**：完整支持主从复制功能，包括全量同步和增量同步
- **3 节点 Raft 强一致集群（mesh 模块，v1.0.15+）**：用 3 台机器替代 Redis Cluster 的 6 节点实现强一致高可用（多数派 ACK + 落盘），已确认写入不丢；`CLUSTER SLOTS` + `MOVED` 兼容 JedisCluster / lettuce / Redisson 集群感知客户端，13 阶段全闭环 + 291 测试全过（详见 [luban-rds-mesh/README.md](luban-rds-mesh/README.md) 与 [docs/mesh/](docs/mesh/index.md)）。v1.0.16+ 增加 WAL 增量落盘（写路径 O(log)→O(1)）
- **健壮的网络层**：NETTY 客户端与服务端协议解析器均修复了 TCP 半包/粘包问题，能够正确处理跨段 RESP 响应与多响应合包
- **内置性能基准测试（v1.0.15+）**：`luban-rds-benchmark` 提供单节点、Cluster、Mesh 三类基准套件（`LubanBenchmarkMain` / `ClusterBenchmarkSuite` / `MeshBenchmarkSuite`），支持与 Redis 7.x 对比，并输出 HTML/Markdown 报告（详见 [docs/guide/benchmarking.md](docs/guide/benchmarking.md)）
- **堆外/混合内存存储引擎（v1.0.17）**：`memory-store-kind=default|hybrid` 配置切换；hybrid 模式 = `OffHeapStringEngine`（堆外 ByteBuffer）`+ OnHeapStructEngine`（堆上去 Caffeine），按 key 类型自动路由；String 走堆外大幅降低 GC 压力，Hash/List/ZSet/Stream 仍走堆上（Caffeine 缓存序列化结构体）；新增 `memory-store-kind` 配置项 + `luban.rds.server.*` Spring Boot 配置前缀，hybrid 模式 `CONFIG SET` 实时生效已验证

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
    <version>1.0.17</version>
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
├── luban-rds-mesh/                # 3 节点 Raft 强一致集群模块（强一致、不丢已确认写入）
│   ├── README.md                                # 模块快速上手（配置/启动/客户端/运维命令）
│   ├── src/main/java/.../mesh/
│   │   ├── MeshNode.java MeshConfig.java        # 节点 + 配置
│   │   ├── bus/                                 # MeshBus 传输层（Codec/Client/Server/Frame）
│   │   ├── core/                                # LogEntry / RaftStateMachine / MeshRole / MeshState
│   │   ├── election/                            # ElectionTimer / LeaseManager / VoteCollector
│   │   ├── gateway/                             # MeshWriteGate（读写分流 / MOVED）
│   │   ├── lifecycle/                           # MeshBootstrap / MeshStartupLoader / MeshConfigPersister
│   │   ├── replication/                         # LogApplier / LogReplicator / SnapshotManager（chunked snapshot）
│   │   ├── rpc/                                 # 5 类 RPC：AppendEntries / RequestVote / InstallSnapshot ...
│   │   └── client/                              # MeshClientRedirector / MeshClusterCommands
│   └── docs/
│       ├── DESIGN.md                            # 完整协议设计文档 v1.2
│       └── IMPLEMENTATION_PLAN.md               # 13 阶段实施计划 v1.2
├── luban-rds-benchmark/           # 性能测试模块（单节点 / Cluster / Mesh / Redis 对比）
│   └── src/main/java/.../benchmark/
│       ├── LubanBenchmarkMain.java              # CLI 入口（commons-cli）
│       ├── api/                                 # Benchmark / BenchmarkConfig / BenchmarkResult
│       ├── cases/                               # 单节点 12 类基准（Get/Set/Incr/ListPush/Hash/...）
│       ├── cluster/                             # ClusterBenchmarkSuite + ClusterVsSingle* / ClusterScale / RedirectOverhead
│       ├── mesh/                                # MeshBenchmarkSuite + MeshScale / MeshFailover / RedisVsMesh
│       └── report/                              # ReportGenerator + HtmlReportBuilder + MarkdownReportBuilder
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

### Mesh 集群命令（3 节点 Raft 强一致，v1.0.15+）

mesh 模式复用 `CLUSTER INFO` / `CLUSTER NODES` / `CLUSTER SLOTS` 三条命令（语义与 Redis Cluster 不同——全量数据单主视图），外加错误协议：

| 命令 / 响应 | 说明 |
|------|------|
| `CLUSTER INFO` | `cluster_state:ok`（有 Leader 时）/ `fail`（选举中），`cluster_known_nodes:3` |
| `CLUSTER NODES` | 3 行；`myself,master` 为当前 Leader（linkState 恒 `connected`），其余 2 行为 `slave` |
| `CLUSTER SLOTS` | `[[0, 16383, ["<leader>", <port>, "<leader-id>"]]]`（含 `*0\r\n` replicas 数组，兼容严格解析器） |
| `-MOVED <slot> <leaderAddr>` | 写打到 Follower 时携带**真实 key 的 CRC16 slot**；集群感知客户端自动跟随 |
| `-MESHDOWN The mesh cluster has no leader` | 选举中无 Leader；客户端退避重试 |

Mesh 模式与 Cluster 模式**互斥**（`mesh-enabled` 与 `cluster-enabled` 启动时校验）。完整协议见 [luban-rds-mesh/README.md](luban-rds-mesh/README.md) 与 [docs/mesh/](docs/mesh/index.md)。

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

### Mesh 配置（与 `cluster-enabled` 互斥）

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `mesh-enabled` | 是否启用 mesh 模式 | `false` |
| `mesh-peers` | peers 列表（`nodeId@host:busPort` 逗号分隔，含自身） | `""` |
| `mesh-self-node-id` | 本节点 nodeId（未配取 peers 首个） | `""` |
| `mesh-bus-port` | mesh 总线端口（0 = 按 peers 取） | `0` |
| `mesh-service-port` | mesh service 端口（0 = 用全局 port；单机多实例必配） | `0` |
| `mesh-election-timeout-min-ms` | 选举超时下限（随机化区间） | `150` |
| `mesh-election-timeout-max-ms` | 选举超时上限 | `300` |
| `mesh-heartbeat-interval-ms` | Leader 心跳周期 | `100` |
| `mesh-lease-duration-ms` | 读租约时长（≈ 2 × electionTimeout） | `600` |
| `mesh-read-consistency` | 读模式（`LEASE` / `READ_INDEX`） | `LEASE` |
| `mesh-read-lease-wait-ms` | 租约失效时等待续租的上限 | `1000` |
| `mesh-snapshot-log-threshold` | 每 N 条日志触发周期快照 | `100000` |

CLI 等价：`--mesh-enabled`、`--mesh-peers`、`--mesh-self-node-id`、`--mesh-bus-port` 等（`java -jar luban-rds-bin.jar --help` 查看全集）。

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
docker build -t luban-rds:1.0.17 .

# 基础运行
docker run -d \
  --name luban-rds \
  -p 9736:9736 \
  -v luban-rds-data:/data \
  -e LUBAN_RDS_PORT=9736 \
  -e LUBAN_RDS_PERSIST_MODE=rdb \
  -e JAVA_OPTS="-Xms256m -Xmx512m" \
  luban-rds:1.0.17

# 带密码运行
docker run -d \
  --name luban-rds \
  -p 9736:9736 \
  -v luban-rds-data:/data \
  -e LUBAN_RDS_REQUIREPASS=your-secure-password \
  luban-rds:1.0.17
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

### v1.0.4（已发布）

| 模块 | 详细说明 |
|---------|---------|
| **集群配置持久化** | `ClusterConfigPersister` 在拓扑变更（MEET/FORGET/ADDSLOTS 等）时自动同步 `nodes.conf`；引入 dirty flag 机制与类 Redis 7 `clusterSaveConfigIfNeeded` 周期任务，避免频繁 I/O |
| **节点状态恢复** | 节点启动时从 `nodes.conf` 加载节点列表、槽位分配与 config epoch，复用已有节点 ID；从恢复的 `ClusterConfig` 重建 `SlotManager` 槽位表 |
| **主动建连** | 启动时主动 `MEET` 已知节点，避免全集群重启后节点成孤岛无法恢复 |
| **版本兼容** | 解析 `nodes.conf` 时忽略 `fail` 标志，v1.0.0 ~ v1.0.3 已生成的配置文件可平滑升级 |

### v1.0.11（已发布 · 2026-08-03）

| 模块 | 详细说明 |
|---------|---------|
| **集群 FAIL 保护期** | `e0289ce`：failover 期间维护 `FAIL` 状态保护窗口，避免 PFAIL 抢先清除；归档为 `fix-fail-state-cleared-prematurely` |

### v1.0.12（已发布 · 2026-08-03）

| 模块 | 详细说明 |
|---------|---------|
| **集群审计修复批 1-6（P0×4 + P1×24）** | `46fdb7d`：`MYSELF replOffset` 恒 0 致自动 failover 失效、手动 failover 写冻结、`MIGRATE` 复制分叉等 3 个 P0 修复；附 N-24/N-1/P1-4/N-25/N-7 等 P1 闭环 |
| **双 master 根因收敛** | `0a1a23a` + `777f0c8`：failover 后 `winner slots` 双写路径消除，角色切换时 `processGossipNodes` 立即对齐 slot 所有权；`8fde4f8` 补相等 epoch 行为回归 |

### v1.0.13（已发布 · 2026-08-03）

| 模块 | 详细说明 |
|---------|---------|
| **集群 R2 审计修复批 1-6（N-1 ~ N-40）** | `7f57568`：连续 6 批覆盖 MYSELF 守卫 / XREAD 键提取 / 事务路由 / destDb 透传 / zset+stream 序列化 / 位图上限 / CLUSTER 8 子命令 / 错误串英文 / vars 段+真实时间戳+迁移方括号 / 消息码 0x40+ / failover 深化（N-11 重试冷却 / N-12 votesCast 清理 / N-9 伪造防护 / N-13 降级收窄 / N-14 投票者持槽+voted_time / N-15 候选纪元裁决）/ 状态单公式 / save 竞态+fsync / 总线端口 / 帧上限 / 连接治理 / INFO·NODES 补全；cluster 全套件 536 测试全绿 |

### v1.0.14（已发布 · 2026-08-03）

| 模块 | 详细说明 |
|---------|---------|
| **Lua 脚本只读性分析器** | `a602f1f`：新增 `LuaScriptAnalyzer` 脚本级只读判定；从节点 EVAL 不再误拒纯读脚本（Redisson 等客户端消除 `READONLY` 报错）；只改 slave 路径零回归 |

### v1.0.17（已发布 · 2026-08-06）

| 模块 | 详细说明 |
|---------|---------|
| **堆外/混合内存存储引擎（v1.0.17）** | `5c513e0` `feat(config): 添加堆外内存存储引擎配置支持`。`memory-store-kind=default\|hybrid` 二选一；hybrid 模式新增 `HybridMemoryStore` 路由 + `OffHeapStringEngine`（堆外 ByteBuffer 存 String）+ `OnHeapStructEngine`（堆上去 Caffeine 缓存序列化结构体）；S1 阶段扩展接口解耦全部强转；String 走堆外大幅降 GC，Hash/List/ZSet/Stream 二期仍堆上；26 测试全绿；hybrid 模式 `CONFIG SET` 实时生效 + Redisson 集群模式冒烟 12/12 全绿闭环无泄漏；`mesh-persist` 配置项开关化；Caffeine 缓存依赖已移除；详见 [docs/architecture/features.md](./docs/architecture/features.md) 与 `hybrid-memory-store-offheap` 归档 |
| **mesh Redisson 真实负载冒烟测试（v1.0.17）** | `e814f37` `test(cluster): 添加 hybrid 模式 Redisson 真实负载冒烟测试`：hybird mesh 模式下 Redisson 集群感知客户端 12 场景冒烟全绿；堆外增减对称、Rebalance 闭环、读写路由一致性验证；为 hybrid 模式生产可用性提供端到端验证 |

### v1.0.16（已发布 · 2026-08-06）

| 模块 | 详细说明 |
|---------|---------|
| **mesh WAL 增量落盘（v1.0.16）** | 写路径由 O(log N) 简化为 O(1)：新 A/B 基线 140 vs 2083 ops/s（disk 路径）；每写全量序列化 Raft log + fsync 为写路径主导瓶颈（243ms@6400 条，raft-nodes.conf）；快照触发前的写吞吐上限由 raftExecutor 单线程决定；详见 `mesh-wal-incremental-persist` 归档与 [docs/mesh/setup.md](./docs/mesh/setup.md) |
| **CommonCommandHandler 去强转（v1.0.16）** | `922dd4b` `refactor: CommonCommandHandler 去强转`：将 `LruSampleSize` / `SoftLimitPercent` 等方法补到 `MemoryStore` 接口，消除实现类向下转型；hybrid 模式下 `CONFIG SET` 命令实时生效 |

### v1.0.15（已发布 · 2026-08-05）

| 模块 | 详细说明 |
|---------|---------|
| **luban-rds-mesh（3 节点 Raft 强一致集群）** | `e44e2a2` ~ `643316e`：13 阶段全闭环实现（MeshBus 传输层 → 状态机/RPC → 选举+PreVote+Lease → 日志复制 → MeshWriteGate → MOVED/MESHDOWN → Leader 读路径 Lease+read-index → CLUSTER SLOTS/NODES/INFO → MULTI 单条目+BLOCK 禁用 → chunked snapshot → 持久化/启动加载 → MeshBootstrap 装配 → 3 节点集成测试）；291 测试全过。详见 [luban-rds-mesh/README.md](luban-rds-mesh/README.md) 与 [docs/mesh/](docs/mesh/index.md) |
| **mesh 13 项 hotfix（v1.0.15 内）** | `6e7f60d` nodeId 编码补齐 40B / `99da18b` 注册总线消息消费者 / `193e153` runPreVote electionTimer.reset() / `9b4ff4a` MOVED 地址带端口 / `286abf8` + `170d35d` MOVED 自重定向死循环 / `d4dc1ad` 选举退避+MOVED 兜底 / `0dc88ef` CLUSTER SLOTS replicas 空数组 / `84eb0aa` 非 Leader MOVED 携带真实 key / `ee29460` 非 Leader propose 异常带真实 key / `2808410` + `bba857c` + `a557616` CLUSTER NODES 死节点 disconnected 标记 / `182ae27` myself 行 linkState 恒 connected / `6d2a9c8` 日志降频 |
| **luban-rds-benchmark mesh 全栈套件** | `2554202` + `59c452b`：`MeshBenchmarkSuite` + `MeshScaleBenchmark` + `MeshFailoverBenchmark` + `RedisVsMeshBenchmark`（与 Redis 7.0.12 对比基线），HTML/Markdown 报告输出。详见 [docs/guide/benchmarking.md](docs/guide/benchmarking.md) |

### 开发中

- [ ] 访问控制列表（ACL — 部分完成：`ACL WHOAMI/LIST/CAT/GETUSER/SETUSER`、命令级+Key 模式级权限校验；待补 `ACL LOAD/SAVE/LOG`）
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
- **集群部署（Redis Cluster）**：[docs/deployment/cluster-setup.md](docs/deployment/cluster-setup.md)
- **Mesh 集群（3 节点 Raft 强一致）**：[docs/mesh/index.md](docs/mesh/index.md)
- **性能基准测试**：[docs/guide/benchmarking.md](docs/guide/benchmarking.md)
- **Mesh 模块子文档**：[luban-rds-mesh/README.md](luban-rds-mesh/README.md) / [DESIGN.md](luban-rds-mesh/docs/DESIGN.md) / [IMPLEMENTATION_PLAN.md](luban-rds-mesh/docs/IMPLEMENTATION_PLAN.md)

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
