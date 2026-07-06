package com.janeluo.luban.rds.cluster.handler;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ClusterCommandHandler 单元测试
 */
class ClusterCommandHandlerTest {

    private ClusterConfig clusterConfig;
    private SlotManager slotManager;
    private ClusterStateManager stateManager;
    private ClusterCommandHandler handler;

    // 测试用的节点ID（40字符十六进制）
    private static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_2 = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String NODE_ID_3 = "c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    @BeforeEach
    void setUp() {
        // 创建集群配置
        clusterConfig = new ClusterConfig(NODE_ID_1);

        // 创建当前节点
        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        // 创建槽位管理器
        slotManager = new DefaultSlotManager(NODE_ID_1);

        // 创建状态管理器
        stateManager = new ClusterStateManager(clusterConfig);

        // 创建命令处理器（不使用 Gossip 协议）
        handler = new ClusterCommandHandler(clusterConfig, slotManager, stateManager, null);
    }

    @Test
    @DisplayName("测试 CLUSTER INFO 命令")
    void testClusterInfo() {
        String result = handler.handle(new String[]{"INFO"});

        assertNotNull(result);
        assertTrue(result.contains("cluster_enabled:1"));
        assertTrue(result.contains("cluster_state:"));
        assertTrue(result.contains("cluster_slots_assigned:"));
        assertTrue(result.contains("cluster_known_nodes:"));
        assertTrue(result.contains("cluster_current_epoch:"));
    }

    @Test
    @DisplayName("测试 CLUSTER NODES 命令")
    void testClusterNodes() {
        // 为当前 master 节点分配槽位区间
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4", "5"});

        String result = handler.handle(new String[]{"NODES"});

        assertNotNull(result);
        assertTrue(result.contains(NODE_ID_1));
        assertTrue(result.contains("127.0.0.1:7000@17000"));
        assertTrue(result.contains("myself,master"));
        // 行尾必须为裸 \n，不得残留 \r，否则集群客户端（如 Redisson）
        // 用 split("\n") 切行后末尾 slot 字段会变成 "0-5\r" 导致 NumberFormatException
        assertFalse(result.contains("\r"), "CLUSTER NODES payload 不得包含 \\r");
        assertTrue(result.endsWith("\n"), "CLUSTER NODES 应以 \\n 结尾");
        assertTrue(result.contains("0-5"), "应显示连续 slot 区间 0-5");
    }

