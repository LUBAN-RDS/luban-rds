package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
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
     * 直接验证 0xFD（秒级）写入+读取 round-trip。
     *
     * <p>背景：{@code setWithExpire} / {@code setWithExpireMs} 在调用 {@code writeExpireTime}
     * 时，由于从 set 到 persist 之间总有毫秒级耗时，{@code pttl % 1000 != 0}，导致整秒分支
     * 永远走不到，0xFD 写入路径与 {@code readExpireTimeSec} 读取路径均无测试覆盖。
     *
     * <p>本测试分两步：
     * <ol>
     *   <li>反射调用 private {@code writeExpireTime(dos, pttl)} 传入整秒 pttl，
     *       断言写出的字节为 {@code 0xFD} + 4 字节小端秒级时间戳。</li>
     *   <li>手工构造一份含字面量 {@code 0xFD} opcode + 4 字节小端秒级时间戳的完整 RDB
     *       字节流（header + SELECTDB + expire opcode + type+key+value + EOF footer），
     *       调用 {@code load()} 后断言键存在且 TTL 落在整秒窗口内，覆盖读取侧
     *       {@code readExpireTimeSec} 路径。</li>
     * </ol>
     */
    @Test
    public void testSecondsLevelOpcode0xFD_RoundTrip() throws Exception {
        // ---- Step 1: 反射调用 writeExpireTime 验证 0xFD 写入字节布局 ----
        // 整秒 pttl（< 1h）应触发 0xFD 秒级路径
        long wholeSecPttl = 60_000L; // 60s，整秒且 < 1h
        long nowBeforeWrite = System.currentTimeMillis();
        long expectedExpireAt = nowBeforeWrite + wholeSecPttl;
        int expectedExpireSec = (int) (expectedExpireAt / 1000L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(baos)) {
            Method writeExpireTime = RdbPersistService.class.getDeclaredMethod(
                    "writeExpireTime", DataOutputStream.class, long.class);
            writeExpireTime.setAccessible(true);
            writeExpireTime.invoke(persistService, dos, wholeSecPttl);
        }

        byte[] expireBytes = baos.toByteArray();
        assertEquals("0xFD write path should emit 5 bytes (1 opcode + 4 LE seconds)",
                5, expireBytes.length);
        assertEquals("first byte should be 0xFD seconds opcode",
                (byte) 0xFD, expireBytes[0]);
        // 4 字节小端秒级时间戳
        int leSec = (expireBytes[1] & 0xFF)
                | ((expireBytes[2] & 0xFF) << 8)
                | ((expireBytes[3] & 0xFF) << 16)
                | ((expireBytes[4] & 0xFF) << 24);
        assertTrue("LE seconds should match expectedExpireSec (got " + leSec
                        + ", expected ~" + expectedExpireSec + ")",
                leSec == expectedExpireSec || leSec == expectedExpireSec + 1);

        // ---- Step 2: 构造完整 RDB 字节流验证 0xFD 读取路径 ----
        // 目标 expireAt：now + 120s，确保 load 时 remaining > 0
        long targetExpireAtMs = System.currentTimeMillis() + 120_000L;
        int targetExpireSec = (int) (targetExpireAtMs / 1000L);

        ByteArrayOutputStream rdbBytes = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(rdbBytes)) {
            // RDB header: REDIS0009
            dos.write("REDIS0009".getBytes(StandardCharsets.ISO_8859_1));
            // SELECTDB 0: 0xFE + length-encoded 0 (单字节 0x00)
            dos.writeByte(0xFE);
            dos.writeByte(0x00);
            // expire opcode 0xFD + 4 字节小端秒级时间戳
            dos.writeByte(0xFD);
            dos.writeByte((byte) (targetExpireSec & 0xFF));
            dos.writeByte((byte) ((targetExpireSec >> 8) & 0xFF));
            dos.writeByte((byte) ((targetExpireSec >> 16) & 0xFF));
            dos.writeByte((byte) ((targetExpireSec >> 24) & 0xFF));
            // type=string 0x00 + key + value（length-prefixed strings，与实现一致）
            byte[] keyBytes = "fdKey".getBytes(StandardCharsets.ISO_8859_1);
            byte[] valBytes = "fdValue".getBytes(StandardCharsets.ISO_8859_1);
            dos.writeByte(0x00); // RDB_TYPE_STRING
            // length < 64 -> 单字节长度
            dos.writeByte(keyBytes.length);
            dos.write(keyBytes);
            dos.writeByte(valBytes.length);
            dos.write(valBytes);
            // EOF footer: 0xFF + 8 字节校验和
            dos.writeByte(0xFF);
            dos.writeLong(System.currentTimeMillis());
        }

        // 写入 dump.rdb，让 persistService.load 读到
        File rdbFile = new File(TEST_DATA_DIR, "dump.rdb");
        try (FileOutputStream fos = new FileOutputStream(rdbFile)) {
            fos.write(rdbBytes.toByteArray());
        }

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("fdValue", newStore.get(0, "fdKey"));
        long pttlAfter = newStore.pttl(0, "fdKey");
        assertTrue("0xFD path: pttl after load should be positive, got " + pttlAfter,
                pttlAfter > 0);
        // 120s TTL，恢复后剩余应在 110s ~ 120s 之间（允许 load 耗时）
        assertTrue("0xFD path: pttl after load within expected range, got " + pttlAfter,
                pttlAfter > 110_000L && pttlAfter <= 120_000L);
        // 注意：0xFD 存储秒级精度的绝对过期时间戳（truncate 到整秒），
        // 但 remaining = expireAtMs - now，now 带毫秒分量，故恢复后 pttl 不必是整秒。
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
