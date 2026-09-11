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
import java.nio.charset.StandardCharsets;
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

    /** nodeId → 连接断开时刻（ms）；连接成功时清除，用于 isFailed() 阈值判定 */
    private final Map<String, Long> disconnectSince = new ConcurrentHashMap<>();

    /** 发送侧日志节流窗口（ms）：mis-config/网络分区下 WARN/ERROR 按心跳节奏刷屏（9/9 事故 4450+2496 条） */
    private static final long SEND_LOG_INTERVAL_MS = 5_000;

    /** 日志节流 key → 上次放行时刻（ms） */
    private final Map<String, Long> sendLogSlots = new ConcurrentHashMap<>();

    /** nodeId → 建连互斥锁（常驻，避免集群规模小下的 ABA 竞态） */
    private final Map<String, Object> connectLocks = new ConcurrentHashMap<>();

    /**
     * Q6（2026-09-11 审计）：nodeId → 在途建连 future——单飞去重。
     * 建连进行中（listener 未回调）的并发 connect 复用同一次连接尝试，
     * 防止"后到者覆盖先到者且旧 channel 永不关闭"的连接泄漏。
     */
    private final Map<String, ChannelFuture> pendingConnects = new ConcurrentHashMap<>();

    /**
     * P1-12a（2026-09-11 审计）：出站握手 token；非空时建连成功即发 BUS_HELLO。
     * 由 MeshBootstrap 装配（与 busServer 同一 token）；null = 不发送（兼容未启用认证）。
     */
    private volatile String authToken;

    private volatile boolean closed;

    /** 设置出站握手 token（空 = 关闭）；与 busServer 配置同值。 */
    public void setAuthToken(String authToken) {
        this.authToken = (authToken == null || authToken.isEmpty()) ? null : authToken;
    }

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
            // Q6：建连进行中——复用在途 future，不重复发起
            ChannelFuture pending = pendingConnects.get(nodeId);
            if (pending != null && !pending.isDone()) {
                logger.debug("节点 {} 建连进行中，复用在途连接尝试", nodeId);
                return pending;
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
            pendingConnects.put(nodeId, future);
            future.addListener((ChannelFuture f) -> {
                pendingConnects.remove(nodeId, f);
                if (f.isSuccess()) {
                    Channel ch = f.channel();
                    // Q6：覆盖旧 channel 前先关闭，防泄漏
                    Channel old = nodeChannels.put(nodeId, ch);
                    if (old != null && old != ch && old.isActive()) {
                        logger.info("关闭被替换的旧连接: nodeId={}", nodeId);
                        old.close();
                    }
                    // P1-12a：token 已配置时先发握手帧（对端认证失败会关连接触发重连路径）
                    String token = this.authToken;
                    if (token != null) {
                        MeshFrame hello = new MeshFrame(selfNodeId,
                                MessageType.BUS_HELLO.getCode(), token.getBytes(StandardCharsets.UTF_8));
                        ch.writeAndFlush(hello).addListener(w -> {
                            if (!w.isSuccess()) {
                                logger.warn("握手帧发送失败，关闭连接: {}", nodeId, w.cause());
                                ch.close();
                            }
                        });
                    }
                    reconnectAttempts.remove(nodeId);
                    disconnectSince.remove(nodeId);
                    logger.info("成功连接 mesh 节点 {}: {}:{}", nodeId, host, busPort);

                    ch.closeFuture().addListener((closeFuture) -> {
                        boolean removed = nodeChannels.remove(nodeId, ch);
                        logger.info("mesh 节点 {} 连接断开", nodeId);
                        if (removed && !closed) {
                            disconnectSince.put(nodeId, System.currentTimeMillis());
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
     * Peer 在线信号：有帧从该节点到达，说明对方已恢复在线。
     * <p>
     * 出站连接正常时无副作用直接返回；出站断开/重连退避中时，清零该 peer 的退避
     * 计数与去重窗口并重新调度重连（退避从 2s 重新起步），避免"对方明明在线却干等
     * 指数退避窗口（最长 64s）"——节点重启后 leader 出站连接迟迟不恢复的根因。
     * </p>
     *
     * @param nodeId 发送方 nodeId（来自入站帧 senderNodeId）
     */
    public void notifyPeerAlive(String nodeId) {
        if (closed || nodeId == null || !nodeEndpoints.containsKey(nodeId)) {
            return; // 未知 peer（含已主动 disconnect 的节点）不处理
        }
        Channel existing = nodeChannels.get(nodeId);
        if (existing != null && existing.isActive()) {
            return; // 出站连接正常，无需干预
        }
        // 必须先清去重窗口：scheduleReconnect 的窗口（≈64s）会挡掉新调度，导致退避重置失效
        reconnectScheduled.remove(nodeId);
        reconnectAttempts.remove(nodeId);
        PeerEndpoint ep = nodeEndpoints.get(nodeId);
        if (ep != null) {
            logger.info("peer {} 在线但出站连接断开，重置退避并立即重连", nodeId);
            scheduleReconnect(nodeId, ep);
        }
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
        if (isSelf(targetNodeId)) {
            // 9/9 事故：nodeId 配置重复时 raft 层会把"自己的 id"当 leader 目标回包，
            // 心跳节奏下每次一条 WARN（单机 10 分钟 4450 条刷屏）。本地短路丢弃 + 节流告警。
            if (tryAcquireSendLogSlot("self")) {
                logger.warn("拒绝向本节点自身 nodeId {} 发送帧 (type=0x{})，已丢弃——"
                                + "若反复出现，请检查 mesh-node-id 是否与 peers 中其他条目配置冲突（同类日志每 {}s 一条）",
                        targetNodeId, Integer.toHexString(frame.getType() & 0xFF), SEND_LOG_INTERVAL_MS / 1000);
            }
            return;
        }
        Channel channel = nodeChannels.get(targetNodeId);
        if (channel == null || !channel.isActive()) {
            if (tryAcquireSendLogSlot("disconn:" + targetNodeId)) {
                logger.warn("目标 mesh 节点 {} 未连接，无法发送（重连退避中，同类日志每 {}s 一条）",
                        targetNodeId, SEND_LOG_INTERVAL_MS / 1000);
            }
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
                // 写失败（编码异常/直接内存 OOM/连接半死）后 channel 可能仍 isActive：
                // 滞留连接表会被 connect() 复用且永远写不出（9/3 生产事故：OOM 后每秒写失败但连接不重建）。
                // close 触发 closeFuture 的既有"清表 + 退避重连"链路，无需在此重复 scheduleReconnect
                //（其 64s 去重窗口会吸收 close 链路里的重复调度）。
                if (tryAcquireSendLogSlot("writefail:" + targetNodeId)) {
                    logger.error("发送 MeshFrame 到节点 {} 失败 (type=0x{})，关闭连接走重连链路（同类日志每 {}s 一条）",
                            targetNodeId, Integer.toHexString(frame.getType() & 0xFF),
                            SEND_LOG_INTERVAL_MS / 1000, future.cause());
                }
                channel.close();
            }
        });
    }

    /**
     * 出站拥塞探测：peer 的 channel 越过写高水位（对端消化不过来）时返回 false。
     * 无 channel 记录时返回 true——未连接由 {@link #send} 的未连接分支处理，
     * 复制器跳过逻辑不应因"还没建连"而误判为拥塞。
     */
    public boolean isWritable(String nodeId) {
        Channel ch = nodeChannels.get(nodeId);
        return ch == null || ch.isWritable();
    }

    /**
     * 发送侧日志节流：同一 key 在 {@link #SEND_LOG_INTERVAL_MS} 窗口内只放行一次。
     */
    private boolean tryAcquireSendLogSlot(String key) {
        long now = System.currentTimeMillis();
        Long last = sendLogSlots.get(key);
        if (last == null || now - last >= SEND_LOG_INTERVAL_MS) {
            sendLogSlots.put(key, now);
            return true;
        }
        return false;
    }

    /**
     * 主动断开指定节点（清除端点，避免触发自动重连）。
     */
    public void disconnect(String nodeId) {
        nodeEndpoints.remove(nodeId);
        reconnectScheduled.remove(nodeId);
        reconnectAttempts.remove(nodeId);
        disconnectSince.remove(nodeId);
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

    /**
     * 判断节点是否已失败（断开超过阈值）。
     * <p>
     * 对齐 Redis {@code cluster-node-timeout} 语义：节点断开超过阈值后，
     * CLUSTER NODES 的 flags 列应标 {@code fail}，使 Redisson 等集群感知客户端
     * 立即从拓扑中移除该节点、停止向死节点发起连接——否则客户端持续重连死节点
     * 导致连接超时堆积、线程池耗尽、请求分钟级卡顿。
     * </p>
     *
     * @param nodeId      目标 nodeId
     * @param thresholdMs 断开阈值（ms）；断开持续超过此值返回 true
     * @return 已断开且超过阈值 → true；在线或未超阈值 → false
     */
    public boolean isFailed(String nodeId, long thresholdMs) {
        if (isConnected(nodeId)) {
            return false;
        }
        Long since = disconnectSince.get(nodeId);
        if (since == null) {
            return false;
        }
        return (System.currentTimeMillis() - since) >= thresholdMs;
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
