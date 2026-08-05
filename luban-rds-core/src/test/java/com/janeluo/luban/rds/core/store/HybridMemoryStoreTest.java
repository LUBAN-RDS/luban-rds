package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class HybridMemoryStoreTest {

    private HybridMemoryStore store;

    @BeforeEach
    void setup() {
        store = new HybridMemoryStore(16, 0, "noeviction", 256);
    }

    @AfterEach
    void teardown() {
        store.close();
    }

    @Test
    void largeStringRoutedOffheap() {
        String big = "x".repeat(1000);
        store.set(0, "k", big);
        assertEquals(big, store.get(0, "k"));
        assertEquals("string", store.type(0, "k"));
        // 堆外引擎应有占用
        assertTrue(store.getOffheapUsedMemory() > 0);
    }

    @Test
    void smallStringRoutedOnheap() {
        store.set(0, "k", "tiny");
        assertEquals("tiny", store.get(0, "k"));
        // 小 string 不进堆外
        assertEquals(0, store.getOffheapUsedMemory());
    }

    @Test
    void hashRoutedOnheap() {
        store.hset(0, "k", "f1", "v1");
        assertEquals("v1", store.hget(0, "k", "f1"));
        assertEquals("hash", store.type(0, "k"));
    }

    @Test
    void typeSwitchStringToHashReleasesBuffer() {
        String big = "x".repeat(1000);
        store.set(0, "k", big);
        long mem1 = store.getOffheapUsedMemory();
        assertTrue(mem1 > 0);
        // string → hash
        store.hset(0, "k", "f", "v");
        // 堆外应已清空该 key
        assertEquals(0, store.getOffheapUsedMemory());
        assertEquals("hash", store.type(0, "k"));
    }

    @Test
    void typeSwitchHashToString() {
        store.hset(0, "k", "f", "v");
        store.set(0, "k", "now-string");
        assertEquals("string", store.type(0, "k"));
        assertEquals("now-string", store.get(0, "k"));
    }

    @Test
    void dbsizeAggregatesBothEngines() {
        store.set(0, "big1", "x".repeat(1000));   // offheap
        store.set(0, "sm1", "t");                  // onheap
        store.hset(0, "h1", "f", "v");             // onheap hash
        assertEquals(3, store.dbsize(0));
    }
}
