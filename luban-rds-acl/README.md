# Luban RDS ACL 模块

## 概述

Luban RDS ACL 模块提供了完整的访问控制列表功能，完全兼容 Redis 7.2 ACL 规范。支持命令级权限控制、键模式匹配、用户管理和审计日志功能。

## 功能特性

### 1. 用户管理
- 创建、修改、删除用户
- 支持多密码和密码哈希
- 支持 `nopass` 无密码用户
- 默认用户（default）自动创建

### 2. 命令权限控制
- 允许/拒绝特定命令（如 `+GET`, `-FLUSHALL`）
- 允许/拒绝命令类别（如 `+@read`, `-@dangerous`）
- 子命令权限控制（如 `+CONFIG|GET`）
- 支持 20+ 种命令类别

### 3. 键权限控制
- 支持通配符模式（如 `~cache:*`）
- 支持只读键模式（如 `%R~readonly:*`）
- 支持只写键模式（如 `%W~writeonly:*`）
- 支持精确键名匹配

### 4. Pub/Sub 频道权限
- 支持频道模式匹配（如 `&news:*`）
- 控制订阅和发布权限

### 5. 审计日志
- 记录所有认证事件
- 记录权限拒绝事件
- 记录用户管理操作
- 支持日志查询

## 快速开始

### 1. 基本配置

```java
// 创建 ACL 管理器
ACLManager aclManager = new ACLManager();
```

### 2. 创建用户

```java
// 创建管理员用户
aclManager.setUser("admin", "on >adminpass ~* +@all");

// 创建只读用户
aclManager.setUser("readonly", "on >readonlypass ~cache:* +@read +info");

// 创建受限用户
aclManager.setUser("limited", "on >limitedpass ~user:* ~session:* +@read -@dangerous");
```

### 3. 用户认证

```java
// 认证用户
boolean authenticated = aclManager.authenticate("admin", "adminpass");

if (authenticated) {
    System.out.println("Authentication successful");
} else {
    System.out.println("Authentication failed");
}
```

### 4. 权限检查

```java
// 检查命令权限
boolean canExecute = aclManager.checkCommandPermission(
    "readonly", 
    "GET", 
    Collections.emptyList()
);

// 检查键权限
boolean canAccessKey = aclManager.checkKeyPermission(
    "readonly", 
    "cache:user:123", 
    ACLPermissionChecker.KeyAccessType.READ
);

// 综合权限检查
boolean allowed = aclManager.checkPermission(
    "readonly",
    "GET",
    Collections.emptyList(),
    List.of("cache:user:123"),
    ACLPermissionChecker.KeyAccessType.READ
);
```

## ACL 规则语法

### 基本规则

| 规则 | 说明 | 示例 |
|------|------|------|
| `on` | 启用用户 | `on` |
| `off` | 禁用用户 | `off` |
| `>` | 添加密码 | `>mypass` |
| `<` | 移除密码 | `<mypass` |
| `#` | 添加密码哈希 | `#a1b2c3...` |
| `nopass` | 无需密码 | `nopass` |

### 命令权限

| 规则 | 说明 | 示例 |
|------|------|------|
| `+<command>` | 允许命令 | `+GET` |
| `-<command>` | 拒绝命令 | `-FLUSHALL` |
| `+@<category>` | 允许类别 | `+@read` |
| `-@<category>` | 拒绝类别 | `-@dangerous` |
| `+<cmd>\|<sub>` | 允许子命令 | `+CONFIG\|GET` |
| `+@all` | 允许所有命令 | `+@all` |

### 键权限

| 规则 | 说明 | 示例 |
|------|------|------|
| `~<pattern>` | 允许读写 | `~cache:*` |
| `%R~<pattern>` | 只读 | `%R~readonly:*` |
| `%W~<pattern>` | 只写 | `%W~writeonly:*` |
| `allkeys` | 允许所有键 | `allkeys` |
| `resetkeys` | 重置键模式 | `resetkeys` |

### Pub/Sub 权限

| 规则 | 说明 | 示例 |
|------|------|------|
| `&<pattern>` | 允许频道 | `&news:*` |
| `allchannels` | 允许所有频道 | `allchannels` |
| `resetchannels` | 重置频道模式 | `resetchannels` |

## 命令类别

### 常用类别

