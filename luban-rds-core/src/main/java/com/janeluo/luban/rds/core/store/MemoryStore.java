package com.janeluo.luban.rds.core.store;

import java.util.List;

public interface MemoryStore {
    Object get(int database, String key);
    
    void set(int database, String key, Object value);
    
    /**
     * 批量设置键值对
     * @param database 数据库索引
     * @param keysAndValues 键值对数组，格式为 [key1, value1, key2, value2, ...]
     */
    void mset(int database, String... keysAndValues);
    
    /**
     * 批量获取键的值
     * @param database 数据库索引
     * @param keys 键数组
     * @return 值列表，不存在的键对应 null
     */
    List<Object> mget(int database, String... keys);
    
    /**
     * 设置带过期时间的键值对
     * @param database 数据库索引
     * @param key 键
     * @param value 值
     * @param expireSeconds 过期时间（秒）
     */
    void setWithExpire(int database, String key, Object value, long expireSeconds);

    /**
     * 设置带过期时间的键值对（毫秒）
     * @param database 数据库索引
     * @param key 键
     * @param value 值
     * @param expireMilliseconds 过期时间（毫秒）
     */
    default void setWithExpireMs(int database, String key, Object value, long expireMilliseconds) {
        setWithExpire(database, key, value, expireMilliseconds / 1000);
    }
    
    boolean del(int database, String key);
    
    boolean expire(int database, String key, long seconds);

    /**
     * 设置键的过期时间（毫秒）
     * @param database 数据库索引
     * @param key 键
     * @param milliseconds 过期时间（毫秒）
     * @return 是否设置成功
     */
    default boolean pexpire(int database, String key, long milliseconds) {
        return expire(database, key, milliseconds / 1000);
    }
    
    boolean exists(int database, String key);

    long ttl(int database, String key);

    /**
     * 获取键的剩余生存时间（毫秒）
     * @param database 数据库索引
     * @param key 键
     * @return 剩余时间（毫秒），-1表示无过期时间，-2表示键不存在
     */
    default long pttl(int database, String key) {
        long ttl = ttl(database, key);
        return ttl > 0 ? ttl * 1000 : ttl;
    }

    /**
     * 原子递增操作
     * @param database 数据库索引
     * @param key 键
     * @param increment 递增值
     * @return 递增后的新值
     */
    long incrby(int database, String key, long increment);
    
    void flushAll();
    
    String type(int database, String key);
    
    /**
     * 扫描数据库中的键
     * @param database 数据库索引
     * @param cursor 游标
     * @param pattern 匹配模式
     * @param count 计数
     * @return 包含新游标和匹配键的列表，格式为 [newCursor, key1, key2, ...]
     */
    List<Object> scan(int database, long cursor, String pattern, int count);
    
    /**
     * 返回当前数据库的键数量
     * @param database 数据库索引
     * @return 当前数据库的键数量
     */
    long dbsize(int database);
    
    /**
     * 删除当前数据库的所有键
     * @param database 数据库索引
     */
    void flushdb(int database);
    
    /**
     * 获取指定键的版本号（用于事务 WATCH 键变更检测）
     * @param database 数据库索引
     * @param key 键
     * @return 版本号，不存在返回0
     */
    long getKeyVersion(int database, String key);
    
    /**
     * 增加指定键的版本号（在任何写操作或元数据变更时调用）
     * @param database 数据库索引
     * @param key 键
     */
    void bumpKeyVersion(int database, String key);
    
    // ==================== Hash 操作优化接口 ====================
    
    /**
     * 设置 Hash 字段值（直接操作，避免复制整个 Map）
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @param value 字段值
     * @return 1 表示新增字段，0 表示更新已有字段
     */
    int hset(int database, String key, String field, String value);
    
    /**
     * 批量设置 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param fieldsAndValues 字段值对数组，格式为 [field1, value1, field2, value2, ...]
     * @return 新增字段的数量
     */
    int hmset(int database, String key, String... fieldsAndValues);
    
