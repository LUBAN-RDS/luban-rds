package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
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
import java.util.concurrent.atomic.AtomicLong;
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
     * 断线后首次重连的延迟（毫秒）。重连失败的后续重试按指数退避递增。
     */
    private static final long RECONNECT_DELAY_MS = 2000;

    /**
     * 指数退避上限（毫秒）。重连延迟 = RECONNECT_DELAY_MS * 2^min(attempts, MAX_BACKOFF_SHIFT)，
     * 超过此次数后延迟不再翻倍（对齐 Redis orphaned_time + node-timeout 门控的量级）。
     */
    private static final int MAX_BACKOFF_SHIFT = 5;

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
     * 待响应的请求映射（requestId -> 响应 Future，P1-20）。
     * <p>
     * 用于 sendAndWait 请求-响应匹配：发送 MIGRATE_KEY 后等待目标节点回 MIGRATE_KEY_ACK。
     * 按 requestId（而非 nodeId）严格匹配，消除并发 MIGRATE 到同一节点时 ACK 串线
     * （A 的 ACK 错误完成 B 的 future → B 误报成功删源键 → 数据丢失）。
     * </p>
     */
    private final Map<Long, CompletableFuture<GossipMessage>> pendingResponses;

    /**
     * 待响应请求的目标节点映射（requestId -> 目标 nodeId，N-7）。
     * <p>
     * 与 {@link #pendingResponses} 同步维护：sendAndWait 注册请求时记录目标节点，
     * completeResponse 校验 ACK 的发送方与目标节点一致才完成 future。
     * 防止任何能连上总线端口的对端伪造 MIGRATE_KEY_ACK 命中在途请求，
     * 导致源端删除从未导入的键（数据丢失）。
     * </p>
     */
    private final Map<Long, String> pendingResponseTargets;

    /**
     * requestId 递增序列（P1-20）。每个 sendAndWait 分配唯一 id 写入请求消息，
     * 响应回填同一 id 用于匹配。初始值随机化（N-7：使在途 requestId 不可预测，
     * 增加伪造 ACK 命中难度）；0 保留为"未设置"（旧消息解码默认值，不命中任何 future）。
     */
    private final AtomicLong requestIdSeq = new AtomicLong(
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000L, 10_000_000_000L));

    /**
     * 重连去重映射（P1-21）：nodeId → 当前已调度的重连触发时刻（ms）。
     * <p>
     * send()/scheduleReconnect 在 node-timeout（5s）窗口内仅允许调度一次重连，
     * 避免每秒心跳触发 ~5 个重叠 connect 尝试形成重连风暴。
     * 调度任务执行时清除本条目，允许下一次失败后重新调度。
     * </p>
     */
    private final Map<String, Long> reconnectScheduled;

    /**
     * 重连失败计数（P1-21）：nodeId → 连续失败次数，用于指数退避。
     * 连接成功（connect 监听器 isSuccess 分支）时清除。
     */
    private final Map<String, Long> reconnectAttempts;

    /**
     * 并发建连互斥锁表（N-40）。
     * <p>
     * key = 节点ID，value = 该节点建连流程的互斥锁。节点粒度串行化
     * "检查已连接 → bootstrap.connect" 段，防止并发 connect 双建连接/通道映射覆盖。
     * 锁表按 nodeId 常驻（集群规模小、FORGET 频次低，内存可忽略），
     * 不移除以避免"移除期间另一线程拿到新锁对象"的 ABA 竞态。
     * </p>
     */
    private final Map<String, Object> connectLocks = new ConcurrentHashMap<>();

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
        this.pendingResponseTargets = new ConcurrentHashMap<>();
        this.reconnectScheduled = new ConcurrentHashMap<>();
        this.reconnectAttempts = new ConcurrentHashMap<>();
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

        // N-40：并发 connect 互斥——同一节点同时只允许一个建连流程。
        // 旧实现中两个并发 connect 都通过"未连接"检查后各自 bootstrap.connect：
        // 先完成的通道未进映射（后写的覆盖它），且其 closeFuture 监听器会误删
        // 后建通道，形成连接泄漏/永久残留。锁表按 nodeId 常驻（集群规模小，可接受）。
        Object lock = connectLocks.computeIfAbsent(nodeId, k -> new Object());
        synchronized (lock) {
            // 检查是否已连接
            Channel existingChannel = nodeChannels.get(nodeId);
            if (existingChannel != null && existingChannel.isActive()) {
                logger.debug("节点 {} 已连接，复用现有连接", nodeId);
                return existingChannel.newSucceededFuture();
            }

            // 计算总线端口：N-37 优先使用对端通告的总线端口（cluster-announce-bus-port /
            // gossip/nodes.conf 传播的 @cport），固定 servicePort+10000 在 NAT/防火墙
            // 自定义总线端口场景下无法组建集群。对端未通告（busPort=0，如旧版本）时
            // 回退 servicePort + 10000。
            ClusterNode target = clusterConfig.getNode(nodeId);
            int busPort = resolveBusPort(target, port);

            logger.info("正在连接节点 {}，地址: {}:{}", nodeId, host, busPort);

            // N-39：仅当节点仍存在于集群配置（且非本节点）时才记录重连端点。
            // 已删除（CLUSTER FORGET）节点的在途 connect/断线监听器不得重新登记端点，
            // 否则已删除节点被"复活"成僵尸重连循环（无条件 nodeEndpoints.put 的旧行为）。
            String myNodeId = clusterConfig.getMyNodeId();
            if (target != null && !nodeId.equals(myNodeId)) {
                nodeEndpoints.put(nodeId, new NodeEndpoint(host, port));
            }

            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    // P1-22：开启 TCP keepalive（对齐服务端 ClusterBusServer SO_KEEPALIVE）。
                    // 半开连接（对端进程崩溃但本地 socket 未知）会由 TCP keepalive 探测最终触发
                    // close 事件，进而走现有断线重连路径；否则 isActive() 长期为 true 使节点永不重连。
                    .option(ChannelOption.SO_KEEPALIVE, true)
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
                    // N-39：连接建立时节点已不存在（建连期间被 CLUSTER FORGET）→
                    // 立即关闭通道且不登记映射，防止在途连接把已删除节点复活。
                    if (clusterConfig.getNode(nodeId) == null || nodeId.equals(myNodeId)) {
                        logger.warn("连接建立但节点已不存在/为本节点，关闭连接: nodeId={}", nodeId);
                        channel.close();
                        return;
                    }
                    nodeChannels.put(nodeId, channel);
                    // 连接成功，重置该节点的重连失败计数（指数退避归零）
                    reconnectAttempts.remove(nodeId);
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
    }

    /**
     * N-37：解析目标节点的出站总线端口——优先使用对端通告的总线端口
     * （cluster-announce-bus-port / gossip / nodes.conf 传播的 @cport），
     * 未通告（busPort=0，如旧版本节点）时回退 servicePort + 10000。
     *
     * @param target      目标节点（可能为 null）
     * @param servicePort 目标节点的服务端口
     * @return 出站连接使用的总线端口
     */
    static int resolveBusPort(ClusterNode target, int servicePort) {
        return (target != null && target.getBusPort() > 0)
                ? target.getBusPort() : servicePort + ClusterBusServer.BUS_PORT_OFFSET;
    }

    /**
     * 在 EventLoop 上调度一次延迟重连（P1-21：去重 + 指数退避）。
     * <p>
     * 去重：在一个延迟窗口内对同一节点仅调度一次重连。send()/断线监听器每秒都可能触发，
     * 若不门控会在 5s CONNECT_TIMEOUT 窗口内堆叠 ~5 个重叠 connect 尝试形成重连风暴。
     * 用 {@code reconnectScheduled} 记录已调度的触发时刻，窗口内重复调用直接返回。
     * </p>
     * <p>
     * 指数退避：连续重连失败时延迟按 {@code RECONNECT_DELAY_MS * 2^min(attempts, MAX_BACKOFF_SHIFT)}
     * 递增（上限约 64s），对齐 Redis orphaned_time + node-timeout 门控的量级，避免对不可达节点高频冲击。
     * 连接成功时由 connect 监听器清零 attempts。
     * </p>
     *
     * @param nodeId   节点ID
     * @param endpoint 节点端点
     */
    private void scheduleReconnect(String nodeId, NodeEndpoint endpoint) {
        if (closed) {
            return;
        }
        long now = System.currentTimeMillis();
        // 去重：窗口内已调度过则不再调度（putIfAbsent + 时间窗口校验）
        Long lastScheduled = reconnectScheduled.get(nodeId);
        long window = RECONNECT_DELAY_MS * (1L << MAX_BACKOFF_SHIFT);
        if (lastScheduled != null && (now - lastScheduled) < window) {
            return;
        }
        reconnectScheduled.put(nodeId, now);

        // 计入本次失败（原子递增，跨 EventLoop 安全）。connect 成功时由监听器清零。
        // 用 compute 保证自增原子：返回递增后的新值。
        long attempts = reconnectAttempts.compute(nodeId, (k, v) -> (v == null) ? 1L : v + 1L);
        long shift = Math.min(attempts - 1L, (long) MAX_BACKOFF_SHIFT);
        long delay = RECONNECT_DELAY_MS * (1L << shift);

        group.schedule(() -> {
            // 执行时清除去重标记，允许下一次失败后重新调度
            reconnectScheduled.remove(nodeId);
            if (closed || nodeEndpoints.get(nodeId) == null) {
                return;
            }
            Channel existing = nodeChannels.get(nodeId);
            if (existing != null && existing.isActive()) {
                return;
            }
            logger.info("尝试重连节点 {}，地址: {}:{}，第 {} 次退避 {}ms", nodeId, endpoint.host,
                    endpoint.port + ClusterBusServer.BUS_PORT_OFFSET, attempts, delay);
            try {
                connect(nodeId, endpoint.host, endpoint.port);
            } catch (Exception e) {
                logger.warn("重连节点 {} 失败", nodeId, e);
            }
        }, delay, TimeUnit.MILLISECONDS);
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
        // 清除重连相关状态：主动断开不应残留重连调度/退避计数
        reconnectScheduled.remove(nodeId);
        reconnectAttempts.remove(nodeId);
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

        countMessageSent(message);

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
     * N-26：总线层分类型发送计数（CLUSTER INFO per-type 字段）。
     * <p>
     * send/sendAndWait 共用的发送计数点；PONG 等响应消息由 ClusterBusHandler
     * 在 writeAndFlush 处单独计数。协议层旧计数点（GossipProtocol）已移除，
     * 避免双重计数。
     * </p>
     *
     * @param message 已发出的消息
     */
    private void countMessageSent(GossipMessage message) {
        if (message != null && message.getType() != null
                && gossipProtocol != null && gossipProtocol.getClusterStateManager() != null) {
            gossipProtocol.getClusterStateManager()
                    .incrementMessagesSent(message.getType().getDisplayName(), 1);
        }
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

        countMessageSent(message);

        Channel channel = nodeChannels.get(nodeId);
        if (channel == null || !channel.isActive()) {
            logger.warn("节点 {} 未连接或连接已断开，无法发送消息", nodeId);
            return null;
        }

        // P1-20：分配唯一 requestId，先注册 future 再发送，ACK 按 requestId 严格匹配。
        // 消除并发 MIGRATE 到同一节点时 ACK 串线（旧实现按 nodeId 单槽位，后注册覆盖前者）。
        long reqId = requestIdSeq.getAndIncrement();
        message.setRequestId(reqId);

        // 注册待响应 Future，由 ClusterBusHandler 收到 MIGRATE_KEY_ACK 时完成
        CompletableFuture<GossipMessage> future = new CompletableFuture<>();
        pendingResponses.put(reqId, future);
        // N-7：记录请求的目标节点，completeResponse 校验 ACK 发送方与之一致，防伪造 ACK
        pendingResponseTargets.put(reqId, nodeId);

        try {
            // 发送消息
            ChannelFuture sendFuture = channel.writeAndFlush(message);
            sendFuture.await(timeout, TimeUnit.MILLISECONDS);

            if (!sendFuture.isSuccess()) {
                logger.error("发送消息到节点 {} 失败", nodeId, sendFuture.cause());
                pendingResponses.remove(reqId);
                return null;
            }

            // 等待响应消息（由 completeResponse 完成）
            try {
                return future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                logger.warn("等待节点 {} 的响应超时或失败（requestId={}）", nodeId, reqId, e);
                return null;
            }
        } catch (InterruptedException e) {
            logger.error("等待消息响应时被中断", e);
            Thread.currentThread().interrupt();
            return null;
        } finally {
            pendingResponses.remove(reqId);
            pendingResponseTargets.remove(reqId);
        }
    }

    /**
     * 完成待响应的请求（由 ClusterBusHandler 在收到 MIGRATE_KEY_ACK 等响应消息时调用，P1-20）。
     * <p>
     * 按响应消息携带的 requestId 严格匹配等待中的 future。requestId=0（旧格式或未携带）
     * 不会命中任何 future（sendAndWait 分配的 id ≥1），保证串线安全。
     * </p>
     * <p>
     * 本重载不校验 ACK 发送方（senderNodeId=null 视为信任），仅供测试与历史调用点使用；
     * 生产路径必须使用 {@link #completeResponse(long, GossipMessage, String)} 校验来源。
     * </p>
     *
     * @param requestId 响应对应的请求 ID（取自响应消息的 getRequestId()）
     * @param response  响应消息
     */
    public void completeResponse(long requestId, GossipMessage response) {
        completeResponse(requestId, response, null);
    }

    /**
     * 完成待响应的请求（N-7：校验 ACK 来源与目标节点一致）。
     * <p>
     * 在 {@link #completeResponse(long, GossipMessage)} 的 requestId 严格匹配基础上，
     * 额外校验 ACK 的发送方节点 ID 与发起请求时的目标节点一致。总线无认证，
     * 任何能连上总线端口的对端都可伪造 MIGRATE_KEY_ACK；若不校验来源，伪造 ACK 可
     * 命中在途请求，使源端删除从未导入的键（数据丢失，本实现独有攻击面）。
     * </p>
     *
     * @param requestId    响应对应的请求 ID（取自响应消息的 getRequestId()）
     * @param response     响应消息
     * @param senderNodeId ACK 的实际发送方节点 ID（取自消息 senderNodeId），null 时跳过来源校验
     */
    public void completeResponse(long requestId, GossipMessage response, String senderNodeId) {
        if (requestId == 0) {
            // 旧格式 ACK 无 requestId，无法匹配；忽略避免误完成任意 future
            logger.warn("收到无 requestId 的响应，无法匹配等待中的请求: type={}", response.getType());
            return;
        }
        // N-7：来源校验——ACK 发送方必须与请求目标节点一致，否则视为伪造/串线，忽略。
        // 未知 requestId 或目标记录已清理（超时/完成）时同样忽略，防止陈旧 ACK 误完成新请求。
        if (senderNodeId != null) {
            String targetNodeId = pendingResponseTargets.get(requestId);
            if (targetNodeId == null) {
                logger.warn("收到无目标记录的响应，忽略: requestId={}, sender={}, type={}",
                        requestId, senderNodeId, response.getType());
                return;
            }
            if (!targetNodeId.equals(senderNodeId)) {
                logger.warn("响应来源与请求目标不一致，忽略（防伪造 ACK）: requestId={}, sender={}, target={}",
                        requestId, senderNodeId, targetNodeId);
                return;
            }
        }
        CompletableFuture<GossipMessage> future = pendingResponses.get(requestId);
        if (future != null) {
            future.complete(response);
            logger.debug("完成 requestId={} 的待响应请求: {}", requestId, response.getType());
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
        // 清除重连调度/退避计数
        reconnectScheduled.clear();
        reconnectAttempts.clear();
        // 清除待响应请求并通知等待方，避免 sendAndWait 永久阻塞
        for (Map.Entry<Long, CompletableFuture<GossipMessage>> entry : pendingResponses.entrySet()) {
            entry.getValue().cancel(false);
        }
        pendingResponses.clear();
        // N-7：同步清理请求目标映射，避免陈旧条目误校验
        pendingResponseTargets.clear();

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