| 类别 | 说明 | 包含命令示例 |
|------|------|-------------|
| `@read` | 读取命令 | GET, HGET, LRANGE, SMEMBERS |
| `@write` | 写入命令 | SET, HSET, LPUSH, SADD |
| `@admin` | 管理命令 | FLUSHALL, CONFIG, SHUTDOWN |
| `@dangerous` | 危险命令 | FLUSHALL, FLUSHDB, KEYS |
| `@fast` | 快速命令 | GET, SET, INCR |
| `@slow` | 慢速命令 | KEYS, SORT, SMEMBERS |
| `@string` | 字符串命令 | GET, SET, INCR, APPEND |
| `@hash` | 哈希命令 | HGET, HSET, HGETALL |
| `@list` | 列表命令 | LPUSH, RPUSH, LRANGE |
| `@set` | 集合命令 | SADD, SREM, SMEMBERS |
| `@sortedset` | 有序集合命令 | ZADD, ZREM, ZRANGE |
| `@stream` | 流命令 | XADD, XREAD, XGROUP |
| `@pubsub` | Pub/Sub 命令 | PUBLISH, SUBSCRIBE |
| `@transaction` | 事务命令 | MULTI, EXEC, DISCARD |
| `@scripting` | 脚本命令 | EVAL, EVALSHA, SCRIPT |
| `@blocking` | 阻塞命令 | BLPOP, BRPOP, XREAD |

### 查看类别命令

```java
// 获取所有类别
Set<String> categories = aclManager.getCommandCategories();

// 获取类别中的命令
Set<String> readCommands = aclManager.getCategoryCommands("@read");
```

## 使用示例

### 示例 1：创建只读用户

```java
// 只能读取 cache:* 前缀的键
aclManager.setUser("cache_reader", 
    "on >reader123 ~cache:* +@read +ping +info");

// 验证
aclManager.authenticate("cache_reader", "reader123"); // true
aclManager.checkCommandPermission("cache_reader", "GET", List.of()); // true
aclManager.checkCommandPermission("cache_reader", "SET", List.of()); // false
aclManager.checkKeyPermission("cache_reader", "cache:user:1", 
    KeyAccessType.READ); // true
aclManager.checkKeyPermission("cache_reader", "user:1", 
    KeyAccessType.READ); // false
```

### 示例 2：创建写入用户

```java
// 可以写入特定前缀的键，不能读取
aclManager.setUser("data_writer", 
    "on >writer456 %W~data:* %R~status:* +@write +@read +ping");

// 验证
aclManager.checkKeyPermission("data_writer", "data:entry:1", 
    KeyAccessType.WRITE); // true
aclManager.checkKeyPermission("data_writer", "data:entry:1", 
    KeyAccessType.READ); // false
aclManager.checkKeyPermission("data_writer", "status:health", 
    KeyAccessType.READ); // true
```

### 示例 3：创建管理员用户

```java
// 拥有所有权限，但不能执行危险命令
aclManager.setUser("safe_admin", 
    "on >admin789 ~* +@all -@dangerous +info +client +role");

// 验证
aclManager.checkCommandPermission("safe_admin", "GET", List.of()); // true
aclManager.checkCommandPermission("safe_admin", "SET", List.of()); // true
aclManager.checkCommandPermission("safe_admin", "FLUSHALL", List.of()); // false
aclManager.checkCommandPermission("safe_admin", "KEYS", List.of()); // false
```

### 示例 4：创建子命令权限用户

```java
// 只能执行 CONFIG GET，不能执行 CONFIG SET
aclManager.setUser("config_reader", 
    "on >config123 ~* +config|get +info");

// 验证
aclManager.checkCommandPermission("config_reader", "CONFIG", 
    List.of("GET", "maxmemory")); // true
aclManager.checkCommandPermission("config_reader", "CONFIG", 
    List.of("SET", "maxmemory", "100mb")); // false
```

### 示例 5：生成强密码

```java
// 生成 256 位（64 个十六进制字符）密码
String password = aclManager.generatePassword();

// 生成 128 位密码
String shortPassword = aclManager.generatePassword(128);

// 使用生成的密码
aclManager.setUser("secure_user", "on >" + password + " ~* +@all");
```

## Redis 兼容的 ACL 命令

### ACL SETUSER

创建或修改用户。

```
ACL SETUSER username [rules...]
```

