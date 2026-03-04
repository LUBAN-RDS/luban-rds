---
title: 代码风格指南
---

# 代码风格指南

本指南详细说明了 Luban-RDS 项目的代码风格规范，确保代码的一致性、可读性和可维护性。

## 1. 基本规范

### 1.1 缩进

- **缩进方式**：使用 4 个空格进行缩进（不使用制表符）
- **换行缩进**：多行语句的换行部分使用 8 个空格缩进
- **大括号位置**：左大括号与语句在同一行，右大括号单独占一行

**示例**：

```java
// 正确
if (condition) {
    // 4 个空格缩进
    doSomething();
}

// 错误（使用制表符）
if (condition) {
	// 使用了制表符
	doSomething();
}
```

### 1.2 行宽

- **最大行宽**：每行代码不超过 100 个字符
- **换行原则**：当一行代码超过最大行宽时，应进行合理换行
- **换行位置**：在操作符之前换行，保持操作符在新行的开头

**示例**：

```java
// 正确
public void doSomething(String param1, String param2, String param3,
        int param4, int param5) {
    // 方法体
}

// 错误（行宽超过 100 字符）
public void doSomething(String param1, String param2, String param3, int param4, int param5, String param6, String param7) {
    // 方法体
}
```

### 1.3 空格

- **操作符周围**：操作符前后各加一个空格
- **括号内部**：括号内部不添加空格
- **逗号后**：逗号后添加一个空格
- **分号后**：分号后添加一个空格（如果在同一行）
- **方法参数**：方法参数之间用逗号分隔，逗号后添加一个空格

**示例**：

```java
// 正确
int result = a + b * c;
List<String> list = new ArrayList<>();
for (int i = 0; i < 10; i++) {
    // 循环体
}

// 错误（缺少空格）
int result=a+b*c;
List<String> list=new ArrayList<>();
for(int i=0;i<10;i++){
    // 循环体
}
```

### 1.4 空行

- **类之间**：不同类之间添加一个空行
- **方法之间**：不同方法之间添加一个空行
- **代码块之间**：逻辑相关的代码块之间添加一个空行
- **变量声明与代码之间**：变量声明与代码之间添加一个空行
- **注释与代码之间**：注释与代码之间添加一个空行

**示例**：

```java
// 正确
public class Example {

    private int value;

    public void method1() {
        // 方法体
    }

    public void method2() {
        // 方法体
    }
}

// 错误（缺少空行）
public class Example {
    private int value;
    public void method1() {
        // 方法体
    }
    public void method2() {
        // 方法体
    }
}
```

## 2. 命名规范

### 2.1 类名

- **命名规则**：使用大驼峰命名法（PascalCase）
- **命名原则**：使用名词或名词短语，清晰表达类的用途
- **避免使用**：避免使用缩写、数字或下划线（除非是特定的技术术语）

**示例**：

```java
// 正确
public class MemoryStore {
    // 类体
}

public class LuaCommandHandler {
    // 类体
}

// 错误
public class memory_store {
    // 类体
}

public class LCH {
    // 类体
}
```

### 2.2 方法名

- **命名规则**：使用小驼峰命名法（camelCase）
- **命名原则**：使用动词或动词短语，清晰表达方法的功能
- **避免使用**：避免使用缩写、数字或下划线（除非是特定的技术术语）

**示例**：

```java
// 正确
public void setValue(String key, String value) {
    // 方法体
}

public String getValue(String key) {
    // 方法体
}

// 错误
public void SET_VALUE(String key, String value) {
    // 方法体
}

public String get_val(String key) {
    // 方法体
}
```

### 2.3 变量名

- **命名规则**：使用小驼峰命名法（camelCase）
- **命名原则**：使用具有描述性的名称，清晰表达变量的用途
- **避免使用**：避免使用单字母变量（除了循环计数器），避免使用缩写或下划线

**示例**：

```java
// 正确
String userName;
int itemCount;

// 错误
String uName;
int ic;
```

### 2.4 常量名

