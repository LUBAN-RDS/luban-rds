package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * ClusterConfigPersister 单元测试
 */
public class ClusterConfigPersisterTest {

    private ClusterConfigPersister persister;
    private File tempFile;

    @Before
    public void setUp() throws IOException {
        persister = new ClusterConfigPersister();
        tempFile = File.createTempFile("cluster-test", ".conf");
    }

    @After
    public void tearDown() {
        if (tempFile != null && tempFile.exists()) {
            tempFile.delete();
        }
    }

    @Test
    public void testGenerateNodeId() {
        String nodeId = ClusterConfigPersister.generateNodeId();

        assertNotNull(nodeId);
        assertEquals(40, nodeId.length());
        assertTrue(nodeId.matches("[0-9a-fA-F]+"));
    }

    @Test
    public void testGenerateNodeIdUniqueness() {
        String id1 = ClusterConfigPersister.generateNodeId();
        String id2 = ClusterConfigPersister.generateNodeId();

        // 两次生成的ID应该不同
        assertNotEquals(id1, id2);
    }

    @Test
    public void testSaveAndLoad() throws IOException {
        // 创建测试配置
        ClusterConfig config = createTestConfig();

        // 保存配置
        persister.save(config, tempFile.getAbsolutePath());

        // 验证文件已创建
        assertTrue(tempFile.exists());

        // 加载配置
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        // 验证加载的配置
        assertNotNull(loadedConfig);
        assertEquals(config.getMyNodeId(), loadedConfig.getMyNodeId());
        assertEquals(config.getCurrentEpoch(), loadedConfig.getCurrentEpoch());
        assertEquals(config.getConfigEpoch(), loadedConfig.getConfigEpoch());
        assertEquals(config.getNodeCount(), loadedConfig.getNodeCount());
    }

