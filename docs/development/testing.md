---
title: 测试指南
---

# 测试指南

本指南详细说明了 Luban-RDS 项目的测试方法、测试框架和最佳实践，帮助开发者编写高质量的测试代码。

## 1. 测试概述

### 1.1 测试类型

- **单元测试**：测试单个类或方法的功能，隔离测试对象
- **集成测试**：测试多个组件之间的交互，验证集成点
- **端到端测试**：测试整个系统的功能，模拟真实用户场景
- **性能测试**：测试系统的性能指标，如响应时间、吞吐量
- **压力测试**：测试系统在高负载下的稳定性

### 1.2 测试目标

- **验证功能**：确保代码实现了预期的功能
- **发现 bug**：在早期发现并修复问题
- **防止回归**：确保修改代码后不会破坏现有功能
- **提高代码质量**：通过测试驱动开发（TDD）提高代码质量
- **文档化功能**：测试用例作为代码功能的文档

### 1.3 测试覆盖

- **语句覆盖**：测试执行了代码中的每一条语句
- **分支覆盖**：测试执行了代码中的每一个分支
- **路径覆盖**：测试执行了代码中的每一条路径
- **条件覆盖**：测试执行了代码中的每一个条件

## 2. 测试框架

### 2.1 JUnit 5

JUnit 5 是 Luban-RDS 项目使用的主要测试框架，它由三个核心模块组成：

- **JUnit Platform**：测试执行的基础平台
- **JUnit Jupiter**：新的编程模型和扩展模型
- **JUnit Vintage**：兼容 JUnit 3 和 JUnit 4 的测试

**依赖配置**：

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-api</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter-engine</artifactId>
    <version>5.9.1</version>
    <scope>test</scope>
</dependency>
```

### 2.2 Mockito

Mockito 是一个用于创建模拟对象的框架，用于隔离测试对象，避免依赖外部资源。

**依赖配置**：

```xml
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-core</artifactId>
    <version>4.8.1</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.mockito</groupId>
    <artifactId>mockito-junit-jupiter</artifactId>
    <version>4.8.1</version>
    <scope>test</scope>
</dependency>
```

### 2.3 Hamcrest

Hamcrest 是一个匹配器库，用于编写更具可读性的断言。

**依赖配置**：

```xml
<dependency>
    <groupId>org.hamcrest</groupId>
    <artifactId>hamcrest</artifactId>
    <version>2.2</version>
    <scope>test</scope>
</dependency>
```

### 2.4 AssertJ

AssertJ 是一个流畅的断言库，提供了更丰富的断言方法。

**依赖配置**：

```xml
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>3.24.2</version>
    <scope>test</scope>
</dependency>
```

### 2.5 TestContainers

TestContainers 是一个用于容器化测试的框架，用于测试依赖外部服务的代码。

**依赖配置**：

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>1.17.6</version>
    <scope>test</scope>
</dependency>
```

## 3. 单元测试

### 3.1 编写单元测试

**测试类命名**：使用被测试类名加 `Test` 后缀
**测试方法命名**：使用 `test` 前缀，后跟测试场景描述
**测试包名**：与被测试类的包名相同

**示例**：

```java
package com.janeluo.luban.rds.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LuaCommandHandlerTest {

    @Test
    public void testEvalScript() {
        // 模拟 MemoryStore
        MemoryStore memoryStore = mock(MemoryStore.class);
        when(memoryStore.set("key", "value", 0)).thenReturn("OK");

        // 创建 LuaCommandHandler
        LuaCommandHandler handler = new LuaCommandHandler(memoryStore);

        // 测试 EVAL 命令
        String script = "return redis.call('SET', KEYS[1], ARGV[1])";
        List<String> keys = Collections.singletonList("key");
        List<String> args = Collections.singletonList("value");

        Object result = handler.eval(script, keys, args);

        // 验证结果
        assertEquals("OK", result);
    }
}
```

### 3.2 测试生命周期

JUnit 5 提供了以下生命周期注解：

- `@BeforeAll`：在所有测试方法之前执行，静态方法
- `@BeforeEach`：在每个测试方法之前执行
- `@Test`：测试方法
- `@AfterEach`：在每个测试方法之后执行
- `@AfterAll`：在所有测试方法之后执行，静态方法

**示例**：

