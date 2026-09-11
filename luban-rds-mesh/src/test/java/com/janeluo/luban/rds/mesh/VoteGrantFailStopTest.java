package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-1（2026-09-11 mesh 审计）：投票授予持久化 fail-stop。
 * <p>
 * 此前 persistStateSafe 吞掉 votedFor 落盘失败（仅 WARN 后照发 granted）——内存已投票、
 * 响应已发出、磁盘未写；崩溃恢复后同 term 二次投票 → 双 Leader。修复：持久化成功才发
 * granted，失败改发 denied；同时消除 raft 线程同步 fsync（P0-6 放大器）。
 * </p>
 */
class VoteGrantFailStopTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    /** 捕获发出的 REQUEST_VOTE_RESP 帧。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, List<MeshFrame>> sent = new HashMap<>();
        CaptureBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(frame);
        }
        RequestVoteResponse lastVoteResponseTo(String to) {
            List<MeshFrame> frames = sent.get(to);
            if (frames == null || frames.isEmpty()) {
                return null;
            }
            MeshFrame f = frames.get(frames.size() - 1);
            return (RequestVoteResponse) MeshRpcMessage.decode(
                    MessageType.fromCode(f.getType()), f.getBody());
        }
    }

    private CaptureBus bus;
    private MeshNode node;
    private MeshState state;

    @BeforeEach
    void setUp() {
        bus = new CaptureBus();
        state = new MeshState();
        state.currentTerm = 5;
        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(5000, 6000)  // 测试期间不触发本地选举
                .heartbeatIntervalMs(1000)
                .build();
        node = new MeshNode(config, state, bus);
        node.start();
    }

    @AfterEach
    void tearDown() {
        node.stop();
    }

    /** 等待 persistExecutor 完成在途任务（投票响应在持久化回调内发送）。 */
    private void awaitPersistDone() throws Exception {
        java.lang.reflect.Field f = MeshNode.class.getDeclaredField("persistExecutor");
        f.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService executor =
                (java.util.concurrent.ScheduledExecutorService) f.get(node);
        executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    @Test
    void realVote_persistFails_respondsDenied() throws Exception {
        node.setPersistHook(() -> {
            throw new RuntimeException("disk full");
        });
        // 正式 RequestVote（term 6 > 5，日志合格）→ 内存上决定授予
        node.onMessage(B, new MeshFrame(B, MessageType.REQUEST_VOTE.getCode(),
                new RequestVoteMessage(6L, B, 10L, 4L, false).encode()));
        node.awaitIdle();
        awaitPersistDone();

        RequestVoteResponse resp = bus.lastVoteResponseTo(B);
        assertTrue(resp != null, "落盘失败也必须回复（denied）");
        assertFalse(resp.isVoteGranted(), "P1-1：落盘失败不得发 granted");
        assertEquals(6L, resp.getTerm());
        // 内存 votedFor 已设置（本 term 内不再授予他人——保守正确）
        assertEquals(B, state.votedFor);
    }

    @Test
    void realVote_persistSucceeds_respondsGranted() throws Exception {
        node.setPersistHook(() -> { });  // 正常落盘
        node.onMessage(B, new MeshFrame(B, MessageType.REQUEST_VOTE.getCode(),
                new RequestVoteMessage(6L, B, 10L, 4L, false).encode()));
        node.awaitIdle();
        awaitPersistDone();

        RequestVoteResponse resp = bus.lastVoteResponseTo(B);
        assertTrue(resp != null && resp.isVoteGranted(), "落盘成功应发 granted");
        assertEquals(6L, resp.getElectionTerm(), "granted 响应携带轮次标识");
    }

    @Test
    void preVote_persistFails_stillGrantedImmediately() throws Exception {
        node.setPersistHook(() -> {
            throw new RuntimeException("disk full");
        });
        // PreVote 探测不持久化（不占投票权），落盘 hook 失败不影响
        node.onMessage(B, new MeshFrame(B, MessageType.REQUEST_VOTE.getCode(),
                new RequestVoteMessage(6L, B, 10L, 4L, true).encode()));
        node.awaitIdle();
        awaitPersistDone();

        RequestVoteResponse resp = bus.lastVoteResponseTo(B);
        assertTrue(resp != null && resp.isVoteGranted(), "PreVote 不受持久化约束");
        assertTrue(resp.isPreVote());
        assertEquals(null, state.votedFor, "PreVote 不设置 votedFor");
    }
}
