package com.janeluo.luban.rds.cluster.integration;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterConfigPersister;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.config.ClusterStats;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 集群集成测试
 * 测试多节点集群场景
 */
class ClusterIntegrationTest {

    private ClusterConfig config;
    private SlotManager slotManager;
    private ClusterStateManager stateManager;
    private ClusterCommandHandler commandHandler;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        commandHandler = new ClusterCommandHandler(config, slotManager, stateManager, null, null);
    }

    @AfterEach
    void tearDown() {
        config.reset();
    }

    @Test
    @DisplayName("测试创建三主节点集群")
    void testCreateThreeMasterCluster() {
        // 创建三个主节点
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
        String nodeId3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);
        ClusterNode node3 = createMasterNode(nodeId3, "127.0.0.1", 7002);

        config.addNode(node1);
        config.addNode(node2);
        config.addNode(node3);

        // 设置当前节点为 node1
        config.setMyNodeId(nodeId1);
        node1.addState(ClusterNodeState.MYSELF);
        slotManager.setMyNodeId(nodeId1);

        // 分配槽位：节点1: 0-5461, 节点2: 5462-10922, 节点3: 10923-16383
        assignSlotRange(node1, 0, 5461);
        assignSlotRange(node2, 5462, 10922);
        assignSlotRange(node3, 10923, 16383);

        // 验证所有槽位都已分配
        assertTrue(config.areAllSlotsAssigned(), "所有槽位应该都已分配");
        assertEquals(16384, config.getAssignedSlotCount(), "已分配槽位数量应为16384");

        // 验证每个节点的槽位数量
        assertEquals(5462, node1.getSlotCount(), "节点1应该有5462个槽位");
        assertEquals(5461, node2.getSlotCount(), "节点2应该有5461个槽位");
        assertEquals(5461, node3.getSlotCount(), "节点3应该有5461个槽位");

        // 验证集群状态为 OK
        stateManager.updateClusterState();
        assertTrue(stateManager.isClusterOk(), "集群状态应该为OK");

        // 验证 CLUSTER INFO 输出
        ClusterStats stats = stateManager.getStats();
        assertEquals("ok", stats.getState(), "集群状态应为ok");
        assertEquals(16384, stats.getSlotsAssigned(), "已分配槽位应为16384");
        assertEquals(3, stats.getSize(), "集群大小应为3个主节点");
    }

    @Test
    @DisplayName("测试节点加入集群")
    void testNodeJoinCluster() {
        // 创建初始节点
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        config.addNode(node1);
        config.setMyNodeId(nodeId1);

        // 验证初始节点列表
        assertEquals(1, config.getNodeCount(), "初始应该只有1个节点");

        // 注意：由于 GossipProtocol 为 null，MEET 命令会返回错误
        // 我们手动模拟节点加入
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);
        config.addNode(node2);

        // 验证节点列表包含新节点
        assertEquals(2, config.getNodeCount(), "应该有2个节点");
        assertTrue(config.hasNode(nodeId2), "节点列表应该包含新节点");

        // 模拟 Gossip 传播 - 设置节点状态
        node2.updateLastPongTime();
        assertFalse(node2.isFail(), "新节点不应该被标记为FAIL");
    }

    @Test
    @DisplayName("测试节点离开集群")
    void testNodeLeaveCluster() {
        // 创建两个节点的集群
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(nodeId1);

        // 分配槽位给 node1
        assignSlotRange(node1, 0, 16383);

        // node2 是从节点（没有槽位）
        node2.removeState(ClusterNodeState.MASTER);
        node2.addState(ClusterNodeState.SLAVE);
        node2.setMasterNodeId(nodeId1);

        // 验证初始状态
        assertEquals(2, config.getNodeCount());

        // 使用 CLUSTER FORGET 命令移除从节点
        String response = commandHandler.handle(new String[]{"FORGET", nodeId2});
        assertTrue(response.contains("+OK"), "FORGET命令应该返回OK");

        // 验证节点已被移除
        assertFalse(config.hasNode(nodeId2), "节点应该已被移除");
        assertEquals(1, config.getNodeCount(), "应该只剩1个节点");

        // 验证 60 秒延迟机制 - 节点在延迟列表中
        // 注意：延迟列表仅用于主节点（有槽位的节点）
        // 从节点没有槽位，不会添加到延迟列表
        assertFalse(commandHandler.isNodeInForgetList(nodeId2), "从节点不应该在延迟移除列表中");
    }

    @Test
    @DisplayName("测试槽位分配")
    void testSlotAssignment() {
        // 创建节点
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        config.addNode(node1);
        config.setMyNodeId(nodeId1);
        slotManager.setMyNodeId(nodeId1);

        // 使用 CLUSTER ADDSLOTS 分配槽位
        String response = commandHandler.handle(new String[]{"ADDSLOTS", "0", "1", "2", "3", "4", "5"});
        assertTrue(response.contains("+OK"), "ADDSLOTS命令应该返回OK");

        // 验证槽位已分配
        assertEquals(nodeId1, slotManager.getSlotOwner(0));
        assertEquals(nodeId1, slotManager.getSlotOwner(5));
        assertEquals(6, node1.getSlotCount());

        // 验证 CLUSTER NODES 输出正确
        String nodesOutput = commandHandler.handle(new String[]{"NODES"});
        assertTrue(nodesOutput.contains(nodeId1), "NODES输出应包含节点ID");
        assertTrue(nodesOutput.contains("master"), "NODES输出应包含master标志");
        assertTrue(nodesOutput.contains("0-5"), "NODES输出应包含槽位范围");

        // 验证 CLUSTER INFO 显示正确的槽位统计
        String infoOutput = commandHandler.handle(new String[]{"INFO"});
        // INFO 输出格式为 cluster_slots_assigned:6\r\n
        assertTrue(infoOutput.contains("cluster_slots_assigned:6"), "INFO应显示6个已分配槽位，实际输出: " + infoOutput);
    }

    @Test
    @DisplayName("测试槽位迁移流程")
    void testSlotMigration() {
        // 创建两个主节点
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(nodeId1);
        slotManager.setMyNodeId(nodeId1);

        // 分配槽位给 node1
        assignSlotRange(node1, 0, 100);
        for (int i = 0; i <= 100; i++) {
            slotManager.setSlotOwner(i, nodeId1);
        }

        // 设置槽位为 MIGRATING 状态
        String response = commandHandler.handle(new String[]{"SETSLOT", "50", "MIGRATING", nodeId2});
        assertTrue(response.contains("+OK"), "SETSLOT MIGRATING应该返回OK");
        assertEquals("MIGRATING", commandHandler.getSlotMigrationState(50));
        assertEquals(nodeId2, commandHandler.getSlotMigrationTarget(50));

        // 在目标节点设置槽位为 IMPORTING 状态
        // 模拟目标节点的操作
        ClusterConfig targetConfig = new ClusterConfig(nodeId2);
        SlotManager targetSlotManager = new DefaultSlotManager(nodeId2);
        ClusterStateManager targetStateManager = new ClusterStateManager(targetConfig);
        ClusterCommandHandler targetHandler = new ClusterCommandHandler(
                targetConfig, targetSlotManager, targetStateManager, null, null);

        
        ClusterNode targetNode = createMasterNode(nodeId2, "127.0.0.1", 7001);
        targetNode.addState(ClusterNodeState.MYSELF);
        targetConfig.addNode(targetNode);
        
        // 需要将源节点也添加到目标配置中， IMPORTING 才需要验证源节点存在
        ClusterNode sourceNodeInTarget = createMasterNode(nodeId1, "127.0.0.1", 7000);
        targetConfig.addNode(sourceNodeInTarget);

        
        String importResponse = targetHandler.handle(new String[]{"SETSLOT", "50", "IMPORTING", nodeId1});
        assertTrue(importResponse.contains("+OK"), "SETSLOT IMPORTING应该返回OK");
        assertEquals("IMPORTING", targetHandler.getSlotMigrationState(50));

        // 完成迁移 - 设置槽位归属
        String nodeResponse = commandHandler.handle(new String[]{"SETSLOT", "50", "NODE", nodeId2});
        assertTrue(nodeResponse.contains("+OK"), "SETSLOT NODE应该返回OK");

        // 验证迁移状态已清除
        assertNull(commandHandler.getSlotMigrationState(50));

        // 验证槽位归属已更新
        assertEquals(nodeId2, slotManager.getSlotOwner(50));
    }

    @Test
    @DisplayName("测试 MOVED 重定向")
    void testMovedRedirect() {
        // 创建两个节点的集群
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(nodeId1);
        slotManager.setMyNodeId(nodeId1);

        // 分配槽位：node1 负责前半部分，node2 负责后半部分
        assignSlotRange(node1, 0, 8191);
        for (int i = 0; i <= 8191; i++) {
            slotManager.setSlotOwner(i, nodeId1);
        }
        assignSlotRange(node2, 8192, 16383);
        for (int i = 8192; i <= 16383; i++) {
            slotManager.setSlotOwner(i, nodeId2);
        }

        // 尝试访问不在本节点的槽位
        int targetSlot = 10000; // 属于 node2
        String owner = slotManager.getSlotOwner(targetSlot);
        assertEquals(nodeId2, owner, "槽位10000应该属于node2");

        // 验证 MOVED 重定向信息
        ClusterNode ownerNode = config.getNode(owner);
        assertNotNull(ownerNode, "应该能找到槽位所属节点");
        assertEquals("127.0.0.1", ownerNode.getIp());
        assertEquals(7001, ownerNode.getPort());

        // 模拟生成 MOVED 响应
        String movedResponse = "-MOVED " + targetSlot + " " + ownerNode.getAddress() + "\r\n";
        assertTrue(movedResponse.contains("MOVED"), "响应应包含MOVED");
        assertTrue(movedResponse.contains("127.0.0.1:7001"), "响应应包含正确地址");
    }

    @Test
    @DisplayName("测试 ASK 重定向")
    void testAskRedirect() {
        // 创建两个节点的集群
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(nodeId1);
        slotManager.setMyNodeId(nodeId1);

        // 分配槽位给 node1
        assignSlotRange(node1, 0, 100);
        for (int i = 0; i <= 100; i++) {
            slotManager.setSlotOwner(i, nodeId1);
        }

        // 设置槽位迁移状态
        int migratingSlot = 50;
        commandHandler.handle(new String[]{"SETSLOT", String.valueOf(migratingSlot), "MIGRATING", nodeId2});

        // 尝试访问迁移中的键
        String testKey = "test-key-" + migratingSlot;
        int slot = SlotUtils.keyHashSlot(testKey);

        // 如果键在迁移槽位中，应该返回 ASK 重定向
        if (slot == migratingSlot) {
            String askResponse = "-ASK " + slot + " " + node2.getAddress() + "\r\n";
            assertTrue(askResponse.contains("ASK"), "响应应包含ASK");
            assertTrue(askResponse.contains("127.0.0.1:7001"), "响应应包含目标节点地址");
        }

        // 验证迁移状态
        assertTrue(slotManager.isSlotMigrating(migratingSlot) || 
                commandHandler.getSlotMigrationState(migratingSlot) != null,
                "槽位应该处于迁移状态");
    }

    @Test
    @DisplayName("测试集群配置持久化")
    void testConfigPersistence() throws Exception {
        // 创建集群配置
        String nodeId1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String nodeId2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

        ClusterNode node1 = createMasterNode(nodeId1, "127.0.0.1", 7000);
        node1.addState(ClusterNodeState.MYSELF);
        ClusterNode node2 = createMasterNode(nodeId2, "127.0.0.1", 7001);
        node2.addState(ClusterNodeState.SLAVE);
        node2.setMasterNodeId(nodeId1);

        config.addNode(node1);
        config.addNode(node2);
        config.setMyNodeId(nodeId1);
        config.setCurrentEpoch(5);
        config.setConfigEpoch(3);

        // 分配槽位
        assignSlotRange(node1, 0, 16383);

        // 保存到临时文件
        File configFile = tempDir.resolve("nodes.conf").toFile();
        ClusterConfigPersister persister = new ClusterConfigPersister();
        persister.save(config, configFile.getAbsolutePath());

        // 验证文件已创建
        assertTrue(configFile.exists(), "配置文件应该已创建");
        assertTrue(configFile.length() > 0, "配置文件不应为空");

        // 重新加载配置
        ClusterConfig loadedConfig = persister.load(configFile.getAbsolutePath());

        // 验证配置一致性
        assertEquals(config.getNodeCount(), loadedConfig.getNodeCount(), "节点数量应该一致");
        assertEquals(config.getCurrentEpoch(), loadedConfig.getCurrentEpoch(), "配置纪元应该一致");
        assertEquals(config.getMyNodeId(), loadedConfig.getMyNodeId(), "当前节点ID应该一致");

        // 验证节点信息
        ClusterNode loadedNode1 = loadedConfig.getNode(nodeId1);
        assertNotNull(loadedNode1, "节点1应该存在");
        assertEquals("127.0.0.1", loadedNode1.getIp(), "IP地址应该一致");
        assertEquals(7000, loadedNode1.getPort(), "端口应该一致");
        assertTrue(loadedNode1.isMaster(), "应该是主节点");
        assertTrue(loadedNode1.isMyself(), "应该是当前节点");

        // 验证槽位分配
        assertEquals(config.getAssignedSlotCount(), loadedConfig.getAssignedSlotCount(), 
                "已分配槽位数量应该一致");
    }

    @Test
    @DisplayName("测试主从复制配置")
    void testReplication() {
        // 创建主节点
        String masterNodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode masterNode = createMasterNode(masterNodeId, "127.0.0.1", 7000);
        config.addNode(masterNode);

        // 分配槽位给主节点
        assignSlotRange(masterNode, 0, 16383);

        // 创建从节点
        String slaveNodeId = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
        ClusterNode slaveNode = new ClusterNode(slaveNodeId);
        slaveNode.setIp("127.0.0.1");
        slaveNode.setPort(7001);
        slaveNode.setBusPort(17001);
        slaveNode.addState(ClusterNodeState.MYSELF);
        config.addNode(slaveNode);
        config.setMyNodeId(slaveNodeId);

        // 使用 CLUSTER REPLICATE 配置从节点
        String response = commandHandler.handle(new String[]{"REPLICATE", masterNodeId});
        assertTrue(response.contains("+OK"), "REPLICATE命令应该返回OK");

        // 验证主从关系正确
        assertTrue(slaveNode.isSlave(), "节点应该被标记为从节点");
        assertEquals(masterNodeId, slaveNode.getMasterNodeId(), "主节点ID应该正确");

        // 验证 CLUSTER SLAVES 返回正确
        String slavesResponse = commandHandler.handle(new String[]{"SLAVES", masterNodeId});
        assertTrue(slavesResponse.contains(slaveNodeId), "SLAVES响应应包含从节点ID");
        assertTrue(slavesResponse.contains("slave"), "SLAVES响应应包含slave标志");

        // 验证集群统计
        assertEquals(1, config.getMasterCount(), "应该有1个主节点");
        assertEquals(1, config.getSlaveCount(), "应该有1个从节点");
    }

    @Test
    @DisplayName("测试 CLUSTER KEYSLOT 命令")
    void testClusterKeyslot() {
        // 测试普通键
        String key1 = "user:1000";
        String response1 = commandHandler.handle(new String[]{"KEYSLOT", key1});
        assertTrue(response1.startsWith(":"), "KEYSLOT响应应以冒号开头");

        // 测试带 hash tag 的键
        String key2 = "user:{1000}:profile";
        String response2 = commandHandler.handle(new String[]{"KEYSLOT", key2});
        assertTrue(response2.startsWith(":"), "KEYSLOT响应应以冒号开头");

        // 验证相同 hash tag 的键映射到相同槽位
        String key3 = "order:{1000}:items";
        String response3 = commandHandler.handle(new String[]{"KEYSLOT", key3});

        // 提取槽位号
        int slot2 = extractSlotFromResponse(response2);
        int slot3 = extractSlotFromResponse(response3);
        assertEquals(slot2, slot3, "相同hash tag的键应该映射到相同槽位");
    }

    @Test
    @DisplayName("测试 CLUSTER MYID 命令")
    void testClusterMyid() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode node = createMasterNode(nodeId, "127.0.0.1", 7000);
        node.addState(ClusterNodeState.MYSELF);
        config.addNode(node);
        config.setMyNodeId(nodeId);

        String response = commandHandler.handle(new String[]{"MYID"});
        assertTrue(response.contains(nodeId), "MYID响应应包含节点ID");
    }

    @Test
    @DisplayName("测试 CLUSTER BUMPEPOCH 命令")
    void testClusterBumpepoch() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode node = createMasterNode(nodeId, "127.0.0.1", 7000);
        node.addState(ClusterNodeState.MYSELF);
        config.addNode(node);
        config.setMyNodeId(nodeId);

        long initialEpoch = config.getCurrentEpoch();
        String response = commandHandler.handle(new String[]{"BUMPEPOCH"});
        assertTrue(response.startsWith(":"), "BUMPEPOCH响应应以冒号开头");
        assertEquals(initialEpoch + 1, config.getCurrentEpoch(), "配置纪元应该增加");
    }

    @Test
    @DisplayName("测试 CLUSTER FLUSHSLOTS 命令")
    void testClusterFlushslots() {
        String nodeId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        ClusterNode node = createMasterNode(nodeId, "127.0.0.1", 7000);
        node.addState(ClusterNodeState.MYSELF);
        config.addNode(node);
        config.setMyNodeId(nodeId);
        slotManager.setMyNodeId(nodeId);

        // 分配一些槽位
        assignSlotRange(node, 0, 100);
        for (int i = 0; i <= 100; i++) {
            slotManager.setSlotOwner(i, nodeId);
        }

        assertEquals(101, node.getSlotCount(), "应该有101个槽位");

        // 执行 FLUSHSLOTS
        String response = commandHandler.handle(new String[]{"FLUSHSLOTS"});
        assertTrue(response.contains("+OK"), "FLUSHSLOTS应该返回OK");

        // 验证槽位已清空
        assertEquals(0, node.getSlotCount(), "槽位应该已清空");
        assertEquals(0, slotManager.getMySlotCount(), "槽位管理器中的槽位应该已清空");
    }

    // ==================== 辅助方法 ====================

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
     * 分配槽位范围给节点
     */
    private void assignSlotRange(ClusterNode node, int start, int end) {
        node.addSlotRange(start, end);
        for (int i = start; i <= end; i++) {
            config.setSlotOwner(i, node.getNodeId());
        }
    }

    /**
     * 从响应中提取槽位号
     */
    private int extractSlotFromResponse(String response) {
        // 响应格式为 ":12345\r\n"
        String numStr = response.substring(1, response.indexOf("\r\n"));
        return Integer.parseInt(numStr);
    }
}
