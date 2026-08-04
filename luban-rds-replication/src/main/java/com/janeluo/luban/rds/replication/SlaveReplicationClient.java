package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从节点复制客户端
 * 
 * 用于连接主节点并进行复制握手和数据同步
 * 
 * 优化点：
 * - 自动重连机制
 * - 重连后优先尝试部分同步
 * - 重连统计信息
 * - 指数退避重连策略
 */
public class SlaveReplicationClient {
    
    private static final Logger logger = LoggerFactory.getLogger(SlaveReplicationClient.class);
    
    /**
     * 默认重连间隔（毫秒）
     */
    private static final long DEFAULT_RECONNECT_INTERVAL = 1000;
    
    /**
     * 最大重连间隔（毫秒）
     */
    private static final long MAX_RECONNECT_INTERVAL = 30000;
    
    /**
     * 重连间隔增长因子
     */
    private static final double RECONNECT_INTERVAL_MULTIPLIER = 1.5;
    
    private final RdsConfig config;
    private final ReplicationCallback callback;
    // volatile：集群模式下由 ReplicationCoordinator 通过 setMasterAddress() 动态注入
    // （CLUSTER REPLICATE / failover），覆盖构造函数从 config.replicaof 解析的地址。
    private volatile String masterHost;
    private volatile int masterPort;
    
    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean running = false;
    private final AtomicReference<ReplicationState> state = new AtomicReference<>(ReplicationState.DISCONNECTED);
    
    // 复制偏移量
    private volatile long replicationOffset = 0;
    private volatile String masterReplId;
    
    // 重连相关
    private volatile long currentReconnectInterval = DEFAULT_RECONNECT_INTERVAL;
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicInteger totalReconnects = new AtomicInteger(0);
    private final AtomicInteger successfulReconnects = new AtomicInteger(0);
    private final AtomicLong lastReconnectTime = new AtomicLong(0);
    private final AtomicLong totalReconnectTime = new AtomicLong(0);

    // REPLCONF 握手逐条等待 + 超时兜底（C2 方案 A：状态机 + 回调驱动）
    // 每发送一条 REPLCONF 启动一次超时，收到对应 +OK 时取消；超时则回退 DISCONNECTED 并调度重连。
    private static final long DEFAULT_REPLCONF_TIMEOUT_MS = 5000;
    private volatile long replconfTimeoutMs = DEFAULT_REPLCONF_TIMEOUT_MS;
    private ScheduledExecutorService handshakeScheduler;
    private volatile ScheduledFuture<?> replconfTimeoutFuture;
    
