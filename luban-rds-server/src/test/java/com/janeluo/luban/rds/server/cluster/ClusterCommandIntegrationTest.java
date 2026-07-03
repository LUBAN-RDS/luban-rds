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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群命令集成测试
 * 测试 ASKING、READONLY、READWRITE 命令在集群模式下的行为
 */
class ClusterCommandIntegrationTest extends AbstractClusterHandlerTest {

    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        channel = createClusterChannel();
    }

    @Test
    @DisplayName("ASKING 命令应返回 OK 并设置 asking 状态为 true")
    void testAskingCommand() throws Exception {
        String response = sendCommand(channel, "ASKING");
        assertEquals("+OK\r\n", response);

        Object clientInfo = getClientInfo(channel);
        assertNotNull(clientInfo);
        Method isAsking = clientInfo.getClass().getDeclaredMethod("isAsking");
        isAsking.setAccessible(true);
        assertTrue((boolean) isAsking.invoke(clientInfo));
    }

    @Test
    @DisplayName("ASKING 状态在执行带键命令后应被清除（一次性使用）")
    void testAskingOneTimeUse() throws Exception {
        // 创建分配了槽位的集群通道，使带键命令能通过 MOVED 检查进入 ASK 检查
        String key = "testkey";
        int slot = SlotUtils.keyHashSlot(key);
        EmbeddedChannel slotChannel = createClusterChannelWithSlot(slot);

        // 发送 ASKING 命令，设置 asking 状态
        String askingResponse = sendCommand(slotChannel, "ASKING");
        assertEquals("+OK\r\n", askingResponse);

        Object clientInfo = getClientInfo(slotChannel);
        assertNotNull(clientInfo);
        Method isAsking = clientInfo.getClass().getDeclaredMethod("isAsking");
        isAsking.setAccessible(true);
        assertTrue((boolean) isAsking.invoke(clientInfo));

        // 发送带键命令（GET），触发 checkAskRedirect 清除 asking 状态
        String getResponse = sendCommand(slotChannel, "GET", key);
        assertNotNull(getResponse);

        // 验证 asking 状态已被清除
        assertFalse((boolean) isAsking.invoke(clientInfo));
    }

    @Test
    @DisplayName("READONLY 命令应返回 OK 并设置 readonly 状态为 true")
    void testReadonlyCommand() throws Exception {
        String response = sendCommand(channel, "READONLY");
        assertEquals("+OK\r\n", response);

        Object clientInfo = getClientInfo(channel);
        assertNotNull(clientInfo);
        Method isReadonly = clientInfo.getClass().getDeclaredMethod("isReadonly");
        isReadonly.setAccessible(true);
        assertTrue((boolean) isReadonly.invoke(clientInfo));
    }

    @Test
    @DisplayName("READONLY 状态在执行其他命令后应保持不变（持久性）")
    void testReadonlyPersistent() throws Exception {
        // 发送 READONLY 命令
        String readonlyResponse = sendCommand(channel, "READONLY");
        assertEquals("+OK\r\n", readonlyResponse);

        // 发送 PING 命令（不需要键，不会触发重定向检查）
        String pingResponse = sendCommand(channel, "PING");
        assertEquals("+PONG\r\n", pingResponse);

        // 验证 readonly 状态仍为 true
        Object clientInfo = getClientInfo(channel);
        assertNotNull(clientInfo);
        Method isReadonly = clientInfo.getClass().getDeclaredMethod("isReadonly");
        isReadonly.setAccessible(true);
        assertTrue((boolean) isReadonly.invoke(clientInfo));
    }

    @Test
    @DisplayName("READWRITE 命令应返回 OK 并设置 readonly 状态为 false")
    void testReadwriteCommand() throws Exception {
        String response = sendCommand(channel, "READWRITE");
        assertEquals("+OK\r\n", response);

        Object clientInfo = getClientInfo(channel);
        assertNotNull(clientInfo);
        Method isReadonly = clientInfo.getClass().getDeclaredMethod("isReadonly");
        isReadonly.setAccessible(true);
        assertFalse((boolean) isReadonly.invoke(clientInfo));
    }

    @Test
    @DisplayName("READWRITE 命令应取消 READONLY 设置的只读状态")
    void testReadwriteCancelsReadonly() throws Exception {
        // 先发送 READONLY 命令
        String readonlyResponse = sendCommand(channel, "READONLY");
        assertEquals("+OK\r\n", readonlyResponse);

        Object clientInfo = getClientInfo(channel);
        assertNotNull(clientInfo);
        Method isReadonly = clientInfo.getClass().getDeclaredMethod("isReadonly");
        isReadonly.setAccessible(true);
        assertTrue((boolean) isReadonly.invoke(clientInfo));

        // 再发送 READWRITE 命令
        String readwriteResponse = sendCommand(channel, "READWRITE");
        assertEquals("+OK\r\n", readwriteResponse);

        // 验证 readonly 已被取消
        assertFalse((boolean) isReadonly.invoke(clientInfo));
    }

    /**
     * 创建分配了指定槽位的集群通道
     * 用于测试需要通过 MOVED 重定向检查的场景
     *
     * @param slot 要分配给本节点的槽位号
     * @return 配置了集群模式且分配了槽位的 EmbeddedChannel
     */
    private EmbeddedChannel createClusterChannelWithSlot(int slot) {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        ClusterConfig clusterConfig = new ClusterConfig(NODE_ID_1);

        ClusterNode myNode = new ClusterNode(NODE_ID_1);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        myNode.setBusPort(17000);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);
        clusterConfig.addNode(myNode);

        SlotManager slotManager = new DefaultSlotManager(NODE_ID_1);
        slotManager.addSlots(slot);

        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);
        ClusterCommandHandler clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0,
                true, clusterConfig, slotManager);
        handler.setClusterCommandHandler(clusterCommandHandler);

        return new EmbeddedChannel(handler);
    }
}
