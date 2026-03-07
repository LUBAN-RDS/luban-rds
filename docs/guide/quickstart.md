---
title: 快速开始
---

# 快速开始

本指南将帮助您在 5 分钟内快速上手 Luban-RDS。

## 环境要求

- **Java 17+**：Luban-RDS 基于 Java 开发
- **Maven 3.6+**：用于构建项目

## 步骤 1：克隆项目

```bash
# 从 GitHub 克隆
git clone https://github.com/LUBAN-RDS/luban-rds.git

# 或从 Gitee 克隆
git clone https://gitee.com/luban-rds/luban-rds.git

cd luban-rds
```

## 步骤 2：构建项目

```bash
# 构建所有模块
mvn clean package

# 跳过测试构建（更快）
mvn clean package -DskipTests
```

构建完成后，可执行文件会生成在 `luban-rds-bin/target/` 目录中。

## 步骤 3：启动服务器

### 使用启动脚本

```bash
# Linux/Mac
cd luban-rds-bin
bash start.sh

# Windows
cd luban-rds-bin
start.bat
```

### 使用 Java 命令

```bash
java -jar luban-rds-bin/target/luban-rds-bin-1.0.0.jar
```

服务器默认监听 **9736** 端口。

## 步骤 4：连接服务器

### 使用 Redis 客户端

```bash
# 使用 redis-cli 连接
redis-cli

# 测试连接
> PING
PONG
```

### 使用 Java 客户端

```java
// 添加依赖
// <dependency>
//     <groupId>com.janeluo</groupId>
//     <artifactId>luban-rds-client</artifactId>
//     <version>1.0.0</version>
// </dependency>

// 创建客户端
RedisClient client = new NettyRedisClient("localhost", 9736);

// 测试连接
String result = client.ping();
System.out.println(result); // 输出: PONG

// 关闭客户端
client.close();
```

## 步骤 5：基本操作

### String 类型

```bash
# 设置值
> SET hello world
OK

# 获取值
> GET hello
"world"

# 递增
> INCR counter
(integer) 1
> INCR counter
(integer) 2
```

### Hash 类型

```bash
# 设置哈希字段
> HSET user:1 name "John"
(integer) 1
> HSET user:1 age 30
(integer) 1

# 获取哈希字段
> HGET user:1 name
"John"
> HGETALL user:1
1) "name"
2) "John"
3) "age"
4) "30"
```

### List 类型

```bash
# 左侧推入元素
> LPUSH fruits apple banana cherry
(integer) 3

# 获取列表范围
> LRANGE fruits 0 -1
1) "cherry"
2) "banana"
3) "apple"

# 右侧弹出元素
> RPOP fruits
"apple"
```

### Set 类型

```bash
# 添加成员
> SADD tags java python javascript
(integer) 3

# 检查成员
> SISMEMBER tags java
(integer) 1

# 获取所有成员
> SMEMBERS tags
1) "python"
2) "java"
3) "javascript"
```

### ZSet 类型

```bash
# 添加有序成员
> ZADD scores 85 "Alice" 92 "Bob" 78 "Charlie"
(integer) 3

# 按分数范围获取
> ZRANGE scores 0 -1 WITHSCORES
1) "Charlie"
2) "78"
3) "Alice"
4) "85"
5) "Bob"
6) "92"
```

## 步骤 6：停止服务器

### 使用 Ctrl+C

在启动服务器的终端中按下 `Ctrl+C` 停止服务。

### 使用 shutdown 命令

```bash
redis-cli shutdown
```

## 下一步

- **[安装指南](./installation.md)**：了解更多安装和配置选项
- **[基本使用](./basic-usage.md)**：学习更多常用命令
- **[高级功能](./advanced.md)**：探索持久化、Lua 脚本等高级特性
- **[API 文档](../api/)**：查看详细的 API 接口说明
