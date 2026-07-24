package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 验证 hset+pexpire / lrem 后 hash/list key 能被 RDB 持久化。
 * <p>
 * 根因：DefaultMemoryStore 的 Caffeine removalListener 在 RemovalCause.REPLACED 时
 * 误删 keySet，导致 pexpire/lrem 的 storage.put 覆盖后 key 在 storage 里但 keySet
 * 里没有，scan/dbsize/RDB 持久化扫不到。修复后 removalListener 忽略 REPLACED。
 * </p>
 */
public class RdbHsetHashKeyPersistenceTest {

    private static final String TEST_DATA_DIR = "./target/test-data/rdb-hset-test";
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
     * 场景 1：hset 创建 hash key 后 pexpire，再 RDB 持久化。
     * 修复前：pexpire 触发 removalListener(REPLACED) 误删 keySet，RDB 扫不到该 key。
     * 修复后：removalListener 忽略 REPLACED，key 保留在 keySet，RDB 能保存。
     */
    @Test
    public void testHsetThenPexpireThenPersist() {
        String key = "dpl-master:session:attr:{b338b6dd-a364-42f1-b128-25ca6a0e3172}";

        // hset 创建 hash key（isNew=true 路径）
        int hsetResult = memoryStore.hset(0, key, "KAPTCHA_SESSION_KEY", "1345");
        assertEquals("hset should create new field", 1, hsetResult);

        // 模拟 Lua 脚本 HSET 后的 PEXPIRE（当 PTTL<=0 时）
        long pttl = memoryStore.pttl(0, key);
        if (pttl <= 0) {
            boolean expireOk = memoryStore.pexpire(0, key, 179999953L);
            assertTrue("pexpire should succeed after hset created the key", expireOk);
        }

        // 关键断言：pexpire 后 keySet 仍包含该 key（dbsize 不为 0）
        assertEquals("dbsize should be 1 after hset+pexpire (keySet must keep the key)",
                1, memoryStore.dbsize(0));

        // scan 应能扫到该 key
        List<Object> scanResult = memoryStore.scan(0, 0, "*", 1000);
        boolean foundInScan = false;
        for (int i = 1; i < scanResult.size(); i++) {
            if (key.equals(scanResult.get(i))) {
                foundInScan = true;
                break;
            }
        }
        assertTrue("scan should include the hset+pexpire hash key", foundInScan);

        // RDB 持久化
        persistService.persistSync(memoryStore);

        // 加载 RDB 验证
        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loadedValue = newStore.get(0, key);
        assertNotNull("hset+pexpire hash key should be persisted to RDB and reloadable",
                loadedValue);
    }

    /**
     * 场景 2：多次 hset 同一 hash key 的不同字段后持久化。
     */
    @Test
    public void testMultipleHsetFieldsThenPersist() {
        String key = "dpl-master:session:attr:{session-123}";

        memoryStore.hset(0, key, "field1", "value1");
        memoryStore.hset(0, key, "field2", "value2");
        memoryStore.pexpire(0, key, 180000000L);
        // pexpire 后再 hset（isNew=false 路径）
        memoryStore.hset(0, key, "field3", "value3");

        assertEquals("dbsize should be 1 after multiple hset+pexpire",
                1, memoryStore.dbsize(0));

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, key);
        assertNotNull("multi-hset hash key should persist after pexpire", loaded);
    }

    /**
     * 场景 3：lrem 修改 list 后 RDB 能保存该 list key。
     * lrem 内部调用 storage.put 覆盖已有条目（:2035），同样触发 REPLACED。
     */
    @Test
    public void testLremAfterPersist() {
        String key = "dpl-master:list:{list-1}";

        // 先创建 list
        memoryStore.lpush(0, key, "a", "b", "c");
        // lrem 删除一个元素（触发 storage.put 覆盖）
        int removed = memoryStore.lrem(0, key, 1, "b");
        assertEquals("lrem should remove 1 element", 1, removed);

        // 设置过期
        memoryStore.pexpire(0, key, 180000000L);

        assertEquals("dbsize should be 1 after lrem+pexpire",
                1, memoryStore.dbsize(0));

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, key);
        assertNotNull("list key should persist after lrem+pexpire", loaded);
    }
}
