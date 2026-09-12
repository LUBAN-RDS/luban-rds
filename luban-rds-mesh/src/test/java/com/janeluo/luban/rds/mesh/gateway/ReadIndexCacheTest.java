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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(inRpc.await(2, TimeUnit.SECONDS), "owner 应已进入 RPC");
        // 等待全部 7 个等待者都挂到在途 future（coalesced 计数可观测），避免 sleep 抖动导致
        // owner 提前完成、部分线程另起一次 RPC 的 flake；owner 在 release 前不会释放 inFlight。
        long deadline = System.currentTimeMillis() + 2_000;
        while (cache.coalesced() < threads - 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(threads - 1, cache.coalesced(), "所有并发读都应挂到在途 future");
        release.countDown();
        done.await(5, TimeUnit.SECONDS);

        assertEquals(8, ok.get(), "所有并发读都应拿到读点");
        assertEquals(1, calls.get(), "并发 miss 必须合并为一次 RPC");
        assertEquals(7, cache.coalesced());
    }

    @Test
    void invalidateDuringInFlightRpc_doesNotCacheStaleEntry() throws Exception {
        ReadIndexCache cache = new ReadIndexCache();
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch inRpc = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Long> slowRpc = () -> {
            calls.incrementAndGet();
            inRpc.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100L;
        };

        Thread owner = new Thread(() -> cache.get(10_000, 3_000, slowRpc, 1L));
        owner.start();
        assertTrue(inRpc.await(2, TimeUnit.SECONDS), "owner 应已进入 RPC");

        cache.invalidate();                           // 取点途中失效（模拟 term/Leader 变更、apply halt）
        release.countDown();
        owner.join(5_000);

        cache.get(10_000, 500, () -> {
            calls.incrementAndGet();
            return 200L;
        }, 1L);

        assertEquals(2, calls.get(), "在途 RPC 期间失效后，陈旧读点不得被回填缓存");
    }

    @Test
    void coalescedWaiterWithDifferentTerm_getsNull() throws Exception {
        ReadIndexCache cache = new ReadIndexCache();
        CountDownLatch inRpc = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Supplier<Long> rpc = () -> {
            inRpc.countDown();
            try {
                release.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return 100L;
        };

        Thread owner = new Thread(() -> cache.get(10_000, 3_000, rpc, 1L));
        owner.start();
        assertTrue(inRpc.await(2, TimeUnit.SECONDS), "owner 应已进入 RPC");

        java.util.concurrent.atomic.AtomicReference<ReadIndexCache.Entry> waiterResult =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() -> waiterResult.set(cache.get(10_000, 3_000, rpc, 2L)));
        waiter.start();
        long deadline = System.currentTimeMillis() + 2_000;
        while (cache.coalesced() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        release.countDown();
        waiter.join(5_000);
        owner.join(5_000);

        assertNull(waiterResult.get(), "term 不一致的合并读点必须拒绝（回落 MOVED）");
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
