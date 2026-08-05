package com.janeluo.luban.rds.core.store;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamConsumerGroupManager;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 混合存储：string 且 UTF-8 字节长度 ≥ threshold → 堆外 ByteBuf；其余 → 堆上。
 *
 * <p>按 type+size 路由，跨引擎一致性（type 切换前先清旧引擎）。
 *
 * <ul>
 *   <li>大 string：{@link OffHeapStringEngine}</li>
 *   <li>小 string / hash / list / set / zset / stream：{@link OnHeapStructEngine}
 *       （继承 {@link DefaultMemoryStore}，复用全部已验证逻辑）</li>
 * </ul>
 *
 * <p>{@link EvictionScheduler} 做跨引擎淘汰；{@link ExpireCoordinator} 做周期过期。
 */
public class HybridMemoryStore implements MemoryStore {

    private final OffHeapStringEngine offheap;
    private final OnHeapStructEngine onheap;
    private final int threshold;
    private final int maxDatabases;
    private final EvictionScheduler evictionScheduler;
    private final ExpireCoordinator expireCoordinator;
    private final ScheduledExecutorService expireExecutor;

    public HybridMemoryStore(int databases, long maxMemory, String policy, int threshold) {
        this.maxDatabases = databases;
        this.threshold = threshold;
        this.offheap = new OffHeapStringEngine(threshold);
        this.onheap = new OnHeapStructEngine(databases, maxMemory, policy);
        this.evictionScheduler = new EvictionScheduler(offheap, onheap, maxMemory, policy);
        this.expireCoordinator = new ExpireCoordinator(offheap, onheap, databases);
        this.expireExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HybridExpire-Worker");
            t.setDaemon(true);
            return t;
        });
        this.expireExecutor.scheduleAtFixedRate(this::safeExpireCycle, 100, 100, TimeUnit.MILLISECONDS);
    }

    public HybridMemoryStore(RdsConfig config) {
        this(config.getDatabases(), config.getMaxmemory(), config.getMaxmemoryPolicy(), config.getOffheapThreshold());
    }

    private void safeExpireCycle() {
        try {
            expireCoordinator.runCycle();
        } catch (Exception e) {
            // 不中断周期
        }
    }

    /** 堆外引擎当前占用字节数（供测试/监控）。 */
    public long getOffheapUsedMemory() {
        return offheap.estimateUsedMemory();
    }

    // ========== 路由辅助 ==========

    /** 判断 string 是否进堆外（按 UTF-8 字节长度）。 */
    private boolean isLargeString(String value) {
        if (value == null) {
            return false;
        }
        return value.getBytes(StandardCharsets.UTF_8).length >= threshold;
    }

    /**
     * 写非 string（结构化类型）前：若旧 string 在堆外则释放 ByteBuf。
     */
    private void clearOtherEngineBeforeNonString(int db, String key) {
        offheap.del(db, key); // 存在则释放，不存在返回 false
    }

    /**
     * 写 string 前：若旧值在堆上（hash/list 等）则清掉。
     */
    private void clearOnHeapBeforeString(int db, String key) {
        if (onheap.exists(db, key)) {
            onheap.del(db, key);
        }
    }

    // ========== Generic string 路由 ==========

    @Override
    public Object get(int database, String key) {
        // 先查堆外（大 string）
        String off = offheap.get(database, key);
        if (off != null) {
            return off;
        }
        return onheap.get(database, key);
    }

    @Override
    public void set(int database, String key, Object value) {
        if (value instanceof String) {
            String s = (String) value;
            clearOnHeapBeforeString(database, key); // 若旧值在堆上（hash 等），清掉
            if (isLargeString(s)) {
                offheap.set(database, key, s);
            } else {
                onheap.set(database, key, value);
            }
        } else {
            clearOtherEngineBeforeNonString(database, key); // 若旧 string 在堆外，释放 ByteBuf
            onheap.set(database, key, value);
        }
    }

    @Override
    public void mset(int database, String... keysAndValues) {
        // mset 格式 [k1,v1,k2,v2,...]，逐个走 set 路由
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            set(database, keysAndValues[i], keysAndValues[i + 1]);
        }
    }

    @Override
    public List<Object> mget(int database, String... keys) {
        List<Object> r = new ArrayList<>(keys.length);
        for (String k : keys) {
            r.add(get(database, k));
        }
        return r;
    }

    @Override
    public void setWithExpire(int database, String key, Object value, long expireSeconds) {
        long expireTimeMs = System.currentTimeMillis() + expireSeconds * 1000L;
        if (value instanceof String) {
            String s = (String) value;
            clearOnHeapBeforeString(database, key);
            if (isLargeString(s)) {
                offheap.setWithExpire(database, key, s, expireTimeMs);
            } else {
                onheap.setWithExpire(database, key, value, expireSeconds);
            }
        } else {
            clearOtherEngineBeforeNonString(database, key);
            onheap.setWithExpire(database, key, value, expireSeconds);
        }
    }

    @Override
    public void setWithExpireMs(int database, String key, Object value, long expireMilliseconds) {
        long expireTimeMs = System.currentTimeMillis() + expireMilliseconds;
        if (value instanceof String) {
            String s = (String) value;
            clearOnHeapBeforeString(database, key);
            if (isLargeString(s)) {
                offheap.setWithExpire(database, key, s, expireTimeMs);
            } else {
                onheap.setWithExpireMs(database, key, value, expireMilliseconds);
            }
        } else {
            clearOtherEngineBeforeNonString(database, key);
            onheap.setWithExpireMs(database, key, value, expireMilliseconds);
        }
    }

    @Override
    public boolean del(int database, String key) {
        boolean a = offheap.del(database, key);
        boolean b = onheap.del(database, key);
        return a || b;
    }

    @Override
    public boolean expire(int database, String key, long seconds) {
        long expireTimeMs = System.currentTimeMillis() + seconds * 1000L;
        if (offheap.expire(database, key, expireTimeMs)) {
            return true;
        }
        return onheap.expire(database, key, seconds);
    }

    @Override
    public boolean pexpire(int database, String key, long milliseconds) {
        long expireTimeMs = System.currentTimeMillis() + milliseconds;
        if (offheap.expire(database, key, expireTimeMs)) {
            return true;
        }
        return onheap.pexpire(database, key, milliseconds);
    }

    @Override
    public boolean exists(int database, String key) {
        return offheap.exists(database, key) || onheap.exists(database, key);
    }

    @Override
    public long ttl(int database, String key) {
        long ohTtlMs = offheap.ttlMs(database, key);
        if (ohTtlMs != -2L) {
            // -1 无过期；否则 ms → s
            return ohTtlMs == -1L ? -1L : ohTtlMs / 1000;
        }
        return onheap.ttl(database, key);
    }

    @Override
    public long pttl(int database, String key) {
        long ohTtlMs = offheap.ttlMs(database, key);
        if (ohTtlMs != -2L) {
            return ohTtlMs;
        }
        return onheap.pttl(database, key);
    }

    @Override
    public long incrby(int database, String key, long increment) {
        // incrby 针对数值 string。确保结果落到 onheap（数值通常 < threshold）。
        Object cur = get(database, key);
        long val = 0;
        if (cur != null) {
            try {
                val = Long.parseLong(cur.toString());
            } catch (NumberFormatException e) {
                throw new RuntimeException("value is not an integer or out of range");
            }
        }
        val += increment;
        set(database, key, String.valueOf(val));
        return val;
    }

    @Override
    public void flushAll() {
        offheap.flushAll();
        onheap.flushAll();
    }

    @Override
    public String type(int database, String key) {
        if (offheap.exists(database, key)) {
            return "string";
        }
        return onheap.type(database, key);
    }

    @Override
    public List<Object> scan(int database, long cursor, String pattern, int count) {
        // 聚合：onheap.scan 已实现 cursor 语义；offheap 一次性按 pattern 返回（堆外数量少）。
        // 简化：onheap scan 结果 + offheap 匹配 key（offheap 全为 string）。
        List<Object> r = new ArrayList<>(onheap.scan(database, cursor, pattern, count));
        for (String k : offheap.scanKeys(database, pattern)) {
            r.add(k);
        }
        return r;
    }

    @Override
    public long dbsize(int database) {
        return offheap.size(database) + onheap.dbsize(database);
    }

    @Override
    public void flushdb(int database) {
        offheap.flushdb(database);
        onheap.flushdb(database);
    }

    @Override
    public long getKeyVersion(int database, String key) {
        return onheap.getKeyVersion(database, key);
    }

    @Override
    public void bumpKeyVersion(int database, String key) {
        onheap.bumpKeyVersion(database, key);
    }

    // ==================== Hash（全 onheap）====================

    @Override
    public int hset(int database, String key, String field, String value) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.hset(database, key, field, value);
    }

    @Override
    public int hmset(int database, String key, String... fieldsAndValues) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.hmset(database, key, fieldsAndValues);
    }

    @Override
    public int hsetnx(int database, String key, String field, String value) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.hsetnx(database, key, field, value);
    }

    @Override
    public String hget(int database, String key, String field) {
        return onheap.hget(database, key, field);
    }

    @Override
    public List<String> hmget(int database, String key, String... fields) {
        return onheap.hmget(database, key, fields);
    }

    @Override
    public int hdel(int database, String key, String... fields) {
        return onheap.hdel(database, key, fields);
    }

    @Override
    public boolean hexists(int database, String key, String field) {
        return onheap.hexists(database, key, field);
    }

    @Override
    public long hincrby(int database, String key, String field, long increment) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.hincrby(database, key, field, increment);
    }

    @Override
    public Map<String, String> hgetall(int database, String key) {
        return onheap.hgetall(database, key);
    }

    @Override
    public int hlen(int database, String key) {
        return onheap.hlen(database, key);
    }

    @Override
    public List<Object> hscan(int database, String key, long cursor, String pattern, int count) {
        return onheap.hscan(database, key, cursor, pattern, count);
    }

    // ==================== List（全 onheap）====================

    @Override
    public int lpush(int database, String key, String... values) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.lpush(database, key, values);
    }

    @Override
    public int rpush(int database, String key, String... values) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.rpush(database, key, values);
    }

    @Override
    public String lpop(int database, String key) {
        return onheap.lpop(database, key);
    }

    @Override
    public String rpop(int database, String key) {
        return onheap.rpop(database, key);
    }

    @Override
    public int lrem(int database, String key, int count, String value) {
        return onheap.lrem(database, key, count, value);
    }

    @Override
    public int llen(int database, String key) {
        return onheap.llen(database, key);
    }

    @Override
    public String lindex(int database, String key, int index) {
        return onheap.lindex(database, key, index);
    }

    @Override
    public void lset(int database, String key, int index, String value) {
        onheap.lset(database, key, index, value);
    }

    @Override
    public List<String> lrange(int database, String key, long start, long stop) {
        return onheap.lrange(database, key, start, stop);
    }

    @Override
    public void ltrim(int database, String key, long start, long stop) {
        onheap.ltrim(database, key, start, stop);
    }

    @Override
    public List<String> blpop(int database, String[] keys, long timeout) {
        return onheap.blpop(database, keys, timeout);
    }

    @Override
    public List<String> brpop(int database, String[] keys, long timeout) {
        return onheap.brpop(database, keys, timeout);
    }

    // ==================== Set（全 onheap）====================

    @Override
    public int sadd(int database, String key, String... members) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.sadd(database, key, members);
    }

    @Override
    public int srem(int database, String key, String... members) {
        return onheap.srem(database, key, members);
    }

    @Override
    public boolean sismember(int database, String key, String member) {
        return onheap.sismember(database, key, member);
    }

    @Override
    public Set<String> smembers(int database, String key) {
        return onheap.smembers(database, key);
    }

    @Override
    public int scard(int database, String key) {
        return onheap.scard(database, key);
    }

    @Override
    public Set<String> sinter(int database, String... keys) {
        return onheap.sinter(database, keys);
    }

    @Override
    public Set<String> sunion(int database, String... keys) {
        return onheap.sunion(database, keys);
    }

    @Override
    public Set<String> sdiff(int database, String... keys) {
        return onheap.sdiff(database, keys);
    }

    @Override
    public List<Object> sscan(int database, String key, long cursor, String pattern, int count) {
        return onheap.sscan(database, key, cursor, pattern, count);
    }

    // ==================== ZSet（全 onheap）====================

    @Override
    public int zadd(int database, String key, double score, String member) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.zadd(database, key, score, member);
    }

    @Override
    public int zrem(int database, String key, String... members) {
        return onheap.zrem(database, key, members);
    }

    @Override
    public Double zscore(int database, String key, String member) {
        return onheap.zscore(database, key, member);
    }

    @Override
    public List<String> zrange(int database, String key, long start, long stop) {
        return onheap.zrange(database, key, start, stop);
    }

    @Override
    public int zcard(int database, String key) {
        return onheap.zcard(database, key);
    }

    @Override
    public List<String> zrangeByScore(int database, String key, double min, double max, int offset, int count) {
        return onheap.zrangeByScore(database, key, min, max, offset, count);
    }

    @Override
    public List<Object> zscan(int database, String key, long cursor, String pattern, int count) {
        return onheap.zscan(database, key, cursor, pattern, count);
    }

    @Override
    public int zremrangeByScore(int database, String key, double min, double max) {
        return onheap.zremrangeByScore(database, key, min, max);
    }

    @Override
    public int zremrangeByRank(int database, String key, long start, long stop) {
        return onheap.zremrangeByRank(database, key, start, stop);
    }

    @Override
    public Long zrank(int database, String key, String member) {
        return onheap.zrank(database, key, member);
    }

    @Override
    public Long zrevrank(int database, String key, String member) {
        return onheap.zrevrank(database, key, member);
    }

    @Override
    public double zincrby(int database, String key, double increment, String member) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.zincrby(database, key, increment, member);
    }

    @Override
    public int zcount(int database, String key, double min, double max) {
        return onheap.zcount(database, key, min, max);
    }

    @Override
    public List<String> zpopmax(int database, String key, int count) {
        return onheap.zpopmax(database, key, count);
    }

    @Override
    public List<String> zpopmin(int database, String key, int count) {
        return onheap.zpopmin(database, key, count);
    }

    @Override
    public List<String> zrevrange(int database, String key, long start, long stop) {
        return onheap.zrevrange(database, key, start, stop);
    }

    @Override
    public Map<String, Double> zgetAllWithScores(int database, String key) {
        return onheap.zgetAllWithScores(database, key);
    }

    // ==================== Stream（全 onheap）====================

    @Override
    public StreamId xadd(int database, String key, StreamId id, Map<String, String> fields,
                         boolean nomkstream, Long maxLen, StreamId minId, Integer limit, boolean approximate) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.xadd(database, key, id, fields, nomkstream, maxLen, minId, limit, approximate);
    }

    @Override
    public long xlen(int database, String key) {
        return onheap.xlen(database, key);
    }

    @Override
    public List<StreamEntry> xrange(int database, String key, StreamId start, StreamId end,
                                    boolean exclusiveStart, boolean exclusiveEnd, int count, boolean reverse) {
        return onheap.xrange(database, key, start, end, exclusiveStart, exclusiveEnd, count, reverse);
    }

    @Override
    public long xdel(int database, String key, StreamId... ids) {
        return onheap.xdel(database, key, ids);
    }

    @Override
    public long xtrim(int database, String key, Long maxLen, StreamId minId, Integer limit, boolean approximate) {
        return onheap.xtrim(database, key, maxLen, minId, limit, approximate);
    }

    @Override
    public Stream getStream(int database, String key) {
        return onheap.getStream(database, key);
    }

    @Override
    public boolean xgroupCreate(int database, String key, String group, StreamId id, boolean mkstream) {
        clearOtherEngineBeforeNonString(database, key);
        return onheap.xgroupCreate(database, key, group, id, mkstream);
    }

    @Override
    public boolean xgroupDestroy(int database, String key, String group) {
        return onheap.xgroupDestroy(database, key, group);
    }

    @Override
    public long xgroupDelConsumer(int database, String key, String group, String consumer) {
        return onheap.xgroupDelConsumer(database, key, group, consumer);
    }

    @Override
    public boolean xgroupSetId(int database, String key, String group, StreamId id) {
        return onheap.xgroupSetId(database, key, group, id);
    }

    @Override
    public Map<String, List<StreamEntry>> xreadGroup(int database, String key, String group, String consumer,
                                                     StreamId id, int count, boolean noack) {
        return onheap.xreadGroup(database, key, group, consumer, id, count, noack);
    }

    @Override
    public long xack(int database, String key, String group, StreamId... ids) {
        return onheap.xack(database, key, group, ids);
    }

    @Override
    public Map<String, Object> xpendingSummary(int database, String key, String group) {
        return onheap.xpendingSummary(database, key, group);
    }

    @Override
    public List<Map<String, Object>> xpendingList(int database, String key, String group,
                                                  StreamId start, StreamId end, int count,
                                                  String consumer, long minIdleTime) {
        return onheap.xpendingList(database, key, group, start, end, count, consumer, minIdleTime);
    }

    @Override
    public List<StreamEntry> xclaim(int database, String key, String group, String consumer,
                                    long minIdleTime, StreamId[] ids, boolean justId, boolean force) {
        return onheap.xclaim(database, key, group, consumer, minIdleTime, ids, justId, force);
    }

    @Override
    public Map<String, Object> xautoclaim(int database, String key, String group, String consumer,
                                          long minIdleTime, StreamId start, int count) {
        return onheap.xautoclaim(database, key, group, consumer, minIdleTime, start, count);
    }

    @Override
    public Map<String, List<StreamEntry>> xread(int database, List<String> keys, List<StreamId> ids, int count, long block) {
        return onheap.xread(database, keys, ids, count, block);
    }

    @Override
    public StreamConsumerGroupManager getStreamConsumerGroupManager(int database, String key) {
        return onheap.getStreamConsumerGroupManager(database, key);
    }

    @Override
    public Map<String, Object> xinfoStream(int database, String key) {
        return onheap.xinfoStream(database, key);
    }

    @Override
    public List<Map<String, Object>> xinfoGroups(int database, String key) {
        return onheap.xinfoGroups(database, key);
    }

    @Override
    public List<Map<String, Object>> xinfoConsumers(int database, String key, String group) {
        return onheap.xinfoConsumers(database, key, group);
    }

    @Override
    public StreamId getGroupLastDeliveredId(int database, String key, String group) {
        return onheap.getGroupLastDeliveredId(database, key, group);
    }

    // ==================== 内存统计 / 碎片整理 ====================

    @Override
    public Long getMemoryUsage(int database, String key) {
        String off = offheap.get(database, key);
        if (off != null) {
            return (long) off.getBytes(StandardCharsets.UTF_8).length + 40L;
        }
        return onheap.getMemoryUsage(database, key);
    }

    @Override
    public long getUsedMemory() {
        return offheap.estimateUsedMemory() + onheap.getUsedMemory();
    }

    @Override
    public long getPeakUsedMemory() {
        // 堆外峰值后续细化；目前以 onheap 峰值为下界
        return onheap.getPeakUsedMemory() + offheap.estimateUsedMemory();
    }

    @Override
    public double getMemoryFragmentationRatio() {
        return onheap.getMemoryFragmentationRatio();
    }

    @Override
    public long defragment() {
        long ohFreed = offheap.defragment();
        return ohFreed + onheap.defragment();
    }

    @Override
    public MemoryStats getMemoryStats() {
        long used = getUsedMemory();
        long peak = onheap.getPeakUsedMemory() + offheap.estimateUsedMemory();
        return new MemoryStats(used, peak, onheap.getMaxMemory(),
                getMemoryFragmentationRatio(), (int) dbsize(0), 0);
    }

    // ==================== Slot（聚合 onheap + offheap）====================

    @Override
    public List<String> getKeysInSlot(int database, int slot, int count) {
        // 简化：slot 索引以 onheap 为准（结构化类型参与迁移）；offheap 的 string
        // key 由 getKeySlot 计算，scan 时再补充。本期保留 onheap 行为。
        return onheap.getKeysInSlot(database, slot, count);
    }

    @Override
    public int countKeysInSlot(int database, int slot) {
        return onheap.countKeysInSlot(database, slot);
    }

    @Override
    public int getKeySlot(String key) {
        return onheap.getKeySlot(key);
    }

    // ==================== 生命周期 ====================

    public void close() {
        expireExecutor.shutdownNow();
        offheap.close();
        onheap.close();
    }
}
