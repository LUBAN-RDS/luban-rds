package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.election.LeaseManager;
import com.janeluo.luban.rds.mesh.election.VoteCollector;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshNode} 选举/心跳/PreVote 协调的单元测试。
 * <p>
 * 不依赖真实网络或定时器触发：用 {@link CaptureBus} 捕获发出的帧，直接调用 MeshNode 的 handler
 * 方法（包级可见）模拟入站消息，并手动驱动 PreVote → 正式选举 → Leader 流程。
 * </p>
 * <p>节点间真实选举（多节点、定时器、多线程）集成测试留到阶段 13。</p>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshNodeTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    /** 捕获所有发出的帧（不发真实网络）。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, MeshFrame> sent = new HashMap<>();
        CaptureBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.put(targetNodeId, frame);
        }
        void clear() { sent.clear(); }
        MeshRpcMessage decode(String to) {
            MeshFrame f = sent.get(to);
            return MeshRpcMessage.decode(MessageType.fromCode(f.getType()), f.getBody());
        }
    }

    private MeshConfig threeNodeConfig() {
        return MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(50, 100)   // 短，便于测试
                .heartbeatIntervalMs(30)
                .leaseDurationMs(200)
                .build();
    }

    private MeshFrame frame(String from, MessageType type, MeshRpcMessage msg) {
        return new MeshFrame(from, type.getCode(), msg.encode());
    }

    private void invokeOnRaftThread(MeshNode node, Runnable r) {
        try {
            java.lang.reflect.Method submit = MeshNode.class.getDeclaredMethod("submitSync", Runnable.class);
            submit.setAccessible(true);
            submit.invoke(node, (Runnable) () -> {
                try { r.run(); } catch (Exception e) { throw new RuntimeException(e); }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void start_nodeIsFollowerWithLeaseInvalid() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            assertEquals(MeshRole.FOLLOWER, node.getRole());
            assertFalse(node.isLeader());
            assertFalse(node.lease().isValid(System.currentTimeMillis()), "未续租应失效");
        } finally {
            node.stop();
        }
    }

    @Test
    void preVote_doesNotIncrementTerm_andSendsPreVoteRequest() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // 模拟 election timeout → runPreVote（在 raft 线程上）
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });

            assertEquals(5L, node.getCurrentTerm(), "PreVote 不应自增 term");
            // 应向 B、C 发 preVote=true 的 RequestVote
            RequestVoteMessage toB = (RequestVoteMessage) bus.decode(B);
            RequestVoteMessage toC = (RequestVoteMessage) bus.decode(C);
            assertTrue(toB.isPreVote(), "应发 PreVote 请求");
            assertTrue(toC.isPreVote());
            assertEquals(5L, toB.getTerm(), "PreVote term 应为当前 term");
            assertEquals(A, toB.getCandidateId());
        } finally {
            node.stop();
        }
    }

    @Test
    void preVote_rejected_doesNotStartRealElection() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // 启动 PreVote
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            VoteCollector preVote = node.getCurrentVoteCollector();
            assertTrue(preVote.isPreVote());

            // B、C 都拒（模拟 PreVote 未获多数派）
            bus.clear();
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(5L, false)));
            node.onMessage(C, frame(C, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(5L, false)));
            node.awaitIdle();

            assertEquals(5L, node.getCurrentTerm(), "PreVote 失败不应自增 term");
            assertEquals(MeshRole.FOLLOWER, node.getRole(), "应保持 FOLLOWER");
            assertFalse(node.isLeader());
        } finally {
            node.stop();
        }
    }

    @Test
    void preVoteMajority_thenRealElection_thenLeader() {
        // 完整路径：PreVote 多数派 → 正式选举多数派 → Leader
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // 1. PreVote
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            assertEquals(5L, node.getCurrentTerm());

            // 2. B 同意 PreVote（自己+B = 多数派）
            bus.clear();
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(5L, true)));
            node.awaitIdle();

            // PreVote 多数派 → 触发正式选举（term+1=6）
            assertEquals(6L, node.getCurrentTerm(), "正式选举应自增 term");
            assertEquals(MeshRole.CANDIDATE, node.getRole());

            // 应发了正式 RequestVote（preVote=false）
            RequestVoteMessage realToB = (RequestVoteMessage) bus.decode(B);
            assertFalse(realToB.isPreVote(), "正式选举应发 preVote=false");
            assertEquals(6L, realToB.getTerm());

            // 3. B 同意正式投票（自己+B=2 多数派）
            bus.clear();
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(6L, true)));
            node.awaitIdle();

            // 4. 应转 Leader
            assertEquals(MeshRole.LEADER, node.getRole());
            assertTrue(node.isLeader());
            assertEquals(A, node.getLeaderId());
            // Leader 启动心跳 → 向 B、C 发空 AppendEntries
            AppendEntriesMessage hbToB = (AppendEntriesMessage) bus.decode(B);
            assertEquals(6L, hbToB.getTerm());
            assertEquals(A, hbToB.getLeaderId());
            assertEquals(0, hbToB.getEntries().size(), "心跳 entries 应为空");
        } finally {
            node.stop();
        }
    }

    @Test
    void appendEntries_higherTerm_downgradesAndResets() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 3;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // 收到更高 term 的 AppendEntries（leader=B, term=5）
            node.onMessage(B, frame(B, MessageType.APPEND_ENTRIES,
                    new AppendEntriesMessage(5L, B, 0L, 0L, java.util.Collections.emptyList(), 0L)));
            node.awaitIdle();

            assertEquals(5L, node.getCurrentTerm());
            assertEquals(MeshRole.FOLLOWER, node.getRole());
            assertEquals(B, node.getLeaderId());

            // 应回复 success=true
            AppendEntriesResponse resp = (AppendEntriesResponse) bus.decode(B);
            assertTrue(resp.isSuccess());
            assertEquals(5L, resp.getTerm());
        } finally {
            node.stop();
        }
    }

    @Test
    void heartbeat_majorityAck_refreshesLease() throws Exception {
        // 让 node 成为 Leader（走完整 PreVote→正式选举），然后模拟 B 对心跳的 success 响应 → 续租
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            // PreVote B 同意
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(5L, true)));
            node.awaitIdle();
            // 正式 B 同意
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(6L, true)));
            node.awaitIdle();
            assertTrue(node.isLeader());
            assertFalse(node.lease().isValid(System.currentTimeMillis()), "Leader 初始租约应失效");

            // 模拟 B 对心跳的 success 响应（matchIndex 上报）
            bus.clear();
            // 手动触发一次心跳广播（在 raft 线程上调私有 broadcastHeartbeat）
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("broadcastHeartbeat");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });

            // B 回 success，matchIndex=0
            node.onMessage(B, frame(B, MessageType.APPEND_ENTRIES_RESP,
                    new AppendEntriesResponse(6L, true, 0L)));
            node.awaitIdle();

            // 自己 + B success = 2 >= majority(2) → 续租
            assertTrue(node.lease().isValid(System.currentTimeMillis()), "多数派 ACK 应续租");
        } finally {
            node.stop();
        }
    }

    @Test
    void lease_invalidate_onLosingLeadership() throws Exception {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // 成为 Leader 并续租
            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(5L, true)));
            node.awaitIdle();
            node.onMessage(B, frame(B, MessageType.REQUEST_VOTE_RESP, new RequestVoteResponse(6L, true)));
            node.awaitIdle();
            assertTrue(node.isLeader());

            invokeOnRaftThread(node, () -> {
                try {
                    java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("broadcastHeartbeat");
                    m.setAccessible(true);
                    m.invoke(node);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            node.onMessage(B, frame(B, MessageType.APPEND_ENTRIES_RESP,
                    new AppendEntriesResponse(6L, true, 0L)));
            node.awaitIdle();
            assertTrue(node.lease().isValid(System.currentTimeMillis()));

            // 收到更高 term AppendEntries → 降级 follower → 租约应失效
            node.onMessage(C, frame(C, MessageType.APPEND_ENTRIES,
                    new AppendEntriesMessage(9L, C, 0L, 0L, java.util.Collections.emptyList(), 0L)));
            node.awaitIdle();

            assertEquals(MeshRole.FOLLOWER, node.getRole());
            assertFalse(node.lease().isValid(System.currentTimeMillis()), "失去 Leader 身份后租约应失效");
        } finally {
            node.stop();
        }
    }

    @Test
    void config_majorityAndTotals() {
        MeshConfig cfg = threeNodeConfig();
        assertEquals(3, cfg.getTotalNodes());
        assertEquals(2, cfg.majority());
        assertEquals(2, cfg.getOtherNodeIds().size());
        assertEquals(200, cfg.getLeaseDurationMs());
        assertEquals(30, cfg.getHeartbeatIntervalMs());
    }

    @Test
    void leaseManager_defaultDurationIs1200() {
        assertEquals(1200, new LeaseManager().getLeaseDurationMs());
    }

    @Test
    void awaitValid_returnsWithinTimeout() throws Exception {
        // 已续租时 awaitValid 立即返回
        LeaseManager lm = new LeaseManager(600);
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        long start = System.nanoTime();
        boolean ok = lm.awaitValid(1000);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertTrue(ok);
        assertTrue(ms < 50, "已有效时应立即返回，耗时=" + ms);
    }
}
