package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.RdbDataLoader;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-4（2026-09-11 mesh 审计）：INSTALL_SNAPSHOT 任期抬升统一走 becomeFollower 转换路径。
 * <p>
 * 此前 SnapshotManager 直接改 currentTerm/votedFor/leaderId 而不降级——仍自认 Leader 的
 * 旧节点收到更高 term 快照后心跳任务继续跑，以新 term 广播心跳 → 同 term 双 Leader 窗口；
 * term/votedFor 修改也不落盘。修复：dispatch 层统一 becomeFollower（停心跳/失效租约/
 * 取消收集器/落盘/复位定时器），每个合法 chunk 复位选举定时器（大快照传输防误选）。
 * </p>
 */
class InstallSnapshotTermUpTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";
    private static final String NEW_LEADER = B;

    private static final String DATA_DIR = "./target/test-data/install-snapshot-termup-test";

    private RdbPersistService persistService;
    private CaptureBus bus;
    private MeshNode node;
    private MeshState state;
    private SnapshotManager snapshotManager;

    /** 捕获所有发出的帧。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, List<MeshFrame>> sent = new HashMap<>();
        CaptureBus(String self) {
            super(self, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(frame);
        }
    }

    @BeforeEach
    void setUp() {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        persistService = new RdbPersistService(DATA_DIR);
        RdbSnapshotGenerator snapshotGenerator =
                new RdbSnapshotGenerator(persistService, DATA_DIR);
        bus = new CaptureBus(A);
        state = new MeshState();
        state.currentTerm = 5;
        // 模拟"仍自认 Leader 的旧节点"（未经选举流程直置角色，聚焦任期裁决路径）
        state.role = MeshRole.LEADER;
        state.leaderId = A;
        state.votedFor = A;

        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(2000, 4000)   // 长，防测试期间定时器干扰
                .heartbeatIntervalMs(500)
                .build();

        node = new MeshNode(config, state, bus, new RaftStateMachine());
        MemoryStore store = new DefaultMemoryStore();
        snapshotManager = new SnapshotManager(A, state, bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR),
                DATA_DIR);
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
        // 宽松清理：done=false 的在途会话临时文件在 Windows 上可能仍被流持有，删除失败不致命
        File dir = new File(DATA_DIR);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    private void deliverSnapshotChunk(long term, String from, long offset, boolean done) {
        InstallSnapshotMessage msg = new InstallSnapshotMessage(
                term, from, 6L, 10L, offset, new byte[]{1, 2, 3}, done);
        MeshFrame frame = new MeshFrame(from, MessageType.INSTALL_SNAPSHOT.getCode(), msg.encode());
        node.onMessage(from, frame);
        awaitIdle();
    }

    /** 反射调用 MeshNode 包级 awaitIdle（与 MeshNodeTest 反射惯例一致）。 */
    private void awaitIdle() {
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("awaitIdle");
            m.setAccessible(true);
            m.invoke(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void higherTermFirstChunk_downgradesSelfClaimedLeader() {
        // 自认 Leader（term 5）收到 term 7 的快照首 chunk → 必须经 becomeFollower 降级
        deliverSnapshotChunk(7L, NEW_LEADER, 0L, false);

        assertEquals(MeshRole.FOLLOWER, state.role, "P0-4：必须降级为 FOLLOWER（此前保持 LEADER 双主）");
        assertEquals(7L, state.currentTerm, "任期抬升到 7");
        assertNull(state.votedFor, "新任期清空投票");
        assertEquals(NEW_LEADER, state.leaderId, "认新 Leader");
    }

    @Test
    void higherTermChunk_persistsTermViaPersistHook() {
        // 注入记录型 persistHook，断言 term 抬升触发持久化（此前 term 修改不落盘）
        StringBuilder persisted = new StringBuilder();
        node.setPersistHook(() -> persisted.append("installSnapshot-term-up;"));
        deliverSnapshotChunk(7L, NEW_LEADER, 0L, false);
        assertEquals(true, persisted.toString().contains("installSnapshot-term-up"),
                "term 抬升必须触发 persistHook 落盘，实际: " + persisted);
    }

    @Test
    void sameTermChunk_followerRoleUnchanged_sessionAccepted() {
        // follower（term 7）收到同 term 快照 chunk：不重复降级，会话正常累积
        deliverSnapshotChunk(7L, NEW_LEADER, 0L, false);
        assertEquals(MeshRole.FOLLOWER, state.role);
        assertEquals(7L, state.currentTerm);

        // 同 term 第二个 chunk：offset 连续（3 字节后 offset=3）
        deliverSnapshotChunk(7L, NEW_LEADER, 3L, false);
        assertEquals(6L, snapshotManager.getReceivedBytes(), "两个 chunk（各 3 字节）应累积到 6");
    }
}
