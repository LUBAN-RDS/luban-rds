---
title: 使用示例
---

# 使用示例

本部分提供了 Luban-RDS 在各种常见场景下的使用示例，帮助您快速应用到实际项目中。

## 1. 基本操作示例

### 1.1 String 类型示例

**场景**：存储用户会话信息

```java
// 设置用户会话，1小时过期
redisTemplate.opsForValue().set("session:user:123", "{\"userId\":123,\"name\":\"John\"}", 1, TimeUnit.HOURS);

// 获取用户会话
String session = redisTemplate.opsForValue().get("session:user:123");

// 计数器示例
redisTemplate.opsForValue().increment("counter:pageviews");
Long pageViews = redisTemplate.opsForValue().increment("counter:pageviews");
```

### 1.2 Hash 类型示例

**场景**：存储用户信息

```java
// 设置用户信息
HashOperations<String, String, String> hashOps = redisTemplate.opsForHash();
hashOps.put("user:123", "name", "John");
hashOps.put("user:123", "email", "john@example.com");
hashOps.put("user:123", "age", "30");

// 获取用户信息
Map<String, String> userInfo = hashOps.entries("user:123");
String userName = hashOps.get("user:123", "name");

// 批量设置
Map<String, String> userData = new HashMap<>();
userData.put("name", "Jane");
userData.put("email", "jane@example.com");
hashOps.putAll("user:124", userData);
```

### 1.3 List 类型示例

**场景**：实现消息队列

```java
// 生产者：添加消息到队列
redisTemplate.opsForList().rightPush("queue:messages", "{\"id\":1,\"content\":\"Hello\"}");

// 消费者：从队列获取消息
String message = redisTemplate.opsForList().leftPop("queue:messages");

// 查看队列长度
Long queueSize = redisTemplate.opsForList().size("queue:messages");

// 获取队列中的消息
List<String> messages = redisTemplate.opsForList().range("queue:messages", 0, -1);
```

### 1.4 Set 类型示例

**场景**：存储用户标签

```java
// 添加用户标签
redisTemplate.opsForSet().add("user:123:tags", "java", "spring", "redis");

// 检查用户是否有特定标签
Boolean hasTag = redisTemplate.opsForSet().isMember("user:123:tags", "java");

// 获取用户所有标签
Set<String> tags = redisTemplate.opsForSet().members("user:123:tags");

// 移除用户标签
redisTemplate.opsForSet().remove("user:123:tags", "redis");
```

### 1.5 ZSet 类型示例

**场景**：实现排行榜

```java
// 添加用户分数
redisTemplate.opsForZSet().add("leaderboard:scores", "user:123", 95);
redisTemplate.opsForZSet().add("leaderboard:scores", "user:124", 88);
redisTemplate.opsForZSet().add("leaderboard:scores", "user:125", 92);

// 获取排行榜（从高到低）
Set<String> topUsers = redisTemplate.opsForZSet().reverseRange("leaderboard:scores", 0, 4);

// 获取带分数的排行榜
Set<ZSetOperations.TypedTuple<String>> topUsersWithScores = redisTemplate.opsForZSet().reverseRangeWithScores("leaderboard:scores", 0, 4);

// 获取用户排名
Long rank = redisTemplate.opsForZSet().reverseRank("leaderboard:scores", "user:123");

// 获取用户分数
Double score = redisTemplate.opsForZSet().score("leaderboard:scores", "user:123");
```

## 2. 高级功能示例

### 2.1 Lua 脚本示例

**场景**：原子操作 - 库存扣减

```java
// Lua 脚本：检查并扣减库存
String script = """
local stock = redis.call('GET', KEYS[1])
if not stock then
    return redis.error_reply('库存不存在')
end
if tonumber(stock) < tonumber(ARGV[1]) then
    return redis.error_reply('库存不足')
end
redis.call('DECRBY', KEYS[1], ARGV[1])
return redis.status_reply('OK')
""";

// 执行脚本
DefaultRedisScript<String> redisScript = new DefaultRedisScript<>(script, String.class);
List<String> keys = Collections.singletonList("stock:product:123");
String result = redisTemplate.execute(redisScript, keys, "1");
```

**场景**：批量操作

```java
// Lua 脚本：批量获取多个键的值
String script = """
local result = {}
for i, key in ipairs(KEYS) do
    result[i] = redis.call('GET', key)
end
return result
""";

// 执行脚本
DefaultRedisScript<List> redisScript = new DefaultRedisScript<>(script, List.class);
List<String> keys = Arrays.asList("user:123:name", "user:124:name", "user:125:name");
List<Object> values = redisTemplate.execute(redisScript, keys);
```

