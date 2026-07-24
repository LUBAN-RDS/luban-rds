package com.janeluo.luban.rds.cluster.integration;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.FailoverManager;
import com.janeluo.luban.rds.cluster.gossip.FailoverResultMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipNodeInfo;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.gossip.PongMessage;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 故障转移后旧主重启经 gossip 自降级为新主 slave 的集成测试。
 * <p>
 * 覆盖 fix-cluster-restart-demote 的端到端消息流：
 * <ol>
 *   <li>Phase 1：以观察者 M1 视角驱动真实 FailoverResult 广播，建立故障转移后的集群拓扑
 *       （S2 升 master、M2 降为 S2 的 slave、槽位转移到 S2）。</li>
 *   <li>Phase 2：模拟 M2 以陈旧本地配置重启（M2 仍为 MYSELF+MASTER、持有 slots 5461-10922、
 *       configEpoch=4/currentEpoch=4，S2 在其本地记录中仍是 M2 的 slave）。</li>
 *   <li>Phase 3：M2 收到来自 S2 的 PONG，senderCurrentEpoch=9，gossip section 携带 M2 的
 *       正确视图 {configEpoch=9, SLAVE, masterNodeId=S2}。</li>
 *   <li>Phase 4：断言 M2 自降级为 S2 的 slave、slots 清空、currentEpoch 同步到 9，
 *       且 S2 在 M2 视图中被提升为 MASTER。</li>
 * </ol>
 * </p>
 * <p>
 * 与 {@link com.janeluo.luban.rds.cluster.gossip.GossipSelfDemoteTest} 验证同一条自降级路径，
 * 但本测试先经真实 {@code FailoverManager.onFailoverResult} 建立故障转移后拓扑，
 * 再模拟重启节点视角的 gossip 收敛，验证整条流程可正确组合。
 * </p>
 */
class ClusterRestartDemoteTest {

    // 3 个 master + 1 个 slave（M2 的 slave S2）
    private static final String M1_ID = "1111111111111111111111111111111111111111";
    private static final String M2_ID = "2222222222222222222222222222222222222222";
    private static final String M3_ID = "3333333333333333333333333333333333333333";
    private static final String S2_ID = "4444444444444444444444444444444444444444";

    // 槽位区间
    private static final int M2_SLOT_START = 5461;
    private static final int M2_SLOT_END = 10922;

    // 故障转移后 S2 的新 configEpoch（胜选自增后的 currentEpoch）
    private static final long POST_FAILOVER_EPOCH = 9L;
    // M2 重启时本地陈旧的 configEpoch / currentEpoch
    private static final long STALE_EPOCH = 4L;