- **命名规则**：使用全大写字母，单词之间用下划线分隔（SNAKE_CASE）
- **命名原则**：使用具有描述性的名称，清晰表达常量的用途
- **定义位置**：常量应定义为 `public static final`，并放在类的顶部

**示例**：

```java
// 正确
public static final int MAX_CONNECTIONS = 1000;
public static final String DEFAULT_ENCODING = "UTF-8";

// 错误
public static final int maxConnections = 1000;
public static final String defaultEncoding = "UTF-8";
```

### 2.5 包名

- **命名规则**：使用小写字母，单词之间用点分隔
- **命名原则**：使用反向域名作为包名前缀，然后是项目名称和模块名称
- **避免使用**：避免使用大写字母或下划线

**示例**：

```java
// 正确
package com.janeluo.luban.rds.core;
package com.janeluo.luban.rds.server;

// 错误
package com.janeluo.luban.rds.Core;
package com.janeluo.luban_rds.core;
```

### 2.6 枚举名

- **命名规则**：使用大驼峰命名法（PascalCase）
- **枚举值**：使用全大写字母，单词之间用下划线分隔
- **命名原则**：枚举类名使用名词，枚举值使用具有描述性的名称

**示例**：

```java
// 正确
public enum CommandType {
    STRING,
    LIST,
    SET,
    HASH,
    ZSET
}

// 错误
public enum command_type {
    string,
    list,
    set
}
```

## 3. 注释规范

### 3.1 Javadoc 注释

- **使用场景**：为公共类、方法、接口添加 Javadoc 注释
- **注释格式**：使用 `/** ... */` 格式，包含 `@param`、`@return`、`@throws` 等标签
- **注释内容**：描述类或方法的用途、参数含义、返回值和异常情况

**示例**：

```java
/**
 * MemoryStore 接口定义了所有数据类型的操作方法
 * 
 * @author Your Name
 * @since 1.0.0
 */
public interface MemoryStore {
    
    /**
     * 设置字符串值
     * 
     * @param key 键名
     * @param value 值
     * @param expire 过期时间（毫秒），0 表示永不过期
     * @return 操作结果
     */
    String set(String key, String value, long expire);
}
```

### 3.2 行注释

- **使用场景**：为复杂的实现逻辑添加行注释
- **注释格式**：使用 `//` 格式，放在代码行的右侧或上方
- **注释内容**：解释代码的实现思路、关键逻辑或注意事项
- **注释位置**：注释应与被注释的代码保持同一缩进级别

**示例**：

```java
// 正确
// 检查键是否存在
if (containsKey(key)) {
    // 更新值并设置过期时间
    updateValue(key, value, expire);
}

// 错误（注释与代码缩进不一致）
// 检查键是否存在
if (containsKey(key)) {
// 更新值并设置过期时间
updateValue(key, value, expire);
}
```

### 3.3 块注释

- **使用场景**：为代码块、算法说明或临时注释添加块注释
- **注释格式**：使用 `/* ... */` 格式
- **注释内容**：详细说明代码块的功能、实现思路或算法原理
- **注释位置**：块注释应放在被注释的代码块之前，与代码块保持同一缩进级别

**示例**：

```java
// 正确
/*
 * 实现 LRU 缓存淘汰算法
 * 1. 当缓存达到最大容量时，移除最久未使用的元素
 * 2. 每次访问元素时，将其移到链表头部
 */
public void evict() {
    // 实现逻辑
}

// 错误（注释格式不正确）
// 实现 LRU 缓存淘汰算法
// 1. 当缓存达到最大容量时，移除最久未使用的元素
// 2. 每次访问元素时，将其移到链表头部
public void evict() {
    // 实现逻辑
}
```

### 3.4 中文注释

- **使用原则**：对于团队内部理解的内容，可使用中文注释
- **使用场景**：实现细节、算法说明、调试信息等
- **注释质量**：中文注释应清晰、准确，避免使用模糊或歧义的表述

**示例**：

