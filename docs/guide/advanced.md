---
title: 高级功能
---

# 高级功能

本部分介绍了 Luban-RDS 的高级功能和特性，包括持久化、Lua 脚本、发布订阅、事务、监控等。

## 1. 持久化

Luban-RDS 支持两种持久化方式：RDB 和 AOF。

### 1.1 RDB 持久化

**原理**：将内存中的数据以二进制格式保存到磁盘文件。

**配置**：

```conf
# RDB 保存策略
# 900秒内有1个修改，300秒内有10个修改，60秒内有10000个修改时保存
save 900 1 300 10 60 10000

# RDB 文件名称
dbfilename dump.rdb

# RDB 文件目录
dir ./
```

**手动触发**：

```bash
# 同步保存（阻塞服务器）
SAVE

# 后台保存（非阻塞）
BGSAVE
```

**优势**：
- 文件紧凑，适合备份
- 恢复速度快
- 对服务器性能影响小

**劣势**：
- 可能丢失最近的数据修改

### 1.2 AOF 持久化

**原理**：将写命令追加到 AOF 文件，恢复时重放这些命令。

**配置**：

```conf
# 开启 AOF
appendonly yes

# AOF 文件名称
appendfilename "appendonly.aof"

# AOF 同步策略：always, everysec, no
appendfsync everysec

# AOF 重写触发条件
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

**手动重写**：

```bash
BGREWRITEAOF
```

**优势**：
- 数据安全性更高
- 可配置不同的同步策略

**劣势**：
- 文件较大
- 恢复速度较慢

## 2. Lua 脚本

Luban-RDS 使用 LuaJ 引擎实现 Lua 脚本支持，提供与 Redis 完全兼容的 Lua 执行环境。

### 2.1 基本用法

```bash
# 执行简单脚本
EVAL "return 'Hello'" 0

# 使用键和参数
EVAL "return redis.call('GET', KEYS[1])" 1 mykey

# 使用变量
EVAL "local value = ARGV[1]; return redis.call('SET', KEYS[1], value)" 1 mykey "Hello"
```

### 2.2 脚本缓存

```bash
# 加载脚本
SCRIPT LOAD "return redis.call('GET', KEYS[1])"
# 返回: "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 执行缓存的脚本
EVALSHA "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c" 1 mykey

# 检查脚本是否存在
SCRIPT EXISTS "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 清除脚本缓存
SCRIPT FLUSH
```

### 2.3 Redis API

Lua 脚本中可以使用以下 Redis API：

- **redis.call()**：执行命令，错误会向上传播
- **redis.pcall()**：执行命令，错误会被捕获
- **redis.error_reply()**：返回错误响应
- **redis.status_reply()**：返回状态响应
- **redis.sha1hex()**：计算字符串的 SHA1 哈希值

### 2.4 最佳实践

- 使用 `KEYS` 和 `ARGV` 传递参数
- 利用脚本缓存提高性能
- 合理设置脚本超时时间
- 避免长时间运行的脚本

## 3. 发布订阅

Luban-RDS 支持频道订阅、模式订阅和消息广播功能。

### 3.1 基本操作

**订阅频道**：

```bash
# 订阅单个频道
SUBSCRIBE channel

# 订阅多个频道
SUBSCRIBE channel1 channel2

# 模式订阅
PSUBSCRIBE channel:*

# 流订阅
SSUBSCRIBE stream:*
```

**发布消息**：

```bash
PUBLISH channel "Hello World"
```

**取消订阅**：

```bash
# 取消订阅单个频道
UNSUBSCRIBE channel

# 取消订阅所有频道
UNSUBSCRIBE

# 取消模式订阅
PUNSUBSCRIBE channel:*

# 取消流订阅
SUNSUBSCRIBE stream:*
```

### 3.2 消息格式

- **订阅确认**：`["subscribe", "channel", count]`
- **取消订阅确认**：`["unsubscribe", "channel", count]`
- **消息推送**：`["message", "channel", "message"]`
- **模式订阅确认**：`["psubscribe", "pattern", count]`
- **模式消息推送**：`["pmessage", "pattern", "channel", "message"]`

### 3.3 使用场景

- 实时通知
- 消息队列
- 事件系统
- 分布式系统协调

## 4. 事务

Luban-RDS 提供完整的事务支持，包括 MULTI、EXEC、DISCARD、WATCH、UNWATCH 命令。

### 4.1 基本操作

```bash
# 开始事务
MULTI

