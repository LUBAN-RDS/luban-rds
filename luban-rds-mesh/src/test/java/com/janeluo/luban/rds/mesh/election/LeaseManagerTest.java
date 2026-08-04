package com.janeluo.luban.rds.mesh.election;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LeaseManager} 单元测试（DESIGN.md §5.7 / §7.5）。
 * <p>
 * 覆盖：续租后有效、过期失效、awaitValid 阻塞与唤醒、invalidate 永久失效、leaseDuration 配置。
 * </p>
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class LeaseManagerTest {

    @Test
    void refresh_makesLeaseValid() {
        LeaseManager lm = new LeaseManager(600);
        long now = 1_000_000L;
        assertFalse(lm.isValid(now), "未续租前应失效");

        lm.refreshOnMajorityAck(now);
        assertTrue(lm.isValid(now), "续租后应有效");
        assertTrue(lm.isValid(now + 599), "续租后 599ms 仍有效");
        assertFalse(lm.isValid(now + 600), "续租后 600ms 应失效");
    }

    @Test
    void leaseExpiresAfterDuration() throws Exception {
        // 用很短 duration 实测过期
        LeaseManager lm = new LeaseManager(50);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        assertTrue(lm.isValid(System.currentTimeMillis()));
        // 等待过期
        Thread.sleep(80);
        assertFalse(lm.isValid(System.currentTimeMillis()), "超过 duration 后应失效");
    }

    @Test
    void invalidate_makesInvalidImmediately() {
        LeaseManager lm = new LeaseManager(600);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        assertTrue(lm.isValid(System.currentTimeMillis()));

        lm.invalidate();
        assertFalse(lm.isValid(System.currentTimeMillis()), "invalidate 后立即失效");
        assertFalse(lm.isValid(System.currentTimeMillis() + 1000), "invalidate 后持久失效");
    }

    @Test
    void invalidate_clearsExpireAt() {
        LeaseManager lm = new LeaseManager(600);
        lm.refreshOnMajorityAck(1_000_000L);
        assertEquals(1_000_600L, lm.getLeaseExpireAt());

        lm.invalidate();
        assertEquals(0L, lm.getLeaseExpireAt(), "invalidate 后 leaseExpireAt 应清零");
    }

    @Test
    void awaitValid_returnsImmediately_whenValid() throws Exception {
        LeaseManager lm = new LeaseManager(600);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        // 已有效 → 立即返回 true
        boolean result = lm.awaitValid(100);
        assertTrue(result);
    }

    @Test
    void awaitValid_returnsFalse_whenTimeoutAndStillInvalid() throws Exception {
        LeaseManager lm = new LeaseManager(50);
        // 未续租，失效
        boolean result = lm.awaitValid(60);
        assertFalse(result, "未续租时 awaitValid 应在超时后返回 false");
    }

    @Test
    void awaitValid_returnsFalseAfterExpiry_withoutRefresh() throws Exception {
        // 续租一次但很快过期；awaitValid 在过期窗口内等待超时
        LeaseManager lm = new LeaseManager(30);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        Thread.sleep(60); // 等其过期
        long start = System.currentTimeMillis();
        boolean result = lm.awaitValid(80);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(result, "过期且无续租应超时返回 false");
        assertTrue(elapsed >= 60, "应阻塞等待约整个超时时长");
    }

    @Test
    void awaitValid_blocksUntilRefreshed() throws Exception {
        // 失效时 awaitValid 阻塞；另一线程续租后应被唤醒返回 true
        LeaseManager lm = new LeaseManager(600);
        AtomicReference<Boolean> result = new AtomicReference<>(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                result.set(lm.awaitValid(3000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                finished.countDown();
            }
        });
        waiter.setDaemon(true);
        waiter.start();

        // 确认等待者已进入阻塞
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(100); // 让其充分阻塞
        assertEquals(null, result.get(), "等待者应仍阻塞（未续租）");

        // 续租唤醒
        lm.refreshOnMajorityAck(System.currentTimeMillis());

        assertTrue(finished.await(2, TimeUnit.SECONDS), "续租后等待者应被唤醒");
        assertTrue(result.get(), "唤醒后 awaitValid 应返回 true");
    }

    @Test
    void invalidate_returnsFalse_onShortTimeout() throws Exception {
        // invalidate 后 awaitValid 在短超时内返回 false（不会因续租变成 true）
        LeaseManager lm = new LeaseManager(600);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        assertTrue(lm.isValid(System.currentTimeMillis()));
        lm.invalidate();
        assertFalse(lm.isValid(System.currentTimeMillis()));

        long start = System.currentTimeMillis();
        boolean result = lm.awaitValid(80);
        long elapsed = System.currentTimeMillis() - start;
        assertFalse(result, "invalidate 后 awaitValid 应超时返回 false");
        assertTrue(elapsed >= 70, "应阻塞至超时");
    }

    @Test
    void awaitValid_interruptedPropagates() throws Exception {
        // 被中断的 awaitValid 应抛 InterruptedException
        LeaseManager lm = new LeaseManager(600);
        AtomicReference<Throwable> caught = new AtomicReference<>(null);
        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                lm.awaitValid(10_000);
            } catch (InterruptedException e) {
                caught.set(e);
                Thread.currentThread().interrupt();
            }
        });
        waiter.setDaemon(true);
        waiter.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(2_000);
        assertTrue(caught.get() instanceof InterruptedException,
                "中断应抛 InterruptedException，实际: " + caught.get());
    }

    @Test
    void constructor_rejectsInvalidDuration() {
        assertThrows(IllegalArgumentException.class, () -> new LeaseManager(0));
        assertThrows(IllegalArgumentException.class, () -> new LeaseManager(-1));
    }

    @Test
    void defaultLeaseDurationIs600ms() {
        LeaseManager lm = new LeaseManager();
        assertEquals(600, lm.getLeaseDurationMs());
        assertEquals(600, LeaseManager.DEFAULT_LEASE_DURATION_MS);
    }
}
