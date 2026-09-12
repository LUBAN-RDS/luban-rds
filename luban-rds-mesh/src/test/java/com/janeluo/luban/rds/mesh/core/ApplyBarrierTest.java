package com.janeluo.luban.rds.mesh.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ApplyBarrierTest {

    @Test
    void awaitApplied_returnsImmediately_whenAlreadyApplied() throws Exception {
        MeshState state = new MeshState();
        state.lastApplied = 10;
        ApplyBarrier barrier = new ApplyBarrier(state);

        long t0 = System.nanoTime();
        assertTrue(barrier.awaitApplied(10, 5_000));
        assertTrue((System.nanoTime() - t0) < TimeUnit.MILLISECONDS.toNanos(200),
                "已追平必须立即返回，不应进入等待");
    }

    @Test
    void awaitApplied_returnsFalse_onTimeout() throws Exception {
        MeshState state = new MeshState();
        state.lastApplied = 1;
        ApplyBarrier barrier = new ApplyBarrier(state);
        assertFalse(barrier.awaitApplied(5, 100));
    }

    @Test
    void awaitApplied_wakesUp_onSignal() throws Exception {
        MeshState state = new MeshState();
        state.lastApplied = 1;
        ApplyBarrier barrier = new ApplyBarrier(state);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                result.set(barrier.awaitApplied(5, 5_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);                    // 确保已进入等待
        state.lastApplied = 5;                // 模拟 apply 推进
        barrier.signalApplied();
        waiter.join(3_000);

        assertTrue(result.get(), "被 signal 唤醒后应返回 true");
    }

    @Test
    void awaitApplied_wakesUp_onSnapshotJump() throws Exception {
        MeshState state = new MeshState();
        state.lastApplied = 0;
        ApplyBarrier barrier = new ApplyBarrier(state);
        AtomicBoolean result = new AtomicBoolean(false);

        Thread waiter = new Thread(() -> {
            try {
                result.set(barrier.awaitApplied(500, 5_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        Thread.sleep(100);
        state.lastApplied = 500;              // 快照安装：lastApplied 跳到 lastIncludedIndex
        barrier.signalApplied();
        waiter.join(3_000);

        assertTrue(result.get(), "快照跳变 signal 后应返回 true（不得吃满超时）");
    }
}
