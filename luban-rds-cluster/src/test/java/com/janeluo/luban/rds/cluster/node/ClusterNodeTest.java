package com.janeluo.luban.rds.cluster.node;

import org.junit.Before;
import org.junit.Test;

import java.util.BitSet;

import static org.junit.Assert.*;

/**
 * ClusterNode 单元测试
 */
public class ClusterNodeTest {

    private ClusterNode node;
    private static final String TEST_NODE_ID = "1234567890abcdef1234567890abcdef12345678";

    @Before
    public void setUp() {
        node = new ClusterNode(TEST_NODE_ID, "127.0.0.1", 6379, 16379);
    }

    @Test
    public void testNodeCreation() {
        assertNotNull(node);
        assertEquals(TEST_NODE_ID, node.getNodeId());
        assertEquals("127.0.0.1", node.getIp());
        assertEquals(6379, node.getPort());
        assertEquals(16379, node.getBusPort());
    }

    @Test
    public void testNodeIdValidation() {
        // 测试正常节点ID
        node.setNodeId("abcdef1234567890abcdef1234567890abcdef12");
        assertEquals("abcdef1234567890abcdef1234567890abcdef12", node.getNodeId());

        // 测试长度不正确
        try {
            node.setNodeId("short");
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("节点ID长度必须为"));
        }

