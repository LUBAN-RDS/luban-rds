package com.janeluo.luban.rds.server.cluster;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群重定向集成测试
 * <p>
 * 测试 RedisServerHandler 中的集群重定向逻辑：
 * <ul>
 *     <li>MOVED 重定向：键所属槽位由其他节点负责</li>
 *     <li>CLUSTERDOWN：槽位未分配或槽位属主节点不存在</li>
 *     <li>ASK 重定向：槽位迁移中且键已不在本节点</li>
 *     <li>ASKING 命令：允许访问导入中的槽位</li>
 * </ul>
 */
class ClusterRedirectIntegrationTest extends AbstractClusterHandlerTest {

    @Test
    @DisplayName("MOVED 重定向：键所属槽位属于其他节点时应返回 -MOVED")
    void testMovedRedirect() {
        EmbeddedChannel channel = createChannelWithMovedRedirect();
        int slot = SlotUtils.keyHashSlot("foo");

        String response = sendCommand(channel, "GET", "foo");

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-MOVED "), "应返回 MOVED 重定向，实际: " + response);
        assertTrue(response.contains(slot + " 127.0.0.1:7001"),
                "应包含槽位号和目标节点地址，实际: " + response);
    }

    @Test
    @DisplayName("本地槽位正常执行：键所属槽位在本节点时命令正常执行")
    void testSlotLocalNormalExecution() {
        int slot = SlotUtils.keyHashSlot("foo");
        EmbeddedChannel channel = createChannelWithLocalSlot(slot);

        String response = sendCommand(channel, "SET", "foo", "bar");

        assertNotNull(response, "响应不应为 null");
        assertEquals("+OK\r\n", response, "SET 命令应正常执行返回 +OK");
    }

    @Test
    @DisplayName("CLUSTERDOWN：槽位未分配时应返回 -CLUSTERDOWN Hash slot not served")
    void testClusterdownUnassignedSlot() {
        EmbeddedChannel channel = createClusterChannel();

        String response = sendCommand(channel, "GET", "anykey");

        assertNotNull(response, "响应不应为 null");
        assertEquals("-CLUSTERDOWN Hash slot not served\r\n", response,
                "未分配槽位应返回 CLUSTERDOWN");
    }

    @Test
    @DisplayName("CLUSTERDOWN：槽位属主节点不在集群配置中时应返回 -CLUSTERDOWN Slot owner not found")
    void testClusterdownOwnerNotFound() {
        int slot = SlotUtils.keyHashSlot("foo");
        EmbeddedChannel channel = createChannelWithOwnerNotFound(slot);

        String response = sendCommand(channel, "GET", "foo");

        assertNotNull(response, "响应不应为 null");
        assertEquals("-CLUSTERDOWN Slot owner not found\r\n", response,
                "属主节点不存在时应返回 CLUSTERDOWN");
    }

    @Test
    @DisplayName("ASK 重定向：槽位迁移中且键不存在时应返回 -ASK")
    void testAskRedirectKeyNotExists() {
        int slot = SlotUtils.keyHashSlot("foo");
        EmbeddedChannel channel = createChannelWithMigratingSlot(slot, false);

        String response = sendCommand(channel, "GET", "foo");

        assertNotNull(response, "响应不应为 null");
        assertTrue(response.startsWith("-ASK "), "应返回 ASK 重定向，实际: " + response);
        assertTrue(response.contains(slot + " 127.0.0.1:7001"),
                "应包含槽位号和目标节点地址，实际: " + response);
    }

    @Test
    @DisplayName("ASK 例外：槽位迁移中但键仍在本节点时命令正常执行")
    void testAskRedirectKeyExists() {
        int slot = SlotUtils.keyHashSlot("foo");
        EmbeddedChannel channel = createChannelWithMigratingSlot(slot, true);

        String response = sendCommand(channel, "GET", "foo");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-ASK "), "键存在时不应返回 ASK 重定向，实际: " + response);
        assertFalse(response.startsWith("-MOVED "), "不应返回 MOVED 重定向，实际: " + response);
        assertTrue(response.contains("bar"), "应返回键值 bar，实际: " + response);
    }

