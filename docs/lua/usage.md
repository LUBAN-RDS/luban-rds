---
title: Lua 脚本使用指南
---

# Lua 脚本使用指南

本部分详细介绍了 Luban-RDS 中 Lua 脚本的使用方法，包括基本语法、常见用例和最佳实践。

## 1. 基本概念

### 1.1 什么是 Lua 脚本？

Lua 是一种轻量级的脚本语言，Luban-RDS 使用 LuaJ 引擎实现了对 Lua 脚本的支持。通过 Lua 脚本，您可以：

- **原子执行**：多个命令作为一个原子操作执行
- **减少网络往返**：在服务器端执行复杂逻辑
- **自定义命令**：实现 Redis 不支持的功能

### 1.2 脚本执行环境

Luban-RDS 为 Lua 脚本提供了以下环境：

- **全局变量**：`KEYS`（键数组）和 `ARGV`（参数数组）
- **Redis API**：`redis` 全局对象，提供与 Redis 交互的方法
- **沙箱模式**：可配置的安全限制

## 2. 基本语法

### 2.1 EVAL 命令

**语法**：
```
EVAL script numkeys key [key ...] arg [arg ...]
```

**参数**：
- `script`：Lua 脚本内容
- `numkeys`：键名数量
- `key [key ...]`：键名数组（会被放入 `KEYS` 全局变量）
- `arg [arg ...]`：参数数组（会被放入 `ARGV` 全局变量）

**示例**：
```bash
# 简单脚本
EVAL "return 'Hello, World!'" 0

# 使用参数
EVAL "return ARGV[1] .. ' ' .. ARGV[2]" 0 Hello World

# 使用键
EVAL "return redis.call('GET', KEYS[1])" 1 mykey
 
# 多键多参数完整传递
EVAL "for i = 1, #KEYS do redis.call('SET', KEYS[i], ARGV[i]) end; return #KEYS" 3 key1 key2 key3 v1 v2 v3
```

### 2.2 EVALSHA 命令

**语法**：
```
EVALSHA sha1 numkeys key [key ...] arg [arg ...]
```

**参数**：
- `sha1`：脚本的 SHA1 哈希值
- 其他参数与 `EVAL` 相同

**示例**：
```bash
# 先加载脚本
SCRIPT LOAD "return redis.call('GET', KEYS[1])"
# 返回: "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 使用 SHA1 执行
EVALSHA a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c 1 mykey
```

### 2.3 SCRIPT 命令族

- **SCRIPT LOAD**：加载并缓存脚本
- **SCRIPT EXISTS**：检查脚本是否已缓存
- **SCRIPT FLUSH**：清除所有脚本缓存
- **SCRIPT KILL**：中断正在运行的脚本

**示例**：
```bash
# 加载脚本
SCRIPT LOAD "return 'Hello'"

# 检查脚本是否存在
SCRIPT EXISTS "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 清除脚本缓存
SCRIPT FLUSH

# 中断运行中的脚本
SCRIPT KILL
```

## 3. Redis API

### 3.1 redis.call()

**语法**：
```lua
redis.call(command, arg1, arg2, ...)
```

**功能**：执行 Redis 命令，错误会向上传播

**示例**：
```lua
-- 设置值
redis.call('SET', 'mykey', 'myvalue')

-- 获取值
local value = redis.call('GET', 'mykey')

-- 原子递增
local counter = redis.call('INCR', 'counter')
```

### 3.2 redis.pcall()

**语法**：
```lua
local result = redis.pcall(command, arg1, arg2, ...)
```

**功能**：执行 Redis 命令，错误会被捕获并返回错误表

**示例**：
```lua
-- 安全获取不存在的键
local result = redis.pcall('GET', 'nonexistent')
-- result 为 nil

-- 检查错误
if type(result) == 'table' and result.err then
    -- 处理错误
    return redis.error_reply('Error: ' .. result.err)
end
```

### 3.3 redis.error_reply()

**语法**：
```lua
return redis.error_reply(message)
```

**功能**：返回错误响应

**示例**：
```lua
if not ARGV[1] then
    return redis.error_reply('ERR wrong number of arguments')
end
```

### 3.4 redis.status_reply()

**语法**：
```lua
return redis.status_reply(message)
```

**功能**：返回状态响应

**示例**：
```lua
return redis.status_reply('OK')
```

### 3.5 redis.sha1hex()

**语法**：
```lua
local sha1 = redis.sha1hex(string)
```

**功能**：计算字符串的 SHA1 哈希值

**参数**：
- `string`：要计算哈希值的字符串

**返回值**：
- 字符串的 SHA1 哈希值（十六进制格式）

**示例**：
```lua
local sha1 = redis.sha1hex('Hello World')
return sha1
-- 返回: 2aae6c35c94fcfb415dbe95f408b9ce91ee846ed
```

## 4. 常见用例

### 4.1 原子计数器

**场景**：实现原子递增并返回新值

```lua
-- 原子递增并返回新值
local current = redis.call('GET', KEYS[1])
if current == false then
    current = 0
end
local newValue = tonumber(current) + tonumber(ARGV[1])
redis.call('SET', KEYS[1], newValue)
return newValue
```