    @Test
    public void testSaveAndLoadWithNodes() throws IOException {
        ClusterConfig config = new ClusterConfig();
        config.setMyNodeId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        config.setCurrentEpoch(10);
        config.setConfigEpoch(5);

        // 添加主节点
        ClusterNode master = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        master.setIp("127.0.0.1");
        master.setPort(7000);
        master.setBusPort(17000);
        master.addState(ClusterNodeState.MASTER);
        master.addState(ClusterNodeState.MYSELF);
        master.setConfigEpoch(5);
        master.addSlotRange(0, 5460);
        config.addNode(master);

        // 添加从节点
        ClusterNode slave = new ClusterNode("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
        slave.setIp("127.0.0.1");
        slave.setPort(7001);
        slave.setBusPort(17001);
        slave.addState(ClusterNodeState.SLAVE);
        slave.setMasterNodeId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        slave.setConfigEpoch(3);
        config.addNode(slave);

        // 设置槽位分配
        for (int i = 0; i <= 5460; i++) {
            config.setSlotOwner(i, master.getNodeId());
        }

        // 保存和加载
        persister.save(config, tempFile.getAbsolutePath());
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        // 验证节点信息
        assertEquals(2, loadedConfig.getNodeCount());

        // 验证主节点
        ClusterNode loadedMaster = loadedConfig.getNode(master.getNodeId());
        assertNotNull(loadedMaster);
        assertEquals("127.0.0.1", loadedMaster.getIp());
        assertEquals(7000, loadedMaster.getPort());
        assertEquals(17000, loadedMaster.getBusPort());
        assertTrue(loadedMaster.isMaster());
        assertTrue(loadedMaster.isMyself());
        assertEquals(5, loadedMaster.getConfigEpoch());
        assertEquals(5461, loadedMaster.getSlotCount());

        // 验证从节点
        ClusterNode loadedSlave = loadedConfig.getNode(slave.getNodeId());
        assertNotNull(loadedSlave);
        assertEquals("127.0.0.1", loadedSlave.getIp());
        assertEquals(7001, loadedSlave.getPort());
        assertTrue(loadedSlave.isSlave());
        assertEquals(master.getNodeId(), loadedSlave.getMasterNodeId());
    }

    @Test
    public void testSaveWithNullConfig() {
        try {
            persister.save(null, tempFile.getAbsolutePath());
            fail("应该抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("不能为空"));
        } catch (IOException e) {
            fail("不应该抛出IOException");
        }
    }

    @Test
    public void testSaveWithNullFilePath() {
        ClusterConfig config = new ClusterConfig();
        try {
            persister.save(config, null);
            fail("应该抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("不能为空"));
        } catch (IOException e) {
            fail("不应该抛出IOException");
        }
    }

    @Test
    public void testLoadWithNullFilePath() {
        try {
            persister.load(null);
            fail("应该抛出异常");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("不能为空"));
        } catch (IOException e) {
            fail("不应该抛出IOException");
        }
    }

    @Test
    public void testLoadNonExistentFile() {
        try {
            persister.load("/non/existent/file.conf");
            fail("应该抛出异常");
        } catch (IOException e) {
            // 预期的异常
        }
    }

    @Test
    public void testFileFormatCompatibility() throws IOException {
        // 创建一个兼容Redis nodes.conf格式的文件
        String content = "# Test Cluster Config\n" +
                "# Current Epoch: 5\n" +
                "# My Config Epoch: 3\n" +
                "\n" +
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0 127.0.0.1:7000@17000 myself,master - 0 1234567890 3 connected 0-5460\n" +
                "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0 127.0.0.1:7001@17001 slave a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0 0 1234567890 2 connected\n";

        Files.write(tempFile.toPath(), content.getBytes());

        // 加载配置
        ClusterConfig config = persister.load(tempFile.getAbsolutePath());

        // 验证
        assertEquals(5, config.getCurrentEpoch());
        assertEquals(3, config.getConfigEpoch());
        assertEquals(2, config.getNodeCount());

        // 验证主节点
        ClusterNode master = config.getNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        assertNotNull(master);
        assertTrue(master.isMaster());
        assertTrue(master.isMyself());
        assertEquals(5461, master.getSlotCount());

        // 验证从节点
        ClusterNode slave = config.getNode("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
        assertNotNull(slave);
        assertTrue(slave.isSlave());
        assertEquals("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", slave.getMasterNodeId());
    }

    @Test
    public void testSaveAndLoadWithDisconnectedNode() throws IOException {
        ClusterConfig config = new ClusterConfig();

        ClusterNode node = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        node.setIp("127.0.0.1");
        node.setPort(7000);
        node.setBusPort(17000);
        node.addState(ClusterNodeState.MASTER);
        node.getLink().setConnected(false); // 设置为断开连接
        config.addNode(node);

        // 保存和加载
        persister.save(config, tempFile.getAbsolutePath());
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        ClusterNode loadedNode = loadedConfig.getNode(node.getNodeId());
        assertNotNull(loadedNode);
        assertFalse(loadedNode.getLink().isConnected());
    }

    @Test
    public void testSaveAndLoadWithFailState() throws IOException {
        ClusterConfig config = new ClusterConfig();

        ClusterNode node = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        node.setIp("127.0.0.1");
        node.setPort(7000);
        node.setBusPort(17000);
        node.addState(ClusterNodeState.MASTER);
        node.addState(ClusterNodeState.FAIL);
        config.addNode(node);

        // 保存和加载
        persister.save(config, tempFile.getAbsolutePath());
        // 验证 nodes.conf 中不包含 fail 标志（FAIL 是运行时瞬时状态，不应落盘）
        String persisted = new String(Files.readAllBytes(tempFile.toPath()));
        assertFalse("nodes.conf 不应持久化 fail 标志", persisted.contains("fail"));
        assertFalse("nodes.conf 不应持久化 fail? 标志", persisted.contains("fail?"));

        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        ClusterNode loadedNode = loadedConfig.getNode(node.getNodeId());
        assertNotNull(loadedNode);
        // FAIL 状态不应在往返后保留（对齐 Redis：重启后由故障检测器重新判定）
        assertFalse("FAIL 状态不应被持久化恢复", loadedNode.isFail());
        // MASTER 等持久状态仍应保留
        assertTrue(loadedNode.isMaster());
    }

    @Test
    public void testSaveAndLoadWithPfailState() throws IOException {
        ClusterConfig config = new ClusterConfig();

        ClusterNode node = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        node.setIp("127.0.0.1");
        node.setPort(7000);
        node.setBusPort(17000);
        node.addState(ClusterNodeState.MASTER);
        node.addState(ClusterNodeState.PFAIL);
        config.addNode(node);

        // 保存和加载
        persister.save(config, tempFile.getAbsolutePath());
        // 验证 nodes.conf 中不包含 fail? 标志（PFAIL 是运行时瞬时状态，不应落盘）
        String persisted = new String(Files.readAllBytes(tempFile.toPath()));
        assertFalse("nodes.conf 不应持久化 fail? 标志", persisted.contains("fail?"));

        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        ClusterNode loadedNode = loadedConfig.getNode(node.getNodeId());
        assertNotNull(loadedNode);
        // PFAIL 状态不应在往返后保留
        assertFalse("PFAIL 状态不应被持久化恢复", loadedNode.isPfail());
        assertTrue(loadedNode.isMaster());
    }

    /**
     * 回归测试：兼容修复前落盘的旧 nodes.conf（含 fail/fail? 标志）
     * <p>
     * 全集群停止前对端节点可能被标记为 FAIL/PFAIL，旧版本会把 fail 标志写入 nodes.conf。
     * 加载时必须忽略这些瞬时状态标志，否则重启后对端节点以 FAIL 状态加载且永远无法恢复
     * （无人发 PING 触发 clearNodeFailState），集群状态恒为 fail。
     * </p>
     */
    @Test
    public void testLoadLegacyFailStateCleared() throws IOException {
        // 模拟修复前版本落盘的 nodes.conf：含 fail 和 fail? 标志
        String content = "# Legacy nodes.conf with fail flags\n" +
                "# Current Epoch: 5\n" +
                "# My Config Epoch: 3\n" +
                "\n" +
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0 127.0.0.1:7000@17000 myself,master - 0 1234567890 3 connected 0-5460\n" +
                "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0 127.0.0.1:7001@17001 master,fail - 0 1234567890 2 disconnected 5461-10922\n" +
                "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0 127.0.0.1:7002@17002 master,fail? - 0 1234567890 1 disconnected 10923-16383\n";

        Files.write(tempFile.toPath(), content.getBytes());

        // 加载配置
        ClusterConfig config = persister.load(tempFile.getAbsolutePath());

        // 验证：所有节点加载后都不应携带 FAIL/PFAIL 状态
        ClusterNode failNode = config.getNode("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
        assertNotNull(failNode);
        assertFalse("旧 fail 标志应被清除", failNode.isFail());
        assertTrue(failNode.isMaster());

        ClusterNode pfailNode = config.getNode("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0");
        assertNotNull(pfailNode);
        assertFalse("旧 fail? 标志应被清除", pfailNode.isPfail());
        assertTrue(pfailNode.isMaster());

        // 验证：槽位分配仍应正确恢复
        assertEquals("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0", config.getSlotOwner(5461));
        assertEquals("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0", config.getSlotOwner(10923));
    }

    /**
     * 测试多节点集群场景：验证 nodes.conf 能正确保存所有节点信息（不仅是 MYSELF 节点）
     * <p>
     * 参照 Redis 7 规范：nodes.conf 应记录集群中所有已知节点的信息，
     * 包括主节点、从节点及其槽位分配，而不仅仅是当前节点自身。
     * </p>
     */
    @Test
    public void testSaveAllNodesInCluster() throws IOException {
        ClusterConfig config = new ClusterConfig();
        config.setMyNodeId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");

        // 创建 3 个主节点（模拟 3-master 集群）
        ClusterNode master1 = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        master1.setIp("192.168.1.1");
        master1.setPort(7000);
        master1.setBusPort(17000);
        master1.addState(ClusterNodeState.MASTER);
        master1.addState(ClusterNodeState.MYSELF);
        master1.setConfigEpoch(1);
        master1.addSlotRange(0, 5460);
        config.addNode(master1);

        ClusterNode master2 = new ClusterNode("b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0");
        master2.setIp("192.168.1.2");
        master2.setPort(7001);
        master2.setBusPort(17001);
        master2.addState(ClusterNodeState.MASTER);
        master2.setConfigEpoch(2);
        master2.addSlotRange(5461, 10922);
        config.addNode(master2);

        ClusterNode master3 = new ClusterNode("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0");
        master3.setIp("192.168.1.3");
        master3.setPort(7002);
        master3.setBusPort(17002);
        master3.addState(ClusterNodeState.MASTER);
        master3.setConfigEpoch(3);
        master3.addSlotRange(10923, 16383);
        config.addNode(master3);

        // 添加一个从节点
        ClusterNode slave = new ClusterNode("d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0");
        slave.setIp("192.168.1.4");
        slave.setPort(7003);
        slave.setBusPort(17003);
        slave.addState(ClusterNodeState.SLAVE);
        slave.setMasterNodeId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        slave.setConfigEpoch(1);
        config.addNode(slave);

        // 设置槽位分配
        for (int i = 0; i <= 5460; i++) {
            config.setSlotOwner(i, master1.getNodeId());
        }
        for (int i = 5461; i <= 10922; i++) {
            config.setSlotOwner(i, master2.getNodeId());
        }
        for (int i = 10923; i <= 16383; i++) {
            config.setSlotOwner(i, master3.getNodeId());
        }

        // 保存配置
        persister.save(config, tempFile.getAbsolutePath());
        assertTrue("nodes.conf 文件应存在", tempFile.exists());

        // 加载配置
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        // 验证：所有 4 个节点都应被正确保存和加载
        assertEquals("应包含所有 4 个节点", 4, loadedConfig.getNodeCount());

        // 验证每个节点
        for (String nodeId : new String[]{
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0",
                "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0",
                "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0",
                "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0"}) {
            ClusterNode loadedNode = loadedConfig.getNode(nodeId);
            assertNotNull("节点 " + nodeId + " 应在加载的配置中", loadedNode);
        }

        // 验证槽位分配
        assertEquals(master1.getNodeId(), loadedConfig.getSlotOwner(0));
        assertEquals(master2.getNodeId(), loadedConfig.getSlotOwner(5461));
        assertEquals(master3.getNodeId(), loadedConfig.getSlotOwner(16383));

        // 验证 MYSELF 标志
        ClusterNode myselfNode = loadedConfig.getNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        assertTrue("MYSELF 节点应有 myself 标志", myselfNode.isMyself());

        // 验证从节点关系
        ClusterNode loadedSlave = loadedConfig.getNode("d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0");
        assertTrue("从节点应有 slave 标志", loadedSlave.isSlave());
        assertEquals("从节点应关联到正确的 master", 
                "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", 
                loadedSlave.getMasterNodeId());
    }

    /**
     * 创建测试配置
     */
    private ClusterConfig createTestConfig() {
        ClusterConfig config = new ClusterConfig();
        config.setMyNodeId("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        config.setCurrentEpoch(10);
        config.setConfigEpoch(5);

        ClusterNode node = new ClusterNode("a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0");
        node.setIp("127.0.0.1");
        node.setPort(7000);
        node.setBusPort(17000);
        node.addState(ClusterNodeState.MASTER);
        node.addState(ClusterNodeState.MYSELF);
        config.addNode(node);

        return config;
    }
}
