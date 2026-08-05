package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class EvictionSchedulerTest {

    private OffHeapStringEngine offheap;
    private OnHeapStructEngine onheap;

    @BeforeEach
    void setup() {
        offheap = new OffHeapStringEngine(100);
        onheap = new OnHeapStructEngine();
    }

    @AfterEach
    void teardown() {
        offheap.close();
        onheap.close();
    }

    @Test
    void noevictionShouldReturnFalse() {
        EvictionScheduler sched = new EvictionScheduler(offheap, onheap, 1024, "noeviction");
        assertFalse(sched.tryEvictMemory(0, 0, 999999));
    }

    @Test
    void allkeysLruEvictsAcrossEngines() {
        // 填两引擎
        offheap.set(0, "oh1", "a".repeat(200));
        offheap.set(0, "oh2", "b".repeat(200));
        onheap.set(0, "on1", "c");   // 小 string 进 onheap
        long total = offheap.estimateUsedMemory() + onheap.estimateUsedMemory();
        // 设 maxMemory 略低于 total，触发淘汰
        EvictionScheduler sched = new EvictionScheduler(offheap, onheap, total - 100, "allkeys-lru");
        boolean ok = sched.tryEvictMemory(0, 0, 50);
        assertTrue(ok);
    }

    @Test
    void volatileLruNoCandidatesReturnsFalse() {
        // 全是无 TTL 的，volatile-lru 无法淘汰
        offheap.set(0, "k1", "a".repeat(200));
        EvictionScheduler sched = new EvictionScheduler(offheap, onheap, 1, "volatile-lru");
        assertFalse(sched.tryEvictMemory(0, 0, 999999));
    }
}
