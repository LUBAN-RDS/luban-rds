---
title: 命令列表
---

# 命令列表

本部分详细列出了 Luban-RDS 支持的所有 Redis 命令，包括命令语法、参数说明和使用示例。

## 1. String 类型命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **SET** | `SET key value [EX seconds] [PX milliseconds] [NXXX]` | 设置字符串值 |
| **GET** | `GET key` | 获取字符串值 |
| **INCR** | `INCR key` | 原子递增 |
| **DECR** | `DECR key` | 原子递减 |
| **INCRBY** | `INCRBY key increment` | 按指定值递增 |
| **DECRBY** | `DECRBY key decrement` | 按指定值递减 |
| **APPEND** | `APPEND key value` | 追加字符串 |
| **STRLEN** | `STRLEN key` | 获取字符串长度 |
| **MSET** | `MSET key value [key value ...]` | 同时设置一个或多个键值对 |
| **MGET** | `MGET key [key ...]` | 获取所有(一个或多个)给定键的值 |

**示例**：
```bash
SET name "John"
GET name
MSET key1 "Hello" key2 "World"
MGET key1 key2
INCR counter
INCRBY counter 10
APPEND message " world"
STRLEN message
```

## 2. Hash 类型命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **HSET** | `HSET key field value` | 设置哈希字段 |
| **HSETNX** | `HSETNX key field value` | 当字段不存在时设置 |
| **HGET** | `HGET key field` | 获取哈希字段 |
| **HGETALL** | `HGETALL key` | 获取所有哈希字段 |
| **HDEL** | `HDEL key field [field ...]` | 删除哈希字段 |
| **HEXISTS** | `HEXISTS key field` | 检查字段是否存在 |
| **HKEYS** | `HKEYS key` | 获取所有字段名 |
| **HVALS** | `HVALS key` | 获取所有字段值 |
| **HLEN** | `HLEN key` | 获取字段数量 |
| **HSCAN** | `HSCAN key cursor [MATCH pattern] [COUNT count]` | 迭代遍历字段，返回 `[cursor, [field,value,...]]` |
| **HMSET** | `HMSET key field value [field value ...]` | 同时将多个 field-value (域-值)对设置到哈希表 key 中 |
| **HMGET** | `HMGET key field [field ...]` | 获取哈希表中所有给定字段的值 |

**示例**：
```bash
HSET user:1 name "John"
HSET user:1 age 30
HMSET user:1 name "Alice" age 30 email "alice@example.com"
HMGET user:1 name age
# 字段不存在时才设置
HSETNX user:1 name "Alice"   # 返回 0，不覆盖
HSETNX user:1 email "alice@example.com"  # 返回 1，新增
HGETALL user:1
HDEL user:1 age
HEXISTS user:1 name
# 迭代遍历字段
HSCAN user:1 0 MATCH n* COUNT 10
```

## 3. List 类型命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **LPUSH** | `LPUSH key value [value ...]` | 左侧推入元素 |
| **RPUSH** | `RPUSH key value [value ...]` | 右侧推入元素 |
| **LPOP** | `LPOP key` | 左侧弹出元素 |
| **RPOP** | `RPOP key` | 右侧弹出元素 |
| **LLEN** | `LLEN key` | 获取列表长度 |
| **LRANGE** | `LRANGE key start stop` | 获取列表范围 |
| **LINDEX** | `LINDEX key index` | 获取指定索引的元素 |
| **LSET** | `LSET key index value` | 设置指定索引的元素 |
| **LREM** | `LREM key count value` | 删除指定值的元素 |

**示例**：
```bash
LPUSH fruits apple banana cherry
RPUSH fruits date
LRANGE fruits 0 -1
LPOP fruits
LLEN fruits
```

## 4. Set 类型命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **SADD** | `SADD key member [member ...]` | 添加集合成员 |
| **SREM** | `SREM key member [member ...]` | 移除集合成员 |
| **SMEMBERS** | `SMEMBERS key` | 获取所有成员 |
| **SISMEMBER** | `SISMEMBER key member` | 检查成员是否存在 |
| **SCARD** | `SCARD key` | 获取成员数量 |
| **SRANDMEMBER** | `SRANDMEMBER key [count]` | 随机获取成员 |
| **SPOP** | `SPOP key [count]` | 随机弹出成员 |

**示例**：
```bash
SADD tags java python javascript
SMEMBERS tags
SISMEMBER tags java
SCARD tags
SPOP tags
```