示例：
```
ACL SETUSER john on >mypassword ~* +@all -@dangerous
```

### ACL DELUSER

删除用户。

```
ACL DELUSER username [username ...]
```

示例：
```
ACL DELUSER john
```

### ACL GETUSER

获取用户详细信息。

```
ACL GETUSER username
```

### ACL LIST

列出所有用户规则。

```
ACL LIST
```

### ACL CAT

列出命令类别或类别中的命令。

```
ACL CAT [category]
```

示例：
```
ACL CAT           # 列出所有类别
ACL CAT @read     # 列出 @read 类别中的命令
```

### ACL GENPASS

生成强密码。

```
ACL GENPASS [bits]
```

示例：
```
ACL GENPASS       # 生成 256 位密码
ACL GENPASS 128   # 生成 128 位密码
```

## 审计日志

### 查看审计日志

```java
// 获取所有审计日志
List<ACLEvent> events = aclManager.getAuditLogger().getAllEvents();

// 获取特定用户的日志
List<ACLEvent> userEvents = aclManager.getAuditLogger()
    .getEventsByUser("john");

// 获取特定类型的日志
List<ACLEvent> authEvents = aclManager.getAuditLogger()
    .getEventsByType(ACLEventType.AUTH_FAILURE);
```

### 审计事件类型

| 类型 | 说明 |
|------|------|
| `AUTH_SUCCESS` | 认证成功 |
| `AUTH_FAILURE` | 认证失败 |
| `PERMISSION_DENIED` | 权限拒绝 |
| `USER_CREATED` | 用户创建 |
| `USER_DELETED` | 用户删除 |
| `USER_MODIFIED` | 用户修改 |

## 性能优化

### 1. 权限缓存

ACL 系统使用缓存机制优化权限检查性能：
- 命令类别映射缓存
- 模式匹配结果缓存

### 2. 并发支持

所有核心组件都使用并发安全的数据结构：
- `ConcurrentHashMap` 用于用户存储
- `ConcurrentLinkedQueue` 用于审计日志

## 最佳实践

### 1. 最小权限原则

为用户分配完成任务所需的最小权限集：

```java
// 不推荐：给予所有权限
aclManager.setUser("worker", "on >pass ~* +@all");

// 推荐：只给予必要的权限
aclManager.setUser("worker", "on >pass ~job:* +@read +lpush +rpop");
```

### 2. 使用命令类别

使用命令类别简化权限配置：

```java
// 不推荐：逐个指定命令
aclManager.setUser("reader", "on >pass ~* +get +hget +lrange +smembers +zrange");

// 推荐：使用命令类别
aclManager.setUser("reader", "on >pass ~* +@read");
```

### 3. 使用键模式

精确控制键访问范围：

```java
// 不推荐：允许所有键
aclManager.setUser("cache", "on >pass ~* +@all");

// 推荐：限制键范围
aclManager.setUser("cache", "on >pass ~cache:* +@read +@write");
```

### 4. 定期审计

定期检查审计日志，发现安全问题：

```java
// 检查认证失败
List<ACLEvent> failures = aclManager.getAuditLogger()
    .getEventsByType(ACLEventType.AUTH_FAILURE);

// 检查权限拒绝
List<ACLEvent> denied = aclManager.getAuditLogger()
    .getEventsByType(ACLEventType.PERMISSION_DENIED);
```

## 注意事项

1. **默认用户**：系统启动时自动创建 `default` 用户，拥有所有权限且无密码
2. **用户名规范**：用户名不能包含空格、换行符等特殊字符
3. **密码安全**：建议使用 `ACL GENPASS` 生成强密码
4. **权限叠加**：规则按从左到右顺序应用，后续规则可能覆盖前面的规则
5. **性能影响**：大量键模式可能影响权限检查性能，建议控制在合理范围内

## 兼容性

- **Redis 协议**：完全兼容 Redis 7.2 ACL 规范
- **Jedis 3.x+**：完全兼容
- **Redisson 3.x+**：完全兼容
- **标准 Redis 客户端**：完全兼容

## 版本历史

### v1.0.0 (2026-03-23)
- 初始版本发布
- 支持 Redis 7.2 ACL 规范
- 完整的用户管理功能
- 命令级权限控制
- 键模式权限控制
- Pub/Sub 频道权限
- 审计日志功能
