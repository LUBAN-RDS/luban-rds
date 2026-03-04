---
title: Lua 脚本 API 参考
---

# Lua 脚本 API 参考

本部分详细介绍了 Luban-RDS 中 Lua 脚本可用的 API，包括函数签名、参数说明和使用示例。

## 1. 全局对象

### 1.1 redis

`redis` 是一个全局对象，提供了与 Redis 交互的方法。

```lua
redis.call()
redis.pcall()
redis.error_reply()
redis.status_reply()
redis.sha1hex()
```

### 1.2 KEYS

`KEYS` 是一个全局数组，包含了脚本执行时传递的键名。

**索引**：从 1 开始
**类型**：字符串数组

**示例**：
```lua
-- 访问第一个键
local key1 = KEYS[1]

-- 遍历所有键
for i = 1, #KEYS do
    local key = KEYS[i]
    -- 处理键
end
```

> 说明：脚本接收的 KEYS 与客户端请求保持一致，参数完整传递，索引从 1 开始。

### 1.3 ARGV

`ARGV` 是一个全局数组，包含了脚本执行时传递的参数。

**索引**：从 1 开始
**类型**：字符串数组

**示例**：
```lua
-- 访问第一个参数
local arg1 = ARGV[1]

-- 遍历所有参数
for i = 1, #ARGV do
    local arg = ARGV[i]
    -- 处理参数
end
```

> 说明：脚本接收的 ARGV 与客户端请求保持一致，参数完整传递，索引从 1 开始。

## 2. redis.call()

**签名**：
```lua
redis.call(command, arg1, arg2, ...)
```

**功能**：
执行 Redis 命令，如果命令失败则抛出错误。

**参数**：
- `command`：Redis 命令名称（字符串）
- `arg1, arg2, ...`：命令参数

**返回值**：
- 命令执行结果，类型取决于命令

**示例**：
```lua
-- 设置值
redis.call('SET', 'mykey', 'myvalue')

-- 获取值
local value = redis.call('GET', 'mykey')

-- 原子递增
local counter = redis.call('INCR', 'counter')

-- 执行多个命令
redis.call('HSET', 'user:1', 'name', 'John')
redis.call('HSET', 'user:1', 'age', '30')
local user = redis.call('HGETALL', 'user:1')
```

**多键多参数传递示例**：
```lua
for i = 1, #KEYS do
  redis.call('SET', KEYS[i], ARGV[i])
end
return #KEYS
```

**错误处理**：
如果命令执行失败，`redis.call()` 会抛出错误，导致整个脚本执行失败。

## 3. redis.pcall()

**签名**：
```lua
local result = redis.pcall(command, arg1, arg2, ...)
```

**功能**：
执行 Redis 命令，如果命令失败则返回错误表，不会抛出错误。

**参数**：
- `command`：Redis 命令名称（字符串）
- `arg1, arg2, ...`：命令参数

**返回值**：
- 成功：命令执行结果
- 失败：错误表，包含 `err` 字段

**示例**：
```lua
-- 安全获取不存在的键
local result = redis.pcall('GET', 'nonexistent')
-- result 为 nil

-- 检查错误
local result = redis.pcall('GET', 'non_existent_key')
if type(result) == 'table' and result.err then
    -- 处理错误
    return redis.error_reply('Error: ' .. result.err)
end

-- 安全删除可能不存在的键
local result = redis.pcall('DEL', 'maybe_exists')
if type(result) == 'table' and result.err then
    -- 忽略错误，继续执行
    return 0
else
    return result
end
```

**参数完整传递建议**：
- 在复杂场景中使用 `pcall` 包裹命令，结合对 `#KEYS` 与 `#ARGV` 的一致性校验，确保参数完整与安全。

**错误表格式**：
```lua
{
    err = "Error message"
}
```

## 4. redis.error_reply()

**签名**：
```lua
return redis.error_reply(message)
```

**功能**：
返回错误响应，脚本执行会被视为失败。

**参数**：
- `message`：错误信息（字符串）

**返回值**：
- 错误响应对象

**示例**：
```lua
if not ARGV[1] then
    return redis.error_reply('ERR wrong number of arguments')
end

if #KEYS < 1 then
    return redis.error_reply('ERR wrong number of keys')
end

local result = redis.pcall('GET', KEYS[1])
if type(result) == 'table' and result.err then
    return redis.error_reply('Error getting key: ' .. result.err)
end
```

## 5. redis.status_reply()

**签名**：
```lua
return redis.status_reply(message)
```

**功能**：
返回状态响应，脚本执行会被视为成功。

**参数**：
- `message`：状态信息（字符串）

**返回值**：
- 状态响应对象

**示例**：
```lua
-- 简单成功响应
return redis.status_reply('OK')

-- 自定义状态信息
return redis.status_reply('Processed ' .. #ARGV .. ' items')

-- 结合逻辑使用
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('DEL', KEYS[1])
    return redis.status_reply('Key deleted')
else
    return redis.status_reply('Key not found')
end
```

## 6. redis.sha1hex()

