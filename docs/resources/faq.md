---
title: 常见问题
---

# 常见问题

本部分汇总了使用 Luban-RDS 时的常见问题和解决方案。

## 1. 基本问题

### 1.1 Luban-RDS 是什么？

Luban-RDS 是一个轻量级、高性能的 Java 实现的内存键值存储，兼容 Redis 协议（RESP）。它设计为可嵌入和可扩展的，支持多种数据结构、Lua 脚本、持久化和发布订阅等功能。

### 1.2 Luban-RDS 与 Redis 有什么区别？

- **实现语言**：Luban-RDS 使用 Java 实现，而 Redis 使用 C 语言实现
- **嵌入能力**：Luban-RDS 设计为可嵌入到 Java 应用中
- **扩展能力**：Luban-RDS 提供了更灵活的扩展机制
- **兼容性**：Luban-RDS 完全兼容 Redis 协议，可以直接使用 Redis 客户端

### 1.3 Luban-RDS 支持哪些数据类型？

Luban-RDS 支持以下数据类型：
- **String**：字符串
- **List**：列表
- **Set**：集合
- **Hash**：哈希
- **ZSet**：有序集合

### 1.4 Luban-RDS 的性能如何？

Luban-RDS 基于 Netty 的 NIO 服务器，支持高并发，性能接近原生 Redis。具体性能取决于硬件配置和使用场景。

## 2. 安装与部署

### 2.1 如何安装 Luban-RDS？

- **从源码构建**：克隆代码库，使用 Maven 构建
- **使用预编译包**：从 GitHub Releases 下载预编译的 JAR 文件
- **Docker 部署**：使用 Docker 镜像
- **Spring Boot 集成**：添加依赖并配置

### 2.2 Luban-RDS 的系统要求是什么？

- **操作系统**：Windows 7+, Linux, macOS
- **Java 版本**：Java 17+（必需）
- **内存**：至少 512MB，推荐 2GB+ 
- **CPU**：至少 1 核，推荐 2 核+ 

### 2.3 如何配置 Luban-RDS？

Luban-RDS 支持通过以下方式配置：
- **命令行参数**：启动时通过命令行传递参数
- **配置文件**：使用 `luban-rds.conf` 文件
- **环境变量**：通过环境变量设置配置

### 2.4 如何启动 Luban-RDS？

```bash
# 基本启动
java -jar luban-rds-bin-1.0.0.jar

# 带配置文件启动
java -jar luban-rds-bin-1.0.0.jar --config /path/to/luban-rds.conf
```

## 3. 使用问题

### 3.1 如何连接到 Luban-RDS？

可以使用任何 Redis 客户端连接到 Luban-RDS：

```bash
# 使用 redis-cli
redis-cli -h localhost -p 9736

# 带密码连接
redis-cli -h localhost -p 9736 -a your-password
```

### 3.2 如何使用 Lua 脚本？

使用 `EVAL` 命令执行 Lua 脚本：

```bash
redis-cli -h localhost -p 9736 EVAL "return redis.call('SET', KEYS[1], ARGV[1])" 1 mykey myvalue
```

### 3.3 如何设置键的过期时间？

使用 `EXPIRE` 命令设置键的过期时间：

```bash
redis-cli -h localhost -p 9736 SET mykey myvalue
redis-cli -h localhost -p 9736 EXPIRE mykey 3600  # 1小时后过期
```

### 3.4 如何使用发布订阅功能？

**订阅频道**：
```bash
redis-cli -h localhost -p 9736 SUBSCRIBE channel1
```

**发布消息**：
```bash
redis-cli -h localhost -p 9736 PUBLISH channel1 "Hello World"
```

## 4. 性能问题

### 4.1 如何优化 Luban-RDS 的性能？

- **内存配置**：设置合理的 `maxmemory` 和 `maxmemory-policy`
- **线程配置**：根据 CPU 核心数调整线程池大小
- **持久化策略**：根据业务需求选择合适的持久化策略
- **命令优化**：使用批量命令，避免大键操作
- **连接管理**：使用连接池，减少连接建立开销

### 4.2 如何监控 Luban-RDS 的性能？

- **INFO 命令**：查看服务器信息和统计数据
- **监控工具**：使用 Prometheus + Grafana 监控
- **自定义脚本**：编写监控脚本监控关键指标

### 4.3 内存使用过高怎么办？

- **设置过期时间**：为键设置合理的过期时间
- **调整内存限制**：设置合适的 `maxmemory`
- **优化数据结构**：使用更高效的数据结构
- **淘汰策略**：选择合适的 `maxmemory-policy`