```java
// 正确（使用中文注释说明实现细节）
/**
 * 执行 Lua 脚本
 * 
 * @param script Lua 脚本
 * @param keys 键数组
 * @param args 参数数组
 * @return 执行结果
 */
public Object eval(String script, List<String> keys, List<String> args) {
    // 初始化 Lua 执行环境
    LuaState luaState = createLuaState();
    // 注入 KEYS 和 ARGV 变量
    injectVariables(luaState, keys, args);
    // 执行脚本
    return executeScript(luaState, script);
}

// 错误（中文注释表述不清）
/**
 * 执行脚本
 */
public Object eval(String script, List<String> keys, List<String> args) {
    // 做一些初始化
    LuaState luaState = createLuaState();
    // 处理变量
    injectVariables(luaState, keys, args);
    // 执行
    return executeScript(luaState, script);
}
```

## 4. 代码结构规范

### 4.1 类结构

- **成员变量**：类的成员变量应放在类的顶部
- **构造方法**：构造方法应放在成员变量之后
- **静态方法**：静态方法应放在构造方法之后
- **实例方法**：实例方法应放在静态方法之后
- **内部类**：内部类应放在实例方法之后

**示例**：

```java
public class Example {
    // 成员变量
    private String name;
    private int age;
    
    // 静态常量
    public static final int MAX_AGE = 100;
    
    // 构造方法
    public Example() {
    }
    
    public Example(String name, int age) {
        this.name = name;
        this.age = age;
    }
    
    // 静态方法
    public static void staticMethod() {
    }
    
    // 实例方法
    public void instanceMethod() {
    }
    
    // 内部类
    private static class InnerClass {
    }
}
```

### 4.2 方法结构

- **参数顺序**：参数应按重要性和使用频率排序，基本类型在前，对象类型在后
- **返回值**：方法应返回有意义的值，避免返回 null（除非必要）
- **异常处理**：方法应抛出适当的异常，避免捕获所有异常
- **方法长度**：方法应保持简短，通常不超过 50 行，超过应考虑拆分

**示例**：

```java
// 正确（方法简短，职责单一）
public String getValue(String key) {
    checkKey(key);
    return doGetValue(key);
}

// 错误（方法过长，职责不单一）
public String getValue(String key) {
    if (key == null || key.isEmpty()) {
        throw new IllegalArgumentException("Key cannot be null or empty");
    }
    synchronized (this) {
        Map<String, String> data = getData();
        if (data.containsKey(key)) {
            return data.get(key);
        }
        return null;
    }
}
```

### 4.3 导入规范

- **导入顺序**：按以下顺序导入：
  1. 静态导入
  2. Java 标准库
  3. 第三方库
  4. 项目内部类
- **导入方式**：避免使用 `*` 通配符导入（除非导入的类超过 10 个）
- **未使用导入**：删除未使用的导入语句

**示例**：

```java
// 正确

import io.netty.channel.Channel;
import org.luaj.vm2.LuaState;

import com.janeluo.luban.rds.core.MemoryStore;
import com.janeluo.luban.rds.protocol.Command;

// 错误（导入顺序混乱）


```

## 5. 编程实践规范

### 5.1 异常处理

- **异常类型**：使用适当的异常类型，避免使用通用的 Exception
- **异常消息**：异常消息应清晰、准确，描述异常的原因
- **异常处理**：根据异常的类型和严重程度选择适当的处理方式
- **资源管理**：使用 try-with-resources 语句管理资源，确保资源正确关闭

**示例**：

```java
// 正确（使用 try-with-resources）
try (FileReader reader = new FileReader("file.txt")) {
    // 读取文件
} catch (IOException e) {
    logger.error("Failed to read file: {}", e.getMessage(), e);
    throw new RuntimeException("Failed to read configuration file", e);
}

// 错误（未使用 try-with-resources）
FileReader reader = null;
try {
    reader = new FileReader("file.txt");
    // 读取文件
} catch (IOException e) {
    e.printStackTrace();
} finally {
    if (reader != null) {
        try {
            reader.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
```

### 5.2 日志规范

