package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClusterConfig 集群配置模型单元测试
 *
 * <p>覆盖构造方法、节点 CRUD、槽位分配/清除、关联关系处理、配置纪元、
 * 集群状态、主从计数、重置及异常处理。包含正向、负向与边界值测试。</p>
 */
@DisplayName("ClusterConfig 集群配置模型测试")
class ClusterConfigTest {

    private static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    private static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";

    private ClusterConfig config;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
    }

    // ==================== 构造方法测试 ====================

    @Nested
    @DisplayName("构造方法测试")
    class ConstructorTest {

        @Test
        @DisplayName("默认构造方法初始化默认值")
        void testDefaultConstructor() {
            assertAll("默认值校验",
                    () -> assertNotNull(config),
                    () -> assertNull(config.getMyNodeId()),
                    () -> assertEquals(0, config.getCurrentEpoch()),
                    () -> assertEquals(0, config.getConfigEpoch()),
                    () -> assertEquals("fail", config.getState()),
                    () -> assertEquals(0, config.getNodeCount()),
                    () -> assertEquals(0, config.getAssignedSlotCount()),
                    () -> assertFalse(config.areAllSlotsAssigned())
            );
        }

        @Test
        @DisplayName("带当前节点ID的构造方法")
        void testConstructorWithMyNodeId() {
            ClusterConfig configWithId = new ClusterConfig(NODE_ID_1);
            assertEquals(NODE_ID_1, configWithId.getMyNodeId());
        }
    }

    // ==================== 节点 CRUD 测试 ====================

    @Nested
    @DisplayName("节点 CRUD 测试")
    class NodeCrudTest {

        @Test
        @DisplayName("添加节点")
        void testAddNode() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            node.setIp("127.0.0.1");
            node.setPort(7000);
            config.addNode(node);

            assertAll("添加节点校验",
                    () -> assertEquals(1, config.getNodeCount()),
                    () -> assertTrue(config.hasNode(NODE_ID_1)),
                    () -> assertNotNull(config.getNode(NODE_ID_1))
            );
        }

        @Test
        @DisplayName("添加 null 节点应抛出异常")
        void testAddNullNode() {
            assertThrows(IllegalArgumentException.class, () -> config.addNode(null));
        }

        @Test
        @DisplayName("添加节点ID为null的节点应抛出异常")
        void testAddNodeWithNullId() {
            ClusterNode node = new ClusterNode();
            assertThrows(IllegalArgumentException.class, () -> config.addNode(node));
        }

        @Test
        @DisplayName("移除节点")
        void testRemoveNode() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            assertTrue(config.hasNode(NODE_ID_1));

            config.removeNode(NODE_ID_1);
            assertAll("移除节点校验",
                    () -> assertFalse(config.hasNode(NODE_ID_1)),
                    () -> assertEquals(0, config.getNodeCount()),
                    () -> assertNull(config.getNode(NODE_ID_1))
            );
        }

        @Test
        @DisplayName("移除不存在的节点不抛异常")
        void testRemoveNonExistentNode() {
            config.removeNode(NODE_ID_1);
            assertEquals(0, config.getNodeCount());
        }

        @Test
        @DisplayName("移除 null 节点ID不抛异常")
        void testRemoveNullNodeId() {
            config.removeNode(null);
            assertEquals(0, config.getNodeCount());
        }

        @Test
        @DisplayName("移除节点时同时清除其负责的槽位")
        void testRemoveNodeWithSlots() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            for (int i = 0; i <= 100; i++) {
                config.setSlotOwner(i, NODE_ID_1);
            }
            assertEquals(101, config.getAssignedSlotCount());

            config.removeNode(NODE_ID_1);
            assertEquals(0, config.getAssignedSlotCount());
            for (int i = 0; i <= 100; i++) {
                assertNull(config.getSlotOwner(i));
            }
        }

        @Test
        @DisplayName("获取所有节点")
        void testGetAllNodes() {
            config.addNode(new ClusterNode(NODE_ID_1));
            config.addNode(new ClusterNode(NODE_ID_2));
            assertEquals(2, config.getAllNodes().size());
        }

        @Test
        @DisplayName("获取当前节点")
        void testGetMyNode() {
            config.setMyNodeId(NODE_ID_1);
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);

            ClusterNode myNode = config.getMyNode();
            assertNotNull(myNode);
            assertEquals(NODE_ID_1, myNode.getNodeId());
        }

        @Test
        @DisplayName("当前节点ID为null时返回null")
        void testGetMyNodeWhenNullId() {
            assertNull(config.getMyNode());
        }

        @Test
        @DisplayName("当前节点不存在时返回null")
        void testGetMyNodeWhenNotExists() {
            config.setMyNodeId(NODE_ID_1);
            assertNull(config.getMyNode());
        }

        @Test
        @DisplayName("设置节点集合")
        void testSetNodes() {
            Map<String, ClusterNode> nodes = new HashMap<>();
            nodes.put(NODE_ID_1, new ClusterNode(NODE_ID_1));
            nodes.put(NODE_ID_2, new ClusterNode(NODE_ID_2));
            config.setNodes(nodes);

            assertEquals(2, config.getNodeCount());
            assertTrue(config.hasNode(NODE_ID_1));
            assertTrue(config.hasNode(NODE_ID_2));
        }

        @Test
        @DisplayName("设置 null 节点集合应重置为空集合")
        void testSetNullNodes() {
            config.addNode(new ClusterNode(NODE_ID_1));
            config.setNodes(null);
            assertEquals(0, config.getNodeCount());
        }
    }

    // ==================== 槽位管理测试 ====================

    @Nested
    @DisplayName("槽位管理测试")
    class SlotManagementTest {

        @Test
        @DisplayName("设置槽位负责节点")
        void testSetSlotOwner() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);

            config.setSlotOwner(0, NODE_ID_1);
            assertAll("设置槽位校验",
                    () -> assertEquals(NODE_ID_1, config.getSlotOwner(0)),
                    () -> assertEquals(1, config.getAssignedSlotCount()),
                    () -> assertTrue(node.hasSlot(0))
            );
        }

        @Test
        @DisplayName("为不存在的节点设置槽位（仍记录分配）")
        void testSetSlotOwnerForNonExistentNode() {
            config.setSlotOwner(0, NODE_ID_1);
            assertEquals(NODE_ID_1, config.getSlotOwner(0));
            assertEquals(1, config.getAssignedSlotCount());
        }

        @Test
        @DisplayName("获取槽位负责节点对象")
        void testGetSlotOwnerNode() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            config.setSlotOwner(0, NODE_ID_1);

            ClusterNode owner = config.getSlotOwnerNode(0);
            assertNotNull(owner);
            assertEquals(NODE_ID_1, owner.getNodeId());
        }

        @Test
        @DisplayName("未分配槽位的负责节点为null")
        void testGetSlotOwnerNodeUnassigned() {
            assertNull(config.getSlotOwnerNode(0));
        }

        @Test
        @DisplayName("清除槽位分配")
        void testClearSlot() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            config.setSlotOwner(0, NODE_ID_1);

            config.clearSlot(0);
            assertAll("清除槽位校验",
                    () -> assertNull(config.getSlotOwner(0)),
                    () -> assertEquals(0, config.getAssignedSlotCount()),
                    () -> assertFalse(node.hasSlot(0))
            );
        }

        @Test
        @DisplayName("清除未分配的槽位不抛异常")
        void testClearUnassignedSlot() {
            config.clearSlot(0);
            assertEquals(0, config.getAssignedSlotCount());
        }

        @Test
        @DisplayName("重新分配槽位（从节点A到节点B）计数不变")
        void testReassignSlot() {
            ClusterNode nodeA = new ClusterNode(NODE_ID_1);
            ClusterNode nodeB = new ClusterNode(NODE_ID_2);
            config.addNode(nodeA);
            config.addNode(nodeB);

            config.setSlotOwner(0, NODE_ID_1);
            assertEquals(1, config.getAssignedSlotCount());

            config.setSlotOwner(0, NODE_ID_2);
            assertEquals(1, config.getAssignedSlotCount());
            assertEquals(NODE_ID_2, config.getSlotOwner(0));
        }

        @Test
        @DisplayName("取消分配槽位（设置为null）")
        void testUnassignSlot() {
            config.setSlotOwner(0, NODE_ID_1);
            assertEquals(1, config.getAssignedSlotCount());

            config.setSlotOwner(0, null);
            assertEquals(0, config.getAssignedSlotCount());
            assertNull(config.getSlotOwner(0));
        }

        @Test
        @DisplayName("获取已分配槽位数量")
        void testGetAssignedSlotCount() {
            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            config.setSlotOwner(0, NODE_ID_1);
            config.setSlotOwner(1, NODE_ID_1);
            config.setSlotOwner(2, NODE_ID_1);
            assertEquals(3, config.getAssignedSlotCount());
        }

        @Test
        @DisplayName("检查所有槽位是否已分配")
        void testAreAllSlotsAssigned() {
            assertFalse(config.areAllSlotsAssigned());

            ClusterNode node = new ClusterNode(NODE_ID_1);
            config.addNode(node);
            for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
                config.setSlotOwner(i, NODE_ID_1);
            }
            assertTrue(config.areAllSlotsAssigned());
        }

        @Test
        @DisplayName("负槽位号应抛出异常")
        void testSetSlotOwnerNegative() {
            assertThrows(IllegalArgumentException.class,
                    () -> config.setSlotOwner(-1, NODE_ID_1));
        }

        @Test
        @DisplayName("超出范围的槽位号应抛出异常")
        void testSetSlotOwnerOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> config.setSlotOwner(ClusterNode.CLUSTER_SLOTS, NODE_ID_1));
        }

        @Test
        @DisplayName("getSlotOwner 越界应抛出异常")
        void testGetSlotOwnerOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> config.getSlotOwner(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> config.getSlotOwner(ClusterNode.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("clearSlot 越界应抛出异常")
        void testClearSlotOverflow() {
            assertThrows(IllegalArgumentException.class, () -> config.clearSlot(-1));
            assertThrows(IllegalArgumentException.class,
                    () -> config.clearSlot(ClusterNode.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("getSlotOwnerNode 越界应抛出异常")
        void testGetSlotOwnerNodeOverflow() {
            assertThrows(IllegalArgumentException.class,
                    () -> config.getSlotOwnerNode(-1));
        }

        @Test
        @DisplayName("边界槽位 0 和 16383")
        void testBoundarySlots() {
            config.setSlotOwner(0, NODE_ID_1);
            config.setSlotOwner(ClusterNode.CLUSTER_SLOTS - 1, NODE_ID_1);
            assertEquals(2, config.getAssignedSlotCount());
        }
    }

    // ==================== 槽位分配表设置测试 ====================

    @Nested
    @DisplayName("槽位分配表设置测试")
    class SlotAssignmentTest {

        @Test
        @DisplayName("设置合法长度的槽位分配表")
        void testSetValidSlotAssignment() {
            String[] assignment = new String[ClusterNode.CLUSTER_SLOTS];
            assignment[0] = NODE_ID_1;
            assignment[1] = NODE_ID_2;
            config.setSlotAssignment(assignment);

            assertEquals(NODE_ID_1, config.getSlotAssignment()[0]);
            assertEquals(NODE_ID_2, config.getSlotAssignment()[1]);
        }

        @Test
        @DisplayName("设置 null 槽位分配表应重置")
        void testSetNullSlotAssignment() {
            config.setSlotAssignment(null);
            assertNotNull(config.getSlotAssignment());
            assertEquals(ClusterNode.CLUSTER_SLOTS, config.getSlotAssignment().length);
        }

        @Test
        @DisplayName("设置非法长度的槽位分配表应抛出异常")
        void testSetInvalidSlotAssignment() {
            String[] invalidArray = new String[100];
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                    () -> config.setSlotAssignment(invalidArray));
            assertTrue(ex.getMessage().contains("16384"));
        }
    }

    // ==================== 配置纪元测试 ====================

    @Nested
    @DisplayName("配置纪元测试")
    class EpochTest {

        @Test
        @DisplayName("递增集群配置纪元")
        void testIncrementEpoch() {
            assertEquals(0, config.getCurrentEpoch());
            assertEquals(1, config.incrementEpoch());
            assertEquals(1, config.getCurrentEpoch());
            assertEquals(2, config.incrementEpoch());
            assertEquals(2, config.getCurrentEpoch());
        }

        @Test
        @DisplayName("setEpochIfGreater 设置更大值成功")
        void testSetEpochIfGreater() {
            assertTrue(config.setEpochIfGreater(1));
            assertEquals(1, config.getCurrentEpoch());
        }

        @Test
        @DisplayName("setEpochIfGreater 设置相等值失败")
        void testSetEpochIfEqual() {
            config.setCurrentEpoch(5);
            assertFalse(config.setEpochIfGreater(5));
            assertEquals(5, config.getCurrentEpoch());
        }

        @Test
        @DisplayName("setEpochIfGreater 设置更小值失败")
        void testSetEpochIfSmaller() {
            config.setCurrentEpoch(5);
            assertFalse(config.setEpochIfGreater(3));
            assertEquals(5, config.getCurrentEpoch());
        }

        @Test
        @DisplayName("直接设置当前节点配置纪元")
        void testSetConfigEpoch() {
            config.setConfigEpoch(10);
            assertEquals(10, config.getConfigEpoch());
        }
    }

    // ==================== 状态与计数测试 ====================

    @Nested
    @DisplayName("状态与计数测试")
    class StateAndCountTest {

        @Test
        @DisplayName("集群状态判断 isClusterOk")
        void testIsClusterOk() {
            assertFalse(config.isClusterOk());
            config.setState("ok");
            assertTrue(config.isClusterOk());
        }

        @Test
        @DisplayName("集群状态判断大小写不敏感")
        void testIsClusterOkCaseInsensitive() {
            config.setState("OK");
            assertTrue(config.isClusterOk());
        }

        @Test
        @DisplayName("获取主节点和从节点数量")
        void testGetMasterAndSlaveCount() {
            ClusterNode master1 = new ClusterNode(NODE_ID_1);
            master1.addState(ClusterNodeState.MASTER);
            config.addNode(master1);

            ClusterNode master2 = new ClusterNode(NODE_ID_2);
            master2.addState(ClusterNodeState.MASTER);
            config.addNode(master2);

            ClusterNode slave1 = new ClusterNode(NODE_ID_3);
            slave1.addState(ClusterNodeState.SLAVE);
            config.addNode(slave1);

            assertEquals(2, config.getMasterCount());
            assertEquals(1, config.getSlaveCount());
        }

        @Test
        @DisplayName("无节点时主从计数为0")
        void testCountWhenNoNodes() {
            assertEquals(0, config.getMasterCount());
            assertEquals(0, config.getSlaveCount());
        }
    }

    // ==================== 重置方法测试 ====================

    @Nested
    @DisplayName("重置方法测试")
    class ResetTest {

        @Test
        @DisplayName("重置后所有配置清空")
        void testReset() {
            config.setMyNodeId(NODE_ID_1);
            config.addNode(new ClusterNode(NODE_ID_1));
            config.setSlotOwner(0, NODE_ID_1);
            config.incrementEpoch();
            config.setConfigEpoch(5);
            config.setState("ok");

            config.reset();

            assertAll("重置后校验",
                    () -> assertEquals(0, config.getNodeCount()),
                    () -> assertEquals(0, config.getAssignedSlotCount()),
                    () -> assertEquals(0, config.getCurrentEpoch()),
                    () -> assertEquals(0, config.getConfigEpoch()),
                    () -> assertEquals("fail", config.getState())
            );
        }
    }

    // ==================== toString 测试 ====================

    @Nested
    @DisplayName("toString 测试")
    class ToStringTest {

        @Test
        @DisplayName("toString 包含关键字段")
        void testToString() {
            config.setMyNodeId(NODE_ID_1);
            config.addNode(new ClusterNode(NODE_ID_1));
            config.setState("ok");
            String str = config.toString();
            assertAll("toString 校验",
                    () -> assertTrue(str.contains(NODE_ID_1)),
                    () -> assertTrue(str.contains("ok")),
                    () -> assertTrue(str.contains("ClusterConfig"))
            );
        }
    }
}