# 执行命令（入队）
SET key1 value1
SET key2 value2

# 提交事务
EXEC

# 取消事务
DISCARD
```

### 4.2 WATCH 机制

```bash
# 监视键
WATCH key1 key2

# 开始事务
MULTI

# 执行命令
SET key1 newvalue

# 提交事务（如果 key1 或 key2 被修改，返回 Null Array）
EXEC
```

### 4.3 事务行为说明

- 使用 WATCH 监视的键在 EXEC 前如果发生变更，EXEC 返回 Null Array（RESP: `*-1\r\n`），事务不执行
- 事务入队阶段若存在参数错误，EXEC 返回 EXECABORT 并丢弃整个事务
- 事务内 SELECT 更新客户端数据库状态

## 5. 管道

### 5.1 基本原理

通过管道（pipeline）技术，客户端可以批量发送命令，减少网络往返时间，提高性能。

### 5.2 使用示例

```java
// 使用管道
RedisClient client = new NettyRedisClient("localhost", 9736);

// 开始管道
client.pipelined();

// 发送多个命令
for (int i = 0; i < 1000; i++) {
    client.set("key" + i, "value" + i);
}

// 执行并获取结果
List<Object> results = client.sync();

client.close();
```

**性能提升**：
- 减少网络往返时间
- 降低 CPU 开销
- 提高命令执行吞吐量

## 6. 监控

### 6.1 实时监控（MONITOR）

MONITOR 命令提供实时命令监控能力：

```bash
# 监控所有命令
MONITOR

# 仅监控数据库 0 的命令
MONITOR DB 0

# 仅监控 SET 开头的命令
MONITOR MATCH ^SET.*
```

**输出格式**：
```
<timestamp> [db <dbid> <client-addr>] "<command>" "<arg1>" "<arg2>" ...
```

例如：
```
1614850000.123456 [0 127.0.0.1:54321] "SET" "key" "value"
```

### 6.2 慢查询日志（SLOWLOG）

```bash
# 获取慢查询日志
SLOWLOG GET 10

# 获取慢查询日志长度
SLOWLOG LEN

# 清空慢查询日志
SLOWLOG RESET
```

### 6.3 性能监控

使用 `INFO` 命令获取服务器性能指标：

```bash
# 获取所有信息
INFO

# 获取内存信息
INFO memory

# 获取统计信息
INFO stats

# 获取命令统计
INFO commandstats
```

### 6.4 内存分析（MEMORY）

```bash
# 估算键占用的内存
MEMORY USAGE mykey

# 获取内存统计信息
MEMORY STATS

# 触发内存清理
MEMORY PURGE

# 获取内存诊断报告
MEMORY DOCTOR
```

## 7. 安全

### 7.1 访问控制

**设置密码**：

```conf
requirepass your-secure-password
```

**使用密码连接**：

```bash
# 连接时提供密码
redis-cli -a your-password

# 连接后认证
AUTH your-password
```

### 7.2 命令限制

（通过配置）限制危险命令的使用，如 `FLUSHALL`、`CONFIG` 等。

### 7.3 网络限制

**绑定地址**：

```conf
bind 127.0.0.1 192.168.1.100
```

## 8. Spring Boot 集成

Luban-RDS 提供了 Spring Boot starter 模块，方便在 Spring Boot 应用中使用。

### 8.1 添加依赖

```xml
<dependency>
    <groupId>com.janeluo.luban</groupId>
    <artifactId>luban-rds-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

### 8.2 配置属性

```yaml
spring:
  luban:
    rds:
      port: 9736
      host: localhost
      password: your-password
```

### 8.3 使用示例

```java
@Autowired
private StringRedisTemplate redisTemplate;

public void setValue(String key, String value) {
    redisTemplate.opsForValue().set(key, value);
}

public String getValue(String key) {
    return redisTemplate.opsForValue().get(key);
}
```

## 9. 高级配置

### 9.1 内存管理

