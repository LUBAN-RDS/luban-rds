package com.janeluo.luban.rds.mesh.election;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q10（2026-09-11 mesh 审计 P2）：ElectionTimer 首次调度 slot 竞态——
 * 回调先于 slot 发布执行时不得被误判过期丢弃（选举超时静默丢失）。
 * <p>高频启动短超时定时器：每个定时器都必须在窗口内触发（修复前概率性丢失）。</p>
 */
class ElectionTimerFirstSlotRaceTest {

    @Test
    void everyStartedTimerFiresDespiteTightScheduling() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            int rounds = 500;
            for (int i = 0; i < rounds; i++) {
                CountDownLatch fired = new CountDownLatch(1);
                // 1ms 固定超时：最大化 fire-before-publish 竞态窗口
                ElectionTimer timer = new ElectionTimer(1, 1, fired::countDown, scheduler);
                timer.start();
                assertTrue(fired.await(1, TimeUnit.SECONDS),
                        "第 " + i + " 轮定时器未触发（超时回调被竞态丢弃）");
                timer.stop();
            }
        } finally {
            scheduler.shutdownNow();
        }
    }

    @Test
    void rapidResetsStillDeliverTimeout() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            CountDownLatch fired = new CountDownLatch(1);
            ElectionTimer timer = new ElectionTimer(30, 30, fired::countDown, scheduler);
            timer.start();
            // 200 次快速 reset：cancel 优先于 fire，最终必须有一次有效触发
            for (int i = 0; i < 200; i++) {
                timer.reset();
                Thread.sleep(0, 50);
            }
            assertTrue(fired.await(2, TimeUnit.SECONDS), "reset 风暴后超时回调必须触发");
            timer.stop();
        } finally {
            scheduler.shutdownNow();
        }
    }
}
