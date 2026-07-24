package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 验证 DefaultMemoryStore 的 keySet 与 storage 在 pexpire/lrem/del/expire 后保持一致。
 * <p>
 * 根因：Caffeine removalListener 在 RemovalCause.REPLACED 时误删 keySet，
 * 导致 pexpire/lrem 的 storage.put 覆盖后 keySet 与 storage 不一致。
 * 修复后 removalListener 忽略 REPLACED，keySet 与 storage 保持同步。
 * </p>
 */
public class MemoryStoreKeySetConsistencyTest {

    private MemoryStore memoryStore;

    @Before
    public void setUp() {
        memoryStore = new DefaultMemoryStore();
    }

    /**
     * 辅助：判断 scan 结果是否包含指定 key。
     */
    private boolean scanContains(String key) {
        List<Object> result = memoryStore.scan(0, 0, "*", 1000);
        for (int i = 1; i < result.size(); i++) {
            if (key.equals(result.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * pexpire 后 keySet 仍包含该 key（dbsize/scan/exists 一致）。
     */
    @Test
    public void testKeySetConsistentAfterPexpire() {
        String key = "test:hash:attr:{id1}";
        memoryStore.hset(0, key, "f1", "v1");
        assertEquals("dbsize should be 1 after hset", 1, memoryStore.dbsize(0));

        boolean ok = memoryStore.pexpire(0, key, 60000L);
        assertTrue("pexpire should succeed", ok);

        // 关键断言：pexpire 后 keySet 不应丢失该 key
        assertEquals("dbsize should still be 1 after pexpire (keySet consistent)",
                1, memoryStore.dbsize(0));
        assertTrue("scan should still include key after pexpire", scanContains(key));
        assertTrue("exists should be true after pexpire", memoryStore.exists(0, key));
    }

    /**
     * lrem 后 keySet 仍包含该 list key（dbsize/scan/exists 一致）。
     */
    @Test
    public void testKeySetConsistentAfterLrem() {
        String key = "test:list:{id2}";
        memoryStore.lpush(0, key, "a", "b", "c");
        assertEquals("dbsize should be 1 after lpush", 1, memoryStore.dbsize(0));

        memoryStore.lrem(0, key, 1, "b");

        assertEquals("dbsize should still be 1 after lrem (keySet consistent)",
                1, memoryStore.dbsize(0));
        assertTrue("scan should still include key after lrem", scanContains(key));
        assertTrue("exists should be true after lrem", memoryStore.exists(0, key));
    }

    /**
     * 显式 del 后 keySet 正确移除该 key。
     */
    @Test
    public void testKeySetRemovedOnExplicitInvalidate() {
        String key = "test:string:{id3}";
        memoryStore.set(0, key, "value");
        assertEquals("dbsize should be 1 after set", 1, memoryStore.dbsize(0));

        boolean deleted = memoryStore.del(0, key);
        assertTrue("del should succeed", deleted);

        assertEquals("dbsize should be 0 after del", 0, memoryStore.dbsize(0));
        assertFalse("scan should not include key after del", scanContains(key));
        assertFalse("exists should be false after del", memoryStore.exists(0, key));
    }

    /**
     * 过期后 keySet 经惰性清理正确移除该 key。
     * 本项目 StoreValue 自管 expireTime，由读路径（get/scan/dbsize）惰性清理。
     */
    @Test
    public void testKeySetRemovedOnExpire() {
        String key = "test:string:{id4}";
        memoryStore.set(0, key, "value");
        // 设置较长过期时间，确保 dbsize 检查时未过期
        memoryStore.pexpire(0, key, 5000L);
        assertEquals("dbsize should be 1 before expire", 1, memoryStore.dbsize(0));
        assertTrue("key should exist before expire", memoryStore.exists(0, key));

        // 用 pexpire 设置为极短过期时间（1ms），然后等待过期
        memoryStore.pexpire(0, key, 1L);
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 触发惰性清理（get 发现过期 -> invalidate -> removalListener EXPLICIT -> keySet.remove）
        Object val = memoryStore.get(0, key);
        assertEquals("get should return null for expired key", null, val);

        assertEquals("dbsize should be 0 after expired key lazily cleaned",
                0, memoryStore.dbsize(0));
        assertFalse("scan should not include expired key after cleanup", scanContains(key));
    }

    /**
     * 多次 pexpire 刷新过期时间后 keySet 仍保持一致。
     * 模拟日志中 session:attr 被反复 pexpire 刷新的场景。
     */
    @Test
    public void testKeySetConsistentAfterMultiplePexpire() {
        String key = "dpl-master:session:attr:{b338b6dd-multi}";
        memoryStore.hset(0, key, "field", "value");

        // 模拟日志中反复 pexpire 刷新（每次都覆盖 storage）
        for (int i = 0; i < 5; i++) {
            memoryStore.pexpire(0, key, 180000000L);
        }

        assertEquals("dbsize should still be 1 after multiple pexpire",
                1, memoryStore.dbsize(0));
        assertTrue("scan should include key after multiple pexpire", scanContains(key));
        assertTrue("exists should be true", memoryStore.exists(0, key));

        // 中间穿插一次 hset（isNew=false 路径），再 pexpire
        memoryStore.hset(0, key, "field2", "value2");
        memoryStore.pexpire(0, key, 180000000L);

        assertEquals("dbsize should still be 1 after hset+pexpire mix",
                1, memoryStore.dbsize(0));
        assertTrue("scan should include key after mix", scanContains(key));
    }
}
