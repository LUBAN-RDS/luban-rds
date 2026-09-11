package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-5（2026-09-11 mesh 审计）：快照安装是"替换"不是"合并"。
 * <p>
 * follower 落后/分歧后才收快照——store 中 leader 已删除、快照里不存在的 key 此前在
 * "全量追平"后继续存在（RdbPersistService.loadWithKeyCount 只逐键 set 不清空），
 * 重同步机制反而固化分歧，切主后幻影键对外可见。修复：安装前 flushAll 清空
 * （清空失败中止安装回 ACK false）。
 * </p>
 */
class SnapshotReplaceSemanticsTest {

    private static final String LEADER = "nodeLeader";
    private static final String FOLLOWER = "nodeFollower";
    private static final String DATA_DIR = "./target/test-data/snapshot-replace-test";

    private RdbPersistService persistService;
    private RdbSnapshotGenerator snapshotGenerator;
    private FollowerCaptureBus bus;

    /** Follower 视角捕获 bus（selfNodeId=FOLLOWER），ACK 帧被捕获。 */
    private static class FollowerCaptureBus extends MeshBusClient {
        final Map<String, List<MeshFrame>> sent = new HashMap<>();
        FollowerCaptureBus() {
            super(FOLLOWER, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(frame);
        }
    }

    @BeforeEach
    void setUp() {
        cleanDir();
        File dir = new File(DATA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        persistService = new RdbPersistService(DATA_DIR);
        snapshotGenerator = new RdbSnapshotGenerator(persistService, DATA_DIR);
        bus = new FollowerCaptureBus();
    }

    @AfterEach
    void tearDown() {
        if (persistService != null) {
            persistService.close();
        }
        cleanDir();
    }

    private void cleanDir() {
        File dir = new File(DATA_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    /** 生成快照 chunk 序列并投递给 follower 侧 SnapshotManager（重写边界为 snapIndex/snapTerm）。 */
    private void deliverFullSnapshot(SnapshotManager followerMgr,
                                     long snapIndex, long snapTerm) {
        // Leader 侧：生成 RDB chunk 序列（小 chunk 强制多帧）
        MemoryStore leaderStore = new DefaultMemoryStore();
        leaderStore.set(0, "kept1", "v1");
        leaderStore.set(0, "kept2", "v2");
        LeaderCaptureBus leaderBus = new LeaderCaptureBus();
        SnapshotManager leaderMgr = new SnapshotManager(LEADER, new MeshState(),
                leaderBus, leaderStore, snapshotGenerator,
                new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                16, SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null);
        assertTrue(leaderMgr.sendSnapshot(FOLLOWER) > 0);

        List<MeshFrame> leaderFrames = leaderBus.frames;
        assertTrue(leaderFrames.size() > 1, "应多帧");
        for (MeshFrame f : leaderFrames) {
            InstallSnapshotMessage orig = (InstallSnapshotMessage)
                    MeshRpcMessage.decode(MessageType.fromCode(f.getType()), f.getBody());
            InstallSnapshotMessage rewritten = new InstallSnapshotMessage(
                    6L, LEADER, snapTerm, snapIndex,
                    orig.getOffset(), orig.getData(), orig.isDone());
            followerMgr.handleInstallSnapshot(LEADER, rewritten);
        }
    }

    /** Leader 侧捕获 bus（收集 sendSnapshot 的全部帧）。 */
    private static class LeaderCaptureBus extends MeshBusClient {
        final List<MeshFrame> frames = new ArrayList<>();
        LeaderCaptureBus() {
            super(LEADER, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            frames.add(frame);
        }
    }

    @Test
    void installSnapshot_clearsDivergentKeys_notInSnapshot() {
        // Follower 本地存在分歧 key：ghost（leader 已删除）、stale（leader 已改值）
        MemoryStore followerStore = new DefaultMemoryStore();
        followerStore.set(0, "ghost", "should-disappear");
        followerStore.set(0, "stale", "old-value");

        MeshState followerState = new MeshState();
        followerState.currentTerm = 6;
        SnapshotManager followerMgr = new SnapshotManager(FOLLOWER, followerState, bus,
                followerStore, snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                16, SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null);

        deliverFullSnapshot(followerMgr, 10L, 5L);

        // P0-5：替换语义——快照不含的 key 必须消失，快照内 key 为 leader 值
        assertNull(followerStore.get(0, "ghost"), "分歧 key（快照未覆盖）安装后必须消失");
        assertNull(followerStore.get(0, "stale"), "分歧 key（快照已删除）安装后必须消失");
        assertEquals("v1", followerStore.get(0, "kept1"), "快照内 key 保留");
        assertEquals("v2", followerStore.get(0, "kept2"), "快照内 key 保留");

        // 安装成功：ACK success=true
        List<MeshFrame> acks = bus.sent.getOrDefault(LEADER, new ArrayList<>());
        assertFalse(acks.isEmpty(), "应回 ACK");
        AppendEntriesResponse ack = (AppendEntriesResponse)
                MeshRpcMessage.decode(MessageType.fromCode(acks.get(acks.size() - 1).getType()),
                        acks.get(acks.size() - 1).getBody());
        assertTrue(ack.isSuccess(), "安装成功 ACK success=true");
    }

    @Test
    void installSnapshot_flushFailure_abortsInstall() {
        // flushAll 抛异常的 store → 安装中止，回 ACK false
        MemoryStore failingStore = new DefaultMemoryStore() {
            @Override
            public void flushAll() {
                throw new IllegalStateException("disk full: flush failed");
            }
        };
        MeshState followerState = new MeshState();
        followerState.currentTerm = 6;
        SnapshotManager followerMgr = new SnapshotManager(FOLLOWER, followerState, bus,
                failingStore, snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                16, SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null);

        deliverFullSnapshot(followerMgr, 10L, 5L);

        List<MeshFrame> acks = bus.sent.getOrDefault(LEADER, new ArrayList<>());
        assertFalse(acks.isEmpty(), "清空失败也应回 ACK false（让 Leader 重发）");
        AppendEntriesResponse ack = (AppendEntriesResponse)
                MeshRpcMessage.decode(MessageType.fromCode(acks.get(acks.size() - 1).getType()),
                        acks.get(acks.size() - 1).getBody());
        assertFalse(ack.isSuccess(), "清空失败必须中止安装（ACK success=false）");
    }
}