```java
public class MemoryStoreTest {
    
    private MemoryStore memoryStore;
    
    @BeforeEach
    void setUp() {
        // 初始化 MemoryStore
        memoryStore = new DefaultMemoryStore();
    }
    
    @Test
    void testSetAndGet() {
        // 测试 SET 和 GET 命令
        memoryStore.set("key", "value", 0);
        String result = memoryStore.get("key");
        assertEquals("value", result);
    }
    
    @AfterEach
    void tearDown() {
        // 清理资源
        memoryStore.clear();
    }
}
```

### 3.3 测试异常

使用 `assertThrows` 测试异常：

```java
@Test
void testSetWithNullKey() {
    // 测试空键异常
    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
        memoryStore.set(null, "value", 0);
    });
    assertEquals("Key cannot be null", exception.getMessage());
}
```

### 3.4 参数化测试

使用 `@ParameterizedTest` 进行参数化测试：

```java
@ParameterizedTest
@ValueSource(strings = {"key1", "key2", "key3"})
void testSetWithDifferentKeys(String key) {
    // 测试不同键的 SET 操作
    memoryStore.set(key, "value", 0);
    String result = memoryStore.get(key);
    assertEquals("value", result);
}
```

## 4. 集成测试

### 4.1 编写集成测试

集成测试测试多个组件之间的交互，通常需要启动完整的应用上下文。

**示例**：

```java
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@SpringBootTest
public class RedisServerIntegrationTest {
    
    @Autowired
    private RedisClient redisClient;
    
    @Test
    void testServerConnection() {
        // 测试服务器连接
        String result = redisClient.set("key", "value");
        assertEquals("OK", result);
        
        String value = redisClient.get("key");
        assertEquals("value", value);
    }
}
```

### 4.2 测试配置

使用 `@TestConfiguration` 配置测试环境：

```java
@TestConfiguration
public class TestConfig {
    
    @Bean
    public RedisClient redisClient() {
        return new NettyRedisClient("localhost", 9736);
    }
}
```

### 4.3 测试数据库

使用 TestContainers 测试数据库连接：

```java
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
public class RedisClientTest {
    
    @Container
    private static final GenericContainer<?> redisContainer = new GenericContainer<>("redis:6.2")
            .withExposedPorts(9736);
    
    @Test
    void testRedisConnection() {
        // 获取容器端口
        int port = redisContainer.getMappedPort(9736);
        
        // 创建客户端
        RedisClient client = new NettyRedisClient("localhost", port);
        
        // 测试连接
        String result = client.ping();
        assertEquals("PONG", result);
    }
}
```

## 5. 端到端测试

### 5.1 编写端到端测试

端到端测试测试整个系统的功能，模拟真实用户场景。

**示例**：

```java
public class EndToEndTest {
    
    private RedisServer server;
    private RedisClient client;
    
    @BeforeEach
    void setUp() {
        // 启动服务器
        server = new NettyRedisServer(9736);
        server.start();
        
        // 创建客户端
        client = new NettyRedisClient("localhost", 9736);
    }
    
    @Test
    void testFullWorkflow() {
        // 测试完整的工作流程
        
        // 1. 设置值
        assertEquals("OK", client.set("user:1:name", "Alice"));
        assertEquals("OK", client.set("user:1:age", "30"));
        
        // 2. 获取值
        assertEquals("Alice", client.get("user:1:name"));
        assertEquals("30", client.get("user:1:age"));
        
        // 3. 使用哈希
        assertEquals("OK", client.hset("user:1", "name", "Alice"));
        assertEquals("OK", client.hset("user:1", "age", "30"));
        assertEquals("Alice", client.hget("user:1", "name"));
        
        // 4. 使用列表
        assertEquals(1L, client.lpush("users", "Alice"));
        assertEquals(2L, client.lpush("users", "Bob"));
        assertEquals("Bob", client.lpop("users"));
    }
    
    @AfterEach
    void tearDown() {
        // 关闭客户端和服务器
        client.close();
        server.stop();
    }
}
```

### 5.2 测试场景

常见的端到端测试场景：

- **基本命令**：测试 SET、GET、DEL 等基本命令
- **数据结构**：测试 List、Set、Hash、ZSet 等数据结构
- **过期时间**：测试 EXPIRE、TTL 等命令
- **发布订阅**：测试 SUBSCRIBE、PUBLISH 等命令
- **Lua 脚本**：测试 EVAL、EVALSHA 等命令
- **持久化**：测试 RDB 和 AOF 持久化

