package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.replication.SnapshotManager;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.RdbDataLoader;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-8（2026-09-11 mesh 审计）：周期快照调度接线。
 * <p>
 * takePeriodicSnapshotIfNeeded 实现完整但此前零生产调用——WAL/内存 log 无界增长，
 * 写路径成本随运行时长线性上升（"越跑越慢"的代码根因）。修复：MeshNode 定时检查并投递到
 * raft 线程执行（与 apply 串行保证 dump 一致性），log 达阈值时快照截断。
 * </p>
 */
class PeriodicSnapshotScheduleTest {

    private static final String A = "nodeA";

    @TempDir
    Path tempDir;

    private RdbPersistService persistService;
    private MeshNode node;
    private MeshState state;
    private SnapshotManager snapshotManager;

    @BeforeEach
    void setUp() {
        persistService = new RdbPersistService(tempDir.toString());
        state = new MeshState();
        state.currentTerm = 1;

        MeshConfig config = MeshConfig.builder(A)
                .electionTimeout(50, 100)
                .heartbeatIntervalMs(200)
                .build();
        MemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        NoopBus bus = new NoopBus();
        node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, store);
        snapshotManager = new SnapshotManager(A, state, bus, store,
                new RdbSnapshotGenerator(persistService, tempDir.toString()),
                new RdbDataLoader(persistService, tempDir.toString()), tempDir.toString(),
                SnapshotManager.DEFAULT_CHUNK_SIZE_BYTES,
                3L,  // 阈值压到 3 条（测试加速）
                null, null);
        node.setSnapshotManager(snapshotManager);
        node.start();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
        if (persistService != null) {
            try {
                persistService.close();
            } catch (Exception ignore) {
                // ignore
            }
        }
    }

    private static class NoopBus extends MeshBusClient {
        NoopBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            // 单节点无 peer
        }
    }

    private byte[] setFrame(String k, String v) {
        return ("*3\r\n$3\r\nSET\r\n$" + k.length() + "\r\n" + k + "\r\n$"
                + v.length() + "\r\n" + v + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void snapshotCheckScheduled_andTriggersAtThreshold() throws Exception {
        // 1. 等单节点当选（no-op@1）
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && node.getRole() != MeshRole.LEADER) {
            Thread.sleep(20);
        }
        assertEquals(MeshRole.LEADER, node.getRole());

        // 2. 定时检查任务已注册（调度接线）
        assertNotNull(readField(node, "snapshotCheckTask"), "start 后应注册周期快照检查任务");

        // 3. propose 3 条写（no-op@1 + 3 = 4 条 log，越过阈值 3）
        for (int i = 1; i <= 3; i++) {
            CompletableFuture<byte[]> f = node.propose(setFrame("k" + i, "v" + i), 0, null);
            f.get(5, TimeUnit.SECONDS);
        }
        assertTrue(state.log.size() >= 3, "log 应已越过快照阈值");

        // 4. 手动驱动一次周期检查（等价 timer 线程投递的 raft 任务；避免 30s 间隔的测试等待）
        invokeRunSnapshotCheckOnce();

        // 5. 快照触发：log 截断到阈值以下、lastIncludedIndex 推进
        deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && state.lastIncludedIndex <= 0) {
            Thread.sleep(20);
        }
        assertTrue(state.lastIncludedIndex > 0, "周期快照应触发并推进 lastIncludedIndex");
        assertTrue(state.log.size() < 3, "log 应被截断: " + state.log.size());
        assertTrue(new File(tempDir.toFile(), "dump.rdb").exists(), "dump.rdb 应落盘");
    }

    @Test
    void belowThreshold_noOp() throws Exception {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline && node.getRole() != MeshRole.LEADER) {
            Thread.sleep(20);
        }
        assertEquals(MeshRole.LEADER, node.getRole());
        // no-op@1 + 1 条写 = 2 < 3 阈值
        node.propose(setFrame("k", "v"), 0, null).get(5, TimeUnit.SECONDS);
        invokeRunSnapshotCheckOnce();
        Thread.sleep(100);
        assertEquals(0L, state.lastIncludedIndex, "未达阈值不应快照");
    }

    private static Object readField(Object target, String name) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 反射调用 MeshNode.runSnapshotCheckOnce（包级测试钩子）。 */
    private void invokeRunSnapshotCheckOnce() {
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runSnapshotCheckOnce");
            m.setAccessible(true);
            m.invoke(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