    @Test
    @DisplayName("故障转移后旧主重启经 gossip 自降级为新主 slave")
    void oldMasterSelfDemotesAfterRestartViaGossip() {
        // ==================== Phase 1: 观察者 M1 视角，驱动真实 FailoverResult 广播 ====================
        // M1 作为 MYSELF 观察者，本地持有 M1/M2/M3/S2 四个节点的完整视图。
        // 槽位直接挂在节点上（不经 config.setSlotOwner），与 FailoverManagerTest.testHandleFailoverResult
        // 的装配方式一致：onFailoverResult 内部 demotion 循环依赖 sharesAnySlot 判定原 master，
        // 若先经 setSlotOwner 登记，转移槽位时会提前从 oldMaster 移除 slots 导致 demotion 被跳过。
        ClusterConfig m1Config = new ClusterConfig();
        m1Config.setMyNodeId(M1_ID);

        ClusterNode m1 = createMasterNode(M1_ID, "127.0.0.1", 7000);
        m1.addState(ClusterNodeState.MYSELF);
        m1.setConfigEpoch(STALE_EPOCH);
        m1.addSlotRange(0, 5460);
        ClusterNode m2 = createMasterNode(M2_ID, "127.0.0.1", 7001);
        m2.setConfigEpoch(STALE_EPOCH);
        m2.addSlotRange(M2_SLOT_START, M2_SLOT_END);
        ClusterNode m3 = createMasterNode(M3_ID, "127.0.0.1", 7002);
        m3.setConfigEpoch(STALE_EPOCH);
        m3.addSlotRange(10923, 16383);
        ClusterNode s2 = createSlaveNode(S2_ID, "127.0.0.1", 7003, M2_ID);
        s2.setConfigEpoch(STALE_EPOCH);

        m1Config.addNode(m1);
        m1Config.addNode(m2);
        m1Config.addNode(m3);
        m1Config.addNode(s2);
        m1Config.setCurrentEpoch(STALE_EPOCH);

        SlotManager m1SlotManager = new DefaultSlotManager();
        ClusterStateManager m1StateManager = new ClusterStateManager(m1Config);
        ClusterBusClient m1BusClient = mock(ClusterBusClient.class);
        FailoverManager m1FailoverManager = new FailoverManager(
                m1Config, m1SlotManager, m1StateManager, m1BusClient,
                () -> {}, 15000L, 0L);
        m1FailoverManager.setReplicationLifecycleListener(new NoOpReplicationLifecycleListener());

        // 构造 S2 胜选广播的 FailoverResult：winner=S2, newConfigEpoch=9, inheritedSlots=M2 的槽位区间
        BitSet inheritedSlots = new BitSet();
        inheritedSlots.set(M2_SLOT_START, M2_SLOT_END + 1);
        FailoverResultMessage resultMessage = new FailoverResultMessage(
                S2_ID, S2_ID, POST_FAILOVER_EPOCH, inheritedSlots);

        // M1 应用 FailoverResult：全网拓扑收敛
        m1FailoverManager.onFailoverResult(resultMessage);

        // Phase 1 断言：S2 升 master，M2 降为 S2 的 slave，槽位归属 S2
        assertTrue(s2.isMaster(), "Phase1: S2 应被提升为 master");
        assertFalse(s2.isSlave(), "Phase1: S2 不应再是 slave");
        assertTrue(m2.isSlave(), "Phase1: M2 应被降级为 slave");
        assertEquals(S2_ID, m2.getMasterNodeId(), "Phase1: M2 应指向新主 S2");
        assertEquals(POST_FAILOVER_EPOCH, m1Config.getCurrentEpoch(),
                "Phase1: 集群 currentEpoch 应同步到 9");
        assertEquals(0, m2.getSlotCount(), "Phase1: M2 槽位应已清空");
        assertTrue(s2.getSlotCount() > 0, "Phase1: S2 应继承 M2 的槽位");

        // ==================== Phase 2: M2 以陈旧本地配置重启 ====================
        // M2 重启后从 nodes.conf 恢复的是故障转移前的陈旧拓扑：
        // M2 仍是 MYSELF+MASTER、持有 slots 5461-10922、configEpoch=4/currentEpoch=4。
        // S2 在 M2 的本地记录中仍是 M2 的 slave（故障转移前的视图）。
        ClusterConfig m2Config = new ClusterConfig();
        m2Config.setMyNodeId(M2_ID);

        ClusterNode m2Self = createMasterNode(M2_ID, "127.0.0.1", 7001);
        m2Self.addState(ClusterNodeState.MYSELF);
        m2Self.setConfigEpoch(STALE_EPOCH);
        BitSet m2StaleSlots = new BitSet();
        m2StaleSlots.set(M2_SLOT_START, M2_SLOT_END + 1);
        m2Self.setSlots(m2StaleSlots);

        // S2 在 M2 重启视图里仍是 slave of M2（陈旧）
        ClusterNode s2InM2View = createSlaveNode(S2_ID, "127.0.0.1", 7003, M2_ID);
        s2InM2View.setConfigEpoch(STALE_EPOCH);
        // 也记录 M1/M3 以贴近真实拓扑（PONG 头处理 updateNodeFromPongMessage 需要发送方存在）
        ClusterNode m1InM2View = createMasterNode(M1_ID, "127.0.0.1", 7000);
        m1InM2View.setConfigEpoch(STALE_EPOCH);
        ClusterNode m3InM2View = createMasterNode(M3_ID, "127.0.0.1", 7002);
        m3InM2View.setConfigEpoch(STALE_EPOCH);

        m2Config.addNode(m2Self);
        m2Config.addNode(s2InM2View);
        m2Config.addNode(m1InM2View);
        m2Config.addNode(m3InM2View);

        SlotManager m2SlotManager = new DefaultSlotManager();
        for (int i = M2_SLOT_START; i <= M2_SLOT_END; i++) {
            m2Config.setSlotOwner(i, M2_ID);
            m2SlotManager.setSlotOwner(i, M2_ID);
        }
        m2Config.setCurrentEpoch(STALE_EPOCH);

        ClusterStateManager m2StateManager = new ClusterStateManager(m2Config);
        ClusterBusClient m2BusClient = mock(ClusterBusClient.class);

        GossipProtocol m2Gossip = new GossipProtocol(m2Config, m2BusClient, 15000L);
        FailoverManager m2FailoverManager = new FailoverManager(
                m2Config, m2SlotManager, m2StateManager, m2BusClient,
                () -> {}, 15000L, 0L);
        m2FailoverManager.setReplicationLifecycleListener(new NoOpReplicationLifecycleListener());
        m2Gossip.setFailoverManager(m2FailoverManager);

        // Phase 2 前置断言：M2 重启视图为陈旧 master
        assertTrue(m2Self.isMaster(), "Phase2: M2 重启时应仍为 master（陈旧视图）");
        assertTrue(m2Self.getSlotCount() > 0, "Phase2: M2 重启时应仍持有 slots");
        assertEquals(STALE_EPOCH, m2Config.getCurrentEpoch(),
                "Phase2: M2 重启时 currentEpoch 应为陈旧的 4");

        // ==================== Phase 3: M2 收到 S2 的 PONG，携带 M2 的降级视图 ====================
        // PONG 来自 S2（已是新 master），senderCurrentEpoch=9。
        // gossip section 携带 M2 的正确视图：configEpoch=9, SLAVE, masterNodeId=S2。
        GossipNodeInfo m2EntryFromS2View = new GossipNodeInfo();
        m2EntryFromS2View.setNodeId(M2_ID);
        m2EntryFromS2View.setConfigEpoch(POST_FAILOVER_EPOCH);
        m2EntryFromS2View.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        m2EntryFromS2View.setMasterNodeId(S2_ID);
        m2EntryFromS2View.setIp("127.0.0.1");
        m2EntryFromS2View.setPort(7001);
        m2EntryFromS2View.setBusPort(17001);

        PongMessage pongFromS2 = new PongMessage(S2_ID, System.currentTimeMillis());
        pongFromS2.setSenderCurrentEpoch(POST_FAILOVER_EPOCH);
        pongFromS2.setSenderConfigEpoch(POST_FAILOVER_EPOCH);
        pongFromS2.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        pongFromS2.setGossipNodes(List.of(m2EntryFromS2View));

        // M2 处理 PONG：handlePong -> processGossipNodes -> handleMyselfGossipEntry -> applySelfDemotion
        m2Gossip.handlePong(pongFromS2);

        // ==================== Phase 4: 断言 M2 已自降级 ====================
        ClusterNode m2After = m2Config.getMyNode();
        assertTrue(m2After.isSlave(), "Phase4: M2 应已自降级为 slave");
        assertFalse(m2After.isMaster(), "Phase4: M2 不应再是 master");
        assertEquals(S2_ID, m2After.getMasterNodeId(),
                "Phase4: M2 应指向新主 S2");
        assertTrue(m2After.getSlots().isEmpty(),
                "Phase4: M2 slots 应已清空");
        assertEquals(POST_FAILOVER_EPOCH, m2Config.getCurrentEpoch(),
                "Phase4: M2 本地 currentEpoch 应同步到 9");

        // S2 在 M2 视图中应被提升为 MASTER（applySelfDemotion 内部提权）
        ClusterNode s2AfterInM2View = m2Config.getNode(S2_ID);
        assertTrue(s2AfterInM2View.isMaster(),
                "Phase4: S2 在 M2 视图中应被提升为 master");
        assertFalse(s2AfterInM2View.isSlave(),
                "Phase4: S2 在 M2 视图中不应再是 slave");

        // 槽位归属应已转移到 S2
        for (int i = M2_SLOT_START; i <= M2_SLOT_END; i++) {
            assertEquals(S2_ID, m2Config.getSlotOwner(i),
                    "Phase4: 槽位 " + i + " 应归属 S2");
            assertEquals(S2_ID, m2SlotManager.getSlotOwner(i),
                    "Phase4: SlotManager 中槽位 " + i + " 应归属 S2");
        }
    }

    // ==================== 辅助方法（复制自 ClusterFailoverTest，保持独立） ====================

    /**
     * 创建主节点
     */
    private ClusterNode createMasterNode(String nodeId, String ip, int port) {
        ClusterNode node = new ClusterNode(nodeId);
        node.setIp(ip);
        node.setPort(port);
        node.setBusPort(port + 10000);
        node.addState(ClusterNodeState.MASTER);
        node.updateLastPongTime();
        return node;
    }

    /**
     * 创建从节点
     */
    private ClusterNode createSlaveNode(String nodeId, String ip, int port, String masterId) {
        ClusterNode node = new ClusterNode(nodeId);
        node.setIp(ip);
        node.setPort(port);
        node.setBusPort(port + 10000);
        node.addState(ClusterNodeState.SLAVE);
        node.setMasterNodeId(masterId);
        node.updateLastPongTime();
        return node;
    }
}
