package com.janeluo.luban.rds.core.store;

import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamConsumerGroupManager;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;

import java.util.List;
import java.util.Map;

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

    /**
     * 裁剪列表，保留指定范围内的元素
     * @param database 数据库索引
     * @param key List 键
     * @param start 起始索引
     * @param stop 结束索引
     */
    void ltrim(int database, String key, long start, long stop);

    /**
     * 阻塞式从列表左侧弹出元素
     * @param database 数据库索引
     * @param keys 列表键数组
     * @param timeout 超时时间（秒），0表示无限等待
     * @return 弹出结果 [key, value]，超时返回 null
     */
    java.util.List<String> blpop(int database, String[] keys, long timeout);

    /**
     * 阻塞式从列表右侧弹出元素
     * @param database 数据库索引
     * @param keys 列表键数组
     * @param timeout 超时时间（秒），0表示无限等待
     * @return 弹出结果 [key, value]，超时返回 null
     */
    java.util.List<String> brpop(int database, String[] keys, long timeout);
    
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

    /**
     * 扫描集合成员
     * @param database 数据库索引
     * @param key Set 键
     * @param cursor 游标
     * @param pattern 成员匹配模式（glob）
     * @param count 返回的最大成员数
     * @return [newCursor, member1, member2, ...]
     */
    java.util.List<Object> sscan(int database, String key, long cursor, String pattern, int count);

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
     * 扫描有序集合成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param cursor 游标
     * @param pattern 成员匹配模式（glob）
     * @param count 返回的最大成员数
     * @return [newCursor, member1, score1, member2, score2, ...]
     */
    java.util.List<Object> zscan(int database, String key, long cursor, String pattern, int count);

    /**
     * 按分数范围删除成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param min 最小分数
     * @param max 最大分数
     * @return 删除的成员数量
     */
    int zremrangeByScore(int database, String key, double min, double max);

    /**
     * 按排名范围删除成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param start 起始排名
     * @param stop 结束排名
     * @return 删除的成员数量
     */
    int zremrangeByRank(int database, String key, long start, long stop);

    /**
     * 获取成员排名（按分数升序，从0开始）
     * @param database 数据库索引
     * @param key ZSet 键
     * @param member 成员
     * @return 排名，不存在返回 null
     */
    Long zrank(int database, String key, String member);

    /**
     * 获取成员排名（按分数降序，从0开始）
     * @param database 数据库索引
     * @param key ZSet 键
     * @param member 成员
     * @return 排名，不存在返回 null
     */
    Long zrevrank(int database, String key, String member);

    /**
     * 增加成员分数
     * @param database 数据库索引
     * @param key ZSet 键
     * @param increment 增量
     * @param member 成员
     * @return 增量后的分数
     */
    double zincrby(int database, String key, double increment, String member);

    /**
     * 统计分数范围内的成员数量
     * @param database 数据库索引
     * @param key ZSet 键
     * @param min 最小分数
     * @param max 最大分数
     * @return 成员数量
     */
    int zcount(int database, String key, double min, double max);

    /**
     * 弹出分数最高的成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param count 弹出数量
     * @return 成员和分数列表 [member1, score1, member2, score2, ...]
     */
    java.util.List<String> zpopmax(int database, String key, int count);

    /**
     * 弹出分数最低的成员
     * @param database 数据库索引
     * @param key ZSet 键
     * @param count 弹出数量
     * @return 成员和分数列表 [member1, score1, member2, score2, ...]
     */
    java.util.List<String> zpopmin(int database, String key, int count);

    /**
     * 按分数降序获取成员范围
     * @param database 数据库索引
     * @param key ZSet 键
     * @param start 起始排名
     * @param stop 结束排名
     * @return 成员列表
     */
    java.util.List<String> zrevrange(int database, String key, long start, long stop);

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
    
    /**
     * 计算内存碎片率
     * Fragmentation ratio = (usedMemory - effectiveMemory) / usedMemory * 100
     * 
     * @return Memory fragmentation ratio (percentage)
     */
    double getMemoryFragmentationRatio();
    
    /**
     * Execute memory defragmentation
     * Cleans expired keys and compresses internal data structures
     * 
     * @return Amount of memory freed in bytes
     */
    long defragment();
    
    /**
     * Get memory statistics
     *
     * @return MemoryStats object containing memory usage information
     */
    MemoryStats getMemoryStats();

    // ===== 内存管理 admin 方法（S1：从 DefaultMemoryStore 专有提升到接口）=====

    /**
     * 最大内存上限（字节）。0 表示不限制。
     */
    long getMaxMemory();

    /**
     * 设置最大内存上限（字节）。0 表示不限制。
     */
    void setMaxMemory(long maxMemory);

    /**
     * 当前淘汰策略（noeviction / allkeys-lru / volatile-lru / allkeys-random /
     * volatile-random / volatile-ttl）。
     */
    String getMaxMemoryPolicy();

    /**
     * 设置淘汰策略。
     */
    void setMaxMemoryPolicy(String policy);

    /**
     * 软限制百分比（0-100），超过时触发告警。
     */
    int getSoftLimitPercent();

    /**
     * 是否已超过软限制。
     */
    boolean isSoftLimitExceeded();

    /**
     * 最大 db 数量。
     */
    int getMaxDatabases();

    /**
     * LRU 采样数量（参考 Redis maxmemory-samples）。
     */
    int getLruSampleSize();

    /**
     * 设置 LRU 采样数量。
     */
    void setLruSampleSize(int size);

    /**
     * 设置软限制百分比（0-100）。
     */
    void setSoftLimitPercent(int percent);

    // ==================== Stream 操作接口 ====================
    
    /**
     * 添加消息到流
     * @param database 数据库索引
     * @param key 流键
     * @param id 消息 ID（null 表示自动生成）
     * @param fields 字段值对
     * @param nomkstream 流不存在时不创建
     * @param maxLen 最大长度（null 表示不限制）
     * @param minId 最小 ID（null 表示不限制）
     * @param limit 裁剪限制
     * @param approximate 是否近似裁剪
     * @return 生成的消息 ID，失败返回 null
     */
    StreamId xadd(int database, String key, StreamId id, Map<String, String> fields,
                  boolean nomkstream, Long maxLen, StreamId minId, Integer limit, boolean approximate);
    
    /**
     * 获取流中消息数量
     * @param database 数据库索引
     * @param key 流键
     * @return 消息数量
     */
    long xlen(int database, String key);
    
    /**
     * 范围查询消息
     * @param database 数据库索引
     * @param key 流键
     * @param start 起始 ID
     * @param end 结束 ID
     * @param exclusiveStart 是否开区间起始
     * @param exclusiveEnd 是否开区间结束
     * @param count 返回数量限制
     * @param reverse 是否降序
     * @return 消息列表
     */
    List<StreamEntry> xrange(int database, String key, StreamId start, StreamId end,
                            boolean exclusiveStart, boolean exclusiveEnd, int count, boolean reverse);
    
    /**
     * 删除消息
     * @param database 数据库索引
     * @param key 流键
     * @param ids 消息 ID 数组
     * @return 删除的消息数量
     */
    long xdel(int database, String key, StreamId... ids);
    
    /**
     * 裁剪流
     * @param database 数据库索引
     * @param key 流键
     * @param maxLen 最大长度（null 表示不限制）
     * @param minId 最小 ID（null 表示不限制）
     * @param limit 裁剪限制
     * @param approximate 是否近似裁剪
     * @return 删除的消息数量
     */
    long xtrim(int database, String key, Long maxLen, StreamId minId, Integer limit, boolean approximate);
    
    /**
     * 获取流对象
     * @param database 数据库索引
     * @param key 流键
     * @return Stream 对象，不存在返回 null
     */
    Stream getStream(int database, String key);
    
    /**
     * 创建消费者组
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param id 起始 ID
     * @param mkstream 流不存在时是否创建
     * @return 是否成功
     */
    boolean xgroupCreate(int database, String key, String group, StreamId id, boolean mkstream);
    
    /**
     * 销毁消费者组
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @return 是否成功
     */
    boolean xgroupDestroy(int database, String key, String group);
    
    /**
     * 删除消费者
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param consumer 消费者名称
     * @return 删除的待处理消息数量
     */
    long xgroupDelConsumer(int database, String key, String group, String consumer);
    
    /**
     * 设置消费者组最后传递 ID
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param id 消息 ID
     * @return 是否成功
     */
    boolean xgroupSetId(int database, String key, String group, StreamId id);
    
    /**
     * 消费者组读取消息
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param consumer 消费者名称
     * @param id 起始 ID
     * @param count 返回数量限制
     * @param noack 是否不需要确认
     * @return 消费者组名称到消息列表的映射
     */
    Map<String, List<StreamEntry>> xreadGroup(int database, String key, String group, String consumer,
                                              StreamId id, int count, boolean noack);
    
    /**
     * 确认消息
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param ids 消息 ID 数组
     * @return 确认的消息数量
     */
    long xack(int database, String key, String group, StreamId... ids);
    
    /**
     * 获取待处理消息摘要
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @return 摘要信息映射
     */
    Map<String, Object> xpendingSummary(int database, String key, String group);
    
    /**
     * 获取待处理消息详细列表
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param start 起始 ID
     * @param end 结束 ID
     * @param count 返回数量限制
     * @param consumer 消费者名称过滤（null 表示不过滤）
     * @param minIdleTime 最小空闲时间（毫秒）
     * @return 待处理消息详细列表
     */
    List<Map<String, Object>> xpendingList(int database, String key, String group,
                                          StreamId start, StreamId end, int count, 
                                          String consumer, long minIdleTime);
    
    /**
     * 转移消息所有权
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param consumer 消费者名称
     * @param minIdleTime 最小空闲时间（毫秒）
     * @param ids 消息 ID 数组
     * @param justId 是否只返回 ID
     * @param force 是否强制转移
     * @return 消息列表
     */
    List<StreamEntry> xclaim(int database, String key, String group, String consumer,
                             long minIdleTime, StreamId[] ids, boolean justId, boolean force);
    
    /**
     * 自动转移超时消息
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @param consumer 消费者名称
     * @param minIdleTime 最小空闲时间（毫秒）
     * @param start 起始 ID
     * @param count 返回数量限制
     * @return 结果映射
     */
    Map<String, Object> xautoclaim(int database, String key, String group, String consumer,
                                   long minIdleTime, StreamId start, int count);
    
    /**
     * 读取消息
     * @param database 数据库索引
     * @param keys 流键列表
     * @param ids 起始 ID 列表
     * @param count 返回数量限制
     * @param block 阻塞时间（毫秒）
     * @return 流键到消息列表的映射
     */
    Map<String, List<StreamEntry>> xread(int database, List<String> keys, List<StreamId> ids, int count, long block);
    
    /**
     * 获取消费者组管理器
     * @param database 数据库索引
     * @param key 流键
     * @return 消费者组管理器，不存在返回 null
     */
    StreamConsumerGroupManager getStreamConsumerGroupManager(int database, String key);
    
    /**
     * 获取流信息
     * @param database 数据库索引
     * @param key 流键
     * @return 流信息映射
     */
    Map<String, Object> xinfoStream(int database, String key);
    
    /**
     * 获取消费者组列表
     * @param database 数据库索引
     * @param key 流键
     * @return 消费者组信息列表
     */
    List<Map<String, Object>> xinfoGroups(int database, String key);
    
    /**
     * 获取消费者列表
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @return 消费者信息列表
     */
    List<Map<String, Object>> xinfoConsumers(int database, String key, String group);
    
    /**
     * 获取消费者组的最后投递 ID
     * @param database 数据库索引
     * @param key 流键
     * @param group 消费者组名称
     * @return 最后投递 ID，不存在返回 null
     */
    StreamId getGroupLastDeliveredId(int database, String key, String group);
    
    // ==================== 槽位操作接口 ====================
    
    /**
     * 获取指定槽位中的键列表
     * @param database 数据库索引
     * @param slot 槽位号
     * @param count 最大返回数量
     * @return 键列表
     */
    List<String> getKeysInSlot(int database, int slot, int count);
    
    /**
     * 获取指定槽位中的键数量
     * @param database 数据库索引
     * @param slot 槽位号
     * @return 键数量
     */
    int countKeysInSlot(int database, int slot);
    
    /**
     * 获取键所属的槽位
     * @param key 键名
     * @return 槽位号
     */
    int getKeySlot(String key);
    
    /**
     * Memory statistics information class
     */
    class MemoryStats {
        private final long usedMemory;
        private final long peakMemory;
        private final long maxMemory;
        private final double fragmentationRatio;
        private final int totalKeys;
        private final int expiredKeys;
        
        public MemoryStats(long usedMemory, long peakMemory, long maxMemory, 
                          double fragmentationRatio, int totalKeys, int expiredKeys) {
            this.usedMemory = usedMemory;
            this.peakMemory = peakMemory;
            this.maxMemory = maxMemory;
            this.fragmentationRatio = fragmentationRatio;
            this.totalKeys = totalKeys;
            this.expiredKeys = expiredKeys;
        }
        
        public long getUsedMemory() {
            return usedMemory;
        }
        
        public long getPeakMemory() {
            return peakMemory;
        }
        
        public long getMaxMemory() {
            return maxMemory;
        }
        
        public double getFragmentationRatio() {
            return fragmentationRatio;
        }
        
        public int getTotalKeys() {
            return totalKeys;
        }
        
        public int getExpiredKeys() {
            return expiredKeys;
        }
    }
}
