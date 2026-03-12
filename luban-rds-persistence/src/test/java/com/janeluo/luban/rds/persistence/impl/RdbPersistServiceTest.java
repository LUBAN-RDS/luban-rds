package com.janeluo.luban.rds.persistence.impl;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.*;

import static org.junit.Assert.*;

public class RdbPersistServiceTest {

    private static final String TEST_DATA_DIR = "./target/test-data/rdb-test";
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
            for (File file : dataDir.listFiles()) {
                file.delete();
            }
            dataDir.delete();
        }
    }

    @Test
    public void testStringPersistence() {
        memoryStore.set(0, "key1", "value1");
        memoryStore.set(0, "key2", "hello world");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("value1", newStore.get(0, "key1"));
        assertEquals("hello world", newStore.get(0, "key2"));
    }

    @Test
    public void testListPersistence() {
        List<String> list = new ArrayList<>();
        list.add("item1");
        list.add("item2");
        list.add("item3");
        memoryStore.set(0, "mylist", list);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "mylist");
        assertNotNull(loaded);
        assertTrue(loaded instanceof List);
        List<?> loadedList = (List<?>) loaded;
        assertEquals(3, loadedList.size());
        assertEquals("item1", loadedList.get(0));
        assertEquals("item2", loadedList.get(1));
        assertEquals("item3", loadedList.get(2));
    }

    @Test
    public void testSetPersistence() {
        Set<String> set = new HashSet<>();
        set.add("member1");
        set.add("member2");
        set.add("member3");
        memoryStore.set(0, "myset", set);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "myset");
        assertNotNull(loaded);
        assertTrue(loaded instanceof Set);
        Set<?> loadedSet = (Set<?>) loaded;
        assertEquals(3, loadedSet.size());
        assertTrue(loadedSet.contains("member1"));
        assertTrue(loadedSet.contains("member2"));
        assertTrue(loadedSet.contains("member3"));
    }

    @Test
    public void testHashPersistence() {
        Map<String, String> hash = new HashMap<>();
        hash.put("field1", "value1");
        hash.put("field2", "value2");
        hash.put("field3", "value3");
        memoryStore.set(0, "myhash", hash);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        Object loaded = newStore.get(0, "myhash");
        assertNotNull(loaded);
        assertTrue(loaded instanceof Map);
        Map<?, ?> loadedHash = (Map<?, ?>) loaded;
        assertEquals(3, loadedHash.size());
        assertEquals("value1", loadedHash.get("field1"));
        assertEquals("value2", loadedHash.get("field2"));
        assertEquals("value3", loadedHash.get("field3"));
    }

    @Test
    public void testZSetPersistence() {
        memoryStore.zadd(0, "myzset", 1.0, "member1");
        memoryStore.zadd(0, "myzset", 2.0, "member2");
        memoryStore.zadd(0, "myzset", 3.0, "member3");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(3L, newStore.zcard(0, "myzset"));
        assertEquals(1.0, newStore.zscore(0, "myzset", "member1"), 0.001);
        assertEquals(2.0, newStore.zscore(0, "myzset", "member2"), 0.001);
        assertEquals(3.0, newStore.zscore(0, "myzset", "member3"), 0.001);
    }

    @Test
    public void testMultipleDatabases() {
        memoryStore.set(0, "db0key", "value0");
        memoryStore.set(1, "db1key", "value1");
        memoryStore.set(2, "db2key", "value2");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals("value0", newStore.get(0, "db0key"));
        assertEquals("value1", newStore.get(1, "db1key"));
        assertEquals("value2", newStore.get(2, "db2key"));
    }

    @Test
    public void testLongKeyNames() {
        String longKey = "a".repeat(200);
        String longValue = "b".repeat(500);
        memoryStore.set(0, longKey, longValue);

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(longValue, newStore.get(0, longKey));
    }

    @Test
    public void testEmptyStore() {
        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(0L, newStore.dbsize(0));
    }

    @Test
    public void testMixedDataTypes() {
        memoryStore.set(0, "str1", "string value");
        memoryStore.set(0, "str2", "another string");

        List<String> list = new ArrayList<>();
        list.add("a");
        list.add("b");
        memoryStore.set(0, "list1", list);

        Set<String> set = new HashSet<>();
        set.add("x");
        set.add("y");
        memoryStore.set(0, "set1", set);

        Map<String, String> hash = new HashMap<>();
        hash.put("f1", "v1");
        memoryStore.set(0, "hash1", hash);

        memoryStore.zadd(0, "zset1", 1.0, "z1");
        memoryStore.zadd(0, "zset1", 2.0, "z2");

        persistService.persistSync(memoryStore);

        MemoryStore newStore = new DefaultMemoryStore();
        persistService.load(newStore);

        assertEquals(6L, newStore.dbsize(0));
        assertEquals("string value", newStore.get(0, "str1"));
        assertEquals(2, ((List<?>) newStore.get(0, "list1")).size());
        assertEquals(2, ((Set<?>) newStore.get(0, "set1")).size());
        assertEquals(1, ((Map<?, ?>) newStore.get(0, "hash1")).size());
        assertEquals(2L, newStore.zcard(0, "zset1"));
    }
}