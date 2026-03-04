---
title: 核心接口
---

# 核心接口

本部分详细介绍了 Luban-RDS 的核心接口，包括 `MemoryStore`、`CommandHandler` 等关键接口的定义和使用方法。

## 1. MemoryStore 接口

`MemoryStore` 是 Luban-RDS 的核心存储接口，提供了所有数据类型的操作方法。

### 1.1 基本操作

```java
public interface MemoryStore {
    /**
     * 获取键对应的值
     * @param database 数据库索引
     * @param key 键
     * @return 值，不存在返回 null
     */
    Object get(int database, String key);
    
    /**
     * 设置键值对
     * @param database 数据库索引
     * @param key 键
     * @param value 值
     */
    void set(int database, String key, Object value);
    
    /**
     * 批量设置键值对
     * @param database 数据库索引
     * @param keysAndValues 键值对数组，格式为 [key1, value1, key2, value2, ...]
     */
    void mset(int database, String... keysAndValues);
    
    /**
     * 批量获取键的值
     * @param database 数据库索引
     * @param keys 键数组
     * @return 值列表，不存在的键对应 null
     */
    List<Object> mget(int database, String... keys);
    
    /**
     * 设置带过期时间的键值对
     * @param database 数据库索引
     * @param key 键
     * @param value 值
     * @param expireSeconds 过期时间（秒）
     */
    void setWithExpire(int database, String key, Object value, long expireSeconds);
    
    /**
     * 删除键
     * @param database 数据库索引
     * @param key 键
     * @return 是否删除成功
     */
    boolean del(int database, String key);
    
    /**
     * 设置键的过期时间
     * @param database 数据库索引
     * @param key 键
     * @param seconds 过期时间（秒）
     * @return 是否设置成功
     */
    boolean expire(int database, String key, long seconds);
    
    /**
     * 检查键是否存在
     * @param database 数据库索引
     * @param key 键
     * @return 是否存在
     */
    boolean exists(int database, String key);
    
    /**
     * 获取键的剩余生存时间
     * @param database 数据库索引
     * @param key 键
     * @return 剩余时间（秒），不存在或无过期时间返回 -1
     */
    long ttl(int database, String key);
    
    /**
     * 清空所有数据库
     */
    void flushAll();
    
    /**
     * 获取键的类型
     * @param database 数据库索引
     * @param key 键
     * @return 类型字符串，如 "string", "hash", "list", "set", "zset"
     */
    String type(int database, String key);
    
    /**
     * 扫描数据库中的键
     * @param database 数据库索引
     * @param cursor 游标
     * @param pattern 匹配模式
     * @param count 计数
     * @return 包含新游标和匹配键的列表，格式为 [newCursor, key1, key2, ...]
     */
    List<Object> scan(int database, long cursor, String pattern, int count);
    
    /**
     * 返回当前数据库的键数量
     * @param database 数据库索引
     * @return 当前数据库的键数量
     */
    long dbsize(int database);
    
    /**
     * 删除当前数据库的所有键
     * @param database 数据库索引
     */
    void flushdb(int database);
}
```

### 1.2 Hash 操作优化接口

```java
// ==================== Hash 操作优化接口 ====================

/**
     * 设置 Hash 字段值（直接操作，避免复制整个 Map）
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @param value 字段值
     * @return 1 表示新增字段，0 表示更新已有字段
     */
    int hset(int database, String key, String field, String value);
    
    /**
     * 批量设置 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param fieldsAndValues 字段值对数组，格式为 [field1, value1, field2, value2, ...]
     * @return 新增字段的数量
     */
    int hmset(int database, String key, String... fieldsAndValues);
    
    /**
     * 当字段不存在时设置 Hash 字段值
 * @param database 数据库索引
 * @param key Hash 键
 * @param field 字段名
 * @param value 字段值
 * @return 1 表示成功设置（原先不存在），0 表示未设置（字段已存在）
 */
int hsetnx(int database, String key, String field, String value);

/**
     * 获取 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @return 字段值，不存在返回 null
     */
    String hget(int database, String key, String field);
    
    /**
     * 批量获取 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param fields 字段名数组
     * @return 字段值列表，不存在的字段对应 null
     */
    java.util.List<String> hmget(int database, String key, String... fields);
    
    /**
     * 删除 Hash 字段
 * @param database 数据库索引
 * @param key Hash 键
 * @param fields 要删除的字段名数组
 * @return 删除的字段数量
 */
int hdel(int database, String key, String... fields);

/**
 * 检查 Hash 字段是否存在
 * @param database 数据库索引
 * @param key Hash 键
 * @param field 字段名
 * @return 是否存在
 */
boolean hexists(int database, String key, String field);

/**
 * 获取 Hash 的所有字段和值
 * @param database 数据库索引
 * @param key Hash 键
 * @return 字段和值的 Map
 */
java.util.Map<String, String> hgetall(int database, String key);

/**
 * 获取 Hash 的字段数量
 * @param database 数据库索引
 * @param key Hash 键
 * @return 字段数量
 */
int hlen(int database, String key);

/**
 * 扫描 Hash 字段
 * @param database 数据库索引
 * @param key Hash 键
 * @param cursor 游标
 * @param pattern 匹配模式
 * @param count 返回的最大字段数
 * @return [newCursor, field1, value1, field2, value2, ...]
 */
java.util.List<Object> hscan(int database, String key, long cursor, String pattern, int count);
```

