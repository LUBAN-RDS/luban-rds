package com.janeluo.luban.rds.core.store;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 堆外 string 存储引擎：len >= threshold 的 string 进堆外 ByteBuf。
 * 单引擎实例承载所有 database（通过 ConcurrentHashMap<Integer, ConcurrentMap<String,OffHeapEntry>>）。
 *
 * 所有权（DD-2 R1）：引擎持有所有 ByteBuf 唯一所有权，refCnt 在引擎内恒为 1。
 * 所有 release 必须经 releaseEntry() 私有方法（单点）。
 * get 拷贝到堆上 byte[]/String 返回，不转移所有权。
 */
public class OffHeapStringEngine implements StoreEngine {

    public static final String ENGINE_ID = "offheap";

    private final int threshold;
    private final PooledByteBufAllocator allocator = PooledByteBufAllocator.DEFAULT;
    private final ConcurrentMap<Integer, ConcurrentMap<String, OffHeapEntry>> dbs = new ConcurrentHashMap<>();

    // 内存计量：堆外实际占用（ByteBuf.capacity 之和，含 allocator 对齐）
    private final java.util.concurrent.atomic.AtomicLong offheapUsed = new java.util.concurrent.atomic.AtomicLong(0);

    public OffHeapStringEngine(int threshold) {
        this.threshold = threshold;
    }

    // ========== string 操作（仅处理 >= threshold 的大 value）==========

    private ConcurrentMap<String, OffHeapEntry> db(int database) {
        return dbs.computeIfAbsent(database, k -> new ConcurrentHashMap<>());
    }