## 5. ZSet 类型命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **ZADD** | `ZADD key score member [score member ...]` | 添加有序集合成员 |
| **ZRANGE** | `ZRANGE key start stop [WITHSCORES]` | 获取范围成员 |
| **ZSCORE** | `ZSCORE key member` | 获取成员分数 |
| **ZREM** | `ZREM key member [member ...]` | 移除成员 |
| **ZCARD** | `ZCARD key` | 获取成员数量 |
| **ZREVRANGE** | `ZREVRANGE key start stop [WITHSCORES]` | 倒序获取范围成员 |
| **ZRANGEBYSCORE** | `ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count]` | 按分数范围获取 |
| **ZINCRBY** | `ZINCRBY key increment member` | 增加成员分数 |

**示例**：
```bash
ZADD scores 85 "Alice" 92 "Bob" 78 "Charlie"
ZRANGE scores 0 -1 WITHSCORES
ZREVRANGE scores 0 -1
ZSCORE scores "Bob"
ZINCRBY scores 5 "Alice"
```

## 6. 通用命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **EXISTS** | `EXISTS key [key ...]` | 检查键是否存在 |
| **DEL** | `DEL key [key ...]` | 删除键 |
| **EXPIRE** | `EXPIRE key seconds` | 设置过期时间 |
| **TTL** | `TTL key` | 获取剩余生存时间 |
| **FLUSHALL** | `FLUSHALL` | 清空所有数据库 |
| **FLUSHDB** | `FLUSHDB` | 清空当前数据库 |
| **TYPE** | `TYPE key` | 获取键类型 |
| **ECHO** | `ECHO message` | 回显字符串 |
| **SELECT** | `SELECT index` | 选择数据库 |
| **INFO** | `INFO [section]` | 获取服务器信息和统计数据。支持的 section 包括：Server, Clients, Memory, Persistence, Stats, Replication, CPU, Commandstats, Cluster, Keyspace 等。如果不指定 section，默认返回所有信息。 |
| **SCAN** | `SCAN cursor [MATCH pattern] [COUNT count]` | 遍历键 |
| **DBSIZE** | `DBSIZE` | 获取数据库键数量 |
| **TIME** | `TIME` | 获取服务器时间 |
| **LASTSAVE** | `LASTSAVE` | 获取最后保存时间 |
| **BGREWRITEAOF** | `BGREWRITEAOF` | 后台重写 AOF |
| **BGSAVE** | `BGSAVE` | 后台保存 |
| **SAVE** | `SAVE` | 同步保存 |
| **QUIT** | `QUIT` | 返回 `+OK\r\n` 后关闭连接 |

**示例**：
```bash
EXISTS name
DEL name age
EXPIRE session 3600
TTL session
FLUSHDB
INFO memory
SCAN 0 MATCH user:* COUNT 10
```

## 7. 认证命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **AUTH** | `AUTH password` | 认证 |

**示例**：
```bash
AUTH your-secure-password
```

## 8. 客户端命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **CLIENT KILL** | `CLIENT KILL [ip:port] [ID client-id] [TYPE normalmasterreplicapubsub] [ADDR ip:port] [SKIPME yesno]` | 关闭客户端连接 |
| **CLIENT LIST** | `CLIENT LIST` | 列出客户端连接 |
| **CLIENT GETNAME** | `CLIENT GETNAME` | 获取连接名称 |
| **CLIENT PAUSE** | `CLIENT PAUSE timeout` | 暂停客户端 |
| **CLIENT SETNAME** | `CLIENT SETNAME connection-name` | 设置连接名称 |

**示例**：
```bash
CLIENT LIST
CLIENT SETNAME "Application-1"
CLIENT GETNAME
```

## 9. Lua 脚本命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **EVAL** | `EVAL script numkeys key [key ...] arg [arg ...]` | 执行 Lua 脚本 |
| **EVALSHA** | `EVALSHA sha1 numkeys key [key ...] arg [arg ...]` | 执行缓存的 Lua 脚本 |
| **SCRIPT LOAD** | `SCRIPT LOAD script` | 加载并缓存 Lua 脚本 |
| **SCRIPT EXISTS** | `SCRIPT EXISTS sha1 [sha1 ...]` | 检查脚本是否已缓存 |
| **SCRIPT FLUSH** | `SCRIPT FLUSH` | 清除所有脚本缓存 |
| **SCRIPT KILL** | `SCRIPT KILL` | 中断正在运行的脚本 |

**示例**：
```bash
EVAL "return 'Hello'" 0
EVAL "return redis.call('GET', KEYS[1])" 1 name
SCRIPT LOAD "return redis.call('GET', KEYS[1])"
EVALSHA "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c" 1 name
```