**签名**：
```lua
local sha1 = redis.sha1hex(string)
```

**功能**：
计算字符串的 SHA1 哈希值。

**参数**：
- `string`：要计算哈希值的字符串

**返回值**：
- 字符串的 SHA1 哈希值（十六进制格式）

**示例**：
```lua
-- 计算简单字符串的哈希
local sha1 = redis.sha1hex('Hello World')
return sha1
-- 返回: 2aae6c35c94fcfb415dbe95f408b9ce91ee846ed

-- 计算键的哈希
local keyHash = redis.sha1hex(KEYS[1])
return keyHash

-- 计算多个参数的哈希
local combined = table.concat(ARGV, ':')
local combinedHash = redis.sha1hex(combined)
return combinedHash
```

## 7. 支持的 Redis 命令

Lua 脚本中可以使用几乎所有的 Redis 命令，包括：

### 7.1 字符串命令
- `SET`, `GET`, `INCR`, `DECR`, `INCRBY`, `DECRBY`, `APPEND`, `STRLEN`

### 7.2 哈希命令
- `HSET`, `HGET`, `HGETALL`, `HDEL`, `HEXISTS`, `HKEYS`, `HVALS`, `HLEN`, `HMSET`, `HMGET`

### 7.3 列表命令
- `LPUSH`, `RPUSH`, `LPOP`, `RPOP`, `LLEN`, `LRANGE`, `LINDEX`, `LSET`, `LREM`

### 7.4 集合命令
- `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`, `SCARD`, `SRANDMEMBER`, `SPOP`

### 7.5 有序集合命令
- `ZADD`, `ZRANGE`, `ZSCORE`, `ZREM`, `ZCARD`, `ZREVRANGE`, `ZRANGEBYSCORE`, `ZINCRBY`

### 7.6 通用命令
- `EXISTS`, `DEL`, `EXPIRE`, `TTL`, `FLUSHALL`, `FLUSHDB`, `TYPE`, `ECHO`, `SELECT`, `INFO`, `SCAN`, `DBSIZE`, `TIME`

### 7.7 发布订阅命令
- `PUBLISH`（注意：`SUBSCRIBE` 和 `UNSUBSCRIBE` 在脚本中不支持）

### 7.8 事务命令
- `MULTI`, `EXEC`, `DISCARD`, `WATCH`, `UNWATCH`（在脚本中使用有限制）

## 8. 数据类型处理

### 8.1 字符串

**示例**：
```lua
-- 设置字符串
redis.call('SET', 'mykey', 'Hello')

-- 获取字符串
local value = redis.call('GET', 'mykey')

-- 字符串操作
local length = string.len(value)
local upper = string.upper(value)
local combined = value .. ' World'
```

### 8.2 数字

**示例**：
```lua
-- 递增
local counter = redis.call('INCR', 'counter')

-- 转换字符串为数字
local num = tonumber(ARGV[1])

-- 数学运算
local sum = num + tonumber(ARGV[2])
local product = num * 2
local average = sum / #ARGV
```

### 8.3 哈希表

**示例**：
```lua
-- 设置哈希字段
redis.call('HSET', 'user:1', 'name', 'John')
redis.call('HSET', 'user:1', 'age', '30')

-- 获取哈希表
local user = redis.call('HGETALL', 'user:1')

-- 遍历哈希表
local userMap = {}
for i = 1, #user, 2 do
    local field = user[i]
    local value = user[i+1]
    userMap[field] = value
end

-- 访问特定字段
local userName = userMap['name']
```

### 8.4 列表

**示例**：
```lua
-- 添加元素到列表
redis.call('LPUSH', 'mylist', 'a', 'b', 'c')

-- 获取列表
local list = redis.call('LRANGE', 'mylist', 0, -1)

-- 遍历列表
for i, value in ipairs(list) do
    print('Element ' .. i .. ': ' .. value)
end

-- 列表长度
local length = #list
```

### 8.5 集合

**示例**：
```lua
-- 添加元素到集合
redis.call('SADD', 'myset', 'a', 'b', 'c')

-- 获取集合
local set = redis.call('SMEMBERS', 'myset')

-- 遍历集合
for _, value in ipairs(set) do
    print('Member: ' .. value)
end

-- 检查成员是否存在
local exists = redis.call('SISMEMBER', 'myset', 'a')
```

### 8.6 有序集合

**示例**：
```lua
-- 添加元素到有序集合
redis.call('ZADD', 'myzset', 1, 'a', 2, 'b', 3, 'c')

-- 获取有序集合
local zset = redis.call('ZRANGE', 'myzset', 0, -1, 'WITHSCORES')

-- 遍历有序集合
for i = 1, #zset, 2 do
    local member = zset[i]
    local score = zset[i+1]
    print('Member: ' .. member .. ', Score: ' .. score)
end

-- 获取成员分数
local score = redis.call('ZSCORE', 'myzset', 'b')
```

## 9. 脚本返回值

### 9.1 基本类型

