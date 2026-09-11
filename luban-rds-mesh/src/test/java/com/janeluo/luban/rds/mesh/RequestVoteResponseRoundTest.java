package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.election.VoteCollector;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-3（2026-09-11 mesh 审计）：投票响应轮次校验。
 * <p>
 * PreVote 阶段（term N）与正式选举（term N+1）此前共用响应结构与计票逻辑——PreVote 的
 * 迟到赞成票在新 collector 被计票，自己 1 票 + 1 张幽灵票 = "2/3 多数"当选 → 同 term 双主。
 * 修复：RequestVoteResponse 尾部追加 preVote/electionTerm，计票前三重校验。
 * </p>
 */
class RequestVoteResponseRoundTest {

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
    }

    private MeshConfig threeNodeConfig() {
        return MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(1000, 2000)  // 长，防测试期间定时器干扰
                .heartbeatIntervalMs(500)
                .build();
    }

    private void onRaftThread(MeshNode node, Runnable r) {
        try {
            Method submit = MeshNode.class.getDeclaredMethod("submitSync", Runnable.class);
            submit.setAccessible(true);
            submit.invoke(node, (Runnable) r);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 反射调用私有 runPreVote（与 MeshNodeTest 的 submitSync 反射惯例一致）。 */
    private void runPreVote(MeshNode node) {
        onRaftThread(node, () -> {
            try {
                Method m = MeshNode.class.getDeclaredMethod("runPreVote");
                m.setAccessible(true);
                m.invoke(node);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // ==================== 帧编解码 ====================

    @Test
    void frameRoundTrip_newFieldsPreserved() {
        RequestVoteResponse resp = new RequestVoteResponse(6L, true, false, 6L);
        RequestVoteResponse decoded = RequestVoteResponse.decode(resp.encode());
        assertEquals(6L, decoded.getTerm());
        assertTrue(decoded.isVoteGranted());
        assertFalse(decoded.isPreVote());
        assertEquals(6L, decoded.getElectionTerm());
    }

    @Test
    void oldVersionFrame_decodesWithConservativeDefaults() throws Exception {
        // 旧格式帧体：long term + boolean granted（9 字节，无尾部新字段）
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(6L);
            out.writeBoolean(true);
        }
        RequestVoteResponse decoded = RequestVoteResponse.decode(baos.toByteArray());
        assertEquals(6L, decoded.getTerm());
        assertTrue(decoded.isVoteGranted());
        // 默认值：electionTerm=0 与任何 currentTerm 不等 → 调用方保守丢弃
        assertFalse(decoded.isPreVote());
        assertEquals(0L, decoded.getElectionTerm());
    }

    // ==================== 计票三重校验 ====================

    @Test
    void preVoteStage_responseWithWrongStageOrRound_isDropped() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 6;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            runPreVote(node);
            VoteCollector collector = node.getCurrentVoteCollector();
            assertTrue(collector != null && collector.isPreVote(), "PreVote collector 在途");
            assertEquals(1, collector.getGranted(), "仅自己一票");

            // ① 阶段不匹配（real 阶段响应落入 preVote collector）→ 丢弃
            node.handleRequestVoteResponse(B, new RequestVoteResponse(6L, true, false, 6L));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "阶段不匹配不计票");

            // ② electionTerm 不匹配（旧轮次 PreVote 迟到票）→ 丢弃
            node.handleRequestVoteResponse(B, new RequestVoteResponse(6L, true, true, 5L));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "旧轮次不计票");

            // ③ term 不匹配 → 丢弃
            node.handleRequestVoteResponse(B, new RequestVoteResponse(5L, true, true, 5L));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "旧 term 不计票");

            // ④ 旧版本帧（electionTerm=0）→ 保守丢弃
            node.handleRequestVoteResponse(B, new RequestVoteResponse(6L, true));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "旧版本帧不计票");
        } finally {
            node.stop();
        }
    }

    @Test
    void fullFlow_preVoteWins_realElectionIsolatedFromLatePreVoteVotes() {
        CaptureBus bus = new CaptureBus();
        MeshState state = new MeshState();
        state.currentTerm = 6;
        MeshNode node = new MeshNode(threeNodeConfig(), state, bus);
        node.start();
        try {
            // PreVote 阶段（term 6）：B 的合法 PreVote 票 → 多数派（1+1=2/3）→ 进入正式选举
            runPreVote(node);
            node.handleRequestVoteResponse(B, new RequestVoteResponse(6L, true, true, 6L));

            VoteCollector real = node.getCurrentVoteCollector();
            assertTrue(real != null && !real.isPreVote(), "已切换到正式选举 collector");
            assertEquals(7L, state.currentTerm, "正式选举自增 term");
            assertEquals(1, real.getGranted(), "正式选举重新只计自己一票");

            // 正式选举阶段（term 7）：PreVote 的迟到票（term 6）→ term 校验丢弃
            node.handleRequestVoteResponse(C, new RequestVoteResponse(6L, true, true, 6L));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "PreVote 迟到票（term 不等）不计入正式选举");

            // 同 term 但阶段是 PreVote 的迟到票 → 阶段校验丢弃
            node.handleRequestVoteResponse(C, new RequestVoteResponse(7L, true, true, 6L));
            assertEquals(1, node.getCurrentVoteCollector().getGranted(), "PreVote 阶段票（阶段不匹配）不计入正式选举");

            // C 的合法正式票（term=7, electionTerm=7, preVote=false）→ 多数派 → 当选
            node.handleRequestVoteResponse(C, new RequestVoteResponse(7L, true, false, 7L));
            assertEquals(MeshRole.LEADER, node.getRole(), "合法多数派票应完成当选");
        } finally {
            node.stop();
        }
    }
}
