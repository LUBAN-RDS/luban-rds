---
title: 协议说明
---

# 协议说明

本部分详细介绍了 Redis 序列化协议（RESP）的工作原理、数据格式和实现细节，帮助您理解 Luban-RDS 如何与客户端通信。

## 1. RESP 协议概述

**RESP**（Redis Serialization Protocol）是 Redis 设计的一种简单高效的通信协议，用于客户端与服务器之间的通信。Luban-RDS 完全支持 RESP 协议，与 Redis 客户端兼容。

### 1.1 协议特点

- **简单**：易于实现和理解
- **高效**：二进制安全，处理速度快
- **灵活**：支持多种数据类型
- **人类可读**：部分内容可直接阅读

### 1.2 数据类型

RESP 支持以下数据类型：

| 类型 | 前缀 | 说明 |
|------|------|------|
| **Simple String** | `+` | 简单字符串，不以换行符结尾 |
| **Error** | `-` | 错误信息，格式与简单字符串类似 |
| **Integer** | `:` | 整数，范围为 64 位有符号整数 |
| **Bulk String** | `$` | 批量字符串，支持二进制数据 |
| **Array** | `*` | 数组，可包含任意类型的数据 |

## 2. 数据格式详解

### 2.1 Simple String

**格式**：`+{content}\r\n`

**示例**：
```
+OK\r\n
+PONG\r\n
+Hello World\r\n
```

**用途**：
- 表示操作成功
- 返回简单的状态信息
- 如 `OK`、`PONG` 等

### 2.2 Error

**格式**：`-{error_type} {error_message}\r\n`

**示例**：
```
-ERR unknown command 'FOO'\r\n
-WRONGTYPE Operation against a key holding the wrong kind of value\r\n
-NOPERM no permission to access this key\r\n
```

**用途**：
- 表示操作失败
- 包含错误类型和错误信息
- 客户端应将其视为异常处理

### 2.3 Integer

**格式**：`:{value}\r\n`

**示例**：
```
:1\r\n
:0\r\n
:-1\r\n
:1234567890\r\n
```

**用途**：
- 表示计数、索引等整数
- 如 `INCR` 命令的返回值
- 如 `EXISTS` 命令的布尔结果（1 表示存在，0 表示不存在）

### 2.4 Bulk String

**格式**：`${length}\r\n{content}\r\n`

**示例**：
```
$5\r\nhello\r\n
$0\r\n\r\n  # 空字符串

$-1\r\n  # nil 值

```

**用途**：
- 存储任意长度的字符串
- 支持二进制数据
- 如 `GET` 命令的返回值

### 2.5 Array

**格式**：`*{count}\r\n{element1}\r\n{element2}\r\n...\r\n{elementN}\r\n`

**示例**：
```
*2\r\n$3\r\nGET\r\n$5\r\nhello\r\n  # 命令数组

*3\r\n:1\r\n:2\r\n:3\r\n  # 整数数组

*2\r\n$5\r\nhello\r\n$5\r\nworld\r\n  # 字符串数组

*-1\r\n  # nil 数组

```

**用途**：
- 表示命令和参数
- 表示多个值的集合
- 如 `HGETALL` 命令的返回值

## 3. 命令执行流程

### 3.1 客户端发送命令

客户端将命令和参数封装为 RESP 数组格式发送给服务器。

**示例**：执行 `SET name John` 命令

```
*3\r\n$3\r\nSET\r\n$4\r\nname\r\n$4\r\nJohn\r\n
```

**解析**：
- `*3`：数组包含 3 个元素
- `$3\r\nSET`：第一个元素是字符串 "SET"，长度为 3
- `$4\r\nname`：第二个元素是字符串 "name"，长度为 4
- `$4\r\nJohn`：第三个元素是字符串 "John"，长度为 4

### 3.2 服务器处理命令

1. **解析请求**：服务器接收 RESP 格式的数据，解析为命令和参数
2. **执行命令**：根据命令类型执行相应的操作
3. **生成响应**：将执行结果编码为 RESP 格式

### 3.3 服务器返回响应

服务器根据命令执行结果返回不同类型的 RESP 数据。

**示例 1**：执行 `SET name John` 的响应

```
+OK\r\n
```

**示例 2**：执行 `GET name` 的响应

```
$4\r\nJohn\r\n
```

**示例 3**：执行 `EXISTS nonexistent` 的响应

```
:0\r\n
```

**示例 4**：执行 `HGETALL user:1` 的响应

```
*4\r\n$4\r\nname\r\n$4\r\nJohn\r\n$3\r\nage\r\n$2\r\n30\r\n
```

### 3.4 入站累积与循环解析
- 每条连接维护入站缓冲区（ByteBuf），channelRead 到来的数据统一累积以应对半包/粘包。
- 使用 while 循环持续解析缓冲区中的完整 RESP 帧，解析成功即分发执行，支持 pipeline。
- 参考实现：[RedisServerHandler.java:L69-L88]

## 4. 特殊情况处理

### 4.1 Nil 值

- **Bulk String**：使用 `$-1\r\n` 表示 nil
- **Array**：使用 `*-1\r\n` 表示 nil

> 区分：Bulk nil 与 Null Array 的语义不同。示例：
- GET 不存在的键 → `$-1\r\n`（Bulk nil）
- WATCH 触发导致 EXEC 不执行 → `*-1\r\n`（Null Array）

**示例**：获取不存在的键

```
GET nonexistent

# 响应
$-1\r\n
```

### 4.2 空字符串

- **Bulk String**：使用 `$0\r\n\r\n` 表示空字符串

**示例**：

```
SET empty ""

# 响应
+OK\r\n
GET empty

# 响应
$0\r\n\r\n
```

### 4.3 嵌套数组

