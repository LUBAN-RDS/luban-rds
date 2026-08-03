package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ClusterConfig 单元测试
 */
public class ClusterConfigTest {

    private ClusterConfig config;
    private String nodeId1;
    private String nodeId2;

    @Before
    public void setUp() {
        config = new ClusterConfig();
        nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    }

    @Test
    public void testDefaultConstructor() {
        assertNotNull(config);
        assertNull(config.getMyNodeId());
        assertEquals(0, config.getCurrentEpoch());
        assertEquals(0, config.getConfigEpoch());
        assertEquals("fail", config.getState());
        assertEquals(0, config.getNodeCount());
        assertEquals(0, config.getAssignedSlotCount());
    }

    @Test
    public void testConstructorWithMyNodeId() {
        ClusterConfig configWithId = new ClusterConfig(nodeId1);
        assertEquals(nodeId1, configWithId.getMyNodeId());
    }

    @Test
    public void testAddNode() {
        ClusterNode node = new ClusterNode(nodeId1);
        node.setIp("127.0.0.1");
        node.setPort(7000);

        config.addNode(node);

        assertEquals(1, config.getNodeCount());
        assertTrue(config.hasNode(nodeId1));
        assertNotNull(config.getNode(nodeId1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNullNode() {
        config.addNode(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddNodeWithNullId() {
        ClusterNode node = new ClusterNode();
        config.addNode(node);
    }

    @Test
    public void testRemoveNode() {
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);
        assertTrue(config.hasNode(nodeId1));

        config.removeNode(nodeId1);
        assertFalse(config.hasNode(nodeId1));
        assertEquals(0, config.getNodeCount());
    }

    @Test
    public void testRemoveNodeWithSlots() {
        ClusterNode node = new ClusterNode(nodeId1);
        node.addSlotRange(0, 100);
        config.addNode(node);

        // 设置槽位分配
        for (int i = 0; i <= 100; i++) {
            config.setSlotOwner(i, nodeId1);
        }

        assertEquals(101, config.getAssignedSlotCount());

        // 移除节点应该同时清除槽位分配
        config.removeNode(nodeId1);
        assertEquals(0, config.getAssignedSlotCount());
    }

    @Test
    public void testSetSlotOwner() {
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);

        config.setSlotOwner(0, nodeId1);
        assertEquals(nodeId1, config.getSlotOwner(0));
        assertEquals(1, config.getAssignedSlotCount());

        // 验证节点的槽位也被设置
        assertTrue(node.hasSlot(0));
    }

    @Test
    public void testSetSlotOwnerForNonExistentNode() {
        config.setSlotOwner(0, nodeId1);
        assertEquals(nodeId1, config.getSlotOwner(0));
        assertEquals(1, config.getAssignedSlotCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetSlotOwnerInvalidSlot() {
        config.setSlotOwner(-1, nodeId1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testSetSlotOwnerSlotTooLarge() {
        config.setSlotOwner(ClusterNode.CLUSTER_SLOTS, nodeId1);
    }

    @Test
    public void testGetSlotOwnerNode() {
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);
        config.setSlotOwner(0, nodeId1);

        ClusterNode owner = config.getSlotOwnerNode(0);
        assertNotNull(owner);
        assertEquals(nodeId1, owner.getNodeId());
    }

    @Test
    public void testClearSlot() {
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);
        config.setSlotOwner(0, nodeId1);

        config.clearSlot(0);
        assertNull(config.getSlotOwner(0));
        assertFalse(node.hasSlot(0));
    }

    @Test
    public void testAreAllSlotsAssigned() {
        assertFalse(config.areAllSlotsAssigned());

        // 分配所有槽位
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            config.setSlotOwner(i, nodeId1);
        }

        assertTrue(config.areAllSlotsAssigned());
    }

    @Test
    public void testIncrementEpoch() {
        assertEquals(0, config.getCurrentEpoch());
        assertEquals(1, config.incrementEpoch());
        assertEquals(1, config.getCurrentEpoch());
        assertEquals(2, config.incrementEpoch());
        assertEquals(2, config.getCurrentEpoch());
    }

    @Test
    public void testSetEpochIfGreater() {
        assertFalse(config.setEpochIfGreater(0));
        assertTrue(config.setEpochIfGreater(1));
        assertEquals(1, config.getCurrentEpoch());
        assertFalse(config.setEpochIfGreater(1));
        assertTrue(config.setEpochIfGreater(5));
        assertEquals(5, config.getCurrentEpoch());
    }

    @Test
    public void testIsClusterOk() {
        assertFalse(config.isClusterOk());
        config.setState("ok");
        assertTrue(config.isClusterOk());
    }

    @Test
    public void testGetMasterCount() {
        ClusterNode master1 = new ClusterNode(nodeId1);
        master1.addState(ClusterNodeState.MASTER);
        config.addNode(master1);

        ClusterNode slave1 = new ClusterNode(nodeId2);
        slave1.addState(ClusterNodeState.SLAVE);
        config.addNode(slave1);

        assertEquals(1, config.getMasterCount());
        assertEquals(1, config.getSlaveCount());
    }

    @Test
    public void testGetMyNode() {
        config.setMyNodeId(nodeId1);
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);

        assertNotNull(config.getMyNode());
        assertEquals(nodeId1, config.getMyNode().getNodeId());
    }

    @Test
    public void testReset() {
        ClusterNode node = new ClusterNode(nodeId1);
        config.addNode(node);
        config.setSlotOwner(0, nodeId1);
        config.incrementEpoch();
        config.setState("ok");

        config.reset();

        assertEquals(0, config.getNodeCount());
        assertEquals(0, config.getAssignedSlotCount());
        assertEquals(0, config.getCurrentEpoch());
        assertEquals("fail", config.getState());
    }

    @Test
    public void testSetSlotAssignmentWithInvalidLength() {
        String[] invalidArray = new String[100];
        try {
            config.setSlotAssignment(invalidArray);
            fail("应该抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("16384"));
        }
    }

    @Test
    public void testGetAllNodes() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        ClusterNode node2 = new ClusterNode(nodeId2);
        config.addNode(node1);
        config.addNode(node2);

        assertEquals(2, config.getAllNodes().size());
    }

    @Test
    public void testSyncSlotsFromNodeAssignsUnownedSlots() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        config.addNode(node1);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(0);
        slots.set(5460);
        slots.set(16383);

        config.syncSlotsFromNode(nodeId1, slots, 1L);

        assertEquals(nodeId1, config.getSlotOwner(0));
        assertEquals(nodeId1, config.getSlotOwner(5460));
        assertEquals(nodeId1, config.getSlotOwner(16383));
        assertEquals(3, config.getAssignedSlotCount());
        assertTrue(node1.hasSlot(0));
        assertTrue(node1.hasSlot(16383));
    }

    @Test
    public void testSyncSlotsFromNodeHigherEpochOverrides() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(1L);
        config.addNode(node1);
        config.setSlotOwner(100, nodeId1);

        ClusterNode node2 = new ClusterNode(nodeId2);
        config.addNode(node2);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        // node2 纪元更大，应抢占 slot 100
        config.syncSlotsFromNode(nodeId2, slots, 5L);

        assertEquals(nodeId2, config.getSlotOwner(100));
        assertFalse(node1.hasSlot(100));
        assertTrue(node2.hasSlot(100));
    }

    @Test
    public void testSyncSlotsFromNodeEqualEpochDoesNotOverride() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(5L);
        config.addNode(node1);
        config.setSlotOwner(100, nodeId1);

        ClusterNode node2 = new ClusterNode(nodeId2);
        node2.setConfigEpoch(5L);
        config.addNode(node2);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        // 纪元相等，不抢占
        config.syncSlotsFromNode(nodeId2, slots, 5L);

        assertEquals(nodeId1, config.getSlotOwner(100));
    }

