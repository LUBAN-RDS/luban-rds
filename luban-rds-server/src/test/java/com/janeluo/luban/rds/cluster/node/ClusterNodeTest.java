package com.janeluo.luban.rds.cluster.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusterNode 集群节点数据模型单元测试
 *
 * <p>覆盖构造方法、数据校验、状态管理、槽位管理、配置纪元、时间方法、
 * 地址方法、关联关系（ClusterLink）、重置及 equals/hashCode/toString。
 * 包含正向、负向与边界值测试。</p>
 */
@DisplayName("ClusterNode 集群节点模型测试")
class ClusterNodeTest {

    private static final String VALID_NODE_ID = "1234567890abcdef1234567890abcdef12345678";
    private static final String ANOTHER_NODE_ID = "abcdef1234567890abcdef1234567890abcdef12";

    private ClusterNode node;

    @BeforeEach
    void setUp() {
        node = new ClusterNode(VALID_NODE_ID, "127.0.0.1", 6379, 16379);
    }

    // ==================== 构造方法测试 ====================

    @Nested
    @DisplayName("构造方法测试")
    class ConstructorTest {

        @Test
        @DisplayName("默认构造方法初始化默认值")
        void testDefaultConstructor() {
            ClusterNode defaultNode = new ClusterNode();
            assertAll("默认值校验",
                    () -> assertNull(defaultNode.getNodeId()),
                    () -> assertNotNull(defaultNode.getState()),
                    () -> assertTrue(defaultNode.getState().isEmpty()),
                    () -> assertEquals(0, defaultNode.getSlotCount()),
                    () -> assertEquals(0, defaultNode.getConfigEpoch()),
                    () -> assertEquals(0, defaultNode.getLastPingTime()),
                    () -> assertTrue(defaultNode.getLastPongTime() > 0),
                    () -> assertNotNull(defaultNode.getLink()),
                    () -> assertNull(defaultNode.getMasterNodeId())
            );
        }

        @Test
        @DisplayName("带节点ID的构造方法")
        void testConstructorWithNodeId() {
            ClusterNode idNode = new ClusterNode(VALID_NODE_ID);
            assertEquals(VALID_NODE_ID, idNode.getNodeId());
        }

        @Test
        @DisplayName("完整构造方法设置所有地址信息")
        void testFullConstructor() {
            assertAll("完整构造校验",
                    () -> assertEquals(VALID_NODE_ID, node.getNodeId()),
                    () -> assertEquals("127.0.0.1", node.getIp()),
                    () -> assertEquals(6379, node.getPort()),
                    () -> assertEquals(16379, node.getBusPort())
            );
        }
    }

    // ==================== 节点ID校验测试 ====================

    @Nested
    @DisplayName("节点ID校验测试")
    class NodeIdValidationTest {

        @Test
        @DisplayName("设置合法的节点ID")
        void testSetValidNodeId() {
            node.setNodeId(ANOTHER_NODE_ID);
            assertEquals(ANOTHER_NODE_ID, node.getNodeId());
        }

        @Test
        @DisplayName("设置null节点ID应允许")
        void testSetNullNodeId() {
            node.setNodeId(null);
            assertNull(node.getNodeId());
        }

