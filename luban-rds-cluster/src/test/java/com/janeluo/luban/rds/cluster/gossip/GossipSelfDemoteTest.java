package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 验证重启的原主节点经 gossip 心跳自降级为新主的 slave。
 * <p>
 * 对应 design doc 3.1/3.2：{@code processGossipNodes} 不再跳过 MYSELF，
 * 由 {@link FailoverManager#applySelfDemotion} 原子降级。
 * </p>
 */
class GossipSelfDemoteTest {

    private static final String MY_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NEW_MASTER_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    private ClusterConfig clusterConfig;
    private GossipProtocol gossipProtocol;
    private FailoverManager failoverManager;
    private SlotManager slotManager;

    @BeforeEach
    void setUp() {
        clusterConfig = new ClusterConfig();
        clusterConfig.setMyNodeId(MY_ID);

        // MYSELF 以旧 master 身份恢复，持有 slots 5461-10922，configEpoch=4
        ClusterNode myNode = new ClusterNode(MY_ID, "127.0.0.1", 9737, 19737);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        myNode.setConfigEpoch(4L);
        BitSet mySlots = new BitSet();
        mySlots.set(5461, 10923);
        myNode.setSlots(mySlots);
        clusterConfig.addNode(myNode);

        slotManager = new DefaultSlotManager();
        for (int i = 5461; i <= 10922; i++) {
            clusterConfig.setSlotOwner(i, MY_ID);
            slotManager.setSlotOwner(i, MY_ID);
        }

        // 新主记录（已提升，configEpoch=9）
        ClusterNode newMaster = new ClusterNode(NEW_MASTER_ID, "127.0.0.1", 9740, 19740);
        newMaster.addState(ClusterNodeState.MASTER);
        newMaster.setConfigEpoch(9L);
        clusterConfig.addNode(newMaster);
        clusterConfig.setCurrentEpoch(9L);

        ClusterBusClient mockBusClient = mock(ClusterBusClient.class);
        ClusterStateManager mockStateManager = mock(ClusterStateManager.class);
        gossipProtocol = new GossipProtocol(clusterConfig, mockBusClient, 5000);
        failoverManager = new FailoverManager(clusterConfig, slotManager, mockStateManager,
                mockBusClient, () -> {}, 15000, 0);
        failoverManager.setReplicationLifecycleListener(new NoOpReplicationLifecycleListener());
        gossipProtocol.setFailoverManager(failoverManager);
    }

    private GossipNodeInfo myselfEntry(long configEpoch, String masterNodeId) {
        GossipNodeInfo info = new GossipNodeInfo();
        info.setNodeId(MY_ID);
        info.setConfigEpoch(configEpoch);
        info.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        info.setMasterNodeId(masterNodeId);
        info.setIp("127.0.0.1");
        info.setPort(9737);
        info.setBusPort(19737);
        return info;
    }

    @Test
    @DisplayName("MYSELF 收到更高 configEpoch + SLAVE 视图时应自降级为新主 slave")
    void myselfDemotesWhenGossipCarriesHigherEpochSlaveView() {
        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setSenderCurrentEpoch(9L);
        pong.setSenderConfigEpoch(9L);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        pong.setGossipNodes(List.of(myselfEntry(9L, NEW_MASTER_ID)));

        gossipProtocol.handlePong(pong);

        ClusterNode myNode = clusterConfig.getMyNode();
        assertTrue(myNode.isSlave(), "MYSELF 应已降级为 slave");
        assertFalse(myNode.isMaster(), "MYSELF 不应再是 master");
        assertEquals(NEW_MASTER_ID, myNode.getMasterNodeId());
        assertTrue(myNode.getSlots().isEmpty(), "MYSELF slots 应已清空");
        assertEquals(9L, clusterConfig.getCurrentEpoch(), "集群 currentEpoch 应同步到 9");
    }

    @Test
    @DisplayName("相等 configEpoch 不应触发降级（防回退）")
    void noDemoteWhenGossipEpochEqualsLocal() {
        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry(4L, NEW_MASTER_ID)));

        gossipProtocol.handlePong(pong);

        assertTrue(clusterConfig.getMyNode().isMaster(), "相等 epoch 不应触发降级");
    }

    @Test
    @DisplayName("更低 configEpoch 不应触发降级")
    void noDemoteWhenGossipEpochLowerThanLocal() {
        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry(3L, NEW_MASTER_ID)));

        gossipProtocol.handlePong(pong);

        assertTrue(clusterConfig.getMyNode().isMaster(), "更低 epoch 不应触发降级");
    }

    @Test
    @DisplayName("MYSELF 已是 slave 时幂等不重复降级")
    void idempotentWhenMyselfAlreadySlave() {
        // 先降级一次
        myselfDemotesWhenGossipCarriesHigherEpochSlaveView();

        // 再次收到相同视图，不应抛异常
        PongMessage pong = new PongMessage(NEW_MASTER_ID, System.currentTimeMillis());
        pong.setGossipNodes(List.of(myselfEntry(9L, NEW_MASTER_ID)));

        assertDoesNotThrow(() -> gossipProtocol.handlePong(pong));
        assertTrue(clusterConfig.getMyNode().isSlave());
    }
}