### 1.3 List 操作优化接口

```java
// ==================== List 操作优化接口 ====================

/**
 * 从列表左侧插入元素（直接操作，避免复制整个 List）
 * @param database 数据库索引
 * @param key List 键
 * @param values 要插入的值
 * @return 插入后列表的长度
 */
int lpush(int database, String key, String... values);

/**
 * 从列表右侧插入元素
 * @param database 数据库索引
 * @param key List 键
 * @param values 要插入的值
 * @return 插入后列表的长度
 */
int rpush(int database, String key, String... values);

/**
 * 从列表左侧弹出元素
 * @param database 数据库索引
 * @param key List 键
 * @return 弹出的元素，列表为空返回 null
 */
String lpop(int database, String key);

/**
 * 从列表右侧弹出元素
 * @param database 数据库索引
 * @param key List 键
 * @return 弹出的元素，列表为空返回 null
 */
String rpop(int database, String key);

/**
 * 获取列表长度
 * @param database 数据库索引
 * @param key List 键
 * @return 列表长度
 */
int llen(int database, String key);

/**
 * 获取列表指定范围的元素
 * @param database 数据库索引
 * @param key List 键
 * @param start 起始索引
 * @param stop 结束索引
 * @return 元素列表
 */
java.util.List<String> lrange(int database, String key, long start, long stop);
```

### 1.4 Set 操作优化接口

```java
// ==================== Set 操作优化接口 ====================

/**
 * 向集合添加元素（直接操作，避免复制整个 Set）
 * @param database 数据库索引
 * @param key Set 键
 * @param members 要添加的成员
 * @return 新添加的成员数量
 */
int sadd(int database, String key, String... members);

/**
 * 从集合删除元素
 * @param database 数据库索引
 * @param key Set 键
 * @param members 要删除的成员
 * @return 删除的成员数量
 */
int srem(int database, String key, String... members);

/**
 * 检查成员是否在集合中
 * @param database 数据库索引
 * @param key Set 键
 * @param member 成员
 * @return 是否存在
 */
boolean sismember(int database, String key, String member);

/**
 * 获取集合所有成员
 * @param database 数据库索引
 * @param key Set 键
 * @return 成员集合
 */
java.util.Set<String> smembers(int database, String key);

/**
 * 获取集合成员数量
 * @param database 数据库索引
 * @param key Set 键
 * @return 成员数量
 */
int scard(int database, String key);
```

### 1.5 ZSet 操作优化接口

```java
// ==================== ZSet 操作优化接口 ====================

/**
 * 向有序集合添加成员（使用跳表结构，保持有序）
 * @param database 数据库索引
 * @param key ZSet 键
 * @param score 分数
 * @param member 成员
 * @return 1 表示新增，0 表示更新
 */
int zadd(int database, String key, double score, String member);

/**
 * 从有序集合删除成员
 * @param database 数据库索引
 * @param key ZSet 键
 * @param members 要删除的成员
 * @return 删除的成员数量
 */
int zrem(int database, String key, String... members);

/**
 * 获取成员的分数
 * @param database 数据库索引
 * @param key ZSet 键
 * @param member 成员
 * @return 分数，不存在返回 null
 */
Double zscore(int database, String key, String member);

/**
 * 按分数范围获取成员（已排序）
 * @param database 数据库索引
 * @param key ZSet 键
 * @param start 起始索引
 * @param stop 结束索引
 * @return 成员列表（按分数升序）
 */
java.util.List<String> zrange(int database, String key, long start, long stop);

/**
 * 获取有序集合成员数量
 * @param database 数据库索引
 * @param key ZSet 键
 * @return 成员数量
 */
int zcard(int database, String key);
```

## 2. CommandHandler 接口

`CommandHandler` 负责处理 Redis 命令。

```java
public interface CommandHandler {
    /**
     * 获取支持的命令集合
     * @return 支持的命令集合
     */
    Set<String> supportedCommands();
    
    /**
     * 处理命令
     * @param database 数据库索引
     * @param args 命令参数
     * @param store 内存存储
     * @return 命令执行结果
     */
    Object handle(int database, String[] args, MemoryStore store);
}
```

## 3. 命令处理器实现