    @Test
    @DisplayName("CLUSTER NODES 非连续 slot 多段区间无 \\r 残留")
    void testClusterNodesNonContiguousSlots() {
        // 分配非连续 slot：0 和 100，应输出 "0 100"
        handler.handle(new String[]{"ADDSLOTS", "0", "100"});

        String result = handler.handle(new String[]{"NODES"});

        assertNotNull(result);
        assertFalse(result.contains("\r"), "CLUSTER NODES payload 不得包含 \\r");
        assertTrue(result.endsWith("\n"), "CLUSTER NODES 应以 \\n 结尾");
        assertTrue(result.contains("0 100"), "非连续 slot 应以空格分隔多段区间");

        // 模拟 Redisson ClusterNodesDecoder 的切行方式，验证每段 slot 可被 Integer.parseInt 解析
        for (String line : result.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] params = line.split(" ");
            if (params.length > 8) {
                for (int i = 8; i < params.length; i++) {
                    String slot = params[i];
                    String[] parts = slot.contains("-") ? slot.split("-") : new String[]{slot};
                    for (String part : parts) {
                        Integer.parseInt(part); // 不抛异常即通过
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("测试 CLUSTER MYID 命令")
    void testClusterMyid() {
        String result = handler.handle(new String[]{"MYID"});

        assertNotNull(result);
        assertTrue(result.contains(NODE_ID_1));
    }

    @Test
    @DisplayName("测试 CLUSTER KEYSLOT 命令")
    void testClusterKeyslot() {
        // 测试普通键
        String result1 = handler.handle(new String[]{"KEYSLOT", "user:1000"});
        assertNotNull(result1);
        assertTrue(result1.startsWith(":"));
        assertTrue(result1.endsWith("\r\n"));

        // 测试带 hash tag 的键
        String result2 = handler.handle(new String[]{"KEYSLOT", "user:{1000}"});
        assertNotNull(result2);
        assertTrue(result2.startsWith(":"));

        // 相同 hash tag 应该返回相同的槽位
        String result3 = handler.handle(new String[]{"KEYSLOT", "{1000}"});
        assertEquals(result2, result3);
    }

    @Test
    @DisplayName("测试 CLUSTER ADDSLOTS 命令")
    void testClusterAddslots() {
        // 添加槽位
        String result = handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});
        assertEquals("+OK\r\n", result);

        // 验证槽位已分配
        assertTrue(slotManager.isSlotLocal(0));
        assertTrue(slotManager.isSlotLocal(1));
        assertTrue(slotManager.isSlotLocal(2));

        // 重复添加应该失败
        String result2 = handler.handle(new String[]{"ADDSLOTS", "0"});
        assertTrue(result2.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER ADDSLOTS 无效槽位")
    void testClusterAddslotsInvalidSlot() {
        // 槽位号超出范围
        String result = handler.handle(new String[]{"ADDSLOTS", "16384"});
        assertTrue(result.contains("-ERR"));

        // 负数槽位
        String result2 = handler.handle(new String[]{"ADDSLOTS", "-1"});
        assertTrue(result2.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER DELSLOTS 命令")
    void testClusterDelslots() {
        // 先添加槽位
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});

        // 删除槽位
        String result = handler.handle(new String[]{"DELSLOTS", "0", "1"});
        assertEquals("+OK\r\n", result);

        // 验证槽位已删除
        assertFalse(slotManager.isSlotLocal(0));
        assertFalse(slotManager.isSlotLocal(1));
        assertTrue(slotManager.isSlotLocal(2));
    }

    @Test
    @DisplayName("测试 CLUSTER DELSLOTS 非本节点槽位")
    void testClusterDelslotsNotMySlot() {
        // 删除未分配的槽位应该失败
        String result = handler.handle(new String[]{"DELSLOTS", "0"});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER FLUSHSLOTS 命令")
    void testClusterFlushslots() {
        // 先添加槽位
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4"});

        // 清空槽位
        String result = handler.handle(new String[]{"FLUSHSLOTS"});
        assertEquals("+OK\r\n", result);

        // 验证槽位已清空
        assertEquals(0, slotManager.getMySlotCount());
    }

    @Test
    @DisplayName("测试 CLUSTER BUMPEPOCH 命令")
    void testClusterBumpepoch() {
        long initialEpoch = clusterConfig.getCurrentEpoch();

        String result = handler.handle(new String[]{"BUMPEPOCH"});
        assertTrue(result.startsWith(":"));
        assertTrue(result.endsWith("\r\n"));

        // 验证纪元已增加
        assertEquals(initialEpoch + 1, clusterConfig.getCurrentEpoch());
    }

    @Test
    @DisplayName("测试 CLUSTER REPLICATE 命令")
    void testClusterReplicate() {
        // 添加另一个主节点
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(masterNode);

        // 配置当前节点为从节点
        String result = handler.handle(new String[]{"REPLICATE", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        // 验证当前节点已变为从节点
        ClusterNode myNode = clusterConfig.getMyNode();
        assertTrue(myNode.isSlave());
        assertEquals(NODE_ID_2, myNode.getMasterNodeId());
    }

    @Test
    @DisplayName("测试 CLUSTER REPLICATE 未知节点")
    void testClusterReplicateUnknownNode() {
        String result = handler.handle(new String[]{"REPLICATE", NODE_ID_2});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER REPLICATE 有槽位的节点")
    void testClusterReplicateWithSlots() {
        // 添加另一个主节点
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(masterNode);

        // 当前节点添加槽位
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});

        // 尝试配置为从节点应该失败
        String result = handler.handle(new String[]{"REPLICATE", NODE_ID_2});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER FORGET 命令")
    void testClusterForget() {
        // 添加另一个节点
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.SLAVE);
        otherNode.setMasterNodeId(NODE_ID_1);
        clusterConfig.addNode(otherNode);

        // 移除节点
        String result = handler.handle(new String[]{"FORGET", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        // 验证节点已移除
        assertNull(clusterConfig.getNode(NODE_ID_2));
    }

    @Test
    @DisplayName("测试 CLUSTER FORGET 不能移除自己")
    void testClusterForgetMyself() {
        String result = handler.handle(new String[]{"FORGET", NODE_ID_1});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER FORGET 有槽位的主节点")
    void testClusterForgetMasterWithSlots() {
        // 添加另一个主节点并分配槽位
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        masterNode.addSlot(100);
        clusterConfig.addNode(masterNode);

        // 尝试移除应该失败
        String result = handler.handle(new String[]{"FORGET", NODE_ID_2});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER SETSLOT IMPORTING 命令")
    void testClusterSetslotImporting() {
        // 添加源节点
        ClusterNode sourceNode = new ClusterNode(NODE_ID_2);
        sourceNode.setIp("127.0.0.1");
        sourceNode.setPort(7001);
        sourceNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(sourceNode);

        String result = handler.handle(new String[]{"SETSLOT", "0", "IMPORTING", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        // 验证迁移状态
        assertEquals("IMPORTING", handler.getSlotMigrationState(0));
        assertEquals(NODE_ID_2, handler.getSlotMigrationTarget(0));
    }

    @Test
    @DisplayName("测试 CLUSTER SETSLOT MIGRATING 命令")
    void testClusterSetslotMigrating() {
        // 先添加槽位
        handler.handle(new String[]{"ADDSLOTS", "0"});

        // 添加目标节点
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        String result = handler.handle(new String[]{"SETSLOT", "0", "MIGRATING", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        // 验证迁移状态
        assertEquals("MIGRATING", handler.getSlotMigrationState(0));
        assertEquals(NODE_ID_2, handler.getSlotMigrationTarget(0));
    }

    @Test
    @DisplayName("测试 CLUSTER SETSLOT STABLE 命令")
    void testClusterSetslotStable() {
        // 先设置 IMPORTING 状态
        ClusterNode sourceNode = new ClusterNode(NODE_ID_2);
        sourceNode.setIp("127.0.0.1");
        sourceNode.setPort(7001);
        sourceNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(sourceNode);

        handler.handle(new String[]{"SETSLOT", "0", "IMPORTING", NODE_ID_2});

        // 清除状态
        String result = handler.handle(new String[]{"SETSLOT", "0", "STABLE"});
        assertEquals("+OK\r\n", result);

        // 验证状态已清除
        assertNull(handler.getSlotMigrationState(0));
    }

    @Test
    @DisplayName("测试 CLUSTER SETSLOT NODE 命令")
    void testClusterSetslotNode() {
        // 添加目标节点
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        String result = handler.handle(new String[]{"SETSLOT", "0", "NODE", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        // 验证槽位已分配
        assertEquals(NODE_ID_2, slotManager.getSlotOwner(0));
    }

    @Test
    @DisplayName("测试 CLUSTER SLAVES 命令")
    void testClusterSlaves() {
        // 添加主节点
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(masterNode);

        // 添加从节点
        ClusterNode slaveNode = new ClusterNode(NODE_ID_3);
        slaveNode.setIp("127.0.0.1");
        slaveNode.setPort(7002);
        slaveNode.addState(ClusterNodeState.SLAVE);
        slaveNode.setMasterNodeId(NODE_ID_2);
        clusterConfig.addNode(slaveNode);

        String result = handler.handle(new String[]{"SLAVES", NODE_ID_2});

        assertNotNull(result);
        assertTrue(result.contains(NODE_ID_3));
    }

    @Test
    @DisplayName("测试 CLUSTER SLAVES 非主节点")
    void testClusterSlavesNotMaster() {
        // 添加从节点
        ClusterNode slaveNode = new ClusterNode(NODE_ID_2);
        slaveNode.setIp("127.0.0.1");
        slaveNode.setPort(7001);
        slaveNode.addState(ClusterNodeState.SLAVE);
        slaveNode.setMasterNodeId(NODE_ID_1);
        clusterConfig.addNode(slaveNode);

        String result = handler.handle(new String[]{"SLAVES", NODE_ID_2});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER FAILOVER 命令")
    void testClusterFailover() {
        // 添加主节点
        ClusterNode masterNode = new ClusterNode(NODE_ID_2);
        masterNode.setIp("127.0.0.1");
        masterNode.setPort(7001);
        masterNode.addState(ClusterNodeState.MASTER);
        masterNode.addSlot(0);
        masterNode.addSlot(1);
        clusterConfig.addNode(masterNode);

        // 配置当前节点为从节点
        ClusterNode myNode = clusterConfig.getMyNode();
        myNode.removeState(ClusterNodeState.MASTER);
        myNode.addState(ClusterNodeState.SLAVE);
        myNode.setMasterNodeId(NODE_ID_2);

        // 执行故障转移
        String result = handler.handle(new String[]{"FAILOVER"});
        assertEquals("+OK\r\n", result);

        // 验证当前节点已提升为主节点
        assertTrue(myNode.isMaster());
        assertNull(myNode.getMasterNodeId());
    }

    @Test
    @DisplayName("测试 CLUSTER FAILOVER 主节点不能执行")
    void testClusterFailoverMaster() {
        // 当前节点是主节点
        String result = handler.handle(new String[]{"FAILOVER"});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER COUNTKEYSINSLOT 命令")
    void testClusterCountkeysinslot() {
        String result = handler.handle(new String[]{"COUNTKEYSINSLOT", "0"});
        // 当前实现返回0
        assertEquals(":0\r\n", result);
    }

    @Test
    @DisplayName("测试 CLUSTER GETKEYSINSLOT 命令")
    void testClusterGetkeysinslot() {
        String result = handler.handle(new String[]{"GETKEYSINSLOT", "0", "10"});
        // 当前实现返回空数组
        assertEquals("*0\r\n", result);
    }

    @Test
    @DisplayName("测试无效子命令")
    void testInvalidSubcommand() {
        String result = handler.handle(new String[]{"INVALID"});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试空参数")
    void testEmptyArgs() {
        String result = handler.handle(new String[]{});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 null 参数")
    void testNullArgs() {
        String result = handler.handle(null);
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER SAVECONFIG 命令")
    void testClusterSaveconfig() {
        String result = handler.handle(new String[]{"SAVECONFIG"});
        assertEquals("+OK\r\n", result);
    }

    @Test
    @DisplayName("测试 CLUSTER MEET 无 Gossip 协议")
    void testClusterMeetWithoutGossip() {
        String result = handler.handle(new String[]{"MEET", "127.0.0.1", "7001"});
        // 没有 Gossip 协议时应该返回错误
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER MEET 无效端口")
    void testClusterMeetInvalidPort() {
        String result = handler.handle(new String[]{"MEET", "127.0.0.1", "invalid"});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试 CLUSTER MEET 端口超出范围")
    void testClusterMeetPortOutOfRange() {
        String result = handler.handle(new String[]{"MEET", "127.0.0.1", "70000"});
        assertTrue(result.contains("-ERR"));
    }

    @Test
    @DisplayName("测试延迟移除节点列表")
    void testForgetNodeList() {
        // 添加另一个主节点（无槽位）
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);

        // 移除节点
        handler.handle(new String[]{"FORGET", NODE_ID_2});

        // 验证在延迟移除列表中（主节点会添加到延迟列表）
        assertTrue(handler.isNodeInForgetList(NODE_ID_2));
    }

    @Test
    @DisplayName("测试清理过期的延迟移除节点")
    void testCleanupForgetNodes() {
        // 添加另一个主节点（无槽位）
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);

        // 移除节点
        handler.handle(new String[]{"FORGET", NODE_ID_2});

        // 清理（此时不应该过期）
        handler.cleanupForgetNodes();
        assertTrue(handler.isNodeInForgetList(NODE_ID_2));
    }

    @Test
    @DisplayName("测试 CLUSTER SETSLOT MIGRATING 非本节点槽位")
    void testClusterSetslotMigratingNotMySlot() {
        // 添加目标节点
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        // 尝试迁移未分配的槽位应该失败
        String result = handler.handle(new String[]{"SETSLOT", "0", "MIGRATING", NODE_ID_2});
        assertTrue(result.contains("-ERR"));
    }
}
