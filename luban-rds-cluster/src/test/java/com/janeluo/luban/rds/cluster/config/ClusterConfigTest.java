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
    public void testSyncSlotsFromNodeOversizedBitmapRejectedWithoutPartialApplication() {
        // N-3：位图含超过 16383 的位（协议违规）时整体拒绝，
        // 不应出现"已转移的槽位生效、剩余中止"的半应用状态
        ClusterNode node1 = new ClusterNode(nodeId1);
        config.addNode(node1);

        java.util.BitSet slots = new java.util.BitSet();
        slots.set(0);
        slots.set(20000); // 越界位

        config.syncSlotsFromNode(nodeId1, slots, 1L);

        assertEquals("越界位图应整体拒绝，不得部分应用", 0, config.getAssignedSlotCount());
        assertNull("slot 0 不应被分配（即使它是合法位）", config.getSlotOwner(0));
        assertFalse(node1.hasSlot(0));
    }

    @Test
    public void testSyncSlotsFromNodeMarksDirtyOnActualChange() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        config.addNode(node1);

        config.clearDirty();
        java.util.BitSet slots = new java.util.BitSet();
        slots.set(0);

        // 实际变更（分配新槽位）→ 应置脏（P1-4：gossip 拓扑变更必须落盘）
        config.syncSlotsFromNode(nodeId1, slots, 1L);
        assertTrue("实际槽位变更应置脏", config.isDirty());

        // 幂等重放（owner/位图均一致）→ 不应再次置脏（避免每次心跳都触发写盘）
        config.clearDirty();
        config.syncSlotsFromNode(nodeId1, slots, 1L);
        assertFalse("无实际变更不应置脏", config.isDirty());

        // 删除路径（advertised 位图不再含 slot 0，epoch 不落后）→ 置脏
        config.clearDirty();
        java.util.BitSet emptySlots = new java.util.BitSet();
        config.syncSlotsFromNode(nodeId1, emptySlots, 1L);
        assertTrue("槽位删除应置脏", config.isDirty());
        assertNull(config.getSlotOwner(0));
    }

    // ==================== N-27：clearDirtyIfUnchanged（保存期间新变更不清脏） ====================

    @Test
    public void testN27DirtyVersionIncrementsOnMarkDirty() {
        long v0 = config.getDirtyVersion();
        config.markDirty();
        assertEquals("markDirty 应递增版本号", v0 + 1, config.getDirtyVersion());
        config.markDirty();
        assertEquals("多次 markDirty 版本号单调递增", v0 + 2, config.getDirtyVersion());
    }

    @Test
    public void testN27ClearDirtyIfUnchangedClearsWhenNoNewChanges() {
        config.markDirty();
        long version = config.getDirtyVersion();
        assertTrue("保存期间无新变更时应清除脏标记",
                config.clearDirtyIfUnchanged(version));
        assertFalse(config.isDirty());
    }

    @Test
    public void testN27ClearDirtyIfUnchangedKeepsDirtyWhenChangeDuringSave() {
        config.markDirty();
        long version = config.getDirtyVersion();
        // 模拟保存期间发生仅 markDirty 的变更（如 recordVoteEpoch 只置脏、不触发回调）
        config.markDirty();
        assertFalse("保存期间有新变更时不得清脏（旧 clearDirty 会抹掉该变更）",
                config.clearDirtyIfUnchanged(version));
        assertTrue("脏标记应保留，使后续周期保存落盘", config.isDirty());
    }

    @Test
    public void testN27ClearDirtyIfUnchangedWithStaleSnapshot() {
        config.markDirty();
        config.markDirty();
        long staleVersion = config.getDirtyVersion() - 1;
        assertFalse("过期版本快照不应清脏", config.clearDirtyIfUnchanged(staleVersion));
        assertTrue(config.isDirty());
    }

    @Test
    public void testSyncSlotsFromNodeUnchangedSameOwnerDoesNotMarkDirty() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(1L);
        config.addNode(node1);
        config.setSlotOwner(100, nodeId1);

        config.clearDirty();
        java.util.BitSet slots = new java.util.BitSet();
        slots.set(100);

        // owner 相同且位图一致 → 幂等，不置脏
        config.syncSlotsFromNode(nodeId1, slots, 1L);
        assertFalse(config.isDirty());
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

    // ==================== P1-2B：syncSlotsFromNode 槽位移除（epoch 守卫） ====================

    /**
     * P1-2B：节点迁出槽位后，gossip 通告的位图不再含该槽位，
     * syncSlotsFromNode 应移除本地残留的所有权，使槽位归属收敛。
     */
    @Test
    public void testSyncSlotsFromNodeRemovesSlotsNoLongerAdvertised() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(5L);
        config.addNode(node1);
        // node1 原持有 slot 0 和 slot 100
        java.util.BitSet initial = new java.util.BitSet();
        initial.set(0);
        initial.set(100);
        config.syncSlotsFromNode(nodeId1, initial, 5L);
        assertEquals(nodeId1, config.getSlotOwner(0));
        assertEquals(nodeId1, config.getSlotOwner(100));

        // node1 迁出 slot 100，advertised 位图只剩 slot 0，epoch 不低于上次
        java.util.BitSet migrated = new java.util.BitSet();
        migrated.set(0);
        config.syncSlotsFromNode(nodeId1, migrated, 5L);

        assertEquals("slot 0 应保留", nodeId1, config.getSlotOwner(0));
        assertNull("slot 100 应被移除（不再 advertised）", config.getSlotOwner(100));
        assertFalse("node1 不应再持有 slot 100", node1.hasSlot(100));
    }

    /**
     * P1-2B：陈旧的 gossip 分片（epoch 偏低）不应冲掉更新的槽位变更。
     */
    @Test
    public void testSyncSlotsFromNodeStaleEpochDoesNotRemove() {
        ClusterNode node1 = new ClusterNode(nodeId1);
        node1.setConfigEpoch(10L);
        config.addNode(node1);
        java.util.BitSet initial = new java.util.BitSet();
        initial.set(0);
        initial.set(100);
        config.syncSlotsFromNode(nodeId1, initial, 10L);

        // 更新 node1 的 configEpoch（模拟后续 failover 提升了它的纪元）
        node1.setConfigEpoch(20L);

        // 陈旧分片：epoch=5 < 20，位图不含 slot 100，但不应移除
        java.util.BitSet stale = new java.util.BitSet();
        stale.set(0);
        config.syncSlotsFromNode(nodeId1, stale, 5L);

        assertEquals("陈旧分片不应移除 slot 100", nodeId1, config.getSlotOwner(100));
        assertTrue("node1 仍应持有 slot 100", node1.hasSlot(100));
    }

    // ==================== P1-3：FORGET 黑名单 ====================

    @Test
    public void testBlacklistNodeIsBlacklistedWithinTtl() {
        config.blacklistNode(nodeId1);
        assertTrue("加入黑名单后应处于黑名单内", config.isBlacklisted(nodeId1));
    }

    @Test
    public void testBlacklistNullAndUnknownReturnsFalse() {
        assertFalse(config.isBlacklisted(null));
        assertFalse(config.isBlacklisted("unknown-node-id"));
    }

    @Test
    public void testCleanupBlacklistRemovesExpiredEntries() {
        // 黑名单条目 TTL 由实现常量决定，这里验证 cleanup 不影响未过期条目
        config.blacklistNode(nodeId1);
        config.cleanupBlacklist();
        assertTrue("未过期条目应保留", config.isBlacklisted(nodeId1));
    }
}