    @Test
    public void testSyncSlotsFromNodeLowerEpochDoesNotOverride() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(10L);
        config.addNode(node1);
        config.setSlotOwner(100, nodeId1);

        ClusterNode node2 = new ClusterNode(nodeId2);
        node2.setConfigEpoch(2L);
        config.addNode(node2);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        // node2 纪元更低，不抢占
        config.syncSlotsFromNode(nodeId2, slots, 2L);

        assertEquals(nodeId1, config.getSlotOwner(100));
    }

    @Test
    public void testSyncSlotsFromNodeNullArgs() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        config.addNode(node1);

        // null slots 不应抛异常
        config.syncSlotsFromNode(nodeId1, null, 1L);
        // 未知 nodeId 不应抛异常
        java.util.BitSet slots = new java.util.BitSet();
        slots.set(0);
        config.syncSlotsFromNode("unknown", slots, 1L);
        assertEquals(0, config.getAssignedSlotCount());
    }

    @Test
    public void testSyncSlotsFromNodeEqualEpochSameOwnerPatchesBitSet() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(5L);
        config.addNode(node1);
        // slot 100 已归属 node1，但 node1.slots BitSet 故意不含 100（模拟 gossip 先到的残留不一致）
        config.setSlotOwner(100, nodeId1);
        // 清掉 node1 的 BitSet 但保留 slotAssignment，制造不一致
        node1.clearSlots();
        assertEquals(nodeId1, config.getSlotOwner(100));
        assertFalse(node1.hasSlot(100));

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        // 相等 epoch（5==5），owner 已是 node1：应幂等补齐 node1 的 BitSet
        config.syncSlotsFromNode(nodeId1, slots, 5L);

        assertEquals("slot owner 不应变", nodeId1, config.getSlotOwner(100));
        assertTrue("owner 的 BitSet 应被幂等补齐", node1.hasSlot(100));
    }

    @Test
    public void testSyncSlotsFromNodeEqualEpochDifferentOwnerDoesNotSteal() {
        // 相等 epoch 且 curOwner != 提供方时，提供方的 BitSet 也不应被错误填充
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(5L);
        config.addNode(node1);
        config.setSlotOwner(100, nodeId1);

        ClusterNode node2 = new ClusterNode(nodeId2);
        node2.setConfigEpoch(5L);
        config.addNode(node2);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        config.syncSlotsFromNode(nodeId2, slots, 5L);

        assertEquals("相等 epoch 不应抢占", nodeId1, config.getSlotOwner(100));
        assertFalse("非 owner 的 BitSet 不应被填充", node2.hasSlot(100));
    }

    // ==================== 脏标记测试（Redis 7 clusterSaveConfigIfNeeded 机制） ====================

    @Test
    public void testDirtyFlagInitialState() {
        // 新建的 ClusterConfig 应该不是脏的
        assertFalse(config.isDirty());
    }

    @Test
    public void testMarkDirty() {
        config.markDirty();
        assertTrue(config.isDirty());
    }

    @Test
    public void testClearDirty() {
        config.markDirty();
        assertTrue(config.isDirty());
        config.clearDirty();
        assertFalse(config.isDirty());
    }

    @Test
    public void testDirtyFlagAfterMultipleOperations() {
        assertFalse(config.isDirty());
        config.markDirty();
        assertTrue(config.isDirty());
        // 多次标记应保持脏状态
        config.markDirty();
        assertTrue(config.isDirty());
        config.clearDirty();
        assertFalse(config.isDirty());
    }
}
