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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 手动 failover 广播测试（C9/3.25）
 * <p>
 * 验证：
 * <ul>
 *   <li>手动 failover（FORCE/TAKEOVER 语义，统一经 {@code performManualFailover}）广播 FailoverResult</li>
 *   <li>原 master configEpoch 对齐自动路径</li>
 *   <li>自动路径仅广播一次（不重复广播）</li>
 * </ul>
 * </p>
 * <p>
 * 驱动方式：手动路径直接调用 {@code performManualFailover}；
 * 自动路径沿用 {@link FailoverManagerTest} 的选举胜选模式——
 * {@code tick()} 进入 REQUESTING 后用 {@code prepareRequestedStateForTest} 注入选举纪元，
 * 再投递过半 {@link FailoverAuthAckMessage} 触发 {@code performFailoverAndBroadcast}。
 * </p>
 */
class ManualFailoverBroadcastTest {

    static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
    static final String NODE_ID_4 = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";
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

    // ==================== 手动 failover 广播 ====================

    @Test
    @DisplayName("手动 failover 广播 FailoverResult，winner=被提升 slave")
    void testManualFailoverBroadcastsFailoverResult() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i < 100; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);
        long epochBefore = config.getCurrentEpoch();

        failoverManager.performManualFailover(slave, master);

        // 角色已切换
        assertTrue(slave.isMaster());
        assertTrue(master.isSlave());
        assertEquals(100, slave.getSlotCount());

        // 广播恰好一次 FailoverResult
        ArgumentCaptor<FailoverResultMessage> captor =
                ArgumentCaptor.forClass(FailoverResultMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());
        FailoverResultMessage msg = captor.getValue();
        assertEquals(slave.getNodeId(), msg.getWinnerNodeId(), "winner 应为被提升的 slave");
        assertEquals(slave.getNodeId(), msg.getSenderNodeId(), "sender 应为被提升的 slave");
        assertTrue(msg.getNewConfigEpoch() > epochBefore, "epoch 应已自增");
        assertEquals(msg.getNewConfigEpoch(), config.getCurrentEpoch(),
                "广播的 epoch 应为最终 currentEpoch");
        assertEquals(100, msg.getInheritedSlots().cardinality(), "应继承原 master 的 100 槽位");
    }

    @Test
    @DisplayName("手动 failover 对齐原 master configEpoch（C9/3.22）")
    void testManualFailoverAlignsOldMasterConfigEpoch() {
        // 集群基线 epoch=3，原 master configEpoch=3
        config.setCurrentEpoch(3L);
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.setConfigEpoch(3L);
        for (int i = 0; i < 50; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);

        failoverManager.performManualFailover(slave, master);

        long currentEpoch = config.getCurrentEpoch();
        assertEquals(4L, currentEpoch, "currentEpoch 应自增 1");
        // 自动路径对齐：旧 master configEpoch 也提升到 currentEpoch
        assertEquals(currentEpoch, master.getConfigEpoch(),
                "原 master configEpoch 应对齐 currentEpoch（对齐自动路径）");
        assertEquals(currentEpoch, slave.getConfigEpoch(),
                "新 master configEpoch 应对齐 currentEpoch");
    }

    @Test
    @DisplayName("手动 failover 不进入选举状态机")
    void testManualFailoverDoesNotEnterElectionStateMachine() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i < 10; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);

        failoverManager.performManualFailover(slave, master);

        assertEquals(FailoverState.IDLE, failoverManager.getState(),
                "手动 failover 不应进入选举状态机");
    }

    @Test
    @DisplayName("手动 TAKEOVER 语义同样广播 FailoverResult（C9）")
    void testTakeoverManualFailoverBroadcasts() {
        // TAKEOVER/FORCE/普通 三种模式在 ClusterCommandHandler 中均委托
        // FailoverManager.performManualFailover(myNode, masterNode)，行为一致。
        // 此处直接验证 manager 层广播，覆盖 TAKEOVER 接管语义。
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i < 30; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);

        failoverManager.performManualFailover(slave, master);

        ArgumentCaptor<FailoverResultMessage> captor =
                ArgumentCaptor.forClass(FailoverResultMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());
        assertEquals(slave.getNodeId(), captor.getValue().getWinnerNodeId());
        assertEquals(30, captor.getValue().getInheritedSlots().cardinality());
    }

    // ==================== 自动路径单次广播 ====================

    @Test
    @DisplayName("自动路径胜选仅广播一次 FailoverResult（不重复广播，C9/3.23）")
    void testAutomaticFailoverDoesNotDoubleBroadcast() {
        // 3 master 集群，需 2 票胜选
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
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
        // 注入选举纪元，使 ACK 的 voteEpoch 校验通过
        failoverManager.prepareRequestedStateForTest(1L);

        // 投递过半授权（masterCount/2+1 = 2）
        // candidateId 必须是 me(NODE_ID_2)，否则 onAuthAck 候选绑定校验会忽略
        failoverManager.onAuthAck(new FailoverAuthAckMessage(m2.getNodeId(), 1L, 1L, 1L, NODE_ID_2));
        failoverManager.onAuthAck(new FailoverAuthAckMessage(m3.getNodeId(), 1L, 1L, 1L, NODE_ID_2));

        // 胜选后状态回 IDLE（ELECTED 为瞬态）
        assertEquals(FailoverState.IDLE, failoverManager.getState());
        assertTrue(me.isMaster(), "自动胜选后 me 应为 master");

        // 自动路径仅广播一次 FailoverResult（移除重复广播后）
        Mockito.verify(busClient, Mockito.times(1))
                .broadcast(Mockito.any(FailoverResultMessage.class));
    }

    // ==================== 重复广播安全（3.24 验证） ====================

    @Test
    @DisplayName("重复应用相同 FailoverResult 幂等、不破坏拓扑（3.24）")
    void testOnFailoverResultIdempotentOnReplay() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);
        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);
        FailoverResultMessage msg = new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited);

        // 首次应用
        failoverManager.onFailoverResult(msg);
        assertTrue(winner.isMaster());
        assertEquals(5L, winner.getConfigEpoch());
        assertEquals(100, winner.getSlotCount());
        assertTrue(oldMaster.isSlave());
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());

        // 重复回放：拓扑应保持一致，不破坏
        failoverManager.onFailoverResult(msg);
        assertTrue(winner.isMaster());
        assertEquals(5L, winner.getConfigEpoch());
        assertEquals(100, winner.getSlotCount());
        assertTrue(oldMaster.isSlave(), "重复应用后旧 master 仍为 slave");
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());
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