- **日志框架**：使用 SLF4J 作为日志框架
- **日志级别**：根据日志的重要性选择适当的日志级别
  - `DEBUG`：调试信息，仅在开发环境使用
  - `INFO`：一般信息，描述系统运行状态
  - `WARN`：警告信息，需要关注但不影响系统运行
  - `ERROR`：错误信息，影响系统运行的问题
- **日志格式**：使用占位符 `{}` 格式化日志消息，避免字符串拼接

**示例**：

```java
// 正确
logger.info("User {} logged in successfully", userName);
logger.error("Failed to connect to database: {}", e.getMessage(), e);

// 错误（使用字符串拼接）
logger.info("User " + userName + " logged in successfully");
logger.error("Failed to connect to database: " + e.getMessage(), e);
```

### 5.3 并发编程

- **线程安全**：确保多线程环境下的代码线程安全
- **同步机制**：使用适当的同步机制，如 `synchronized`、`ReentrantLock` 等
- **原子操作**：使用 `Atomic` 类进行原子操作
- **线程池**：使用线程池管理线程，避免创建过多线程
- **避免死锁**：避免嵌套同步，确保获取锁的顺序一致

**示例**：

```java
// 正确（使用线程池）
private static final ExecutorService executorService = Executors.newFixedThreadPool(10);

public void submitTask(Runnable task) {
    executorService.submit(task);
}

// 错误（每次创建新线程）
public void submitTask(Runnable task) {
    new Thread(task).start();
}
```

### 5.4 性能优化

- **内存优化**：避免创建不必要的对象，使用对象池或缓存
- **算法优化**：选择适当的算法和数据结构
- **I/O 优化**：减少 I/O 操作，使用缓冲流
- **网络优化**：减少网络往返，使用批量操作
- **代码优化**：避免不必要的计算，使用局部变量

**示例**：

```java
// 正确（使用 StringBuilder 拼接字符串）
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 1000; i++) {
    sb.append("Value: ").append(i).append("\n");
}
String result = sb.toString();

// 错误（使用字符串拼接）
String result = "";
for (int i = 0; i < 1000; i++) {
    result += "Value: " + i + "\n";
}
```

## 6. 测试规范

### 6.1 测试命名

- **测试类名**：使用被测试类名加 `Test` 后缀
- **测试方法名**：使用 `test` 前缀，后跟测试场景描述
- **测试包名**：与被测试类的包名相同

**示例**：

```java
// 正确
public class LuaCommandHandlerTest {
    
    @Test
    public void testEvalScript() {
        // 测试代码
    }
    
    @Test
    public void testEvalShaScript() {
        // 测试代码
    }
}

// 错误
public class TestLuaCommandHandler {
    
    @Test
    public void evalScript() {
        // 测试代码
    }
}
```

### 6.2 测试覆盖

- **覆盖范围**：测试应覆盖正常场景、边界条件和异常场景
- **测试断言**：使用明确的断言消息，确保测试失败时能快速定位问题
- **测试隔离**：测试用例之间应相互隔离，避免共享状态
- **测试数据**：使用有意义的测试数据，避免使用硬编码的魔法数字

**示例**：

```java
// 正确
@Test
public void testSetWithExpire() {
    // 测试正常场景
    String result = memoryStore.set("key", "value", 1000);
    assertEquals("OK", result);
    
    // 测试边界条件
    result = memoryStore.set("key2", "value2", 0);
    assertEquals("OK", result);
    
    // 测试异常场景
    try {
        memoryStore.set(null, "value", 1000);
        fail("Should throw IllegalArgumentException");
    } catch (IllegalArgumentException e) {
        // 预期异常
    }
}

// 错误（测试不完整）
@Test
public void testSet() {
    String result = memoryStore.set("key", "value", 1000);
    assertEquals("OK", result);
}
```

### 6.3 测试框架

- **单元测试**：使用 JUnit 4/5 进行单元测试
- **集成测试**：使用 JUnit 4/5 结合 Spring Test 进行集成测试
- **模拟对象**：使用 Mockito 进行对象模拟
- **测试断言**：使用 JUnit 的断言方法或 Hamcrest 匹配器