    /**
     * 当字段不存在时设置 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @param value 字段值
     * @return 1 表示成功设置（原先不存在），0 表示未设置（字段已存在）
     */
    int hsetnx(int database, String key, String field, String value);
    
    /**
     * 获取 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @return 字段值，不存在返回 null
     */
    String hget(int database, String key, String field);
    
    /**
     * 批量获取 Hash 字段值
     * @param database 数据库索引
     * @param key Hash 键
     * @param fields 字段名数组
     * @return 字段值列表，不存在的字段对应 null
     */
    List<String> hmget(int database, String key, String... fields);
    
    /**
     * 删除 Hash 字段
     * @param database 数据库索引
     * @param key Hash 键
     * @param fields 要删除的字段名数组
     * @return 删除的字段数量
     */
    int hdel(int database, String key, String... fields);
    
    /**
     * 检查 Hash 字段是否存在
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @return 是否存在
     */
    boolean hexists(int database, String key, String field);

    /**
     * 为哈希表中的字段值加上指定增量值
     * @param database 数据库索引
     * @param key Hash 键
     * @param field 字段名
     * @param increment 增量值
     * @return 增量后的值
     */
    long hincrby(int database, String key, String field, long increment);
    
    /**
     * 获取 Hash 的所有字段和值
     * @param database 数据库索引
     * @param key Hash 键
     * @return 字段和值的 Map
     */
    java.util.Map<String, String> hgetall(int database, String key);
    
    /**
     * 获取 Hash 的字段数量
     * @param database 数据库索引
     * @param key Hash 键
     * @return 字段数量
     */
    int hlen(int database, String key);
    
    /**
     * 扫描 Hash 字段
     * @param database 数据库索引
     * @param key Hash 键
     * @param cursor 游标
     * @param pattern 字段匹配模式（glob）
     * @param count 返回的最大字段数
     * @return [newCursor, field1, value1, field2, value2, ...]
     */
    java.util.List<Object> hscan(int database, String key, long cursor, String pattern, int count);
    
    // ==================== List 操作优化接口 ====================
    
    /**
     * 从列表左侧插入元素（直接操作，避免复制整个 List）
     * @param database 数据库索引
     * @param key List 键
     * @param values 要插入的值
     * @return 插入后列表的长度
     */
    int lpush(int database, String key, String... values);
    
    /**
     * 从列表右侧插入元素
     * @param database 数据库索引
     * @param key List 键
     * @param values 要插入的值
     * @return 插入后列表的长度
     */
    int rpush(int database, String key, String... values);
    
    /**
     * 从列表左侧弹出元素
     * @param database 数据库索引
     * @param key List 键
     * @return 弹出的元素，列表为空返回 null
     */
    String lpop(int database, String key);
    
    /**
     * 从列表右侧弹出元素
     * @param database 数据库索引
     * @param key List 键
     * @return 弹出的元素，列表为空返回 null
     */
    String rpop(int database, String key);
    
    /**
     * 移除列表元素
     * @param database 数据库索引
     * @param key 列表键
     * @param count 移除数量
     * @param value 元素值
     * @return 移除数量
     */
    int lrem(int database, String key, int count, String value);

    /**
     * 获取列表长度
     * @param database 数据库索引
     * @param key 列表键
     * @return 列表长度
     */
    int llen(int database, String key);
    
    /**
     * 获取列表指定索引的元素
     * @param database 数据库索引
     * @param key 列表键
     * @param index 索引
     * @return 元素值
     */
    String lindex(int database, String key, int index);

    /**
     * 设置列表指定索引的元素
     * @param database 数据库索引
     * @param key 列表键
     * @param index 索引
     * @param value 元素值
     * @throws RuntimeException 如果键不存在或索引越界
     */
    void lset(int database, String key, int index, String value);

    /**
     * 获取列表指定范围的元素
     * @param database 数据库索引
     * @param key List 键
     * @param start 起始索引
     * @param stop 结束索引
     * @return 元素列表
     */
    java.util.List<String> lrange(int database, String key, long start, long stop);
    
