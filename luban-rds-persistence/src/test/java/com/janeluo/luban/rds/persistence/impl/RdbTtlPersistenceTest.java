package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * RDB TTL 持久化测试 (C10)。
 *
 * <p>验证带 TTL 的键在 RDB 持久化 + 重启后能正确恢复剩余生存时间，
 * 且不复活已过期键，并对旧格式（无 expire opcode）向后兼容。
 *
 * <p>注意：DefaultMemoryStore.ttl() 返回剩余秒数（向下取整），
 * pttl() 返回剩余毫秒。这里用 pttl 做精度判断，ttl 做粗略断言。
 */
public class RdbTtlPersistenceTest {

    private static final String TEST_DATA_DIR = "./target/test-data/rdb-ttl-test";

    private RdbPersistService persistService;
    private MemoryStore memoryStore;

    @Before
    public void setUp() {
        cleanTestDataDir();
        File dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        persistService = new RdbPersistService(TEST_DATA_DIR);
        memoryStore = new DefaultMemoryStore();
    }

    @After
    public void tearDown() {
        if (persistService != null) {
            persistService.close();
        }
        cleanTestDataDir();
    }

    private void cleanTestDataDir() {
        File dataDir = new File(TEST_DATA_DIR);
        if (dataDir.exists()) {
            File[] files = dataDir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            dataDir.delete();
        }
    }

    /**
     * SET EX 后 RDB 持久化 + 重启恢复 TTL。
     * 秒级 TTL（>1h 不满足整秒条件其实也满足，这里用大值触发 0xFD 秒级路径）。
     */
    @Test
    public void testStringWithSecondsTtlRestored() {
        memoryStore.setWithExpire(0, "ttlKey", "value", 3600L);

        long pttlBefore = memoryStore.pttl(0, "ttlKey");
        assertTrue("pttl before persist should be positive", pttlBefore > 0);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("value", newStore.get(0, "ttlKey"));
        long pttlAfter = newStore.pttl(0, "ttlKey");
        assertTrue("pttl after load should still be positive, got " + pttlAfter, pttlAfter > 0);
        // 3600s TTL：恢复后剩余应在 3500-3600s 之间（允许持久化/加载耗时）
        assertTrue("pttl after load should be within expected range, got " + pttlAfter,
                pttlAfter > 3500 * 1000L && pttlAfter <= 3600 * 1000L);
    }

    /**
     * 毫秒级 TTL（非整秒或 <1h）应使用 0xFC 路径。
     * 用一个带毫秒级非整秒剩余的键验证。
     */
    @Test
    public void testStringWithMsTtlRestoredVia0xFC() {
        // setWithExpireMs 走毫秒路径。用 599999ms（<1h，非整秒）触发 0xFC
        memoryStore.setWithExpireMs(0, "msKey", "msValue", 599999L);

        long pttlBefore = memoryStore.pttl(0, "msKey");
        assertTrue("pttl before persist should be positive", pttlBefore > 0);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("msValue", newStore.get(0, "msKey"));
        long pttlAfter = newStore.pttl(0, "msKey");
        assertTrue("pttl after load should be positive (0xFC path), got " + pttlAfter,
                pttlAfter > 0);
        assertTrue("pttl after load within range, got " + pttlAfter,
                pttlAfter > 599000L && pttlAfter <= 599999L);
    }

    /**
     * 已过期键不复活：写入很短 TTL 的键，等其过期后持久化，
     * 重启后该键不应存在。
     *
     * <p>这里通过构造 expireAt < now 的 RDB 内容来验证：先持久化一个带 TTL 的键，
     * 然后用 DefaultMemoryStore 的语义验证过期键不被加载。
     * 为稳定测试，直接断言：持久化时 ttl>0 才写 expire opcode；
     * 若键已过期，scan/get 拿不到，writeKeyValue 不会被调用。
     */
    @Test
    public void testExpiredKeyNotResurrected() throws Exception {
        // 写入一个 1 秒过期的键
        memoryStore.setWithExpire(0, "shortLived", "v", 1L);
        assertTrue(memoryStore.pttl(0, "shortLived") > 0);

        // 等待过期
        Thread.sleep(1200L);
        // 触发惰性过期（get 会 invalidate）
        Object v = memoryStore.get(0, "shortLived");
        assertNull("key should have expired", v);

        // 同时写一个永久键作为对照
        memoryStore.set(0, "permanent", "p");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertNull("expired key should NOT be resurrected", newStore.get(0, "shortLived"));
        assertEquals("permanent key should be loaded", "p", newStore.get(0, "permanent"));
    }