        @Test
        @DisplayName("设置长度不正确的节点ID应抛出异常")
        void testSetNodeIdWithWrongLength() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setNodeId("short"));
            assertTrue(ex.getMessage().contains("节点ID长度必须为"));
        }

        @Test
        @DisplayName("设置非十六进制字符的节点ID应抛出异常")
        void testSetNodeIdWithNonHexChars() {
            // 40个字符但包含非十六进制字符 'z'
            String invalidHex = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setNodeId(invalidHex));
            assertTrue(ex.getMessage().contains("节点ID必须为十六进制字符串"));
        }

        @Test
        @DisplayName("大写十六进制节点ID应被接受")
        void testSetNodeIdWithUpperCaseHex() {
            String upperHex = "ABCDEF1234567890ABCDEF1234567890ABCDEF12";
            node.setNodeId(upperHex);
            assertEquals(upperHex, node.getNodeId());
        }
    }

    // ==================== 端口校验测试 ====================

    @Nested
    @DisplayName("端口校验测试")
    class PortValidationTest {

        @Test
        @DisplayName("设置边界端口值 0 和 65535")
        void testSetBoundaryPorts() {
            node.setPort(0);
            assertEquals(0, node.getPort());
            node.setPort(65535);
            assertEquals(65535, node.getPort());
        }

        @Test
        @DisplayName("设置负端口应抛出异常")
        void testSetNegativePort() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setPort(-1));
            assertTrue(ex.getMessage().contains("端口号必须在"));
        }

        @Test
        @DisplayName("设置超出范围的端口应抛出异常")
        void testSetOverflowPort() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setPort(65536));
            assertTrue(ex.getMessage().contains("端口号必须在"));
        }

        @Test
        @DisplayName("设置边界总线端口值 0 和 65535")
        void testSetBoundaryBusPorts() {
            node.setBusPort(0);
            assertEquals(0, node.getBusPort());
            node.setBusPort(65535);
            assertEquals(65535, node.getBusPort());
        }

        @Test
        @DisplayName("设置负总线端口应抛出异常")
        void testSetNegativeBusPort() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setBusPort(-1));
            assertTrue(ex.getMessage().contains("集群总线端口必须在"));
        }

        @Test
        @DisplayName("设置超出范围的总线端口应抛出异常")
        void testSetOverflowBusPort() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.setBusPort(65536));
            assertTrue(ex.getMessage().contains("集群总线端口必须在"));
        }
    }

    // ==================== 状态管理测试 ====================

    @Nested
    @DisplayName("状态管理测试")
    class StateManagementTest {

        @Test
        @DisplayName("添加和移除状态")
        void testAddAndRemoveState() {
            node.addState(ClusterNodeState.MASTER);
            assertTrue(node.hasState(ClusterNodeState.MASTER));

            node.removeState(ClusterNodeState.MASTER);
            assertFalse(node.hasState(ClusterNodeState.MASTER));
        }

        @Test
        @DisplayName("isMaster/isSlave/isMyself 状态判断")
        void testRoleStates() {
            node.addState(ClusterNodeState.MASTER);
            assertTrue(node.isMaster());
            assertFalse(node.isSlave());

            node.removeState(ClusterNodeState.MASTER);
            node.addState(ClusterNodeState.SLAVE);
            assertFalse(node.isMaster());
            assertTrue(node.isSlave());

            node.addState(ClusterNodeState.MYSELF);
            assertTrue(node.isMyself());
        }

        @Test
        @DisplayName("isFail/isPfail 状态判断")
        void testFailStates() {
            node.addState(ClusterNodeState.FAIL);
            assertTrue(node.isFail());
            assertFalse(node.isAvailable());

            node.removeState(ClusterNodeState.FAIL);
            node.addState(ClusterNodeState.PFAIL);
            assertTrue(node.isPfail());
            assertFalse(node.isAvailable());
        }

        @Test
        @DisplayName("无下线状态时节点可用")
        void testIsAvailableWhenNoFail() {
            node.addState(ClusterNodeState.MASTER);
            assertTrue(node.isAvailable());
        }

        @Test
        @DisplayName("同时存在 FAIL 和 PFAIL 时不可用")
        void testIsAvailableWhenBothFail() {
            node.addState(ClusterNodeState.FAIL);
            node.addState(ClusterNodeState.PFAIL);
            assertFalse(node.isAvailable());
        }

        @Test
        @DisplayName("使用 EnumSet 设置状态集合")
        void testSetStateWithEnumSet() {
            Set<ClusterNodeState> states = EnumSet.of(
                    ClusterNodeState.MASTER, ClusterNodeState.MYSELF);
            node.setState(states);
            assertAll("EnumSet 状态校验",
                    () -> assertTrue(node.isMaster()),
                    () -> assertTrue(node.isMyself()),
                    () -> assertFalse(node.isSlave())
            );
        }

        @Test
        @DisplayName("使用 HashSet 设置状态集合")
        void testSetStateWithHashSet() {
            Set<ClusterNodeState> states = new HashSet<>();
            states.add(ClusterNodeState.SLAVE);
            node.setState(states);
            assertTrue(node.isSlave());
        }

        @Test
        @DisplayName("设置 null 状态集合应清空为空集合")
        void testSetStateWithNull() {
            node.addState(ClusterNodeState.MASTER);
            node.setState(null);
            assertNotNull(node.getState());
            assertTrue(node.getState().isEmpty());
        }
    }

    // ==================== 槽位管理测试 ====================

    @Nested
    @DisplayName("槽位管理测试")
    class SlotManagementTest {

        @Test
        @DisplayName("添加和移除单个槽位")
        void testAddAndRemoveSingleSlot() {
            node.addSlot(0);
            assertTrue(node.hasSlot(0));
            assertEquals(1, node.getSlotCount());

            node.addSlot(100);
            assertEquals(2, node.getSlotCount());

            node.removeSlot(0);
            assertFalse(node.hasSlot(0));
            assertEquals(1, node.getSlotCount());
        }

        @Test
        @DisplayName("添加槽位范围")
        void testAddSlotRange() {
            node.addSlotRange(1000, 2000);
            assertAll("范围校验",
                    () -> assertTrue(node.hasSlot(1000)),
                    () -> assertTrue(node.hasSlot(1500)),
                    () -> assertTrue(node.hasSlot(2000)),
                    () -> assertEquals(1001, node.getSlotCount())
            );
        }

        @Test
        @DisplayName("添加单个槽位与范围组合")
        void testAddSlotAndRangeCombined() {
            node.addSlot(0);
            node.addSlot(100);
            node.addSlotRange(1000, 2000);
            assertEquals(1003, node.getSlotCount());
        }

        @Test
        @DisplayName("清空所有槽位")
        void testClearSlots() {
            node.addSlotRange(0, 99);
            node.clearSlots();
            assertEquals(0, node.getSlotCount());
        }

        @Test
        @DisplayName("设置 BitSet 槽位")
        void testSetSlots() {
            BitSet newSlots = new BitSet(ClusterNode.CLUSTER_SLOTS);
            newSlots.set(0, 100);
            node.setSlots(newSlots);
            assertEquals(100, node.getSlotCount());
            assertTrue(node.hasSlot(0));
            assertTrue(node.hasSlot(99));
        }

        @Test
        @DisplayName("设置 null 槽位应重置为空 BitSet")
        void testSetNullSlots() {
            node.addSlot(0);
            node.setSlots(null);
            assertEquals(0, node.getSlotCount());
        }

        @Test
        @DisplayName("边界槽位 0 和 16383")
        void testBoundarySlots() {
            node.addSlot(0);
            node.addSlot(ClusterNode.CLUSTER_SLOTS - 1);
            assertTrue(node.hasSlot(0));
            assertTrue(node.hasSlot(ClusterNode.CLUSTER_SLOTS - 1));
            assertEquals(2, node.getSlotCount());
        }

        @Test
        @DisplayName("负槽位号应抛出异常")
        void testNegativeSlot() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.addSlot(-1));
            assertTrue(ex.getMessage().contains("槽位号必须在"));
        }

        @Test
        @DisplayName("超出范围的槽位号应抛出异常")
        void testOverflowSlot() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.addSlot(ClusterNode.CLUSTER_SLOTS));
            assertTrue(ex.getMessage().contains("槽位号必须在"));
        }

        @Test
        @DisplayName("hasSlot 越界应抛出异常")
        void testHasSlotOverflow() {
            assertThrows(IllegalArgumentException.class, () -> node.hasSlot(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> node.hasSlot(ClusterNode.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("removeSlot 越界应抛出异常")
        void testRemoveSlotOverflow() {
            assertThrows(IllegalArgumentException.class, () -> node.removeSlot(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> node.removeSlot(ClusterNode.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("起始槽位大于结束槽位应抛出异常")
        void testInvalidSlotRange() {
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> node.addSlotRange(100, 50));
            assertTrue(ex.getMessage().contains("起始槽位不能大于结束槽位"));
        }

        @Test
        @DisplayName("addSlotRange 边界范围起始和结束相同")
        void testAddSlotRangeSingle() {
            node.addSlotRange(500, 500);
            assertEquals(1, node.getSlotCount());
            assertTrue(node.hasSlot(500));
        }
    }

    // ==================== 配置纪元测试 ====================

    @Nested
    @DisplayName("配置纪元测试")
    class ConfigEpochTest {

        @Test
        @DisplayName("初始纪元为 0")
        void testInitialEpoch() {
            assertEquals(0, node.getConfigEpoch());
        }

        @Test
        @DisplayName("递增配置纪元")
        void testIncrementEpoch() {
            assertEquals(1, node.incrementConfigEpoch());
            assertEquals(1, node.getConfigEpoch());
            assertEquals(2, node.incrementConfigEpoch());
            assertEquals(2, node.getConfigEpoch());
        }

        @Test
        @DisplayName("setConfigEpochIfGreater 设置更大值成功")
        void testSetEpochIfGreater() {
            assertTrue(node.setConfigEpochIfGreater(5));
            assertEquals(5, node.getConfigEpoch());
        }

        @Test
        @DisplayName("setConfigEpochIfGreater 设置更小值失败")
        void testSetEpochIfSmaller() {
            node.setConfigEpoch(10);
            assertFalse(node.setConfigEpochIfGreater(3));
            assertEquals(10, node.getConfigEpoch());
        }

        @Test
        @DisplayName("setConfigEpochIfGreater 设置相等值失败")
        void testSetEpochIfEqual() {
            node.setConfigEpoch(10);
            assertFalse(node.setConfigEpochIfGreater(10));
            assertEquals(10, node.getConfigEpoch());
        }

        @Test
        @DisplayName("直接设置配置纪元")
        void testSetConfigEpochDirectly() {
            node.setConfigEpoch(100);
            assertEquals(100, node.getConfigEpoch());
        }
    }

    // ==================== 时间方法测试 ====================

    @Nested
    @DisplayName("时间方法测试")
    class TimeMethodTest {

        @Test
        @DisplayName("更新最后 PING 时间")
        void testUpdateLastPingTime() throws InterruptedException {
            long before = System.currentTimeMillis();
            node.updateLastPingTime();
            long after = System.currentTimeMillis();
            assertAll("PING 时间校验",
                    () -> assertTrue(node.getLastPingTime() >= before),
                    () -> assertTrue(node.getLastPingTime() <= after)
            );
        }

        @Test
        @DisplayName("更新最后 PONG 时间")
        void testUpdateLastPongTime() throws InterruptedException {
            long before = System.currentTimeMillis();
            node.updateLastPongTime();
            long after = System.currentTimeMillis();
            assertAll("PONG 时间校验",
                    () -> assertTrue(node.getLastPongTime() >= before),
                    () -> assertTrue(node.getLastPongTime() <= after)
            );
        }

        @Test
        @DisplayName("获取距离上次 PONG 的时间间隔为正数")
        void testGetTimeSinceLastPong() throws InterruptedException {
            node.updateLastPongTime();
            Thread.sleep(10);
            assertTrue(node.getTimeSinceLastPong() > 0);
        }

        @Test
        @DisplayName("未发送 PING 时时间间隔为 0")
        void testGetTimeSinceLastPingWhenZero() {
            assertEquals(0, node.getTimeSinceLastPing());
        }

        @Test
        @DisplayName("发送 PING 后时间间隔为正数")
        void testGetTimeSinceLastPingAfterPing() throws InterruptedException {
            node.updateLastPingTime();
            Thread.sleep(10);
            assertTrue(node.getTimeSinceLastPing() > 0);
        }
    }

    // ==================== 地址与关联关系测试 ====================

    @Nested
    @DisplayName("地址与关联关系测试")
    class AddressAndLinkTest {

        @Test
        @DisplayName("获取节点地址字符串")
        void testGetAddress() {
            assertEquals("127.0.0.1:6379", node.getAddress());
        }

        @Test
        @DisplayName("获取节点完整地址字符串")
        void testGetFullAddress() {
            assertEquals("127.0.0.1:6379@16379", node.getFullAddress());
        }

        @Test
        @DisplayName("默认关联的 ClusterLink 不为空且未连接")
        void testDefaultLink() {
            ClusterLink link = node.getLink();
            assertNotNull(link);
            assertFalse(link.isConnected());
        }

        @Test
        @DisplayName("设置新的 ClusterLink")
        void testSetLink() {
            ClusterLink newLink = new ClusterLink(true, System.currentTimeMillis(), 1024);
            node.setLink(newLink);
            assertTrue(node.getLink().isConnected());
            assertEquals(1024, node.getLink().getOutboundBufferSize());
        }

        @Test
        @DisplayName("设置 null ClusterLink 应创建默认连接")
        void testSetNullLink() {
            node.setLink(null);
            assertNotNull(node.getLink());
            assertFalse(node.getLink().isConnected());
        }

        @Test
        @DisplayName("设置主节点ID")
        void testMasterNodeId() {
            node.setMasterNodeId(ANOTHER_NODE_ID);
            assertEquals(ANOTHER_NODE_ID, node.getMasterNodeId());
        }
    }

    // ==================== 重置方法测试 ====================

    @Nested
    @DisplayName("重置方法测试")
    class ResetTest {

        @Test
        @DisplayName("重置后状态、槽位、纪元、主节点ID均清空")
        void testReset() {
            node.addState(ClusterNodeState.MASTER);
            node.addSlot(100);
            node.addSlot(200);
            node.setConfigEpoch(10);
            node.setMasterNodeId(ANOTHER_NODE_ID);
            node.updateLastPingTime();

            node.reset();

            assertAll("重置后校验",
                    () -> assertTrue(node.getState().isEmpty()),
                    () -> assertEquals(0, node.getSlotCount()),
                    () -> assertEquals(0, node.getConfigEpoch()),
                    () -> assertNull(node.getMasterNodeId()),
                    () -> assertEquals(0, node.getLastPingTime()),
                    () -> assertTrue(node.getLastPongTime() > 0),
                    () -> assertNotNull(node.getLink()),
                    () -> assertFalse(node.getLink().isConnected())
            );
        }
    }

    // ==================== equals/hashCode/toString 测试 ====================

    @Nested
    @DisplayName("equals/hashCode/toString 测试")
    class EqualsHashCodeToStringTest {

        @Test
        @DisplayName("相同节点ID的节点相等")
        void testEqualsSameId() {
            ClusterNode node1 = new ClusterNode(VALID_NODE_ID);
            ClusterNode node2 = new ClusterNode(VALID_NODE_ID);
            assertEquals(node1, node2);
            assertEquals(node1.hashCode(), node2.hashCode());
        }

        @Test
        @DisplayName("不同节点ID的节点不相等")
        void testNotEqualsDifferentId() {
            ClusterNode node1 = new ClusterNode(VALID_NODE_ID);
            ClusterNode node2 = new ClusterNode(ANOTHER_NODE_ID);
            assertNotEquals(node1, node2);
        }

        @Test
        @DisplayName("与自身相等")
        void testEqualsSelf() {
            assertEquals(node, node);
        }

        @Test
        @DisplayName("与 null 不相等")
        void testNotEqualsNull() {
            assertNotEquals(node, null);
        }

        @Test
        @DisplayName("与非 ClusterNode 类型不相等")
        void testNotEqualsDifferentType() {
            assertNotEquals(node, "some string");
        }

        @Test
        @DisplayName("toString 包含关键字段")
        void testToString() {
            String str = node.toString();
            assertAll("toString 校验",
                    () -> assertTrue(str.contains(VALID_NODE_ID)),
                    () -> assertTrue(str.contains("127.0.0.1")),
                    () -> assertTrue(str.contains("6379")),
                    () -> assertTrue(str.contains("16379"))
            );
        }
    }
}
