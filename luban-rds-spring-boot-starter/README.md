# Luban RDS Spring Boot Starter

Luban RDS 的 Spring Boot 3.4.11 自动配置集成模块，提供开箱即用的嵌入式 Redis 服务器支持。

## 快速开始

### 1. 添加依赖

在您的 Spring Boot 项目的 `pom.xml` 中添加依赖：

```xml
<dependency>
    <groupId>com.janeluo</groupId>
    <artifactId>luban-rds-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### 2. 配置

在 `application.yml` 中添加配置：

```yaml
luban:
  rds:
    enabled: true        # 是否启用（默认 true）
    port: 9736          # 服务端口（默认 9736，0 表示随机端口）
    host: localhost     # 监听地址（默认 localhost）
```

### 3. 使用

注入 `RedisClient` 即可使用：

```java
import com.janeluo.luban.rds.client.RedisClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MyService {

    @Autowired
    private RedisClient redisClient;

    public void example() {
        // 设置值
        redisClient.set("key", "value");
        
        // 获取值
        String value = redisClient.get("key");
        
        // 删除键
        redisClient.del("key");
    }
}
```

## 配置选项

### 基础配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.enabled` | Boolean | true | 是否启用自动配置 |
| `luban.rds.port` | Integer | 9736 | 服务端口，0 表示随机分配 |
| `luban.rds.host` | String | localhost | 监听地址 |

### 线程池配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.boss-threads` | Integer | 1 | Boss 线程数（连接接受） |
| `luban.rds.worker-threads` | Integer | 0 | Worker 线程数（I/O 处理），0 表示 CPU * 2 |
| `luban.rds.business-threads` | Integer | 0 | 业务线程数（命令处理），0 表示 CPU |

### 连接配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.max-connections` | Integer | 10000 | 最大连接数 |
| `luban.rds.idle-timeout` | Integer | 300 | 空闲超时（秒） |
| `luban.rds.max-monitor-clients` | Integer | 100 | 最大 MONITOR 客户端数 |
| `luban.rds.password` | String | - | AUTH 密码 |

### 数据库配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.databases` | Integer | 16 | 数据库数量 |
| `luban.rds.statistics-enabled` | Boolean | true | 是否启用统计 |

### 持久化配置

#### RDB 持久化

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.rdb-enabled` | Boolean | false | 是否启用 RDB |
| `luban.rds.rdb-file-path` | String | dump.rdb | RDB 文件路径 |
| `luban.rds.rdb-interval-seconds` | Integer | 60 | 持久化间隔（秒） |

#### AOF 持久化

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.aof-enabled` | Boolean | false | 是否启用 AOF |
| `luban.rds.aof-file-path` | String | appendonly.aof | AOF 文件路径 |
| `luban.rds.aof-sync-strategy` | Enum | everysec | 同步策略（always/everysec/no） |

### Lua 脚本配置

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `luban.rds.lua-script-timeout` | Long | 5000 | 脚本超时（毫秒） |
| `luban.rds.lua-script-max-size` | Integer | 1048576 | 脚本最大大小（字节） |
| `luban.rds.lua-sandbox-enabled` | Boolean | true | 是否启用沙箱 |

## 使用场景

### 内嵌服务器模式（默认）

适用于测试环境或需要本地内存缓存的场景：

```yaml
luban:
  rds:
    enabled: true
    port: 0  # 随机端口
```

### 远程服务器模式

仅连接远程服务器，不启动内嵌服务器：

```yaml
luban:
  rds:
    client:
      enabled: true
    host: remote-server
    port: 9736
```

### 生产环境配置

启用持久化和安全认证：

```yaml
luban:
  rds:
    enabled: true
    port: 9736
    password: your-password
    rdb-enabled: true
    rdb-file-path: /data/redis/dump.rdb
    rdb-interval-seconds: 300
    aof-enabled: true
    aof-sync-strategy: everysec
    max-connections: 10000
```

## 自动配置 Bean

默认注册以下 Bean：

- `EmbeddedRedisServer` - 内嵌服务器实例
- `RedisClient` - Redis 客户端（自动连接到内嵌服务器）
- `NettyRedisServer` - 底层 Netty 服务器

可以通过自定义 Bean 来覆盖默认配置：

```java
@Configuration
public class CustomRdsConfiguration {
    
    @Bean
    @Primary
    public RedisClient customRedisClient() {
        // 创建自定义客户端
        return RedisClientFactory.createClient(config);
    }
}
```

## 禁用自动配置

如果不需要自动配置，可以：

### 方式 1：通过配置禁用

```yaml
luban:
  rds:
    enabled: false
```

### 方式 2：通过注解排除

```java
@SpringBootApplication(exclude = {LubanRdsAutoConfiguration.class})
public class MyApplication {
    public static void main(String[] args) {
        SpringApplication.run(MyApplication.class, args);
    }
}
```

## 版本要求

- Java 17+
- Spring Boot 3.4.11
- Maven 3.6+

## 许可证

本项目基于 Apache License 2.0 开源。
