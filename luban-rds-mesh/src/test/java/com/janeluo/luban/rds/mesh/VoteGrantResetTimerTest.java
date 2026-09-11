package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.election.ElectionTimer;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogReplicator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q2（2026-09-11 mesh 审计 P2）：授出选票后复位选举定时器（落实
 * VoteDecision.resetElectionTimer）。denied / 过期 term 不复位（D2 语义保持）。
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class VoteGrantResetTimerTest {

    private static final String A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String B = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private static class CaptureBus extends MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) { /* no-op */ }
    }

    private static MeshNode newFollowerNode(MeshState state) {
        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
        state.currentTerm = 1;
        state.role = MeshRole.FOLLOWER;
        MeshNode node = new MeshNode(config, state, new CaptureBus(A),
                new RaftStateMachine(), null, null);
        return node;
    }

    private static ElectionTimer timerOf(MeshNode node) throws Exception {
        Field f = MeshNode.class.getDeclaredField("electionTimer");
        f.setAccessible(true);
        return (ElectionTimer) f.get(node);
    }

    private static void awaitRaftIdle(MeshNode node) throws Exception {
        Field f = MeshNode.class.getDeclaredField("raftExecutor");
        f.setAccessible(true);
        Object executor = f.get(node);
        java.lang.reflect.Method m = executor.getClass().getMethod("submit", Runnable.class);
        // 无法直接 awaitIdle——用短暂轮询代替（持久化回调是异步的）
        Thread.sleep(200);
    }

    @Test
    void grantResetsElectionTimer() throws Exception {
        MeshState state = new MeshState();
        MeshNode node = newFollowerNode(state);
        node.start();
        ElectionTimer timer = timerOf(node);
        long before = timer.getScheduleCount();

        // term=2 更高 + 日志更新 → 授予（正式投票）
        node.handleRequestVote(B, new com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage(
                2L, B, 5, 1, false));
        awaitRaftIdle(node);

        long after = timer.getScheduleCount();
        assertTrue(after > before, "授予选票后应复位选举定时器（scheduleCount 增加）");
        org.junit.jupiter.api.Assertions.assertEquals(2L, state.currentTerm);
        node.stop();
    }

    @Test
    void deniedVoteDoesNotResetTimer() throws Exception {
        MeshState state = new MeshState();
        // 本节点已有日志（index 1）→ 候选者日志（0,0）落后 → 拒绝
        state.appendEntry(new com.janeluo.luban.rds.mesh.core.LogEntry(
                1L, 1L, new byte[0], 0, null));
        MeshNode node = newFollowerNode(state);
        node.start();
        ElectionTimer timer = timerOf(node);
        long before = timer.getScheduleCount();

        // term 相同但候选者日志落后 → 拒绝，不复位
        node.handleRequestVote(B, new com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage(
                1L, B, 0, 0, false));
        awaitRaftIdle(node);

        assertEquals(before, timer.getScheduleCount(), "拒绝票不应复位定时器");
        node.stop();
    }

    private static void assertEquals(long expected, long actual, String msg) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, msg);
    }
}
