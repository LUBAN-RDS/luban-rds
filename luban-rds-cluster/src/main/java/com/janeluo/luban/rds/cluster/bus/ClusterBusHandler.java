package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.FailMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.gossip.MeetMessage;
import com.janeluo.luban.rds.cluster.gossip.PingMessage;
import com.janeluo.luban.rds.cluster.gossip.PongMessage;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
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
     * 远程节点ID（连接建立后设置）
     */
    private String remoteNodeId;

    /**
     * 构造方法
     *
     * @param clusterConfig   集群配置
     * @param gossipProtocol  Gossip 协议处理器
     */
    public ClusterBusHandler(ClusterConfig clusterConfig, GossipProtocol gossipProtocol) {
        this.clusterConfig = clusterConfig;
        this.gossipProtocol = gossipProtocol;
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

        logger.debug("收到 Gossip 消息: {}", message);

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
        logger.debug("收到 PONG 消息，来自节点: {}", pong.getSenderNodeId());

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
