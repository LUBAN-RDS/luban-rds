---
title: Lua 脚本
---

# Lua 脚本

Luban-RDS 使用 LuaJ 引擎实现了完整的 Lua 脚本支持，与 Redis 完全兼容。本部分详细介绍了 Lua 脚本的使用方法和 API 参考。

## 脚本内容

- **[使用指南](./usage.md)** - Lua 脚本的基本用法和最佳实践
- **[API 参考](./api.md)** - Redis Lua API 的详细说明

## 核心特性

- **完全兼容 Redis Lua API**：支持 `redis.call()` 和 `redis.pcall()`
- **脚本缓存**：通过 SHA1 值执行缓存的脚本
- **原子性执行**：脚本执行期间不会被其他命令中断
- **沙箱模式**：支持限制 Lua 模块和函数的访问
- **脚本超时**：支持脚本执行超时设置
- **内存限制**：支持脚本大小和返回值大小限制
- **执行统计**：支持脚本执行次数、时长、错误等统计
- **新增函数**：支持 `redis.sha1hex()` 函数

## 支持的命令

| 命令 | 说明 |
|------|------|
| **EVAL** | 执行 Lua 脚本 |
| **EVALSHA** | 执行缓存的 Lua 脚本 |
| **SCRIPT LOAD** | 加载并缓存 Lua 脚本 |
| **SCRIPT EXISTS** | 检查脚本是否已缓存 |
| **SCRIPT FLUSH** | 清除所有脚本缓存 |
| **SCRIPT KILL** | 中断正在运行的脚本 |

## 快速示例

### 基本用法

```bash
# 执行简单脚本
EVAL "return 'Hello'" 0

# 使用键和参数
EVAL "return redis.call('GET', KEYS[1])" 1 name

# 复杂脚本
EVAL "local sum = 0; for i=1, #ARGV do sum = sum + tonumber(ARGV[i]) end; return sum" 0 1 2 3 4 5
```

### 脚本缓存

```bash
# 加载脚本
SCRIPT LOAD "return redis.call('GET', KEYS[1])"
# 返回: "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c"

# 执行缓存的脚本
EVALSHA "a94d8e6e8b8c8e8c8e8c8e8c8e8c8e8c8e8c8e8c" 1 name
```

## 应用场景

- **原子操作**：确保多个命令的原子执行
- **复杂计算**：在服务器端执行复杂计算，减少网络传输
- **自定义命令**：实现 Redis 不支持的自定义命令
- **批量操作**：批量处理数据，提高性能

## 注意事项

1. **脚本执行时间**：避免执行耗时过长的脚本
2. **内存使用**：注意脚本大小和返回值大小限制
3. **安全性**：避免在脚本中执行危险操作
4. **错误处理**：使用 `redis.pcall()` 进行错误处理

## 下一步

- **[使用指南](./usage.md)**：学习 Lua 脚本的基本用法和最佳实践
- **[API 参考](./api.md)**：查看 Redis Lua API 的详细说明
- **[使用示例](../guide/examples.md)**：查看实际应用场景的代码示例
