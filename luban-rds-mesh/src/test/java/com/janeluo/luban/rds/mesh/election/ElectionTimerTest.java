package com.janeluo.luban.rds.mesh.election;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ElectionTimer} 单元测试：随机区间、reset 重置、start/stop、超时触发回调。
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ElectionTimerTest {

    @Test
    void nextTimeoutMs_isWithinRange() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        for (int i = 0; i < 1000; i++) {
            long v = t.nextTimeoutMs();
            assertTrue(v >= 150 && v <= 300, "超时应落在 [150,300]，实际: " + v);
        }
        assertEquals(150, t.getMinMs());
        assertEquals(300, t.getMaxMs());
    }

    @Test
    void nextTimeoutMs_producesVariedValues() {
        // 确认随机化（防 split vote 的关键）
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        java.util.Set<Long> samples = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            samples.add(t.nextTimeoutMs());
        }
        assertTrue(samples.size() > 10, "应产生多个不同超时值，实际种类: " + samples.size());
    }

    @Test
    void timeoutFiresCallback() throws Exception {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch fired = new CountDownLatch(1);
        ElectionTimer t = new ElectionTimer(20, 30, fired::countDown, exec);

        t.start();
        assertTrue(fired.await(1, TimeUnit.SECONDS), "超时应触发回调");
        exec.shutdownNow();
    }

    @Test
    void reset_preventsTimeoutFromFiring() throws Exception {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger fires = new AtomicInteger();
        // 区间较长（1-2s），靠 reset 推迟
        ElectionTimer t = new ElectionTimer(1000, 2000, fires::incrementAndGet, exec);

        t.start();
        // 在 200ms 内反复 reset，回调不应触发
        for (int i = 0; i < 5; i++) {
            Thread.sleep(80);
            t.reset();
        }
        assertEquals(0, fires.get(), "持续 reset 应阻止超时触发");
        t.stop();
        exec.shutdownNow();
    }

    @Test
    void stop_preventsCallback() throws Exception {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger fires = new AtomicInteger();
        ElectionTimer t = new ElectionTimer(50, 60, fires::incrementAndGet, exec);

        t.start();
        t.stop();
        Thread.sleep(200);
        assertEquals(0, fires.get(), "stop 后不应触发回调");
        exec.shutdownNow();
    }

    @Test
    void cannotRestartAfterStop() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        ElectionTimer t = new ElectionTimer(150, 300, () -> {}, exec);
        t.start();
        t.stop();
        assertThrows(IllegalStateException.class, t::start);
        exec.shutdownNow();
    }

    @Test
    void constructor_validatesRange() {
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor();
        assertThrows(IllegalArgumentException.class,
                () -> new ElectionTimer(0, 100, () -> {}, exec));
        assertThrows(IllegalArgumentException.class,
                () -> new ElectionTimer(300, 150, () -> {}, exec));
        assertThrows(IllegalArgumentException.class,
                () -> new ElectionTimer(150, 300, null, exec));
        assertThrows(IllegalArgumentException.class,
                () -> new ElectionTimer(150, 300, () -> {}, null));
        exec.shutdownNow();
    }

    @Test
    void defaultRangeIs150to300() {
        ElectionTimer t = new ElectionTimer(() -> {},
                Executors.newSingleThreadScheduledExecutor());
        assertEquals(150, t.getMinMs());
        assertEquals(300, t.getMaxMs());
    }

    // ==================== 选举退避测试（P1）====================

    @Test
    void onElectionFailed_increasesConsecutiveFailures() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        assertEquals(0, t.getConsecutiveFailures());
        t.onElectionFailed();
        assertEquals(1, t.getConsecutiveFailures());
        t.onElectionFailed();
        assertEquals(2, t.getConsecutiveFailures());
    }

    @Test
    void onElectionFailed_capsAtMaxBackoffShift() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        // 连续失败超过 MAX_BACKOFF_SHIFT(2) 后应封顶
        for (int i = 0; i < 10; i++) {
            t.onElectionFailed();
        }
        assertEquals(2, t.getConsecutiveFailures(), "consecutiveFailures 应封顶在 MAX_BACKOFF_SHIFT=2");
    }

    @Test
    void nextTimeoutMs_doublesRangeAfterFailure() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        // 正常区间 [150, 300]
        for (int i = 0; i < 500; i++) {
            long v = t.nextTimeoutMs();
            assertTrue(v >= 150 && v <= 300, "正常区间应 [150,300]，实际: " + v);
        }
        t.onElectionFailed(); // shift=1 → [300, 600]
        for (int i = 0; i < 500; i++) {
            long v = t.nextTimeoutMs();
            assertTrue(v >= 300 && v <= 600, "1 次失败后区间应 [300,600]，实际: " + v);
        }
        t.onElectionFailed(); // shift=2 → [600, 1200]
        for (int i = 0; i < 500; i++) {
            long v = t.nextTimeoutMs();
            assertTrue(v >= 600 && v <= 1200, "2 次失败后区间应 [600,1200]，实际: " + v);
        }
    }

    @Test
    void onElectionSucceeded_resetsBackoff() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        t.onElectionFailed();
        t.onElectionFailed();
        assertEquals(2, t.getConsecutiveFailures());
        t.onElectionSucceeded();
        assertEquals(0, t.getConsecutiveFailures());
        // 区间恢复正常
        for (int i = 0; i < 500; i++) {
            long v = t.nextTimeoutMs();
            assertTrue(v >= 150 && v <= 300, "复位后区间应 [150,300]，实际: " + v);
        }
    }

    @Test
    void onElectionSucceeded_noopWhenAlreadyZero() {
        ElectionTimer t = new ElectionTimer(150, 300, () -> {},
                Executors.newSingleThreadScheduledExecutor());
        // 未失败过时调 onElectionSucceeded 不应有副作用
        t.onElectionSucceeded();
        assertEquals(0, t.getConsecutiveFailures());
    }
}