## 6. 性能测试

### 6.1 性能测试概述

Luban-RDS 提供了多层次的性能测试方案：
1. **微基准测试 (Micro-benchmarks)**: 使用 JMH 对核心组件（如 `MemoryStore`）进行纳秒级性能分析。
2. **端到端基准测试 (E2E Benchmarks)**: 使用 `luban-rds-benchmark` 模块对运行中的服务器进行吞吐量和延迟测试。

### 6.2 端到端基准测试工具 (`luban-rds-benchmark`)

项目包含一个独立的基准测试模块 `luban-rds-benchmark`，用于模拟高并发场景下的服务器性能表现。该工具支持多种数据结构的操作测试、压力测试模式以及实时内存监控。

#### 6.2.1 编译与运行

首先，确保你已经构建了项目：

```bash
mvn clean package -pl luban-rds-benchmark -am
```

构建完成后，可以在 `luban-rds-benchmark/target` 目录下找到可执行的 JAR 包（通常名为 `luban-rds-benchmark-1.0.0-SNAPSHOT-jar-with-dependencies.jar`）。

运行示例：

```bash
java -jar luban-rds-benchmark/target/luban-rds-benchmark-1.0.0-SNAPSHOT-jar-with-dependencies.jar \
    -h 127.0.0.1 -p 9736 -t 20 -n 100000 -c set,get
```

#### 6.2.2 命令行参数

| 参数 | 全称 | 默认值 | 说明 |
|------|------|--------|------|
| `-h` | `--host` | 127.0.0.1 | 服务器地址 |
| `-p` | `--port` | 9736 | 服务器端口 |
| `-t` | `--threads` | 10 | 并发线程数 |
| `-n` | `--requests` | 100000 | 总请求数（在非持续时间模式下生效） |
| `-d` | `--duration` | 0 | 测试持续时间（秒）。设置为 >0 时启用**压力测试模式**，忽略 `-n` 参数 |
| `-s` | `--size` | 100 | 数据包载荷大小（字节） |
| `-c` | `--cases` | all | 指定运行的测试用例，逗号分隔。支持：`set`, `get`, `incr`, `lpush`, `lrange`, `hset`, `hget`, `sadd` |
| `-m` | `--monitor` | false | 开启服务器内存实时监控（每 5 秒打印一次 INFO memory） |

#### 6.2.3 测试场景示例

**1. 标准基准测试**
执行 10 万次 SET 和 GET 操作，使用 20 个并发线程：
```bash
java -jar luban-rds-benchmark.jar -t 20 -n 100000 -c set,get
```

**2. 稳定性压力测试**
持续运行 5 分钟（300秒），开启内存监控，验证是否存在内存泄漏或性能衰减：
```bash
java -jar luban-rds-benchmark.jar -t 50 -d 300 -m
```

### 6.3 微基准测试 (JMH)

对于内部核心组件的性能优化，我们推荐使用 JMH。

**依赖配置**：


```xml
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-core</artifactId>
    <version>1.36</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.openjdk.jmh</groupId>
    <artifactId>jmh-generator-annprocess</artifactId>
    <version>1.36</version>
    <scope>test</scope>
</dependency>
```

**示例**：

```java
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5)
@Measurement(iterations = 10)
public class MemoryStoreBenchmark {
    
    private MemoryStore memoryStore;
    
    @Setup
    public void setUp() {
        memoryStore = new DefaultMemoryStore();
    }
    
    @Benchmark
    public void testSet() {
        memoryStore.set("key", "value", 0);
    }
    
    @Benchmark
    public String testGet() {
        memoryStore.set("key", "value", 0);
        return memoryStore.get("key");
    }
    
    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
                .include(MemoryStoreBenchmark.class.getSimpleName())
                .build();
        new Runner(options).run();
    }
}
```

### 6.2 性能测试指标

常见的性能测试指标：

- **响应时间**：命令执行的平均时间
- **吞吐量**：每秒执行的命令数
- **内存使用**：内存占用情况
- **CPU 使用**：CPU 使用率
- **并发性能**：并发请求下的性能

### 6.3 性能测试工具

