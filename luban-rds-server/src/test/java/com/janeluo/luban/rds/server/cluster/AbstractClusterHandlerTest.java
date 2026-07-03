package com.janeluo.luban.rds.server.cluster;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;

import java.nio.charset.StandardCharsets;

/**
 * 集群模式 Handler 测试基类
 * 封装集群模式 EmbeddedChannel 的创建逻辑
 */
public abstract class AbstractClusterHandlerTest {

    protected static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    protected static final String NODE_ID_2 = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    /**
     * 创建集群模式的 EmbeddedChannel
     *
     * @return 配置了集群模式的 EmbeddedChannel
     */
    protected EmbeddedChannel createClusterChannel() {
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
        ClusterStateManager stateManager = new ClusterStateManager(clusterConfig);
        ClusterCommandHandler clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, slotManager, stateManager, null);

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0,
                true, clusterConfig, slotManager);
        handler.setClusterCommandHandler(clusterCommandHandler);

        return new EmbeddedChannel(handler);
    }

    /**
     * 创建非集群模式的 EmbeddedChannel
     *
     * @return 非集群模式的 EmbeddedChannel
     */
    protected EmbeddedChannel createNonClusterChannel() {
        MemoryStore memoryStore = new DefaultMemoryStore();
        DefaultCommandHandler commandHandler = new DefaultCommandHandler();
        RedisProtocolParser protocolParser = new RedisProtocolParser();

        RedisServerHandler handler = new RedisServerHandler(
                memoryStore, commandHandler, protocolParser, 0);

        return new EmbeddedChannel(handler);
    }

    /**
     * 发送 RESP 命令并返回响应字符串
     *
     * @param channel 目标通道
     * @param parts   命令部分，parts[0] 为命令名，parts[1+] 为参数
     * @return 响应字符串，无响应时返回 null
     */
    protected String sendCommand(EmbeddedChannel channel, String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            sb.append("$").append(part.length()).append("\r\n");
            sb.append(part).append("\r\n");
        }
        ByteBuf input = Unpooled.copiedBuffer(sb.toString(), StandardCharsets.UTF_8);
        channel.writeInbound(input);
        channel.flush();

        ByteBuf response = channel.readOutbound();
        if (response != null) {
            String responseStr = response.toString(StandardCharsets.UTF_8);
            response.release();
            return responseStr;
        }
        return null;
    }

    /**
     * 获取 ClientInfo（通过反射从 channel attribute 获取）
     *
     * @param channel 目标通道
     * @return ClientInfo 对象，获取失败时返回 null
     */
    protected Object getClientInfo(EmbeddedChannel channel) {
        try {
            java.lang.reflect.Field field = RedisServerHandler.class.getDeclaredField("CLIENT_INFO_KEY");
            field.setAccessible(true);
            AttributeKey<?> key = (AttributeKey<?>) field.get(null);
            return channel.attr(key).get();
        } catch (Exception e) {
            return null;
        }
    }
}
