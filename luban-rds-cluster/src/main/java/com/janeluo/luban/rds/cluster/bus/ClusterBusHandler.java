package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.FailMessage;
import com.janeluo.luban.rds.cluster.gossip.FailoverAuthAckMessage;
import com.janeluo.luban.rds.cluster.gossip.FailoverAuthRequestMessage;
import com.janeluo.luban.rds.cluster.gossip.FailoverResultMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.gossip.MeetMessage;
import com.janeluo.luban.rds.cluster.gossip.ManualFailoverOffsetMessage;
import com.janeluo.luban.rds.cluster.gossip.ManualFailoverStartMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyAckMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyMessage;
import com.janeluo.luban.rds.cluster.gossip.PingMessage;
import com.janeluo.luban.rds.cluster.gossip.PongMessage;
import com.janeluo.luban.rds.cluster.gossip.PublishMessage;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * 临时节点ID是否已解析为真实节点ID（CAS 保护，确保并发消息只解析一次）
     */
    private final AtomicBoolean tempIdResolved = new AtomicBoolean(false);

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
        logger.info("集群总线连接建立，远程地址: {}", formatRemoteAddress(ctx));
    }

    /**
     * 通道断开时调用
     *
     * @param ctx 通道上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("集群总线连接断开，远程地址: {}, 节点ID: {}", formatRemoteAddress(ctx), remoteNodeId);

        // 更新节点连接状态
        if (remoteNodeId != null) {
            ClusterNode node = clusterConfig.getNode(remoteNodeId);
            if (node != null && node.getLink() != null) {
                node.getLink().setConnected(false);
            }
        }
    }

    /**
     * 安全格式化远程地址，避免 remoteAddress 为 null 或类型不符时抛异常。
     *
     * @param ctx 通道上下文
     * @return 可读的远程地址字符串
     */
    private String formatRemoteAddress(ChannelHandlerContext ctx) {
        try {
            java.net.SocketAddress addr = ctx.channel().remoteAddress();
            if (addr instanceof InetSocketAddress) {
                InetSocketAddress inet = (InetSocketAddress) addr;
                return inet.getAddress() != null
                        ? inet.getAddress().getHostAddress() + ":" + inet.getPort()
                        : "unknown:" + inet.getPort();
            }
            return addr != null ? addr.toString() : "unknown";
        } catch (Exception e) {
            return "unknown";
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

        // MEET 握手响应处理：将临时节点ID替换为真实节点ID（仅执行一次，CAS 保证并发安全）
        if (busClient != null && expectedNodeId != null && remoteNodeId != null
                && !expectedNodeId.equals(remoteNodeId)
                && tempIdResolved.compareAndSet(false, true)) {
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
            case PUBLISH:
                handlePublish((PublishMessage) message);
                return null;
            case FAILOVER_AUTH_REQUEST:
                handleFailoverAuthRequest((FailoverAuthRequestMessage) message);
                return null;
            case FAILOVER_AUTH_ACK:
                handleFailoverAuthAck((FailoverAuthAckMessage) message);
                return null;
            case FAILOVER_RESULT:
                handleFailoverResult((FailoverResultMessage) message);
                return null;
            case MIGRATE_KEY:
                return handleMigrateKey((MigrateKeyMessage) message);
            case MIGRATE_KEY_ACK:
                handleMigrateKeyAck((MigrateKeyAckMessage) message);
                return null;
            case MANUAL_FAILOVER_START:
                handleManualFailoverStart((ManualFailoverStartMessage) message);
                return null;
            case MANUAL_FAILOVER_OFFSET:
                handleManualFailoverOffset((ManualFailoverOffsetMessage) message);
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
     * 处理 PUBLISH 消息（跨节点 Pub/Sub 传播）。
     * <p>
     * 委托 GossipProtocol 转发到本地 PubSubManager。若 GossipProtocol 未注入或
     * 暂未实现本地订阅转发，记录告警但不丢失消息类型分支。
     * </p>
     *
     * @param msg PUBLISH 消息
     */
    private void handlePublish(PublishMessage msg) {
        logger.debug("收到 PUBLISH 消息，来自节点: {}, 频道: {}", msg.getSenderNodeId(), msg.getChannel());
        if (gossipProtocol != null) {
            gossipProtocol.handlePublish(msg);
        } else {
            logger.warn("收到 PUBLISH 消息但 GossipProtocol 未注入，频道: {}", msg.getChannel());
        }
    }

    /**
     * 处理故障转移授权请求（委托给 GossipProtocol/FailoverManager）。
     *
     * @param msg 授权请求消息
     */
    private void handleFailoverAuthRequest(FailoverAuthRequestMessage msg) {
        logger.debug("收到 FAILOVER_AUTH_REQUEST: candidate={}, epoch={}",
                msg.getSenderNodeId(), msg.getCurrentEpoch());
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverAuthRequest(msg);
        }
    }

    /**
     * 处理故障转移授权确认（委托给 GossipProtocol/FailoverManager）。
     *
     * @param msg 授权确认消息
     */
    private void handleFailoverAuthAck(FailoverAuthAckMessage msg) {
        logger.debug("收到 FAILOVER_AUTH_ACK: voter={}, epoch={}",
                msg.getSenderNodeId(), msg.getCurrentEpoch());
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverAuthAck(msg);
        }
    }

    /**
     * 处理故障转移结果（委托给 GossipProtocol/FailoverManager）。
     *
     * @param msg 胜选结果消息
     */
    private void handleFailoverResult(FailoverResultMessage msg) {
        logger.info("收到 FAILOVER_RESULT: winner={}, epoch={}",
                msg.getWinnerNodeId(), msg.getNewConfigEpoch());
        if (gossipProtocol != null) {
            gossipProtocol.handleFailoverResult(msg);
        }
    }

    /**
     * 处理键迁移请求（MIGRATE_KEY）。
     * <p>
     * 目标节点收到请求后调用 GossipProtocol.handleMigrateKey 导入键，
     * 返回 MIGRATE_KEY_ACK 给源节点。
     * </p>
     *
     * @param msg 键迁移请求消息
     * @return 键迁移确认消息
     */
    private GossipMessage handleMigrateKey(MigrateKeyMessage msg) {
        logger.debug("收到 MIGRATE_KEY: key={}, sender={}", msg.getKey(), msg.getSenderNodeId());
        if (gossipProtocol != null) {
            return gossipProtocol.handleMigrateKey(msg);
        }
        ClusterNode myNode = clusterConfig.getMyNode();
        String myNodeId = myNode != null ? myNode.getNodeId() : msg.getSenderNodeId();
        return new MigrateKeyAckMessage(myNodeId, msg.getKey(), false, "GossipProtocol 未注入");
    }

    /**
     * 处理键迁移确认（MIGRATE_KEY_ACK）。
     * <p>
     * 源节点收到目标节点的 ACK 后，完成等待中的 sendAndWait 请求。
     * </p>
     *
     * @param msg 键迁移确认消息
     */
    private void handleMigrateKeyAck(MigrateKeyAckMessage msg) {
        logger.debug("收到 MIGRATE_KEY_ACK: key={}, success={}, sender={}, requestId={}",
                msg.getKey(), msg.isSuccess(), msg.getSenderNodeId(), msg.getRequestId());
        if (busClient != null) {
            // P1-20：按 requestId 严格匹配等待中的 future（不再按 senderNodeId 单槽位）
            busClient.completeResponse(msg.getRequestId(), msg);
        }
    }

    /**
     * 处理手动故障转移启动请求（P1-12，MANUAL_FAILOVER_START）。
     * <p>
     * master 侧：收到候选 slave 的 MFStart 后，暂停客户端写、记录复制偏移量、回传 offset。
     * </p>
     *
     * @param msg 手动故障转移启动消息
     */
    private void handleManualFailoverStart(ManualFailoverStartMessage msg) {
        logger.info("收到 MANUAL_FAILOVER_START: sender={}", msg.getSenderNodeId());
        if (gossipProtocol != null) {
            gossipProtocol.handleManualFailoverStart(msg);
        }
    }

    /**
     * 处理手动故障转移 offset 回传（P1-12，MANUAL_FAILOVER_OFFSET）。
     * <p>
     * slave 侧：收到 master 回传的暂停时偏移量，记录后等待自身 offset 追平再提升。
     * </p>
     *
     * @param msg master 暂停写时的 offset 回传消息
     */
    private void handleManualFailoverOffset(ManualFailoverOffsetMessage msg) {
        logger.info("收到 MANUAL_FAILOVER_OFFSET: sender={}, masterOffset={}",
                msg.getSenderNodeId(), msg.getMasterOffset());
        if (gossipProtocol != null) {
            gossipProtocol.handleManualFailoverOffset(msg);
        }
    }

    /**
     * 发生异常时调用
     *
     * @param ctx   通道上下文
     * @param cause 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("集群总线连接异常，远程地址: {}, 节点ID: {}",
                formatRemoteAddress(ctx), remoteNodeId, cause);

        // 关闭连接，触发客户端重连评估
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
