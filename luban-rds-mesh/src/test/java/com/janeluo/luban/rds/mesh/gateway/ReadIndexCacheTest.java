package com.janeluo.luban.rds.mesh.gateway;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReadIndexCacheTest {

    @Test
    void reusesWithinWindow_issuesSingleRpc() {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Long> rpc = () -> {
            calls.incrementAndGet();
            return 100L;
        };

        ReadIndexCache.Entry e1 = cache.get(1_000, 500, rpc, 1L);
        ReadIndexCache.Entry e2 = cache.get(1_000, 500, rpc, 1L);

        assertNotNull(e1);
        assertNotNull(e2);
        assertEquals(100L, e2.readIndex);
        assertEquals(1, calls.get(), "窗口内必须复用，只发一次 RPC");
        assertEquals(1, cache.cacheHits());
    }

    @Test
    void expiredWindow_issuesNewRpc() throws Exception {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Long> rpc = () -> (long) (calls.incrementAndGet() * 100);

        cache.get(50, 500, rpc, 1L);
        Thread.sleep(120);
        ReadIndexCache.Entry e2 = cache.get(50, 500, rpc, 1L);

        assertEquals(2, calls.get(), "窗口过期必须重新取读点");
        assertEquals(200L, e2.readIndex);
    }

    @Test
    void zeroWindow_neverCaches() {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Long> rpc = () -> {
            calls.incrementAndGet();
            return 100L;
        };

        cache.get(0, 500, rpc, 1L);
        cache.get(0, 500, rpc, 1L);

        assertEquals(2, calls.get(), "cacheMs=0 必须每次取新读点（严格线性一致）");
        assertEquals(0, cache.cacheHits());
    }

    @Test
    void termChange_invalidatesCache() {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Long> rpc = () -> {
            calls.incrementAndGet();
            return 100L;
        };

        cache.get(1_000, 500, rpc, 1L);
        cache.get(1_000, 500, rpc, 2L);              // term 从 1 → 2

        assertEquals(2, calls.get(), "term 变化必须失效缓存");
        assertEquals(1, cache.invalidations());
    }

    @Test
    void concurrentMisses_coalesceIntoOneRpc() throws Exception {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inRpc = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Long> rpc = () -> {
            calls.incrementAndGet();
            inRpc.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100L;
        };

        int threads = 8;
        AtomicInteger ok = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                ReadIndexCache.Entry e = cache.get(1_000, 3_000, rpc, 1L);
                if (e != null) {
                    ok.incrementAndGet();
                }
                done.countDown();
            }).start();
        }
        inRpc.await(2, TimeUnit.SECONDS);
        Thread.sleep(100);                            // 让其余线程都挂到在途 future
        release.countDown();
        done.await(5, TimeUnit.SECONDS);

        assertEquals(8, ok.get(), "所有并发读都应拿到读点");
        assertEquals(1, calls.get(), "并发 miss 必须合并为一次 RPC");
        assertEquals(7, cache.coalesced());
    }

    @Test
    void rpcFailure_isNotCached() {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicLong value = new AtomicLong(0);
        Supplier<Long> rpc = value::get;

        assertNull(cache.get(1_000, 500, () -> null, 1L), "RPC 失败必须返回 null");
        assertNull(cache.get(1_000, 500, () -> null, 1L), "失败不得写缓存，下一次仍要取");
        assertNotNull(cache.get(1_000, 500, () -> 42L, 1L));
        assertEquals(42L, cache.get(1_000, 500, () -> 7L, 1L).readIndex, "新读点应写入缓存");
    }

    @Test
    void invalidate_clearsEntry() {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        Supplier<Long> rpc = () -> {
            calls.incrementAndGet();
            return 100L;
        };

        cache.get(10_000, 500, rpc, 1L);
        cache.invalidate();
        cache.get(10_000, 500, rpc, 1L);

        assertEquals(2, calls.get(), "invalidate 后必须重新取读点");
        assertEquals(1, cache.invalidations());
    }
}