**执行**：
```bash
EVAL "local current = redis.call('GET', KEYS[1]); if current == false then current = 0 end; local newValue = tonumber(current) + tonumber(ARGV[1]); redis.call('SET', KEYS[1], newValue); return newValue" 1 counter 5
```

### 4.2 批量设置

**场景**：批量设置多个键值对

```lua
-- 批量设置多个键值对
for i = 1, #KEYS do
    redis.call('SET', KEYS[i], ARGV[i])
end
return #KEYS
```

**执行**：
```bash
EVAL "for i = 1, #KEYS do redis.call('SET', KEYS[i], ARGV[i]) end; return #KEYS" 3 key1 key2 key3 value1 value2 value3
```

## 5. 最佳实践

### 5.1 参数一致性校验
- 在脚本开头校验 `#KEYS` 与 `#ARGV` 的一致性，避免参数不齐导致运行时错误：
```lua
if #KEYS ~= #ARGV then
  return redis.error_reply('ERR number of keys and values must match')
end
```

### 5.2 使用 pcall 保护复杂逻辑
- 对可能失败的命令使用 `redis.pcall` 包裹，结合错误表判断，增强脚本健壮性。

### 4.3 列表操作

**场景**：从列表左侧弹出元素，如果列表为空则返回默认值

```lua
-- 从列表左侧弹出元素，如果列表为空则返回默认值
local value = redis.call('LPOP', KEYS[1])
if value == false then
    value = ARGV[1]
end
return value
```

**执行**：
```bash
EVAL "local value = redis.call('LPOP', KEYS[1]); if value == false then value = ARGV[1] end; return value" 1 mylist default_value
```

### 4.4 哈希操作

**场景**：获取哈希的所有字段并计算总和

```lua
-- 获取哈希的所有字段并计算总和
local hash = redis.call('HGETALL', KEYS[1])
local sum = 0
for i = 1, #hash, 2 do
    sum = sum + tonumber(hash[i+1])
end
return sum
```

**执行**：
```bash
# 先设置哈希
HSET myhash field1 10
HSET myhash field2 20
HSET myhash field3 30

# 计算总和
EVAL "local hash = redis.call('HGETALL', KEYS[1]); local sum = 0; for i = 1, #hash, 2 do sum = sum + tonumber(hash[i+1]) end; return sum" 1 myhash
# 返回: 60
```

### 4.5 集合操作

**场景**：检查多个成员是否都在集合中

```lua
-- 检查多个成员是否都在集合中
local allPresent = true
for i = 1, #ARGV do
    if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 0 then
        allPresent = false
        break
    end
end
return allPresent and 1 or 0
```

**执行**：
```bash
# 先设置集合
SADD myset member1 member2 member3

# 检查成员
EVAL "local allPresent = true; for i = 1, #ARGV do if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 0 then allPresent = false; break end end; return allPresent and 1 or 0" 1 myset member1 member2
# 返回: 1

EVAL "local allPresent = true; for i = 1, #ARGV do if redis.call('SISMEMBER', KEYS[1], ARGV[i]) == 0 then allPresent = false; break end end; return allPresent and 1 or 0" 1 myset member1 member4
# 返回: 0
```

### 4.6 有序集合操作

**场景**：获取有序集合的前三名

```lua
-- 获取有序集合的前三名
local top3 = redis.call('ZREVRANGE', KEYS[1], 0, 2, 'WITHSCORES')
return top3
```

**执行**：
```bash
# 先设置有序集合
ZADD scores 85 "Alice" 92 "Bob" 78 "Charlie" 95 "David" 88 "Eve"

# 获取前三名
EVAL "local top3 = redis.call('ZREVRANGE', KEYS[1], 0, 2, 'WITHSCORES'); return top3" 1 scores
```

## 5. 脚本缓存

### 5.1 基本原理

脚本缓存通过 SHA1 哈希值存储和执行脚本，避免每次都传输完整的脚本内容：

1. **加载脚本**：使用 `SCRIPT LOAD` 命令加载脚本到缓存
2. **获取 SHA1**：命令返回脚本的 SHA1 哈希值
3. **执行脚本**：使用 `EVALSHA` 命令通过 SHA1 执行脚本

### 5.2 使用示例

```bash
# 1. 加载脚本
local sha1 = SCRIPT LOAD "return redis.call('GET', KEYS[1])"
# 返回: "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 2. 检查脚本是否存在
SCRIPT EXISTS "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"
# 返回: 1

# 3. 使用 SHA1 执行
EVALSHA "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c" 1 mykey

# 4. 清除脚本缓存
SCRIPT FLUSH
# 返回: OK
```

### 5.3 优势

- **减少网络传输**：只传输 SHA1 值，不传输完整脚本
- **提高执行速度**：脚本已加载到内存，无需解析
- **更安全**：避免每次都传输脚本内容

## 6. 最佳实践

### 6.1 使用脚本缓存

