---
title: 基本使用
---

# 基本使用

本部分介绍了 Luban-RDS 的常用命令和基本操作，帮助您快速掌握核心功能。

## 连接服务器

### 使用 Redis 客户端

```bash
# 默认连接（本地 9736 端口）
redis-cli

# 指定主机和端口
redis-cli -h localhost -p 9736

# 使用密码连接
redis-cli -a your-password
```

### 使用 Java 客户端

```java
// 创建客户端
RedisClient client = new NettyRedisClient("localhost", 9736);

// 验证连接
String result = client.ping();
System.out.println(result); // 输出: PONG

// 关闭客户端
client.close();
```

## String 类型操作

### SET - 设置值

```bash
# 设置简单值
SET key value

# 设置带过期时间的值（10秒）
SET key value EX 10

# 仅当键不存在时设置
SET key value NX

# 仅当键存在时设置
SET key value XX
```

### GET - 获取值

```bash
GET key
```

### 自增/自减

```bash
# 自增 1
INCR key

# 自减 1
DECR key

# 自增指定值
INCRBY key 10

# 自减指定值
DECRBY key 5
```

### 字符串操作

```bash
# 追加字符串
APPEND key " world"

# 获取字符串长度
STRLEN key
```

## Hash 类型操作

### HSET - 设置哈希字段

```bash
# 设置单个字段
HSET hash field value

# 设置多个字段
HMSET hash field1 value1 field2 value2
```

### HGET - 获取哈希字段

```bash
# 获取单个字段
HGET hash field

# 获取多个字段
HMGET hash field1 field2

# 获取所有字段和值
HGETALL hash
```

### HDEL - 删除哈希字段

```bash
HDEL hash field1 field2
```

### 其他哈希操作

```bash
# 检查字段是否存在
HEXISTS hash field

# 获取所有字段名
HKEYS hash

# 获取所有字段值
HVALS hash

# 获取字段数量
HLEN hash

# 仅当字段不存在时设置
HSETNX hash field value

# 迭代遍历字段（分页）
HSCAN hash 0 MATCH f* COUNT 10
```

## List 类型操作

### 推入元素

```bash
# 左侧推入（头部）
LPUSH list value1 value2

# 右侧推入（尾部）
RPUSH list value1 value2
```

### 弹出元素

```bash
# 左侧弹出（头部）
LPOP list

# 右侧弹出（尾部）
RPOP list
```

### 获取列表元素

```bash
# 获取列表长度
LLEN list

# 获取指定范围的元素
LRANGE list 0 -1  # 获取所有元素
LRANGE list 0 9   # 获取前 10 个元素
```

### 其他列表操作

```bash
# 获取指定索引的元素
LINDEX list 0

# 设置指定索引的元素
LSET list 0 newValue

# 删除指定值的元素
LREM list 1 value  # 删除第一个匹配的值
LREM list 0 value  # 删除所有匹配的值
```

## Set 类型操作

### SADD - 添加成员

```bash
SADD set member1 member2 member3
```

### SREM - 移除成员

```bash
SREM set member1 member2
```

### SMEMBERS - 获取所有成员

```bash
SMEMBERS set
```

### 其他集合操作

```bash
# 检查成员是否存在
SISMEMBER set member

# 获取成员数量
SCARD set

# 随机获取成员
SRANDMEMBER set
SRANDMEMBER set 3  # 随机获取 3 个成员

# 随机弹出成员
SPOP set
SPOP set 2  # 随机弹出 2 个成员
```

## ZSet 类型操作

### ZADD - 添加有序成员

```bash
# 添加单个成员
ZADD zset 10 member1

# 添加多个成员
ZADD zset 20 member2 30 member3
```

### ZRANGE - 获取范围成员

```bash
# 按分数升序获取
ZRANGE zset 0 -1

# 按分数降序获取
ZREVRANGE zset 0 -1

# 带分数获取
ZRANGE zset 0 -1 WITHSCORES
```

### ZSCORE - 获取成员分数

```bash
ZSCORE zset member
```

### 其他有序集合操作

```bash
# 移除成员
ZREM zset member1 member2

# 获取成员数量
ZCARD zset

# 按分数范围获取
ZRANGEBYSCORE zset 10 20

# 增加成员分数
ZINCRBY zset 5 member
```

## 通用命令

### 键操作

```bash
# 检查键是否存在
EXISTS key

# 删除键
DEL key1 key2

# 获取键类型
TYPE key

# 重命名键
RENAME oldKey newKey

# 仅当新键不存在时重命名
RENAMENX oldKey newKey
```

### 过期时间

```bash
# 设置过期时间（秒）
EXPIRE key 60

# 设置过期时间（毫秒）
PEXPIRE key 60000

# 设置过期时间戳（秒）
EXPIREAT key 1609459200

# 获取剩余生存时间（秒）
TTL key

# 获取剩余生存时间（毫秒）
PTTL key

# 移除过期时间
PERSIST key
```

### 数据库操作

```bash
# 选择数据库
SELECT 0

# 清空当前数据库
FLUSHDB

# 清空所有数据库
FLUSHALL

# 获取当前数据库键数量
DBSIZE

# 随机获取一个键
RANDOMKEY
```

### 服务器操作

```bash
# 获取服务器信息
INFO

# 获取指定部分的信息
INFO memory
INFO stats

# 获取服务器时间
TIME

# 测试连接
PING

# 回显字符串
ECHO "Hello"
```

## 发布订阅

### 订阅频道

```bash
# 订阅单个频道
SUBSCRIBE channel

# 订阅多个频道
SUBSCRIBE channel1 channel2

# 取消订阅
UNSUBSCRIBE channel

# 取消所有订阅
UNSUBSCRIBE
```

### 发布消息

```bash
PUBLISH channel "Hello World"
```

## 下一步

- **[高级功能](./advanced.md)**：探索持久化、Lua 脚本等高级特性
- **[API 文档](../api/)**：查看详细的 API 接口说明
- **[使用示例](./examples.md)**：学习常见场景的代码示例
