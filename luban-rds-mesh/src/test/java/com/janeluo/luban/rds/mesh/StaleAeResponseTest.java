package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.replication.LogReplicator;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-2（2026-09-11 mesh 审计）：过期 AppendEntries 响应丢弃 + 租约续租加 matchIndex 前提。
 * <p>
 * 旧任期迟到 success 曾推高 matchIndex/nextIndex 并刷新租约——租约虚高（旧数据可读窗口）+
 * 复制跳段（靠后续 NACK 自愈）。修复：resp.term != currentTerm 直接丢弃；
 * 续租要求 resp.matchIndex >= commitIndex（follower 确认已含全部已提交数据）。
 * </p>
 */
class StaleAeResponseTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    private MeshNode node;
    private MeshState state;
    private AtomicLong leaseRefreshCount;

    @BeforeEach
    void setUp() {
        state = new MeshState();
        state.currentTerm = 7;
        state.role = MeshRole.LEADER;
        state.leaderId = A;

        leaseRefreshCount = new AtomicLong();

        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(5000, 6000)
                .heartbeatIntervalMs(1000)
                .build();

        node = new MeshNode(config, state, new CaptureBus(), new RaftStateMachine(),
                new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore()),
                new DefaultMemoryStore());
        // 直接对 replicator 注入计数续租（绕过 MeshNode 内部 lease 装配，便于观测前提判定）
        LogReplicator replicator = node.getReplicator();
        replicator.setLeaseRefresher(leaseRefreshCount::incrementAndGet);
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 预置日志：index 1..10（term 7），commitIndex=10
        for (long i = 1; i <= 10; i++) {
            state.appendEntry(new LogEntry(7L, i, new byte[]{1}, 0, null));
        }
        state.commitIndex = 10;
        node.start();
    }

    @AfterEach
    void tearDown() {
        node.stop();
    }

    private static class CaptureBus extends MeshBusClient {
        CaptureBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) {
            // no-op：本测试只驱动响应处理方向
        }
    }

    @Test
    void staleTermResponse_dropped_noMatchIndexNoLease() {
        // B 的 matchIndex 已是 10（追平）；来一张 term=5 的迟到 success（matchIndex=15）
        node.handleAppendEntriesResponse(B, new AppendEntriesResponse(5L, true, 15L));
        node.awaitIdle();

        assertEquals(0L, node.getReplicator().getMatchIndex(B),
                "旧任期响应不得推高 matchIndex");
        assertEquals(0L, leaseRefreshCount.get(), "旧任期响应不得续租");
    }

    @Test
    void sameTermResponse_behindCommitIndex_noLeaseRefresh() {
        // C 落后（matchIndex=4 < commitIndex=10）→ 同 term success 不续租
        node.handleAppendEntriesResponse(C, new AppendEntriesResponse(7L, true, 4L));
        node.awaitIdle();

        assertEquals(4L, node.getReplicator().getMatchIndex(C), "matchIndex 正常推进");
        assertEquals(0L, leaseRefreshCount.get(), "追赶中的 ACK（matchIndex<commitIndex）不得续租");
    }

    @Test
    void sameTermResponse_caughtUp_leaseRefreshed() {
        // 空闲/追平语义：matchIndex=10 >= commitIndex=10 → 续租（防空闲集群回归）
        node.handleAppendEntriesResponse(B, new AppendEntriesResponse(7L, true, 10L));
        node.awaitIdle();

        assertEquals(10L, node.getReplicator().getMatchIndex(B), "追平后 matchIndex 推进");
        assertTrue(leaseRefreshCount.get() >= 1, "追平的 ACK 应续租（空闲集群不回归）");
    }
}