| 处理器 | 处理的命令 | 实现类 |
|--------|------------|--------|
| **StringCommandHandler** | SET, GET, INCR, DECR, INCRBY, DECRBY, APPEND, STRLEN | `luban-rds-core` |
| **HashCommandHandler** | HSET, HGET, HGETALL, HDEL, HEXISTS, HKEYS, HVALS, HLEN | `luban-rds-core` |
| **HashCommandHandler（扩展）** | HSETNX, HSCAN | `luban-rds-core` |
| **ListCommandHandler** | LPUSH, RPUSH, LPOP, RPOP, LLEN, LRANGE | `luban-rds-core` |
| **SetCommandHandler** | SADD, SREM, SMEMBERS, SISMEMBER, SCARD | `luban-rds-core` |
| **ZSetCommandHandler** | ZADD, ZRANGE, ZSCORE, ZREM, ZCARD | `luban-rds-core` |
| **CommonCommandHandler** | EXISTS, DEL, EXPIRE, TTL, FLUSHALL, TYPE, ECHO, SELECT, INFO, SCAN, DBSIZE, FLUSHDB, TIME, LASTSAVE, BGREWRITEAOF, BGSAVE | `luban-rds-core` |
| **AuthCommandHandler** | AUTH | `luban-rds-core` |
| **ClientCommandHandler** | CLIENT KILL, CLIENT LIST, CLIENT GETNAME, CLIENT PAUSE, CLIENT SETNAME | `luban-rds-core` |
| **LuaCommandHandler** | EVAL, EVALSHA, SCRIPT | `luban-rds-core` |
| **SelectCommandHandler** | SELECT | `luban-rds-core` |

## 4. RedisClient 接口

`RedisClient` 是 Java 客户端接口，用于与 Luban-RDS 服务器通信。

```java
public interface RedisClient {
    /**
     * 连接服务器
     */
    void connect();
    
    /**
     * 关闭连接
     */
    void close();
    
    /**
     * 测试连接
     * @return PONG
     */
    String ping();
    
    /**
     * 设置值
     * @param key 键
     * @param value 值
     * @return OK
     */
    String set(String key, String value);
    
    /**
     * 获取值
     * @param key 键
     * @return 值
     */
    String get(String key);
    
    /**
     * 执行命令
     * @param command 命令
     * @param args 参数
     * @return 命令执行结果
     */
    Object execute(String command, String... args);
}
```

## 5. 实现类

### 5.1 DefaultMemoryStore

`DefaultMemoryStore` 是 `MemoryStore` 接口的默认实现，使用 Java 集合存储数据。

**核心特性**：
- 使用 `ConcurrentHashMap` 存储数据库
- 支持过期时间管理
- 实现了所有数据类型的操作
- 直接操作集合，避免数据复制

### 5.2 NettyRedisServer

`NettyRedisServer` 是基于 Netty 的 Redis 服务器实现。

**核心特性**：
- 使用 Netty 4 的 NIO 非阻塞 IO
- 支持高并发连接
- 事件驱动的请求处理
- 可配置的线程池

### 5.3 RedisProtocolParser

`RedisProtocolParser` 负责解析 Redis 序列化协议（RESP）。

**支持的类型**：
- Simple String (+)
- Error (-)
- Integer (:)
- Bulk String ($)
- Array (*)

## 6. 版本信息

- **当前版本**：1.0.0
- **核心模块**：
  - luban-rds-core：核心命令处理器和内存存储
  - luban-rds-protocol：RESP 协议解析
  - luban-rds-server：Netty 服务器实现
  - luban-rds-persistence：持久化支持
  - luban-rds-client：Java 客户端
  - luban-rds-common：公共工具类
- **依赖**：
  - Netty 4.1.x：网络通信
  - LuaJ 3.0.1：Lua 脚本执行
  - Jackson：JSON 处理
  - SLF4J：日志处理

## 7. 下一步

- **[命令列表](./commands.md)**：查看支持的所有 Redis 命令
- **[协议说明](./protocol.md)**：深入了解 RESP 协议的工作原理
- **[使用指南](../guide/)**：学习如何使用 Luban-RDS

## 8. 命令分发与完整参数传递

Luban-RDS 使用 `DefaultCommandHandler` 根据命令名将请求分发到具体的命令处理器。服务器会将客户端发送的参数“完整原样”传递到处理器，包括命令名及其后续所有参数，从而保持与原始请求一致（二进制安全、无截断）。

- 分发入口参考：[DefaultCommandHandler.java:L55-L62](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-core/src/main/java/com/igbp/luban/rds/core/handler/DefaultCommandHandler.java#L55-L62)
- 参数传递参考：[RedisServerHandler.java:L685-L694](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L685-L694)

## 9. 事务内 SELECT 的状态更新

在事务执行过程中，`SELECT` 命令会更新客户端当前数据库状态，并影响后续命令的执行上下文。该行为在事务命令循环中得到处理：

- 代码参考：[RedisServerHandler.java:L695-L701](file:///d:/workspaces_idea/igbp-luban-rds/luban-rds-server/src/main/java/com/igbp/luban/rds/server/RedisServerHandler.java#L695-L701)
