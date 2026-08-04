package com.janeluo.luban.rds.cluster.handler;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

        // 创建命令处理器（不使用 Gossip 协议，不配置持久化路径，不注入 MemoryStore）
        handler = new ClusterCommandHandler(clusterConfig, slotManager, stateManager, null, null, null);
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
    @DisplayName("CLUSTER ADDSLOTS 后当前节点 configEpoch 应非零且等于 currentEpoch")
    void testClusterAddslotsSetsConfigEpoch() {
        // ADDSLOTS 前 configEpoch 为 0
        ClusterNode myNode = clusterConfig.getMyNode();
        assertEquals(0L, myNode.getConfigEpoch(), "ADDSLOTS 前 configEpoch 应为 0");

        String result = handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});
        assertEquals("+OK\r\n", result);

        // ADDSLOTS 后 configEpoch 应等于 currentEpoch（>0），与 REPLICATE/故障转移路径一致
        assertTrue(myNode.getConfigEpoch() > 0, "ADDSLOTS 后 configEpoch 应非零");
        assertEquals(clusterConfig.getCurrentEpoch(), myNode.getConfigEpoch(),
                "ADDSLOTS 后 configEpoch 应等于 currentEpoch");
    }

    @Test
    @DisplayName("CLUSTER SET-CONFIG-EPOCH 设置节点与集群配置纪元")
    void testClusterSetConfigEpoch() {
        String result = handler.handle(new String[]{"SET-CONFIG-EPOCH", "4"});
        assertEquals("+OK\r\n", result);

        ClusterNode myNode = clusterConfig.getMyNode();
        assertEquals(4L, myNode.getConfigEpoch(),
                "SET-CONFIG-EPOCH 后 myNode.configEpoch 应为 4");
        assertTrue(clusterConfig.getCurrentEpoch() >= 4L,
                "集群 currentEpoch 应至少为 4");
    }

    @Test
    @DisplayName("CLUSTER SET-CONFIG-EPOCH 无效参数")
    void testClusterSetConfigEpochInvalid() {
        // 缺少参数
        String result1 = handler.handle(new String[]{"SET-CONFIG-EPOCH"});
        assertTrue(result1.contains("-ERR"));

        // 非数字
        String result2 = handler.handle(new String[]{"SET-CONFIG-EPOCH", "abc"});
        assertTrue(result2.contains("-ERR"));

        // 负数
        String result3 = handler.handle(new String[]{"SET-CONFIG-EPOCH", "-1"});
        assertTrue(result3.contains("-ERR"));
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

    /**
     * P1-2A：SETSLOT NODE 必须提升新 owner 的 per-node configEpoch，
     * 否则 gossip 经 syncSlotsFromNode 的 epoch 仲裁会拒绝槽位变更，第三节点永不收敛。
     */
    @Test
    @DisplayName("P1-2A：SETSLOT NODE 提升新 owner 的 configEpoch")
    void testClusterSetslotNodeBumpsConfigEpoch() {
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        targetNode.setConfigEpoch(0L);
        clusterConfig.addNode(targetNode);

        long epochBefore = clusterConfig.getCurrentEpoch();
        String result = handler.handle(new String[]{"SETSLOT", "0", "NODE", NODE_ID_2});
        assertEquals("+OK\r\n", result);

        long epochAfter = clusterConfig.getCurrentEpoch();
        assertTrue(epochAfter > epochBefore, "currentEpoch 应自增");
        assertEquals(epochAfter, targetNode.getConfigEpoch(),
                "新 owner 的 configEpoch 应被提升到当前 currentEpoch");
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
        // memoryStore 未注入且槽位 0 未分配给本节点，返回 0
        assertEquals(":0\r\n", result);
    }

    @Test
    @DisplayName("测试 CLUSTER GETKEYSINSLOT 命令")
    void testClusterGetkeysinslot() {
        String result = handler.handle(new String[]{"GETKEYSINSLOT", "0", "10"});
        // memoryStore 未注入且槽位 0 未分配给本节点，返回空数组
        assertEquals("*0\r\n", result);
    }

    @Test
    @DisplayName("测试 GETKEYSINSLOT/COUNTKEYSINSLOT 接入 MemoryStore 后返回真实数据")
    void testGetkeysinslotWithMemoryStore() {
        // 分配槽位 0 给本节点
        slotManager.addSlots(0);

        MemoryStore mockStore = mock(MemoryStore.class);
        List<String> keys = Arrays.asList("key1", "key2");
        when(mockStore.getKeysInSlot(0, 0, 10)).thenReturn(keys);
        when(mockStore.countKeysInSlot(0, 0)).thenReturn(2);

        ClusterCommandHandler handlerWithStore = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null, null, mockStore);

        String getKeysResult = handlerWithStore.handle(new String[]{"GETKEYSINSLOT", "0", "10"});
        assertTrue(getKeysResult.startsWith("*2\r\n"));
        assertTrue(getKeysResult.contains("key1"));
        assertTrue(getKeysResult.contains("key2"));

        String countResult = handlerWithStore.handle(new String[]{"COUNTKEYSINSLOT", "0"});
        assertEquals(":2\r\n", countResult);
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
    @DisplayName("测试 CLUSTER SAVECONFIG 命令 - 路径为 null 时返回错误")
    void testClusterSaveconfigNullPath() {
        // handler 创建时传入 null 路径
        ClusterCommandHandler nullPathHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null, null, null);
        String result = nullPathHandler.handle(new String[]{"SAVECONFIG"});
        assertTrue(result.contains("-ERR"), "路径为 null 时应返回错误");
        assertTrue(result.contains("not configured"));
    }

    @Test
    @DisplayName("测试 CLUSTER SAVECONFIG 命令 - 正常保存到临时文件")
    void testClusterSaveconfigWithTempFile() throws Exception {
        // 创建临时文件路径
        java.nio.file.Path tempDir = java.nio.file.Files.createTempDirectory("luban-test-");
        String tempFilePath = tempDir.resolve("nodes.conf").toAbsolutePath().toString();
        try {
            // 创建带文件路径的 handler
            ClusterCommandHandler persistHandler = new ClusterCommandHandler(
                    clusterConfig, slotManager, stateManager, null, tempFilePath, null);
            String result = persistHandler.handle(new String[]{"SAVECONFIG"});
            assertEquals("+OK\r\n", result);

            // 验证文件确实被创建
            java.io.File savedFile = new java.io.File(tempFilePath);
            assertTrue(savedFile.exists(), "nodes.conf 文件应该被创建");
            assertTrue(savedFile.length() > 0, "nodes.conf 文件不应为空");

            // 验证文件内容包含节点信息
            String content = new String(java.nio.file.Files.readAllBytes(savedFile.toPath()));
            assertTrue(content.contains(NODE_ID_1), "应包含节点ID");
            assertTrue(content.contains("myself,master"), "应包含节点状态");
        } finally {
            // 清理临时文件
            java.io.File savedFile = new java.io.File(tempFilePath);
            if (savedFile.exists()) {
                savedFile.delete();
            }
            tempDir.toFile().delete();
        }
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

    /**
     * P1-3：FORGET 必须把节点加入 ClusterConfig 的共享黑名单（master 与 slave 两分支都加），
     * 否则 gossip 的 processGossipNodes/handleMeet 会立即把它重新引入，使 FORGET 失效。
     */
    @Test
    @DisplayName("P1-3：FORGET 主节点后加入 clusterConfig 黑名单")
    void testForgetMasterAddsToBlacklist() {
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);

        assertFalse(clusterConfig.isBlacklisted(NODE_ID_2));
        String result = handler.handle(new String[]{"FORGET", NODE_ID_2});
        assertEquals("+OK\r\n", result);
        assertTrue(clusterConfig.isBlacklisted(NODE_ID_2), "FORGET 后节点应在 clusterConfig 黑名单内");
    }

    @Test
    @DisplayName("P1-3：FORGET 从节点后也加入 clusterConfig 黑名单")
    void testForgetSlaveAddsToBlacklist() {
        // 设置 MYSELF 为 master，使 NODE_ID_2 可成为其 slave
        ClusterNode slaveNode = new ClusterNode(NODE_ID_2);
        slaveNode.setIp("127.0.0.1");
        slaveNode.setPort(7001);
        slaveNode.addState(ClusterNodeState.SLAVE);
        slaveNode.setMasterNodeId(NODE_ID_1);
        clusterConfig.addNode(slaveNode);

        String result = handler.handle(new String[]{"FORGET", NODE_ID_2});
        assertEquals("+OK\r\n", result);
        assertTrue(clusterConfig.isBlacklisted(NODE_ID_2), "FORGET 从节点后也应在黑名单内");
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

    @Test
    @DisplayName("测试 CLUSTER SETSLOT NODE 清除残留迁移状态（P0-2 收敛配套）")
    void testClusterSetslotNodeClearsMigrationState() {
        // 添加目标节点
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        // 分配槽位并同时建立 IMPORTING/MIGRATING 状态
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4", "5"});
        handler.handle(new String[]{"SETSLOT", "3", "IMPORTING", NODE_ID_2});
        handler.handle(new String[]{"SETSLOT", "3", "MIGRATING", NODE_ID_2});
        assertTrue(slotManager.isSlotImporting(3));
        assertTrue(slotManager.isSlotMigrating(3));

        // 迁移完成 SETSLOT NODE：应清除残留状态，
        // 否则目标/源节点间形成迁移完成后的永久 ASK 互指循环
        String result = handler.handle(new String[]{"SETSLOT", "3", "NODE", NODE_ID_2});

        assertEquals("+OK\r\n", result);
        assertFalse(slotManager.isSlotImporting(3));
        assertFalse(slotManager.isSlotMigrating(3));
    }

    // ==================== 第四批（互操作，N-19/N-29） ====================

    @Test
    @DisplayName("CLUSTER SHARDS 输出分片结构（slots + 节点 map）")
    void testClusterShards() {
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});

        String result = handler.handle(new String[]{"SHARDS"});

        assertNotNull(result);
        assertTrue(result.startsWith("*1\r\n"), "1 个分片区间");
        // slots 数组 [start, end]
        assertTrue(result.contains("*2\r\n:0\r\n:2\r\n"), "应输出槽位区间 [0,2]");
        // 节点 map 字段（RESP2 键值交替）
        assertTrue(result.contains("$2\r\nid\r\n"), "节点 map 应含 id");
        assertTrue(result.contains("$2\r\nip\r\n"), "节点 map 应含 ip");
        assertTrue(result.contains("$8\r\nendpoint\r\n"), "节点 map 应含 endpoint");
        assertTrue(result.contains("$4\r\nrole\r\n"), "节点 map 应含 role");
        assertTrue(result.contains("$6\r\nmaster\r\n"), "master 角色在前");
        assertTrue(result.contains("$18\r\nreplication-offset\r\n"), "节点 map 应含 replication-offset");
        assertTrue(result.contains("$6\r\nhealth\r\n"), "节点 map 应含 health");
        assertTrue(result.contains("$2\r\nok\r\n"), "健康节点 health 为 ok");
    }

    @Test
    @DisplayName("CLUSTER SHARDS 无槽位时返回空数组")
    void testClusterShardsEmpty() {
        String result = handler.handle(new String[]{"SHARDS"});
        assertEquals("*0\r\n", result);
    }

    @Test
    @DisplayName("CLUSTER LINKS 返回空数组（合法响应）")
    void testClusterLinks() {
        assertEquals("*0\r\n", handler.handle(new String[]{"LINKS"}));
    }

    @Test
    @DisplayName("CLUSTER ADDSLOTSRANGE 批量分配槽位")
    void testClusterAddslotsRange() {
        String result = handler.handle(new String[]{"ADDSLOTSRANGE", "0", "5", "10", "10"});
        assertEquals("+OK\r\n", result);

        for (int slot = 0; slot <= 5; slot++) {
            assertTrue(slotManager.isSlotLocal(slot), "槽位 " + slot + " 应已分配");
        }
        assertTrue(slotManager.isSlotLocal(10));
        assertFalse(slotManager.isSlotLocal(6));
    }

    @Test
    @DisplayName("CLUSTER ADDSLOTSRANGE 非法参数返回错误")
    void testClusterAddslotsRangeInvalid() {
        // 参数不成对
        assertTrue(handler.handle(new String[]{"ADDSLOTSRANGE", "0", "5", "10"}).contains("-ERR"));
        // start > end
        assertTrue(handler.handle(new String[]{"ADDSLOTSRANGE", "5", "0"}).contains("-ERR"));
        // 非数字
        assertTrue(handler.handle(new String[]{"ADDSLOTSRANGE", "0", "abc"}).contains("-ERR"));
        // 越界（N-20：英文错误串）
        String outOfRange = handler.handle(new String[]{"ADDSLOTSRANGE", "16384", "16385"});
        assertTrue(outOfRange.contains("-ERR Invalid slot specified"), "越界应返回英文错误串: " + outOfRange);
    }

    @Test
    @DisplayName("CLUSTER DELSLOTSRANGE 批量移除槽位")
    void testClusterDelslotsRange() {
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4", "5"});
        assertEquals("+OK\r\n", handler.handle(new String[]{"DELSLOTSRANGE", "2", "4"}));

        assertTrue(slotManager.isSlotLocal(0));
        assertFalse(slotManager.isSlotLocal(2));
        assertFalse(slotManager.isSlotLocal(4));
        assertTrue(slotManager.isSlotLocal(5));
    }

    @Test
    @DisplayName("CLUSTER DELSLOTSRANGE 非数字参数返回错误")
    void testClusterDelslotsRangeInvalid() {
        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4", "5"});
        assertTrue(handler.handle(new String[]{"DELSLOTSRANGE", "0", "x"}).contains("-ERR"));
    }

    @Test
    @DisplayName("CLUSTER COUNT-FAILURE-REPORTS 未知节点报错，已知节点返回 0")
    void testClusterCountFailureReports() {
        assertTrue(handler.handle(new String[]{"COUNT-FAILURE-REPORTS", "unknown-node"}).contains("-ERR"));
        assertEquals(":0\r\n", handler.handle(new String[]{"COUNT-FAILURE-REPORTS", NODE_ID_1}));
    }

    @Test
    @DisplayName("CLUSTER RESET 拒绝 master 持槽节点")
    void testClusterResetRejectsMasterWithSlots() {
        handler.handle(new String[]{"ADDSLOTS", "0", "1"});
        String result = handler.handle(new String[]{"RESET"});
        assertTrue(result.contains("-ERR"), "master 持槽时 RESET 应被拒绝: " + result);
    }

    @Test
    @DisplayName("CLUSTER RESET SOFT 清空槽位并保留节点列表")
    void testClusterResetSoft() {
        // 添加其他节点
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);

        handler.handle(new String[]{"ADDSLOTS", "0", "1"});
        handler.handle(new String[]{"DELSLOTS", "0", "1"}); // 清空本节点槽位以通过检查

        String result = handler.handle(new String[]{"RESET"});
        assertEquals("+OK\r\n", result);

        // 节点列表保留（SOFT 不清节点）
        assertEquals(2, clusterConfig.getNodeCount());
        // 纪元归零
        assertEquals(0, clusterConfig.getCurrentEpoch());
        assertEquals(0, clusterConfig.getMyNode().getConfigEpoch());
        // 本节点仍为无槽 master
        assertTrue(clusterConfig.getMyNode().isMaster());
        assertEquals(0, clusterConfig.getMyNode().getSlotCount());
    }

    @Test
    @DisplayName("CLUSTER RESET HARD 移除其他节点并生成新节点 ID")
    void testClusterResetHard() {
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);
        clusterConfig.incrementEpoch();

        String oldMyId = clusterConfig.getMyNodeId();
        String result = handler.handle(new String[]{"RESET", "HARD"});
        assertEquals("+OK\r\n", result);

        // 其他节点被移除，仅剩 myself
        assertEquals(1, clusterConfig.getNodeCount());
        // 生成了新节点 ID
        String newMyId = clusterConfig.getMyNodeId();
        assertNotNull(newMyId);
        assertFalse(oldMyId.equals(newMyId), "HARD RESET 应生成新节点 ID");
        assertEquals(40, newMyId.length());
        // 纪元归零
        assertEquals(0, clusterConfig.getCurrentEpoch());
    }

    @Test
    @DisplayName("CLUSTER HELP 返回子命令列表数组")
    void testClusterHelp() {
        String result = handler.handle(new String[]{"HELP"});
        assertNotNull(result);
        assertTrue(result.startsWith("*"));
        assertTrue(result.contains("CLUSTER <subcommand> [<arg> [value] [opt] ...]. Subcommands are:"));
        assertTrue(result.contains("ADDSLOTSRANGE <start> <end> [<start> <end> ...]"));
        assertTrue(result.contains("SHARDS"));
        assertTrue(result.contains("RESET [HARD|SOFT]"));
        assertTrue(result.contains("REFRESH"));
    }

    @Test
    @DisplayName("CLUSTER REFRESH 从 nodes.conf 恢复槽位分配")
    void testClusterRefresh() throws IOException {
        java.io.File tempFile = java.io.File.createTempFile("cluster-refresh", ".conf");
        try {
            // 使用带配置路径的 handler 先保存拓扑
            ClusterCommandHandler persistHandler = new ClusterCommandHandler(
                    clusterConfig, slotManager, stateManager, null, tempFile.getAbsolutePath(), null);
            persistHandler.handle(new String[]{"ADDSLOTS", "10", "11", "12"});
            assertEquals("+OK\r\n", persistHandler.handle(new String[]{"SAVECONFIG"}));

            // 清空内存中的槽位
            handler.handle(new String[]{"FLUSHSLOTS"});
            assertFalse(slotManager.isSlotLocal(10));

            // REFRESH 从磁盘恢复
            ClusterCommandHandler refreshHandler = new ClusterCommandHandler(
                    clusterConfig, slotManager, stateManager, null, tempFile.getAbsolutePath(), null);
            String result = refreshHandler.handle(new String[]{"REFRESH"});
            assertEquals("+OK\r\n", result);

            assertTrue(slotManager.isSlotLocal(10), "REFRESH 后槽位 10 应恢复");
            assertTrue(slotManager.isSlotLocal(12));
            assertEquals(NODE_ID_1, clusterConfig.getSlotOwner(10));
        } finally {
            tempFile.delete();
        }
    }

    @Test
    @DisplayName("N-29：SETSLOT IMPORTING/MIGRATING 同步 ClusterConfig，STABLE/NODE 清除")
    void testSetslotMigrationStateSyncedToConfig() {
        // 添加目标节点
        ClusterNode targetNode = new ClusterNode(NODE_ID_2);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(7001);
        targetNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(targetNode);

        handler.handle(new String[]{"ADDSLOTS", "0", "1", "2"});

        handler.handle(new String[]{"SETSLOT", "2", "IMPORTING", NODE_ID_2});
        assertEquals(NODE_ID_2, clusterConfig.getImportingSource(2));
        handler.handle(new String[]{"SETSLOT", "2", "MIGRATING", NODE_ID_2});
        assertEquals(NODE_ID_2, clusterConfig.getMigratingTarget(2));

        // STABLE 清除
        handler.handle(new String[]{"SETSLOT", "2", "STABLE"});
        assertNull(clusterConfig.getImportingSource(2));
        assertNull(clusterConfig.getMigratingTarget(2));

        // NODE 清除
        handler.handle(new String[]{"SETSLOT", "1", "IMPORTING", NODE_ID_2});
        handler.handle(new String[]{"SETSLOT", "1", "NODE", NODE_ID_2});
        assertNull(clusterConfig.getImportingSource(1));
    }

    @Test
    @DisplayName("N-20：ADDSLOTS 越界返回 Redis 英文错误串")
    void testAddslotsEnglishErrorMessages() {
        String outOfRange = handler.handle(new String[]{"ADDSLOTS", "16384"});
        assertEquals("-ERR Invalid slot specified\r\n", outOfRange);

        String notNumber = handler.handle(new String[]{"ADDSLOTS", "abc"});
        assertEquals("-ERR Invalid or out of range slot\r\n", notNumber);

        String setslotOutOfRange = handler.handle(new String[]{"SETSLOT", "-1", "STABLE"});
        assertEquals("-ERR Invalid slot specified\r\n", setslotOutOfRange);
    }

    // ==================== N-26：CLUSTER INFO/NODES 输出补全 ====================

    @Test
    @DisplayName("N-26：CLUSTER INFO 输出 per-type 消息计数与 total_cluster_links 字段")
    void testClusterInfoPerTypeCounters() {
        stateManager.incrementMessagesSent("ping", 4);
        stateManager.incrementMessagesSent("auth-req", 2);
        stateManager.incrementMessagesReceived("pong", 6);

        String result = handler.handle(new String[]{"INFO"});

        assertTrue(result.contains("cluster_stats_messages_ping_sent:4"),
                "应输出 per-type sent 计数");
        assertTrue(result.contains("cluster_stats_messages_auth-req_sent:2"),
                "应输出 per-type sent 计数（auth-req）");
        assertTrue(result.contains("cluster_stats_messages_sent:6"),
                "应输出 sent 汇总（4+2）");
        assertTrue(result.contains("cluster_stats_messages_pong_received:6"),
                "应输出 per-type received 计数");
        assertTrue(result.contains("cluster_stats_messages_received:6"));
        assertTrue(result.contains("total_cluster_links_buffer_limit_exceeded:0"),
                "应对齐 Redis 7.2 的 total_cluster_links_buffer_limit_exceeded 字段");
    }

    @Test
    @DisplayName("N-26：cluster_my_epoch 取 MYSELF 节点 configEpoch（非陈旧死字段）")
    void testClusterMyEpochFromMyselfNode() {
        clusterConfig.getMyNode().setConfigEpoch(7L);
        // ClusterConfig 级别独立字段保持 0（死字段），输出不应受其影响
        clusterConfig.setConfigEpoch(0L);

        String result = handler.handle(new String[]{"INFO"});
        assertTrue(result.contains("cluster_my_epoch:7"),
                "cluster_my_epoch 应输出 MYSELF 节点实际 configEpoch");
    }

    @Test
    @DisplayName("N-26：CLUSTER NODES 输出 NOADDR 节点（地址 :0@0）")
    void testClusterNodesIncludesNoaddrNode() {
        ClusterNode noaddr = new ClusterNode(NODE_ID_2);
        noaddr.addState(ClusterNodeState.MASTER);
        noaddr.addState(ClusterNodeState.NOADDR);
        clusterConfig.addNode(noaddr);

        String result = handler.handle(new String[]{"NODES"});

        assertTrue(result.contains(NODE_ID_2), "NOADDR 节点不应被跳过");
        assertTrue(result.contains(":0@0"), "NOADDR 节点地址应为 :0@0（对齐 Redis）");
    }

    @Test
    @DisplayName("N-26：CLUSTER NODES 从节点 config-epoch 列输出其 master 的纪元")
    void testClusterNodesSlaveShowsMasterEpoch() {
        ClusterNode master = clusterConfig.getMyNode();
        master.setConfigEpoch(9L);
        ClusterNode slave = new ClusterNode(NODE_ID_2);
        slave.setIp("127.0.0.1");
        slave.setPort(7001);
        slave.setBusPort(17001);
        slave.addState(ClusterNodeState.SLAVE);
        slave.setMasterNodeId(NODE_ID_1);
        slave.setConfigEpoch(3L);  // 从节点自身纪元（不应出现在输出列）
        clusterConfig.addNode(slave);

        String result = handler.handle(new String[]{"NODES"});
        String slaveLine = null;
        for (String line : result.split("\n")) {
            if (line.startsWith(NODE_ID_2 + " ")) {
                slaveLine = line;
                break;
            }
        }
        assertNotNull(slaveLine, "应找到从节点行");
        // 行格式: <id> <addr> <flags> <master> <ping> <pong> <epoch> <link>
        String[] fields = slaveLine.split(" ");
        assertEquals("9", fields[6], "从节点 config-epoch 列应输出其 master 的纪元");
        assertEquals(NODE_ID_1, fields[3], "master 列应指向 master");
    }

    @Test
    @DisplayName("N-26：CLUSTER NODES flags 顺序 fail? 先于 fail（对齐 Redis 规范序）")
    void testClusterNodesFlagsOrder() {
        ClusterNode failNode = new ClusterNode(NODE_ID_2);
        failNode.setIp("127.0.0.1");
        failNode.setPort(7001);
        failNode.setBusPort(17001);
        failNode.addState(ClusterNodeState.MASTER);
        failNode.addState(ClusterNodeState.FAIL);
        clusterConfig.addNode(failNode);

        ClusterNode pfailNode = new ClusterNode(NODE_ID_3);
        pfailNode.setIp("127.0.0.1");
        pfailNode.setPort(7002);
        pfailNode.setBusPort(17002);
        pfailNode.addState(ClusterNodeState.MASTER);
        pfailNode.addState(ClusterNodeState.PFAIL);
        clusterConfig.addNode(pfailNode);

        String result = handler.handle(new String[]{"NODES"});
        // Redis redisNodeFlagsTable 顺序：myself, master, slave, fail?, fail, handshake, noaddr
        assertTrue(result.contains(NODE_ID_2 + " 127.0.0.1:7001@17001 master,fail "),
                "FAIL 节点 flags 应为 master,fail");
        assertTrue(result.contains(NODE_ID_3 + " 127.0.0.1:7002@17002 master,fail? "),
                "PFAIL 节点 flags 应为 master,fail?");
    }
}