### 4.4 命令执行缓慢怎么办？

- **检查大键**：使用 `--bigkeys` 查找大键
- **优化命令**：避免对大键执行全量操作
- **使用批量命令**：减少网络往返
- **检查持久化**：避免持久化操作影响性能

## 5. 持久化问题

### 5.1 如何配置持久化？

**RDB 配置**：
```conf
save 900 1
save 300 10
save 60 10000
rdbfilename dump.rdb
dir /data
```

**AOF 配置**：
```conf
aof-enabled yes
aof-filename appendonly.aof
aof-sync everysec
```

### 5.2 如何从持久化文件恢复数据？

1. 停止服务
2. 复制持久化文件到数据目录
3. 启动服务，服务会自动加载持久化文件

### 5.3 持久化失败怎么办？

- **检查磁盘空间**：确保磁盘空间充足
- **检查目录权限**：确保有写入权限
- **检查磁盘健康**：使用 `smartctl` 检查磁盘健康状态
- **调整持久化策略**：减少持久化频率

## 6. 安全问题

### 6.1 如何设置密码？

在配置文件中设置：
```conf
requirepass your-secure-password
```

或使用命令设置：
```bash
redis-cli -h localhost -p 9736 CONFIG SET requirepass your-secure-password
```

### 6.2 如何禁用危险命令？

在配置文件中设置：
```conf
rename-command FLUSHALL ""
rename-command FLUSHDB ""
rename-command CONFIG ""
```

### 6.3 如何限制网络访问？

在配置文件中设置：
```conf
bind 127.0.0.1 192.168.1.100
timeout 300
tcp-keepalive 300
```

## 7. 扩展问题

### 7.1 如何添加自定义命令？

1. 实现 `CommandHandler` 接口
2. 注册命令到 `DefaultCommandHandler`
3. 测试命令功能

### 7.2 如何扩展存储后端？

1. 实现 `MemoryStore` 接口
2. 配置服务器使用自定义存储
3. 测试存储功能

### 7.3 如何集成到 Spring Boot 应用？

1. 添加依赖：
   ```xml
   <dependency>
       <groupId>com.janeluo</groupId>
       <artifactId>luban-rds-spring-boot-starter</artifactId>
       <version>1.0.0</version>
   </dependency>
   ```

2. 配置属性：
   ```properties
   spring.luban.rds.port=9736
   spring.luban.rds.host=localhost
   ```

3. 注入使用：
   ```java
   @Autowired
   private StringRedisTemplate redisTemplate;
   ```

## 8. 故障排查

### 8.1 无法连接到服务怎么办？

- **检查服务状态**：确保服务正在运行
- **检查端口**：确保端口未被占用
- **检查防火墙**：确保防火墙未阻止连接
- **检查网络**：确保网络连接正常

### 8.2 服务崩溃怎么办？

- **检查日志**：查找崩溃原因
- **检查内存**：避免内存溢出
- **检查硬件**：确保硬件正常
- **重启服务**：使用备份文件恢复

### 8.3 数据丢失怎么办？

- **使用持久化**：启用 RDB 和 AOF 持久化
- **定期备份**：定期备份持久化文件
- **灾难恢复**：使用备份文件恢复数据

### 8.4 客户端连接数过多怎么办？

- **增加 maxclients**：调整 `maxclients` 配置
- **检查连接**：确保客户端正确关闭连接
- **设置超时**：配置 `timeout` 自动释放空闲连接

## 9. 其他问题

### 9.1 Luban-RDS 支持集群模式吗？

目前 Luban-RDS 主要支持单机模式，集群模式正在规划中。

### 9.2 Luban-RDS 支持主从复制吗？

目前 Luban-RDS 支持基本的主从复制功能，详细配置请参考相关文档。

### 9.3 如何贡献代码？

请参考 [贡献指南](../development/contributing.md) 了解如何贡献代码。

### 9.4 如何报告 bug？

请在 GitHub 仓库的 Issues 页面报告 bug，提供详细的问题描述和复现步骤。

### 9.5 如何获取帮助？

- **文档**：参考本文档
- **GitHub Issues**：在 GitHub 仓库提交问题
- **社区**：加入社区讨论

## 10. 下一步

 - [更新日志](/changelog)：了解版本更新内容
- [路线图](https://github.com/your-org/luban-rds/blob/main/ROADMAP.md)：了解未来规划
- [相关资源](https://github.com/your-org/luban-rds/blob/main/README.md)：获取相关学习资源