    // ==================== Set 操作优化接口 ====================
    
    /**
     * 向集合添加元素（直接操作，避免复制整个 Set）
     * @param database 数据库索引
     * @param key Set 键
     * @param members 要添加的成员
     * @return 新添加的成员数量
     */
    int sadd(int database, String key, String... members);
    
    /**
     * 从集合删除元素
     * @param database 数据库索引
     * @param key Set 键
     * @param members 要删除的成员
     * @return 删除的成员数量
     */
    int srem(int database, String key, String... members);
    
    /**
     * 检查成员是否在集合中
     * @param database 数据库索引
     * @param key Set 键
     * @param member 成员
     * @return 是否存在
     */
    boolean sismember(int database, String key, String member);
    
    /**
     * 获取集合所有成员
     * @param database 数据库索引
     * @param key Set 键
     * @return 成员集合
     */
    java.util.Set<String> smembers(int database, String key);
    
    /**
     * 获取集合成员数量
     * @param database 数据库索引
     * @param key Set 键
     * @return 成员数量
     */
    int scard(int database, String key);

    /**
     * 返回多个集合的交集
     * @param database 数据库索引
     * @param keys 集合键数组
     * @return 交集结果
     */
    java.util.Set<String> sinter(int database, String... keys);

    /**
     * 返回多个集合的并集
     * @param database 数据库索引
     * @param keys 集合键数组
     * @return 并集结果
     */
    java.util.Set<String> sunion(int database, String... keys);

    /**
     * 返回多个集合的差集
     * @param database 数据库索引
     * @param keys 集合键数组（第一个集合为基准）
     * @return 差集结果
     */
    java.util.Set<String> sdiff(int database, String... keys);

    // ==================== ZSet 操作优化接口 ====================
    
    /**
     * 向有序集合添加成员（使用跳表结构，保持有序）
     * @param database 数据库索引
     * @param key ZSet 键
     * @param score 分数
     * @param member 成员
     * @return 1 表示新增，0 表示更新
     */
    int zadd(int database, String key, double score, String member);
    
    /**
     * 从有序集合删除成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param members 要删除的成员
     * @return 删除的成员数量
     */
    int zrem(int database, String key, String... members);
    
    /**
     * 获取成员的分数
     * @param database 数据库索引
     * @param key ZSet 键
     * @param member 成员
     * @return 分数，不存在返回 null
     */
    Double zscore(int database, String key, String member);
    
    /**
     * 按分数范围获取成员（已排序）
     * @param database 数据库索引
     * @param key ZSet 键
     * @param start 起始索引
     * @param stop 结束索引
     * @return 成员列表（按分数升序）
     */
    java.util.List<String> zrange(int database, String key, long start, long stop);
    
    /**
     * 获取有序集合成员数量
     * @param database 数据库索引
     * @param key ZSet 键
     * @return 成员数量
     */
    int zcard(int database, String key);
    
    /**
     * 按分数范围获取成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param min 最小分数
     * @param max 最大分数
     * @param offset 偏移量
     * @param count 数量
     * @return 成员列表
     */
    java.util.List<String> zrangeByScore(int database, String key, double min, double max, int offset, int count);

    /**
     * 获取有序集合的所有成员和分数
     * @param database 数据库索引
     * @param key ZSet 键
     * @return 成员和分数的映射，键不存在返回 null
     */
    java.util.Map<String, Double> zgetAllWithScores(int database, String key);

    /**
     * 获取指定键占用的内存大小（字节）
     * @param database 数据库索引
     * @param key 键
     * @return 占用字节数，如果键不存在返回 null
     */
    Long getMemoryUsage(int database, String key);
    
    /**
     * 获取当前使用的内存总量（字节）
     */
    long getUsedMemory();
    
    /**
     * 获取历史峰值内存使用量（字节）
     */
    long getPeakUsedMemory();
}