        // 测试非十六进制字符（必须是40字符长度）
        try {
            node.setNodeId("ghij4567890abcdef1234567890abcdef12345678".substring(0, 40));
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("节点ID必须为十六进制字符串"));
        }
    }

    @Test
    public void testStateManagement() {
        // 测试添加状态
        node.addState(ClusterNodeState.MASTER);
        assertTrue(node.hasState(ClusterNodeState.MASTER));
        assertTrue(node.isMaster());

        // 测试移除状态
        node.removeState(ClusterNodeState.MASTER);
        assertFalse(node.hasState(ClusterNodeState.MASTER));
        assertFalse(node.isMaster());

        // 测试从节点状态
        node.addState(ClusterNodeState.SLAVE);
        assertTrue(node.isSlave());

        // 测试本节点标志
        node.addState(ClusterNodeState.MYSELF);
        assertTrue(node.isMyself());

        // 测试下线状态
        node.addState(ClusterNodeState.FAIL);
        assertTrue(node.isFail());
        assertFalse(node.isAvailable());

        // 测试可能下线状态
        node.removeState(ClusterNodeState.FAIL);
        node.addState(ClusterNodeState.PFAIL);
        assertTrue(node.isPfail());
        assertFalse(node.isAvailable());
    }

    @Test
    public void testSlotManagement() {
        // 测试单个槽位
        node.addSlot(0);
        assertTrue(node.hasSlot(0));
        assertEquals(1, node.getSlotCount());

        node.addSlot(100);
        assertTrue(node.hasSlot(100));
        assertEquals(2, node.getSlotCount());

        // 测试槽位范围
        node.addSlotRange(1000, 2000);
        assertTrue(node.hasSlot(1000));
        assertTrue(node.hasSlot(1500));
        assertTrue(node.hasSlot(2000));
        assertEquals(1003, node.getSlotCount());

        // 测试移除槽位
        node.removeSlot(0);
        assertFalse(node.hasSlot(0));
        assertEquals(1002, node.getSlotCount());

        // 测试清空槽位
        node.clearSlots();
        assertEquals(0, node.getSlotCount());
    }

    @Test
    public void testSlotValidation() {
        // 测试无效槽位号
        try {
            node.addSlot(-1);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("槽位号必须在"));
        }

        try {
            node.addSlot(16384);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("槽位号必须在"));
        }

        // 测试槽位范围错误
        try {
            node.addSlotRange(100, 50);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("起始槽位不能大于结束槽位"));
        }
    }

    @Test
    public void testConfigEpoch() {
        assertEquals(0, node.getConfigEpoch());

        // 测试增加配置纪元
        long newEpoch = node.incrementConfigEpoch();
        assertEquals(1, newEpoch);
        assertEquals(1, node.getConfigEpoch());

        // 测试设置更大的配置纪元
        assertTrue(node.setConfigEpochIfGreater(5));
        assertEquals(5, node.getConfigEpoch());

        // 测试设置更小的配置纪元（应该失败）
        assertFalse(node.setConfigEpochIfGreater(3));
        assertEquals(5, node.getConfigEpoch());
    }

    @Test
    public void testTimeMethods() throws InterruptedException {
        // 测试更新PING时间
        long beforePing = System.currentTimeMillis();
        node.updateLastPingTime();
        long afterPing = System.currentTimeMillis();
        assertTrue(node.getLastPingTime() >= beforePing);
        assertTrue(node.getLastPingTime() <= afterPing);

        // 测试更新PONG时间
        Thread.sleep(10);
        long beforePong = System.currentTimeMillis();
        node.updateLastPongTime();
        long afterPong = System.currentTimeMillis();
        assertTrue(node.getLastPongTime() >= beforePong);
        assertTrue(node.getLastPongTime() <= afterPong);

        // 测试时间间隔计算
        Thread.sleep(10);
        assertTrue(node.getTimeSinceLastPong() > 0);
        assertTrue(node.getTimeSinceLastPing() > 0);
    }

    @Test
    public void testPortValidation() {
        // 测试无效端口号
        try {
            node.setPort(-1);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("端口号必须在"));
        }

        try {
            node.setPort(65536);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("端口号必须在"));
        }

        try {
            node.setBusPort(-1);
            fail("应该抛出IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("集群总线端口必须在"));
        }
    }

    @Test
    public void testAddressMethods() {
        assertEquals("127.0.0.1:6379", node.getAddress());
        assertEquals("127.0.0.1:6379@16379", node.getFullAddress());
    }

    @Test
    public void testClusterLink() {
        ClusterLink link = node.getLink();
        assertNotNull(link);
        assertFalse(link.isConnected());

        link.setConnected(true);
        assertTrue(link.isConnected());
        assertTrue(node.getLink().isConnected());
    }

    @Test
    public void testReset() {
        // 设置各种状态
        node.addState(ClusterNodeState.MASTER);
        node.addSlot(100);
        node.setConfigEpoch(10);
        node.setMasterNodeId("master123");

        // 重置
        node.reset();

        // 验证重置后的状态
        assertTrue(node.getState().isEmpty());
        assertEquals(0, node.getSlotCount());
        assertEquals(0, node.getConfigEpoch());
        assertNull(node.getMasterNodeId());
    }

    @Test
    public void testEqualsAndHashCode() {
        ClusterNode node1 = new ClusterNode(TEST_NODE_ID);
        ClusterNode node2 = new ClusterNode(TEST_NODE_ID);
        ClusterNode node3 = new ClusterNode("abcdef1234567890abcdef1234567890abcdef12");

        // 测试equals
        assertEquals(node1, node2);
        assertNotEquals(node1, node3);

        // 测试hashCode
        assertEquals(node1.hashCode(), node2.hashCode());
    }

    @Test
    public void testToString() {
        String str = node.toString();
        assertTrue(str.contains(TEST_NODE_ID));
        assertTrue(str.contains("127.0.0.1"));
        assertTrue(str.contains("6379"));
        assertTrue(str.contains("16379"));
    }

    @Test
    public void testSetSlots() {
        BitSet newSlots = new BitSet(ClusterNode.CLUSTER_SLOTS);
        newSlots.set(0, 100);

        node.setSlots(newSlots);
        assertEquals(100, node.getSlotCount());
        assertTrue(node.hasSlot(0));
        assertTrue(node.hasSlot(99));

        // 测试设置null
        node.setSlots(null);
        assertEquals(0, node.getSlotCount());
    }

    @Test
    public void testSetState() {
        java.util.Set<ClusterNodeState> states = new java.util.HashSet<>();
        states.add(ClusterNodeState.MASTER);
        states.add(ClusterNodeState.MYSELF);

        node.setState(states);
        assertTrue(node.isMaster());
        assertTrue(node.isMyself());

        // 测试设置null
        node.setState(null);
        assertNotNull(node.getState());
        assertTrue(node.getState().isEmpty());
    }
}