    @Test
    @DisplayName("ASKING 命令允许访问导入中的槽位")
    void testAskingAllowsImportingSlot() {
        int slot = SlotUtils.keyHashSlot("foo");
        EmbeddedChannel channel = createChannelWithImportingSlot(slot);

        // 先发送 ASKING 命令设置 asking 状态
        String askingResponse = sendCommand(channel, "ASKING");
        assertEquals("+OK\r\n", askingResponse, "ASKING 命令应返回 +OK");

        // 再发送 GET 命令，asking 状态应允许访问
        String response = sendCommand(channel, "GET", "foo");

        assertNotNull(response, "响应不应为 null");
        assertFalse(response.startsWith("-ASK "), "ASKING 状态下不应返回 ASK 重定向，实际: " + response);
        assertFalse(response.startsWith("-MOVED "), "不应返回 MOVED 重定向，实际: " + response);
        assertFalse(response.startsWith("-CLUSTERDOWN"),
                "不应返回 CLUSTERDOWN，实际: " + response);
    }

    // ==================== 辅助方法：创建不同配置的集群通道 ====================

    /**
     * 创建 MOVED 重定向场景的通道
     * 键 "foo" 的槽位通过 setSlotOwner 分配给 NODE_ID_2（127.0.0.1:7001），
     * 槽位非本地，触发 MOVED 重定向。
     */
    private EmbeddedChannel createChannelWithMovedRedirect() {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);
        addOtherNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        int slot = SlotUtils.keyHashSlot("foo");
        slotManager.setSlotOwner(slot, NODE_ID_2);

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    /**
     * 创建本地槽位场景的通道
     * 通过 addSlots 将指定槽位分配给本节点，命令可正常执行。
     *
     * @param slot 要分配给本节点的槽位号
     */
    private EmbeddedChannel createChannelWithLocalSlot(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.addSlots(slot);

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    /**
     * 创建属主节点不存在的场景的通道
     * 通过 setSlotOwner 将槽位分配给 NODE_ID_2，但不将 NODE_ID_2 加入集群配置，
     * 触发 CLUSTERDOWN Slot owner not found。
     *
     * @param slot 要分配给 NODE_ID_2 的槽位号
     */
    private EmbeddedChannel createChannelWithOwnerNotFound(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.setSlotOwner(slot, NODE_ID_2);

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    /**
     * 创建槽位迁移场景的通道
     * 先通过 addSlots 将槽位分配给本节点（本地），再设置 MIGRATING 状态。
     *
     * @param slot     要迁移的槽位号
     * @param keyExists 是否在 memoryStore 中预置键值（键名 "foo"，值 "bar"）
     */
    private EmbeddedChannel createChannelWithMigratingSlot(int slot, boolean keyExists) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);
        addOtherNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.addSlots(slot);
        slotManager.setSlotMigrating(slot, NODE_ID_2);

        if (keyExists) {
            memoryStore.set(0, "foo", "bar");
        }

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    /**
     * 创建槽位导入场景的通道
     * 先通过 addSlots 将槽位分配给本节点（本地，使 checkSlotAndRedirect 通过），
     * 再设置 IMPORTING 状态。这样 ASKING 命令可以在 checkAskRedirect 中绕过重定向。
     *
     * @param slot 要导入的槽位号
     */
    private EmbeddedChannel createChannelWithImportingSlot(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);
        addMyNode(clusterConfig);
        addOtherNode(clusterConfig);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.addSlots(slot);
        slotManager.setSlotImporting(slot, NODE_ID_2);

        return buildChannel(memoryStore, commandHandler, protocolParser, clusterConfig, slotManager);
    }

    /**
     * 向集群配置添加本节点（NODE_ID_1，127.0.0.1:7000）
     */
    private void addMyNode(ClusterConfig clusterConfig) {
        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);
        // P1-13：命令路由门控依赖 cluster_state=ok。
        clusterConfig.setState("ok");
    }

    /**
     * 向集群配置添加另一节点（NODE_ID_2，127.0.0.1:7001）
     */
    private void addOtherNode(ClusterConfig clusterConfig) {
        ClusterNode otherNode = new ClusterNode(NODE_ID_2);
        otherNode.setIp("127.0.0.1");
        otherNode.setPort(7001);
        otherNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(otherNode);
    }

    /**
     * 使用已有组件构建集群模式 EmbeddedChannel
     */
    private EmbeddedChannel buildChannel(MemoryStore memoryStore, DefaultCommandHandler commandHandler,
                                         RedisProtocolParser protocolParser, ClusterConfig clusterConfig,
                                         SlotManager slotManager) {
        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);
        ClusterCommandHandler clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null, null, null);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0,
                true, clusterConfig, slotManager);
        handler.setClusterCommandHandler(clusterCommandHandler);

        return new EmbeddedChannel(handler);
    }
}