对于频繁执行的脚本，使用 `SCRIPT LOAD` 和 `EVALSHA` 可以提高性能：

```bash
# 加载脚本
local sha1 = SCRIPT LOAD "return redis.call('GET', KEYS[1])"

# 多次执行
EVALSHA sha1 1 key1
EVALSHA sha1 1 key2
EVALSHA sha1 1 key3
```

### 6.2 错误处理

使用 `redis.pcall()` 进行错误处理：

```lua
local result = redis.pcall('GET', KEYS[1])
if type(result) == 'table' and result.err then
    return redis.error_reply('Error: ' .. result.err)
end
return result
```

### 6.3 参数验证

在脚本开始时验证参数：

```lua
if #KEYS < 1 then
    return redis.error_reply('ERR wrong number of keys')
end

if #ARGV < 1 then
    return redis.error_reply('ERR wrong number of arguments')
end
```

### 6.4 使用局部变量

使用局部变量提高性能：

```lua
local key = KEYS[1]
local value = ARGV[1]
redis.call('SET', key, value)
```

### 6.5 避免长时间运行的脚本

Lua 脚本执行期间会阻塞服务器，避免执行耗时操作：

```lua
-- 不推荐：循环次数过多
for i = 1, 1000000 do
    redis.call('SET', 'key' .. i, 'value' .. i)
end

-- 推荐：分批处理
```

### 6.6 合理使用键和参数

- **键**：使用 `KEYS` 传递所有需要操作的键
- **参数**：使用 `ARGV` 传递其他参数
- **命名规范**：为键和参数使用有意义的名称

## 7. 安全注意事项

### 7.1 Lua 沙箱

Luban-RDS 支持 Lua 沙箱，可以限制脚本的行为：

- **模块限制**：可配置允许使用的 Lua 模块
- **函数限制**：可配置禁止使用的危险函数
- **内存限制**：限制脚本大小和返回值大小
- **执行时间限制**：防止脚本长时间运行

### 7.2 配置示例

在配置文件中添加：

```conf
# Lua 沙箱配置
lua.sandbox-enabled=true
lua.allowed-modules=base,string,table,math
lua.blocked-functions=os.execute,io.open
lua.script-timeout-ms=5000
lua.max-script-bytes=524288
lua.max-return-bytes=524288
```

### 7.3 潜在风险

- **无限循环**：脚本中包含无限循环会阻塞服务器
- **内存泄漏**：创建大量数据结构可能导致内存泄漏
- **拒绝服务**：恶意脚本可能导致服务器资源耗尽

## 8. 性能优化

### 8.1 脚本设计

- **保持脚本简洁**：只包含必要的逻辑
- **避免复杂计算**：将复杂计算移到客户端
- **使用批量操作**：减少 Redis 命令的执行次数

### 8.2 执行优化

- **使用管道**：批量执行脚本，减少网络往返
- **监控执行时间**：定期检查脚本执行时间
- **优化数据结构**：选择合适的数据结构存储数据

### 8.3 内存优化

- **释放临时变量**：不再使用的变量应设为 nil
- **避免大对象**：不要在脚本中创建过大的对象
- **合理设置限制**：根据实际情况调整内存限制

## 9. 调试技巧

### 9.1 基本调试

- **使用 redis.error_reply**：返回调试信息
- **使用 print 函数**：打印调试信息到服务器日志
- **分步测试**：将复杂脚本分解为多个简单脚本

### 9.2 常见错误

| 错误信息 | 可能原因 | 解决方案 |
|---------|---------|--------|
| `ERR Error running script` | 脚本执行出错 | 检查脚本语法和逻辑 |
| `ERR Unknown script` | SHA1 不存在 | 重新加载脚本 |
| `ERR Script killed` | 脚本执行超时 | 优化脚本或增加超时时间 |
| `ERR invalid bulk length` | 参数格式错误 | 检查参数数量和格式 |

### 9.3 调试示例

```lua
-- 调试脚本
local key = KEYS[1]
local value = ARGV[1]

-- 打印调试信息
print('Debug: key = ' .. key)
print('Debug: value = ' .. value)

-- 检查键是否存在
if not redis.call('EXISTS', key) then
    return redis.error_reply('Key does not exist')
end

-- 执行操作
local result = redis.call('SET', key, value)
return result
```

## 10. 总结

Lua 脚本是 Luban-RDS 中强大的功能，可以帮助您：

- **实现原子操作**：确保多个命令的原子执行
- **减少网络往返**：在服务器端执行复杂逻辑
- **自定义命令**：实现 Redis 不支持的功能
- **提高性能**：通过脚本缓存和批量操作提高性能

通过本指南的学习，您应该能够：

1. 编写基本的 Lua 脚本
2. 使用脚本缓存提高性能
3. 实现常见场景的脚本逻辑
4. 遵循最佳实践和安全注意事项

## 11. 下一步

- **[API 参考](./api.md)**：查看 Redis Lua API 的详细说明
- **[使用示例](../guide/examples.md)**：查看实际应用场景的代码示例
- **[核心接口](../api/core.md)**：了解 Luban-RDS 的核心接口
