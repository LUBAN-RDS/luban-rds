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
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 集群总线客户端
 * <p>
 * 用于向其他节点发送 Gossip 消息
 * </p>
 */
public class ClusterBusClient {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBusClient.class);

    /**
     * 连接超时时间（毫秒）
     */
    private static final int CONNECT_TIMEOUT_MS = 5000;

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
     * Gossip 协议处理器
     */
    private final GossipProtocol gossipProtocol;

    /**
     * 客户端是否已关闭
     */
    private volatile boolean closed;

    /**
     * 构造方法
     *
     * @param clusterConfig   集群配置
     * @param gossipProtocol  Gossip 协议处理器
     */
    public ClusterBusClient(ClusterConfig clusterConfig, GossipProtocol gossipProtocol) {
        this.group = new NioEventLoopGroup();
        this.nodeChannels = new ConcurrentHashMap<>();
        this.clusterConfig = clusterConfig;
        this.gossipProtocol = gossipProtocol;
        this.closed = false;
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

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 添加对象编解码器
                        ch.pipeline().addLast(
                                new ObjectDecoder(Integer.MAX_VALUE, 
                                        ClassResolvers.cacheDisabled(null)));
                        ch.pipeline().addLast(new ObjectEncoder());
                        
                        // 添加业务处理器
                        ch.pipeline().addLast(new ClusterBusHandler(clusterConfig, gossipProtocol));
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

                // 添加关闭监听器，连接断开时从映射中移除
                channel.closeFuture().addListener((closeFuture) -> {
                    nodeChannels.remove(nodeId, channel);
                    logger.info("节点 {} 连接已断开", nodeId);
                });
            } else {
                logger.error("连接节点 {} 失败，地址: {}:{}", nodeId, host, busPort, 
                        channelFuture.cause());
            }
        });

        return future;
    }

    /**
     * 断开与指定节点的连接
     *
     * @param nodeId 节点ID
     */
    public void disconnect(String nodeId) {
        Channel channel = nodeChannels.remove(nodeId);
        if (channel != null && channel.isActive()) {
            channel.close();
            logger.info("已断开与节点 {} 的连接", nodeId);
        }
    }

    /**
     * 发送消息到指定节点
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
            return;
        }

        channel.writeAndFlush(message).addListener((future) -> {
            if (future.isSuccess()) {
                logger.debug("消息已发送到节点 {}: {}", nodeId, message.getType());
            } else {
                logger.error("发送消息到节点 {} 失败", nodeId, future.cause());
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

        try {
            // 发送消息
            ChannelFuture sendFuture = channel.writeAndFlush(message);
            sendFuture.await(timeout, TimeUnit.MILLISECONDS);

            if (!sendFuture.isSuccess()) {
                logger.error("发送消息到节点 {} 失败", nodeId, sendFuture.cause());
                return null;
            }

            // 注意：这里简化了实现，实际应该等待响应消息
            // 完整实现需要使用 Promise/Future 机制等待响应
            logger.debug("消息已发送到节点 {}: {}", nodeId, message.getType());
            return null;
        } catch (InterruptedException e) {
            logger.error("等待消息响应时被中断", e);
            Thread.currentThread().interrupt();
            return null;
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

        // 关闭线程组
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);

        logger.info("集群总线客户端已关闭");
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