- **JMH**：Java 微基准测试框架
- **Gatling**：负载测试工具
- **JMeter**：性能测试工具
- **Redis-benchmark**：Redis 官方性能测试工具

## 7. 测试最佳实践

### 7.1 测试原则

- **独立性**：测试用例之间应相互独立，避免共享状态
- **可重复性**：测试结果应可重复，不受外部因素影响
- **简洁性**：测试代码应简洁明了，易于理解
- **覆盖性**：测试应覆盖主要功能和边界条件
- **可读性**：测试代码应具有良好的可读性

### 7.2 测试命名规范

- **测试类**：`{被测试类名}Test`
- **测试方法**：`test{测试场景}`
- **测试包**：与被测试类的包名相同

### 7.3 测试数据管理

- **测试数据隔离**：每个测试用例使用独立的测试数据
- **测试数据清理**：测试结束后清理测试数据
- **测试数据生成**：使用测试数据工厂生成测试数据

### 7.4 测试代码质量

- **代码风格**：遵循项目的代码风格规范
- **注释**：为复杂的测试逻辑添加注释
- **重构**：定期重构测试代码，保持代码质量
- **代码审查**：对测试代码进行代码审查

### 7.5 测试覆盖率

- **目标**：单元测试覆盖率应达到 80% 以上
- **工具**：使用 JaCoCo 生成覆盖率报告
- **分析**：分析覆盖率报告，识别未覆盖的代码
- **改进**：为未覆盖的代码添加测试用例

## 8. 测试工具集成

### 8.1 IntelliJ IDEA

- **运行测试**：右键点击测试类，选择 `Run 'TestClassName'`
- **调试测试**：右键点击测试类，选择 `Debug 'TestClassName'`
- **查看覆盖率**：使用 `Run > Run 'TestClassName' with Coverage`
- **测试模板**：使用 `File > New > Test` 创建测试类

### 8.2 Eclipse

- **运行测试**：右键点击测试类，选择 `Run As > JUnit Test`
- **调试测试**：右键点击测试类，选择 `Debug As > JUnit Test`
- **查看覆盖率**：使用 EclEmma 插件查看覆盖率
- **测试模板**：使用 `File > New > JUnit Test Case` 创建测试类

### 8.3 Maven

- **运行测试**：`mvn test`
- **运行特定测试**：`mvn test -Dtest=TestClassName`
- **生成覆盖率报告**：`mvn test jacoco:report`
- **运行集成测试**：`mvn verify`

## 9. 常见测试问题解决

### 9.1 测试失败

#### 问题：断言失败

**症状**：测试过程中出现 `AssertionError`。

**解决方案**：
- 检查断言条件是否正确
- 检查被测试代码的行为是否符合预期
- 调整测试数据或测试逻辑

#### 问题：超时

**症状**：测试过程中出现 `TimeoutException`。

**解决方案**：
- 检查测试代码是否存在无限循环
- 检查测试环境是否资源不足
- 增加测试超时时间：`@Test(timeout = 10000)`

#### 问题：空指针异常

**症状**：测试过程中出现 `NullPointerException`。

**解决方案**：
- 检查测试代码是否正确初始化对象
- 检查被测试代码是否正确处理 null 值
- 添加适当的 null 检查

### 9.2 测试运行慢

**解决方案**：
- 优化测试代码，减少测试执行时间
- 并行运行测试：`mvn test -T 4`
- 跳过不需要的测试：`mvn test -Dtest=需要的测试类`
- 使用内存数据库或模拟对象减少外部依赖

### 9.3 测试环境问题

**解决方案**：
- 确保测试环境配置正确
- 使用 Docker 容器化测试环境
- 清理测试环境，避免环境污染
- 记录测试环境信息，便于问题排查

## 10. 测试报告

### 10.1 生成测试报告

使用 Maven Surefire 插件生成测试报告：

```bash
# 运行测试并生成报告
mvn test
```

测试报告位于 `target/surefire-reports` 目录下。

### 10.2 生成覆盖率报告

使用 JaCoCo 插件生成覆盖率报告：

```bash
# 运行测试并生成覆盖率报告
mvn test jacoco:report
```

覆盖率报告位于 `target/site/jacoco` 目录下。

### 10.3 集成到 CI/CD

在 CI/CD 流程中集成测试报告：

**GitHub Actions 配置**：

