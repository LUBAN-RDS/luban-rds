package com.janeluo.luban.rds.client;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis客户端接口
 * 
 * <p>定义Redis客户端的基本操作契约，支持：
 * <ul>
 *   <li>字符串操作（SET、GET、INCR、DECR等）</li>
 *   <li>哈希操作（HSET、HGET、HGETALL等）</li>
 *   <li>列表操作（LPUSH、RPUSH、LPOP、RPOP等）</li>
 *   <li>集合操作（SADD、SREM、SMEMBERS等）</li>
 *   <li>有序集合操作（ZADD、ZRANGE、ZSCORE等）</li>
 *   <li>通用操作（EXISTS、DEL、EXPIRE、TTL等）</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public interface RedisClient {
    
    // ==================== 字符串操作 ====================
    
    /**
     * 设置键值对
     *
     * @param key 键
     * @param value 值
     */
    void set(String key, String value);
    
    /**
     * 获取键对应的值
     *
     * @param key 键
     * @return 值，如果不存在返回null
     */
    String get(String key);
    
    /**
     * 将键的值加1
     *
     * @param key 键
     * @return 增加后的值
     */
    Long incr(String key);
    
    /**
     * 将键的值减1
     *
     * @param key 键
     * @return 减少后的值
     */
    Long decr(String key);
    
    /**
     * 将键的值增加指定数值
     *
     * @param key 键
     * @param increment 增量
     * @return 增加后的值
     */
    Long incrBy(String key, long increment);
    
    /**
     * 将键的值减少指定数值
     *
     * @param key 键
     * @param decrement 减量
     * @return 减少后的值
     */
    Long decrBy(String key, long decrement);
    
    /**
     * 追加值到键对应的字符串末尾
     *
     * @param key 键
     * @param value 要追加的值
     * @return 追加后的字符串长度
     */
    Long append(String key, String value);
    
    /**
     * 获取键对应字符串的长度
     *
     * @param key 键
     * @return 字符串长度
     */
    Long strlen(String key);
    
    // ==================== 哈希操作 ====================
    
    /**
     * 设置哈希字段的值
     *
     * @param key 键
     * @param field 字段名
     * @param value 字段值
     * @return 如果是新字段返回1，如果字段已存在返回0
     */
    Long hset(String key, String field, String value);
    
    /**
     * 获取哈希字段的值
     *
     * @param key 键
     * @param field 字段名
     * @return 字段值，如果不存在返回null
     */
    String hget(String key, String field);
    
    /**
     * 获取哈希的所有字段和值
     *
     * @param key 键
     * @return 字段和值的映射
     */
    Map<String, String> hgetAll(String key);
    
    /**
     * 删除哈希的一个或多个字段
     *
     * @param key 键
     * @param fields 字段名数组
     * @return 被删除字段的数量
     */
    Long hdel(String key, String... fields);
    
    /**
     * 检查哈希字段是否存在
     *
     * @param key 键
     * @param field 字段名
     * @return 如果存在返回true，否则返回false
     */
    Boolean hexists(String key, String field);
    
    /**
     * 获取哈希的所有字段名
     *
     * @param key 键
     * @return 字段名集合
     */
    Set<String> hkeys(String key);
    
    /**
     * 获取哈希的所有字段值
     *
     * @param key 键
     * @return 字段值列表
     */
    List<String> hvals(String key);
    
    /**
     * 获取哈希的字段数量
     *
     * @param key 键
     * @return 字段数量
     */
    Long hlen(String key);
    
    // ==================== 列表操作 ====================
    
    /**
     * 将一个或多个值插入列表头部
     *
     * @param key 键
     * @param values 值数组
     * @return 插入后列表的长度
     */
    Long lpush(String key, String... values);
    
    /**
     * 将一个或多个值插入列表尾部
     *
     * @param key 键
     * @param values 值数组
     * @return 插入后列表的长度
     */
    Long rpush(String key, String... values);
    
    /**
     * 移除并返回列表头部的元素
     *
     * @param key 键
     * @return 头部元素，如果列表为空返回null
     */
    String lpop(String key);
    
    /**
     * 移除并返回列表尾部的元素
     *
     * @param key 键
     * @return 尾部元素，如果列表为空返回null
     */
    String rpop(String key);
    
    /**
     * 获取列表的长度
     *
     * @param key 键
     * @return 列表长度
     */
    Long llen(String key);
    
    /**
     * 获取列表指定范围内的元素
     *
     * @param key 键
     * @param start 起始位置
     * @param stop 结束位置
     * @return 元素列表
     */
    List<String> lrange(String key, long start, long stop);
    
    // ==================== 集合操作 ====================
    
    /**
     * 向集合添加一个或多个成员
     *
     * @param key 键
     * @param members 成员数组
     * @return 添加的新成员数量
     */
    Long sadd(String key, String... members);
    
    /**
     * 从集合移除一个或多个成员
     *
     * @param key 键
     * @param members 成员数组
     * @return 被移除的成员数量
     */
    Long srem(String key, String... members);
    
    /**
     * 获取集合的所有成员
     *
     * @param key 键
     * @return 成员集合
     */
    Set<String> smembers(String key);
    
    /**
     * 检查成员是否是集合的成员
     *
     * @param key 键
     * @param member 成员
     * @return 如果是成员返回true，否则返回false
     */
    Boolean sismember(String key, String member);
    
    /**
     * 获取集合的成员数量
     *
     * @param key 键
     * @return 成员数量
     */
    Long scard(String key);
    
    // ==================== 有序集合操作 ====================
    
    /**
     * 向有序集合添加成员
     *
     * @param key 键
     * @param score 分数
     * @param member 成员
     * @return 如果是新成员返回1，如果成员已存在返回0
     */
    Long zadd(String key, double score, String member);
    
    /**
     * 获取有序集合指定范围内的成员
     *
     * @param key 键
     * @param start 起始位置
     * @param stop 结束位置
     * @return 成员列表
     */
    List<String> zrange(String key, long start, long stop);
    
    /**
     * 获取有序集合成员的分数
     *
     * @param key 键
     * @param member 成员
     * @return 分数，如果成员不存在返回null
     */
    Double zscore(String key, String member);
    
    /**
     * 从有序集合移除一个或多个成员
     *
     * @param key 键
     * @param members 成员数组
     * @return 被移除的成员数量
     */
    Long zrem(String key, String... members);
    
    /**
     * 获取有序集合的成员数量
     *
     * @param key 键
     * @return 成员数量
     */
    Long zcard(String key);
    
    // ==================== 通用操作 ====================
    
    /**
     * 检查一个或多个键是否存在
     *
     * @param keys 键数组
     * @return 存在的键数量
     */
    Long exists(String... keys);
    
    /**
     * 删除一个或多个键
     *
     * @param keys 键数组
     * @return 被删除的键数量
     */
    Long del(String... keys);
    
    /**
     * 设置键的过期时间
     *
     * @param key 键
     * @param seconds 过期时间（秒）
     * @return 如果设置成功返回true，否则返回false
     */
    Boolean expire(String key, long seconds);
    
    /**
     * 获取键的剩余过期时间
     *
     * @param key 键
     * @return 剩余过期时间（秒），如果键不存在返回-2，如果键没有过期时间返回-1
     */
    Long ttl(String key);
    
    /**
     * 清空所有数据库
     */
    void flushAll();
    
    /**
     * 获取键的数据类型
     *
     * @param key 键
     * @return 数据类型字符串
     */
    String type(String key);
    
    // ==================== 连接管理 ====================
    
    /**
     * 连接到服务器
     */
    void connect();
    
    /**
     * 断开与服务器的连接
     */
    void disconnect();
    
    /**
     * 检查是否已连接
     *
     * @return 如果已连接返回true，否则返回false
     */
    boolean isConnected();
}
