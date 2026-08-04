package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.BitSet;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N-12/N-14/N-15 投票侧闸门测试（对齐 Redis clusterSendFailoverAuthIfNeeded）：
 * <ul>
 *   <li>N-14：投票者必须持槽（myself-&gt;numslots &gt; 0，cluster.c "nodeIsSlave(myself) ||
 *       myself-&gt;numslots == 0" 无投票权）+ voted_time 2×nodeTimeout 冷却（按 master 维度）</li>
 *   <li>N-15：候选 configEpoch 与槽位 owner 比较裁决（候选声明纪元低于 owner 纪元 → 陈旧候选拒绝）</li>
 *   <li>N-12：votesCast 陈旧条目不拒新纪元首投（epoch 抬升清理 + 请求侧防御过滤）</li>
 * </ul>
 */
class FailoverVotingGateTest {

    static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";  // 投票者
    static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";  // 候选 A
    static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";  // 被接管 master M3
    static final String NODE_ID_4 = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";  // 被接管 master M4
    static final String NODE_ID_5 = "e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0";  // 候选 B
    static final long NODE_TIMEOUT = 15000L;

    ClusterConfig config;
    SlotManager slotManager;
    ClusterStateManager stateManager;
    ClusterBusClient busClient;
    FailoverManager failoverManager;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
    }

    // ==================== N-14：投票者持槽要求 ====================

    @Test
    @DisplayName("N-14：未持槽的 master 无投票权（对齐 Redis myself->numslots == 0）")
    void testVoterWithoutSlotsDoesNotVote() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 不分配任何槽位
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

        Mockito.verifyNoInteractions(busClient);
        assertEquals(0L, config.getLastVoteEpoch(), "未持槽投票者不应记录投票纪元");
    }

    @Test
    @DisplayName("N-14：持槽 master 正常投票（对照）")
    void testVoterWithSlotsVotes() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertEquals(10L, config.getLastVoteEpoch());
    }

    // ==================== N-14：voted_time 冷却 ====================

    @Test
    @DisplayName("N-14：投票后 2×nodeTimeout 冷却期内同 master 新纪元候选不获票")
    void testVotedTimeCooldownBlocksSameMasterVote() {
        // 小 nodeTimeout（50ms）缩短冷却期（2×50=100ms），测试免长等待
        FailoverManager fm = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, 50L, 0L);
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidateA = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidateB = createSlaveNode(NODE_ID_5, 7004, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidateA);
        config.addNode(candidateB);
        config.setMyNodeId(NODE_ID_1);

        // 首投（epoch=10）→ 授权，记录 voted_time
        fm.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertTrue(fm.getLastVoteTimeForTest(NODE_ID_3) > 0L, "投票后应记录 voted_time");

        Mockito.clearInvocations(busClient);
        // 冷却期内新纪元（epoch=11）同 master 新候选 → 拒绝（即使纪元合法）
        fm.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 11L, 0L));
        Mockito.verifyNoInteractions(busClient);

        // 模拟冷却期流逝 → 放行
        fm.setLastVoteTimeForTest(NODE_ID_3, 0L);
        fm.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 11L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertEquals(11L, config.getLastVoteEpoch(), "冷却期后新纪元投票应推进 lastVoteEpoch");
    }

    @Test
    @DisplayName("N-14：不同 master 的选举互不阻塞（voted_time 按 master 维度记录）")
    void testVoteCooldownDoesNotBlockOtherMaster() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMasterA = createMasterNode(NODE_ID_3, 7002);
        failedMasterA.addState(ClusterNodeState.FAIL);
        ClusterNode failedMasterB = createMasterNode(NODE_ID_4, 7003);
        failedMasterB.addState(ClusterNodeState.FAIL);
        ClusterNode candidateA = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidateC = createSlaveNode(NODE_ID_5, 7004, NODE_ID_4);
        config.addNode(me);
        config.addNode(failedMasterA);
        config.addNode(failedMasterB);
        config.addNode(candidateA);
        config.addNode(candidateC);
        config.setMyNodeId(NODE_ID_1);

        // 投 A（master M3，epoch=10）
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.clearInvocations(busClient);
        // 同纪元他 master 的候选 C（epoch=10）→ 被 lastVoteEpoch 闸门拒绝（纪元已投）
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 10L, 0L));
        Mockito.verifyNoInteractions(busClient);

        // 新纪元（11）不同 master 的候选 C → 冷却不阻塞（不同 master 维度），N-12 防御过滤放行
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 11L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertEquals(11L, config.getLastVoteEpoch());
    }

    // ==================== N-12：votesCast 陈旧条目清理 ====================

    @Test
    @DisplayName("N-12：gossip 抬升 currentEpoch 后新纪元首投不被旧 votesCast 误拒")
    void testStaleVotesCastDoesNotBlockNewEpochFirstVote() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidateA = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidateB = createSlaveNode(NODE_ID_5, 7004, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidateA);
        config.addNode(candidateB);
        config.setMyNodeId(NODE_ID_1);

        // 首投 epoch=10
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertEquals(10L, config.getLastVoteEpoch());

        Mockito.clearInvocations(busClient);
        // 模拟 gossip/结果消息抬升 currentEpoch 到 15（votesCast 未随之清理的旧行为场景）
        config.setCurrentEpoch(15L);
        // 模拟 voted_time 冷却已过（N-14 闸门与本场景正交）
        failoverManager.setLastVoteTimeForTest(NODE_ID_3, 0L);

        // 新纪元（15）首投 → 修复前被 votesCast={A:10} 误拒（选举停滞），修复后放行
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 15L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
        assertEquals(15L, config.getLastVoteEpoch(), "新纪元投票后 lastVoteEpoch 应推进");
    }

    @Test
    @DisplayName("N-12：onClusterEpochRaised 清理 votesCast（GossipProtocol epoch 抬升回调）")
    void testOnClusterEpochRaisedClearsStaleVotesCast() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));

        Mockito.clearInvocations(busClient);
        // 模拟 GossipProtocol PING/PONG/MEET 抬升纪元后的回调（votesCast 清理）
        failoverManager.onClusterEpochRaised();
        failoverManager.setLastVoteTimeForTest(NODE_ID_3, 0L);
        // 同候选更高纪元请求（15）→ 旧记录已清 → 重新投票
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 15L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    @Test
    @DisplayName("N-12：PING 抬升 currentEpoch 时触发 votesCast 清理（GossipProtocol 接线）")
    void testPingEpochRaiseTriggersVotesCastCleanup() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        // PING 发送方（已有节点）
        ClusterNode sender = createMasterNode(NODE_ID_4, 7003);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.addNode(sender);
        config.setMyNodeId(NODE_ID_1);

        GossipProtocol gossip = new GossipProtocol(config, busClient, NODE_TIMEOUT);
        gossip.setFailoverManager(failoverManager);

        // 首投 epoch=10
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));

        Mockito.clearInvocations(busClient);
        // 发送方 PING 携带更高 currentEpoch=20 → setEpochIfGreater 返回 true → 清理 votesCast
        PingMessage ping = new PingMessage(NODE_ID_4, System.currentTimeMillis());
        ping.setSenderCurrentEpoch(20L);
        ping.setSenderConfigEpoch(5L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        ping.setSenderSlots(new BitSet());
        gossip.handlePing(ping);

        assertEquals(20L, config.getCurrentEpoch(), "PING 应抬升 currentEpoch");
        // 冷却期流逝后新纪元首投应放行（votesCast 已被接线清理）
        failoverManager.setLastVoteTimeForTest(NODE_ID_3, 0L);
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 20L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    // ==================== N-15：候选 configEpoch 与槽位 owner 裁决 ====================

    @Test
    @DisplayName("N-15：候选声明纪元低于槽位 owner 纪元时拒绝投票（陈旧候选）")
    void testStaleCandidateClaimEpochRejected() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        // 被接管 master 持槽 0-99，configEpoch=5
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.setConfigEpoch(5L);
        for (int i = 0; i <= 99; i++) {
            failedMaster.addSlot(i);
            config.setSlotOwner(i, NODE_ID_3);
        }
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        // 声明纪元 3 < owner 纪元 5 → 陈旧候选，拒绝（修复前仅记日志、照常投票）
        FailoverAuthRequestMessage staleReq = new FailoverAuthRequestMessage(NODE_ID_2, 3L, 10L, 0L);
        staleReq.setClaimedSlots(failedMaster.getSlots());
        failoverManager.onAuthRequest(staleReq);
        Mockito.verifyNoInteractions(busClient);
        assertEquals(0L, config.getLastVoteEpoch(), "陈旧候选不应获票");

        // 声明纪元 5 == owner 纪元 → 放行（正常选举）
        FailoverAuthRequestMessage freshReq = new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L);
        freshReq.setClaimedSlots(failedMaster.getSlots());
        failoverManager.onAuthRequest(freshReq);
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    @Test
    @DisplayName("N-15：旧版消息无槽位声明时回退本地视图，陈旧候选仍被拒绝")
    void testStaleCandidateFallbackToLocalMasterSlots() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addSlot(100);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.setConfigEpoch(5L);
        for (int i = 0; i <= 99; i++) {
            failedMaster.addSlot(i);
            config.setSlotOwner(i, NODE_ID_3);
        }
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        // 不设置 claimedSlots（旧版消息）→ 回退候选 master 的本地槽位视图 → 陈旧候选仍被拒绝
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 3L, 10L, 0L));
        Mockito.verifyNoInteractions(busClient);

        // 声明纪元与 owner 一致 → 放行
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    @Test
    @DisplayName("N-15 wire：AUTH_REQUEST 声明槽位线编解码往返 + 旧 24 字节消息兼容")
    void testClaimedSlotsWireRoundTripAndLegacyCompat() {
        FailoverAuthRequestMessage original = new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 123L);
        BitSet slots = new BitSet();
        slots.set(0, 100);
        original.setClaimedSlots(slots);

        byte[] fullBody = original.encodeBody();
        FailoverAuthRequestMessage decoded = new FailoverAuthRequestMessage();
        decoded.decodeBody(fullBody);
        assertEquals(5L, decoded.getConfigEpoch());
        assertEquals(10L, decoded.getCurrentEpoch());
        assertEquals(123L, decoded.getReplicationOffset());
        assertEquals(slots, decoded.getClaimedSlots(), "声明槽位经线编解码后必须保持一致");

        // 旧版消息：截断尾部声明槽位字段（24 字节体）→ 解码不报错且无声明槽位
        byte[] legacyBody = new byte[24];
        System.arraycopy(fullBody, 0, legacyBody, 0, 24);
        FailoverAuthRequestMessage decodedOld = new FailoverAuthRequestMessage();
        decodedOld.decodeBody(legacyBody);
        assertEquals(10L, decodedOld.getCurrentEpoch(), "旧消息前 24 字节字段应正常解码");
        assertNull(decodedOld.getClaimedSlots(),
                "旧消息无声明槽位字段，应为 null（投票侧回退本地视图）");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建主节点（不分配槽位——需投票的测试显式 addSlot）。
     * 与 FailoverManagerTest 的辅助方法不同：本文件多数测试需要"无槽 master"对照场景。
     */
    private ClusterNode createMasterNode(String id, int port) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.MASTER);
        return n;
    }

    private ClusterNode createSlaveNode(String id, int port, String masterId) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.SLAVE);
        n.setMasterNodeId(masterId);
        return n;
    }
}
