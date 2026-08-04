package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ClusterStateManager 单元测试
 */
public class ClusterStateManagerTest {

    private ClusterConfig config;
    private ClusterStateManager stateManager;

    @Before
    public void setUp() {
        config = new ClusterConfig();
        stateManager = new ClusterStateManager(config);
    }

    @Test
    public void testIsClusterOkWhenEmpty() {
        // 空配置，所有槽位未分配
        assertFalse(stateManager.isClusterOk());
    }

    @Test
    public void testIsClusterOkWithAllSlotsAssigned() {
        // 创建主节点并分配所有槽位
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.setIp("127.0.0.1");
        master.setPort(7000);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);

        // 分配所有槽位
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            config.setSlotOwner(i, nodeId);
        }

        assertTrue(stateManager.isClusterOk());
    }

    @Test
    public void testIsClusterOkWithPartialSlots() {
        // 只分配部分槽位
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);

        // 只分配一半槽位
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS / 2; i++) {
            config.setSlotOwner(i, nodeId);
        }

        assertFalse(stateManager.isClusterOk());
    }

    @Test
    public void testIsClusterOkWithFailNode() {
        // 创建主节点并分配所有槽位
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.FAIL); // 标记为FAIL
        config.addNode(master);

        // 分配所有槽位
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            config.setSlotOwner(i, nodeId);
        }

        // 有FAIL节点，集群不健康
        assertFalse(stateManager.isClusterOk());
    }

    @Test
    public void testIsClusterOkWithSlaveAndFailMaster() {
        // 创建主节点（FAIL）
        String masterId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(masterId);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.FAIL);
        config.addNode(master);

        // 创建从节点（指向 FAIL 主节点）
        String slaveId = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
        ClusterNode slave = new ClusterNode(slaveId);
        slave.addState(ClusterNodeState.SLAVE);
        slave.setMasterNodeId(masterId);
        config.addNode(slave);

        // 槽位归属主节点（对齐 Redis：slot owner 只能是 master）。主节点 FAIL，集群不健康。
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            config.setSlotOwner(i, masterId);
        }

        // 主节点FAIL，集群不健康
        assertFalse(stateManager.isClusterOk());
    }

    @Test
    public void testGetStats() {
        // 创建主节点
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);

        // 分配部分槽位
        for (int i = 0; i < 1000; i++) {
            config.setSlotOwner(i, nodeId);
        }

        ClusterStats stats = stateManager.getStats();

        assertNotNull(stats);
        assertEquals("fail", stats.getState()); // 未全部分配
        assertEquals(1000, stats.getSlotsAssigned());
        assertEquals(1000, stats.getSlotsOk()); // 节点正常
        assertEquals(0, stats.getSlotsPfail());
        assertEquals(0, stats.getSlotsFail());
        assertEquals(1, stats.getKnownNodes());
        assertEquals(1, stats.getSize()); // 1个主节点
    }

    @Test
    public void testGetStatsWithFailSlots() {
        // 创建FAIL状态的主节点
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.FAIL);
        config.addNode(master);

        // 分配槽位
        for (int i = 0; i < 1000; i++) {
            config.setSlotOwner(i, nodeId);
        }

        ClusterStats stats = stateManager.getStats();

        assertEquals(1000, stats.getSlotsAssigned());
        assertEquals(0, stats.getSlotsOk());
        assertEquals(0, stats.getSlotsPfail());
        assertEquals(1000, stats.getSlotsFail());
    }

    @Test
    public void testGetStatsWithPfailSlots() {
        // 创建PFAIL状态的主节点
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.PFAIL);
        config.addNode(master);

        // 分配槽位
        for (int i = 0; i < 1000; i++) {
            config.setSlotOwner(i, nodeId);
        }

        ClusterStats stats = stateManager.getStats();

        assertEquals(1000, stats.getSlotsAssigned());
        assertEquals(0, stats.getSlotsOk());
        assertEquals(1000, stats.getSlotsPfail());
        assertEquals(0, stats.getSlotsFail());
    }

    @Test
    public void testMessageCounters() {
        stateManager.incrementMessagesSent(10);
        stateManager.incrementMessagesReceived(5);

        ClusterStats stats = stateManager.getStats();
        assertEquals(10, stats.getMessagesSent());
        assertEquals(5, stats.getMessagesReceived());

        // 再次增加
        stateManager.incrementMessagesSent(5);
        stateManager.incrementMessagesReceived(3);

        stats = stateManager.getStats();
        assertEquals(15, stats.getMessagesSent());
        assertEquals(8, stats.getMessagesReceived());
    }

    @Test
    public void testResetMessageCounters() {
        stateManager.incrementMessagesSent(100);
        stateManager.incrementMessagesReceived(50);

        stateManager.resetMessageCounters();

        ClusterStats stats = stateManager.getStats();
        assertEquals(0, stats.getMessagesSent());
        assertEquals(0, stats.getMessagesReceived());
    }

    @Test
    public void testIsSlotAvailable() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);
        config.setSlotOwner(0, nodeId);

        // 槽位已分配且节点正常
        assertTrue(stateManager.isSlotAvailable(0));

        // 未分配的槽位
        assertFalse(stateManager.isSlotAvailable(1));
    }

    @Test
    public void testIsSlotAvailableWithFailNode() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.FAIL);
        config.addNode(master);
        config.setSlotOwner(0, nodeId);

        // FAIL节点的槽位不可用
        assertFalse(stateManager.isSlotAvailable(0));
    }

    @Test
    public void testIsSlotAvailableInvalidSlot() {
        assertFalse(stateManager.isSlotAvailable(-1));
        assertFalse(stateManager.isSlotAvailable(ClusterNode.CLUSTER_SLOTS));
    }

    @Test
    public void testGetUnavailableSlotCount() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);

        // 分配1000个槽位
        for (int i = 0; i < 1000; i++) {
            config.setSlotOwner(i, nodeId);
        }

        // 1000个可用，其余15384个不可用
        assertEquals(ClusterNode.CLUSTER_SLOTS - 1000, stateManager.getUnavailableSlotCount());
    }

    @Test
    public void testCanFailover() {
        // myself 是 slave，其 master 已 FAIL
        ClusterNode failedMaster = new ClusterNode(String.format("a%039d", 0));
        failedMaster.addState(ClusterNodeState.MASTER);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode me = new ClusterNode(String.format("a%039d", 1));
        me.addState(ClusterNodeState.SLAVE);
        me.addState(ClusterNodeState.MYSELF);
        me.setMasterNodeId(failedMaster.getNodeId());
        // 2 个可用 master 满足 quorum
        ClusterNode m2 = new ClusterNode(String.format("a%039d", 2));
        m2.addState(ClusterNodeState.MASTER);
        ClusterNode m3 = new ClusterNode(String.format("a%039d", 3));
        m3.addState(ClusterNodeState.MASTER);
        config.addNode(failedMaster);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(me.getNodeId());

        // myself 是 slave、master FAIL、超过半数主节点可用
        assertTrue(stateManager.canFailover());
    }

    @Test
    public void testCanFailoverWithFailNodes() {
        // myself 是 slave，其 master 已 FAIL
        ClusterNode failedMaster = new ClusterNode(String.format("a%039d", 0));
        failedMaster.addState(ClusterNodeState.MASTER);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode me = new ClusterNode(String.format("a%039d", 1));
        me.addState(ClusterNodeState.SLAVE);
        me.addState(ClusterNodeState.MYSELF);
        me.setMasterNodeId(failedMaster.getNodeId());
        // 2 个可用 master 满足 quorum（masterCount=3，可用 2 > 1）
        ClusterNode m2 = new ClusterNode(String.format("a%039d", 2));
        m2.addState(ClusterNodeState.MASTER);
        ClusterNode m3 = new ClusterNode(String.format("a%039d", 3));
        m3.addState(ClusterNodeState.MASTER);
        config.addNode(failedMaster);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(me.getNodeId());

        // 可用 master 超过半数
        assertTrue(stateManager.canFailover());
    }

    @Test
    public void testCanFailoverWithMajorityFail() {
        // myself 是 slave，其 master 已 FAIL
        ClusterNode failedMaster = new ClusterNode(String.format("a%039d", 0));
        failedMaster.addState(ClusterNodeState.MASTER);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode me = new ClusterNode(String.format("a%039d", 1));
        me.addState(ClusterNodeState.SLAVE);
        me.addState(ClusterNodeState.MYSELF);
        me.setMasterNodeId(failedMaster.getNodeId());
        // 3 个 master 中 2 个 FAIL（含 myMaster），仅 1 个可用 → 不过半
        ClusterNode m2 = new ClusterNode(String.format("a%039d", 2));
        m2.addState(ClusterNodeState.MASTER);
        ClusterNode m3 = new ClusterNode(String.format("a%039d", 3));
        m3.addState(ClusterNodeState.MASTER);
        m3.addState(ClusterNodeState.FAIL);
        config.addNode(failedMaster);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(me.getNodeId());

        // 可用 master 仅 1 个，不超过半数（masterCount=3，需 >1）
        assertFalse(stateManager.canFailover());
    }

    @Test
    public void testGetFailNodeCount() {
        // 创建节点
        for (int i = 0; i < 5; i++) {
            String nodeId = String.format("a%039d", i);
            ClusterNode node = new ClusterNode(nodeId);
            node.addState(ClusterNodeState.MASTER);
            if (i >= 3) { // 最后2个节点FAIL
                node.addState(ClusterNodeState.FAIL);
            }
            config.addNode(node);
        }

        assertEquals(2, stateManager.getFailNodeCount());
    }

    @Test
    public void testGetPfailNodeCount() {
        // 创建节点
        for (int i = 0; i < 5; i++) {
            String nodeId = String.format("a%039d", i);
            ClusterNode node = new ClusterNode(nodeId);
            node.addState(ClusterNodeState.MASTER);
            if (i >= 3) { // 最后2个节点PFAIL
                node.addState(ClusterNodeState.PFAIL);
            }
            config.addNode(node);
        }

        assertEquals(2, stateManager.getPfailNodeCount());
    }

    @Test
    public void testUpdateClusterState() {
        // 初始状态为fail
        assertEquals("fail", config.getState());

        // 创建主节点并分配所有槽位
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode master = new ClusterNode(nodeId);
        master.addState(ClusterNodeState.MASTER);
        config.addNode(master);

        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            config.setSlotOwner(i, nodeId);
        }

        // 更新状态
        stateManager.updateClusterState();

        // 状态应该变为ok
        assertEquals("ok", config.getState());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNullConfig() {
        new ClusterStateManager(null);
    }

    // ==================== N-26：cluster_state 单公式（对齐 Redis clusterUpdateState） ====================

    /**
     * 辅助：创建持槽 master 并登记槽位归属。
     */
    private ClusterNode addSlotMaster(String id, int slotStart, int slotEnd, long configEpoch) {
        ClusterNode master = new ClusterNode(id);
        master.addState(ClusterNodeState.MASTER);
        master.setConfigEpoch(configEpoch);
        config.addNode(master);
        for (int i = slotStart; i <= slotEnd; i++) {
            config.setSlotOwner(i, id);
        }
        return master;
    }

    @Test
    public void testN26SinglePfailMasterBreaksQuorum() {
        // 单 master PFAIL：覆盖率通过（PFAIL owner 容忍），但 reachable(0) < size/2+1(1) → fail。
        // 对齐 Redis：不可达的持槽 master 使集群处于 minority，状态 fail。
        ClusterNode master = addSlotMaster("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 0, 16383, 1);
        master.addState(ClusterNodeState.PFAIL);

        assertFalse("PFAIL 持槽 master 不满足多数可达 → 集群 fail", stateManager.isClusterOk());
    }

    @Test
    public void testN26FailMasterWithoutSlotsKeepsClusterOk() {
        // 3 master：1 个 FAIL 且无槽位（槽位已被接管），2 个健康持槽 master 覆盖全部槽位。
        // 覆盖率通过（owner 非 FAIL）；quorum：size=2（FAIL master 无槽不计入），reachable=2 >= 2 → ok。
        ClusterNode m1 = addSlotMaster("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 0, 8191, 1);
        ClusterNode m2 = addSlotMaster("b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 8192, 16383, 1);
        ClusterNode failed = new ClusterNode("c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        failed.addState(ClusterNodeState.MASTER);
        failed.addState(ClusterNodeState.FAIL);
        config.addNode(failed);

        assertTrue("FAIL 但无槽位的 master 不参与 size/覆盖率，集群应保持 ok", stateManager.isClusterOk());
    }

    @Test
    public void testN26FailMasterHoldingSlotsFailsCoverage() {
        // 3 master，1 个 FAIL 仍持有槽位 → 覆盖率检查失败（owner FAIL）→ 集群 fail。
        // 旧 GossipTask 公式（可用 master 过半 + 全槽已分配）在此场景判 ok，两公式结论相反——
        // 统一后以 Redis 覆盖率语义为准。
        ClusterNode m1 = addSlotMaster("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 0, 5460, 1);
        ClusterNode m2 = addSlotMaster("b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 5461, 10922, 1);
        ClusterNode m3 = addSlotMaster("c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 10923, 16383, 1);
        m3.addState(ClusterNodeState.FAIL);

        assertFalse("FAIL master 持槽 → 覆盖率失败 → 集群 fail", stateManager.isClusterOk());
    }

    @Test
    public void testN26EmptyMasterNotCountedInQuorum() {
        // 2 个健康持槽 master 覆盖全部槽位 + 1 个无槽空 master：
        // quorum 只按持槽 master 计（size=2, reachable=2），空 master 不稀释集群规模。
        addSlotMaster("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 0, 8191, 1);
        addSlotMaster("b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 8192, 16383, 1);
        ClusterNode empty = new ClusterNode("c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        empty.addState(ClusterNodeState.MASTER);
        config.addNode(empty);

        assertTrue("无槽 master 不应参与 cluster size（Redis getClusterSize 语义）", stateManager.isClusterOk());
    }

    @Test
    public void testN26HalfMastersFailedBreaksQuorum() {
        // 3 个持槽 master，2 个 FAIL 且持有槽位：覆盖率已失败；即便槽位被接管，
        // reachable(1) < size/2+1(2) 也使 quorum 失败 → fail。
        ClusterNode m1 = addSlotMaster("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 0, 5460, 1);
        ClusterNode m2 = addSlotMaster("b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 5461, 10922, 1);
        ClusterNode m3 = addSlotMaster("c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 10923, 16383, 1);
        m2.addState(ClusterNodeState.FAIL);
        m3.addState(ClusterNodeState.FAIL);

        assertFalse("多数持槽 master 不可达 → 集群 fail", stateManager.isClusterOk());
    }

    @Test
    public void testN26MyEpochFromMyselfNode() {
        // cluster_my_epoch 应来自 MYSELF 节点的实际 configEpoch（ClusterConfig 级别
        // 独立字段为陈旧死字段，恒为 0）。
        ClusterNode myNode = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        myNode.addState(ClusterNodeState.MASTER);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.setConfigEpoch(7L);
        config.addNode(myNode);
        config.setMyNodeId(myNode.getNodeId());
        config.setConfigEpoch(0L);  // 独立字段保持 0（死字段）

        ClusterStats stats = stateManager.getStats();
        assertEquals("cluster_my_epoch 应取 MYSELF 节点 configEpoch", 7L, stats.getMyEpoch());
    }

    @Test
    public void testN26PerTypeMessageCounters() {
        stateManager.incrementMessagesSent("ping", 3);
        stateManager.incrementMessagesSent("auth-req", 2);
        stateManager.incrementMessagesReceived("pong", 5);

        ClusterStats stats = stateManager.getStats();
        assertEquals(5L, stats.getMessagesSent());
        assertEquals(5L, stats.getMessagesReceived());
        assertEquals(Long.valueOf(3L), stats.getMessagesSentByType().get("ping"));
        assertEquals(Long.valueOf(2L), stats.getMessagesSentByType().get("auth-req"));
        assertEquals(Long.valueOf(5L), stats.getMessagesReceivedByType().get("pong"));
    }

    @Test
    public void testGetConfig() {
        assertEquals(config, stateManager.getConfig());
    }
}