    public void set(int database, String key, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < threshold) {
            return; // 小 value 路由层走 onheap，此处不存
        }
        setBytes(database, key, bytes, 0L);
    }

    public void setWithExpire(int database, String key, String value, long expireTimeMs) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < threshold) return;
        setBytes(database, key, bytes, expireTimeMs);
    }

    private void setBytes(int database, String key, byte[] bytes, long expireTimeMs) {
        ConcurrentMap<String, OffHeapEntry> map = db(database);
        OffHeapEntry old = map.get(key);
        // 分配 + 写入
        ByteBuf buf = allocator.directBuffer(bytes.length);
        buf.writeBytes(bytes);
        long now = System.currentTimeMillis();
        OffHeapEntry entry = new OffHeapEntry(buf, bytes.length, expireTimeMs, now);
        OffHeapEntry prev = map.put(key, entry);
        // release 旧值（单点 R1）
        long added = buf.capacity();
        if (prev != null) {
            added -= releaseEntry(prev);
        }
        if (added != 0) {
            offheapUsed.addAndGet(added);
        }
    }

    public String get(int database, String key) {
        OffHeapEntry entry = db(database).get(key);
        if (entry == null) return null;
        if (entry.isExpired()) {
            del(database, key); // 懒过期
            return null;
        }
        entry.updateAccessTime();
        // DD-2 R1：拷贝到堆上，不转移 ByteBuf 所有权
        byte[] copy = new byte[entry.getLen()];
        entry.getBuffer().getBytes(0, copy);
        return new String(copy, StandardCharsets.UTF_8);
    }

    public boolean del(int database, String key) {
        OffHeapEntry entry = db(database).remove(key);
        if (entry == null) return false;
        releaseEntry(entry);
        return true;
    }

    public boolean exists(int database, String key) {
        OffHeapEntry entry = db(database).get(key);
        if (entry == null) return false;
        if (entry.isExpired()) { del(database, key); return false; }
        return true;
    }

    public String type(int database, String key) {
        return exists(database, key) ? "string" : "none";
    }

    public long ttlMs(int database, String key) {
        OffHeapEntry entry = db(database).get(key);
        if (entry == null) return -2L;
        if (entry.isExpired()) { del(database, key); return -2L; }
        if (!entry.hasExpireTime()) return -1L;
        return entry.getExpireTime() - System.currentTimeMillis();
    }

    public boolean expire(int database, String key, long expireTimeMs) {
        OffHeapEntry entry = db(database).get(key);
        if (entry == null || entry.isExpired()) {
            if (entry != null) del(database, key);
            return false;
        }
        entry.setExpireTime(expireTimeMs);
        return true;
    }

    // ========== DD-2 release 单点化：所有释放经此方法 ==========

    /**
     * release 一个 entry 的 ByteBuf。返回释放的字节数（buffer.capacity）。
     */
    private long releaseEntry(OffHeapEntry entry) {
        if (entry == null) return 0L;
        ByteBuf buf = entry.getBuffer();
        long cap = buf.capacity();
        int rc = buf.refCnt();
        if (rc > 0) {
            buf.release(rc); // 归零，归还 pool
        }
        offheapUsed.addAndGet(-cap);
        return cap;
    }

    // ========== StoreEngine 实现（T2.2/T2.3 补全淘汰/过期；此处先占位）==========

    @Override public String engineId() { return ENGINE_ID; }

    @Override
    public java.util.List<EvictionCandidate> sampleForEviction(int database, String policy, int n) {
        if (n <= 0) return java.util.Collections.emptyList();
        ConcurrentMap<String, OffHeapEntry> map = db(database);
        if (map.isEmpty()) return java.util.Collections.emptyList();

        boolean volatileOnly = DefaultMemoryStore.POLICY_VOLATILE_LRU.equals(policy)
                || DefaultMemoryStore.POLICY_VOLATILE_RANDOM.equals(policy)
                || DefaultMemoryStore.POLICY_VOLATILE_TTL.equals(policy);

        java.util.List<String> keys = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.shuffle(keys);
        java.util.List<EvictionCandidate> result = new java.util.ArrayList<>();
        int picked = 0;
        for (String k : keys) {
            if (picked >= n) break;
            OffHeapEntry e = map.get(k);
            if (e == null || e.isExpired()) continue;
            if (volatileOnly && !e.hasExpireTime()) continue;
            result.add(new EvictionCandidate(ENGINE_ID, database, k, e.getLastAccessTime(), e.getExpireTime()));
            picked++;
        }
        return result;
    }

    @Override
    public long evict(int database, String key) {
        OffHeapEntry entry = db(database).remove(key);
        return releaseEntry(entry);
    }

    @Override
    public int expireBatch(int database, int budget) {
        if (budget <= 0) return 0;
        ConcurrentMap<String, OffHeapEntry> map = db(database);
        if (map.isEmpty()) return 0;
        java.util.List<String> keys = new java.util.ArrayList<>(map.keySet());
        java.util.Collections.shuffle(keys);
        int removed = 0;
        for (String k : keys) {
            if (removed >= budget) break;
            OffHeapEntry e = map.get(k);
            if (e != null && e.isExpired()) {
                del(database, k);
                removed++;
            }
        }
        return removed;
    }

    @Override
    public int size(int database) {
        return db(database).size();
    }

    public long estimateUsedMemory() { return offheapUsed.get(); }

    // ========== scan / 碎片整理（供 HybridMemoryStore 聚合）==========

    /**
     * 按模式扫描 db 中的堆外 string key（返回 key 列表，不含 value）。
     * pattern 支持 glob 的 * 和 ?。null / "*" 表示全部。
     */
    public java.util.List<String> scanKeys(int database, String pattern) {
        ConcurrentMap<String, OffHeapEntry> map = dbs.get(database);
        if (map == null) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> r = new java.util.ArrayList<>();
        for (String k : map.keySet()) {
            if (pattern == null || "*".equals(pattern) || matchGlob(k, pattern)) {
                r.add(k);
            }
        }
        return r;
    }

    /** 极简 glob：* → .*，? → .。其余字符按字面（regex 转义）。 */
    private boolean matchGlob(String key, String pattern) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '*': regex.append(".*"); break;
                case '?': regex.append('.'); break;
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\':
                    regex.append('\\').append(c); break;
                default: regex.append(c);
            }
        }
        return key.matches(regex.toString());
    }

    /**
     * 堆外无碎片概念（pool 管理对齐）；本方法只清理过期 entry。
     * 返回 0（释放的内存已计入 estimateUsedMemory 的衰减，不再额外统计）。
     */
    public long defragment() {
        for (Integer db : dbs.keySet()) {
            expireBatch(db, Integer.MAX_VALUE);
        }
        return 0L;
    }

    // ========== flush / close ==========

    public void flushdb(int database) {
        ConcurrentMap<String, OffHeapEntry> map = dbs.remove(database);
        if (map != null) {
            for (OffHeapEntry e : map.values()) releaseEntry(e);
        }
    }

    public void flushAll() {
        for (Integer db : dbs.keySet()) flushdb(db);
    }

    public void close() {
        flushAll();
        // 断言：所有 ByteBuf 已 release
        // ADVANCED leak detection 会在日志报告泄漏
    }
}
