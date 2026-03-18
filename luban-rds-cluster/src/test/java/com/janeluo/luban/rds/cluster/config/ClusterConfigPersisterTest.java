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
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        ClusterNode loadedNode = loadedConfig.getNode(node.getNodeId());
        assertNotNull(loadedNode);
        assertTrue(loadedNode.isFail());
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
        ClusterConfig loadedConfig = persister.load(tempFile.getAbsolutePath());

        ClusterNode loadedNode = loadedConfig.getNode(node.getNodeId());
        assertNotNull(loadedNode);
        assertTrue(loadedNode.isPfail());
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
