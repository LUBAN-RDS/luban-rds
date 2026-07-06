package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.FailMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.gossip.MeetMessage;
import com.janeluo.luban.rds.cluster.gossip.PingMessage;
import com.janeluo.luban.rds.cluster.gossip.PongMessage;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * 集群总线消息处理器
 * <p>
 * 处理节点间 Gossip 协议消息
 * </p>
 */
public class ClusterBusHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBusHandler.class);

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * Gossip 协议处理器
     */
    private final GossipProtocol gossipProtocol;

    /**
     * 集群总线客户端引用（仅出站连接的处理器有值，用于 MEET 握手后更新通道映射）
     */
    private final ClusterBusClient busClient;

    /**
     * 期望的节点ID（出站连接时为临时节点ID，收到 PONG 后替换为真实节点ID）
     */
    private final String expectedNodeId;

    /**
     * 远程节点ID（连接建立后设置）
     */
    private String remoteNodeId;

    /**
     * 临时节点ID是否已解析为真实节点ID
     */
    private volatile boolean tempIdResolved;

    /**
     * 构造方法（用于 ClusterBusServer 的入站连接）
     *
     * @param clusterConfig   集群配置
     * @param gossipProtocol  Gossip 协议处理器
     */
    public ClusterBusHandler(ClusterConfig clusterConfig, GossipProtocol gossipProtocol) {
        this(clusterConfig, gossipProtocol, null, null);
    }

    /**
     * 完整构造方法（用于 ClusterBusClient 的出站连接）
     *
     * @param clusterConfig   集群配置
     * @param gossipProtocol  Gossip 协议处理器
     * @param busClient       集群总线客户端（可为 null）
     * @param expectedNodeId  期望的节点ID（临时ID，可为 null）
     */
    public ClusterBusHandler(ClusterConfig clusterConfig, GossipProtocol gossipProtocol,
                             ClusterBusClient busClient, String expectedNodeId) {
        this.clusterConfig = clusterConfig;
        this.gossipProtocol = gossipProtocol;
        this.busClient = busClient;
        this.expectedNodeId = expectedNodeId;
    }

    /**
     * 通道激活时调用
     *
     * @param ctx 通道上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.info("集群总线连接建立，远程地址: {}:{}", 
                remoteAddress.getAddress().getHostAddress(), 
                remoteAddress.getPort());
    }

    /**
     * 通道断开时调用
     *
     * @param ctx 通道上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.info("集群总线连接断开，远程地址: {}:{}, 节点ID: {}", 
                remoteAddress.getAddress().getHostAddress(), 
                remoteAddress.getPort(),
                remoteNodeId);

        // 更新节点连接状态
        if (remoteNodeId != null) {
            ClusterNode node = clusterConfig.getNode(remoteNodeId);
            if (node != null && node.getLink() != null) {
                node.getLink().setConnected(false);
            }
        }
    }

    /**
     * 收到消息时调用
     *
     * @param ctx 通道上下文
     * @param msg 消息对象
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof GossipMessage)) {
            logger.warn("收到未知类型的消息: {}", msg.getClass().getName());
            return;
        }

        GossipMessage message = (GossipMessage) msg;
        remoteNodeId = message.getSenderNodeId();

        if (logger.isTraceEnabled()) {
            logger.trace("收到 Gossip 消息: {}", message);
        }

        // MEET 握手响应处理：将临时节点ID替换为真实节点ID（仅执行一次）
        if (busClient != null && expectedNodeId != null && remoteNodeId != null
                && !expectedNodeId.equals(remoteNodeId) && !tempIdResolved) {
            tempIdResolved = true;
            resolveTempNodeId(remoteNodeId);
        }

        try {
            // 根据消息类型分发处理
            GossipMessage response = handleMessage(message);

            // 如果需要响应，发送响应消息
            if (response != null) {
                ctx.writeAndFlush(response);
            }
        } catch (Exception e) {
            logger.error("处理 Gossip 消息失败: {}", message, e);
        }
    }

    /**
     * 将临时节点ID替换为真实节点ID
     * <p>
     * 在 CLUSTER MEET 流程中，发送方用临时ID在本地配置中占位。
     * 当收到目标节点的 PONG 响应时，通过此方法将临时ID替换为真实节点ID，
     * 并更新 ClusterBusClient 的通道映射。
     * </p>
     *
     * @param realNodeId 真实节点ID
     */
    private void resolveTempNodeId(String realNodeId) {
        ClusterNode tempNode = clusterConfig.getNode(expectedNodeId);
        if (tempNode == null) {
            // 临时节点不存在，可能已被解析
            return;
        }

        // 如果真实节点已存在，移除临时节点即可
        if (clusterConfig.getNode(realNodeId) != null) {
            clusterConfig.removeNode(expectedNodeId);
            busClient.renameChannel(expectedNodeId, realNodeId);
            logger.info("节点ID解析完成（真实节点已存在）: tempId={} -> realId={}", expectedNodeId, realNodeId);
            return;
        }

        // 创建真实节点，复制临时节点的地址信息
        ClusterNode realNode = new ClusterNode(
                realNodeId,
                tempNode.getIp(),
                tempNode.getPort(),
                tempNode.getBusPort()
        );
        realNode.addState(ClusterNodeState.HANDSHAKE);

        // 移除临时节点，添加真实节点
        clusterConfig.removeNode(expectedNodeId);
        clusterConfig.addNode(realNode);

        // 更新通道映射
        busClient.renameChannel(expectedNodeId, realNodeId);

        logger.info("节点ID解析完成: tempId={} -> realId={}, address={}",
                expectedNodeId, realNodeId, realNode.getFullAddress());
    }

    /**
     * 处理消息
     *
     * @param message Gossip 消息
     * @return 响应消息，如果不需要响应则返回 null
     */
    private GossipMessage handleMessage(GossipMessage message) {
        switch (message.getType()) {
            case PING:
                return handlePing((PingMessage) message);
            case PONG:
                handlePong((PongMessage) message);
                return null;
            case MEET:
                return handleMeet((MeetMessage) message);
            case FAIL:
                handleFail((FailMessage) message);
                return null;
            case UPDATE:
                handleUpdate(message);
                return null;
            default:
                logger.warn("未知的消息类型: {}", message.getType());
                return null;
        }
    }

    /**
     * 处理 PING 消息
     *
     * @param ping PING 消息
     * @return PONG 响应
     */
    private GossipMessage handlePing(PingMessage ping) {
        logger.debug("收到 PING 消息，来自节点: {}", ping.getSenderNodeId());

        if (gossipProtocol != null) {
            return gossipProtocol.handlePing(ping);
        }

        // 默认响应
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode != null) {
            return new PongMessage(myNode.getNodeId(), System.currentTimeMillis());
        }
        return null;
    }

    /**
     * 处理 PONG 消息
     *
     * @param pong PONG 消息
     */
    private void handlePong(PongMessage pong) {
        if (logger.isTraceEnabled()) {
            logger.trace("收到 PONG 消息，来自节点: {}", pong.getSenderNodeId());
        }

        if (gossipProtocol != null) {
            gossipProtocol.handlePong(pong);
        }
    }

    /**
     * 处理 MEET 消息
     *
     * @param meet MEET 消息
     * @return PONG 响应
     */
    private GossipMessage handleMeet(MeetMessage meet) {
        logger.info("收到 MEET 消息，来自节点: {}", meet.getSenderNodeId());

        if (gossipProtocol != null) {
            gossipProtocol.handleMeet(meet);
            // 返回 PONG 响应
            ClusterNode myNode = clusterConfig.getMyNode();
            if (myNode != null) {
                return new PongMessage(myNode.getNodeId(), System.currentTimeMillis());
            }
        }
        return null;
    }

    /**
     * 处理 FAIL 消息
     *
     * @param fail FAIL 消息
     */
    private void handleFail(FailMessage fail) {
        logger.info("收到 FAIL 消息，报告节点: {}, 故障节点: {}", 
                fail.getSenderNodeId(), 
                fail.getFailedNodeId());

        if (gossipProtocol != null) {
            gossipProtocol.handleFail(fail);
        }
    }

    /**
     * 处理 UPDATE 消息
     *
     * @param message UPDATE 消息
     */
    private void handleUpdate(GossipMessage message) {
        logger.info("收到 UPDATE 消息，来自节点: {}", message.getSenderNodeId());
        // UPDATE 消息处理可以后续扩展
    }

    /**
     * 发生异常时调用
     *
     * @param ctx   通道上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        logger.error("集群总线连接异常，远程地址: {}:{}, 节点ID: {}", 
                remoteAddress.getAddress().getHostAddress(), 
                remoteAddress.getPort(),
                remoteNodeId, 
                cause);

        // 关闭连接
        ctx.close();
    }

    /**
     * 获取远程节点ID
     *
     * @return 远程节点ID
     */
    public String getRemoteNodeId() {
        return remoteNodeId;
    }
}
