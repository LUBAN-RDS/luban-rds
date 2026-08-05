package com.janeluo.luban.rds.mesh.bus;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Mesh 总线出站客户端：对每个 peer 建立长连接（参考 {@code ClusterBusClient}，独立实现不复用）。
 * <p>
 * 阶段 1 职责：
 * <ul>
 *   <li><b>过滤自身 nodeId</b>：peers 列表可能含自身，跳过不自连。</li>
 *   <li><b>去重</b>：同一 nodeId 不重复建连（复用活跃 Channel，节点级 synchronized 串行建连）。</li>
 *   <li><b>退避重连</b>：连接断开/失败后指数退避重连（{@link #RECONNECT_DELAY_MS} * 2^attempts，
 *       上限 {@link #MAX_BACKOFF_SHIFT}）。</li>
 *   <li><b>keepalive</b>：开启 {@code SO_KEEPALIVE} 探测半开连接，{@code TCP_NODELAY}。</li>
 *   <li><b>{@link #send(String, MeshFrame)}</b>：按目标 nodeId 查 Channel 写出，未连接时评估重连。</li>
 * </ul>
 * </p>
 * <p>
 * 与 cluster 的差异：阶段 1 不做 MEET 握手、不做 request-response 关联（那是 RPC 层职责），
 * peer 的 busPort 直接用配置值（mesh 节点 busPort 显式配置，不走 servicePort+10000 推算）。
 * </p>
 */
public class MeshBusClient {

    private static final Logger logger = LoggerFactory.getLogger(MeshBusClient.class);

    /** 连接超时（ms） */
    private static final int CONNECT_TIMEOUT_MS = 5000;

    /** 断线后首次重连延迟（ms） */
    private static final long RECONNECT_DELAY_MS = 2000;

    /** 指数退避位移上限：超过此次数后延迟不再翻倍（≈ 2s * 2^5 = 64s） */
    private static final int MAX_BACKOFF_SHIFT = 5;

    private final String selfNodeId;
    private final MeshBusHandler handler;
    private final EventLoopGroup group;

    /** nodeId → 活跃 Channel */
    private final Map<String, Channel> nodeChannels = new ConcurrentHashMap<>();

    /** nodeId → peer 端点（host + busPort），重连依据；仅记录非主动 disconnect 的节点 */
    private final Map<String, PeerEndpoint> nodeEndpoints = new ConcurrentHashMap<>();

    /** nodeId → 重连连续失败次数（指数退避，连接成功时清零） */
    private final Map<String, Long> reconnectAttempts = new ConcurrentHashMap<>();

    /** nodeId → 已调度的重连触发时刻（ms），窗口内去重防重连风暴 */
    private final Map<String, Long> reconnectScheduled = new ConcurrentHashMap<>();

    /** nodeId → 建连互斥锁（常驻，避免集群规模小下的 ABA 竞态） */
    private final Map<String, Object> connectLocks = new ConcurrentHashMap<>();

    private volatile boolean closed;

    public MeshBusClient(String selfNodeId, MeshBusHandler handler) {
        this.selfNodeId = selfNodeId;
        this.handler = handler;
        this.group = new NioEventLoopGroup();
        this.closed = false;
    }

    /** Peer 端点信息。 */
    public static final class PeerEndpoint {
        private final String host;
        private final int busPort;

        public PeerEndpoint(String host, int busPort) {
            this.host = host;
            this.busPort = busPort;
        }

        public String getHost() {
            return host;
        }

        public int getBusPort() {
            return busPort;
        }
    }

    /**
     * 批量连接所有 peer（自动过滤自身 nodeId、去重）。
     *
     * @param peers peer 列表（nodeId → endpoint）
     */
    public void start(Map<String, PeerEndpoint> peers) {
        if (closed) {
            throw new IllegalStateException("MeshBusClient 已关闭");
        }
        if (peers == null || peers.isEmpty()) {
            logger.info("MeshBusClient 启动：无 peer，nodeId={}", selfNodeId);
            return;
        }
        for (Map.Entry<String, PeerEndpoint> e : peers.entrySet()) {
            String nodeId = e.getKey();
            if (isSelf(nodeId)) {
                logger.debug("跳过自身 peer，不自连: nodeId={}", nodeId);
                continue;
            }
            connect(nodeId, e.getValue().getHost(), e.getValue().getBusPort());
        }
    }

    /**
     * 连接指定 peer（去重 + 退避注册）。
     *
     * @param nodeId  目标节点 nodeId（非自身）
     * @param host    目标主机
     * @param busPort 目标总线端口
     * @return ChannelFuture
     */
    public ChannelFuture connect(String nodeId, String host, int busPort) {
        if (closed) {
            throw new IllegalStateException("MeshBusClient 已关闭");
        }
        if (isSelf(nodeId)) {
            logger.debug("拒绝自连: nodeId={}", nodeId);
            return null;
        }

        Object lock = connectLocks.computeIfAbsent(nodeId, k -> new Object());
        synchronized (lock) {
            Channel existing = nodeChannels.get(nodeId);
            if (existing != null && existing.isActive()) {
                logger.debug("节点 {} 已连接，复用现有连接", nodeId);
                return existing.newSucceededFuture();
            }

            nodeEndpoints.put(nodeId, new PeerEndpoint(host, busPort));

            logger.info("正在连接 mesh 节点 {}: {}:{}", nodeId, host, busPort);

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(
                                    new MeshBusCodec.Encoder(),
                                    new MeshBusCodec.Decoder());
                            // 共享 handler（@Sharable）
                            ch.pipeline().addLast(handler);
                        }
                    });

            ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, busPort));
            future.addListener((ChannelFuture f) -> {
                if (f.isSuccess()) {
                    Channel ch = f.channel();
                    nodeChannels.put(nodeId, ch);
                    reconnectAttempts.remove(nodeId);
                    logger.info("成功连接 mesh 节点 {}: {}:{}", nodeId, host, busPort);

                    ch.closeFuture().addListener((closeFuture) -> {
                        boolean removed = nodeChannels.remove(nodeId, ch);
                        logger.info("mesh 节点 {} 连接断开", nodeId);
                        if (removed && !closed) {
                            PeerEndpoint ep = nodeEndpoints.get(nodeId);
                            if (ep != null) {
                                scheduleReconnect(nodeId, ep);
                            }
                        }
                    });
                } else {
                    logger.error("连接 mesh 节点 {} 失败: {}:{}", nodeId, host, busPort, f.cause());
                    // 连接失败触发一次重连评估（指数退避）
                    PeerEndpoint ep = nodeEndpoints.get(nodeId);
                    if (ep != null) {
                        scheduleReconnect(nodeId, ep);
                    }
                }
            });
            return future;
        }
    }

    /**
     * 在 EventLoop 上调度一次延迟重连（去重 + 指数退避）。
     */
    private void scheduleReconnect(String nodeId, PeerEndpoint endpoint) {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        Long lastScheduled = reconnectScheduled.get(nodeId);
        long window = RECONNECT_DELAY_MS * (1L << MAX_BACKOFF_SHIFT);
        if (lastScheduled != null && (now - lastScheduled) < window) {
            return;
        }
        reconnectScheduled.put(nodeId, now);

        long attempts = reconnectAttempts.compute(nodeId, (k, v) -> (v == null) ? 1L : v + 1L);
        long shift = Math.min(attempts - 1L, (long) MAX_BACKOFF_SHIFT);
        long delay = RECONNECT_DELAY_MS * (1L << shift);

        group.schedule(() -> {
            reconnectScheduled.remove(nodeId);
            if (closed || nodeEndpoints.get(nodeId) == null) {
                return;
            }
            Channel existing = nodeChannels.get(nodeId);
            if (existing != null && existing.isActive()) {
                return;
            }
            logger.info("尝试重连 mesh 节点 {}: {}:{}，第 {} 次退避 {}ms",
                    nodeId, endpoint.host, endpoint.busPort, attempts, delay);
            try {
                connect(nodeId, endpoint.host, endpoint.busPort);
            } catch (Exception e) {
                logger.warn("重连 mesh 节点 {} 失败", nodeId, e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 向目标节点发送消息。
     *
     * @param targetNodeId 目标 nodeId
     * @param frame        待发帧（senderNodeId 由调用方填本节点 nodeId）
     */
    public void send(String targetNodeId, MeshFrame frame) {
        if (closed) {
            throw new IllegalStateException("MeshBusClient 已关闭");
        }
        Channel channel = nodeChannels.get(targetNodeId);
        if (channel == null || !channel.isActive()) {
            logger.warn("目标 mesh 节点 {} 未连接，无法发送", targetNodeId);
            PeerEndpoint ep = nodeEndpoints.get(targetNodeId);
            if (ep != null) {
                scheduleReconnect(targetNodeId, ep);
            }
            return;
        }
        channel.writeAndFlush(frame).addListener((future) -> {
            if (future.isSuccess()) {
                logger.trace("MeshFrame 已发往节点 {}: {}", targetNodeId, frame);
            } else {
                logger.error("发送 MeshFrame 到节点 {} 失败", targetNodeId, future.cause());
                PeerEndpoint ep = nodeEndpoints.get(targetNodeId);
                if (ep != null) {
                    scheduleReconnect(targetNodeId, ep);
                }
            }
        });
    }

    /**
     * 主动断开指定节点（清除端点，避免触发自动重连）。
     */
    public void disconnect(String nodeId) {
        nodeEndpoints.remove(nodeId);
        reconnectScheduled.remove(nodeId);
        reconnectAttempts.remove(nodeId);
        Channel ch = nodeChannels.remove(nodeId);
        if (ch != null && ch.isActive()) {
            ch.close();
            logger.info("已断开与 mesh 节点 {} 的连接", nodeId);
        }
    }

    public boolean isConnected(String nodeId) {
        Channel ch = nodeChannels.get(nodeId);
        return ch != null && ch.isActive();
    }

    public int getConnectedCount() {
        int count = 0;
        for (Channel ch : nodeChannels.values()) {
            if (ch.isActive()) {
                count++;
            }
        }
        return count;
    }

    /** 返回当前已连接的 nodeId 列表（用于广播）。 */
    public Collection<String> getConnectedNodeIds() {
        return nodeChannels.keySet();
    }

    public InetSocketAddress getRemoteAddress(String nodeId) {
        Channel ch = nodeChannels.get(nodeId);
        if (ch != null && ch.remoteAddress() instanceof InetSocketAddress) {
            return (InetSocketAddress) ch.remoteAddress();
        }
        return null;
    }

    /**
     * 关闭客户端：关闭所有连接与 EventLoopGroup。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Channel ch : nodeChannels.values()) {
            if (ch.isActive()) {
                ch.close();
            }
        }
        nodeChannels.clear();
        nodeEndpoints.clear();
        reconnectScheduled.clear();
        reconnectAttempts.clear();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
        logger.info("MeshBusClient 已关闭");
    }

    private boolean isSelf(String nodeId) {
        return nodeId != null && nodeId.equals(selfNodeId);
    }

    public String getSelfNodeId() {
        return selfNodeId;
    }
}