    /**
     * 创建复制客户端
     *
     * @param config   配置
     * @param callback 回调接口
     */
    public SlaveReplicationClient(RdsConfig config, ReplicationCallback callback) {
        this.config = config;
        this.callback = callback;

        // 解析主节点地址
        String replicaof = config.getReplicaof();
        if (replicaof != null && !replicaof.isEmpty()) {
            String[] parts = replicaof.split(":");
            this.masterHost = parts[0].trim();
            this.masterPort = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 6379;
        } else {
            this.masterHost = null;
            this.masterPort = 0;
        }

        // REPLCONF 握手超时调度器：独立守护线程，避免依赖 workerGroup 生命周期
        // （workerGroup 在 start() 时才创建，且测试中可能不存在）
        this.handshakeScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slave-replconf-handshake-timeout");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * 显式设置主节点地址（host:port）。
     * <p>
     * 集群模式下由 {@code ReplicationCoordinator} 注入从 CLUSTER REPLICATE /
     * failover 解析出的 master 地址。构造函数只从 {@code config.getReplicaof()}
     * 解析地址（standalone 模式），集群模式下 replicaof 为空，若不注入则
     * {@link #start()} 会因 masterHost 为 null 而静默返回、永不建立复制连接。
     * </p>
     *
     * @param masterAddress master 地址（host:port），null 或空时清除已注入地址
     */
    public void setMasterAddress(String masterAddress) {
        if (masterAddress == null || masterAddress.isEmpty()) {
            this.masterHost = null;
            this.masterPort = 0;
            return;
        }
        String trimmed = masterAddress.trim();
        int idx = trimmed.lastIndexOf(':');
        if (idx < 0) {
            this.masterHost = trimmed;
            this.masterPort = 6379;
        } else {
            this.masterHost = trimmed.substring(0, idx).trim();
            try {
                this.masterPort = Integer.parseInt(trimmed.substring(idx + 1).trim());
            } catch (NumberFormatException e) {
                this.masterPort = 6379;
            }
        }
    }

    /**
     * 启动复制客户端
     */
    public synchronized void start() {
        if (running || masterHost == null) {
            return;
        }
        
        running = true;
        workerGroup = new NioEventLoopGroup(1);
        
        connect();
    }
    
    /**
     * 连接主节点
     */
    private void connect() {
        if (!running) {
            return;
        }
        
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, !config.isReplDisableTcpNodelay())
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, config.getReplTimeout() * 1000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new SimpleChannelInboundHandler<ByteBuf>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                                handleResponse(msg);
                            }
                            
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx) {
                                handleDisconnect();
                            }
                            
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                                logger.error("复制连接异常", cause);
                                ctx.close();
                            }
                        });
                    }
                });
        
        ChannelFuture future = bootstrap.connect(masterHost, masterPort);
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                logger.info("成功连接到主节点 {}:{}", masterHost, masterPort);
                channel = f.channel();
                
                // 重置重连间隔
                currentReconnectInterval = DEFAULT_RECONNECT_INTERVAL;
                
                // 记录成功重连
                if (reconnectAttempts.get() > 0) {
                    successfulReconnects.incrementAndGet();
                    long reconnectDuration = System.currentTimeMillis() - lastReconnectTime.get();
                    totalReconnectTime.addAndGet(reconnectDuration);
                    logger.info("重连成功，耗时 {} ms", reconnectDuration);
                }
                
                reconnectAttempts.set(0);
                startHandshake();
            } else {
                logger.error("连接主节点失败: {}:{}", masterHost, masterPort, f.cause());
                scheduleReconnect();
            }
        });
    }
    
    /**
     * 开始握手流程
     */
    private void startHandshake() {
        state.set(ReplicationState.HANDSHAKE_PING);
        
        // 发送 PING
        sendCommand("PING");
    }
    
    /**
     * 处理响应
     */
    private void handleResponse(ByteBuf msg) {
        String response = msg.toString(CharsetUtil.UTF_8);
        logger.debug("收到主节点响应: {}", response);
        
        ReplicationState currentState = state.get();
        
        switch (currentState) {
            case HANDSHAKE_PING:
                if (response.startsWith("+PONG") || response.startsWith("+NOAUTH")) {
                    handlePingResponse(response);
                }
                break;
            case HANDSHAKE_AUTH:
                handleAuthResponse(response);
                break;
            case HANDSHAKE_REPLCONF_PORT:
            case HANDSHAKE_REPLCONF_IP:
            case HANDSHAKE_REPLCONF_CAPA:
            case HANDSHAKE_REPLCONF_ACK:
                handleReplconfResponse(response);
                break;
            case HANDSHAKE_PSYNC:
                handlePsyncResponse(response);
                break;
            case FULL_SYNC:
            case PARTIAL_SYNC:
            case LOADING_RDB:
            case ONLINE:
                handleSyncData(msg);
                break;
            default:
                logger.warn("未知状态: {}", currentState);
        }
    }
    
    /**
     * 处理 PING 响应
     */
    private void handlePingResponse(String response) {
        if (response.startsWith("+NOAUTH")) {
            // 需要认证
            state.set(ReplicationState.HANDSHAKE_AUTH);
            String password = config.getMasterauth();
            if (password != null && !password.isEmpty()) {
                sendCommand("AUTH", password);
            } else {
                logger.error("主节点需要认证，但未配置 masterauth");
                state.set(ReplicationState.ERROR);
            }
        } else {
            // PONG 响应，继续握手
            sendReplConf();
        }
    }
    
    /**
     * 处理认证响应
     */
    private void handleAuthResponse(String response) {
        if (response.startsWith("+OK")) {
            sendReplConf();
        } else {
            logger.error("认证失败: {}", response);
            state.set(ReplicationState.ERROR);
        }
    }
    
    /**
     * 发送 REPLCONF（C2 方案 A：状态机 + 回调驱动，逐条等待响应）
     *
     * 仅发送第一条 REPLCONF（listening-port），状态切到 HANDSHAKE_REPLCONF_PORT，
     * 并启动 5s 超时兜底。后续 REPLCONF（ip-address、capa）由
     * {@link #handleReplconfResponse(String)} 收到 +OK 后在回调内驱动发送，
     * 最后一条 CAPA 的 +OK 回调内调用 {@link #startPsync()}。
     *
     * 这样每条 REPLCONF 的响应都能正确匹配到各自的状态，避免一次性发送三条
     * 导致 state.set() 覆盖、响应无法匹配的问题。
     */
    private void sendReplConf() {
        // 发送监听端口
        state.set(ReplicationState.HANDSHAKE_REPLCONF_PORT);
        sendCommand("REPLCONF", "listening-port", String.valueOf(config.getPort()));
        scheduleReplconfTimeout();
    }

    /**
     * 处理 REPLCONF 响应（C2 方案 A：回调驱动状态机）
     *
     * 收到 +OK 时根据当前状态推进到下一条 REPLCONF，并在每次发送时重新启动超时；
     * 收到 -ERR 等非 +OK 响应时回退到 DISCONNECTED 并触发重连。
     */
    private void handleReplconfResponse(String response) {
        if (!response.startsWith("+OK")) {
            logger.warn("REPLCONF 响应异常，回退到 DISCONNECTED: {}", response);
            failReplconfHandshake("REPLCONF 异常响应: " + response);
            return;
        }

        // 收到 +OK，取消对应超时
        cancelReplconfTimeout();

        ReplicationState currentState = state.get();
        switch (currentState) {
            case HANDSHAKE_REPLCONF_PORT:
                // PORT +OK -> 发送 IP
                state.set(ReplicationState.HANDSHAKE_REPLCONF_IP);
                sendCommand("REPLCONF", "ip-address", "127.0.0.1");
                scheduleReplconfTimeout();
                break;
            case HANDSHAKE_REPLCONF_IP:
                // IP +OK -> 发送 CAPA
                state.set(ReplicationState.HANDSHAKE_REPLCONF_CAPA);
                sendCommand("REPLCONF", "capa", "eof", "capa", "psync2");
                scheduleReplconfTimeout();
                break;
            case HANDSHAKE_REPLCONF_CAPA:
                // CAPA +OK -> 进入 PSYNC
                logger.info("REPLCONF 握手完成，开始 PSYNC");
                startPsync();
                break;
            case HANDSHAKE_REPLCONF_ACK:
                // REPLCONF ACK 的响应（在线阶段 ACK 确认），不参与握手推进
                logger.debug("REPLCONF ACK 响应: {}", response);
                break;
            default:
                logger.warn("REPLCONF +OK 收到时处于非预期状态: {}", currentState);
                break;
        }
    }

    /**
     * 启动 REPLCONF 单条响应超时
     */
    private void scheduleReplconfTimeout() {
        cancelReplconfTimeout();
        if (handshakeScheduler == null || handshakeScheduler.isShutdown()) {
            return;
        }
        final ReplicationState timeoutState = state.get();
        replconfTimeoutFuture = handshakeScheduler.schedule(() -> {
            handleReplconfTimeout(timeoutState);
        }, replconfTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 取消当前 REPLCONF 超时
     */
    private void cancelReplconfTimeout() {
        ScheduledFuture<?> future = replconfTimeoutFuture;
        if (future != null) {
            future.cancel(false);
            replconfTimeoutFuture = null;
        }
    }

    /**
     * REPLCONF 超时处理：回退 DISCONNECTED 并触发重连
     *
     * @param expectedState 触发超时时等待的状态；若状态已变更（例如 +OK 已先到达）则忽略
     */
    private void handleReplconfTimeout(ReplicationState expectedState) {
        // 仅当仍处于等待该响应的状态时才回退，避免误取消已推进的流程
        if (state.get() != expectedState) {
            return;
        }
        logger.error("REPLCONF 握手超时（等待 {}），回退到 DISCONNECTED", expectedState);
        failReplconfHandshake("REPLCONF 握手超时");
    }

    /**
     * REPLCONF 失败统一处理：回退 DISCONNECTED、通知回调、调度重连
     */
    private void failReplconfHandshake(String reason) {
        logger.warn("REPLCONF 握手失败：{}", reason);
        cancelReplconfTimeout();
        state.set(ReplicationState.DISCONNECTED);
        if (callback != null) {
            callback.onDisconnected();
        }
        if (running) {
            scheduleReconnect();
        }
    }
    
    /**
     * 开始 PSYNC
     */
    private void startPsync() {
        // 进入 PSYNC 握手阶段，等待 +FULLRESYNC 或 +CONTINUE 响应
        state.set(ReplicationState.HANDSHAKE_PSYNC);
        if (masterReplId != null) {
            // 部分重同步 - 优先尝试
            logger.info("尝试部分重同步，replId: {}, offset: {}", masterReplId, replicationOffset);
            sendCommand("PSYNC", masterReplId, String.valueOf(replicationOffset));
        } else {
            // 全量同步
            logger.info("尝试全量同步");
            sendCommand("PSYNC", "?", "-1");
        }
    }
    
    /**
     * 处理 PSYNC 响应
     * 
     * 解析主节点对 PSYNC 命令的响应：
     * - "+FULLRESYNC <replid> <offset>"：进入全量同步
     * - "+CONTINUE [replid]"：进入部分重同步
     * - 其他响应：视为异常，回退到 DISCONNECTED 等待重连
     */
    private void handlePsyncResponse(String response) {
        if (response.startsWith("+FULLRESYNC")) {
            // 全量同步
            logger.info("开始全量同步");
            
            // 解析 "+FULLRESYNC <replid> <offset>"
            String[] parts = response.split("\\s+");
            if (parts.length < 3) {
                logger.error("FULLRESYNC 响应格式异常，缺少 replid 或 offset: {}", response);
                state.set(ReplicationState.DISCONNECTED);
                return;
            }
            
            String newReplId = parts[1];
            long newOffset;
            try {
                newOffset = Long.parseLong(parts[2].trim());
            } catch (NumberFormatException e) {
                logger.error("FULLRESYNC 响应 offset 解析失败: {}", response, e);
                state.set(ReplicationState.DISCONNECTED);
                return;
            }
            
            masterReplId = newReplId;
            replicationOffset = newOffset;
            state.set(ReplicationState.FULL_SYNC);
            
            if (callback != null) {
                callback.onFullSync(masterReplId, replicationOffset);
            }
        } else if (response.startsWith("+CONTINUE")) {
            // 部分重同步
            logger.info("部分重同步成功");
            
            // 解析 "+CONTINUE [replid]"，replid 可选
            String[] parts = response.split("\\s+");
            if (parts.length >= 2) {
                masterReplId = parts[1].trim();
            }
            // CONTINUE 不携带 offset，保留现有 replicationOffset
            
            state.set(ReplicationState.PARTIAL_SYNC);
            
            if (callback != null) {
                callback.onPartialSync(masterReplId, replicationOffset);
            }
        } else {
            // 异常响应（-ERR 等），回退到 DISCONNECTED 等待重连
            logger.error("PSYNC 响应异常，回退到 DISCONNECTED: {}", response);
            state.set(ReplicationState.DISCONNECTED);
        }
    }
    
    /**
     * 处理同步数据
     *
     * C2 回调链：RDB 加载完成 / 部分重同步首条命令到达时，切换到 ONLINE 并触发
     * {@link ReplicationCallback#onOnline()}，使心跳调度器周期发送 REPLCONF ACK。
     *
     * 主节点在全量同步阶段先发送 {@code $<length>\r\n} + RDB 字节，再发送 backlog 中
     * 积压的 RESP 命令流；部分重同步阶段则直接发送 RESP 命令流。RESP 命令以
     * {@code *}（multi-bulk）开头，RDB 字节不会以该字符开头，因此用它作为命令流
     * 起点的启发式信号：在同步态下首次收到以 {@code *} 开头的帧，即视为同步完成、
     * 进入在线状态。
     */
    private void handleSyncData(ByteBuf data) {
        ReplicationState currentState = state.get();

        if (currentState == ReplicationState.FULL_SYNC
                || currentState == ReplicationState.LOADING_RDB) {
            // RDB 传输阶段：若收到 RESP 命令帧（以 '*' 开头），视为 RDB 传输结束、
            // 命令传播开始，先切换到 ONLINE 触发 onOnline，再走命令传播路径。
            if (currentState == ReplicationState.LOADING_RDB && isRespCommandFrame(data)) {
                transitionToOnline();
                dispatchCommandPropagation(data);
            } else {
                if (callback != null) {
                    callback.onRdbData(data.copy());
                }
                // 收到首个 RDB 数据块后从 FULL_SYNC 推进到 LOADING_RDB，
                // 与 SlaveReplicationService.onRdbData 的状态推进保持一致。
                if (currentState == ReplicationState.FULL_SYNC) {
                    state.set(ReplicationState.LOADING_RDB);
                }
            }
        } else if (currentState == ReplicationState.PARTIAL_SYNC) {
            // 部分重同步：收到首条命令即认为进入在线状态，触发 onOnline。
            if (!isOnline()) {
                transitionToOnline();
            }
            dispatchCommandPropagation(data);
        } else if (currentState == ReplicationState.ONLINE) {
            dispatchCommandPropagation(data);
        }

        // 更新偏移量
        replicationOffset += data.readableBytes();
    }

    /**
     * 判断数据帧是否为 RESP 命令（multi-bulk，以 '*' 开头）
     */
    private boolean isRespCommandFrame(ByteBuf data) {
        return data.readableBytes() > 0 && data.getByte(data.readerIndex()) == '*';
    }

    /**
     * 切换到 ONLINE 状态并触发 onOnline 回调（C2 回调链核心）
     */
    private void transitionToOnline() {
        state.set(ReplicationState.ONLINE);
        if (callback != null) {
            callback.onOnline();
        }
        logger.info("复制同步完成，slave 进入 ONLINE 状态，开始周期发送 REPLCONF ACK");
    }

    /**
     * 分发命令传播数据给回调
     */
    private void dispatchCommandPropagation(ByteBuf data) {
        if (callback != null) {
            callback.onCommandPropagation(data.copy());
        }
    }
    
    /**
     * 处理断开连接
     */
    private void handleDisconnect() {
        logger.warn("与主节点断开连接");

        // 取消可能在途的 REPLCONF 超时，避免断开后误触发
        cancelReplconfTimeout();

        ReplicationState previousState = state.get();
        state.set(ReplicationState.DISCONNECTED);

        if (callback != null) {
            callback.onDisconnected();
        }

        // 尝试重连
        if (running && previousState != ReplicationState.ERROR) {
            scheduleReconnect();
        }
    }
    
    /**
     * 调度重连（使用指数退避策略）
     */
    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        
        int attempts = reconnectAttempts.incrementAndGet();
        totalReconnects.incrementAndGet();
        lastReconnectTime.set(System.currentTimeMillis());
        
        logger.info("计划重连，第 {} 次尝试，间隔 {} ms", attempts, currentReconnectInterval);
        
        workerGroup.schedule(() -> {
            logger.info("尝试重新连接主节点...");
            connect();
        }, currentReconnectInterval, TimeUnit.MILLISECONDS);
        
        // 指数退避
        currentReconnectInterval = Math.min(
            (long) (currentReconnectInterval * RECONNECT_INTERVAL_MULTIPLIER),
            MAX_RECONNECT_INTERVAL
        );
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect() {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        
        currentReconnectInterval = DEFAULT_RECONNECT_INTERVAL;
        connect();
    }
    
    /**
     * 发送命令
     */
    private void sendCommand(String... args) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            sb.append("$").append(arg.length()).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        
        channel.writeAndFlush(Unpooled.copiedBuffer(sb.toString(), CharsetUtil.UTF_8));
    }
    
    /**
     * 停止复制客户端
     */
    public synchronized void stop() {
        running = false;

        // 取消可能在途的 REPLCONF 超时
        cancelReplconfTimeout();

        if (channel != null) {
            channel.close();
            channel = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }

        if (handshakeScheduler != null) {
            handshakeScheduler.shutdownNow();
            handshakeScheduler = null;
        }

        state.set(ReplicationState.DISCONNECTED);
    }
    
    /**
     * 获取当前状态
     */
    public ReplicationState getState() {
        return state.get();
    }
    
    /**
     * 获取复制偏移量
     */
    public long getReplicationOffset() {
        return replicationOffset;
    }
    
    /**
     * 设置复制偏移量
     */
    public void setReplicationOffset(long offset) {
        this.replicationOffset = offset;
    }
    
    /**
     * 获取主节点复制 ID
     */
    public String getMasterReplId() {
        return masterReplId;
    }
    
    /**
     * 设置主节点复制 ID
     */
    public void setMasterReplId(String replId) {
        this.masterReplId = replId;
    }
    
    /**
     * 是否在线
     */
    public boolean isOnline() {
        return state.get() == ReplicationState.ONLINE;
    }
    
    /**
     * 发送 REPLCONF ACK
     */
    public void sendAck() {
        if (isOnline()) {
            sendCommand("REPLCONF", "ACK", String.valueOf(replicationOffset));
        }
    }
    
    // ==================== 重连统计信息 ====================
    
    /**
     * 获取当前重连尝试次数
     */
    public int getReconnectAttempts() {
        return reconnectAttempts.get();
    }
    
    /**
     * 获取总重连次数
     */
    public int getTotalReconnects() {
        return totalReconnects.get();
    }
    
    /**
     * 获取成功重连次数
     */
    public int getSuccessfulReconnects() {
        return successfulReconnects.get();
    }
    
    /**
     * 获取上次重连时间
     */
    public long getLastReconnectTime() {
        return lastReconnectTime.get();
    }
    
    /**
     * 获取总重连耗时
     */
    public long getTotalReconnectTime() {
        return totalReconnectTime.get();
    }
    
    /**
     * 获取平均重连耗时
     */
    public long getAverageReconnectTime() {
        int successCount = successfulReconnects.get();
        if (successCount == 0) {
            return 0;
        }
        return totalReconnectTime.get() / successCount;
    }
    
    /**
     * 获取当前重连间隔
     */
    public long getCurrentReconnectInterval() {
        return currentReconnectInterval;
    }
    
    /**
     * 获取重连统计信息
     */
    public String getReconnectInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("reconnect_attempts:").append(reconnectAttempts.get()).append("\r\n");
        sb.append("reconnect_total:").append(totalReconnects.get()).append("\r\n");
        sb.append("reconnect_successful:").append(successfulReconnects.get()).append("\r\n");
        sb.append("reconnect_current_interval:").append(currentReconnectInterval).append(" ms\r\n");
        sb.append("reconnect_avg_time:").append(getAverageReconnectTime()).append(" ms\r\n");
        return sb.toString();
    }
}
