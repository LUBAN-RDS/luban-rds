package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failover 偏移量选举单元测试（C8 / 任务 3.20）
 * <p>
 * 覆盖：
 * <ul>
 *   <li>broadcastAuthRequest 携带真实 replicationOffset（替换硬编码 0L）</li>
 *   <li>onAuthRequest 同纪元首投即定：首个候选获票，后续候选即使 offset 更大也被拒绝</li>
 *   <li>单候选 sanity check</li>
 * </ul>
 * </p>
 */
class FailoverOffsetElectionTest {

    static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
    static final String NODE_ID_4 = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";
    static final String NODE_ID_5 = "e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0";
    static final long NODE_TIMEOUT = 15000L;

    ClusterConfig config;
    SlotManager slotManager;
    ClusterStateManager stateManager;
    ClusterBusClient busClient;
    FailoverManager failoverManager;
    ReplicationLifecycleListener mockListener;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
        mockListener = Mockito.mock(ReplicationLifecycleListener.class);
        // 默认返回 0，单测按需 stub
        Mockito.when(mockListener.getReplicationOffset()).thenReturn(0L);
        failoverManager.setReplicationLifecycleListener(mockListener);
    }

    // ==================== 3.17: broadcastAuthRequest 携带真实 offset ====================

    @Test
    @DisplayName("broadcastAuthRequest 携带 listener 返回的真实复制偏移量（替换硬编码 0L）")
    void testBroadcastAuthRequestIncludesReplicationOffset() throws Exception {
        // 安装 mock listener 返回固定 offset
        Mockito.when(mockListener.getReplicationOffset()).thenReturn(12345L);

        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // 额外 2 个可用 master 满足 quorum（canFailover 要求多数 master 可用）
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();  // 进入 REQUESTING
        // 等退避窗口（gracePeriod=0 + N-11 固定 500ms 基数 + rank*1000 + jitter ≤ 500ms）+ 余量
        Thread.sleep(1100);
        failoverManager.tick();  // 退避到期，广播 AUTH_REQUEST

        ArgumentCaptor<FailoverAuthRequestMessage> captor =
                ArgumentCaptor.forClass(FailoverAuthRequestMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());

        FailoverAuthRequestMessage req = captor.getValue();
        assertEquals(NODE_ID_2, req.getSenderNodeId());
        assertEquals(12345L, req.getReplicationOffset(),
                "AUTH_REQUEST 应携带 listener 返回的真实复制偏移量");
        assertTrue(config.getCurrentEpoch() >= 1);
    }

    @Test
    @DisplayName("未装配 listener 时 AUTH_REQUEST 偏移量回退为 0（向后兼容）")
    void testBroadcastAuthRequestFallsBackToZeroOffset() throws Exception {
        // 覆盖 setUp 注入：显式重置为 NoOp（未装配复制组件的场景）
        failoverManager.setReplicationLifecycleListener(null);

        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();
        Thread.sleep(1100);
        failoverManager.tick();

        ArgumentCaptor<FailoverAuthRequestMessage> captor =
                ArgumentCaptor.forClass(FailoverAuthRequestMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());

        assertEquals(0L, captor.getValue().getReplicationOffset(),
                "未装配 listener 时偏移量应回退为 0");
    }

    // ==================== 3.19: onAuthRequest 首投即定 ====================

    @Test
    @DisplayName("同纪元首候选获票，后续候选即使 offset 更大也被拒绝（首投即定，不撤票）")
    void testVoterPrefersLargerOffset_firstVoteWins() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 两个候选 slave，其各自 master 均已 FAIL
        ClusterNode failedMasterA = createMasterNode(NODE_ID_3, 7002);
        failedMasterA.addState(ClusterNodeState.FAIL);
        ClusterNode failedMasterB = createMasterNode(NODE_ID_4, 7003);
        failedMasterB.addState(ClusterNodeState.FAIL);
        ClusterNode candidateA = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);  // offset=100
        ClusterNode candidateB = createSlaveNode(NODE_ID_5, 7004, NODE_ID_4);  // offset=200
        config.addNode(me);
        config.addNode(failedMasterA);
        config.addNode(failedMasterB);
        config.addNode(candidateA);
        config.addNode(candidateB);
        config.setMyNodeId(NODE_ID_1);

        // 候选 A（offset=100）先到达 -> 获票
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(
                NODE_ID_2, 5L, 10L, 100L));
        Mockito.verify(busClient, Mockito.times(1)).broadcast(Mockito.any(FailoverAuthAckMessage.class));

        Mockito.clearInvocations(busClient);

        // 候选 B（offset=200，更大）同纪元到达 -> 首投即定，拒绝（不撤票）
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(
                NODE_ID_5, 5L, 10L, 200L));

        Mockito.verifyNoInteractions(busClient);
        assertEquals(10L, config.getCurrentEpoch(), "纪元应已追平到 reqEpoch");
    }

    @Test
    @DisplayName("单候选 sanity check：有效 AUTH_REQUEST 携带 offset 仍正常获票")
    void testVoterSingleCandidateVotesByOffset() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(
                NODE_ID_2, 5L, 10L, 9999L));

        ArgumentCaptor<FailoverAuthAckMessage> captor =
                ArgumentCaptor.forClass(FailoverAuthAckMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());

        FailoverAuthAckMessage ack = captor.getValue();
        assertEquals(NODE_ID_1, ack.getSenderNodeId(), "ACK 发送者应为投票 master");
        assertEquals(10L, ack.getVoteEpoch(), "ACK voteEpoch 应匹配请求纪元");
        assertEquals(10L, config.getCurrentEpoch());
    }

    @Test
    @DisplayName("重复同候选同纪元请求触发幂等重发 ACK")
    void testIdempotentAckResendWithOffset() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                NODE_ID_2, 5L, 10L, 500L);
        failoverManager.onAuthRequest(req);
        failoverManager.onAuthRequest(req);  // 重复

        Mockito.verify(busClient, Mockito.times(2))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建主节点。默认分配槽位 0——N-14 起投票者必须持槽（对齐 Redis
     * "myself->numslots == 0 无投票权"），本文件多数测试以 master 身份投票，需满足该前置。
     */
    private ClusterNode createMasterNode(String id, int port) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.MASTER);
        n.addSlot(0);
        return n;
    }

    private ClusterNode createSlaveNode(String id, int port, String masterId) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.SLAVE);
        n.setMasterNodeId(masterId);
        return n;
    }
}
