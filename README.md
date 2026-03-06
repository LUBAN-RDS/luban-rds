# Luban-RDS

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-green.svg)](https://www.oracle.com/java/)
[![Maven](https://img.shields.io/badge/Maven-3.6+-blue.svg)](https://maven.apache.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7.18-green.svg)](https://spring.io/projects/spring-boot)
[![Netty](https://img.shields.io/badge/Netty-4.2.10.Final-orange.svg)](https://netty.io/)
[![Redis Compatible](https://img.shields.io/badge/Redis-Protocol%20Compatible-red.svg)](https://redis.io/)
[![GitHub Stars](https://img.shields.io/github/stars/LUBAN-RDS/luban-rds?style=social)](https://github.com/LUBAN-RDS/luban-rds)
[![GitHub Forks](https://img.shields.io/github/forks/LUBAN-RDS/luban-rds?style=social)](https://github.com/LUBAN-RDS/luban-rds)

## 📖 项目简介

Luban-RDS 是一个完全兼容 Redis 协议的轻量级内存数据库，使用 Java 开发，基于 Maven 构建，支持嵌入到 Spring Boot 项目中使用。

## ✨ 主要特性

- **完全兼容 Redis 协议**：支持标准 Redis 客户端（redis-cli、Jedis、Lettuce）连接
- **轻量级设计**：核心依赖少，易于集成
- **丰富的数据结构**：支持 String、Hash、List、Set、ZSet
- **键过期机制**：实现惰性删除机制
- **内存淘汰策略**：支持 LRU、Random、TTL 等多种淘汰策略
- **持久化支持**：支持 RDB 异步快照和 AOF 追加日志
- **安全认证**：支持 AUTH 命令进行密码验证
- **多数据库**：支持 SELECT 命令切换数据库（默认 16 个）
- **高性能网络**：基于 Netty 的 NIO 服务器，支持高并发连接
- **Spring Boot 集成**：提供自动配置和 RedisTemplate
- **线程安全**：基于 ConcurrentHashMap 和 Caffeine 实现的内存存储
- **性能优化**：协议解析优化、响应缓存、数据结构直接操作
- **易于扩展**：模块化设计，支持命令和数据结构扩展
- **发布/订阅**：支持 SUBSCRIBE、UNSUBSCRIBE、PUBLISH、PSUBSCRIBE、PUNSUBSCRIBE
- **Lua 脚本**：支持 EVAL、EVALSHA、SCRIPT 命令族，完全兼容 Redis Lua 脚本
- **事务支持**：支持 MULTI、EXEC、DISCARD、WATCH、UNWATCH
- **实时监控**：支持 MONITOR 命令实时监控命令执行
- **慢查询日志**：支持 SLOWLOG 命令记录慢查询
- **内存分析**：支持 MEMORY 命令族进行内存诊断

## 🚀 快速开始

### 安装

```bash
# 克隆代码
git clone <repository-url>
cd luban-rds

# 构建项目
mvn clean install
```

### 基本使用

#### 运行独立服务器

```bash
# 使用 Maven 插件运行
mvn -pl luban-rds-server exec:java -Dexec.mainClass="com.janeluo.luban.rds.server.NettyRedisServer"

# 或使用 Java 命令运行
cd luban-rds-server/target
java -cp "luban-rds-server-1.0.0.jar:lib/*" com.janeluo.luban.rds.server.NettyRedisServer
```

#### 连接服务器

使用标准 Redis 客户端连接：

```bash
redis-cli -h localhost -p 9736

# 测试连接
127.0.0.1:9736> PING
PONG

# 测试字符串操作
127.0.0.1:9736> SET test "Hello Luban-RDS"
OK
127.0.0.1:9736> GET test
"Hello Luban-RDS"
```

### Spring Boot 集成

#### 添加依赖

```xml
<dependency>
    <groupId>com.janeluo.luban</groupId>
    <artifactId>luban-rds-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

#### 配置服务器

在 `application.properties` 中配置：

```properties
# Redis 服务器配置
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

#### 使用 RedisTemplate

```java
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
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

- **Java**：JDK 17+
- **Maven**：项目构建工具
- **Netty**：4.2.10.Final，高性能网络服务器
- **Spring Boot**：2.7.18，自动配置和集成
- **Caffeine**：3.2.3，高性能缓存库
- **Guava**：33.5.0-jre，工具库
- **SLF4J**：1.7.36，日志框架
- **LuaJ**：3.0.1，Java 实现的 Lua 解释器（用于 Lua 脚本支持）
- **Kryo**：5.6.0，高性能序列化框架（用于 RDB 持久化）

## 📁 项目结构

```
luban-rds/
├── luban-rds-common/          # 通用模块
│   ├── src/main/java/com/janeluo/luban/rds/common/
│   │   ├── config/             # 配置类
│   │   ├── constant/           # 常量定义（含 RedisResponseConstant 性能优化）
│   │   ├── exception/          # 异常类
│   │   └── util/               # 工具类
├── luban-rds-core/             # 核心模块
│   ├── src/main/java/com/janeluo/luban/rds/core/
│   │   ├── store/              # 内存存储实现（含 LRU 淘汰、ZSet 跳表）
│   │   ├── handler/            # 命令处理器
│   │   └── slowlog/            # 慢查询日志
├── luban-rds-protocol/         # 协议模块
│   ├── src/main/java/com/janeluo/luban/rds/protocol/
│   │   ├── Command.java        # 命令对象
│   │   └── RedisProtocolParser.java  # RESP 协议解析器（含性能优化）
├── luban-rds-persistence/      # 持久化模块
│   ├── src/main/java/com/janeluo/luban/rds/persistence/
│   │   ├── PersistService.java # 持久化服务接口
│   │   └── impl/               # RDB/AOF 实现
├── luban-rds-server/           # 服务器模块
│   ├── src/main/java/com/janeluo/luban/rds/server/
│   │   ├── RedisServer.java    # 服务器接口
│   │   ├── NettyRedisServer.java  # Netty 服务器实现
│   │   ├── EmbeddedRedisServer.java  # 嵌入式服务器实现
│   │   ├── RedisServerHandler.java  # 连接处理器
│   │   ├── PubSubManager.java  # 发布订阅管理
│   │   └── MonitorManager.java # 监控管理
├── luban-rds-spring-boot-starter/  # Spring Boot 启动器
│   ├── src/main/java/com/janeluo/luban/rds/spring/boot/
│   │   ├── autoconfigure/      # 自动配置
│   │   └── template/           # RedisTemplate
├── luban-rds-client/           # 客户端模块
│   ├── src/main/java/com/janeluo/luban/rds/client/
│   │   ├── RedisClient.java    # 客户端接口
│   │   └── NettyRedisClient.java  # Netty 客户端实现
├── luban-rds-bin/              # 可执行程序模块
│   ├── scripts/                # 启动脚本
│   └── src/main/java/          # 服务器启动入口
├── luban-rds-benchmark/        # 性能测试模块
│   └── src/main/java/          # 性能测试代码
├── docs/                       # 文档目录
│   ├── 系统架构.md              # 系统架构文档
│   ├── 功能架构.md              # 功能架构文档
│   ├── 使用手册.md              # 使用手册
│   ├── API文档.md               # API 文档
│   └── 部署指南.md              # 部署指南
└── pom.xml                     # 父项目 POM 文件
```

## 📚 命令参考

### 字符串命令
- SET, SETNX, GET, GETSET, MSET, MGET, INCR, DECR, INCRBY, DECRBY, APPEND, STRLEN, SETRANGE, GETRANGE, PSETEX

### 哈希命令
- HSET, HSETNX, HMSET, HGET, HMGET, HGETALL, HDEL, HEXISTS, HKEYS, HVALS, HLEN, HINCRBY, HSCAN

### 列表命令
- LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE, LREM, LINDEX, LSET

### 集合命令
- SADD, SREM, SMEMBERS, SISMEMBER, SCARD, SPOP, SRANDMEMBER, SMOVE, SINTER, SUNION, SDIFF

### 有序集合命令
- ZADD, ZREM, ZRANGE, ZREVRANGE, ZRANGEBYSCORE, ZSCORE, ZCARD, ZRANK, ZREVRANK, ZCOUNT, ZINCRBY

### 通用命令
- PING, ECHO, DEL, EXISTS, EXPIRE, PEXPIRE, TTL, PTTL, PERSIST, TYPE, FLUSHALL, FLUSHDB, DBSIZE, SCAN, SELECT, INFO, TIME, LASTSAVE, BGREWRITEAOF, BGSAVE, KEYS, QUIT

### 认证命令
- AUTH

### 客户端命令
- CLIENT KILL, CLIENT LIST, CLIENT GETNAME, CLIENT PAUSE, CLIENT SETNAME

### 配置命令
- CONFIG GET, CONFIG SET

### 发布/订阅命令
- SUBSCRIBE, UNSUBSCRIBE, PUBLISH, PSUBSCRIBE, PUNSUBSCRIBE, SSUBSCRIBE, SUNSUBSCRIBE

### Lua 脚本命令
- EVAL, EVALSHA, SCRIPT LOAD, SCRIPT EXISTS, SCRIPT FLUSH, SCRIPT KILL
 
### 事务命令
- MULTI, EXEC, DISCARD, WATCH, UNWATCH

#### 事务行为说明
- 使用 WATCH 监视的键在 EXEC 前如果发生变更，EXEC 返回 Null Array（RESP: `*-1\r\n`），事务不执行
- 事务入队阶段若存在参数错误，EXEC 返回 EXECABORT 并丢弃整个事务

### 监控命令
- MONITOR [DB dbid] [MATCH pattern] - 实时监控命令执行

### 慢查询日志命令
- SLOWLOG GET [count], SLOWLOG LEN, SLOWLOG RESET

### 内存管理命令
- MEMORY USAGE, MEMORY STATS, MEMORY PURGE, MEMORY MALLOC-STATS, MEMORY DOCTOR, MEMORY HELP

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

| 配置项 | 描述 | 默认值 |
|-------|------|--------|
| `luban.rds.server.enabled` | 是否启用服务器 | true |
| `luban.rds.server.host` | 服务器监听地址 | localhost |
| `luban.rds.server.port` | 服务器监听端口 | 9736 |
| `luban.rds.server.persist-mode` | 持久化模式（rdb/aof） | rdb |
| `luban.rds.server.data-dir` | 数据存储目录 | ./data |
| `luban.rds.server.rdb-save-interval` | RDB 保存间隔（秒） | 60 |
| `luban.rds.server.maxmemory` | 最大内存限制（字节，0 表示无限制） | 0 |
| `luban.rds.server.maxmemory-policy` | 内存淘汰策略 | noeviction |
| `luban.rds.server.requirepass` | 密码认证（空字符串表示不需要） | "" |
| `luban.rds.server.databases` | 数据库数量 | 16 |

## 💾 持久化

Luban-RDS 支持两种持久化方式：

### RDB 持久化
- 异步快照，定期将内存数据保存到磁盘
- 配置 `luban.rds.server.persist-mode=rdb`
- 设置保存间隔 `luban.rds.server.rdb-save-interval=60`

### AOF 持久化
- 追加日志，记录所有写操作
- 配置 `luban.rds.server.persist-mode=aof`

## 📊 监控和管理

- **MONITOR 命令**：实时监控命令执行，支持按数据库和模式过滤
- **SLOWLOG**：记录慢查询
- **INFO 命令**：查看服务器状态信息
- **MEMORY 命令**：内存使用分析和诊断

## 🌐 部署指南

### 独立部署
1. 构建项目：`mvn clean install`
2. 运行服务器：`java -cp "luban-rds-server-1.0.0.jar:lib/*" com.janeluo.luban.rds.server.NettyRedisServer`
3. 配置防火墙，允许端口 9736 访问

### Docker 部署
（计划后续支持）

## 🛠️ 开发和贡献

### 环境要求
- JDK 17+
- Maven 3.6+

### 构建和测试
```bash
# 构建项目
mvn clean install

# 运行单元测试
mvn test
```

### 代码规范
- 遵循 Java 标准编码规范
- 使用 4 空格缩进
- 为公共类和方法添加 Javadoc 注释

### 提交代码
- 使用清晰的提交信息
- 推荐使用功能分支

## ❓ 常见问题

### 1. 启动失败，端口被占用

**解决方案**：
- 检查端口是否被其他进程占用：`netstat -tlnp | grep 9736`
- 使用不同的端口启动服务器

### 2. 客户端无法连接到服务器

**解决方案**：
- 检查服务器是否正在运行
- 检查网络连接是否正常
- 检查防火墙是否阻止连接

### 3. 内存使用过高

**解决方案**：
- 清理不需要的数据
- 为临时数据设置合理的过期时间
- 增加 JVM 内存限制

### 4. 服务器重启后数据丢失

**解决方案**：
- 启用 RDB 持久化：配置 `luban.rds.server.persist-mode=rdb`
- 设置合适的保存间隔：配置 `luban.rds.server.rdb-save-interval=60`
- 确保数据目录可写：配置 `luban.rds.server.data-dir=./data`

## 📍 未来规划

- [x] 支持持久化（RDB、AOF）
- [x] 支持内存淘汰策略（LRU、Random、TTL）
- [x] 支持密码认证
- [x] 支持多数据库
- [x] 支持 Lua 脚本
- [x] 支持事务
- [x] 支持发布/订阅
- [x] 支持实时监控（MONITOR）
- [x] 支持慢查询日志（SLOWLOG）
- [x] 支持内存分析（MEMORY）
- [ ] 支持主从复制
- [ ] 支持集群模式
- [ ] 支持更多 Redis 命令
- [ ] 增强监控和管理功能
- [ ] Docker 部署支持

## 📚 文档

- [在线文档](https://luban-rds.github.io/luban-rds/)：完整的项目文档
- [系统架构](docs/系统架构.md)：详细描述系统的架构设计
- [功能架构](docs/功能架构.md)：详细描述系统的功能组件
- [使用手册](docs/使用手册.md)：详细说明如何使用系统
- [API 文档](docs/API文档.md)：详细描述系统的 API 接口
- [部署指南](docs/部署指南.md)：详细说明如何部署和运行系统

## 📄 许可证

本项目采用 Apache 2.0 许可证 - 详见 [LICENSE](LICENSE) 文件

## 🤝 贡献

欢迎提交 Issue 和 Pull Request 来改进这个项目！

## 📞 联系方式

- 项目地址：https://github.com/LUBAN-RDS/luban-rds
- 问题反馈：https://github.com/LUBAN-RDS/luban-rds/issues
- 在线文档：https://luban-rds.github.io/luban-rds/
