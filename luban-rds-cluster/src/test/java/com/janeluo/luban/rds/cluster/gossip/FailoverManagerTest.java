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

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailoverManager 单元测试
 * 覆盖：状态机流转、退避广播、投票授权、胜选提升、FailoverResult 收敛
 */
class FailoverManagerTest {

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

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
    }

    // ==================== 状态机基础 ====================

    @Test
    @DisplayName("初始状态为 IDLE")
    void testInitialState() {
        assertEquals(FailoverState.IDLE, failoverManager.getState());
    }

    @Test
    @DisplayName("slave 检测到 master FAIL 进入 REQUESTING 态")
    void testSlaveEntersRequestingWhenMasterFail() {
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

        failoverManager.tick();

        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        Mockito.verifyNoInteractions(busClient);  // 退避窗口内未广播
    }

    @Test
    @DisplayName("非 slave 节点 tick 不触发选举")
    void testMasterDoesNotTriggerElection() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.tick();

        assertEquals(FailoverState.IDLE, failoverManager.getState());
        Mockito.verifyNoInteractions(busClient);
    }

    @Test
    @DisplayName("master 未 FAIL 时 slave 不触发选举")
    void testSlaveNoElectionWhenMasterAlive() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(master);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();

        assertEquals(FailoverState.IDLE, failoverManager.getState());
    }

    @Test
    @DisplayName("退避到期后广播 AUTH_REQUEST 并自增 epoch")
    void testBroadcastAfterBackoff() throws Exception {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // 额外 2 个可用 master 满足 quorum
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();  // 进入 REQUESTING
        Mockito.verifyNoInteractions(busClient);

        // 等退避窗口（gracePeriod=0 + jitter ≤ 500ms）+ 余量
        Thread.sleep(600);
        failoverManager.tick();  // 退避到期，广播

        assertTrue(config.getCurrentEpoch() >= 1);
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverAuthRequestMessage.class));
    }

    // ==================== 投票授权（master 侧） ====================

    @Test
    @DisplayName("master 首次收到有效 AUTH_REQUEST 投票授权")
    void testMasterVotesForFirstRequest() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 候选 NODE_ID_2 是 slave，其 master NODE_ID_3 已 FAIL
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                NODE_ID_2, 5L, 10L, 0L);
        failoverManager.onAuthRequest(req);

        assertEquals(10L, config.getCurrentEpoch());
        Mockito.verify(busClient).broadcast(Mockito.argThat(
                m -> m instanceof FailoverAuthAckMessage
                        && NODE_ID_1.equals(((FailoverAuthAckMessage) m).getSenderNodeId())));
    }

    @Test
    @DisplayName("重复同纪元请求触发幂等重发 ACK")
    void testIdempotentAckResend() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L);
        failoverManager.onAuthRequest(req);
        failoverManager.onAuthRequest(req);  // 重复

        Mockito.verify(busClient, Mockito.times(2))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    @Test
    @DisplayName("本纪元已投他 slave 则拒绝")
    void testRejectOtherSlaveInSameEpoch() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 两个候选 slave，其 master 均已 FAIL
        ClusterNode failedMaster1 = createMasterNode(NODE_ID_3, 7002);
        failedMaster1.addState(ClusterNodeState.FAIL);
        ClusterNode failedMaster2 = createMasterNode(NODE_ID_4, 7003);
        failedMaster2.addState(ClusterNodeState.FAIL);
        ClusterNode candidate2 = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidate5 = createSlaveNode(NODE_ID_5, 7004, NODE_ID_4);
        config.addNode(me);
        config.addNode(failedMaster1);
        config.addNode(failedMaster2);
        config.addNode(candidate2);
        config.addNode(candidate5);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.clearInvocations(busClient);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 10L, 0L));

        Mockito.verifyNoInteractions(busClient);
    }

    @Test
    @DisplayName("过期纪元请求被拒绝")
    void testRejectStaleEpoch() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_1);
        config.setCurrentEpoch(20L);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

        Mockito.verifyNoInteractions(busClient);
    }

    // ==================== 胜选提升（候选侧） ====================

    @Test
    @DisplayName("收到过半 master 授权后胜选提升并广播 RESULT")
    void testWinElectionAndPromote() {
        // 3 master 集群，需要 2 票（masterCount/2+1 = 2）
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // m1 持有槽位 0-100
        for (int i = 0; i <= 100; i++) m1.addSlot(i);
        m1.addState(ClusterNodeState.FAIL);

        config.addNode(m1);
        config.addNode(m2);
        config.addNode(m3);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        // 进入候选态
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        // 模拟已广播 AUTH_REQUEST（设置 electionEpoch=1，使 ACK 的 voteEpoch 校验通过）
        failoverManager.prepareRequestedStateForTest(1L);

        // 收到 2 个 master 授权（≥ masterCount/2+1 = 2）
        // FailoverAuthAckMessage 构造: (senderNodeId, configEpoch, currentEpoch, voteEpoch)
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m2.getNodeId(), 1L, 1L, 1L));
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m3.getNodeId(), 1L, 1L, 1L));

        // 验证：me 已是 master、继承槽位、m1 降级 slave、广播 RESULT
        assertTrue(me.isMaster());
        assertFalse(me.isSlave());
        assertEquals(101, me.getSlotCount());
        assertFalse(m1.isMaster());
        assertTrue(m1.isSlave());
        assertEquals(me.getNodeId(), m1.getMasterNodeId());
        assertTrue(config.getCurrentEpoch() >= 1);
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverResultMessage.class));
    }

    @Test
    @DisplayName("授权票数未过半不触发胜选")
    void testNoWinWithoutMajority() {
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        m1.addState(ClusterNodeState.FAIL);

        config.addNode(m1);
        config.addNode(m2);
        config.addNode(m3);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();
        // 仅 1 票（需 2 票）
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m2.getNodeId(), 1L, 1L, 1L));

        assertFalse(me.isMaster(), "未过半不应提升");
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
    }

    // ==================== FailoverResult 收敛 ====================

    @Test
    @DisplayName("onFailoverResult 收到结果后 winner 提权、原 master 降级、槽位转移")
    void testHandleFailoverResult() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);

        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);

        FailoverResultMessage msg = new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited);
        failoverManager.onFailoverResult(msg);

        assertTrue(winner.isMaster());
        assertFalse(winner.isFail());
        assertEquals(5L, winner.getConfigEpoch());
        assertEquals(100, winner.getSlotCount());
        assertFalse(oldMaster.isMaster());
        assertTrue(oldMaster.isSlave());
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());
        assertEquals(5L, config.getCurrentEpoch());
    }

    @Test
    @DisplayName("旧纪元 FailoverResult 被忽略")
    void testIgnoreStaleResult() {
        config.setCurrentEpoch(10L);
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(winner);

        BitSet inherited = new BitSet();
        inherited.set(0, 10);

        failoverManager.onFailoverResult(new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited));  // 5 < 10

        assertFalse(winner.isMaster());
        assertEquals(10L, config.getCurrentEpoch());
    }

    // ==================== 手动故障转移 ====================

    @Test
    @DisplayName("手动 performManualFailover 不触发选举状态机、广播 RESULT（C9）")
    void testManualFailoverBypassesStateMachine() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i < 100; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);

        failoverManager.performManualFailover(slave, master);

        assertEquals(FailoverState.IDLE, failoverManager.getState());
        assertTrue(slave.isMaster());
        assertTrue(master.isSlave());
        assertEquals(100, slave.getSlotCount());
        // C9: 手动 failover 广播 FailoverResult 使全网拓扑收敛
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverResultMessage.class));
    }

    // ==================== 辅助方法 ====================

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