### 2.2 发布订阅示例

**场景**：实时通知

```java
// 订阅者
RedisMessageListenerContainer container = new RedisMessageListenerContainer();
container.setConnectionFactory(redisConnectionFactory);

MessageListener listener = (message, pattern) -> {
    String channel = new String(pattern);
    String messageBody = new String(message.getBody());
    System.out.println("Received message from channel " + channel + ": " + messageBody);
};

// 订阅频道
PatternTopic topic = new PatternTopic("notifications:*");
container.addMessageListener(listener, topic);
container.start();

// 发布者
redisTemplate.convertAndSend("notifications:users", "{\"type\":\"user_created\",\"id\":123}");
```

### 2.3 管道示例

**场景**：批量插入数据

```java
// 使用管道批量执行命令
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int i = 0; i < 1000; i++) {
        connection.stringCommands().set("batch:key:" + i, "value:" + i);
    }
    return null;
});

// 测量执行时间
long startTime = System.currentTimeMillis();
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int i = 0; i < 10000; i++) {
        connection.stringCommands().set("test:key:" + i, "test:value:" + i);
    }
    return null;
});
long endTime = System.currentTimeMillis();
System.out.println("Batch insert took " + (endTime - startTime) + "ms");
```

## 3. 实际应用场景

### 3.1 缓存实现

**场景**：缓存数据库查询结果

```java
// 缓存键生成
String cacheKey = "cache:user:" + userId;

// 尝试从缓存获取
String userJson = redisTemplate.opsForValue().get(cacheKey);
if (userJson != null) {
    // 缓存命中，解析返回
    return JSON.parseObject(userJson, User.class);
}

// 缓存未命中，从数据库查询
User user = userRepository.findById(userId).orElse(null);
if (user != null) {
    // 存入缓存，设置过期时间
    redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(user), 30, TimeUnit.MINUTES);
}
return user;

// 更新数据时清除缓存
public void updateUser(User user) {
    userRepository.save(user);
    redisTemplate.delete("cache:user:" + user.getId());
}
```

### 3.2 分布式锁

**场景**：防止并发操作

```java
// 尝试获取锁
String lockKey = "lock:order:" + orderId;
Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, "", 5, TimeUnit.SECONDS);

if (acquired) {
    try {
        // 执行业务逻辑
        processOrder(orderId);
    } finally {
        // 释放锁
        redisTemplate.delete(lockKey);
    }
} else {
    // 锁获取失败
    throw new RuntimeException("Order is being processed by another thread");
}
```

### 3.3 限流实现

**场景**：API 接口限流

```java
// 基于滑动窗口的限流
String rateLimitKey = "rate_limit:api:" + userId;
long windowSize = 60; // 60秒窗口
int maxRequests = 100; // 最大请求数

// 获取当前时间
long currentTime = System.currentTimeMillis() / 1000;
long windowStart = currentTime - windowSize;

// 使用管道执行多个命令
List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    // 添加当前请求时间戳
    connection.zSetCommands().zAdd(rateLimitKey, currentTime, String.valueOf(currentTime));
    // 删除窗口外的请求
    connection.zSetCommands().zRemRangeByScore(rateLimitKey, 0, windowStart);
    // 设置键过期时间
    connection.keyCommands().expire(rateLimitKey, windowSize);
    // 获取当前窗口内的请求数
    connection.zSetCommands().zCard(rateLimitKey);
    return null;
});

// 检查是否超过限制
Long requestCount = (Long) results.get(3);
if (requestCount > maxRequests) {
    throw new RuntimeException("API rate limit exceeded");
}
```

### 3.4 会话管理

**场景**：分布式会话存储

```java
// 创建会话
String sessionId = UUID.randomUUID().toString();
String sessionKey = "session:" + sessionId;

// 存储会话数据
Map<String, String> sessionData = new HashMap<>();
sessionData.put("userId", "123");
sessionData.put("lastAccess", String.valueOf(System.currentTimeMillis()));
redisTemplate.opsForHash().putAll(sessionKey, sessionData);

// 设置会话过期时间
redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);

// 获取会话数据
Map<Object, Object> session = redisTemplate.opsForHash().entries(sessionKey);
if (session.isEmpty()) {
    // 会话不存在或已过期
    throw new RuntimeException("Session expired");
}

// 更新会话访问时间
redisTemplate.opsForHash().put(sessionKey, "lastAccess", String.valueOf(System.currentTimeMillis()));
redisTemplate.expire(sessionKey, 30, TimeUnit.MINUTES);
```

