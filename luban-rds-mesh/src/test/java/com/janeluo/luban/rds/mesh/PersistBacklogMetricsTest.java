package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-16（2026-09-11 mesh 审计）：persistExecutor 积压可观测。
 * <p>
 * fsync 慢于写速率时 durableIndex 停滞 → commit/apply 无限滞后且此前完全不可观测。
 * 修复：timer 线程周期采样队列深度，超过阈值（1000）WARN（附 durableIndex 滞后量）。
 * 不设硬拒绝——丢弃/回灌持久化任务都会破坏正确性。
 * </p>
 */
class PersistBacklogMetricsTest {

    private MeshNode node;
    private CountDownLatch release;

    @BeforeEach
    void setUp() {
        MeshConfig config = MeshConfig.builder("nodeA")
                .addPeer("nodeB", "127.0.0.1:11001")
                .electionTimeout(5000, 6000)
                .heartbeatIntervalMs(1000)
                .build();
        release = new CountDownLatch(1);
        node = new MeshNode(config, new MeshState(), new NoopBus(), new RaftStateMachine());
        node.start();
    }

    @AfterEach
    void tearDown() {
        release.countDown();
        node.stop();
    }

    private static class NoopBus extends MeshBusClient {
        NoopBus() {
            super("nodeA", new MeshBusHandler());
        }
    }

    /** 反射取 persistExecutor。 */
    private ScheduledExecutorService persistExecutor() throws Exception {
        java.lang.reflect.Field f = MeshNode.class.getDeclaredField("persistExecutor");
        f.setAccessible(true);
        return (ScheduledExecutorService) f.get(node);
    }

    /** 反射调用采样（包级测试钩子 runPersistBacklogSampleForTest）。 */
    private void runSample() throws Exception {
        java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPersistBacklogSampleForTest");
        m.setAccessible(true);
        m.invoke(node);
    }

    @Test
    void backlogAboveThreshold_triggersWarn() throws Exception {
        long warnsBefore = readWarnCount();

        // 塞入超过阈值（1000）的阻塞任务，使队列深度越线
        ScheduledExecutorService executor = persistExecutor();
        for (int i = 0; i < 1002; i++) {
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        // 等第一个任务真正开始跑（队列保留 1000+）
        Thread.sleep(100);

        runSample();
        long warnsAfter = readWarnCount();
        assertTrue(warnsAfter > warnsBefore, "积压超阈值应产生 WARN 告警");
    }

    @Test
    void backlogBelowThreshold_noWarn() throws Exception {
        long warnsBefore = readWarnCount();
        runSample();
        assertEquals(warnsBefore, readWarnCount(), "正常积压不应产生告警噪声");
    }

    private long readWarnCount() throws Exception {
        java.lang.reflect.Field f = MeshNode.class.getDeclaredField("persistBacklogWarnCount");
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(node)).get();
    }
}
