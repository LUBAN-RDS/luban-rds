package com.janeluo.luban.rds.core.store;

import com.janeluo.luban.rds.common.constant.RdsDataTypeConstant;
import com.janeluo.luban.rds.common.util.RdsUtil;
import com.janeluo.luban.rds.common.util.SlotUtils;
import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.core.stream.Consumer;
import com.janeluo.luban.rds.core.stream.ConsumerGroup;
import com.janeluo.luban.rds.core.stream.PendingMessage;
import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamConsumerGroupManager;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DefaultMemoryStore implements MemoryStore {
    private static final Logger logger = LoggerFactory.getLogger(DefaultMemoryStore.class);
    
    // 内存淘汰策略常量
    public static final String POLICY_NOEVICTION = "noeviction";
    public static final String POLICY_ALLKEYS_LRU = "allkeys-lru";
    public static final String POLICY_VOLATILE_LRU = "volatile-lru";
    public static final String POLICY_ALLKEYS_RANDOM = "allkeys-random";
    public static final String POLICY_VOLATILE_RANDOM = "volatile-random";
    public static final String POLICY_VOLATILE_TTL = "volatile-ttl";
    
    // 估算每个键值对的基础内存开销（字节）
    private static final long BASE_ENTRY_OVERHEAD = 128;
    
    // Java object overhead constants (approximate values for 64-bit JVM with compressed oops)
    private static final int OBJECT_HEADER_SIZE = 12; // Object header
    private static final int REFERENCE_SIZE = 4; // Reference (compressed oop)
    private static final int ARRAY_HEADER_SIZE = 16; // Array header
    private static final int STRING_OVERHEAD = 24; // String object overhead (header + hash + coder + value ref)
    private static final int HASHMAP_ENTRY_OVERHEAD = 32; // HashMap Node overhead
    private static final int HASHMAP_OVERHEAD = 48; // HashMap object overhead
    private static final int ARRAYLIST_OVERHEAD = 24; // ArrayList object overhead
    private static final int HASHSET_OVERHEAD = 48; // HashSet object overhead
    private static final int CONCURRENTHASHMAP_OVERHEAD = 64; // ConcurrentHashMap overhead
    
    /**
     * Optimized StoreValue with reduced memory footprint.
     * Uses primitive types and byte index instead of String for type.
     */
    private static class StoreValue {
        private final Object value;
        private final byte typeIndex; // 0=string, 1=hash, 2=list, 3=set, 4=zset
        private long expireTime; // 0 means no expiration, otherwise absolute timestamp in milliseconds
        private long lastAccessTime; // Last access time for LRU
        private int estimatedSize; // Estimated memory size (int is sufficient, single key unlikely > 2GB)
        
        // Type index constants
        private static final byte TYPE_STRING = 0;
        private static final byte TYPE_HASH = 1;
        private static final byte TYPE_LIST = 2;
        private static final byte TYPE_SET = 3;
        private static final byte TYPE_ZSET = 4;
        private static final byte TYPE_STREAM = 5;
        
        // Special value for no expiration
        private static final long NO_EXPIRE = 0L;
        
        public StoreValue(Object value, String type) {
            this.value = value;
            this.typeIndex = typeToIndex(type);
            this.expireTime = NO_EXPIRE;
            this.lastAccessTime = System.currentTimeMillis();
            this.estimatedSize = (int) estimateSize(value);
        }
        
        /**
         * Creates a StoreValue with expiration time.
         *
         * @param value the value to store
         * @param type the type string
         * @param expireTime absolute expiration timestamp in milliseconds
         */
        public StoreValue(Object value, String type, Long expireTime) {
            this.value = value;
            this.typeIndex = typeToIndex(type);
            this.expireTime = expireTime != null ? expireTime : NO_EXPIRE;
            this.lastAccessTime = System.currentTimeMillis();
            this.estimatedSize = (int) estimateSize(value);
        }
        
        /**
         * Creates a StoreValue with byte type index and expiration time.
         *
         * @param value the value to store
         * @param typeIndex the type index
         * @param expireTime absolute expiration timestamp in milliseconds
         */
        public StoreValue(Object value, byte typeIndex, long expireTime) {
            this.value = value;
            this.typeIndex = typeIndex;
            this.expireTime = expireTime;
            this.lastAccessTime = System.currentTimeMillis();
            this.estimatedSize = (int) estimateSize(value);
        }
        
        private static byte typeToIndex(String type) {
            if (type == null) {
                return TYPE_STRING;
            }
            switch (type) {
                case RdsDataTypeConstant.STRING:
                    return TYPE_STRING;
                case RdsDataTypeConstant.HASH:
                    return TYPE_HASH;
                case RdsDataTypeConstant.LIST:
                    return TYPE_LIST;
                case RdsDataTypeConstant.SET:
                    return TYPE_SET;
                case RdsDataTypeConstant.ZSET:
                    return TYPE_ZSET;
                case RdsDataTypeConstant.STREAM:
                    return TYPE_STREAM;
                default:
                    return TYPE_STRING;
            }
        }
        
        private static String indexToType(byte index) {
            switch (index) {
                case TYPE_STRING:
                    return RdsDataTypeConstant.STRING;
                case TYPE_HASH:
                    return RdsDataTypeConstant.HASH;
                case TYPE_LIST:
                    return RdsDataTypeConstant.LIST;
                case TYPE_SET:
                    return RdsDataTypeConstant.SET;
                case TYPE_ZSET:
                    return RdsDataTypeConstant.ZSET;
                case TYPE_STREAM:
                    return RdsDataTypeConstant.STREAM;
                default:
                    return RdsDataTypeConstant.STRING;
            }
        }
        
        public String getType() {
            return indexToType(typeIndex);
        }
        
        public void updateEstimatedSize(long delta) {
            this.estimatedSize = (int) Math.max(0, this.estimatedSize + delta);
        }
        
        public boolean isExpired() {
            return expireTime != NO_EXPIRE && System.currentTimeMillis() >= expireTime;
        }
        
        public void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public long getLastAccessTime() {
            return lastAccessTime;
        }
        
        public boolean hasExpireTime() {
            return expireTime != NO_EXPIRE;
        }
        
        public Long getExpireTime() {
            return expireTime == NO_EXPIRE ? null : expireTime;
        }
        
        public long getEstimatedSize() {
            return estimatedSize;
        }
        
        /**
         * Estimates the memory size of a value with Java object overhead.
         * This provides a more accurate estimation of actual memory usage.
         *
         * @param value the value to estimate
         * @return estimated memory size in bytes
         */
        private static long estimateSize(Object value) {
            if (value == null) {
                return BASE_ENTRY_OVERHEAD;
            }
            
            long size = BASE_ENTRY_OVERHEAD;
            
            if (value instanceof String) {
                String str = (String) value;
                // String object: header (12) + hash (4) + coder (1) + value reference (4) + padding
                // char array: header (16) + length * 2
                size += STRING_OVERHEAD + (long) str.length() * 2L;
            } else if (value instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) value;
                // Map overhead + entries
                size += HASHMAP_OVERHEAD + (long) map.size() * HASHMAP_ENTRY_OVERHEAD;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    size += estimateEntrySize(entry.getKey(), entry.getValue());
                }
            } else if (value instanceof List) {
                List<?> list = (List<?>) value;
                // ArrayList overhead + element references
                size += ARRAYLIST_OVERHEAD + (long) list.size() * REFERENCE_SIZE;
                for (Object item : list) {
                    if (item instanceof String) {
                        size += STRING_OVERHEAD + ((String) item).length() * 2L;
                    }
                }
            } else if (value instanceof java.util.Set) {
                java.util.Set<?> set = (java.util.Set<?>) value;
                // HashSet overhead + entries
                size += HASHSET_OVERHEAD + (long) set.size() * HASHMAP_ENTRY_OVERHEAD;
                for (Object item : set) {
                    if (item instanceof String) {
                        size += STRING_OVERHEAD + ((String) item).length() * 2L;
                    }
                }
            } else if (value instanceof ZSetStore) {
                ZSetStore zset = (ZSetStore) value;
                // ZSetStore has two maps: memberScores and scoreMembers
                // memberScores: ConcurrentHashMap<String, Double>
                // scoreMembers: ConcurrentSkipListMap<Double, ConcurrentSkipListSet<String>>
                size += CONCURRENTHASHMAP_OVERHEAD + (long) zset.size() * 64L;
                // ScoreMembers overhead (ConcurrentSkipListMap + nested ConcurrentSkipListSets)
                // 跳表节点比 CHM 桶节点重，单成员估算 72L
                size += 96 + (long) zset.size() * 72L;
                for (String member : zset.memberScores.keySet()) {
                    size += STRING_OVERHEAD + member.length() * 2L;
                }
            } else if (value instanceof Stream) {
                Stream stream = (Stream) value;
                size += stream.estimateMemorySize();
            } else {
                // Other types, estimate a fixed value
                size += 64;
            }
            
            return size;
        }
        
        /**
         * Estimates the memory size of a map entry.
         *
         * @param key the entry key
         * @param value the entry value
         * @return estimated memory size in bytes
         */
        private static long estimateEntrySize(Object key, Object value) {
            long size = HASHMAP_ENTRY_OVERHEAD;
            if (key instanceof String) {
                size += STRING_OVERHEAD + ((String) key).length() * 2L;
            }
            if (value instanceof String) {
                size += STRING_OVERHEAD + ((String) value).length() * 2L;
            }
            return size;
        }
    }
    
    // 每个数据库的存储结构
    private static class DatabaseStore {
        final Cache<String, StoreValue> storage;
        final ConcurrentHashMap<String, Boolean> keySet; // 用于跟踪所有键，支持SCAN命令
        final ConcurrentHashMap<String, AtomicLong> keyVersions; // 键版本，用于WATCH
        final ConcurrentHashMap<Integer, Set<String>> slotToKeys; // 槽位到键的映射索引
        
        public DatabaseStore() {
            this.keySet = new ConcurrentHashMap<>(64); // 初始容量
            this.keyVersions = new ConcurrentHashMap<>(64);
            this.slotToKeys = new ConcurrentHashMap<>();
            this.storage = Caffeine.newBuilder()
                    .initialCapacity(256) // 设置初始容量，减少扩容
                    .removalListener(new RemovalListener<String, StoreValue>() {
                        @Override
                        public void onRemoval(String key, StoreValue value, RemovalCause cause) {
                            // REPLACED 表示 entry 被新值覆盖（如 pexpire/lrem 的 storage.put），
                            // key 仍在 cache 中，不应从 keySet/slotToKeys 移除。
                            // 仅在 key 真正离开 cache 时（显式删除、GC 回收、过期、容量淘汰）
                            // 清理辅助索引，避免 scan/dbsize/RDB 持久化扫不到仍存在的 key。
                            if (cause == RemovalCause.REPLACED) {
                                return;
                            }
                            // 当键被移除时，从keySet和slotToKeys中也移除
                            keySet.remove(key);
                            removeFromSlotIndex(key);
                        }
                    })
                    .build();
        }
        
        /**
         * 从槽位索引中移除键
         */
        private void removeFromSlotIndex(String key) {
            int slot = SlotUtils.getSlot(key);
            Set<String> keysInSlot = slotToKeys.get(slot);
            if (keysInSlot != null) {
                keysInSlot.remove(key);
                // 如果槽位为空，移除整个槽位条目以节省内存
                if (keysInSlot.isEmpty()) {
                    slotToKeys.remove(slot, keysInSlot);
                }
            }
        }
        
        /**
         * 添加键到槽位索引
         */
        private void addToSlotIndex(String key) {
            int slot = SlotUtils.getSlot(key);
            slotToKeys.computeIfAbsent(slot, k -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }
    
    // 数据库存储管理
    private final ConcurrentHashMap<Integer, DatabaseStore> databaseStores = new ConcurrentHashMap<>();
    
    // 数据库数量限制
    private int maxDatabases = 16;
    
    // 分段锁配置，用于替代String.intern()避免内存泄漏
    private static final int LOCK_STRIPE_COUNT = 1024;
    private static final int LOCK_STRIPE_MASK = LOCK_STRIPE_COUNT - 1;
    private final Object[] lockStripes = new Object[LOCK_STRIPE_COUNT];
    
    // 过期键主动清理机制
    private final ScheduledExecutorService expirationExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "KeyExpiration-Worker");
        t.setDaemon(true);
        return t;
    });
    private static final int EXPIRE_CYCLE_KEYS = 100;
    
    // 最大内存限制（字节），0表示不限制
    private long maxMemory = 0;
    
    // 内存淘汰策略
    private String maxMemoryPolicy = POLICY_NOEVICTION;
    
    public String getMaxMemoryPolicy() {
        return maxMemoryPolicy;
    }
    
    public void setMaxMemoryPolicy(String policy) {
        if (policy == null) return;
        switch (policy) {
            case POLICY_NOEVICTION:
            case POLICY_ALLKEYS_LRU:
            case POLICY_VOLATILE_LRU:
            case POLICY_ALLKEYS_RANDOM:
            case POLICY_VOLATILE_RANDOM:
            case POLICY_VOLATILE_TTL:
                this.maxMemoryPolicy = policy;
                break;
            default:
                break;
        }
    }
    
    // 当前使用的内存（估算值）
    private final AtomicLong usedMemory = new AtomicLong(0);
    
    // 历史峰值内存使用量
    private final AtomicLong peakUsedMemory = new AtomicLong(0);
    
    // 随机数生成器，用于随机淘汰策略
    private final Random random = new Random();
    
    // LRU 采样数量（参考 Redis 默认值为 5）
    private int lruSampleSize = 5;
    
    public int getLruSampleSize() {
        return lruSampleSize;
    }
    
    public void setLruSampleSize(int size) {
        if (size > 0) {
            this.lruSampleSize = size;
        }
    }
    
    // 软阈值（百分比，0-100）
    private int softLimitPercent = 90;
    
    public int getSoftLimitPercent() {
        return softLimitPercent;
    }
    
    public void setSoftLimitPercent(int percent) {
        if (percent < 0) percent = 0;
        if (percent > 100) percent = 100;
        this.softLimitPercent = percent;
    }
    
    public boolean isSoftLimitExceeded() {
        if (maxMemory <= 0) return false;
        long threshold = (maxMemory * softLimitPercent) / 100;
        return usedMemory.get() >= threshold;
    }
    
    // LRU 候选池大小（参考 Redis eviction pool 大小为 16）
    private static final int LRU_POOL_SIZE = 16;
    
    // LRU 候选池，存储待淘汰的键信息
    private final java.util.concurrent.ConcurrentSkipListSet<LruPoolEntry> lruPool = 
            new java.util.concurrent.ConcurrentSkipListSet<>();
    
    /**
     * LRU 候选池条目
     * 参考 Redis 的 evictionPoolEntry 结构
     */
    private static class LruPoolEntry implements Comparable<LruPoolEntry> {
        final int database;
        final String key;
        final long idleTime; // 空闲时间（越大表示越久未访问）
        
        LruPoolEntry(int database, String key, long idleTime) {
            this.database = database;
            this.key = key;
            this.idleTime = idleTime;
        }
        
        @Override
        public int compareTo(LruPoolEntry other) {
            // 按空闲时间升序排列，空闲时间最长的在最后
            int cmp = Long.compare(this.idleTime, other.idleTime);
            if (cmp != 0) return cmp;
            // 如果空闲时间相同，按 key 排序以保证唯一性
            cmp = Integer.compare(this.database, other.database);
            if (cmp != 0) return cmp;
            return this.key.compareTo(other.key);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof LruPoolEntry)) return false;
            LruPoolEntry other = (LruPoolEntry) obj;
            return this.database == other.database && this.key.equals(other.key);
        }
        
        @Override
        public int hashCode() {
            return 31 * database + key.hashCode();
        }
    }
    
    public DefaultMemoryStore() {
        initLockStripes();
        getOrCreateDatabaseStore(0);
        startExpirationTask();
    }
    
    /**
     * 使用配置创建内存存储
     * 
     * @param databases 数据库数量
     * @param maxMemory 最大内存限制（字节）
     * @param maxMemoryPolicy 内存淘汰策略
     */
    public DefaultMemoryStore(int databases, long maxMemory, String maxMemoryPolicy) {
        this.maxDatabases = databases;
        this.maxMemory = maxMemory;
        this.maxMemoryPolicy = maxMemoryPolicy != null ? maxMemoryPolicy : POLICY_NOEVICTION;
        initLockStripes();
        getOrCreateDatabaseStore(0);
        startExpirationTask();
        
        logger.info("内存存储初始化: databases={}, maxMemory={}bytes, policy={}", 
                databases, maxMemory, this.maxMemoryPolicy);
    }
    
    /**
     * 获取数据库数量限制
     */
    public int getMaxDatabases() {
        return maxDatabases;
    }
    
    /**
     * 检查数据库索引是否有效
     */
    public boolean isValidDatabase(int database) {
        return database >= 0 && database < maxDatabases;
    }
    
    @Override
    public long getUsedMemory() {
        return usedMemory.get();
    }
    
    @Override
    public long getPeakUsedMemory() {
        return peakUsedMemory.get();
    }
    
    private void updateMemory(long delta) {
        long current = usedMemory.addAndGet(delta);
        if (delta > 0) {
            long peak = peakUsedMemory.get();
            while (current > peak) {
                if (peakUsedMemory.compareAndSet(peak, current)) {
                    break;
                }
                peak = peakUsedMemory.get();
            }
        }
    }

    /**
     * 获取最大内存限制
     */
    public long getMaxMemory() {
        return maxMemory;
    }
    
    public void setMaxMemory(long maxMemory) {
        this.maxMemory = Math.max(0, maxMemory);
    }
    
    /**
     * 检查是否超过内存限制
     */
    private boolean isMemoryExceeded() {
        return maxMemory > 0 && usedMemory.get() >= maxMemory;
    }
    
    /**
     * 尝试释放内存以满足新数据写入
     * 
     * @param requiredSize 需要的内存大小
     * @return 是否成功释放足够内存
     */
    private boolean tryEvictMemory(long requiredSize) {
        if (maxMemory <= 0) {
            return true; // 没有内存限制
        }
        
        // 如果当前内存加上需要的大小不超过限制，直接返回
        if (usedMemory.get() + requiredSize <= maxMemory) {
            return true;
        }
        
        // 根据淘汰策略进行内存回收
        switch (maxMemoryPolicy) {
            case POLICY_NOEVICTION:
                // 不淘汰，返回失败
                return false;
                
            case POLICY_ALLKEYS_LRU:
                return evictByLru(false, requiredSize);
                
            case POLICY_VOLATILE_LRU:
                return evictByLru(true, requiredSize);
                
            case POLICY_ALLKEYS_RANDOM:
                return evictByRandom(false, requiredSize);
                
            case POLICY_VOLATILE_RANDOM:
                return evictByRandom(true, requiredSize);
                
            case POLICY_VOLATILE_TTL:
                return evictByTtl(requiredSize);
                
            default:
                return false;
        }
    }
    
    /**
     * Preallocates memory for batch operations.
     * Call this before executing large batch operations to reduce frequent resizing.
     *
     * @param estimatedKeyCount estimated number of keys to be inserted
     * @param estimatedValueSize estimated average value size per key
     */
    public void preallocateMemory(int estimatedKeyCount, long estimatedValueSize) {
        long totalEstimated = estimatedKeyCount * (BASE_ENTRY_OVERHEAD + estimatedValueSize);
        
        // Check if eviction is needed
        if (maxMemory > 0 && usedMemory.get() + totalEstimated > maxMemory * 0.9) {
            // Trigger memory eviction
            tryEvictMemory(totalEstimated);
        }
        
        // Pre-expand ConcurrentHashMaps for each database
        // Note: ConcurrentHashMap doesn't support direct pre-expansion, but we can 
        // ensure the database stores are initialized
        for (int i = 0; i < Math.min(maxDatabases, estimatedKeyCount / 100 + 1); i++) {
            getOrCreateDatabaseStore(i);
        }
    }
    
    /**
     * LRU淘汰策略（参考 Redis 近似 LRU 算法）
     * <p>
     * Redis 的 LRU 实现并非精确的 LRU，而是采用采样近似算法：
     * 1. 随机采样 N 个键（默认 5 个，可通过 maxmemory-samples 配置）
     * 2. 从采样的键中淘汰空闲时间最长的
     * 3. 使用 eviction pool 缓存候选键，提高淘汰效率
     * 
     * @param volatileOnly 是否只淘汰设置了过期时间的键
     * @param requiredSize 需要释放的内存大小
     */
    private boolean evictByLru(boolean volatileOnly, long requiredSize) {
        while (usedMemory.get() + requiredSize > maxMemory) {
            // 填充 LRU 候选池
            fillLruPool(volatileOnly);
            
            // 从候选池中选择空闲时间最长的键淘汰
            LruPoolEntry bestEntry = null;
            
            // 获取空闲时间最长的条目（池中最后一个）
            while (!lruPool.isEmpty()) {
                bestEntry = lruPool.pollLast();
                if (bestEntry != null) {
                    // 验证键是否仍然存在
                    DatabaseStore store = databaseStores.get(bestEntry.database);
                    if (store != null) {
                        StoreValue value = store.storage.getIfPresent(bestEntry.key);
                        if (value != null) {
                            // 再次检查 volatile 条件
                            if (!volatileOnly || value.hasExpireTime()) {
                                break;
                            }
                        }
                    }
                    bestEntry = null;
                }
            }
            
            if (bestEntry == null) {
                // 候选池为空，尝试直接采样淘汰
                bestEntry = sampleBestKeyToEvict(volatileOnly);
            }
            
            if (bestEntry == null) {
                logger.debug("LRU淘汰失败: 无可淘汰键, 已用内存={}bytes, 最大内存={}bytes", 
                    usedMemory.get(), maxMemory);
                return false;
            }
            
            // 淘汰该键
            del(bestEntry.database, bestEntry.key);
            logger.debug("LRU淘汰键: db={}, key={}, idleTime={}ms", 
                    bestEntry.database, bestEntry.key, bestEntry.idleTime);
        }
        
        return true;
    }
    
    /**
     * 填充 LRU 候选池（参考 Redis evictionPoolPopulate）
     * 
     * 随机采样键，将空闲时间较长的键加入候选池
     */
    private void fillLruPool(boolean volatileOnly) {
        long currentTime = System.currentTimeMillis();
        
        List<Object[]> sampledKeys = new ArrayList<>();
        
        for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
            DatabaseStore store = dbEntry.getValue();
            int dbKey = dbEntry.getKey();
            
            int sampled = 0;
            int targetSample = Math.max(1, lruSampleSize / databaseStores.size());
            
            for (String key : store.keySet.keySet()) {
                if (sampled >= targetSample) break;
                
                DatabaseStore currentStore = databaseStores.get(dbKey);
                if (currentStore == null) continue;
                
                StoreValue value = currentStore.storage.getIfPresent(key);
                if (value == null) continue;
                
                if (volatileOnly && !value.hasExpireTime()) {
                    continue;
                }
                
                long idleTime = currentTime - value.getLastAccessTime();
                sampledKeys.add(new Object[]{dbKey, key, idleTime});
                sampled++;
            }
        }
        
        for (Object[] entry : sampledKeys) {
            int database = (Integer) entry[0];
            String key = (String) entry[1];
            long idleTime = (Long) entry[2];
            
            LruPoolEntry poolEntry = new LruPoolEntry(database, key, idleTime);
            
            if (lruPool.size() < LRU_POOL_SIZE) {
                lruPool.add(poolEntry);
            } else {
                LruPoolEntry smallest = lruPool.first();
                if (idleTime > smallest.idleTime) {
                    lruPool.pollFirst();
                    lruPool.add(poolEntry);
                }
            }
        }
    }
    
    /**
     * 直接采样选择最佳淘汰键（当候选池为空时使用）
     */
    private LruPoolEntry sampleBestKeyToEvict(boolean volatileOnly) {
        long currentTime = System.currentTimeMillis();
        LruPoolEntry best = null;
        
        // 收集所有数据库的键
        List<Object[]> allKeys = new ArrayList<>();
        for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
            DatabaseStore store = dbEntry.getValue();
            for (String key : store.keySet.keySet()) {
                allKeys.add(new Object[]{dbEntry.getKey(), key});
            }
        }
        
        if (allKeys.isEmpty()) {
            return null;
        }
        
        // 采样并找出最佳淘汰键
        int sampleCount = Math.min(lruSampleSize * 2, allKeys.size());
        for (int i = 0; i < sampleCount; i++) {
            int idx = random.nextInt(allKeys.size());
            Object[] entry = allKeys.get(idx);
            int database = (Integer) entry[0];
            String key = (String) entry[1];
            
            DatabaseStore store = databaseStores.get(database);
            if (store == null) continue;
            
            StoreValue value = store.storage.getIfPresent(key);
            if (value == null) continue;
            
            if (volatileOnly && !value.hasExpireTime()) {
                continue;
            }
            
            long idleTime = currentTime - value.getLastAccessTime();
            
            if (best == null || idleTime > best.idleTime) {
                best = new LruPoolEntry(database, key, idleTime);
            }
        }
        
        return best;
    }
    
    /**
     * 随机淘汰策略
     * 
     * @param volatileOnly 是否只淘汰设置了过期时间的键
     * @param requiredSize 需要释放的内存大小
     */
    private boolean evictByRandom(boolean volatileOnly, long requiredSize) {
        while (usedMemory.get() + requiredSize > maxMemory) {
            // 收集所有可淘汰的键
            List<int[]> candidates = new ArrayList<>(); // [database, keyIndex]
            
            for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
                DatabaseStore store = dbEntry.getValue();
                int keyIndex = 0;
                for (String key : store.keySet.keySet()) {
                    StoreValue value = store.storage.getIfPresent(key);
                    if (value == null) continue;
                    
                    if (volatileOnly && !value.hasExpireTime()) {
                        continue;
                    }
                    
                    candidates.add(new int[]{dbEntry.getKey(), keyIndex});
                    keyIndex++;
                }
            }
            
            if (candidates.isEmpty()) {
                logger.debug("随机淘汰失败: 无可淘汰键, 已用内存={}bytes, 最大内存={}bytes", 
                    usedMemory.get(), maxMemory);
                return false;
            }
            
            // 随机选择一个键淘汰
            int[] selected = candidates.get(random.nextInt(candidates.size()));
            int dbIndex = selected[0];
            DatabaseStore store = databaseStores.get(dbIndex);
            
            // 获取对应的键
            int targetIndex = selected[1];
            int currentIndex = 0;
            for (String key : store.keySet.keySet()) {
                if (currentIndex == targetIndex) {
                    del(dbIndex, key);
                    logger.debug("随机淘汰键: db={}, key={}", dbIndex, key);
                    break;
                }
                currentIndex++;
            }
        }
        
        return true;
    }
    
    /**
     * TTL淘汰策略：淘汰即将过期的键
     * 
     * @param requiredSize 需要释放的内存大小
     */
    private boolean evictByTtl(long requiredSize) {
        while (usedMemory.get() + requiredSize > maxMemory) {
            String keyToEvict = null;
            int dbToEvict = -1;
            long earliestExpire = Long.MAX_VALUE;
            
            // 遍历所有数据库找到最快过期的键
            for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
                DatabaseStore store = dbEntry.getValue();
                for (String key : store.keySet.keySet()) {
                    StoreValue value = store.storage.getIfPresent(key);
                    if (value == null || !value.hasExpireTime()) continue;
                    
                    Long expireTime = value.getExpireTime();
                    if (expireTime != null && expireTime < earliestExpire) {
                        earliestExpire = expireTime;
                        keyToEvict = key;
                        dbToEvict = dbEntry.getKey();
                    }
                }
            }
            
            if (keyToEvict == null) {
                logger.debug("TTL淘汰失败: 无设置过期时间的键, 已用内存={}bytes, 最大内存={}bytes", 
                    usedMemory.get(), maxMemory);
                return false;
            }
            
            // 淘汰该键
            del(dbToEvict, keyToEvict);
            logger.debug("TTL淘汰键: db={}, key={}", dbToEvict, keyToEvict);
        }
        
        return true;
    }
    
    // 获取或创建数据库存储
    private DatabaseStore getOrCreateDatabaseStore(int database) {
        return databaseStores.computeIfAbsent(database, k -> new DatabaseStore());
    }
    
    @Override
    public Object get(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        if (storeValue.isExpired()) {
            synchronized (getLockForKey(database, key)) {
                storeValue = store.storage.getIfPresent(key);
                if (storeValue != null && storeValue.isExpired()) {
                    long freedMemory = storeValue.getEstimatedSize();
                    store.storage.invalidate(key);
                    store.keySet.remove(key);
                    updateMemory(-freedMemory);
                }
            }
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        storeValue.updateAccessTime();
        RuntimeConfig.incKeyspaceHits();
        
        return storeValue.value;
    }
    
    @Override
    public void set(int database, String key, Object value) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        String type = getType(value);
        StoreValue newValue = new StoreValue(value, type);
        long requiredSize = newValue.getEstimatedSize();
        
        // 检查是否已存在该键，如果存在则先减去旧值的内存
        StoreValue oldValue = store.storage.getIfPresent(key);
        if (oldValue != null) {
            updateMemory(-oldValue.getEstimatedSize());
        }
        
        // 检查内存限制，尝试淘汰
        if (!tryEvictMemory(requiredSize)) {
            // 内存不足且无法淘汰，恢复旧值的内存统计
            if (oldValue != null) {
                updateMemory(oldValue.getEstimatedSize());
            }
            throw new RuntimeException("OOM command not allowed when used memory > 'maxmemory'");
        }
        
        // 写入新值
        store.storage.put(key, newValue);
        store.keySet.put(key, Boolean.TRUE);
        store.addToSlotIndex(key); // 更新槽位索引
        updateMemory(requiredSize);
        bumpKeyVersion(database, key);
    }
    
    @Override
    public void setWithExpire(int database, String key, Object value, long expireSeconds) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        String type = getType(value);
        long expireTime = System.currentTimeMillis() + expireSeconds * 1000L;
        StoreValue newValue = new StoreValue(value, type, expireTime);
        long requiredSize = newValue.getEstimatedSize();
        
        // 检查是否已存在该键，如果存在则先减去旧值的内存
        StoreValue oldValue = store.storage.getIfPresent(key);
        if (oldValue != null) {
            updateMemory(-oldValue.getEstimatedSize());
        }
        
        // 检查内存限制，尝试淘汰
        if (!tryEvictMemory(requiredSize)) {
            // 内存不足且无法淘汰，恢复旧值的内存统计
            if (oldValue != null) {
                updateMemory(oldValue.getEstimatedSize());
            }
            throw new RuntimeException("OOM command not allowed when used memory > 'maxmemory'");
        }
        
        // 写入新值
        store.storage.put(key, newValue);
        store.keySet.put(key, Boolean.TRUE);
        store.addToSlotIndex(key); // 更新槽位索引
        updateMemory(requiredSize);
        bumpKeyVersion(database, key);
    }
    
    @Override
    public void setWithExpireMs(int database, String key, Object value, long expireMilliseconds) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        String type = getType(value);
        long expireTime = System.currentTimeMillis() + expireMilliseconds;
        StoreValue newValue = new StoreValue(value, type, expireTime);
        long requiredSize = newValue.getEstimatedSize();
        
        // 检查是否已存在该键，如果存在则先减去旧值的内存
        StoreValue oldValue = store.storage.getIfPresent(key);
        if (oldValue != null) {
            updateMemory(-oldValue.getEstimatedSize());
        }
        
        // 检查内存限制，尝试淘汰
        if (!tryEvictMemory(requiredSize)) {
            // 内存不足且无法淘汰，恢复旧值的内存统计
            if (oldValue != null) {
                updateMemory(oldValue.getEstimatedSize());
            }
            throw new RuntimeException("OOM command not allowed when used memory > 'maxmemory'");
        }
        
        // 写入新值
        store.storage.put(key, newValue);
        store.keySet.put(key, Boolean.TRUE);
        store.addToSlotIndex(key); // 更新槽位索引
        updateMemory(requiredSize);
        bumpKeyVersion(database, key);
    }

    @Override
    public void mset(int database, String... keysAndValues) {
        if (keysAndValues == null || keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("wrong number of arguments for MSET");
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        
        synchronized (store) {
            long totalRequiredSize = 0;
            java.util.Map<String, StoreValue> newValues = new java.util.LinkedHashMap<>();
            java.util.Map<String, StoreValue> oldValues = new java.util.LinkedHashMap<>();
            
            for (int i = 0; i < keysAndValues.length; i += 2) {
                String key = keysAndValues[i];
                String value = keysAndValues[i + 1];
                String type = getType(value);
                StoreValue newValue = new StoreValue(value, type);
                newValues.put(key, newValue);
                totalRequiredSize += newValue.getEstimatedSize();
                
                StoreValue oldValue = store.storage.getIfPresent(key);
                if (oldValue != null) {
                    oldValues.put(key, oldValue);
                    totalRequiredSize -= oldValue.getEstimatedSize();
                }
            }
            
            if (!tryEvictMemory(totalRequiredSize)) {
                throw new RuntimeException("OOM command not allowed when used memory > 'maxmemory'");
            }
            
            for (java.util.Map.Entry<String, StoreValue> entry : newValues.entrySet()) {
                String key = entry.getKey();
                StoreValue newValue = entry.getValue();
                store.storage.put(key, newValue);
                store.keySet.put(key, Boolean.TRUE);
                store.addToSlotIndex(key); // 更新槽位索引
                updateMemory(newValue.getEstimatedSize());
                bumpKeyVersion(database, key);
            }
        }
    }

    @Override
    public java.util.List<Object> mget(int database, String... keys) {
        java.util.List<Object> result = new java.util.ArrayList<>(keys.length);
        for (String key : keys) {
            result.add(get(database, key));
        }
        return result;
    }

    @Override
    public long incrby(int database, String key, long increment) {
        DatabaseStore store = getOrCreateDatabaseStore(database);

        // 使用同步块确保原子性
        synchronized (getLockForKey(database, key)) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            long currentValue = 0;

            if (storeValue != null && !storeValue.isExpired()) {
                Object val = storeValue.value;
                if (val instanceof String) {
                    try {
                        currentValue = Long.parseLong((String) val);
                    } catch (NumberFormatException e) {
                        throw new RuntimeException("ERR value is not an integer or out of range");
                    }
                } else {
                    throw new RuntimeException("ERR value is not an integer or out of range");
                }
            }

            long newValue = currentValue + increment;
            String newValueStr = String.valueOf(newValue);

            // 保留原有的过期时间
            Long expireTime = null;
            if (storeValue != null) {
                expireTime = storeValue.getExpireTime();
            }

            if (expireTime != null && expireTime > 0) {
                long ttl = expireTime - System.currentTimeMillis();
                if (ttl > 0) {
                    setWithExpireMs(database, key, newValueStr, ttl);
                } else {
                    // 已过期，设置为无过期时间
                    set(database, key, newValueStr);
                }
            } else {
                set(database, key, newValueStr);
            }

            return newValue;
        }
    }

    /**
     * 初始化分段锁数组
     */
    private void initLockStripes() {
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            lockStripes[i] = new Object();
        }
    }
    
    /**
     * 启动过期键主动清理任务
     */
    private void startExpirationTask() {
        expirationExecutor.scheduleAtFixedRate(() -> {
            try {
                expireCycle();
            } catch (Exception e) {
                logger.error("Error in expiration cycle", e);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    /**
     * 过期键清理循环（参考 Redis 的主动过期策略）
     * 每次最多清理 EXPIRE_CYCLE_KEYS 个过期键
     */
    private void expireCycle() {
        int expired = 0;
        
        for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
            DatabaseStore store = dbEntry.getValue();
            
            for (String key : store.keySet.keySet()) {
                if (expired >= EXPIRE_CYCLE_KEYS) {
                    return;
                }
                
                StoreValue value = store.storage.getIfPresent(key);
                if (value != null && value.isExpired()) {
                    del(dbEntry.getKey(), key);
                    expired++;
                }
            }
        }
    }
    
    /**
     * 关闭内存存储，释放资源
     */
    public void close() {
        expirationExecutor.shutdown();
        try {
            if (!expirationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                expirationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            expirationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * 获取指定键的锁对象，用于同步
     * 使用分段锁替代String.intern()避免内存泄漏
     */
    private Object getLockForKey(int database, String key) {
        int hash = (database + ":" + key).hashCode();
        int stripe = Math.abs(hash & LOCK_STRIPE_MASK);
        return lockStripes[stripe];
    }

    @Override
    public boolean del(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue != null) {
            // 更新内存统计
            updateMemory(-storeValue.getEstimatedSize());
            store.storage.invalidate(key);
            store.keySet.remove(key);
            bumpKeyVersion(database, key);
            return true;
        }
        return false;
    }
    
    @Override
    public boolean expire(int database, String key, long seconds) {
        return pexpire(database, key, seconds * 1000L);
    }
    
    @Override
    public boolean pexpire(int database, String key, long milliseconds) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            logger.debug("pexpire failed: key={} not found", key);
            return false;
        }
        
        long expireTime = System.currentTimeMillis() + milliseconds;
        logger.debug("pexpire: key={} ms={} expireTime={}", key, milliseconds, expireTime);
        StoreValue newStoreValue = new StoreValue(storeValue.value, storeValue.getType(), expireTime);
        store.storage.put(key, newStoreValue);
        bumpKeyVersion(database, key);
        return true;
    }
    
    @Override
    public boolean exists(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            RuntimeConfig.incKeyspaceMisses();
            return false;
        }
        
        if (storeValue.isExpired()) {
            store.storage.invalidate(key);
            RuntimeConfig.incKeyspaceMisses();
            return false;
        }
        
        RuntimeConfig.incKeyspaceHits();
        return true;
    }
    
    @Override
    public long ttl(int database, String key) {
        long pttl = pttl(database, key);
        if (pttl < 0) {
            return pttl;
        }
        return pttl / 1000;
    }
    
    @Override
    public long pttl(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            RuntimeConfig.incKeyspaceMisses();
            return -2;
        }
        
        if (storeValue.isExpired()) {
            store.storage.invalidate(key);
            RuntimeConfig.incKeyspaceMisses();
            return -2;
        }
        
        if (!storeValue.hasExpireTime()) {
            RuntimeConfig.incKeyspaceHits();
            return -1;
        }
        
        RuntimeConfig.incKeyspaceHits();
        Long expireTime = storeValue.getExpireTime();
        long remaining = expireTime - System.currentTimeMillis();
        return remaining > 0 ? remaining : -2;
    }
    
    @Override
    public void flushAll() {
        // 清空所有数据库并重置内存统计
        for (DatabaseStore store : databaseStores.values()) {
            store.storage.invalidateAll();
            store.keySet.clear();
            store.slotToKeys.clear(); // 清空槽位索引
        }
        usedMemory.set(0);
    }
    
    @Override
    public String type(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            RuntimeConfig.incKeyspaceMisses();
            return RdsDataTypeConstant.NONE;
        }
        
        if (storeValue.isExpired()) {
            store.storage.invalidate(key);
            RuntimeConfig.incKeyspaceMisses();
            return RdsDataTypeConstant.NONE;
        }
        
        RuntimeConfig.incKeyspaceHits();
        return storeValue.getType();
    }
    
    private String getType(Object value) {
        if (value == null) {
            return RdsDataTypeConstant.STRING;
        }
        if (value instanceof Map) {
            return RdsDataTypeConstant.HASH;
        }
        if (value instanceof java.util.List) {
            return RdsDataTypeConstant.LIST;
        }
        if (value instanceof java.util.Set) {
            return RdsDataTypeConstant.SET;
        }
        if (value instanceof java.util.SortedSet) {
            return RdsDataTypeConstant.ZSET;
        }
        if (value instanceof ZSetStore) {
            return RdsDataTypeConstant.ZSET;
        }
        if (value instanceof Stream) {
            return RdsDataTypeConstant.STREAM;
        }
        return RdsDataTypeConstant.STRING;
    }
    
    @Override
    public java.util.List<Object> scan(int database, long cursor, String pattern, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        java.util.List<Object> result = new java.util.ArrayList<>();
        
        // 默认为0
        if (cursor == 0 && store.keySet.isEmpty()) {
            // 没有键，返回0游标
            result.add(0L);
            return result;
        }
        
        // 简单实现：遍历所有键，支持模式匹配和计数限制
        int processed = 0;
        boolean found = false;
        
        for (String key : store.keySet.keySet()) {
            // 检查键是否过期
            StoreValue storeValue = store.storage.getIfPresent(key);
            if (storeValue == null || storeValue.isExpired()) {
                // 键不存在或已过期，从keySet中移除
                store.keySet.remove(key);
                store.storage.invalidate(key);
                continue;
            }
            
            // 检查是否匹配模式
            if (pattern != null && !pattern.equals("*")) {
                // 转换为正则表达式
                String regex = pattern.replace("*", ".*")
                                     .replace("?", ".")
                                     .replace("{", "{")
                                     .replace("}", "}");
                if (!key.matches(regex)) {
                    continue;
                }
            }
            
            // 检查是否需要从游标开始
            if (cursor > 0 && !found) {
                // 简单实现：跳过cursor个键
                if (processed < cursor) {
                    processed++;
                    continue;
                } else {
                    found = true;
                }
            }
            
            // 添加到结果中
            result.add(key);
            processed++;
            
            // 达到计数限制，停止
            if (processed >= count) {
                break;
            }
        }
        
        // 计算新游标
        long newCursor = 0;
        if (processed >= count) {
            // 还有更多键，设置新游标
            newCursor = cursor + processed;
        }
        
        // 将新游标添加到结果的开头
        result.add(0, newCursor);
        
        return result;
    }
    
    @Override
    public long dbsize(int database) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        long count = 0;
        
        // 遍历所有键，统计未过期的键数量
        for (String key : store.keySet.keySet()) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            if (storeValue != null && !storeValue.isExpired()) {
                count++;
            } else {
                // 键不存在或已过期，从keySet中移除
                store.keySet.remove(key);
                store.storage.invalidate(key);
            }
        }
        
        return count;
    }
    
    @Override
    public void flushdb(int database) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        // 更新内存统计
        for (String key : store.keySet.keySet()) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            if (storeValue != null) {
                updateMemory(-storeValue.getEstimatedSize());
            }
        }
        store.storage.invalidateAll();
        store.keySet.clear();
        store.slotToKeys.clear(); // 清空槽位索引
        // 清空时仅标记版本变化，不逐个键处理
        // 可以选择在此处重置版本，但为保持 WATCH 语义，保留现有版本映射
    }
    
    @Override
    public long getKeyVersion(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        AtomicLong ver = store.keyVersions.get(key);
        return ver == null ? 0L : ver.get();
    }
    
    @Override
    public void bumpKeyVersion(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        store.keySet.put(key, Boolean.TRUE);
        store.keyVersions.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    // ==================== Hash 操作优化实现 ====================
    
    @Override
    public int hset(int database, String key, String field, String value) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.ConcurrentHashMap<String, String> hash;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            // 创建新的 Hash
            hash = new java.util.concurrent.ConcurrentHashMap<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.ConcurrentHashMap) {
                hash = (java.util.concurrent.ConcurrentHashMap<String, String>) val;
            } else if (val instanceof java.util.Map) {
                // 转换为 ConcurrentHashMap
                hash = new java.util.concurrent.ConcurrentHashMap<>((java.util.Map<String, String>) val);
                isNew = true;
            } else {
                // 类型错误，创建新的
                hash = new java.util.concurrent.ConcurrentHashMap<>();
                isNew = true;
            }
        }
        
        // 检查字段是否已存在
        String oldValue = hash.put(field, value);
        boolean existed = oldValue != null;
        
        // 只有新创建的 Hash 才需要重新存储
        if (isNew) {
            set(database, key, hash);
        } else {
            long delta = 0;
            if (!existed) {
                delta = 64 + field.length() * 2L + value.length() * 2L;
            } else {
                delta = (value.length() - oldValue.length()) * 2L;
            }
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return existed ? 0 : 1;
    }
    
    @Override
    public int hsetnx(int database, String key, String field, String value) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.ConcurrentHashMap<String, String> hash;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            hash = new java.util.concurrent.ConcurrentHashMap<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.ConcurrentHashMap) {
                hash = (java.util.concurrent.ConcurrentHashMap<String, String>) val;
            } else if (val instanceof java.util.Map) {
                hash = new java.util.concurrent.ConcurrentHashMap<>((java.util.Map<String, String>) val);
                isNew = true;
            } else {
                hash = new java.util.concurrent.ConcurrentHashMap<>();
                isNew = true;
            }
        }
        
        String prev = hash.putIfAbsent(field, value);
        if (prev == null) {
            if (isNew) {
                set(database, key, hash);
            } else {
                long delta = 64 + field.length() * 2L + value.length() * 2L;
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
                bumpKeyVersion(database, key);
            }
            return 1;
        } else {
            // 字段已存在，不做修改；如果是新建的 Hash，也不应写回空修改
            return 0;
        }
    }

    @Override
    public int hmset(int database, String key, String... fieldsAndValues) {
        if (fieldsAndValues == null || fieldsAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("wrong number of arguments for HMSET");
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.ConcurrentHashMap<String, String> hash;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            hash = new java.util.concurrent.ConcurrentHashMap<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.ConcurrentHashMap) {
                hash = (java.util.concurrent.ConcurrentHashMap<String, String>) val;
            } else if (val instanceof java.util.Map) {
                hash = new java.util.concurrent.ConcurrentHashMap<>((java.util.Map<String, String>) val);
                isNew = true;
            } else {
                hash = new java.util.concurrent.ConcurrentHashMap<>();
                isNew = true;
            }
        }
        
        int addedCount = 0;
        long delta = 0;
        
        for (int i = 0; i < fieldsAndValues.length; i += 2) {
            String field = fieldsAndValues[i];
            String value = fieldsAndValues[i+1];
            
            String oldValue = hash.put(field, value);
            if (oldValue == null) {
                addedCount++;
                delta += (64 + field.length() * 2L + value.length() * 2L);
            } else {
                delta += (value.length() - oldValue.length()) * 2L;
            }
        }
        
        if (isNew) {
            set(database, key, hash);
        } else {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return addedCount;
    }

    
    @Override
    public String hget(int database, String key, String field) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Map) {
            java.util.Map<?, ?> hash = (java.util.Map<?, ?>) val;
            Object fieldValue = hash.get(field);
            return fieldValue != null ? fieldValue.toString() : null;
        }
        
        return null;
    }

    @Override
    public java.util.List<String> hmget(int database, String key, String... fields) {
        java.util.List<String> result = new java.util.ArrayList<>(fields.length);
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.Map<?, ?> hash = null;
        if (storeValue != null && !storeValue.isExpired() && storeValue.value instanceof java.util.Map) {
            hash = (java.util.Map<?, ?>) storeValue.value;
        }
        
        for (String field : fields) {
            if (hash != null) {
                Object val = hash.get(field);
                result.add(val != null ? val.toString() : null);
            } else {
                result.add(null);
            }
        }
        return result;
    }

    
    @Override
    public int hdel(int database, String key, String... fields) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.Map)) {
            return 0;
        }
        
        java.util.Map<String, String> hash = (java.util.Map<String, String>) val;
        int deleted = 0;
        long delta = 0;
        for (String field : fields) {
            String removedVal = hash.remove(field);
            if (removedVal != null) {
                deleted++;
                delta -= (64 + field.length() * 2L + removedVal.length() * 2L);
            }
        }
        
        if (deleted > 0) {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        return deleted;
    }
    
    @Override
    public boolean hexists(int database, String key, String field) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null) {
            logger.debug("hexists: key={} not found", key);
            return false;
        }
        if (storeValue.isExpired()) {
            logger.debug("hexists: key={} expired", key);
            return false;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Map) {
            boolean exists = ((java.util.Map<?, ?>) val).containsKey(field);
            logger.debug("hexists: key={} field={} exists={}", key, field, exists);
            return exists;
        }
        
        logger.debug("hexists: key={} wrong type", key);
        return false;
    }

    @Override
    public long hincrby(int database, String key, String field, long increment) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.ConcurrentHashMap<String, String> hash;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            hash = new java.util.concurrent.ConcurrentHashMap<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.ConcurrentHashMap) {
                hash = (java.util.concurrent.ConcurrentHashMap<String, String>) val;
            } else if (val instanceof java.util.Map) {
                hash = new java.util.concurrent.ConcurrentHashMap<>((java.util.Map<String, String>) val);
                isNew = true;
            } else {
                hash = new java.util.concurrent.ConcurrentHashMap<>();
                isNew = true;
            }
        }
        
        long newValue;
        String oldValueStr = hash.get(field);
        if (oldValueStr == null) {
            newValue = increment;
        } else {
            try {
                newValue = Long.parseLong(oldValueStr) + increment;
            } catch (NumberFormatException e) {
                throw new RuntimeException("ERR hash value is not an integer");
            }
        }
        
        String newValueStr = String.valueOf(newValue);
        hash.put(field, newValueStr);
        
        if (isNew) {
            set(database, key, hash);
        } else {
            long delta = 0;
            if (oldValueStr == null) {
                delta = 64 + field.length() * 2L + newValueStr.length() * 2L;
            } else {
                delta = (newValueStr.length() - oldValueStr.length()) * 2L;
            }
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return newValue;
    }
    
    @Override
    public java.util.Map<String, String> hgetall(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return java.util.Collections.emptyMap();
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Map) {
            java.util.Map<?, ?> rawHash = (java.util.Map<?, ?>) val;
            java.util.Map<String, String> result = new java.util.HashMap<>(rawHash.size());
            for (java.util.Map.Entry<?, ?> entry : rawHash.entrySet()) {
                result.put(entry.getKey().toString(), entry.getValue().toString());
            }
            return result;
        }
        
        return java.util.Collections.emptyMap();
    }
    
    @Override
    public int hlen(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) val).size();
        }
        
        return 0;
    }
    
    @Override
    public java.util.List<Object> hscan(int database, String key, long cursor, String pattern, int count) {
        java.util.List<Object> result = new java.util.ArrayList<>();
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.Map<String, String> hash = new java.util.HashMap<>();
        if (storeValue != null && !storeValue.isExpired() && storeValue.value instanceof java.util.Map) {
            java.util.Map<?, ?> raw = (java.util.Map<?, ?>) storeValue.value;
            for (java.util.Map.Entry<?, ?> e : raw.entrySet()) {
                hash.put(e.getKey().toString(), e.getValue() == null ? "" : e.getValue().toString());
            }
        }
        
        // 模式为'*'时匹配全部；简单 glob -> 正则转换
        String regex = null;
        if (pattern != null && !"*".equals(pattern)) {
            regex = pattern.replace("*", ".*").replace("?", ".").replace("{", "{").replace("}", "}");
        }
        
        int processed = 0;
        boolean started = cursor == 0;
        for (java.util.Map.Entry<String, String> entry : hash.entrySet()) {
            String field = entry.getKey();
            if (regex != null && !field.matches(regex)) {
                continue;
            }
            if (!started) {
                // 跳过到游标位置（简化实现）
                processed++;
                if (processed > cursor) {
                    started = true;
                }
                continue;
            }
            // 收集字段与值
            result.add(field);
            result.add(entry.getValue());
            if (result.size() / 2 >= count) {
                break;
            }
        }
        
        long newCursor = 0;
        if (result.size() / 2 >= count) {
            // 还有更多，设置新游标（简化为偏移值）
            newCursor = cursor + (result.size() / 2);
        }
        
        // 将新游标插入到开头
        result.add(0, newCursor);
        return result;
    }
    
    // ==================== List 操作优化实现 ====================
    
    @Override
    public int lpush(int database, String key, String... values) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.CopyOnWriteArrayList<String> list;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            list = new java.util.concurrent.CopyOnWriteArrayList<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.CopyOnWriteArrayList) {
                list = (java.util.concurrent.CopyOnWriteArrayList<String>) val;
            } else if (val instanceof java.util.List) {
                list = new java.util.concurrent.CopyOnWriteArrayList<>((java.util.List<String>) val);
                isNew = true;
            } else {
                list = new java.util.concurrent.CopyOnWriteArrayList<>();
                isNew = true;
            }
        }
        
        // 从左侧插入，倒序添加以保持顺序
        long delta = 0;
        for (int i = values.length - 1; i >= 0; i--) {
            list.add(0, values[i]);
            delta += (32 + values[i].length() * 2L);
        }
        
        if (isNew) {
            set(database, key, list);
        } else {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return list.size();
    }
    
    @Override
    public int rpush(int database, String key, String... values) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.CopyOnWriteArrayList<String> list;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            list = new java.util.concurrent.CopyOnWriteArrayList<>();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.CopyOnWriteArrayList) {
                list = (java.util.concurrent.CopyOnWriteArrayList<String>) val;
            } else if (val instanceof java.util.List) {
                list = new java.util.concurrent.CopyOnWriteArrayList<>((java.util.List<String>) val);
                isNew = true;
            } else {
                list = new java.util.concurrent.CopyOnWriteArrayList<>();
                isNew = true;
            }
        }
        
        // 从右侧插入
        long delta = 0;
        for (String value : values) {
            list.add(value);
            delta += (32 + value.length() * 2L);
        }
        
        if (isNew) {
            set(database, key, list);
        } else {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return list.size();
    }
    
    @Override
    public String lpop(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            return null;
        }
        
        java.util.List<String> list = (java.util.List<String>) val;
        if (list.isEmpty()) {
            RuntimeConfig.incKeyspaceHits();
            return null;
        }
        
        RuntimeConfig.incKeyspaceHits();
        String v = list.remove(0);
        long delta = -(32 + v.length() * 2L);
        storeValue.updateEstimatedSize(delta);
        updateMemory(delta);
        bumpKeyVersion(database, key);
        return v;
    }
    
    @Override
    public String rpop(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            return null;
        }
        
        java.util.List<String> list = (java.util.List<String>) val;
        if (list.isEmpty()) {
            RuntimeConfig.incKeyspaceHits();
            return null;
        }
        
        RuntimeConfig.incKeyspaceHits();
        String v = list.remove(list.size() - 1);
        long delta = -(32 + v.length() * 2L);
        storeValue.updateEstimatedSize(delta);
        updateMemory(delta);
        bumpKeyVersion(database, key);
        return v;
    }
    
    @Override
    public int lrem(int database, String key, int count, String value) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            return 0;
        }
        
        // Copy to ArrayList for modification (CopyOnWriteArrayList iterator doesn't support remove)
        java.util.List<String> list = new java.util.ArrayList<>((java.util.List<String>) val);
        int removed = 0;
        
        if (count == 0) {
            // Remove all occurrences
            java.util.Iterator<String> it = list.iterator();
            while (it.hasNext()) {
                String v = it.next();
                if (v.equals(value)) {
                    it.remove();
                    removed++;
                }
            }
        } else if (count > 0) {
            // Remove first count occurrences from head
            java.util.Iterator<String> it = list.iterator();
            while (it.hasNext() && removed < count) {
                String v = it.next();
                if (v.equals(value)) {
                    it.remove();
                    removed++;
                }
            }
        } else {
            // Remove first |count| occurrences from tail
            int toRemove = Math.abs(count);
            java.util.ListIterator<String> it = list.listIterator(list.size());
            while (it.hasPrevious() && removed < toRemove) {
                String v = it.previous();
                if (v.equals(value)) {
                    it.remove();
                    removed++;
                }
            }
        }
        
        if (removed > 0) {
            // Replace with new CopyOnWriteArrayList and StoreValue
            java.util.concurrent.CopyOnWriteArrayList<String> newList = new java.util.concurrent.CopyOnWriteArrayList<>(list);
            StoreValue newValue;
            if (storeValue.hasExpireTime()) {
                newValue = new StoreValue(newList, RdsDataTypeConstant.LIST, storeValue.getExpireTime());
            } else {
                newValue = new StoreValue(newList, RdsDataTypeConstant.LIST);
            }
            
            long oldSize = storeValue.getEstimatedSize();
            long newSize = newValue.getEstimatedSize();
            updateMemory(newSize - oldSize);
            
            store.storage.put(key, newValue);
            bumpKeyVersion(database, key);
        }
        
        return removed;
    }

    @Override
    public int llen(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.List) {
            RuntimeConfig.incKeyspaceHits();
            return ((java.util.List<?>) val).size();
        }
        
        RuntimeConfig.incKeyspaceHits();
        return 0;
    }
    
    @Override
    public String lindex(int database, String key, int index) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            throw new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        
        java.util.List<?> list = (java.util.List<?>) val;
        int size = list.size();
        
        if (index >= 0) {
            if (index < size) {
                return list.get(index).toString();
            }
        } else {
            if (Math.abs(index) <= size) {
                return list.get(size + index).toString();
            }
        }
        
        return null;
    }
    
    @Override
    public void lset(int database, String key, int index, String value) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            throw new RuntimeException("ERR no such key");
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            throw new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value");
        }
        
        @SuppressWarnings("unchecked")
        java.util.List<String> list = (java.util.List<String>) val;
        int size = list.size();
        
        int actualIndex;
        if (index >= 0) {
            actualIndex = index;
        } else {
            actualIndex = size + index;
        }
        
        if (actualIndex < 0 || actualIndex >= size) {
            throw new RuntimeException("ERR index out of range");
        }
        
        list.set(actualIndex, value);
    }
    
    @Override
    public java.util.List<String> lrange(int database, String key, long start, long stop) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            RuntimeConfig.incKeyspaceHits();
            return java.util.Collections.emptyList();
        }
        
        RuntimeConfig.incKeyspaceHits();
        java.util.List<?> list = (java.util.List<?>) val;
        int size = list.size();
        
        // 处理负数索引
        if (start < 0) {
            start = Math.max(0, size + start);
        }
        if (stop < 0) {
            stop = Math.max(-1, size + stop);
        }
        
        int startIdx = (int) Math.min(start, size);
        int stopIdx = (int) Math.min(stop + 1, size);
        
        if (startIdx >= stopIdx || startIdx >= size) {
            return java.util.Collections.emptyList();
        }
        
        java.util.List<String> result = new java.util.ArrayList<>(stopIdx - startIdx);
        for (int i = startIdx; i < stopIdx; i++) {
            result.add(list.get(i).toString());
        }
        
        return result;
    }
    
    @Override
    public void ltrim(int database, String key, long start, long stop) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.List)) {
            return;
        }
        
        @SuppressWarnings("unchecked")
        java.util.List<String> list = (java.util.List<String>) val;
        int size = list.size();
        
        // 处理负数索引
        if (start < 0) {
            start = Math.max(0, size + start);
        }
        if (stop < 0) {
            stop = Math.max(-1, size + stop);
        }
        
        int startIdx = (int) Math.min(start, size);
        int stopIdx = (int) Math.min(stop + 1, size);
        
        if (startIdx >= stopIdx || startIdx >= size) {
            // 清空列表
            long oldSize = storeValue.getEstimatedSize();
            list.clear();
            storeValue.updateEstimatedSize(-oldSize);
            updateMemory(-oldSize);
        } else {
            // 保留指定范围的元素
            java.util.List<String> subList = new java.util.ArrayList<>(list.subList(startIdx, stopIdx));
            list.clear();
            list.addAll(subList);
            // 重新计算大小（简化处理）
            long newSize = 64;
            for (String s : list) {
                newSize += 32 + s.length() * 2L;
            }
            long oldSize = storeValue.getEstimatedSize();
            storeValue.updateEstimatedSize(newSize - oldSize);
            updateMemory(newSize - oldSize);
        }
        
        bumpKeyVersion(database, key);
    }
    
    @Override
    public java.util.List<String> blpop(int database, String[] keys, long timeout) {
        // 非阻塞实现：立即检查所有键，如果有元素就弹出
        // 真正的阻塞实现需要修改服务器架构
        for (String key : keys) {
            String value = lpop(database, key);
            if (value != null) {
                java.util.List<String> result = new java.util.ArrayList<>(2);
                result.add(key);
                result.add(value);
                return result;
            }
        }
        // 没有可用元素，返回 null（在非阻塞模式下）
        return null;
    }
    
    @Override
    public java.util.List<String> brpop(int database, String[] keys, long timeout) {
        // 非阻塞实现：立即检查所有键，如果有元素就弹出
        for (String key : keys) {
            String value = rpop(database, key);
            if (value != null) {
                java.util.List<String> result = new java.util.ArrayList<>(2);
                result.add(key);
                result.add(value);
                return result;
            }
        }
        // 没有可用元素，返回 null（在非阻塞模式下）
        return null;
    }
    
    // ==================== Set 操作优化实现 ====================
    
    @Override
    public int sadd(int database, String key, String... members) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> set;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            set = java.util.concurrent.ConcurrentHashMap.newKeySet();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof java.util.concurrent.ConcurrentHashMap.KeySetView) {
                set = (java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>) val;
            } else if (val instanceof java.util.Set) {
                set = java.util.concurrent.ConcurrentHashMap.newKeySet();
                set.addAll((java.util.Set<String>) val);
                isNew = true;
            } else {
                set = java.util.concurrent.ConcurrentHashMap.newKeySet();
                isNew = true;
            }
        }
        
        int added = 0;
        long delta = 0;
        for (String member : members) {
            if (set.add(member)) {
                added++;
                delta += (32 + member.length() * 2L);
            }
        }
        
        if (isNew) {
            set(database, key, set);
        } else if (added > 0) {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return added;
    }
    
    @Override
    public int srem(int database, String key, String... members) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.Set)) {
            RuntimeConfig.incKeyspaceHits();
            return 0;
        }
        
        java.util.Set<String> set = (java.util.Set<String>) val;
        int removed = 0;
        long delta = 0;
        for (String member : members) {
            if (set.remove(member)) {
                removed++;
                delta -= (32 + member.length() * 2L);
            }
        }
        
        if (removed > 0) {
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        return removed;
    }
    
    @Override
    public boolean sismember(int database, String key, String member) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return false;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Set) {
            RuntimeConfig.incKeyspaceHits();
            return ((java.util.Set<?>) val).contains(member);
        }
        
        RuntimeConfig.incKeyspaceHits();
        return false;
    }
    
    @Override
    public java.util.Set<String> smembers(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptySet();
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Set) {
            RuntimeConfig.incKeyspaceHits();
            java.util.Set<?> rawSet = (java.util.Set<?>) val;
            java.util.Set<String> result = new java.util.HashSet<>(rawSet.size());
            for (Object item : rawSet) {
                result.add(item.toString());
            }
            return result;
        }
        
        RuntimeConfig.incKeyspaceHits();
        return java.util.Collections.emptySet();
    }
    
    @Override
    public int scard(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Set) {
            RuntimeConfig.incKeyspaceHits();
            return ((java.util.Set<?>) val).size();
        }
        
        RuntimeConfig.incKeyspaceHits();
        return 0;
    }

    @Override
    public java.util.Set<String> sinter(int database, String... keys) {
        java.util.Set<String> result = null;
        
        for (String key : keys) {
            java.util.Set<String> set = smembers(database, key);
            if (result == null) {
                result = new java.util.HashSet<>(set);
            } else {
                result.retainAll(set);
            }
            if (result.isEmpty()) {
                break;
            }
        }
        
        return result != null ? result : java.util.Collections.emptySet();
    }

    @Override
    public java.util.Set<String> sunion(int database, String... keys) {
        java.util.Set<String> result = new java.util.HashSet<>();
        
        for (String key : keys) {
            result.addAll(smembers(database, key));
        }
        
        return result;
    }

    @Override
    public java.util.Set<String> sdiff(int database, String... keys) {
        if (keys.length == 0) {
            return java.util.Collections.emptySet();
        }
        
        java.util.Set<String> result = new java.util.HashSet<>(smembers(database, keys[0]));
        
        for (int i = 1; i < keys.length; i++) {
            result.removeAll(smembers(database, keys[i]));
        }
        
        return result;
    }
    
    @Override
    public java.util.List<Object> sscan(int database, String key, long cursor, String pattern, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        java.util.List<Object> result = new java.util.ArrayList<>();
        
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null || storeValue.isExpired()) {
            result.add(0L);
            return result;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof java.util.concurrent.ConcurrentHashMap.KeySetView)) {
            result.add(0L);
            return result;
        }
        
        @SuppressWarnings("unchecked")
        java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> set = 
                (java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>) val;
        RuntimeConfig.incKeyspaceHits();
        
        // 转换模式为正则表达式
        String regex = null;
        if (pattern != null && !pattern.equals("*")) {
            regex = pattern.replace(".", "\\.")
                          .replace("*", ".*")
                          .replace("?", ".");
        }
        
        int processed = 0;
        int added = 0;
        long newCursor = 0;
        
        for (String member : set) {
            // 如果有游标，跳过之前的元素
            if (cursor > 0 && processed < cursor) {
                processed++;
                continue;
            }
            
            // 检查模式匹配
            if (regex != null && !member.matches(regex)) {
                processed++;
                continue;
            }
            
            if (added < count) {
                result.add(member);
                added++;
            }
            processed++;
            
            if (added >= count) {
                newCursor = processed;
                break;
            }
        }
        
        // 如果已经遍历完所有元素，游标返回0
        result.add(0, newCursor);
        return result;
    }
    
    // ==================== ZSet 操作优化实现 ====================
    
    /**
     * ZSet 内部存储结构
     * 使用 ConcurrentSkipListMap 保持按分数排序
     */
    private static class ZSetStore implements java.io.Serializable {
        // N-31：ZSetStore 是 ZSET 键的存储值对象，MIGRATE 迁移（Java 序列化）需要其可序列化。
        // 字段均为 ConcurrentHashMap/ConcurrentSkipListMap/ConcurrentSkipListSet（均可序列化），
        // 反序列化白名单按包前缀 com.janeluo.luban.rds.core.store.* 放行。
        private static final long serialVersionUID = 1L;
        // member -> score 映射，用于快速查找分数
        final java.util.concurrent.ConcurrentHashMap<String, Double> memberScores = 
                new java.util.concurrent.ConcurrentHashMap<>();
        // score -> members 映射，用于按分数排序
        // 同分成员使用 ConcurrentSkipListSet 按字典序迭代（Redis 7 语义）
        final java.util.concurrent.ConcurrentSkipListMap<Double, java.util.concurrent.ConcurrentSkipListSet<String>> scoreMembers = 
                new java.util.concurrent.ConcurrentSkipListMap<>();
        
        int add(String member, double score) {
            Double oldScore = memberScores.put(member, score);
            
            // 如果是更新，先从旧分数中移除
            if (oldScore != null) {
                java.util.concurrent.ConcurrentSkipListSet<String> oldSet = scoreMembers.get(oldScore);
                if (oldSet != null) {
                    oldSet.remove(member);
                    if (oldSet.isEmpty()) {
                        scoreMembers.remove(oldScore);
                    }
                }
            }
            
            // 添加到新分数
            scoreMembers.computeIfAbsent(score, k -> new java.util.concurrent.ConcurrentSkipListSet<>()).add(member);
            
            return oldScore == null ? 1 : 0;
        }
        
        long[] remove(String... members) {
            long removedCount = 0;
            long removedBytes = 0;
            for (String member : members) {
                Double score = memberScores.remove(member);
                if (score != null) {
                    java.util.concurrent.ConcurrentSkipListSet<String> set = scoreMembers.get(score);
                    if (set != null) {
                        set.remove(member);
                        if (set.isEmpty()) {
                            scoreMembers.remove(score);
                        }
                    }
                    removedCount++;
                    removedBytes += (128 + member.length() * 2L);
                }
            }
            return new long[]{removedCount, removedBytes};
        }
        
        Double getScore(String member) {
            return memberScores.get(member);
        }
        
        java.util.List<String> range(long start, long stop) {
            int size = memberScores.size();
            if (start < 0) start = Math.max(0, size + start);
            if (stop < 0) stop = Math.max(-1, size + stop);
            
            int startIdx = (int) Math.min(start, size);
            int stopIdx = (int) Math.min(stop + 1, size);
            
            if (startIdx >= stopIdx || startIdx >= size) {
                return java.util.Collections.emptyList();
            }
            
            java.util.List<String> result = new java.util.ArrayList<>(stopIdx - startIdx);
            int idx = 0;
            for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : scoreMembers.entrySet()) {
                for (String member : entry.getValue()) {
                    if (idx >= startIdx && idx < stopIdx) {
                        result.add(member);
                    }
                    idx++;
                    if (idx >= stopIdx) break;
                }
                if (idx >= stopIdx) break;
            }
            return result;
        }
        
        java.util.List<String> rangeByScore(double min, double max, int offset, int count) {
            java.util.NavigableMap<Double, java.util.concurrent.ConcurrentSkipListSet<String>> subMap = 
                    scoreMembers.subMap(min, true, max, true);
            
            java.util.List<String> result = new java.util.ArrayList<>();
            int skipped = 0;
            
            for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : subMap.entrySet()) {
                for (String member : entry.getValue()) {
                    if (skipped < offset) {
                        skipped++;
                        continue;
                    }
                    if (count >= 0 && result.size() >= count) {
                        return result;
                    }
                    result.add(member);
                }
                if (count >= 0 && result.size() >= count) {
                    break;
                }
            }
            return result;
        }
        
        int size() {
            return memberScores.size();
        }
    }
    
    @Override
    public int zadd(int database, String key, double score, String member) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        ZSetStore zset;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            zset = new ZSetStore();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof ZSetStore) {
                zset = (ZSetStore) val;
            } else {
                zset = new ZSetStore();
                isNew = true;
            }
        }
        
        int result = zset.add(member, score);
        
        if (isNew) {
            set(database, key, zset);
        } else {
            if (result == 1) {
                long delta = 128 + member.length() * 2L;
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
            }
            if (result == 1 || result == 0) {
                bumpKeyVersion(database, key);
            }
        }
        
        return result;
    }
    
    @Override
    public int zrem(int database, String key, String... members) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            long[] result = ((ZSetStore) val).remove(members);
            int removed = (int) result[0];
            if (removed > 0) {
                long delta = -result[1];
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
                bumpKeyVersion(database, key);
            }
            return removed;
        }
        
        return 0;
    }
    
    @Override
    public Double zscore(int database, String key, String member) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            RuntimeConfig.incKeyspaceHits();
            return ((ZSetStore) val).getScore(member);
        }
        
        RuntimeConfig.incKeyspaceHits();
        return null;
    }
    
    @Override
    public java.util.List<String> zrange(int database, String key, long start, long stop) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            RuntimeConfig.incKeyspaceHits();
            return ((ZSetStore) val).range(start, stop);
        }
        
        RuntimeConfig.incKeyspaceHits();
        return java.util.Collections.emptyList();
    }
    
    @Override
    public int zcard(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            RuntimeConfig.incKeyspaceHits();
            return ((ZSetStore) val).size();
        }
        
        RuntimeConfig.incKeyspaceHits();
        return 0;
    }
    
    @Override
    public java.util.List<String> zrangeByScore(int database, String key, double min, double max, int offset, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            RuntimeConfig.incKeyspaceHits();
            return ((ZSetStore) val).rangeByScore(min, max, offset, count);
        }
        
        RuntimeConfig.incKeyspaceHits();
        return java.util.Collections.emptyList();
    }
    
    @Override
    public java.util.Map<String, Double> zgetAllWithScores(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        Object val = storeValue.value;
        if (val instanceof ZSetStore) {
            RuntimeConfig.incKeyspaceHits();
            return new java.util.HashMap<>(((ZSetStore) val).memberScores);
        }
        
        RuntimeConfig.incKeyspaceHits();
        return null;
    }
    
    @Override
    public java.util.List<Object> zscan(int database, String key, long cursor, String pattern, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        java.util.List<Object> result = new java.util.ArrayList<>();
        
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null || storeValue.isExpired()) {
            result.add(0L);
            return result;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            result.add(0L);
            return result;
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        // 转换模式为正则表达式
        String regex = null;
        if (pattern != null && !pattern.equals("*")) {
            regex = pattern.replace(".", "\\.")
                          .replace("*", ".*")
                          .replace("?", ".");
        }
        
        int processed = 0;
        int added = 0;
        long newCursor = 0;
        
        for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : zset.scoreMembers.entrySet()) {
            for (String member : entry.getValue()) {
                // 如果有游标，跳过之前的元素
                if (cursor > 0 && processed < cursor) {
                    processed++;
                    continue;
                }
                
                // 检查模式匹配
                if (regex != null && !member.matches(regex)) {
                    processed++;
                    continue;
                }
                
                if (added < count) {
                    result.add(member);
                    result.add(entry.getKey().toString());
                    added++;
                }
                processed++;
                
                if (added >= count) {
                    newCursor = processed;
                    break;
                }
            }
            if (added >= count) {
                break;
            }
        }
        
        // 如果已经遍历完所有元素，游标返回0
        result.add(0, newCursor);
        return result;
    }
    
    @Override
    public int zremrangeByScore(int database, String key, double min, double max) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            return 0;
        }
        
        ZSetStore zset = (ZSetStore) val;
        
        // 获取分数范围内的所有成员
        java.util.NavigableMap<Double, java.util.concurrent.ConcurrentSkipListSet<String>> subMap = 
                zset.scoreMembers.subMap(min, true, max, true);
        
        java.util.List<String> membersToRemove = new java.util.ArrayList<>();
        for (java.util.concurrent.ConcurrentSkipListSet<String> members : subMap.values()) {
            membersToRemove.addAll(members);
        }
        
        if (membersToRemove.isEmpty()) {
            return 0;
        }
        
        long[] result = zset.remove(membersToRemove.toArray(new String[0]));
        int removed = (int) result[0];
        
        if (removed > 0) {
            long delta = -result[1];
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return removed;
    }
    
    @Override
    public int zremrangeByRank(int database, String key, long start, long stop) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            return 0;
        }
        
        ZSetStore zset = (ZSetStore) val;
        int size = zset.size();
        
        // 处理负索引
        if (start < 0) start = Math.max(0, size + start);
        if (stop < 0) stop = Math.max(-1, size + stop);
        
        int startIdx = (int) Math.min(start, size);
        int stopIdx = (int) Math.min(stop + 1, size);
        
        if (startIdx >= stopIdx || startIdx >= size) {
            return 0;
        }
        
        // 收集要删除的成员
        java.util.List<String> membersToRemove = new java.util.ArrayList<>();
        int idx = 0;
        for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : zset.scoreMembers.entrySet()) {
            for (String member : entry.getValue()) {
                if (idx >= startIdx && idx < stopIdx) {
                    membersToRemove.add(member);
                }
                idx++;
                if (idx >= stopIdx) break;
            }
            if (idx >= stopIdx) break;
        }
        
        if (membersToRemove.isEmpty()) {
            return 0;
        }
        
        long[] result = zset.remove(membersToRemove.toArray(new String[0]));
        int removed = (int) result[0];
        
        if (removed > 0) {
            long delta = -result[1];
            storeValue.updateEstimatedSize(delta);
            updateMemory(delta);
            bumpKeyVersion(database, key);
        }
        
        return removed;
    }
    
    @Override
    public Long zrank(int database, String key, String member) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            RuntimeConfig.incKeyspaceHits();
            return null;
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        Double score = zset.getScore(member);
        if (score == null) {
            return null;
        }
        
        // 计算排名：遍历所有分数小于该成员分数的成员数量
        long rank = 0;
        java.util.NavigableMap<Double, java.util.concurrent.ConcurrentSkipListSet<String>> headMap = 
                zset.scoreMembers.headMap(score, false);
        for (java.util.concurrent.ConcurrentSkipListSet<String> members : headMap.values()) {
            rank += members.size();
        }
        
        // 在相同分数的成员中找到该成员的位置
        java.util.concurrent.ConcurrentSkipListSet<String> sameScoreMembers = zset.scoreMembers.get(score);
        if (sameScoreMembers != null) {
            for (String m : sameScoreMembers) {
                if (m.equals(member)) {
                    break;
                }
                rank++;
            }
        }
        
        return rank;
    }
    
    @Override
    public Long zrevrank(int database, String key, String member) {
        Long rank = zrank(database, key, member);
        if (rank == null) {
            return null;
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            return null;
        }
        
        ZSetStore zset = (ZSetStore) val;
        return (long) zset.size() - 1 - rank;
    }
    
    @Override
    public double zincrby(int database, String key, double increment, String member) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        ZSetStore zset;
        boolean isNew = false;
        
        if (storeValue == null || storeValue.isExpired()) {
            zset = new ZSetStore();
            isNew = true;
        } else {
            Object val = storeValue.value;
            if (val instanceof ZSetStore) {
                zset = (ZSetStore) val;
            } else {
                zset = new ZSetStore();
                isNew = true;
            }
        }
        
        Double oldScore = zset.getScore(member);
        double newScore = (oldScore != null) ? oldScore + increment : increment;
        
        zset.add(member, newScore);
        
        if (isNew) {
            set(database, key, zset);
        } else {
            if (oldScore == null) {
                long delta = 128 + member.length() * 2L;
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
            }
            bumpKeyVersion(database, key);
        }
        
        return newScore;
    }
    
    @Override
    public int zcount(int database, String key, double min, double max) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            RuntimeConfig.incKeyspaceHits();
            return 0;
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        java.util.NavigableMap<Double, java.util.concurrent.ConcurrentSkipListSet<String>> subMap = 
                zset.scoreMembers.subMap(min, true, max, true);
        
        int count = 0;
        for (java.util.concurrent.ConcurrentSkipListSet<String> members : subMap.values()) {
            count += members.size();
        }
        
        return count;
    }
    
    @Override
    public java.util.List<String> zpopmax(int database, String key, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            RuntimeConfig.incKeyspaceHits();
            return java.util.Collections.emptyList();
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        java.util.List<String> result = new java.util.ArrayList<>();
        int removed = 0;
        
        // 从最高分数开始遍历（降序）；同分按字典序降序（Redis 7 语义）
        for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : 
                zset.scoreMembers.descendingMap().entrySet()) {
            for (String member : entry.getValue().descendingSet()) {
                if (removed >= count) break;
                result.add(member);
                result.add(entry.getKey().toString());
                removed++;
            }
            if (removed >= count) break;
        }
        
        // 删除这些成员
        if (!result.isEmpty()) {
            java.util.List<String> membersToRemove = new java.util.ArrayList<>();
            for (int i = 0; i < result.size(); i += 2) {
                membersToRemove.add(result.get(i));
            }
            long[] removeResult = zset.remove(membersToRemove.toArray(new String[0]));
            if (removeResult[0] > 0) {
                long delta = -removeResult[1];
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
                bumpKeyVersion(database, key);
            }
        }
        
        return result;
    }
    
    @Override
    public java.util.List<String> zpopmin(int database, String key, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            RuntimeConfig.incKeyspaceHits();
            return java.util.Collections.emptyList();
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        java.util.List<String> result = new java.util.ArrayList<>();
        int removed = 0;
        
        // 从最低分数开始遍历（升序）；同分按字典序升序（Redis 7 语义）
        for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : 
                zset.scoreMembers.entrySet()) {
            for (String member : entry.getValue()) {
                if (removed >= count) break;
                result.add(member);
                result.add(entry.getKey().toString());
                removed++;
            }
            if (removed >= count) break;
        }
        
        // 删除这些成员
        if (!result.isEmpty()) {
            java.util.List<String> membersToRemove = new java.util.ArrayList<>();
            for (int i = 0; i < result.size(); i += 2) {
                membersToRemove.add(result.get(i));
            }
            long[] removeResult = zset.remove(membersToRemove.toArray(new String[0]));
            if (removeResult[0] > 0) {
                long delta = -removeResult[1];
                storeValue.updateEstimatedSize(delta);
                updateMemory(delta);
                bumpKeyVersion(database, key);
            }
        }
        
        return result;
    }
    
    @Override
    public java.util.List<String> zrevrange(int database, String key, long start, long stop) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            RuntimeConfig.incKeyspaceMisses();
            return java.util.Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof ZSetStore)) {
            RuntimeConfig.incKeyspaceHits();
            return java.util.Collections.emptyList();
        }
        
        ZSetStore zset = (ZSetStore) val;
        RuntimeConfig.incKeyspaceHits();
        
        int size = zset.size();
        if (start < 0) start = Math.max(0, size + start);
        if (stop < 0) stop = Math.max(-1, size + stop);
        
        int startIdx = (int) Math.min(start, size);
        int stopIdx = (int) Math.min(stop + 1, size);
        
        if (startIdx >= stopIdx || startIdx >= size) {
            return java.util.Collections.emptyList();
        }
        
        java.util.List<String> result = new java.util.ArrayList<>(stopIdx - startIdx);
        int idx = 0;
        
        // 降序遍历；同分按字典序降序（Redis 7 语义）
        for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentSkipListSet<String>> entry : 
                zset.scoreMembers.descendingMap().entrySet()) {
            for (String member : entry.getValue().descendingSet()) {
                if (idx >= startIdx && idx < stopIdx) {
                    result.add(member);
                }
                idx++;
                if (idx >= stopIdx) break;
            }
            if (idx >= stopIdx) break;
        }
        
        return result;
    }

    @Override
    public Long getMemoryUsage(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null) {
            return null;
        }
        
        if (storeValue.isExpired()) {
            store.storage.invalidate(key);
            store.keySet.remove(key);
            updateMemory(-storeValue.getEstimatedSize());
            return null;
        }
        
        return storeValue.getEstimatedSize();
    }
    
    // ==================== Memory Fragmentation & Defragmentation ====================
    
    /**
     * Calculate memory fragmentation ratio
     * Fragmentation ratio = (usedMemory - effectiveMemory) / usedMemory * 100
     * 
     * @return Memory fragmentation ratio (percentage)
     */
    @Override
    public double getMemoryFragmentationRatio() {
        long used = usedMemory.get();
        if (used == 0) {
            return 0.0;
        }
        
        // Estimate effective memory: sum of all keys' actual sizes
        long effectiveMemory = 0;
        for (DatabaseStore store : databaseStores.values()) {
            for (String key : store.keySet.keySet()) {
                StoreValue value = store.storage.getIfPresent(key);
                if (value != null && !value.isExpired()) {
                    effectiveMemory += value.getEstimatedSize();
                }
            }
        }
        
        // Fragmentation ratio = (estimated used memory - effective memory) / estimated used memory * 100
        double fragmentation = ((double)(used - effectiveMemory) / used) * 100;
        return Math.max(0, fragmentation);
    }
    
    /**
     * Execute memory defragmentation
     * Cleans expired keys and compresses internal data structures
     * 
     * @return Amount of memory freed in bytes
     */
    @Override
    public long defragment() {
        long freedMemory = 0;
        
        // 1. Clean all expired keys
        freedMemory += cleanExpiredKeys();
        
        // 2. Compress Caffeine Cache (via cleanUp)
        for (DatabaseStore store : databaseStores.values()) {
            store.storage.cleanUp();
        }
        
        // 3. Suggest JVM to perform garbage collection
        System.gc();
        
        logger.info("Memory defragmentation completed, freed {} bytes", freedMemory);
        return freedMemory;
    }
    
    /**
     * Clean all expired keys from all databases
     * 
     * @return Amount of memory freed in bytes
     */
    private long cleanExpiredKeys() {
        long freed = 0;
        for (Map.Entry<Integer, DatabaseStore> entry : databaseStores.entrySet()) {
            DatabaseStore store = entry.getValue();
            List<String> keysToRemove = new ArrayList<>();
            
            for (String key : store.keySet.keySet()) {
                StoreValue value = store.storage.getIfPresent(key);
                if (value != null && value.isExpired()) {
                    keysToRemove.add(key);
                }
            }
            
            for (String key : keysToRemove) {
                StoreValue value = store.storage.getIfPresent(key);
                if (value != null) {
                    freed += value.getEstimatedSize();
                    store.storage.invalidate(key);
                    store.keySet.remove(key);
                }
            }
        }
        
        if (freed > 0) {
            updateMemory(-freed);
        }
        return freed;
    }
    
    /**
     * Get memory statistics
     * 
     * @return MemoryStats object containing memory usage information
     */
    @Override
    public MemoryStats getMemoryStats() {
        int totalKeys = 0;
        int expiredKeys = 0;
        
        for (DatabaseStore store : databaseStores.values()) {
            for (String key : store.keySet.keySet()) {
                StoreValue value = store.storage.getIfPresent(key);
                if (value != null) {
                    if (value.isExpired()) {
                        expiredKeys++;
                    } else {
                        totalKeys++;
                    }
                }
            }
        }
        
        return new MemoryStats(
            usedMemory.get(),
            peakUsedMemory.get(),
            maxMemory,
            getMemoryFragmentationRatio(),
            totalKeys,
            expiredKeys
        );
    }
    
    // ==================== Stream 操作实现 ====================
    
    /**
     * 添加消息到流
     */
    @Override
    public StreamId xadd(int database, String key, StreamId id, Map<String, String> fields,
                         boolean nomkstream, Long maxLen, StreamId minId, Integer limit, boolean approximate) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        
        synchronized (getLockForKey(database, key)) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            
            Stream stream;
            boolean isNew = false;
            
            if (storeValue == null || storeValue.isExpired()) {
                if (nomkstream) {
                    return null;
                }
                stream = new Stream();
                isNew = true;
            } else {
                Object val = storeValue.value;
                if (val instanceof Stream) {
                    stream = (Stream) val;
                } else {
                    return null;
                }
            }
            
            try {
                StreamId generatedId = stream.addEntry(id, fields);
                
                // 处理裁剪
                if (maxLen != null && maxLen > 0) {
                    stream.trim(maxLen.intValue());
                }
                if (minId != null) {
                    stream.trim(minId);
                }
                
                if (isNew) {
                    set(database, key, stream);
                } else {
                    bumpKeyVersion(database, key);
                }
                
                return generatedId;
            } catch (IllegalArgumentException e) {
                logger.debug("XADD failed: {}", e.getMessage());
                return null;
            }
        }
    }
    
    /**
     * 获取流中消息数量
     */
    @Override
    public long xlen(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (val instanceof Stream) {
            return ((Stream) val).getLength();
        }
        
        return 0;
    }
    
    /**
     * 范围查询消息
     */
    @Override
    public List<StreamEntry> xrange(int database, String key, StreamId start, StreamId end,
                                    boolean exclusiveStart, boolean exclusiveEnd, int count, boolean reverse) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (val instanceof Stream) {
            Stream stream = (Stream) val;
            
            if (reverse) {
                return stream.getRangeFromReverse(end, exclusiveEnd, count);
            } else {
                return stream.getRange(start, end, exclusiveStart, exclusiveEnd, count);
            }
        }
        
        return Collections.emptyList();
    }
    
    /**
     * 删除消息
     */
    @Override
    public long xdel(int database, String key, StreamId... ids) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return 0;
        }
        
        Stream stream = (Stream) val;
        long deleted = 0;
        
        for (StreamId id : ids) {
            if (stream.deleteEntry(id)) {
                deleted++;
            }
        }
        
        if (deleted > 0) {
            bumpKeyVersion(database, key);
        }
        
        return deleted;
    }
    
    /**
     * 裁剪流
     */
    @Override
    public long xtrim(int database, String key, Long maxLen, StreamId minId, Integer limit, boolean approximate) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return 0;
        }
        
        Stream stream = (Stream) val;
        long trimmed = 0;
        
        if (maxLen != null && maxLen > 0) {
            trimmed += stream.trim(maxLen.intValue());
        }
        if (minId != null) {
            trimmed += stream.trim(minId);
        }
        
        if (trimmed > 0) {
            bumpKeyVersion(database, key);
        }
        
        return trimmed;
    }
    
    /**
     * 获取流对象
     */
    @Override
    public Stream getStream(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (val instanceof Stream) {
            return (Stream) val;
        }
        
        return null;
    }
    
    /**
     * 创建消费者组
     */
    @Override
    public boolean xgroupCreate(int database, String key, String group, StreamId id, boolean mkstream) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        
        synchronized (getLockForKey(database, key)) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            
            Stream stream;
            boolean isNew = false;
            
            if (storeValue == null || storeValue.isExpired()) {
                if (!mkstream) {
                    return false;
                }
                stream = new Stream();
                isNew = true;
            } else {
                Object val = storeValue.value;
                if (val instanceof Stream) {
                    stream = (Stream) val;
                } else {
                    return false;
                }
            }
            
            StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
            if (groupManager == null) {
                groupManager = new StreamConsumerGroupManager(key);
                stream.setConsumerGroupManager(groupManager);
            }
            
            try {
                StreamId startId = (id != null) ? id : stream.getLastGeneratedId();
                if (startId == null) {
                    startId = StreamId.MIN_ID;
                }
                
                groupManager.createGroup(group, startId);
                
                if (isNew) {
                    set(database, key, stream);
                } else {
                    bumpKeyVersion(database, key);
                }
                
                return true;
            } catch (IllegalStateException e) {
                logger.debug("XGROUP CREATE failed: {}", e.getMessage());
                return false;
            }
        }
    }
    
    /**
     * 销毁消费者组
     */
    @Override
    public boolean xgroupDestroy(int database, String key, String group) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return false;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return false;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return false;
        }
        
        boolean destroyed = groupManager.destroyGroup(group);
        if (destroyed) {
            bumpKeyVersion(database, key);
        }
        
        return destroyed;
    }
    
    /**
     * 删除消费者
     */
    @Override
    public long xgroupDelConsumer(int database, String key, String group, String consumer) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return 0;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return 0;
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return 0;
        }
        
        Consumer deletedConsumer = consumerGroup.deleteConsumer(consumer);
        if (deletedConsumer != null) {
            bumpKeyVersion(database, key);
            return deletedConsumer.getPendingCount();
        }
        
        return 0;
    }
    
    /**
     * 设置消费者组最后传递 ID
     */
    @Override
    public boolean xgroupSetId(int database, String key, String group, StreamId id) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return false;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return false;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return false;
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return false;
        }
        
        consumerGroup.setLastDeliveredId(id);
        bumpKeyVersion(database, key);
        
        return true;
    }
    
    /**
     * 读取流消息
     */
    @Override
    public Map<String, List<StreamEntry>> xread(int database, List<String> keys, List<StreamId> ids, int count, long block) {
        Map<String, List<StreamEntry>> result = new LinkedHashMap<>();
        
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            StreamId id = ids.get(i);
            
            StoreValue storeValue = getOrCreateDatabaseStore(database).storage.getIfPresent(key);
            if (storeValue == null || storeValue.isExpired()) {
                continue;
            }
            
            Object val = storeValue.value;
            if (!(val instanceof Stream)) {
                continue;
            }
            
            Stream stream = (Stream) val;
            StreamId effectiveId = id;
            if (effectiveId == null) {
                effectiveId = stream.getLastGeneratedId();
                if (effectiveId == null) {
                    continue;
                }
            }
            
            List<StreamEntry> entries = stream.getRangeFrom(effectiveId, true, count > 0 ? count : Integer.MAX_VALUE);
            if (!entries.isEmpty()) {
                result.put(key, entries);
            }
        }
        
        return result;
    }
    
    /**
     * 消费者组读取消息
     */
    @Override
    public Map<String, List<StreamEntry>> xreadGroup(int database, String key, String group, String consumer,
                                                       StreamId id, int count, boolean noack) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            throw new IllegalStateException("NOGROUP No such key '" + key + "' or consumer group '" + group + "'");
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            throw new IllegalStateException("NOGROUP No such key '" + key + "' or consumer group '" + group + "'");
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            throw new IllegalStateException("NOGROUP No such key '" + key + "' or consumer group '" + group + "'");
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            throw new IllegalStateException("NOGROUP No such key '" + key + "' or consumer group '" + group + "'");
        }
        
        // 确保消费者存在（即使没有消息也要创建消费者）
        consumerGroup.createConsumer(consumer);
        
        List<StreamEntry> entries;
        
        // id 为 null 表示 ">"，读取新消息
        if (id == null) {
            // 从组的 last_delivered_id 之后读取新消息
            StreamId effectiveId = consumerGroup.getLastDeliveredId();
            if (effectiveId == null) {
                effectiveId = StreamId.MIN_ID;
            }
            
            entries = stream.getRangeFrom(effectiveId, true, count);
            
            if (!noack) {
                // 非 NOACK 模式：添加到 PEL
                for (StreamEntry entry : entries) {
                    consumerGroup.addPendingMessage(entry.getId(), consumer);
                }
            }
            
            if (!entries.isEmpty()) {
                consumerGroup.setLastDeliveredId(entries.get(entries.size() - 1).getId());
            }
        } else {
            // 指定 ID：从 PEL 中读取消息
            List<PendingMessage> pendingMessages = consumerGroup.getPendingMessages(id, null, count, null, 0);
            entries = new ArrayList<>();
            for (PendingMessage pm : pendingMessages) {
                StreamEntry entry = stream.getEntry(pm.getId());
                if (entry != null) {
                    entries.add(entry);
                }
            }
        }
        
        bumpKeyVersion(database, key);
        
        Map<String, List<StreamEntry>> result = new HashMap<>();
        result.put(key, entries);
        return result;
    }
    
    /**
     * 确认消息
     */
    @Override
    public long xack(int database, String key, String group, StreamId... ids) {
        if (ids == null || ids.length == 0) {
            return 0;
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return 0;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return 0;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return 0;
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return 0;
        }
        
        long acked = 0;
        for (StreamId id : ids) {
            if (consumerGroup.ackMessage(id) != null) {
                acked++;
            }
        }
        
        if (acked > 0) {
            bumpKeyVersion(database, key);
        }
        
        return acked;
    }
    
    /**
     * 获取待处理消息摘要
     */
    @Override
    public Map<String, Object> xpendingSummary(int database, String key, String group) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyMap();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyMap();
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return Collections.emptyMap();
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return Collections.emptyMap();
        }
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("count", consumerGroup.getPendingCount());
        summary.put("lastDeliveredId", consumerGroup.getLastDeliveredId());
        
        StreamId[] idRange = consumerGroup.getPendingIdRange();
        if (idRange != null) {
            summary.put("startId", idRange[0]);
            summary.put("endId", idRange[1]);
        }
        
        return summary;
    }
    
    /**
     * 获取待处理消息详细列表
     */
    @Override
    public List<Map<String, Object>> xpendingList(int database, String key, String group,
                                                   StreamId start, StreamId end, int count, 
                                                   String consumer, long minIdleTime) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyList();
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return Collections.emptyList();
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return Collections.emptyList();
        }
        
        List<PendingMessage> pendingMessages = 
            consumerGroup.getPendingMessages(start, end, count, consumer, minIdleTime);
        
        List<Map<String, Object>> result = new ArrayList<>(pendingMessages.size());
        for (PendingMessage pm : pendingMessages) {
            Map<String, Object> info = new HashMap<>();
            info.put("id", pm.getId());
            info.put("consumer", pm.getConsumerName());
            info.put("deliveryTime", pm.getDeliveryTime());
            info.put("deliveryCount", pm.getDeliveryCount());
            result.add(info);
        }
        
        return result;
    }
    
    /**
     * 转移消息所有权
     */
    @Override
    public List<StreamEntry> xclaim(int database, String key, String group, String consumer,
                                     long minIdleTime, StreamId[] ids, boolean justId, boolean force) {
        if (ids == null || ids.length == 0) {
            return Collections.emptyList();
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyList();
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return Collections.emptyList();
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return Collections.emptyList();
        }
        
        List<StreamEntry> claimedEntries = new ArrayList<>();
        
        for (StreamId id : ids) {
            PendingMessage pendingMessage = consumerGroup.getPendingMessage(id);
            
            if (pendingMessage == null) {
                if (!force) {
                    continue;
                }
                StreamEntry entry = stream.getEntry(id);
                if (entry == null) {
                    continue;
                }
                consumerGroup.addPendingMessage(id, consumer);
                claimedEntries.add(entry);
                continue;
            }
            
            long idleTime = System.currentTimeMillis() - pendingMessage.getDeliveryTime();
            if (idleTime < minIdleTime) {
                continue;
            }
            
            consumerGroup.claimMessage(id, consumer);
            
            if (justId) {
                // JUSTID: 只返回 ID，创建一个空的 StreamEntry
                claimedEntries.add(new StreamEntry(id, Collections.emptyMap()));
            } else {
                StreamEntry entry = stream.getEntry(id);
                if (entry != null) {
                    claimedEntries.add(entry);
                }
            }
        }
        
        if (!claimedEntries.isEmpty()) {
            bumpKeyVersion(database, key);
        }
        
        return claimedEntries;
    }
    
    /**
     * 自动转移超时消息
     */
    @Override
    public Map<String, Object> xautoclaim(int database, String key, String group, String consumer,
                                          long minIdleTime, StreamId start, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            Map<String, Object> result = new HashMap<>();
            result.put("nextId", StreamId.MIN_ID);
            result.put("entries", Collections.emptyList());
            return result;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            Map<String, Object> result = new HashMap<>();
            result.put("nextId", StreamId.MIN_ID);
            result.put("entries", Collections.emptyList());
            return result;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("nextId", StreamId.MIN_ID);
            result.put("entries", Collections.emptyList());
            return result;
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("nextId", StreamId.MIN_ID);
            result.put("entries", Collections.emptyList());
            return result;
        }
        
        List<StreamEntry> claimedEntries = new ArrayList<>();
        StreamId nextId = start;
        
        List<PendingMessage> pendingMessages = 
            consumerGroup.getPendingMessages(start, StreamId.MAX_ID, count * 2, null, minIdleTime);
        
        for (PendingMessage pm : pendingMessages) {
            if (claimedEntries.size() >= count) {
                nextId = pm.getId();
                break;
            }
            
            consumerGroup.claimMessage(pm.getId(), consumer);
            
            StreamEntry entry = stream.getEntry(pm.getId());
            if (entry != null) {
                claimedEntries.add(entry);
            }
            
            nextId = pm.getId();
        }
        
        if (!claimedEntries.isEmpty()) {
            bumpKeyVersion(database, key);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("nextId", nextId);
        result.put("entries", claimedEntries);
        return result;
    }
    
    /**
     * 获取消费者组管理器
     */
    @Override
    public StreamConsumerGroupManager getStreamConsumerGroupManager(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (val instanceof Stream) {
            Stream stream = (Stream) val;
            return stream.getConsumerGroupManager();
        }
        
        return null;
    }
    
    /**
     * 获取流信息
     */
    @Override
    public Map<String, Object> xinfoStream(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyMap();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyMap();
        }
        
        Stream stream = (Stream) val;
        Map<String, Object> info = new HashMap<>();
        
        info.put("length", stream.getLength());
        info.put("radix-tree-keys", stream.getLength());
        info.put("radix-tree-nodes", stream.getLength());
        info.put("last-generated-id", stream.getLastGeneratedId());
        
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        if (groupManager != null) {
            info.put("groups", groupManager.getGroupCount());
        } else {
            info.put("groups", 0);
        }
        
        StreamEntry firstEntry = stream.getFirstEntry();
        if (firstEntry != null) {
            info.put("first-entry", firstEntry);
        }
        
        StreamEntry lastEntry = stream.getLastEntry();
        if (lastEntry != null) {
            info.put("last-entry", lastEntry);
        }
        
        return info;
    }
    
    /**
     * 获取消费者组列表
     */
    @Override
    public List<Map<String, Object>> xinfoGroups(int database, String key) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyList();
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return Collections.emptyList();
        }
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConsumerGroup group : groupManager.getGroups()) {
            Map<String, Object> groupInfo = new HashMap<>();
            groupInfo.put("name", group.getName());
            groupInfo.put("consumers", group.getConsumerCount());
            groupInfo.put("pending", group.getPendingCount());
            groupInfo.put("last-delivered-id", group.getLastDeliveredId());
            result.add(groupInfo);
        }
        
        return result;
    }
    
    /**
     * 获取消费者列表
     */
    @Override
    public List<Map<String, Object>> xinfoConsumers(int database, String key, String group) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return Collections.emptyList();
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return Collections.emptyList();
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return Collections.emptyList();
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return Collections.emptyList();
        }
        
        List<Map<String, Object>> result = new ArrayList<>();
        for (Consumer consumer : consumerGroup.getConsumers()) {
            Map<String, Object> consumerInfo = new HashMap<>();
            consumerInfo.put("name", consumer.getName());
            consumerInfo.put("pending", consumer.getPendingCount());
            consumerInfo.put("idle", consumer.getIdleTime());
            result.add(consumerInfo);
        }
        
        return result;
    }

    @Override
    public StreamId getGroupLastDeliveredId(int database, String key, String group) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        
        if (storeValue == null || storeValue.isExpired()) {
            return null;
        }
        
        Object val = storeValue.value;
        if (!(val instanceof Stream)) {
            return null;
        }
        
        Stream stream = (Stream) val;
        StreamConsumerGroupManager groupManager = stream.getConsumerGroupManager();
        
        if (groupManager == null) {
            return null;
        }
        
        ConsumerGroup consumerGroup = groupManager.getGroup(group);
        if (consumerGroup == null) {
            return null;
        }
        
        return consumerGroup.getLastDeliveredId();
    }
    
    // ==================== 槽位操作实现 ====================
    
    @Override
    public List<String> getKeysInSlot(int database, int slot, int count) {
        if (!SlotUtils.isValidSlot(slot)) {
            throw new IllegalArgumentException("Invalid slot number: " + slot);
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        Set<String> keysInSlot = store.slotToKeys.get(slot);
        
        if (keysInSlot == null || keysInSlot.isEmpty()) {
            return Collections.emptyList();
        }
        
        List<String> result = new ArrayList<>();
        int added = 0;
        for (String key : keysInSlot) {
            if (added >= count) {
                break;
            }
            // 验证键是否仍然存在（可能已被删除但索引尚未清理）
            if (store.storage.getIfPresent(key) != null) {
                result.add(key);
                added++;
            }
        }
        
        return result;
    }
    
    @Override
    public int countKeysInSlot(int database, int slot) {
        if (!SlotUtils.isValidSlot(slot)) {
            return 0;
        }
        
        DatabaseStore store = getOrCreateDatabaseStore(database);
        Set<String> keysInSlot = store.slotToKeys.get(slot);
        
        if (keysInSlot == null || keysInSlot.isEmpty()) {
            return 0;
        }
        
        // 统计实际存在的键数量（排除已过期或被删除的键）
        int count = 0;
        for (String key : keysInSlot) {
            StoreValue storeValue = store.storage.getIfPresent(key);
            if (storeValue != null && !storeValue.isExpired()) {
                count++;
            }
        }
        
        return count;
    }
    
    @Override
    public int getKeySlot(String key) {
        return SlotUtils.getSlot(key);
    }
}