```conf
# 最大内存限制
maxmemory 2gb

# 内存淘汰策略
maxmemory-policy volatile-lru
```

### 9.2 网络配置

```conf
# TCP 保活时间
tcp-keepalive 300

# 客户端超时时间
timeout 300
```

### 9.3 日志配置

（通过 logback.xml 配置）调整日志级别和输出方式。

## 10. 分布式追踪

### 10.1 概述

Luban-RDS 内置分布式追踪支持，每个请求自动生成唯一的 TraceId，便于日志关联和问题排查。

### 10.2 TraceId 格式

```
{时间戳}-{机器标识}-{进程ID}-{序列号}
示例：1704067200000-a1b2c3d4-1234-000001
```

### 10.3 日志输出

所有日志自动包含 `traceId` 字段：

```
14:30:45.123 [nioEventLoopGroup-3-1] [traceId:1704067200000-a1b2c3d4-1234-000001] DEBUG c.j.l.r.server.RedisServerHandler - Processing command: SET
```

### 10.4 手动使用

```java
import com.janeluo.luban.rds.common.context.TraceContext;

// 获取当前 TraceId
String traceId = TraceContext.getTraceId();

// 设置自定义 TraceId
TraceContext.setTraceId("custom-trace-id");

// 生成新 TraceId
String newTraceId = TraceContext.generateTraceId();
```

### 10.5 异步场景

在异步处理时使用包装类传递 TraceId：

```java
import com.janeluo.luban.rds.common.context.TraceableRunnable;
import com.janeluo.luban.rds.common.context.TraceableCallable;

// 包装 Runnable
executor.submit(TraceableRunnable.wrap(() -> {
    // TraceId 自动传递到子线程
    logger.info("Processing in async thread");
}));

// 包装 Callable
Future<String> future = executor.submit(TraceableCallable.wrap(() -> {
    return TraceContext.getTraceId();
}));
```

### 10.6 日志查询

```bash
# 查询特定请求的所有日志
grep "traceId:1704067200000-a1b2c3d4-1234-000001" logs/luban-rds.log
```

## 11. 主从复制

### 11.1 概述

主从复制是 Luban-RDS 的高可用性特性，通过将数据从主节点复制到从节点，实现数据备份和负载均衡。

### 11.2 配置主从复制

**方法 1：配置文件**

在从节点的配置文件中添加：

```conf
# 配置主节点地址
replicaof 192.168.1.100 9736

# 如果主节点有密码
masterauth your-master-password

# 复制超时时间
repl-timeout 60

# 复制积压缓冲区大小
repl-backlog-size 1mb
```

**方法 2：运行时命令**

```bash
# 配置为从节点
SLAVEOF 192.168.1.100 9736

# 取消从节点身份
SLAVEOF NO ONE
```

### 11.3 查看复制状态

**查看节点角色**：

```bash
ROLE
```

**主节点输出**：
```
1) "master"
2) (integer) 123456  # 当前复制偏移量
3) 1) 1) "192.168.1.101"
      2) "6379"
      3) "123450"
```

**从节点输出**：
```
1) "slave"
2) "192.168.1.100"
3) (integer) 9736
4) "connected"
5) (integer) 123456  # 当前复制偏移量
```

### 11.4 复制监控

使用 `INFO replication` 命令查看复制详细信息：

```bash
INFO replication
```

### 11.5 最佳实践

- **主从部署**：主节点负责写操作，从节点负责读操作
- **数据备份**：从节点可以用于数据备份，不影响主节点性能
- **高可用**：当主节点故障时，可将从节点提升为新的主节点
- **网络配置**：确保主从节点之间网络稳定
- **内存配置**：从节点内存应不小于主节点，确保能容纳所有数据

### 11.6 故障处理

**主节点故障**：
1. 选择一个从节点执行 `SLAVEOF NO ONE` 成为新的主节点
2. 其他从节点执行 `SLAVEOF new-master-ip new-master-port` 指向新的主节点

**从节点故障**：
- 从节点重启后会自动重新连接主节点并同步数据

## 12. 下一步

- **[使用示例](./examples.md)**：查看常见场景的代码示例
- **[API 文档](../api/)**：深入了解 API 接口
- **[部署运维](../deployment/)**：学习生产环境部署和维护