## 4. 性能优化示例

### 4.1 合理使用数据结构

**场景**：选择合适的数据结构

```java
// 反例：使用 String 存储复杂数据
String userData = "{\"id\":123,\"name\":\"John\",\"email\":\"john@example.com\"}";
redisTemplate.opsForValue().set("user:123", userData);

// 正例：使用 Hash 存储复杂数据
redisTemplate.opsForHash().put("user:123", "id", "123");
redisTemplate.opsForHash().put("user:123", "name", "John");
redisTemplate.opsForHash().put("user:123", "email", "john@example.com");

// 优点：
// 1. 可以单独获取或修改字段
// 2. 节省内存空间
// 3. 提高访问效率
```

### 4.2 批量操作

**场景**：批量获取数据

```java
// 反例：多次单独获取
for (String key : keys) {
    String value = redisTemplate.opsForValue().get(key);
    // 处理 value
}

// 正例：使用管道批量获取
List<Object> values = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (String key : keys) {
        connection.stringCommands().get(key.getBytes());
    }
    return null;
});

// 正例：使用 MGET 命令
List<String> values = redisTemplate.opsForValue().multiGet(keys);

// 正例：使用 MSET 命令批量设置
Map<String, String> multiSetMap = new HashMap<>();
multiSetMap.put("key1", "value1");
multiSetMap.put("key2", "value2");
redisTemplate.opsForValue().multiSet(multiSetMap);
```

### 4.3 缓存预热

**场景**：系统启动时预热缓存

```java
@Component
public class CacheWarmer implements ApplicationRunner {
    
    @Autowired
    private RedisTemplate redisTemplate;
    
    @Autowired
    private UserRepository userRepository;
    
    @Override
    public void run(ApplicationArguments args) {
        // 预热热门用户数据
        List<User> hotUsers = userRepository.findTop10ByOrderByViewsDesc();
        for (User user : hotUsers) {
            String cacheKey = "cache:user:" + user.getId();
            redisTemplate.opsForValue().set(cacheKey, JSON.toJSONString(user), 1, TimeUnit.HOURS);
        }
        
        System.out.println("Cache warmed up with " + hotUsers.size() + " hot users");
    }
}
```

## 5. 注意事项

1. **键名设计**：使用统一的命名规范，如 `{prefix}:{entity}:{id}`
2. **过期时间**：为缓存数据设置合理的过期时间
3. **内存管理**：监控内存使用情况，避免内存泄漏
4. **错误处理**：处理 Redis 连接异常和命令执行错误
5. **测试**：编写单元测试和集成测试，确保功能正常

## 6. 工具类示例

### 6.1 Redis 工具类

```java
@Component
public class RedisUtils {
    
    @Autowired
    private RedisTemplate redisTemplate;
    
    // 设置值带过期时间
    public void setWithExpire(String key, Object value, long expireSeconds) {
        redisTemplate.opsForValue().set(key, value, expireSeconds, TimeUnit.SECONDS);
    }
    
    // 获取值
    public <T> T get(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? JSON.parseObject(value.toString(), clazz) : null;
    }
    
    // 批量获取
    public <T> Map<String, T> multiGet(Collection<String> keys, Class<T> clazz) {
        Map<String, T> result = new HashMap<>();
        List<Object> values = redisTemplate.opsForValue().multiGet(keys);
        if (values != null) {
            Iterator<String> keyIter = keys.iterator();
            Iterator<Object> valueIter = values.iterator();
            while (keyIter.hasNext() && valueIter.hasNext()) {
                String key = keyIter.next();
                Object value = valueIter.next();
                if (value != null) {
                    result.put(key, JSON.parseObject(value.toString(), clazz));
                }
            }
        }
        return result;
    }
    
    // 执行 Lua 脚本
    public <T> T executeScript(String script, List<String> keys, Object... args) {
        DefaultRedisScript<T> redisScript = new DefaultRedisScript<>(script, null);
        return redisTemplate.execute(redisScript, keys, args);
    }
}
```

## 7. 下一步

- **[API 文档](../api/)**：查看详细的 API 接口说明
- **[架构设计](../architecture/)**：了解系统架构和设计原理
- **[部署运维](../deployment/)**：学习生产环境部署和维护
