package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.serialization.ClassResolvers;
import io.netty.handler.codec.serialization.ObjectDecoder;
import io.netty.handler.codec.serialization.ObjectEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * 集群总线服务器
 * <p>
 * 监听端口 = 服务端口 + 10000
 * 用于节点间 Gossip 协议通信
 * </p>
 */
public class ClusterBusServer {

    private static final Logger logger = LoggerFactory.getLogger(ClusterBusServer.class);

    /**
     * 集群总线端口偏移量
     */
    public static final int BUS_PORT_OFFSET = 10000;

    /**
     * 集群总线端口
     */
    private final int port;

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * Gossip 协议处理器
     */
    private final GossipProtocol gossipProtocol;

    /**
     * Boss 线程组（接收连接）
     */
    private EventLoopGroup bossGroup;

    /**
     * Worker 线程组（处理 I/O）
     */
    private EventLoopGroup workerGroup;

    /**
     * 服务器 Channel
     */
    private Channel channel;

    /**
     * 服务器是否运行中
     */
    private volatile boolean running;

    /**
     * 构造方法
     *
     * @param servicePort   服务端口
     * @param clusterConfig 集群配置
     * @param gossipProtocol Gossip 协议处理器
     */
    public ClusterBusServer(int servicePort, ClusterConfig clusterConfig, GossipProtocol gossipProtocol) {
        this.port = servicePort + BUS_PORT_OFFSET;
        this.clusterConfig = clusterConfig;
        this.gossipProtocol = gossipProtocol;
        this.running = false;
    }

    /**
     * 启动集群总线服务器
     */
    public void start() {
        if (running) {
            logger.warn("集群总线服务器已在运行中，端口: {}", port);
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
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

            // 绑定端口并启动服务器
            ChannelFuture future = bootstrap.bind(port).sync();
            channel = future.channel();
            running = true;

            logger.info("集群总线服务器启动成功，监听端口: {}", port);

        } catch (InterruptedException e) {
            logger.error("集群总线服务器启动失败", e);
            shutdown();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止集群总线服务器
     */
    public void stop() {
        if (!running) {
            logger.warn("集群总线服务器未运行");
            return;
        }

        shutdown();
        logger.info("集群总线服务器已停止");
    }

    /**
     * 关闭资源
     */
    private void shutdown() {
        running = false;

        // 关闭服务器 Channel
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                logger.error("关闭服务器 Channel 失败", e);
                Thread.currentThread().interrupt();
            }
        }

        // 关闭线程组
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    /**
     * 检查服务器是否运行中
     *
     * @return 是否运行中
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取集群总线端口
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取服务器绑定的地址
     *
     * @return 绑定地址，如果未启动则返回 null
     */
    public InetSocketAddress getLocalAddress() {
        if (channel != null && channel.localAddress() != null) {
            return (InetSocketAddress) channel.localAddress();
        }
        return null;
    }

    /**
     * 等待服务器关闭
     */
    public void awaitTermination() {
        if (channel != null) {
            try {
                channel.closeFuture().sync();
            } catch (InterruptedException e) {
                logger.error("等待服务器关闭时被中断", e);
                Thread.currentThread().interrupt();
            }
        }
    }
}
