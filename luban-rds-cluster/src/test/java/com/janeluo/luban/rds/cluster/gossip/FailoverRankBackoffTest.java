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
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P1-6 回归测试：候选侧 rank 退避 + replica-validity-factor + replOffset wire-format 传播。
 */
class FailoverRankBackoffTest {

    private static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    private static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
    private static final String MASTER_ID = "dddddddddddddddddddddddddddddddddddddddd";
    private static final long NODE_TIMEOUT = 15000L;

    private ClusterConfig config;
    private SlotManager slotManager;
    private ClusterStateManager stateManager;
    private ClusterBusClient busClient;
    private FailoverManager failoverManager;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        // slaveValidityFactor=0 禁用有效性校验（多数测试默认）
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L, 0L);
        ReplicationLifecycleListener listener = Mockito.mock(ReplicationLifecycleListener.class);
        Mockito.when(listener.getReplicationOffset()).thenReturn(0L);
        failoverManager.setReplicationLifecycleListener(listener);
    }

    @Test
    @DisplayName("P1-6：未装配复制（offset 全 0）时 rank=0，退化为 gracePeriod+jitter")
    void testRankZeroWhenNoReplicationOffset() {
        setupFailoverScenario(0L, 0L, 0L);
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        assertEquals(0, failoverManager.getComputedRankForTest(), "offset 全 0 时 rank 应为 0");
    }

    @Test
    @DisplayName("P1-6：offset 最大的 slave rank=0")
    void testRankZeroForFreshestSlave() {
        // 本节点 offset=300，兄弟 offset=100/200 → 本节点最新鲜 → rank=0
        setupFailoverScenario(300L, 100L, 200L);
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        assertEquals(0, failoverManager.getComputedRankForTest(),
                "offset 最大的 slave 应 rank=0");
    }

    @Test
    @DisplayName("P1-6：offset 落后的 slave rank>0（按 offset 降序排名）")
    void testRankPositiveForStaleSlave() {
        // 本节点 offset=100，兄弟 offset=200/300 → 两个兄弟都比本节点大 → rank=2
        setupFailoverScenario(100L, 200L, 300L);
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        assertEquals(2, failoverManager.getComputedRankForTest(),
                "offset 最小的 slave 应 rank=2（两个兄弟 offset 更大）");
    }

    @Test
    @DisplayName("P1-6：offset 居中的 slave rank=1")
    void testRankOneForMiddleSlave() {
        // 本节点 offset=200，兄弟 offset=100/300 → 一个兄弟更大 → rank=1
        setupFailoverScenario(200L, 100L, 300L);
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        assertEquals(1, failoverManager.getComputedRankForTest(),
                "offset 居中的 slave 应 rank=1");
    }

    @Test
    @DisplayName("P1-6：validity-factor>0 且数据过旧时不发起选举")
    void testValidityFactorBlocksTooStaleSlave() {
        // 重建 failoverManager，启用 validity 校验（factor=1，允许落后 nodeTimeout*1=15000）
        FailoverManager fm = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L, 1L);
        ReplicationLifecycleListener listener = Mockito.mock(ReplicationLifecycleListener.class);
        Mockito.when(listener.getReplicationOffset()).thenReturn(0L);
        fm.setReplicationLifecycleListener(listener);

        // 本节点 offset=0，兄弟 offset=100000（远超允许落后量 15000）→ 数据过旧，不发起
        setupFailoverScenario(0L, 100000L, 50000L);
        fm.tick();
        assertEquals(FailoverState.IDLE, fm.getState(),
                "数据过旧的 slave 在 validity-factor 校验下不应发起选举");
    }

    @Test
    @DisplayName("P1-6：validity-factor>0 但数据足够新鲜时正常发起选举")
    void testValidityFactorAllowsFreshEnoughSlave() {
        FailoverManager fm = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L, 1L);
        ReplicationLifecycleListener listener = Mockito.mock(ReplicationLifecycleListener.class);
        Mockito.when(listener.getReplicationOffset()).thenReturn(0L);
        fm.setReplicationLifecycleListener(listener);

        // 本节点 offset=14999，兄弟 offset=15000（落后 1，远小于允许量 15000）→ 允许
        setupFailoverScenario(14999L, 15000L, 14000L);
        fm.tick();
        assertEquals(FailoverState.REQUESTING, fm.getState(),
                "数据足够新鲜的 slave 应正常发起选举");
    }

    @Test
    @DisplayName("P1-6 wire-format：PingMessage replOffset 尾部追加，向后兼容（截断旧消息解码默认 0）")
    void testPingMessageReplOffsetBackwardCompat() {
        PingMessage ping = new PingMessage(NODE_ID_1, System.currentTimeMillis());
        ping.setSenderReplicationOffset(987654321L);

        byte[] full = ping.encodeBody();
        // 解码完整消息
        PingMessage decoded = new PingMessage();
        decoded.decodeBody(full);
        assertEquals(987654321L, decoded.getSenderReplicationOffset());

        // 截断尾部 8 字节（模拟旧版本消息），解码应不报错且 replOffset 默认 0
        byte[] truncated = new byte[full.length - 8];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        PingMessage decodedOld = new PingMessage();
        decodedOld.decodeBody(truncated);
        assertEquals(0L, decodedOld.getSenderReplicationOffset(),
                "旧消息无 replOffset 字段时应默认 0");
    }

    @Test
    @DisplayName("P1-6 wire-format：GossipNodeInfo replOffset 尾部追加，向后兼容")
    void testGossipNodeInfoReplOffsetBackwardCompat() {
        GossipNodeInfo info = new GossipNodeInfo(NODE_ID_2);
        info.setIp("127.0.0.1");
        info.setPort(7001);
        info.setBusPort(17001);
        info.setConfigEpoch(5L);
        info.addFlag(ClusterNodeState.SLAVE);
        info.setReplicationOffset(123456L);

        byte[] full = info.encode();
        // 解码完整
        GossipNodeInfo decoded = new GossipNodeInfo();
        decoded.decode(full, 0);
        assertEquals(123456L, decoded.getReplicationOffset());

        // 截断尾部 8 字节（模拟旧版本），解码应不报错且 replOffset 默认 0
        byte[] truncated = new byte[full.length - 8];
        System.arraycopy(full, 0, truncated, 0, truncated.length);
        GossipNodeInfo decodedOld = new GossipNodeInfo();
        decodedOld.decode(truncated, 0);
        assertEquals(0L, decodedOld.getReplicationOffset(),
                "旧 gossip section 无 replOffset 字段时应默认 0");
        assertEquals(NODE_ID_2, decodedOld.getNodeId(), "其他字段应正常解码");
    }

    /**
     * 构造故障转移场景：一个已 FAIL 的 master + 本节点(slave) + 两个兄弟 slave。
     * @param myOffset     本节点 replOffset
     * @param sibling1Offset 兄弟1 replOffset
     * @param sibling2Offset 兄弟2 replOffset
     */
    private void setupFailoverScenario(long myOffset, long sibling1Offset, long sibling2Offset) {
        ClusterNode master = new ClusterNode(MASTER_ID, "127.0.0.1", 7000, 17000);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.FAIL);
        config.addNode(master);

        ClusterNode me = new ClusterNode(NODE_ID_1, "127.0.0.1", 7001, 17001);
        me.addState(ClusterNodeState.MYSELF);
        me.addState(ClusterNodeState.SLAVE);
        me.setMasterNodeId(MASTER_ID);
        me.setReplOffset(myOffset);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_1);

        ClusterNode s1 = new ClusterNode(NODE_ID_2, "127.0.0.1", 7002, 17002);
        s1.addState(ClusterNodeState.SLAVE);
        s1.setMasterNodeId(MASTER_ID);
        s1.setReplOffset(sibling1Offset);
        config.addNode(s1);

        ClusterNode s2 = new ClusterNode(NODE_ID_3, "127.0.0.1", 7003, 17003);
        s2.addState(ClusterNodeState.SLAVE);
        s2.setMasterNodeId(MASTER_ID);
        s2.setReplOffset(sibling2Offset);
        config.addNode(s2);

        // 额外 2 个可用 master 满足 quorum（canFailover 要求多数 master 可用）
        ClusterNode m2 = new ClusterNode("e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0",
                "127.0.0.1", 7004, 17004);
        m2.addState(ClusterNodeState.MASTER);
        config.addNode(m2);
        ClusterNode m3 = new ClusterNode("f1e2d3c4b5a6f7e8d9c0b1a2f3e4d5c6b7a8f9e0",
                "127.0.0.1", 7005, 17005);
        m3.addState(ClusterNodeState.MASTER);
        config.addNode(m3);
    }
}