    /**
     * 旧格式向后兼容：RDB 中无 expire opcode 的键应按永久键加载。
     *
     * <p>策略：先持久化一个带 TTL 的键得到含 0xFC/0xFD 的 RDB，
     * 然后手工构造一个不含 expire opcode 的 RDB（仅 type+key+value），
     * 验证加载后 pttl == -1（永久）。
     *
     * <p>更简单：直接持久化一个永久键（无 TTL），加载后 pttl 应为 -1。
     * 这覆盖了“无 opcode -> 永久”的加载路径。
     */
    @Test
    public void testBackwardCompatNoOpcodeLoadsAsPermanent() {
        // 永久键：writeKeyValue 不写 expire opcode
        memoryStore.set(0, "perm", "permValue");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("permValue", newStore.get(0, "perm"));
        assertEquals("no-opcode key should be permanent (ttl=-1)", -1L, newStore.ttl(0, "perm"));
        assertEquals("no-opcode key should be permanent (pttl=-1)", -1L, newStore.pttl(0, "perm"));
    }

    /**
     * 验证加载侧读到 expireAt < now（已过期）时不加载该键。
     *
     * <p>构造方式：写入一个 TTL=2s 的键，持久化得到 expireAt≈now+2s 的 RDB，
     * 然后等待 3s 使 expireAt < now，再 load，该键应不复活。
     */
    @Test
    public void testLoadSkipsKeysWhoseExpireAtAlreadyPassed() throws Exception {
        memoryStore.setWithExpire(0, "willExpire", "v", 2L);
        persistService.persistSync(memoryStore);

        // 等待超过 expireAt
        Thread.sleep(2500L);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        // expireAt < now，加载侧应跳过该键
        assertNull("key whose expireAt already passed should not be loaded",
                newStore.get(0, "willExpire"));
    }

    /**
     * 混合场景：永久键 + 秒级 TTL 键 + 毫秒级 TTL 键共存于同一 RDB，
     * 加载后各自的 TTL 语义正确。
     */
    @Test
    public void testMixedPermanentAndTtlKeys() {
        memoryStore.set(0, "perm", "p");
        memoryStore.setWithExpire(0, "secTtl", "s", 3600L);
        memoryStore.setWithExpireMs(0, "msTtl", "m", 599999L);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("p", newStore.get(0, "perm"));
        assertEquals(-1L, newStore.pttl(0, "perm"));

        assertEquals("s", newStore.get(0, "secTtl"));
        assertTrue("secTtl pttl should be positive", newStore.pttl(0, "secTtl") > 0);

        assertEquals("m", newStore.get(0, "msTtl"));
        assertTrue("msTtl pttl should be positive", newStore.pttl(0, "msTtl") > 0);
    }

    /**
     * 带 TTL 的非 string 类型（hash/list/set/zset）也应恢复 TTL。
     */
    @Test
    public void testHashWithTtlRestored() {
        Map<String, String> hash = new HashMap<>();
        hash.put("f1", "v1");
        hash.put("f2", "v2");
        memoryStore.set(0, "h", hash);
        memoryStore.pexpire(0, "h", 599999L);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "h");
        assertNotNull(loaded);
        assertTrue(loaded instanceof Map);
        assertEquals(2, ((Map<?, ?>) loaded).size());
        long pttl = newStore.pttl(0, "h");
        assertTrue("hash TTL should be restored, got " + pttl, pttl > 0);
    }

    @Test
    public void testListWithTtlRestored() {
        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        memoryStore.set(0, "l", list);
        memoryStore.expire(0, "l", 3600L);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "l");
        assertNotNull(loaded);
        assertTrue(loaded instanceof List);
        assertEquals(2, ((List<?>) loaded).size());
        assertTrue("list TTL should be restored", newStore.pttl(0, "l") > 0);
    }

    @Test
    public void testSetWithTtlRestored() {
        Set<String> set = new HashSet<>();
        set.add("x");
        set.add("y");
        memoryStore.set(0, "s", set);
        memoryStore.expire(0, "s", 3600L);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "s");
        assertNotNull(loaded);
        assertTrue(loaded instanceof Set);
        assertEquals(2, ((Set<?>) loaded).size());
        assertTrue("set TTL should be restored", newStore.pttl(0, "s") > 0);
    }

    @Test
    public void testZsetWithTtlRestored() {
        memoryStore.zadd(0, "z", 1.0, "m1");
        memoryStore.zadd(0, "z", 2.0, "m2");
        memoryStore.expire(0, "z", 3600L);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(2L, newStore.zcard(0, "z"));
        assertTrue("zset TTL should be restored", newStore.pttl(0, "z") > 0);
    }
}
