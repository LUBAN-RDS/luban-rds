package com.janeluo.luban.rds.client;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface RedisClient {
    // 字符串操作
    void set(String key, String value);
    String get(String key);
    Long incr(String key);
    Long decr(String key);
    Long incrBy(String key, long increment);
    Long decrBy(String key, long decrement);
    Long append(String key, String value);
    Long strlen(String key);
    
    // 哈希操作
    Long hset(String key, String field, String value);
    String hget(String key, String field);
    Map<String, String> hgetAll(String key);
    Long hdel(String key, String... fields);
    Boolean hexists(String key, String field);
    Set<String> hkeys(String key);
    List<String> hvals(String key);
    Long hlen(String key);
    
    // 列表操作
    Long lpush(String key, String... values);
    Long rpush(String key, String... values);
    String lpop(String key);
    String rpop(String key);
    Long llen(String key);
    List<String> lrange(String key, long start, long stop);
    
    // 集合操作
    Long sadd(String key, String... members);
    Long srem(String key, String... members);
    Set<String> smembers(String key);
    Boolean sismember(String key, String member);
    Long scard(String key);
    
    // 有序集合操作
    Long zadd(String key, double score, String member);
    List<String> zrange(String key, long start, long stop);
    Double zscore(String key, String member);
    Long zrem(String key, String... members);
    Long zcard(String key);
    
    // 通用操作
    Long exists(String... keys);
    Long del(String... keys);
    Boolean expire(String key, long seconds);
    Long ttl(String key);
    void flushAll();
    String type(String key);
    
    // 连接管理
    void connect();
    void disconnect();
    boolean isConnected();
}