**示例**：

```java
// 正确（使用 Mockito 模拟对象）
@Test
public void testCommandHandler() {
    // 模拟 MemoryStore
    MemoryStore memoryStore = Mockito.mock(MemoryStore.class);
    Mockito.when(memoryStore.set("key", "value", 0)).thenReturn("OK");
    
    // 创建命令处理器
    CommandHandler handler = new StringCommandHandler(memoryStore);
    
    // 测试命令执行
    Command command = new Command("SET", Arrays.asList("key", "value"));
    Object result = handler.handle(command);
    
    // 验证结果
    assertEquals("OK", result);
    Mockito.verify(memoryStore).set("key", "value", 0);
}
```

## 7. 工具和配置

### 7.1 IDE 配置

- **IntelliJ IDEA**：
  - 导入项目根目录下的 `.editorconfig` 文件
  - 在 `File > Settings > Editor > Code Style > Java` 中配置代码风格
  - 启用 `Reformat Code` 和 `Optimize Imports` 功能

- **Eclipse**：
  - 导入项目根目录下的 `eclipse-formatter.xml` 文件
  - 在 `Window > Preferences > Java > Code Style > Formatter` 中配置代码风格
  - 启用自动格式化功能

### 7.2 构建工具配置

- **Maven**：
  - 使用 `maven-compiler-plugin` 配置 Java 版本
  - 使用 `maven-surefire-plugin` 配置测试
  - 使用 `maven-checkstyle-plugin` 检查代码风格

**示例**：

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.8.1</version>
    <configuration>
        <source>1.8</source>
        <target>1.8</target>
        <encoding>UTF-8</encoding>
    </configuration>
</plugin>
```

### 7.3 代码检查工具

- **Checkstyle**：检查代码风格和规范
- **PMD**：检查潜在的代码问题
- **FindBugs**：检查潜在的 bug
- **SonarQube**：综合代码质量分析

**示例**：

```bash
# 运行 Checkstyle
mvn checkstyle:check

# 运行 PMD
mvn pmd:check

# 运行 FindBugs
mvn findbugs:check
```

## 8. 常见错误和最佳实践

### 8.1 常见错误

- **使用魔法数字**：直接使用数字而不使用命名常量
- **硬编码字符串**：直接使用字符串而不使用常量
- **过长的方法**：方法过长，职责不单一
- **过深的嵌套**：代码嵌套层级过深，影响可读性
- **未使用的变量**：定义了但未使用的变量
- **未关闭的资源**：未正确关闭文件、数据库连接等资源
- **空指针异常**：未检查 null 值

### 8.2 最佳实践

- **使用常量**：将魔法数字和字符串定义为常量
- **方法拆分**：将过长的方法拆分为多个短小的方法
- **减少嵌套**：使用提前返回、卫语句等减少代码嵌套
- **null 检查**：使用 Objects.requireNonNull 或 Optional 处理 null 值
- **资源管理**：使用 try-with-resources 管理资源
- **代码复用**：提取重复代码为方法或工具类
- **单元测试**：为新功能和修复添加单元测试
- **代码审查**：定期进行代码审查，确保代码质量

## 9. 总结

遵循代码风格指南可以：

- **提高代码可读性**：使代码更易于理解和维护
- **减少错误**：规范的代码风格可以减少常见错误
- **提高团队协作效率**：统一的代码风格使团队成员更容易理解和修改彼此的代码
- **提升项目质量**：规范的代码风格是高质量项目的基础

本指南是 Luban-RDS 项目的代码风格规范，所有贡献者应遵循这些规范，确保代码的一致性和可维护性。

## 10. 参考资料

- **Google Java 风格指南**：[https://google.github.io/styleguide/javaguide.html](https://google.github.io/styleguide/javaguide.html)
- **Oracle Java 代码约定**：[https://www.oracle.com/java/technologies/javase/codeconventions-contents.html](https://www.oracle.com/java/technologies/javase/codeconventions-contents.html)
- **Effective Java**：Joshua Bloch 著
- **Clean Code**：Robert C. Martin 著
