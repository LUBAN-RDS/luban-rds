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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从节点复制客户端
 * 用于连接主节点并进行复制握手和数据同步
 */
public class SlaveReplicationClient {
    
    private static final Logger logger = LoggerFactory.getLogger(SlaveReplicationClient.class);
    
    private final RdsConfig config;
    private final ReplicationCallback callback;
    private final String masterHost;
    private final int masterPort;
    
    private EventLoopGroup workerGroup;
    private Channel channel;
    private volatile boolean running = false;
    private final AtomicReference<ReplicationState> state = new AtomicReference<>(ReplicationState.DISCONNECTED);
    
    // 复制偏移量
    private volatile long replicationOffset = 0;
    private volatile String masterReplId;
    
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
                        // 这里需要添加RESP协议解码器
                        // 暂时使用简单的字符串处理
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
     * 发送 REPLCONF
     */
    private void sendReplConf() {
        // 发送监听端口
        state.set(ReplicationState.HANDSHAKE_REPLCONF_PORT);
        sendCommand("REPLCONF", "listening-port", String.valueOf(config.getPort()));
        
        // 发送 IP 地址
        state.set(ReplicationState.HANDSHAKE_REPLCONF_IP);
        sendCommand("REPLCONF", "ip-address", "127.0.0.1");
        
        // 发送能力
        state.set(ReplicationState.HANDSHAKE_REPLCONF_CAPA);
        sendCommand("REPLCONF", "capa", "eof", "capa", "psync2");
        
        // 开始 PSYNC
        startPsync();
    }
    
    /**
     * 处理 REPLCONF 响应
     */
    private void handleReplconfResponse(String response) {
        if (!response.startsWith("+OK")) {
            logger.warn("REPLCONF 响应异常: {}", response);
        }
    }
    
    /**
     * 开始 PSYNC
     */
    private void startPsync() {
        if (masterReplId != null) {
            // 部分重同步
            sendCommand("PSYNC", masterReplId, String.valueOf(replicationOffset));
        } else {
            // 全量同步
            sendCommand("PSYNC", "?", "-1");
        }
    }
    
    /**
     * 处理 PSYNC 响应
     */
    private void handlePsyncResponse(String response) {
        if (response.startsWith("+FULLRESYNC")) {
            // 全量同步
            logger.info("开始全量同步");
            state.set(ReplicationState.FULL_SYNC);
            
            // 解析响应
            String[] parts = response.split(" ");
            if (parts.length >= 3) {
                masterReplId = parts[1];
                replicationOffset = Long.parseLong(parts[2].trim());
            }
            
            if (callback != null) {
                callback.onFullSync(masterReplId, replicationOffset);
            }
        } else if (response.startsWith("+CONTINUE")) {
            // 部分重同步
            logger.info("部分重同步");
            state.set(ReplicationState.PARTIAL_SYNC);
            
            String[] parts = response.split(" ");
            if (parts.length >= 2) {
                masterReplId = parts[1].trim();
            }
            
            if (callback != null) {
                callback.onPartialSync(masterReplId, replicationOffset);
            }
        } else {
            logger.error("PSYNC 响应异常: {}", response);
        }
    }
    
    /**
     * 处理同步数据
     */
    private void handleSyncData(ByteBuf data) {
        // 这里需要处理 RDB 数据和命令传播
        // 简化处理，实际需要完整的协议解析
        
        if (state.get() == ReplicationState.FULL_SYNC || state.get() == ReplicationState.LOADING_RDB) {
            if (callback != null) {
                callback.onRdbData(data.copy());
            }
        } else if (state.get() == ReplicationState.ONLINE || state.get() == ReplicationState.PARTIAL_SYNC) {
            if (callback != null) {
                callback.onCommandPropagation(data.copy());
            }
        }
        
        // 更新偏移量
        replicationOffset += data.readableBytes();
    }
    
    /**
     * 处理断开连接
     */
    private void handleDisconnect() {
        logger.warn("与主节点断开连接");
        
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
     * 调度重连
     */
    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        
        workerGroup.schedule(() -> {
            logger.info("尝试重新连接主节点...");
            connect();
        }, config.getReplReconnectInterval(), TimeUnit.MILLISECONDS);
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
        
        if (channel != null) {
            channel.close();
            channel = null;
        }
        
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
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
}