## 10. 发布订阅命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **SUBSCRIBE** | `SUBSCRIBE channel [channel ...]` | 订阅频道 |
| **UNSUBSCRIBE** | `UNSUBSCRIBE [channel [channel ...]]` | 取消订阅 |
| **PUBLISH** | `PUBLISH channel message` | 发布消息 |
| **PSUBSCRIBE** | `PSUBSCRIBE pattern [pattern ...]` | 模式订阅 |
| **PUNSUBSCRIBE** | `PUNSUBSCRIBE [pattern [pattern ...]]` | 取消模式订阅 |

**示例**：
```bash
# 订阅者
SUBSCRIBE news

# 发布者
PUBLISH news "Breaking news!"

# 模式订阅
PSUBSCRIBE news:*
```

## 11. 事务命令
 

| 命令 | 语法 | 说明 |
|------|------|------|
| **MULTI** | `MULTI` | 开始事务 |
| **EXEC** | `EXEC` | 执行事务 |
| **DISCARD** | `DISCARD` | 取消事务 |
| **WATCH** | `WATCH key [key ...]` | 监视键 |
| **UNWATCH** | `UNWATCH` | 取消监视 |

**示例**：
```bash
MULTI
SET name "John"
SET age 30
EXEC
```
 
### 行为说明
- 进入事务后，普通命令不立即执行，返回 `QUEUED` 并入队；
- 当队列中存在参数错误等入队阶段错误，`EXEC` 返回 `EXECABORT` 并丢弃整个事务；
- 使用 `WATCH` 监视的键在 `EXEC` 前如果发生变更，`EXEC` 返回 Null Array（RESP: `*-1\r\n`），事务不执行；
- `WATCH` 只能在事务外使用；`UNWATCH` 清除所有监视；

**RESP 原始示例（WATCH 触发）：**
```
*-1\r\n
```

## 12. 内存管理命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **MEMORY USAGE** | `MEMORY USAGE key [SAMPLES count]` | 估算指定键占用的内存大小 |
| **MEMORY STATS** | `MEMORY STATS` | 返回详细的内存统计信息 |
| **MEMORY PURGE** | `MEMORY PURGE` | 尝试释放内存（触发 GC） |
| **MEMORY MALLOC-STATS** | `MEMORY MALLOC-STATS` | 返回内存分配器的内部统计信息（JVM 内存详情） |
| **MEMORY DOCTOR** | `MEMORY DOCTOR` | 内存使用诊断报告 |
| **MEMORY HELP** | `MEMORY HELP` | 获取帮助信息 |

**示例**：
```bash
MEMORY USAGE mykey
MEMORY STATS
MEMORY DOCTOR
```

## 13. 慢查询日志命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **SLOWLOG GET** | `SLOWLOG GET [count]` | 获取慢查询日志。默认返回所有日志，或指定返回最近的 count 条。 |
| **SLOWLOG LEN** | `SLOWLOG LEN` | 获取慢查询日志的当前长度。 |
| **SLOWLOG RESET** | `SLOWLOG RESET` | 清空慢查询日志。 |

**示例**：
```bash
SLOWLOG GET 10
SLOWLOG LEN
SLOWLOG RESET
```

## 14. 命令执行规则

### 13.1 命令大小写

- Redis 命令不区分大小写，但建议使用大写以提高可读性
- 键名和值区分大小写

### 13.2 参数数量

- 不同命令需要不同数量的参数
- 缺少参数会返回错误

### 13.3 错误处理

- 命令执行失败会返回错误信息
- 错误信息格式：`-ERROR message`

### 13.4 性能注意事项

- **FLUSHALL** 和 **FLUSHDB** 会阻塞服务器
- 大键的操作（如获取大型哈希表）可能会影响性能
- Lua 脚本执行时间过长会阻塞服务器

## 14. 命令返回值

| 类型 | 说明 | 示例 |
|------|------|------|
| **String** | 字符串值 | `"Hello"` |
| **Integer** | 整数值 | `(integer) 1` |
| **Array** | 数组 | `1) "a" 2) "b"` |
| **Nil** | 空值 | `(nil)` |
| **Error** | 错误信息 | `-ERROR message` |
| **Status** | 状态信息 | `OK` |

## 15. 监控命令

| 命令 | 语法 | 说明 |
|------|------|------|
| **MONITOR** | `MONITOR [DB dbid] [MATCH pattern]` | 实时监控服务器接收到的命令 |

**参数说明**：
- `DB dbid`（可选）：仅监控指定数据库的命令。
- `MATCH pattern`（可选）：仅监控命令名匹配指定模式的命令（支持正则）。

**示例**：
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

## 16. 下一步

- **[核心接口](./core.md)**：了解 MemoryStore 等核心接口的详细定义
- **[协议说明](./protocol.md)**：深入了解 RESP 协议的工作原理
- **[使用指南](../guide/)**：学习如何使用这些命令
