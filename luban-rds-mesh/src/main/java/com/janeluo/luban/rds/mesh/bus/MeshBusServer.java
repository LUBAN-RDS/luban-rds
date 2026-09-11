package com.janeluo.luban.rds.mesh.bus;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * Mesh 总线服务端：Netty {@link ServerBootstrap}，bind {@code busPort}。
 * <p>
 * 与 {@code ClusterBusServer} 同构但独立（不共享 cluster 代码）。pipeline 装
 * {@link MeshBusCodec.Encoder}/{@link MeshBusCodec.Decoder} + 共享的 {@link MeshBusHandler}。
 * </p>
 * <p>
 * 设计要点：
 * <ul>
 *   <li>boss/worker 双 EventLoopGroup，{@code SO_BACKLOG=128}、子连接 {@code SO_KEEPALIVE}
 *       + {@code TCP_NODELAY}（与 cluster server 选项一致）。</li>
 *   <li>{@link MeshBusHandler} 由调用方构造并注入（{@code @Sharable}，可被 server/client 共享）。</li>
 *   <li>{@code start}/{@code stop} 由 {@code synchronized} 守护，绑定失败时释放已建 EventLoopGroup。</li>
 * </ul>
 * </p>
 */
public class MeshBusServer {

    private static final Logger logger = LoggerFactory.getLogger(MeshBusServer.class);

    /** 本节点 nodeId（阶段 1 暂留，后续握手/过滤用） */
    private final String selfNodeId;

    /** 总线监听端口 */
    private final int busPort;

    /** 入站分发处理器（@Sharable，server/client 共享） */
    private final MeshBusHandler handler;

    /**
     * P1-12a（2026-09-11 审计）：总线握手认证 token；null/空 = 认证关闭（完全兼容旧行为，
     * 滚动升级顺序：先全网升级再统一配置 token）。须在 {@link #start()} 前设置。
     */
    private volatile String authToken;

    /** 认证失败/未认证帧拒绝计数（可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong rejectedAuthCount =
            new java.util.concurrent.atomic.AtomicLong();

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean running;

    /**
     * @param selfNodeId 本节点 nodeId
     * @param busPort    总线监听端口
     * @param handler    共享入站处理器
     */
    public MeshBusServer(String selfNodeId, int busPort, MeshBusHandler handler) {
        this.selfNodeId = selfNodeId;
        this.busPort = busPort;
        this.handler = handler;
        this.running = false;
    }

    /** 设置握手认证 token（空 = 关闭认证）；须在 start() 前调用。 */
    public void setAuthToken(String authToken) {
        this.authToken = (authToken == null || authToken.isEmpty()) ? null : authToken;
    }

    /** 认证失败拒绝计数（认证关闭时恒 0）。 */
    public long getRejectedAuthCount() {
        return rejectedAuthCount.get();
    }

    /**
     * 启动服务端：bind busPort。
     */
    public synchronized void start() {
        if (running) {
            logger.warn("MeshBusServer 已在运行，端口: {}", busPort);
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
                            ch.pipeline().addLast(
                                    new MeshBusCodec.Encoder(),
                                    new MeshBusCodec.Decoder());
                            // P1-12a：token 已配置时插入 per-channel 认证门（在共享 handler 之前）
                            if (authToken != null) {
                                ch.pipeline().addLast(new AuthGateHandler());
                            }
                            // 共享 handler（@Sharable）
                            ch.pipeline().addLast(handler);
                        }
                    });

            ChannelFuture future = bootstrap.bind(busPort).sync();
            channel = future.channel();
            running = true;
            logger.info("MeshBusServer 启动成功，nodeId={}, 监听端口: {}", selfNodeId, busPort);
        } catch (InterruptedException e) {
            logger.error("MeshBusServer 启动被中断", e);
            shutdown();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("MeshBusServer 启动失败，端口: {}", busPort, e);
            shutdown();
            throw new RuntimeException("MeshBusServer 绑定端口失败: " + busPort, e);
        }
    }

    /**
     * 停止服务端：优雅关闭 EventLoopGroup。
     */
    public synchronized void stop() {
        if (!running) {
            logger.warn("MeshBusServer 未运行");
            return;
        }
        shutdown();
        logger.info("MeshBusServer 已停止");
    }

    private void shutdown() {
        running = false;
        if (channel != null) {
            try {
                channel.close().sync();
            } catch (InterruptedException e) {
                logger.error("关闭 MeshBusServer Channel 失败", e);
                Thread.currentThread().interrupt();
            }
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        channel = null;
        bossGroup = null;
        workerGroup = null;
    }

    public boolean isRunning() {
        return running;
    }

    public int getBusPort() {
        return busPort;
    }

    public String getSelfNodeId() {
        return selfNodeId;
    }

    /**
     * P1-12a：per-channel 认证门（非 Sharable，initChannel 时按连接创建）。
     * <p>BUS_HELLO 帧校验 token（常数时间比较）通过后放行该连接的后续帧；
     * 未认证连接收到业务帧直接关连接。HELLO 本身不转发上层。</p>
     */
    private final class AuthGateHandler extends io.netty.channel.ChannelInboundHandlerAdapter {

        private boolean authenticated;

        @Override
        public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
            if (authenticated || !(msg instanceof MeshFrame)) {
                super.channelRead(ctx, msg);
                return;
            }
            MeshFrame frame = (MeshFrame) msg;
            if (frame.getType() == MessageType.BUS_HELLO.getCode()) {
                String token = frame.getBody() == null
                        ? "" : new String(frame.getBody(), java.nio.charset.StandardCharsets.UTF_8);
                String sender = frame.getSenderNodeId();
                if (sender != null && !sender.isEmpty() && tokenEquals(token, authToken)) {
                    authenticated = true;
                    logger.info("总线握手认证通过: nodeId={}, remote={}",
                            sender, ctx.channel().remoteAddress());
                } else {
                    rejectedAuthCount.incrementAndGet();
                    logger.warn("总线握手认证失败（token/nodeId 无效），关闭连接: remote={}",
                            ctx.channel().remoteAddress());
                    ctx.close();
                }
                return;
            }
            rejectedAuthCount.incrementAndGet();
            logger.warn("未认证连接收到业务帧（type=0x{}），关闭连接: remote={}",
                    Integer.toHexString(frame.getType() & 0xFF), ctx.channel().remoteAddress());
            ctx.close();
        }

        /** 常数时间 token 比较，防时序侧信道。 */
        private boolean tokenEquals(String a, String b) {
            return java.security.MessageDigest.isEqual(
                    a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