```yaml
name: CI

on: [push, pull_request]

jobs:
  build:
    runs-on: ubuntu-latest
    
    steps:
    - uses: actions/checkout@v3
    - name: Set up JDK 17
      uses: actions/setup-java@v3
      with:
        java-version: '17'
        distribution: 'temurin'
    - name: Build with Maven
      run: mvn clean package
    - name: Run tests
      run: mvn test jacoco:report
    - name: Upload coverage report
      uses: actions/upload-artifact@v3
      with:
        name: coverage-report
        path: target/site/jacoco
```

## 11. 测试驱动开发（TDD）

### 11.1 TDD 流程

1. **编写测试**：编写一个失败的测试用例
2. **运行测试**：验证测试失败
3. **编写代码**：编写最小化的代码使测试通过
4. **运行测试**：验证测试通过
5. **重构代码**：优化代码结构，保持测试通过

### 11.2 TDD 示例

**步骤 1：编写测试**

```java
@Test
void testSetWithExpire() {
    // 测试带过期时间的 SET 操作
    memoryStore.set("key", "value", 1000);
    String result = memoryStore.get("key");
    assertEquals("value", result);
    
    // 等待过期
    Thread.sleep(1500);
    
    // 验证键已过期
    result = memoryStore.get("key");
    assertNull(result);
}
```

**步骤 2：运行测试**

测试失败，因为 `set` 方法还没有实现过期时间功能。

**步骤 3：编写代码**

```java
@Override
public String set(String key, String value, long expire) {
    // 存储值
    data.put(key, value);
    
    // 设置过期时间
    if (expire > 0) {
        expirationMap.put(key, System.currentTimeMillis() + expire);
    }
    
    return "OK";
}

@Override
public String get(String key) {
    // 检查是否过期
    if (expirationMap.containsKey(key) && System.currentTimeMillis() > expirationMap.get(key)) {
        data.remove(key);
        expirationMap.remove(key);
        return null;
    }
    return data.get(key);
}
```

**步骤 4：运行测试**

测试通过。

**步骤 5：重构代码**

```java
@Override
public String set(String key, String value, long expire) {
    validateKey(key);
    data.put(key, value);
    updateExpiration(key, expire);
    return "OK";
}

private void updateExpiration(String key, long expire) {
    if (expire > 0) {
        expirationMap.put(key, System.currentTimeMillis() + expire);
    } else {
        expirationMap.remove(key);
    }
}
```

## 12. 总结

本指南提供了 Luban-RDS 项目的测试方法和最佳实践，包括：

- **测试类型**：单元测试、集成测试、端到端测试、性能测试
- **测试框架**：JUnit 5、Mockito、Hamcrest、AssertJ、TestContainers
- **单元测试**：编写、生命周期、异常测试、参数化测试
- **集成测试**：编写、配置、测试数据库
- **端到端测试**：编写、测试场景
- **性能测试**：编写、指标、工具
- **测试最佳实践**：原则、命名规范、数据管理、代码质量、覆盖率
- **测试工具集成**：IntelliJ IDEA、Eclipse、Maven
- **常见问题解决**：测试失败、运行慢、环境问题
- **测试报告**：生成、覆盖率、CI/CD 集成
- **测试驱动开发**：流程、示例

遵循本指南可以编写高质量的测试代码，确保 Luban-RDS 项目的功能正确性和代码质量。

## 13. 参考资料

- **JUnit 5 官方文档**：[https://junit.org/junit5/docs/current/user-guide/](https://junit.org/junit5/docs/current/user-guide/)
- **Mockito 官方文档**：[https://site.mockito.org/](https://site.mockito.org/)
- **Hamcrest 官方文档**：[http://hamcrest.org/JavaHamcrest/](http://hamcrest.org/JavaHamcrest/)
- **AssertJ 官方文档**：[https://assertj.github.io/doc/](https://assertj.github.io/doc/)
- **TestContainers 官方文档**：[https://www.testcontainers.org/](https://www.testcontainers.org/)
- **JMH 官方文档**：[https://openjdk.org/projects/code-tools/jmh/](https://openjdk.org/projects/code-tools/jmh/)
- **测试驱动开发**：[https://en.wikipedia.org/wiki/Test-driven_development](https://en.wikipedia.org/wiki/Test-driven_development)