- **字符串**：直接返回字符串
- **数字**：直接返回数字
- **布尔值**：Lua 的 `true` 会被转换为 Redis 的整数 `1`，`false` 会被转换为 Redis 的 `nil`
- **nil**：会被转换为 Redis 的 `nil`

**示例**：
```lua
-- 返回字符串
return 'Hello'

-- 返回数字
return 42

-- 返回布尔值
return true  -- 会被转换为 (integer) 1
return false -- 会被转换为 (nil)

-- 返回 nil
return nil   -- 会被转换为 (nil)
```

### 9.2 复合类型

- **表**：会被转换为 Redis 的数组
- **嵌套表**：会被转换为 Redis 的嵌套数组

**示例**：
```lua
-- 返回简单表
return {1, 2, 3}
-- 会被转换为 1) "1" 2) "2" 3) "3"

-- 返回混合类型表
return {'Hello', 42, true}
-- 会被转换为 1) "Hello" 2) "42" 3) "1"

-- 返回嵌套表
return {{1, 2}, {3, 4}}
-- 会被转换为 1) 1) "1" 2) "2" 2) 1) "3" 2) "4"

-- 返回哈希表样式的表
local user = {name = 'John', age = 30}
return user
-- 会被转换为 1) "name" 2) "John" 3) "age" 4) "30"
```

### 9.3 特殊返回值

- **redis.error_reply()**：返回错误响应
- **redis.status_reply()**：返回状态响应

**示例**：
```lua
-- 返回错误
return redis.error_reply('Something went wrong')

-- 返回状态
return redis.status_reply('OK')
```

## 10. 常见用例

### 10.1 原子操作

**场景**：实现原子递增并返回新值

```lua
local key = KEYS[1]
local increment = tonumber(ARGV[1]) or 1

local current = redis.call('GET', key)
if current == false then
    current = 0
else
    current = tonumber(current)
end

local newValue = current + increment
redis.call('SET', key, newValue)

return newValue
```

### 10.2 批量操作

**场景**：批量设置多个键值对

```lua
if #KEYS ~= #ARGV then
    return redis.error_reply('ERR number of keys and values must match')
end

for i = 1, #KEYS do
    local key = KEYS[i]
    local value = ARGV[i]
    redis.call('SET', key, value)
end

return #KEYS
```

### 10.3 条件操作

**场景**：只有当键不存在时才设置值

```lua
local key = KEYS[1]
local value = ARGV[1]

if redis.call('EXISTS', key) == 0 then
    redis.call('SET', key, value)
    return 1
else
    return 0
end
```

### 10.4 复杂查询

**场景**：获取多个哈希表的特定字段

```lua
local result = {}

for i, key in ipairs(KEYS) do
    local field = ARGV[i]
    local value = redis.call('HGET', key, field)
    table.insert(result, key)
    table.insert(result, value)
end

return result
```

## 11. 性能考虑

### 11.1 命令执行

- **减少命令数量**：尽量减少 Redis 命令的执行次数
- **使用批量命令**：如 `HMSET`、`MSET` 等
- **避免大键操作**：操作大键会阻塞服务器

### 11.2 内存使用

- **释放临时变量**：不再使用的变量应设为 nil
- **避免大对象**：不要在脚本中创建过大的表
- **合理使用局部变量**：局部变量比全局变量更快

### 11.3 执行时间

- **保持脚本简洁**：脚本执行时间不应超过配置的超时时间
- **避免复杂计算**：将复杂计算移到客户端
- **避免无限循环**：确保脚本有明确的终止条件

## 12. 安全考虑

### 12.1 沙箱限制

- **模块限制**：某些 Lua 模块可能被禁用
- **函数限制**：某些危险函数可能被禁用
- **内存限制**：脚本大小和返回值大小可能被限制
- **执行时间限制**：脚本执行时间可能被限制

### 12.2 潜在风险

- **无限循环**：脚本中包含无限循环会阻塞服务器
- **内存泄漏**：创建大量数据结构可能导致内存泄漏
- **拒绝服务**：恶意脚本可能导致服务器资源耗尽

### 12.3 最佳实践

- **验证输入**：验证所有输入参数
- **限制执行时间**：设置合理的脚本超时时间
- **监控脚本**：定期检查脚本执行情况
- **使用白名单**：只允许执行预定义的脚本

## 13. 总结

Luban-RDS 提供了完整的 Lua 脚本 API，支持：

- **redis.call()**：执行 Redis 命令，错误会向上传播
- **redis.pcall()**：执行 Redis 命令，错误会被捕获
- **redis.error_reply()**：返回错误响应
- **redis.status_reply()**：返回状态响应
- **redis.sha1hex()**：计算字符串的 SHA1 哈希值

通过这些 API，您可以在 Lua 脚本中实现复杂的业务逻辑，享受原子执行和减少网络往返的好处。

## 14. 下一步

- **[使用指南](./usage.md)**：学习 Lua 脚本的基本用法和最佳实践
- **[使用示例](../guide/examples.md)**：查看实际应用场景的代码示例
- **[核心接口](../api/core.md)**：了解 Luban-RDS 的核心接口
