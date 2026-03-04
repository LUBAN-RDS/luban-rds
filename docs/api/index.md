---
title: API 文档
---

# API 文档

本部分提供了 Luban-RDS 的详细 API 文档，包括核心接口、命令列表和协议说明。

## API 内容

- **[核心接口](./core.md)** - MemoryStore 等核心接口的详细说明
- **[命令列表](./commands.md)** - 支持的所有 Redis 命令
- **[协议说明](./protocol.md)** - RESP 协议的详细解析

## 核心模块

Luban-RDS 采用 Maven 多模块结构，主要模块包括：

| 模块 | 职责 | 关键类 |
|------|------|--------|
| **luban-rds-core** | 核心业务逻辑 | `CommandHandler`, `MemoryStore` |
| **luban-rds-protocol** | 协议解析 | `RedisProtocolParser`, `Command` |
| **luban-rds-server** | 网络服务 | `NettyRedisServer`, `RedisServerHandler` |
| **luban-rds-persistence** | 持久化 | `PersistService`, `RdbPersistService` |
| **luban-rds-client** | 客户端 | `RedisClient`, `NettyRedisClient` |

## 版本信息

- **当前版本**：1.0.0-SNAPSHOT
- **Java 版本要求**：Java 17+
- **兼容 Redis 版本**：6.0+

## 示例代码

### Java 客户端示例

```java
// 创建客户端
RedisClient client = new NettyRedisClient("localhost", 9736);

// 执行命令
String result = client.ping();
System.out.println(result); // 输出: PONG

// 存储数据
client.set("key", "value");
String value = client.get("key");
System.out.println(value); // 输出: value

// 关闭客户端
client.close();
```

### Spring Boot 集成示例

```java
// 添加依赖
// <dependency>
//     <groupId>com.janeluoluo</groupId>
//     <artifactId>luban-rds-spring-boot-starter</artifactId>
//     <version>1.0.0</version>
// </dependency>

// 使用 RedisTemplate
@Autowired
private StringRedisTemplate redisTemplate;

public void setValue(String key, String value) {
    redisTemplate.opsForValue().set(key, value);
}

public String getValue(String key) {
    return redisTemplate.opsForValue().get(key);
}
```

## 下一步

- **[核心接口](./core.md)**：了解 MemoryStore 等核心接口的详细定义
- **[命令列表](./commands.md)**：查看支持的所有 Redis 命令
- **[协议说明](./protocol.md)**：深入了解 RESP 协议的工作原理
