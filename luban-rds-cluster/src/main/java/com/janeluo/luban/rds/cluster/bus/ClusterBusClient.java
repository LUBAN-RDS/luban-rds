package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 集群总线客户端
 * <p>
 * 用于向其他节点发送 Gossip 消息
 * </p>
 */
public class ClusterBusClient {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBusClient.class);

    static final AttributeKey<AtomicReference<String>> CHANNEL_NODE_ID_KEY =
            AttributeKey.valueOf("cluster.nodeId");

    /**
     * 连接超时时间（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /**
     * 断线后首次重连的延迟（毫秒）。重连失败的后续重试由下一次断开事件触发。
     */
    private static final long RECONNECT_DELAY_MS = 2000;

    /**
     * EventLoop 线程组
     */
    private final EventLoopGroup group;

    /**
     * 节点连接映射（nodeId -> Channel）
     */
    private final Map<String, Channel> nodeChannels;

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * Gossip 协议处理器（volatile：构造后通过 setGossipProtocol 注入，保证多线程可见性）
     */
    private volatile GossipProtocol gossipProtocol;

    /**
     * 客户端是否已关闭
     */
    private volatile boolean closed;

    /**
     * 节点连接端点映射（nodeId -> host/port），用于连接断开时触发自动重连。
     * <p>
     * 仅当节点仍在此映射中（即未被 disconnect 主动断开）时，连接断开才会触发重连。
     * </p>
     */
    private final Map<String, NodeEndpoint> nodeEndpoints;

    /**
     * 待响应的请求映射（目标节点ID -> 响应 Future）
     * <p>
     * 用于 sendAndWait 请求-响应匹配：发送 MIGRATE_KEY 后等待目标节点回 MIGRATE_KEY_ACK。
     * </p>
     */
    private final Map<String, CompletableFuture<GossipMessage>> pendingResponses;

    /**
     * 构造方法
     *
     * @param clusterConfig   集群配置
     * @param gossipProtocol  Gossip 协议处理器
     */
    public ClusterBusClient(ClusterConfig clusterConfig, GossipProtocol gossipProtocol) {
        this.group = new NioEventLoopGroup();
        this.nodeChannels = new ConcurrentHashMap<>();
        this.nodeEndpoints = new ConcurrentHashMap<>();
        this.clusterConfig = clusterConfig;
        this.gossipProtocol = gossipProtocol;
        this.pendingResponses = new ConcurrentHashMap<>();
        this.closed = false;
    }

    /**
     * 节点连接端点信息（host + servicePort），重连时使用。
     */
    private static final class NodeEndpoint {
        final String host;
        final int port;

        NodeEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * 连接到指定节点
     *
     * @param nodeId 目标节点ID
     * @param host   目标主机
     * @param port   目标端口（服务端口，会自动计算总线端口）
     * @return 连接 Future
     */
    public ChannelFuture connect(String nodeId, String host, int port) {
        if (closed) {
            throw new IllegalStateException("客户端已关闭");
        }

        // 检查是否已连接
        Channel existingChannel = nodeChannels.get(nodeId);
        if (existingChannel != null && existingChannel.isActive()) {
            logger.debug("节点 {} 已连接，复用现有连接", nodeId);
            return existingChannel.newSucceededFuture();
        }

        // 计算总线端口
        int busPort = port + ClusterBusServer.BUS_PORT_OFFSET;

        logger.info("正在连接节点 {}，地址: {}:{}", nodeId, host, busPort);

        // 记录端点信息，供断线重连使用（在尝试连接前记录，避免监听器竞态）
        nodeEndpoints.put(nodeId, new NodeEndpoint(host, port));

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 二进制编解码器（基于 GossipMessage.encode/parseMessage）
                        ch.pipeline().addLast(
                                new ClusterBusCodec.Encoder(),
                                new ClusterBusCodec.Decoder());

                        // 添加业务处理器
                        // 传入 ClusterBusClient 引用和 nodeId，使处理器能在 MEET 握手完成后
                        // 将临时节点ID替换为真实节点ID，并更新通道映射
                        ch.pipeline().addLast(new ClusterBusHandler(clusterConfig, gossipProtocol,
                                ClusterBusClient.this, nodeId));
                    }
                });

        // 异步连接
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, busPort));

        // 添加连接监听器
        future.addListener((ChannelFuture channelFuture) -> {
            if (channelFuture.isSuccess()) {
                Channel channel = channelFuture.channel();
                nodeChannels.put(nodeId, channel);
                logger.info("成功连接节点 {}，地址: {}:{}", nodeId, host, busPort);

                AtomicReference<String> currentNodeId = new AtomicReference<>(nodeId);
                channel.attr(CHANNEL_NODE_ID_KEY).set(currentNodeId);

                // 添加关闭监听器，连接断开时从映射中移除并评估是否需要重连
                channel.closeFuture().addListener((closeFuture) -> {
                    String activeNodeId = currentNodeId.get();
                    boolean removed = nodeChannels.remove(activeNodeId, channel);
                    logger.info("节点 {} 连接已断开", activeNodeId);

                    // 节点端点仍在映射中说明非主动断开（disconnect 会先移除端点），触发延迟重连
                    if (removed) {
                        NodeEndpoint endpoint = nodeEndpoints.get(activeNodeId);
                        if (endpoint != null) {
                            scheduleReconnect(activeNodeId, endpoint);
                        }
                    }
                });
            } else {
                logger.error("连接节点 {} 失败，地址: {}:{}", nodeId, host, busPort,
                        channelFuture.cause());
            }
        });

        return future;
    }

    /**
     * 在 EventLoop 上调度一次延迟重连。
     * <p>
     * 仅当节点端点未被 disconnect 移除、且当前没有活跃连接时才会真正发起重连，
     * 避免重复重连风暴。重连失败会在下一次断开事件中再次触发。
     * </p>
     *
     * @param nodeId   节点ID
     * @param endpoint 节点端点
     */
    private void scheduleReconnect(String nodeId, NodeEndpoint endpoint) {
        if (closed) {
            return;
        }
        group.schedule(() -> {
            if (closed || nodeEndpoints.get(nodeId) == null) {
                return;
            }
            Channel existing = nodeChannels.get(nodeId);
            if (existing != null && existing.isActive()) {
                return;
            }
            logger.info("尝试重连节点 {}，地址: {}:{}", nodeId, endpoint.host,
                    endpoint.port + ClusterBusServer.BUS_PORT_OFFSET);
            try {
                connect(nodeId, endpoint.host, endpoint.port);
            } catch (Exception e) {
                logger.warn("重连节点 {} 失败", nodeId, e);
            }
        }, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 断开与指定节点的连接。
     * <p>
     * 先移除端点映射，使断线监听器不会触发自动重连，再关闭通道。
     * </p>
     *
     * @param nodeId 节点ID
     */
    public void disconnect(String nodeId) {
        nodeEndpoints.remove(nodeId);
        Channel channel = nodeChannels.remove(nodeId);
        if (channel != null && channel.isActive()) {
            channel.close();
            logger.info("已断开与节点 {} 的连接", nodeId);
        }
    }

    /**
     * 重命名通道映射的节点ID
     * <p>
     * 在 MEET 握手完成时，将临时节点ID替换为真实节点ID，
     * 使后续 PING/PONG 等消息能通过真实节点ID找到对应通道。
     * </p>
     * <p>
     * 使用 ConcurrentHashMap.compute 保证「移除旧ID + 写入新ID」的原子性，
     * 避免并发下通道丢失或重复。同时同步迁移端点映射，保证重连信息正确。
     * </p>
     *
     * @param oldNodeId 旧的临时节点ID
     * @param newNodeId 真实节点ID
     */
    public void renameChannel(String oldNodeId, String newNodeId) {
        // 原子迁移通道映射：仅当旧ID仍持有通道时才写入新ID
        nodeChannels.compute(oldNodeId, (k, existingChannel) -> {
            if (existingChannel == null) {
                return null;
            }
            nodeChannels.putIfAbsent(newNodeId, existingChannel);
            return null; // 返回 null 表示从 oldNodeId 移除
        });

        // 同步迁移端点映射，保证重连信息与新 nodeId 关联
        NodeEndpoint endpoint = nodeEndpoints.remove(oldNodeId);
        if (endpoint != null) {
            nodeEndpoints.putIfAbsent(newNodeId, endpoint);
        }

        // 更新通道上持有的 nodeId 引用
        Channel channel = nodeChannels.get(newNodeId);
        if (channel != null) {
            AtomicReference<String> nodeIdRef = channel.attr(CHANNEL_NODE_ID_KEY).get();
            if (nodeIdRef != null) {
                nodeIdRef.set(newNodeId);
            }
        }
        logger.info("通道映射已更新: {} -> {}", oldNodeId, newNodeId);
    }

    /**
     * 发送消息到指定节点
     * <p>
     * 若节点未连接，尝试基于已知端点触发重连；无端点信息则记录告警并放弃。
     * 写入失败也会评估重连。
     * </p>
     *
     * @param nodeId  目标节点ID
     * @param message 消息对象
     */
    public void send(String nodeId, GossipMessage message) {
        if (closed) {
            throw new IllegalStateException("客户端已关闭");
        }

        Channel channel = nodeChannels.get(nodeId);
        if (channel == null || !channel.isActive()) {
            logger.warn("节点 {} 未连接或连接已断开，无法发送消息", nodeId);
            // 评估重连：仅当端点信息存在且非主动断开
            NodeEndpoint endpoint = nodeEndpoints.get(nodeId);
            if (endpoint != null) {
                scheduleReconnect(nodeId, endpoint);
            }
            return;
        }

        channel.writeAndFlush(message).addListener((future) -> {
            if (future.isSuccess()) {
                logger.debug("消息已发送到节点 {}: {}", nodeId, message.getType());
            } else {
                logger.error("发送消息到节点 {} 失败", nodeId, future.cause());
                NodeEndpoint endpoint = nodeEndpoints.get(nodeId);
                if (endpoint != null) {
                    scheduleReconnect(nodeId, endpoint);
                }
            }
        });
    }

    /**
     * 发送消息并等待响应
     *
     * @param nodeId  目标节点ID
     * @param message 消息对象
     * @param timeout 超时时间（毫秒）
     * @return 响应消息，超时或失败返回 null
     */
    public GossipMessage sendAndWait(String nodeId, GossipMessage message, long timeout) {
        if (closed) {
            throw new IllegalStateException("客户端已关闭");
        }

        Channel channel = nodeChannels.get(nodeId);
        if (channel == null || !channel.isActive()) {
            logger.warn("节点 {} 未连接或连接已断开，无法发送消息", nodeId);
            return null;
        }

        // 注册待响应 Future，由 ClusterBusHandler 收到 MIGRATE_KEY_ACK 时完成
        CompletableFuture<GossipMessage> future = new CompletableFuture<>();
        pendingResponses.put(nodeId, future);

        try {
            // 发送消息
            ChannelFuture sendFuture = channel.writeAndFlush(message);
            sendFuture.await(timeout, TimeUnit.MILLISECONDS);

            if (!sendFuture.isSuccess()) {
                logger.error("发送消息到节点 {} 失败", nodeId, sendFuture.cause());
                pendingResponses.remove(nodeId);
                return null;
            }

            // 等待响应消息（由 completeResponse 完成）
            try {
                return future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.warn("等待节点 {} 的响应超时或失败", nodeId, e);
                return null;
            }
        } catch (InterruptedException e) {
            logger.error("等待消息响应时被中断", e);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pendingResponses.remove(nodeId);
        }
    }

    /**
     * 完成待响应的请求（由 ClusterBusHandler 在收到 MIGRATE_KEY_ACK 等响应消息时调用）
     *
     * @param senderNodeId 响应发送者节点ID
     * @param response     响应消息
     */
    public void completeResponse(String senderNodeId, GossipMessage response) {
        CompletableFuture<GossipMessage> future = pendingResponses.get(senderNodeId);
        if (future != null) {
            future.complete(response);
            logger.debug("完成节点 {} 的待响应请求: {}", senderNodeId, response.getType());
        }
    }

    /**
     * 广播消息到所有已连接节点
     *
     * @param message 消息对象
     */
    public void broadcast(GossipMessage message) {
        if (closed) {
            throw new IllegalStateException("客户端已关闭");
        }

        for (Map.Entry<String, Channel> entry : nodeChannels.entrySet()) {
            String nodeId = entry.getKey();
            Channel channel = entry.getValue();

            if (channel.isActive()) {
                channel.writeAndFlush(message).addListener((future) -> {
                    if (future.isSuccess()) {
                        logger.debug("广播消息已发送到节点 {}: {}", nodeId, message.getType());
                    } else {
                        logger.error("广播消息到节点 {} 失败", nodeId, future.cause());
                    }
                });
            }
        }
    }

    /**
     * 检查与指定节点的连接状态
     *
     * @param nodeId 节点ID
     * @return 是否已连接
     */
    public boolean isConnected(String nodeId) {
        Channel channel = nodeChannels.get(nodeId);
        return channel != null && channel.isActive();
    }

    /**
     * 获取已连接的节点数量
     *
     * @return 已连接节点数量
     */
    public int getConnectedCount() {
        int count = 0;
        for (Channel channel : nodeChannels.values()) {
            if (channel.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 关闭客户端，释放所有资源
     */
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        // 关闭所有连接
        for (Channel channel : nodeChannels.values()) {
            if (channel.isActive()) {
                channel.close();
            }
        }
        nodeChannels.clear();
        // 清除端点映射，避免已关闭客户端上残留重连信息
        nodeEndpoints.clear();
        // 清除待响应请求并通知等待方，避免 sendAndWait 永久阻塞
        for (Map.Entry<String, CompletableFuture<GossipMessage>> entry : pendingResponses.entrySet()) {
            entry.getValue().cancel(false);
        }
        pendingResponses.clear();

        // 关闭线程组
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);

        logger.info("集群总线客户端已关闭");
    }

    /**
     * 设置 Gossip 协议处理器
     * <p>
     * 用于解决构造函数顺序依赖问题：ClusterBusClient 在 GossipProtocol 之前创建，
     * 需要在 GossipProtocol 创建后通过此方法注入引用，使 ClusterBusHandler 能正确处理
     * PING/PONG/MEET 等握手消息。
     * </p>
     *
     * @param gossipProtocol Gossip 协议处理器
     */
    public void setGossipProtocol(GossipProtocol gossipProtocol) {
        this.gossipProtocol = gossipProtocol;
    }

    /**
     * 获取节点的连接地址
     *
     * @param nodeId 节点ID
     * @return 连接地址，如果未连接则返回 null
     */
    public InetSocketAddress getRemoteAddress(String nodeId) {
        Channel channel = nodeChannels.get(nodeId);
        if (channel != null && channel.remoteAddress() != null) {
            return (InetSocketAddress) channel.remoteAddress();
        }
        return null;
    }
}
