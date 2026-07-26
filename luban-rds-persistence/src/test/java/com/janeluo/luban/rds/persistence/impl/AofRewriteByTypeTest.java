package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AOF rewrite 按类型生成重建命令测试 (C11)。
 *
 * <p>验证 AOF rewrite 后重启加载能保留类型与数据：
 * <ul>
 *   <li>string/list/set/hash/zset 各自的重建命令格式正确</li>
 *   <li>带 TTL 的键追加 PEXPIREAT 保留过期时间</li>
 *   <li>stream 逐条 XADD + XGROUP CREATE + XCLAIM FORCE 完整恢复 PEL</li>
 *   <li>空集合不写重建命令（Redis 行为）</li>
 * </ul>
 */
public class AofRewriteByTypeTest {

    private static final String TEST_DATA_DIR = "./target/test-data/aof-rewrite-bytype-test";

    private AofPersistService persistService;
    private MemoryStore memoryStore;

    @Before
    public void setUp() {
        cleanTestDataDir();
        File dataDir = new File(TEST_DATA_DIR);
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        persistService = new AofPersistService(TEST_DATA_DIR, 0);
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

    // ==================== 基础类型 ====================

    @Test
    public void testStringRewriteAndReload() {
        memoryStore.set(0, "s1", "hello");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("string", newStore.type(0, "s1"));
        assertEquals("hello", newStore.get(0, "s1"));
    }

    @Test
    public void testListRewriteAndReload() {
        memoryStore.rpush(0, "l1", "a", "b", "c");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("list", newStore.type(0, "l1"));
        List<String> list = newStore.lrange(0, "l1", 0, -1);
        assertEquals(3, list.size());
        assertEquals("a", list.get(0));
        assertEquals("b", list.get(1));
        assertEquals("c", list.get(2));
    }

    @Test
    public void testSetRewriteAndReload() {
        memoryStore.sadd(0, "set1", "m1", "m2", "m3");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("set", newStore.type(0, "set1"));
        Set<String> members = newStore.smembers(0, "set1");
        assertEquals(3, members.size());
        assertTrue(members.contains("m1"));
        assertTrue(members.contains("m2"));
        assertTrue(members.contains("m3"));
    }

    @Test
    public void testHashRewriteAndReload() {
        memoryStore.hmset(0, "h1", "f1", "v1", "f2", "v2");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("hash", newStore.type(0, "h1"));
        Map<String, String> hash = newStore.hgetall(0, "h1");
        assertEquals(2, hash.size());
        assertEquals("v1", hash.get("f1"));
        assertEquals("v2", hash.get("f2"));
    }

    @Test
    public void testZsetRewriteAndReload() {
        memoryStore.zadd(0, "z1", 1.5, "a");
        memoryStore.zadd(0, "z1", 2.5, "b");
        memoryStore.zadd(0, "z1", 3.5, "c");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("zset", newStore.type(0, "z1"));
        assertEquals(3L, newStore.zcard(0, "z1"));
        assertEquals(Double.valueOf(1.5), newStore.zscore(0, "z1", "a"));
        assertEquals(Double.valueOf(2.5), newStore.zscore(0, "z1", "b"));
        assertEquals(Double.valueOf(3.5), newStore.zscore(0, "z1", "c"));
    }

    // ==================== TTL ====================

    @Test
    public void testStringWithTtlRewriteAndReload() {
        memoryStore.setWithExpireMs(0, "ttlKey", "v", 600_000L);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("v", newStore.get(0, "ttlKey"));
        long pttl = newStore.pttl(0, "ttlKey");
        assertTrue("TTL should be restored, got " + pttl, pttl > 0);
        assertTrue("TTL should be within range, got " + pttl,
                pttl > 590_000L && pttl <= 600_000L);
    }

    @Test
    public void testHashWithTtlRewriteAndReload() {
        memoryStore.hmset(0, "hTtl", "f1", "v1");
        memoryStore.pexpire(0, "hTtl", 600_000L);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("hash", newStore.type(0, "hTtl"));
        assertEquals("v1", newStore.hget(0, "hTtl", "f1"));
        long pttl = newStore.pttl(0, "hTtl");
        assertTrue("hash TTL restored, got " + pttl, pttl > 0);
    }

    @Test
    public void testZsetWithTtlRewriteAndReload() {
        memoryStore.zadd(0, "zTtl", 1.0, "m");
        memoryStore.pexpire(0, "zTtl", 600_000L);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("zset", newStore.type(0, "zTtl"));
        assertEquals(1L, newStore.zcard(0, "zTtl"));
        assertTrue("zset TTL restored", newStore.pttl(0, "zTtl") > 0);
    }

    // ==================== 空集合不写 ====================

    @Test
    public void testEmptyCollectionsNotWritten() {
        // 创建空集合（直接 set 空容器）
        memoryStore.set(0, "emptyList", new ArrayList<String>());
        memoryStore.set(0, "emptySet", new HashSet<String>());
        memoryStore.set(0, "emptyHash", new HashMap<String, String>());
        // zset 通过 zadd 创建后无成员较难，跳过；list/set/hash 通过空容器直接 set

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        // 空集合不应被恢复（Redis 行为：rewrite 跳过空集合）
        // 注意：DefaultMemoryStore.set 直接存储对象，type 取决于对象类型
        // 但 AOF rewrite 应跳过这些空集合，不写重建命令，故加载后键不存在
        assertNull("empty list should NOT be rewritten", newStore.get(0, "emptyList"));
        assertNull("empty set should NOT be rewritten", newStore.get(0, "emptySet"));
        assertNull("empty hash should NOT be rewritten", newStore.get(0, "emptyHash"));
    }

    // ==================== Stream ====================

    @Test
    public void testStreamRewriteAndReloadData() {
        // XADD 三条消息
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("field1", "value1");
        StreamId id1 = memoryStore.xadd(0, "stream1", new StreamId(1000, 0), f1,
                false, null, null, null, false);

        Map<String, String> f2 = new LinkedHashMap<>();
        f2.put("field2", "value2");
        StreamId id2 = memoryStore.xadd(0, "stream1", new StreamId(2000, 0), f2,
                false, null, null, null, false);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("stream", newStore.type(0, "stream1"));
        assertEquals(2L, newStore.xlen(0, "stream1"));

        List<StreamEntry> entries = newStore.xrange(0, "stream1",
                StreamId.MIN_ID, StreamId.MAX_ID, false, false, 100, false);
        assertEquals(2, entries.size());
        assertEquals(id1, entries.get(0).getId());
        assertEquals("value1", entries.get(0).getFields().get("field1"));
        assertEquals(id2, entries.get(1).getId());
        assertEquals("value2", entries.get(1).getFields().get("field2"));
    }

    @Test
    public void testStreamRewriteAndReloadGroupAndPel() {
        // 准备 stream + group + PEL
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("k", "v1");
        StreamId id1 = memoryStore.xadd(0, "s", new StreamId(1000, 0), f1,
                false, null, null, null, false);

        Map<String, String> f2 = new LinkedHashMap<>();
        f2.put("k", "v2");
        StreamId id2 = memoryStore.xadd(0, "s", new StreamId(2000, 0), f2,
                false, null, null, null, false);

        // 创建消费者组，从 0-0 开始（即所有消息都待投递）
        assertTrue(memoryStore.xgroupCreate(0, "s", "grp1", StreamId.MIN_ID, false));

        // consumer1 读取 -> 两条消息进入 PEL
        Map<String, List<StreamEntry>> read = memoryStore.xreadGroup(0, "s", "grp1", "consumer1",
                null, 10, false);
        assertNotNull(read);
        // xreadGroup 返回值以 key 为键（与生产实现一致），consumer1 读取后两条消息进入 PEL
        List<StreamEntry> got = read.get("s");
        assertNotNull(got);
        assertEquals(2, got.size());

        // 验证 PEL 已建立
        Map<String, Object> summary = memoryStore.xpendingSummary(0, "s", "grp1");
        assertEquals(2L, ((Number) summary.get("count")).longValue());

        // 执行 rewrite
        persistService.rewrite(memoryStore);

        // 加载到新 store
        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        // 验证 stream 数据
        assertEquals("stream", newStore.type(0, "s"));
        assertEquals(2L, newStore.xlen(0, "s"));

        // 验证 group 存在
        List<Map<String, Object>> groups = newStore.xinfoGroups(0, "s");
        assertEquals(1, groups.size());
        assertEquals("grp1", groups.get(0).get("name"));

        // 验证 PEL 完整恢复
        Map<String, Object> pelSummary = newStore.xpendingSummary(0, "s", "grp1");
        long pelCount = ((Number) pelSummary.get("count")).longValue();
        assertEquals("PEL should be fully restored with 2 pending messages", 2L, pelCount);

        // 验证 PEL 中的消息 ID 与原一致
        List<Map<String, Object>> pelList = newStore.xpendingList(0, "s", "grp1",
                StreamId.MIN_ID, StreamId.MAX_ID, 100, null, 0);
        Set<String> pelIds = new HashSet<>();
        for (Map<String, Object> pm : pelList) {
            pelIds.add(String.valueOf(pm.get("id")));
        }
        assertTrue("PEL should contain id1: " + id1, pelIds.contains(id1.toString()));
        assertTrue("PEL should contain id2: " + id2, pelIds.contains(id2.toString()));
    }

    @Test
    public void testStreamRewriteMultipleGroupsAndConsumers() {
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("k", "v1");
        StreamId id1 = memoryStore.xadd(0, "ms", new StreamId(1000, 0), f1,
                false, null, null, null, false);

        Map<String, String> f2 = new LinkedHashMap<>();
        f2.put("k", "v2");
        StreamId id2 = memoryStore.xadd(0, "ms", new StreamId(2000, 0), f2,
                false, null, null, null, false);

        // 两个组
        assertTrue(memoryStore.xgroupCreate(0, "ms", "g1", StreamId.MIN_ID, false));
        assertTrue(memoryStore.xgroupCreate(0, "ms", "g2", StreamId.MIN_ID, false));

        // g1/consumer1 读取一条
        memoryStore.xreadGroup(0, "ms", "g1", "consumer1",
                null, 1, false);
        // g2/consumer2 读取两条
        memoryStore.xreadGroup(0, "ms", "g2", "consumer2",
                null, 10, false);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(2L, newStore.xlen(0, "ms"));

        List<Map<String, Object>> groups = newStore.xinfoGroups(0, "ms");
        assertEquals(2, groups.size());

        // g1 应有 1 条 PEL
        Map<String, Object> g1Summary = newStore.xpendingSummary(0, "ms", "g1");
        assertEquals(1L, ((Number) g1Summary.get("count")).longValue());

        // g2 应有 2 条 PEL
        Map<String, Object> g2Summary = newStore.xpendingSummary(0, "ms", "g2");
        assertEquals(2L, ((Number) g2Summary.get("count")).longValue());
    }

    @Test
    public void testStreamWithTtlRewriteAndReload() {
        Map<String, String> f1 = new LinkedHashMap<>();
        f1.put("k", "v1");
        memoryStore.xadd(0, "sTtl", new StreamId(1000, 0), f1,
                false, null, null, null, false);
        memoryStore.pexpire(0, "sTtl", 600_000L);

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("stream", newStore.type(0, "sTtl"));
        assertEquals(1L, newStore.xlen(0, "sTtl"));
        assertTrue("stream TTL restored", newStore.pttl(0, "sTtl") > 0);
    }

    // ==================== 混合场景 ====================

    @Test
    public void testMixedTypesRewriteAndReload() {
        memoryStore.set(0, "str", "sv");
        memoryStore.rpush(0, "lst", "l1", "l2");
        memoryStore.sadd(0, "st", "s1", "s2");
        memoryStore.hmset(0, "hs", "f", "v");
        memoryStore.zadd(0, "zs", 1.0, "m");

        persistService.rewrite(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("string", newStore.type(0, "str"));
        assertEquals("list", newStore.type(0, "lst"));
        assertEquals("set", newStore.type(0, "st"));
        assertEquals("hash", newStore.type(0, "hs"));
        assertEquals("zset", newStore.type(0, "zs"));

        assertEquals("sv", newStore.get(0, "str"));
        assertEquals(2, newStore.lrange(0, "lst", 0, -1).size());
        assertEquals(2, newStore.smembers(0, "st").size());
        assertEquals("v", newStore.hget(0, "hs", "f"));
        assertEquals(1L, newStore.zcard(0, "zs"));
    }
}
