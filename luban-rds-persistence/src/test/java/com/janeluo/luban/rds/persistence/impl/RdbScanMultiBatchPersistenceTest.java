package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 验证 RDB 保存对超过单批 COUNT（1000）的键做多批 scan 遍历时全键无丢失。
 * <p>
 * R4 规格场景：数据库存在超过单批 COUNT（1000）的键，执行 RDB 保存 →
 * RDB 文件包含全部键，无丢失。
 * </p>
 * <p>
 * 根因背景：SCAN 族重写为 last-visited-key 编码游标后，RdbPersistService
 * 以 {@code scan(db, cursor, "*", 1000, null)} 在 {@code do/while} 循环中
 * 逐批取键；若游标推进/终止条件有误，第二批及后续批的键会丢失。
 * 本测试插入 1100 个键（1095 个 string + 5 个结构类型键），
 * 保证首批 scan 返回非零游标、必然触发多批遍历。
 * </p>
 */
public class RdbScanMultiBatchPersistenceTest {

    /** 单批 scan COUNT，与 RdbPersistService 保存时使用的一致。 */
    private static final int SCAN_BATCH = 1000;

    /** string 键数量：1095 个 string + 5 个结构键 = 1100，保证跨 2 批。 */
    private static final int STRING_KEY_COUNT = 1095;

    private static final int TOTAL_KEY_COUNT = 1100;

    private static final String TEST_DATA_DIR = "./target/test-data/rdb-scan-multibatch-test";

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

    private static String stringKey(int i) {
        return String.format("mb:str:%04d", i);
    }

    private static String stringValue(int i) {
        return "val-" + i;
    }

    /**
     * 向 store 插入 1100 个键（1095 个 string + 2 个 hash + 2 个 set + 1 个 zset）。
     */
    private void insertKeys() {
        for (int i = 0; i < STRING_KEY_COUNT; i++) {
            memoryStore.set(0, stringKey(i), stringValue(i));
        }
        memoryStore.hmset(0, "mb:hash:1", "f1", "v1", "f2", "v2");
        memoryStore.hmset(0, "mb:hash:2", "f1", "v1", "f2", "v2", "f3", "v3");
        memoryStore.sadd(0, "mb:set:1", "m1", "m2", "m3");
        memoryStore.sadd(0, "mb:set:2", "m1", "m2", "m3", "m4");
        memoryStore.zadd(0, "mb:zset:1", 1.5, "a");
        memoryStore.zadd(0, "mb:zset:1", 2.5, "b");
        memoryStore.zadd(0, "mb:zset:1", 3.5, "c");
    }

    /**
     * 验证保存前的数据量，并确认首批 scan（COUNT=1000）返回非零游标，
     * 即测试数据必然触发多批遍历（若为单批，本测试失去验证意义）。
     */
    private void assertMultiBatchPrecondition() {
        assertEquals("插入键总数应为 1100", TOTAL_KEY_COUNT, memoryStore.dbsize(0));

        List<Object> firstPage = memoryStore.scan(0, "0", "*", SCAN_BATCH, null);
        assertTrue("首批 scan 应返回 1000 个键（COUNT=1000）",
                firstPage.size() == SCAN_BATCH + 1);
        String cursor = (String) firstPage.get(0);
        assertTrue("首批 scan 后游标应为非零（必须跨多批遍历），实际=" + cursor,
                !"0".equals(cursor));
    }

    /**
     * 1100 个键 RDB 保存后全部存活：数量、string 值与各结构键内容/类型完整。
     */
    @Test
    public void testRdbSaveAllKeysSurviveAcrossBatches() {
        insertKeys();
        assertMultiBatchPrecondition();

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("RDB 加载后键总数应为 1100，无丢失",
                TOTAL_KEY_COUNT, newStore.dbsize(0));

        for (int i = 0; i < STRING_KEY_COUNT; i++) {
            assertEquals("string 键 " + stringKey(i) + " 应存活且值完整",
                    stringValue(i), newStore.get(0, stringKey(i)));
        }

        // hash
        assertEquals("hash", newStore.type(0, "mb:hash:1"));
        Map<String, String> hash1 = newStore.hgetall(0, "mb:hash:1");
        assertEquals(2, hash1.size());
        assertEquals("v1", hash1.get("f1"));
        assertEquals("v2", hash1.get("f2"));

        assertEquals("hash", newStore.type(0, "mb:hash:2"));
        Map<String, String> hash2 = newStore.hgetall(0, "mb:hash:2");
        assertEquals(3, hash2.size());
        assertEquals("v3", hash2.get("f3"));

        // set
        assertEquals("set", newStore.type(0, "mb:set:1"));
        Set<String> set1 = newStore.smembers(0, "mb:set:1");
        assertEquals(3, set1.size());
        assertTrue(set1.contains("m1"));
        assertTrue(set1.contains("m3"));

        assertEquals("set", newStore.type(0, "mb:set:2"));
        assertEquals(4, newStore.smembers(0, "mb:set:2").size());

        // zset
        assertEquals("zset", newStore.type(0, "mb:zset:1"));
        assertEquals(3L, newStore.zcard(0, "mb:zset:1"));
        assertEquals(Double.valueOf(1.5), newStore.zscore(0, "mb:zset:1", "a"));
        assertEquals(Double.valueOf(3.5), newStore.zscore(0, "mb:zset:1", "c"));
    }
}