- RESP 数组可以嵌套任意深度
- 常用于复杂的数据结构

**示例**：

```
*2\r\n*3\r\n:1\r\n:2\r\n:3\r\n*2\r\n$3\r\nfoo\r\n$3\r\nbar\r\n
```

## 5. 协议实现

### 5.1 解析器

Luban-RDS 使用 `RedisProtocolParser` 类解析 RESP 协议：

```java
public class RedisProtocolParser {
    /**
     * 解析 RESP 格式的数据
     * @param data 字节数组
     * @return 解析后的数据对象
     */
    public Object parse(byte[] data) {
        // 实现解析逻辑
    }
    
    /**
     * 编码对象为 RESP 格式
     * @param obj 要编码的对象
     * @return 编码后的字节数组
     */
    public byte[] encode(Object obj) {
        // 实现编码逻辑
    }
}
```

### 5.2 命令对象

解析后的命令会被封装为 `Command` 对象：

```java
public class Command {
    private String name;
    private String[] args;
    
    // 构造器、getter、setter 等
}
```

### 5.3 性能优化

- **缓冲区管理**：使用 Netty 的 ByteBuf 减少内存复制
- **零拷贝**：直接操作字节数组，避免不必要的数据转换
- **批量处理**：支持管道（pipeline）批量执行命令

### 5.4 QUIT 响应与连接关闭
- QUIT 的响应为 `+OK\r\n`（Simple String）
- 响应写回后服务器主动关闭连接
- 参考实现：[RedisServerHandler.java:L752-L761]

## 6. 客户端实现

### 6.1 基本流程

1. **建立连接**：与服务器建立 TCP 连接
2. **发送命令**：将命令编码为 RESP 格式发送
3. **接收响应**：读取服务器响应并解析
4. **处理结果**：根据响应类型处理结果
5. **关闭连接**：完成操作后关闭连接

### 6.2 示例代码

**Java 实现**：

```java
public class SimpleRedisClient {
    private Socket socket;
    private OutputStream out;
    private InputStream in;
    
    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        out = socket.getOutputStream();
        in = socket.getInputStream();
    }
    
    public String sendCommand(String... args) throws IOException {
        // 编码命令为 RESP 数组
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append("$").append(arg.length()).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        
        // 发送命令
        out.write(sb.toString().getBytes());
        out.flush();
        
        // 读取响应
        byte[] buffer = new byte[1024];
        int len = in.read(buffer);
        return new String(buffer, 0, len);
    }
    
    public void close() throws IOException {
        if (socket != null) {
            socket.close();
        }
    }
    
    public static void main(String[] args) throws IOException {
        SimpleRedisClient client = new SimpleRedisClient();
        client.connect("localhost", 9736);
        
        // 执行命令
        String response = client.sendCommand("SET", "name", "John");
        System.out.println("SET response: " + response);
        
        response = client.sendCommand("GET", "name");
        System.out.println("GET response: " + response);
        
        client.close();
    }
}
```

### 6.3 常见客户端

- **redis-cli**：Redis 官方命令行客户端
- **Jedis**：Java 客户端
- **Lettuce**：Java 响应式客户端
- **StackExchange.Redis**：.NET 客户端
- **node-redis**：Node.js 客户端
- **redis-py**：Python 客户端

## 7. 协议扩展

### 7.1 RESP3

Redis 6.0 引入了 RESP3 协议，增加了更多数据类型：

- **Map**：映射类型
- **Set**：集合类型
- **Null**：空值类型
- **Boolean**：布尔类型
- **Double**：浮点数类型
- **Big number**：大整数类型

### 7.2 Luban-RDS 的扩展

Luban-RDS 目前完全支持 RESP2 协议，未来可能会考虑支持 RESP3 协议。

## 8. 性能考虑

### 8.1 协议开销

- **RESP** 协议非常高效，开销很小
- 相比其他协议（如 HTTP），RESP 更加紧凑
- 适合高并发场景

### 8.2 最佳实践

- **使用管道**：批量发送命令，减少网络往返
- **合理使用数据类型**：选择合适的数据类型存储数据
- **避免大键**：大键会增加网络传输时间
- **使用连接池**：复用连接，减少连接建立开销

## 9. 调试技巧

### 9.1 查看原始协议

使用 `redis-cli --raw` 查看原始响应：

```bash
redis-cli --raw GET name
```

### 9.2 使用网络工具

- **telnet**：直接连接服务器，发送原始命令
- **netcat**：类似 telnet，功能更强大
- **Wireshark**：抓包分析网络通信

**示例**：使用 telnet 连接

```bash
telnet localhost 9736

# 发送命令
*3
$3
SET
$4
name
$4
John

# 接收响应
+OK

# 发送命令
*2
$3
GET
$4
name

# 接收响应
$4
John

```

## 10. 安全注意事项

### 10.1 命令注入

- **风险**：恶意客户端可能发送危险命令
- **防护**：
  - 设置密码认证
  - 限制危险命令的使用
  - 使用网络访问控制

### 10.2 数据安全

- **RESP** 协议本身不加密
- 敏感数据应在应用层加密
- 生产环境建议使用 TLS 加密传输

## 11. 总结

RESP 协议是一种简单、高效、灵活的通信协议，为 Redis 和 Luban-RDS 提供了良好的客户端兼容性。通过理解 RESP 协议的工作原理，您可以：

- 更好地使用 Luban-RDS
- 开发自定义客户端
- 优化应用性能
- 排查通信问题

## 12. 下一步

- **[核心接口](./core.md)**：了解 Luban-RDS 的核心接口
- **[命令列表](./commands.md)**：查看支持的所有命令
- **[使用指南](../guide/)**：学习如何使用 Luban-RDS
