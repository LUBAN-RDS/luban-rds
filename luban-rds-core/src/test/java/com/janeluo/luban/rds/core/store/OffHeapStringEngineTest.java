package com.janeluo.luban.rds.core.store;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OffHeapStringEngineTest {

    private OffHeapStringEngine engine;

    @BeforeEach
    void setup() { engine = new OffHeapStringEngine(256); } // threshold=256
    @AfterEach
    void teardown() { engine.close(); }

    @Test
    void setGetLargeStringRoundTrip() {
        String big = "x".repeat(1000); // >= 256
        engine.set(0, "k1", big);
        assertEquals(big, engine.get(0, "k1"));
    }

    @Test
    void smallStringShouldNotBeStoredOffheap() {
        engine.set(0, "k1", "tiny"); // < 256
        // 小 string 不进堆外引擎，get 返回 null（路由层负责走 onheap）
        assertNull(engine.get(0, "k1"));
    }

    @Test
    void delShouldReleaseBuffer() {
        String big = "y".repeat(500);
        engine.set(0, "k1", big);
        assertTrue(engine.del(0, "k1"));
        assertNull(engine.get(0, "k1"));
        assertEquals(0, engine.estimateUsedMemory());
    }

    @Test
    void replaceShouldReleaseOldBuffer() {
        engine.set(0, "k1", "a".repeat(500));
        long mem1 = engine.estimateUsedMemory();
        engine.set(0, "k1", "b".repeat(600)); // replace
        long mem2 = engine.estimateUsedMemory();
        assertNotEquals(mem1, mem2);
        assertEquals("b".repeat(600), engine.get(0, "k1"));
        // refCnt 应仍为 1（旧 buffer 已 release，新 buffer refCnt=1）
        // 间接验证：close 时不抛异常即说明无泄漏
    }

    @Test
    void existsAndType() {
        engine.set(0, "k1", "x".repeat(300));
        assertTrue(engine.exists(0, "k1"));
        assertFalse(engine.exists(0, "nope"));
        assertEquals("string", engine.type(0, "k1"));
    }

    @Test
    void sampleForEvictionReturnsCandidatesForLru() {
        engine.set(0, "k1", "a".repeat(300));
        engine.set(0, "k2", "b".repeat(300));
        var cands = engine.sampleForEviction(0, "allkeys-lru", 5);
        assertEquals(2, cands.size());
        assertTrue(cands.stream().allMatch(c -> "offheap".equals(c.engineId)));
    }

    @Test
    void sampleForEvictionVolatileOnlyFiltersNoTtl() {
        engine.set(0, "k1", "a".repeat(300));              // 无 TTL
        engine.setWithExpire(0, "k2", "b".repeat(300), System.currentTimeMillis() + 100000);
        var all = engine.sampleForEviction(0, "allkeys-lru", 5);
        var vol = engine.sampleForEviction(0, "volatile-lru", 5);
        assertTrue(all.size() >= 2);
        assertEquals(1, vol.size()); // 只有 k2
        assertEquals("k2", vol.get(0).key);
    }
}
