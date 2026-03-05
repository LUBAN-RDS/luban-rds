package com.janeluo.luban.rds.core.store;

import com.janeluo.luban.rds.common.constant.RdsDataTypeConstant;
import com.janeluo.luban.rds.common.util.RdsUtil;
import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
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
    
    private static class StoreValue {
        private final Object value;
        private final String type;
        private Long expireTime;
        private long lastAccessTime; // 最后访问时间，用于LRU
        private long estimatedSize; // 估算的内存大小
        
        public StoreValue(Object value, String type) {
            this.value = value;
            this.type = type;
            this.lastAccessTime = System.currentTimeMillis();
            this.estimatedSize = estimateSize(value);
        }
        
        public StoreValue(Object value, String type, long expireSeconds) {
            this.value = value;
            this.type = type;
            this.expireTime = RdsUtil.currentSeconds() + expireSeconds;
            this.lastAccessTime = System.currentTimeMillis();
            this.estimatedSize = estimateSize(value);
        }
        
        public void updateEstimatedSize(long delta) {
            this.estimatedSize += delta;
        }
        
        public boolean isExpired() {
            return expireTime != null && RdsUtil.currentSeconds() >= expireTime;
        }
        
        public void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }
        
        public long getLastAccessTime() {
            return lastAccessTime;
        }
        
        public boolean hasExpireTime() {
            return expireTime != null;
        }
        
        public Long getExpireTime() {
            return expireTime;
        }
        
        public long getEstimatedSize() {
            return estimatedSize;
        }
        
        /**
         * 估算值的内存大小
         */
        private static long estimateSize(Object value) {
            if (value == null) {
                return BASE_ENTRY_OVERHEAD;
            }
            
            long size = BASE_ENTRY_OVERHEAD;
            
            if (value instanceof String) {
                // 字符串：每个字符2字节（Java char）
                size += ((String) value).length() * 2L;
            } else if (value instanceof Map) {
                // Map：估算每个条目的大小
                Map<?, ?> map = (Map<?, ?>) value;
                size += map.size() * 64L; // 每个条目估算64字节
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() instanceof String) {
                        size += ((String) entry.getKey()).length() * 2L;
                    }
                    if (entry.getValue() instanceof String) {
                        size += ((String) entry.getValue()).length() * 2L;
                    }
                }
            } else if (value instanceof java.util.Collection) {
                // 集合类型
                java.util.Collection<?> collection = (java.util.Collection<?>) value;
                size += collection.size() * 32L; // 每个元素估算32字节
                for (Object item : collection) {
                    if (item instanceof String) {
                        size += ((String) item).length() * 2L;
                    }
                }
            } else if (value instanceof ZSetStore) {
                ZSetStore zset = (ZSetStore) value;
                size += zset.size() * 128L; // 每个元素估算128字节 (HashMap entry + SkipList entry)
                for (String member : zset.memberScores.keySet()) {
                    size += member.length() * 2L;
                }
            } else {
                // 其他类型，估算一个固定值
                size += 64;
            }
            
            return size;
        }
    }
    
    // 每个数据库的存储结构
    private static class DatabaseStore {
        final Cache<String, StoreValue> storage;
        final ConcurrentHashMap<String, Boolean> keySet; // 用于跟踪所有键，支持SCAN命令
        final ConcurrentHashMap<String, AtomicLong> keyVersions; // 键版本，用于WATCH
        
        public DatabaseStore() {
            this.keySet = new ConcurrentHashMap<>();
            this.keyVersions = new ConcurrentHashMap<>();
            this.storage = Caffeine.newBuilder()
                    .removalListener(new RemovalListener<String, StoreValue>() {
                        @Override
                        public void onRemoval(String key, StoreValue value, RemovalCause cause) {
                            // 当键被移除时，从keySet中也移除
                            keySet.remove(key);
                        }
                    })
                    .build();
        }
    }
    
    // 数据库存储管理
    private final ConcurrentHashMap<Integer, DatabaseStore> databaseStores = new ConcurrentHashMap<>();
    
    // 数据库数量限制
    private int maxDatabases = 16;
    
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
        // 初始化默认数据库（0号数据库）
        getOrCreateDatabaseStore(0);
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
        // 初始化默认数据库（0号数据库）
        getOrCreateDatabaseStore(0);
        
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
                logger.warn("LRU淘汰失败：没有可淘汰的键");
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
        
        // 收集所有数据库的键
        List<Object[]> allKeys = new ArrayList<>(); // [database, key]
        for (Map.Entry<Integer, DatabaseStore> dbEntry : databaseStores.entrySet()) {
            DatabaseStore store = dbEntry.getValue();
            for (String key : store.keySet.keySet()) {
                allKeys.add(new Object[]{dbEntry.getKey(), key});
            }
        }
        
        if (allKeys.isEmpty()) {
            return;
        }
        
        // 随机采样
        int sampleCount = Math.min(lruSampleSize, allKeys.size());
        for (int i = 0; i < sampleCount; i++) {
            int idx = random.nextInt(allKeys.size());
            Object[] entry = allKeys.get(idx);
            int database = (Integer) entry[0];
            String key = (String) entry[1];
            
            DatabaseStore store = databaseStores.get(database);
            if (store == null) continue;
            
            StoreValue value = store.storage.getIfPresent(key);
            if (value == null) continue;
            
            // 检查 volatile 条件
            if (volatileOnly && !value.hasExpireTime()) {
                continue;
            }
            
            // 计算空闲时间
            long idleTime = currentTime - value.getLastAccessTime();
            
            // 尝试加入候选池
            LruPoolEntry poolEntry = new LruPoolEntry(database, key, idleTime);
            
            if (lruPool.size() < LRU_POOL_SIZE) {
                // 池未满，直接加入
                lruPool.add(poolEntry);
            } else {
                // 池已满，检查是否比池中最小空闲时间的键更适合淘汰
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
                logger.warn("随机淘汰失败：没有可淘汰的键");
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
                logger.warn("TTL淘汰失败：没有设置过期时间的键");
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
            // 删除过期键并更新内存统计
            long freedMemory = storeValue.getEstimatedSize();
            store.storage.invalidate(key);
            store.keySet.remove(key);
            updateMemory(-freedMemory);
            RuntimeConfig.incKeyspaceMisses();
            return null;
        }
        
        // 更新访问时间（用于LRU）
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
        updateMemory(requiredSize);
        bumpKeyVersion(database, key);
    }
    
    @Override
    public void setWithExpire(int database, String key, Object value, long expireSeconds) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        String type = getType(value);
        StoreValue newValue = new StoreValue(value, type, expireSeconds);
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
        updateMemory(requiredSize);
        bumpKeyVersion(database, key);
    }

    @Override
    public void mset(int database, String... keysAndValues) {
        if (keysAndValues == null || keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("wrong number of arguments for MSET");
        }
        // 循环设置
        for (int i = 0; i < keysAndValues.length; i += 2) {
            String key = keysAndValues[i];
            String value = keysAndValues[i+1];
            set(database, key, value);
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
        DatabaseStore store = getOrCreateDatabaseStore(database);
        StoreValue storeValue = store.storage.getIfPresent(key);
        if (storeValue == null) {
            return false;
        }
        
        // 更新过期时间
        StoreValue newStoreValue = new StoreValue(storeValue.value, storeValue.type, seconds);
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
        
        if (storeValue.expireTime == null) {
            RuntimeConfig.incKeyspaceHits();
            return -1;
        }
        
        RuntimeConfig.incKeyspaceHits();
        long remaining = storeValue.expireTime - RdsUtil.currentSeconds();
        return remaining > 0 ? remaining : -2;
    }
    
    @Override
    public void flushAll() {
        // 清空所有数据库并重置内存统计
        for (DatabaseStore store : databaseStores.values()) {
            store.storage.invalidateAll();
            store.keySet.clear();
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
        return storeValue.type;
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
        return RdsDataTypeConstant.STRING;
    }
    
    @Override
    public java.util.List<Object> scan(int database, long cursor, String pattern, int count) {
        DatabaseStore store = getOrCreateDatabaseStore(database);
        java.util.List<Object> result = new java.util.ArrayList<>();
        
        // ��认为0
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
        
        if (storeValue == null || storeValue.isExpired()) {
            return false;
        }
        
        Object val = storeValue.value;
        if (val instanceof java.util.Map) {
            return ((java.util.Map<?, ?>) val).containsKey(field);
        }
        
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
                long remaining = Math.max(0, storeValue.getExpireTime() - RdsUtil.currentSeconds());
                newValue = new StoreValue(newList, RdsDataTypeConstant.LIST, remaining);
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
    
    // ==================== ZSet 操作优化实现 ====================
    
    /**
     * ZSet 内部存储结构
     * 使用 ConcurrentSkipListMap 保持按分数排序
     */
    private static class ZSetStore {
        // member -> score 映射，用于快速查找分数
        final java.util.concurrent.ConcurrentHashMap<String, Double> memberScores = 
                new java.util.concurrent.ConcurrentHashMap<>();
        // score -> members 映射，用于按分数排序
        final java.util.concurrent.ConcurrentSkipListMap<Double, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>> scoreMembers = 
                new java.util.concurrent.ConcurrentSkipListMap<>();
        
        int add(String member, double score) {
            Double oldScore = memberScores.put(member, score);
            
            // 如果是更新，先从旧分数中移除
            if (oldScore != null) {
                java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> oldSet = scoreMembers.get(oldScore);
                if (oldSet != null) {
                    oldSet.remove(member);
                    if (oldSet.isEmpty()) {
                        scoreMembers.remove(oldScore);
                    }
                }
            }
            
            // 添加到新分数
            scoreMembers.computeIfAbsent(score, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(member);
            
            return oldScore == null ? 1 : 0;
        }
        
        long[] remove(String... members) {
            long removedCount = 0;
            long removedBytes = 0;
            for (String member : members) {
                Double score = memberScores.remove(member);
                if (score != null) {
                    java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean> set = scoreMembers.get(score);
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
            for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>> entry : scoreMembers.entrySet()) {
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
            java.util.NavigableMap<Double, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>> subMap = 
                    scoreMembers.subMap(min, true, max, true);
            
            java.util.List<String> result = new java.util.ArrayList<>();
            int skipped = 0;
            
            for (java.util.Map.Entry<Double, java.util.concurrent.ConcurrentHashMap.KeySetView<String, Boolean>> entry : subMap.entrySet()) {
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
}
