package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.handler.LuaScriptAnalyzer;
import com.janeluo.luban.rds.core.stream.BlockingResult;
import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamEntry;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.stream.Stream.StreamWaiter;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.slowlog.SlowLogManager;
import com.janeluo.luban.rds.protocol.Command;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.migration.MigrateCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.replication.MasterReplicationManager;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.MeshClientRedirector;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import com.janeluo.luban.rds.common.context.TraceContext;

/**
 * Redis服务器命令处理器
 * 
 * <p>负责处理客户端连接和命令执行，是服务器的核心处理组件。
 * 
 * <p>主要功能：
 * <ul>
 *   <li>RESP协议解析和命令分发</li>
 *   <li>客户端连接和状态管理</li>
 *   <li>Pub/Sub消息订阅发布</li>
 *   <li>事务支持（MULTI/EXEC/DISCARD/WATCH）</li>
 *   <li>慢日志记录</li>
 *   <li>命令监控（MONITOR）</li>
 *   <li>认证（AUTH）</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class RedisServerHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisServerHandler.class);
    
    /**
     * 已知命令集合
     */
    private static final java.util.Set<String> KNOWN_COMMANDS = new HashSet<>();
    static {
        String[] names = new String[]{
                "PING","ECHO","SELECT","QUIT","AUTH",
                "GET","SET","SETNX","GETSET","MGET","MSET","MSETNX","STRLEN","APPEND","INCR","DECR","INCRBY","DECRBY",
                "LPUSH","RPUSH","LPOP","RPOP","LLEN","LRANGE","LINDEX","LSET","LREM","LTRIM","BLPOP","BRPOP",
                "SADD","SREM","SISMEMBER","SMEMBERS","SCARD","SPOP","SRANDMEMBER","SMOVE","SUNION","SINTER","SDIFF","SSCAN",
                "HSET","HGET","HMSET","HMGET","HGETALL","HKEYS","HVALS","HLEN","HEXISTS","HDEL","HINCRBY","HSCAN",
                "ZADD","ZREM","ZSCORE","ZRANK","ZREVRANK","ZRANGE","ZREVRANGE","ZRANGEBYSCORE","ZCARD","ZCOUNT","ZINCRBY","ZPOPMAX","ZPOPMIN","ZSCAN",
                "EXPIRE","PEXPIRE","TTL","PTTL","PERSIST","TYPE","KEYS","DEL","EXISTS","DBSIZE","FLUSHDB","FLUSHALL","SCAN",
                "SUBSCRIBE","UNSUBSCRIBE","PUBLISH","PSUBSCRIBE","PUNSUBSCRIBE","SSUBSCRIBE","SUNSUBSCRIBE",
                "EVAL","EVALSHA","SCRIPT","SCRIPT LOAD","SCRIPT EXISTS","SCRIPT FLUSH","SCRIPT KILL",
                "MULTI","EXEC","DISCARD","WATCH","UNWATCH","QUIT",
                "INFO", "MONITOR", "CONFIG", "COMMAND", "TIME", "BGSAVE", "BGREWRITEAOF", "LASTSAVE",
                "MEMORY", "MEMORY USAGE", "MEMORY STATS", "MEMORY PURGE", "MEMORY DOCTOR", "MEMORY MALLOC-STATS", "MEMORY HELP",
                "SLOWLOG",
                // Stream 命令
                "XADD","XLEN","XRANGE","XREVRANGE","XDEL","XTRIM","XREAD","XINFO",
                "XGROUP","XREADGROUP","XACK","XPENDING","XCLAIM","XAUTOCLAIM",
                // 集群命令
                "ASKING","READONLY","READWRITE","CLUSTER","MIGRATE",
                // 复制命令
                "SLAVEOF","REPLICAOF","PSYNC","SYNC","REPLCONF","WAIT"
        };
        for (String n : names) KNOWN_COMMANDS.add(n);
        
        // Register PubSubService
        com.janeluo.luban.rds.common.context.ServerContext.setPubSubService((channel, message) -> publishMessage(channel, message));
    }
    
    // 服务器状态管理
    private static final long SERVER_START_TIME = System.currentTimeMillis();
    private static final AtomicLong TOTAL_COMMANDS_PROCESSED = new AtomicLong(0);
    private static final AtomicLong TOTAL_CONNECTIONS_RECEIVED = new AtomicLong(0);
    private static final AtomicLong CURRENT_CONNECTIONS = new AtomicLong(0);
    
    // Attribute key for storing ClientInfo in Channel
    private static final AttributeKey<ClientInfo> CLIENT_INFO_KEY = AttributeKey.valueOf("clientInfo");
    
    // Pub/Sub 管理
    private static final PubSubManager PUB_SUB_MANAGER = new PubSubManager();
    private static final com.janeluo.luban.rds.protocol.RedisProtocolParser SHARED_PROTOCOL_PARSER = new com.janeluo.luban.rds.protocol.RedisProtocolParser();
    
    public static int publishMessage(String channel, String message) {
        int receivers = 0;
        java.util.List<Channel> snapshot = new java.util.ArrayList<>(PUB_SUB_MANAGER.subscribers(channel));
        for (Channel ch : snapshot) {
            ByteBuf resp = SHARED_PROTOCOL_PARSER.serialize(java.util.Arrays.asList(
                CMD_MESSAGE, 
                channel.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                message.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            if (resp != null && resp.isReadable()) {
                ch.writeAndFlush(resp);
                receivers++;
            } else if (resp != null) {
                resp.release();
            }
        }
        
        // Pattern subscribers
        java.util.Map<String, java.util.Collection<Channel>> patternSubs = PUB_SUB_MANAGER.patternSubscribers(channel);
        for (java.util.Map.Entry<String, java.util.Collection<Channel>> entry : patternSubs.entrySet()) {
            String pattern = entry.getKey();
            for (Channel ch : entry.getValue()) {
                ByteBuf resp = SHARED_PROTOCOL_PARSER.serialize(java.util.Arrays.asList(
                    CMD_PMESSAGE, 
                    pattern.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                    channel.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                    message.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
                if (resp != null && resp.isReadable()) {
                    ch.writeAndFlush(resp);
                    receivers++;
                } else if (resp != null) {
                    resp.release();
                }
            }
        }
        
        // Stream subscribers
        java.util.List<Channel> streamSnapshot = new java.util.ArrayList<>(PUB_SUB_MANAGER.getStreamSubscribers(channel));
        for (Channel ch : streamSnapshot) {
            ByteBuf resp = SHARED_PROTOCOL_PARSER.serialize(java.util.Arrays.asList(
                CMD_SMESSAGE, 
                channel.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                message.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1)));
            if (resp != null && resp.isReadable()) {
                ch.writeAndFlush(resp);
                receivers++;
            } else if (resp != null) {
                resp.release();
            }
        }
        return receivers;
    }
    
    // Pub/Sub 响应常量
    private static final byte[] CMD_SUBSCRIBE = "subscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_UNSUBSCRIBE = "unsubscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_PSUBSCRIBE = "psubscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_PUNSUBSCRIBE = "punsubscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_SSUBSCRIBE = "ssubscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_SUNSUBSCRIBE = "sunsubscribe".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_MESSAGE = "message".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_PMESSAGE = "pmessage".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] CMD_SMESSAGE = "smessage".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    private static final byte[] EMPTY_BYTES = new byte[0];
    
    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    
    // 客户端空闲超时时间（毫秒），0表示禁用
    private final int timeout;
    
    // 集群模式相关字段
    private final boolean clusterEnabled;
    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;

    /**
     * 集群 state=fail 时是否允许处理只读命令（对应 Redis cluster-allow-reads-when-down）。
     * 在构造时从 {@link com.janeluo.luban.rds.common.context.ServerContext#getConfig()} 一次性读取，
     * 避免每条命令热路径上重复读取配置。
     */
    private final boolean clusterAllowReadsWhenDown;
    
    // 复制模式相关字段
    private com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler replicationCommandHandler;

    // 复制协调器（集群模式下由 NettyRedisServer 注入，用于获取主节点复制管理器与判断角色）
    private ReplicationCoordinator replicationCoordinator;

    /**
     * 持久化服务（由 NettyRedisServer 注入，用于 AOF 写命令记录）。
     * <p>
     * 非 AOF 模式下 {@link PersistService#recordCommand(byte[])} 为 default 空实现，
     * 因此无需在此判断持久化模式；注入 null 时跳过记录。
     * </p>
     */
    private PersistService persistService;
    
    // 集群命令处理器
    private ClusterCommandHandler clusterCommandHandler;

    // MIGRATE 命令处理器（集群模式下节点间键迁移）
    private MigrateCommandHandler migrateCommandHandler;

    // 写暂停门控（P1-12，手动 failover 普通模式期间拒绝写）。volatile 保证写路径零开销读
    private volatile com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate writePauseGate =
            new com.janeluo.luban.rds.cluster.lifecycle.NoOpWritePauseGate();

    /**
     * Mesh 模式客户端重定向器（阶段 6）。仅 mesh 模式下由装配层（阶段 12 MeshBootstrap）注入；
     * 非 mesh 模式为 {@code null}，processCommand 不会产生 {@link MovedToLeaderException}，
     * catch 块形同虚设，对 cluster / standalone 模式零影响。
     */
    private MeshClientRedirector meshClientRedirector;
    
    public RedisServerHandler(MemoryStore memoryStore, DefaultCommandHandler commandHandler, RedisProtocolParser protocolParser) {
        this(memoryStore, commandHandler, protocolParser, 0, false, null, null);
    }
    
    public RedisServerHandler(MemoryStore memoryStore, DefaultCommandHandler commandHandler, RedisProtocolParser protocolParser, int timeout) {
        this(memoryStore, commandHandler, protocolParser, timeout, false, null, null);
    }
    
    /**
     * 完整构造方法（支持集群模式）
     *
     * @param memoryStore     内存存储
     * @param commandHandler  命令处理器
     * @param protocolParser  协议解析器
     * @param timeout         客户端空闲超时时间（毫秒）
     * @param clusterEnabled  是否启用集群模式
     * @param clusterConfig   集群配置
     * @param slotManager     槽位管理器
     */
    public RedisServerHandler(MemoryStore memoryStore, DefaultCommandHandler commandHandler, 
                              RedisProtocolParser protocolParser, int timeout,
                              boolean clusterEnabled, ClusterConfig clusterConfig, SlotManager slotManager) {
        this.memoryStore = memoryStore;
        this.commandHandler = commandHandler;
        this.protocolParser = protocolParser;
        this.timeout = timeout;
        this.clusterEnabled = clusterEnabled;
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;

        // 初始化复制管理器（主节点模式）
        com.janeluo.luban.rds.common.config.RdsConfig config =
            com.janeluo.luban.rds.common.context.ServerContext.getConfig();
        if (config != null) {
            MasterReplicationManager.initialize(
                (int) config.getReplBacklogSize());
            this.clusterAllowReadsWhenDown = config.isClusterAllowReadsWhenDown();
        } else {
            this.clusterAllowReadsWhenDown = false;
        }
    }
    
    /**
     * 设置复制命令处理器
     */
    public void setReplicationCommandHandler(
            com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler handler) {
        this.replicationCommandHandler = handler;
    }

    /**
     * 设置复制协调器（集群模式下由 NettyRedisServer 注入）。
     * <p>
     * 命令传播通过协调器获取主节点复制管理器，避免直接调用
     * {@link MasterReplicationManager#getInstance()} 懒创建非预期的单例，
     * 并在从节点角色下跳过传播。
     * </p>
     *
     * @param coordinator 复制协调器，可为 null（非复制模式）
     */
    public void setReplicationCoordinator(ReplicationCoordinator coordinator) {
        this.replicationCoordinator = coordinator;
    }

    /**
     * 设置持久化服务（由 NettyRedisServer 注入）。
     * <p>
     * 用于在命令执行后将写命令的原始 RESP 帧写入 AOF（{@link PersistService#recordCommand(byte[])}）。
     * 非 AOF 模式下 recordCommand 为 default 空实现，注入 null 表示不启用 AOF 记录。
     * </p>
     *
     * @param persistService 持久化服务实例，可为 null
     */
    public void setPersistService(PersistService persistService) {
        this.persistService = persistService;
    }
    
    /**
     * 设置集群命令处理器
     */
    public void setClusterCommandHandler(ClusterCommandHandler handler) {
        this.clusterCommandHandler = handler;
    }

    /**
     * 设置 MIGRATE 命令处理器
     */
    public void setMigrateCommandHandler(MigrateCommandHandler handler) {
        this.migrateCommandHandler = handler;
    }

    /**
     * 设置写暂停门控（P1-12）。
     * <p>
     * 注入后，写路径在执行写命令前查询 {@code gate.isPaused()}，暂停时拒绝写
     * （手动 failover 普通模式期间 master 已暂停写，避免接管时丢数据）。
     * </p>
     *
     * @param gate 写暂停门控，null 时保持默认 NoOp（永不暂停）
     */
    public void setWritePauseGate(com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate gate) {
        this.writePauseGate = gate != null ? gate
                : new com.janeluo.luban.rds.cluster.lifecycle.NoOpWritePauseGate();
    }

    /**
     * 设置 Mesh 客户端重定向器（阶段 6）。
     * <p>
     * 仅 mesh 模式下由装配层注入（用于把 {@link MovedToLeaderException} 转成 MOVED/MESHDOWN 响应）。
     * 非 mesh 模式不注入（保持 {@code null}），processCommand catch 块判断非空才处理，
     * 不影响 cluster / standalone 模式。mesh gate 的真正接入（写命令走 MeshWriteGate）留阶段 12。
     * </p>
     *
     * @param redirector Mesh 客户端重定向器，{@code null} 表示非 mesh 模式
     */
    public void setMeshClientRedirector(MeshClientRedirector redirector) {
        this.meshClientRedirector = redirector;
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ByteBuf) {
            ByteBuf buffer = (ByteBuf) msg;
            try {
                ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
                if (clientInfo == null) {
                    clientInfo = new ClientInfo(null);
                    clientInfo.initInboundBuf(ctx.alloc());
                    ctx.channel().attr(CLIENT_INFO_KEY).set(clientInfo);
                }
                clientInfo.updateLastActiveTime();
                clientInfo.getInboundBuf().writeBytes(buffer);
                while (true) {
                    if (clientInfo.getProtocolVersion() == ProtocolVersion.RESP2) {
                        if (detectResp3Hello(clientInfo.getInboundBuf(), ctx, clientInfo)) {
                            continue;
                        }
                    }
                    // 捕获 raw RESP 帧字节：在 parse 前后记录 readerIndex，
                    // parse 成功会推进 readerIndex，差值即为完整一帧的字节范围。
                    // 该原始帧用于复制传播（backlog + 推送从节点），避免重新序列化。
                    int readerIndexBefore = clientInfo.getInboundBuf().readerIndex();
                    Command command = protocolParser.parse(clientInfo.getInboundBuf());
                    if (command == null) {
                        break;
                    }
                    int readerIndexAfter = clientInfo.getInboundBuf().readerIndex();
                    byte[] rawRespFrame = null;
                    if (readerIndexAfter > readerIndexBefore) {
                        int frameLength = readerIndexAfter - readerIndexBefore;
                        rawRespFrame = new byte[frameLength];
                        clientInfo.getInboundBuf().getBytes(readerIndexBefore, rawRespFrame);
                    }
                    try {
                        TraceContext.startTrace();
                        processCommand(ctx, clientInfo, command, rawRespFrame);
                    } finally {
                        TraceContext.endTrace();
                    }
                }
            } finally {
                buffer.release();
            }
        }
    }
    
    /**
     * 检测RESP3的HELLO命令
     * RESP3客户端会发送HELLO命令来协商协议版本
     */
    private boolean detectResp3Hello(ByteBuf buffer, ChannelHandlerContext ctx, ClientInfo clientInfo) {
        if (!buffer.isReadable()) {
            return false;
        }
        
        // 保存当前缓冲区位置
        int startIndex = buffer.readerIndex();
        
        try {
            byte firstByte = buffer.readByte();
            
            if (firstByte == '*') {
                // 解析数组长度
                int length = parseInteger(buffer);
                if (length >= 2) {
                    if (buffer.readableBytes() > 0) {
                        byte type = buffer.readByte();
                        if (type == '$') {
                            // 解析命令长度
                            int cmdLength = parseInteger(buffer);
                            if (cmdLength > 0 && buffer.readableBytes() >= cmdLength + 2) {
                                // 读取命令名称
                                byte[] cmdBytes = new byte[cmdLength];
                                buffer.readBytes(cmdBytes);
                                String commandName = new String(cmdBytes, java.nio.charset.StandardCharsets.UTF_8);
                                
                                if ("HELLO".equalsIgnoreCase(commandName)) {
                                    // 读取协议版本参数
                                    if (buffer.readableBytes() > 0) {
                                        type = buffer.readByte();
                                        if (type == '$') {
                                            // 解析版本长度
                                            int versionLength = parseInteger(buffer);
                                            if (versionLength > 0 && buffer.readableBytes() >= versionLength + 2) {
                                                // 读取版本号
                                                byte[] versionBytes = new byte[versionLength];
                                                buffer.readBytes(versionBytes);
                                                String version = new String(versionBytes, java.nio.charset.StandardCharsets.UTF_8);
                                                
                                                if ("3".equals(version)) {
                                                    // 切换到RESP3
                                                    clientInfo.setProtocolVersion(ProtocolVersion.RESP3);
                                                    // 响应HELLO命令
                                                    java.util.Map<String, Object> response = new java.util.HashMap<>();
                                                    response.put("server", "Luban-RDS");
                                                    response.put("version", "1.0.0");
                                                    response.put("proto", 3);
                                                    response.put("id", ctx.channel().id().asLongText());
                                                    response.put("mode", "standalone");
                                                    response.put("role", "master");
                                                    response.put("modules", new java.util.ArrayList<>());
                                                    ByteBuf respBuffer = protocolParser.serialize(response);
                                                    if (respBuffer != null && respBuffer.isReadable()) {
                                                        ctx.writeAndFlush(respBuffer);
                                                    } else if (respBuffer != null) {
                                                        respBuffer.release();
                                                    }
                                                    return true;
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 发生异常时，重置缓冲区位置
            buffer.readerIndex(startIndex);
            return false;
        }
        
        // 没有检测到HELLO 3命令，重置缓冲区位置
        buffer.readerIndex(startIndex);
        return false;
    }
    
    /**
     * 解析整数，使用本地实现避免依赖protocolParser的public方法
     */
    private int parseInteger(ByteBuf buffer) {
        int result = 0;
        boolean negative = false;
        byte b;
        
        while (buffer.isReadable()) {
            b = buffer.readByte();
            if (b == '\r') {
                if (buffer.readableBytes() > 0 && buffer.readByte() == '\n') {
                    break;
                }
                return -1;
            } else if (b == '-') {
                negative = true;
            } else if (b >= '0' && b <= '9') {
                result = result * 10 + (b - '0');
            } else {
                return -1;
            }
        }
        
        return negative ? -result : result;
    }
    
private void processCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, Command command,
                            byte[] rawRespFrame) {
        try {
            String rawCommandName = command.getName();
            String commandName = rawCommandName != null ? rawCommandName.trim().toUpperCase() : "";
            String[] args = command.getArgs();
            logger.debug("Command: {} Args: {}", commandName, java.util.Arrays.toString(args));
            TOTAL_COMMANDS_PROCESSED.incrementAndGet();
            int currentDatabase = clientInfo.getCurrentDatabase();
            logger.debug("Processing command: {} In Pub/Sub mode: {}", commandName, clientInfo.isInPubSubMode());

            // MONITOR 钩子：在命令处理前提交到监控队列
            // 排除 MONITOR 命令本身，避免递归监控
            if (!"MONITOR".equals(commandName)) {
                MonitorManager.getInstance().submit(currentDatabase, ctx.channel().remoteAddress().toString(), commandName, args);
            }

            if ("WATCH".equals(commandName)) {
                logger.debug("Handling WATCH command");
                handleWatchCommand(ctx, clientInfo, currentDatabase, args);
                return;
            } else if ("UNWATCH".equals(commandName)) {
                logger.debug("Handling UNWATCH command");
                handleUnwatchCommand(ctx, clientInfo);
                return;
            } else if ("MULTI".equals(commandName)) {
                logger.debug("Handling MULTI command");
                handleMultiCommand(ctx, clientInfo);
                return;
            } else if ("EXEC".equals(commandName)) {
                logger.debug("Handling EXEC command");
                handleExecCommand(ctx, clientInfo);
                return;
            } else if ("DISCARD".equals(commandName)) {
                logger.debug("Handling DISCARD command");
                handleDiscardCommand(ctx, clientInfo);
                return;
            } else if ("QUIT".equals(commandName)) {
                logger.debug("Handling QUIT command");
                handleQuitCommand(ctx);
                return;
            } else if ("MONITOR".equals(commandName)) {
                logger.debug("Handling MONITOR command");
                handleMonitorCommand(ctx, clientInfo, args);
                return;
            }
            
            // ==================== 复制命令处理 ====================
            if ("SLAVEOF".equals(commandName) || "REPLICAOF".equals(commandName)) {
                logger.debug("Handling {} command", commandName);
                if (replicationCommandHandler != null) {
                    String response = replicationCommandHandler.handleWithChannel(ctx, args);
                    if (response != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(response);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                    }
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR replication not configured\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            } else if ("PSYNC".equals(commandName) || "SYNC".equals(commandName)) {
                logger.debug("Handling {} command", commandName);
                if (replicationCommandHandler != null) {
                    replicationCommandHandler.handleWithChannel(ctx, args);
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR replication not configured\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            } else if ("REPLCONF".equals(commandName)) {
                logger.debug("Handling REPLCONF command");
                if (replicationCommandHandler != null) {
                    String response = replicationCommandHandler.handleWithChannel(ctx, args);
                    if (response != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(response);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                    }
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR replication not configured\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            } else if ("WAIT".equals(commandName)) {
                logger.debug("Handling WAIT command");
                if (replicationCommandHandler != null) {
                    String response = replicationCommandHandler.handleWithChannel(ctx, args);
                    if (response != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(response);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                    }
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize(":0\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            } else if ("ASKING".equals(commandName)) {
                // 处理 ASKING 命令
                logger.debug("Handling ASKING command");
                String response = handleAsking(clientInfo);
                ByteBuf responseBuffer = protocolParser.serialize(response);
                if (responseBuffer != null && responseBuffer.isReadable()) {
                    ctx.writeAndFlush(responseBuffer);
                } else if (responseBuffer != null) {
                    responseBuffer.release();
                }
                return;
            } else if ("READONLY".equals(commandName)) {
                // 处理 READONLY 命令
                logger.debug("Handling READONLY command");
                String response = handleReadonly(clientInfo);
                ByteBuf responseBuffer = protocolParser.serialize(response);
                if (responseBuffer != null && responseBuffer.isReadable()) {
                    ctx.writeAndFlush(responseBuffer);
                } else if (responseBuffer != null) {
                    responseBuffer.release();
                }
                return;
            } else if ("READWRITE".equals(commandName)) {
                // 处理 READWRITE 命令
                logger.debug("Handling READWRITE command");
                String response = handleReadwrite(clientInfo);
                ByteBuf responseBuffer = protocolParser.serialize(response);
                if (responseBuffer != null && responseBuffer.isReadable()) {
                    ctx.writeAndFlush(responseBuffer);
                } else if (responseBuffer != null) {
                    responseBuffer.release();
                }
                return;
            } else if ("CLUSTER".equals(commandName) && clusterEnabled) {
                // 处理 CLUSTER 命令
                logger.debug("Handling CLUSTER command");
                if (clusterCommandHandler != null) {
                    String[] subArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
                    String response = clusterCommandHandler.handle(subArgs);
                    if (response != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(response);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                    }
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR cluster command not configured\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            } else if ("MIGRATE".equals(commandName) && clusterEnabled) {
                // 处理 MIGRATE 命令（通过集群总线将键迁移到目标节点）
                logger.debug("Handling MIGRATE command");
                if (migrateCommandHandler != null) {
                    String response = migrateCommandHandler.handle(args);
                    if (response != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(response);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                    }
                } else {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR migrate command not configured\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                }
                return;
            }
            if (clientInfo.isInMonitorMode()) {
                 // MONITOR clients only accept QUIT
                 ByteBuf errorBuffer = protocolParser.serialize("-ERR only (QUIT) allowed in MONITOR mode\r\n");
                 if (errorBuffer != null && errorBuffer.isReadable()) {
                     ctx.writeAndFlush(errorBuffer);
                 } else if (errorBuffer != null) {
                     errorBuffer.release();
                 }
                 return;
            }
            if (clientInfo.isInTransaction()) {
                if (!isKnownCommand(commandName)) {
                    ByteBuf b = protocolParser.serialize("-ERR unknown command '" + commandName + "'\r\n");
                    if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                    else if (b != null) b.release();
                    clientInfo.setTxQueueError(true);
                    return;
                }
                if (!validateMinArity(commandName, args.length)) {
                    ByteBuf b = protocolParser.serialize("-ERR wrong number of arguments for '" + commandName.toLowerCase() + "' command\r\n");
                    if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                    else if (b != null) b.release();
                    clientInfo.setTxQueueError(true);
                    return;
                }
                clientInfo.getTxQueue().add(command);
                ByteBuf b = protocolParser.serialize("QUEUED");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            if (commandHandler.isAuthRequired()) {
                boolean isAuthCommand = "AUTH".equals(commandName);
                boolean isAuthenticated = clientInfo.isAuthenticated();
                if (!isAuthenticated && !isAuthCommand) {
                    ByteBuf errorBuffer = protocolParser.serialize("-NOAUTH Authentication required.");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                    return;
                }
            }
            if (clientInfo.isInPubSubMode()) {
                if (!"SUBSCRIBE".equals(commandName)
                        && !"UNSUBSCRIBE".equals(commandName)
                        && !"PSUBSCRIBE".equals(commandName)
                        && !"PUNSUBSCRIBE".equals(commandName)
                        && !"SSUBSCRIBE".equals(commandName)
                        && !"SUNSUBSCRIBE".equals(commandName)
                        && !"PING".equals(commandName)
                        && !"PUBLISH".equals(commandName)) {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR only (SUBSCRIBE/PSUBSCRIBE/UNSUBSCRIBE/PUNSUBSCRIBE/SSUBSCRIBE/SUNSUBSCRIBE/PING/PUBLISH) allowed in Pub/Sub mode\r\n");
                    if (errorBuffer != null && errorBuffer.isReadable()) {
                        ctx.writeAndFlush(errorBuffer);
                    } else if (errorBuffer != null) {
                        errorBuffer.release();
                    }
                    return;
                }
            }
            if ("SUBSCRIBE".equals(commandName)) {
                handleSubscribe(ctx, args);
                return;
            } else if ("UNSUBSCRIBE".equals(commandName)) {
                handleUnsubscribe(ctx, args);
                return;
            } else if ("PSUBSCRIBE".equals(commandName)) {
                handlePsubscribe(ctx, args);
                return;
            } else if ("PUNSUBSCRIBE".equals(commandName)) {
                handlePunsubscribe(ctx, args);
                return;
            } else if ("SSUBSCRIBE".equals(commandName)) {
                handleSsubscribe(ctx, args);
                return;
            } else if ("SUNSUBSCRIBE".equals(commandName)) {
                handleSunsubscribe(ctx, args);
                return;
            } else if ("PUBLISH".equals(commandName)) {
                handlePublish(ctx, args);
                return;
            }
            
            // ==================== 集群重定向检查 ====================
            // 在命令执行前检查是否需要重定向。
            // 裁决逻辑收敛在 clusterRedirectForCommand（普通命令与 EXEC 事务内命令
            // 共用同一套检查，保证行为一致，N-16）：
            //   ① cluster_state 门控（P1-13：state=fail 拒绝所有键命令，仅
            //      cluster-allow-reads-when-down 下放行只读命令）
            //   ② slave 角色路由（P1-14：slave+写 → -READONLY；slave+读+未 READONLY → MOVED）
            //   ③ CROSSSLOT（多键命令全键同槽）
            //   ④ MOVED / ASK 重定向（以首键判定）
            if (clusterEnabled && commandRequiresKey(commandName)) {
                String redirect = clusterRedirectForCommand(commandName, args, clientInfo);
                if (redirect != null) {
                    writeRedirect(ctx, redirect);
                    return;
                }
            }

            // P1-12：写暂停门控。手动 failover 普通模式期间 master 已暂停写，
            // 此期间拒绝写命令（非只读），避免接管时丢失未暂停的写入。
            // 无暂停时 writePauseGate.isPaused() 为单次 volatile 读，零开销。
            if (writePauseGate.isPaused() && !isReadOnlyCommand(commandName != null
                    ? commandName.toUpperCase() : "")) {
                writeRedirect(ctx, "-LOADING cluster failover in progress, writes temporarily paused\r\n");
                return;
            }

            long startTime = System.nanoTime();
            Object response = commandHandler.handle(commandName, currentDatabase, args, memoryStore);
            long duration = (System.nanoTime() - startTime) / 1000; // microseconds
            SlowLogManager.getInstance().push(duration, java.util.Arrays.asList(args), ctx.channel().remoteAddress().toString(), clientInfo.getName());

            // 复制传播：非只读、非失败、非重定向的写命令传播到 backlog 与在线从节点。
            // 仅当存在原始 RESP 帧时尝试传播（内部调用 processCommand 时 rawRespFrame 为 null，跳过）。
            if (rawRespFrame != null && shouldPropagate(commandName, response)) {
                propagateCommand(rawRespFrame);
                // AOF 记录：与复制传播同位置、同写命令集合，保证 AOF 与 backlog 一致。
                // 非 AOF 模式下 recordCommand 为 default 空实现（no-op），注入 null 时跳过。
                if (persistService != null) {
                    persistService.recordCommand(rawRespFrame);
                }
            }
            
            if ("AUTH".equals(commandName) && clientInfo != null) {
                if (response instanceof String && ((String) response).startsWith("+OK")) {
                    clientInfo.setAuthenticated(true);
                }
            }
            if ("SELECT".equals(commandName) && args.length >= 2) {
                try {
                    int database = Integer.parseInt(args[1]);
                    if (clientInfo != null) {
                        clientInfo.setCurrentDatabase(database);
                    }
                } catch (NumberFormatException e) {
                }
            }
            
            // 处理阻塞命令结果
            if (response instanceof BlockingResult) {
                handleBlockingResult(ctx, clientInfo, (BlockingResult) response);
                return;
            }
            
            // 处理 LPUSH/RPUSH 后唤醒阻塞请求
            String upperCommand = commandName.toUpperCase();
            if (("LPUSH".equals(upperCommand) || "RPUSH".equals(upperCommand)) && args.length >= 3) {
                handlePushWakeUp(currentDatabase, args[1]);
            }
            
            ByteBuf responseBuffer = protocolParser.serialize(response);
            if (responseBuffer != null && responseBuffer.isReadable()) {
                ctx.writeAndFlush(responseBuffer);
            } else if (responseBuffer != null) {
                responseBuffer.release();
            }
        } catch (MovedToLeaderException e) {
            // 阶段 6：mesh 非 Leader 重定向。专用 catch 必须在通用 catch (Exception) 之前，
            // 否则会被吞成 "ERR Error handling command"。mesh gate 的真正接入（写命令走
            // MeshWriteGate 抛本异常）留阶段 12；此处仅在 mesh 模式（redirector != null）时处理，
            // 非 mesh 模式不会产生本异常，catch 形同虚设，对 cluster / standalone 零影响。
            if (meshClientRedirector != null) {
                String resp = meshClientRedirector.formatResponse(e);
                ByteBuf redirectBuffer = protocolParser.serialize(resp);
                if (redirectBuffer != null && redirectBuffer.isReadable()) {
                    ctx.writeAndFlush(redirectBuffer);
                } else if (redirectBuffer != null) {
                    redirectBuffer.release();
                }
                return;
            }
            // redirector 未注入：退化为通用错误处理（保留向下兼容）
            logger.error("MovedToLeaderException without meshClientRedirector configured", e);
            Object errorResponse = "MOVED redirector not configured";
            ByteBuf errorBuffer = protocolParser.serialize(errorResponse);
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
        } catch (Exception e) {
            logger.error("Error handling command", e);
            Object errorResponse = "ERR Error handling command";
            ByteBuf errorBuffer = protocolParser.serialize(errorResponse);
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
        }
    }
    

    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Exception caught in RedisServerHandler", cause);
        ctx.close();
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        TOTAL_CONNECTIONS_RECEIVED.incrementAndGet();
        CURRENT_CONNECTIONS.incrementAndGet();
        ClientInfo clientInfo = new ClientInfo(null);
        // Initialize inbound buffer using channel allocator for pooled memory support
        clientInfo.initInboundBuf(ctx.alloc());
        // Store ClientInfo in Channel attribute for thread-safe access
        ctx.channel().attr(CLIENT_INFO_KEY).set(clientInfo);
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        CURRENT_CONNECTIONS.decrementAndGet();
        // Unsubscribe all subscriptions when connection closes
        PUB_SUB_MANAGER.unsubscribeAll(ctx.channel());
        PUB_SUB_MANAGER.punsubscribeAll(ctx.channel());
        MonitorManager.getInstance().removeMonitor(ctx.channel());
        // Clean up ClientInfo from Channel attribute
        ClientInfo info = ctx.channel().attr(CLIENT_INFO_KEY).getAndSet(null);
        if (info != null && info.getInboundBuf() != null) {
            info.getInboundBuf().release();
        }
    }
    
    // 协议版本枚举
    private enum ProtocolVersion {
        RESP2, RESP3
    }
    
    // Client info class
    private static class ClientInfo {
        private final String name;
        private final long connectedTime;
        private int currentDatabase; // Current selected database
        private long lastActiveTime; // Last active time
        private boolean authenticated; // Whether authenticated
        private boolean inPubSubMode; // Whether in Pub/Sub mode
        private boolean inMonitorMode;
        private boolean inTransaction;
        private java.util.List<Command> txQueue;
        private boolean txQueueError;
        private final java.util.Map<String, Long> watchedVersions = new HashMap<>();
        private io.netty.buffer.ByteBuf inboundBuf; // Initialized in channelActive using channel allocator
        private ProtocolVersion protocolVersion = ProtocolVersion.RESP2; // Default to RESP2
        
        // 集群模式相关字段
        private boolean asking; // ASK 状态，用于槽位迁移过程中的重定向
        private boolean readonly; // READONLY 状态，允许从节点处理读请求
        
        public ClientInfo(String name) {
            this.name = name;
            this.connectedTime = System.currentTimeMillis();
            this.lastActiveTime = System.currentTimeMillis();
            this.currentDatabase = 0; // Default to database 0
            this.authenticated = false;
            this.inTransaction = false;
            this.txQueue = new ArrayList<>();
            this.txQueueError = false;
        }
        
        /**
         * Initialize inbound buffer using channel allocator
         * Should be called in channelActive
         * 
         * @param allocator ByteBuf allocator from channel
         */
        public void initInboundBuf(io.netty.buffer.ByteBufAllocator allocator) {
            if (this.inboundBuf == null) {
                this.inboundBuf = allocator.buffer(1024);
            }
        }
        
        public ProtocolVersion getProtocolVersion() {
            return protocolVersion;
        }
        
        public void setProtocolVersion(ProtocolVersion protocolVersion) {
            this.protocolVersion = protocolVersion;
        }
        
        public String getName() {
            return name;
        }
        
        public long getConnectedTime() {
            return connectedTime;
        }
        
        public int getCurrentDatabase() {
            return currentDatabase;
        }
        
        public void setCurrentDatabase(int currentDatabase) {
            this.currentDatabase = currentDatabase;
        }
        
        public long getLastActiveTime() {
            return lastActiveTime;
        }
        
        public void updateLastActiveTime() {
            this.lastActiveTime = System.currentTimeMillis();
        }
        
        public boolean isAuthenticated() {
            return authenticated;
        }
        
        public void setAuthenticated(boolean authenticated) {
            this.authenticated = authenticated;
        }
        
        public boolean isInPubSubMode() {
            return inPubSubMode;
        }
        
        public void setInPubSubMode(boolean inPubSubMode) {
            this.inPubSubMode = inPubSubMode;
        }

        public boolean isInMonitorMode() {
            return inMonitorMode;
        }

        public void setInMonitorMode(boolean inMonitorMode) {
            this.inMonitorMode = inMonitorMode;
        }
        
        public boolean isInTransaction() {
            return inTransaction;
        }
        
        public void setInTransaction(boolean inTransaction) {
            this.inTransaction = inTransaction;
        }
        
        public java.util.List<Command> getTxQueue() {
            return txQueue;
        }
        
        public boolean isTxQueueError() {
            return txQueueError;
        }
        
        public void setTxQueueError(boolean txQueueError) {
            this.txQueueError = txQueueError;
        }
        
        public java.util.Map<String, Long> getWatchedVersions() {
            return watchedVersions;
        }
        
        public void resetTransaction() {
            this.inTransaction = false;
            this.txQueue.clear();
            this.txQueueError = false;
            this.watchedVersions.clear();
        }
        
        public io.netty.buffer.ByteBuf getInboundBuf() {
            return inboundBuf;
        }
        
        // 集群模式相关 getter/setter
        public boolean isAsking() {
            return asking;
        }
        
        public void setAsking(boolean asking) {
            this.asking = asking;
        }
        
        public boolean isReadonly() {
            return readonly;
        }
        
        public void setReadonly(boolean readonly) {
            this.readonly = readonly;
        }
    }

    // 处理 SUBSCRIBE 命令
    private void handleSubscribe(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 2) {
            ByteBuf errorBuffer = protocolParser.serialize("-ERR wrong number of arguments for 'subscribe' command\r\n");
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
            return;
        }
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        for (int i = 1; i < args.length; i++) {
            String channelName = args[i];
            PUB_SUB_MANAGER.subscribe(ctx.channel(), channelName);
            int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
            ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                CMD_SUBSCRIBE, 
                channelName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                count));
            if (resp != null && resp.isReadable()) {
                ctx.writeAndFlush(resp);
            } else if (resp != null) {
                resp.release();
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(true);
        }
    }

    // Handle UNSUBSCRIBE command
    private void handleUnsubscribe(ChannelHandlerContext ctx, String[] args) {
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        if (args.length <= 1) {
            java.util.Set<String> subs = PUB_SUB_MANAGER.subscriptions(ctx.channel());
            if (subs.isEmpty()) {
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                    CMD_UNSUBSCRIBE, 
                    EMPTY_BYTES, 
                    PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel())));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            } else {
                for (String ch : subs.toArray(new String[0])) {
                    PUB_SUB_MANAGER.unsubscribe(ctx.channel(), ch);
                    int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
                    ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                        CMD_UNSUBSCRIBE, 
                        ch.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                        count));
                    if (resp != null && resp.isReadable()) {
                        ctx.writeAndFlush(resp);
                    } else if (resp != null) {
                        resp.release();
                    }
                }
            }
        } else {
            for (int i = 1; i < args.length; i++) {
                String channelName = args[i];
                PUB_SUB_MANAGER.unsubscribe(ctx.channel(), channelName);
                int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                    CMD_UNSUBSCRIBE, 
                    channelName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                    count));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) > 0);
        }
    }

    // 处理 PSUBSCRIBE 命令
    private void handlePsubscribe(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 2) {
            ByteBuf errorBuffer = protocolParser.serialize("-ERR wrong number of arguments for 'psubscribe' command\r\n");
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
            return;
        }
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        for (int i = 1; i < args.length; i++) {
            String pattern = args[i];
            PUB_SUB_MANAGER.psubscribe(ctx.channel(), pattern);
            int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
            ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                CMD_PSUBSCRIBE, 
                pattern.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                count));
            if (resp != null && resp.isReadable()) {
                ctx.writeAndFlush(resp);
            } else if (resp != null) {
                resp.release();
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(true);
        }
    }

    // Handle PUNSUBSCRIBE command
    private void handlePunsubscribe(ChannelHandlerContext ctx, String[] args) {
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        if (args.length <= 1) {
            java.util.Set<String> patterns = PUB_SUB_MANAGER.patternSubscriptions(ctx.channel());
            if (patterns.isEmpty()) {
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                    CMD_PUNSUBSCRIBE, 
                    EMPTY_BYTES, 
                    PUB_SUB_MANAGER.subscriptionCount(ctx.channel())));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            } else {
                for (String p : patterns.toArray(new String[0])) {
                    PUB_SUB_MANAGER.punsubscribe(ctx.channel(), p);
                    int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
                    ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                        CMD_PUNSUBSCRIBE, 
                        p.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                        count));
                    if (resp != null && resp.isReadable()) {
                        ctx.writeAndFlush(resp);
                    } else if (resp != null) {
                        resp.release();
                    }
                }
            }
        } else {
            for (int i = 1; i < args.length; i++) {
                String pattern = args[i];
                PUB_SUB_MANAGER.punsubscribe(ctx.channel(), pattern);
                int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel());
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                    CMD_PUNSUBSCRIBE, 
                    pattern.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                    count));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) > 0);
        }
    }

    // 处理 PUBLISH 命令
    private void handlePublish(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 3) {
            ByteBuf errorBuffer = protocolParser.serialize("-ERR wrong number of arguments for 'publish' command\r\n");
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
            return;
        }
        String channel = args[1];
        String message = args[2];
        
        int receivers = publishMessage(channel, message);
        
        ByteBuf countBuf = protocolParser.serialize(receivers);
        if (countBuf != null && countBuf.isReadable()) {
            ctx.writeAndFlush(countBuf);
        } else if (countBuf != null) {
            countBuf.release();
        }
    }

    // 处理 SSUBSCRIBE 命令
    private void handleSsubscribe(ChannelHandlerContext ctx, String[] args) {
        if (args.length < 2) {
            ByteBuf errorBuffer = protocolParser.serialize("-ERR wrong number of arguments for 'ssubscribe' command\r\n");
            if (errorBuffer != null && errorBuffer.isReadable()) {
                ctx.writeAndFlush(errorBuffer);
            } else if (errorBuffer != null) {
                errorBuffer.release();
            }
            return;
        }
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        for (int i = 1; i < args.length; i++) {
            String streamName = args[i];
            PUB_SUB_MANAGER.ssubscribe(ctx.channel(), streamName);
            int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel());
            ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                CMD_SSUBSCRIBE, 
                streamName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                count));
            if (resp != null && resp.isReadable()) {
                ctx.writeAndFlush(resp);
            } else if (resp != null) {
                resp.release();
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel()) > 0);
        }
    }

    // Handle SUNSUBSCRIBE command
    private void handleSunsubscribe(ChannelHandlerContext ctx, String[] args) {
        ClientInfo clientInfo = ctx.channel().attr(CLIENT_INFO_KEY).get();
        if (args.length <= 1) {
            java.util.Set<String> subs = PUB_SUB_MANAGER.streamSubscriptions(ctx.channel());
            if (subs.isEmpty()) {
                int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel());
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                    CMD_SUNSUBSCRIBE, 
                    EMPTY_BYTES, 
                    count));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            } else {
                for (String s : subs.toArray(new String[0])) {
                    PUB_SUB_MANAGER.sunsubscribe(ctx.channel(), s);
                    int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel());
                    ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                        CMD_SUNSUBSCRIBE, 
                        s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                        count));
                    if (resp != null && resp.isReadable()) {
                        ctx.writeAndFlush(resp);
                    } else if (resp != null) {
                        resp.release();
                    }
                }
            }
        } else {
            for (int i = 1; i < args.length; i++) {
                String streamName = args[i];
                PUB_SUB_MANAGER.sunsubscribe(ctx.channel(), streamName);
                int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) + PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel());
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList(
                        CMD_SUNSUBSCRIBE, 
                        streamName.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 
                        count));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.patternSubscriptionCount(ctx.channel()) > 0 || PUB_SUB_MANAGER.streamSubscriptionCount(ctx.channel()) > 0);
        }
    }
    
    // 获取服务器启动时间
    public static long getServerStartTime() {
        return SERVER_START_TIME;
    }
    
    // 获取总命令执行次数
    public static long getTotalCommandsProcessed() {
        return TOTAL_COMMANDS_PROCESSED.get();
    }
    
    // 获取总连接数
    public static long getTotalConnectionsReceived() {
        return TOTAL_CONNECTIONS_RECEIVED.get();
    }
    
    // Get current connection count
    public static int getCurrentConnections() {
        return (int) CURRENT_CONNECTIONS.get();
    }

    public static PubSubManager getPubSubManager() {
        return PUB_SUB_MANAGER;
    }
    
    private boolean isKnownCommand(String name) {
        return KNOWN_COMMANDS.contains(name.toUpperCase());
    }
    
    private boolean validateMinArity(String name, int argc) {
        String n = name.toUpperCase();
        if ("SET".equals(n)) return argc >= 3;
        if ("GET".equals(n)) return argc >= 2;
        if ("DEL".equals(n)) return argc >= 2;
        if ("EXISTS".equals(n)) return argc >= 2;
        if ("EXPIRE".equals(n)) return argc >= 3;
        if ("TTL".equals(n)) return argc >= 2;
        if ("MSET".equals(n)) return argc >= 3;
        if ("MGET".equals(n)) return argc >= 2;
        if ("HSET".equals(n)) return argc >= 4;
        if ("HSETNX".equals(n)) return argc >= 4;
        if ("HMSET".equals(n)) return argc >= 4;
        if ("HGET".equals(n)) return argc >= 3;
        if ("HMGET".equals(n)) return argc >= 3;
        if ("HDEL".equals(n)) return argc >= 3;
        if ("HEXISTS".equals(n)) return argc >= 3;
        if ("HGETALL".equals(n)) return argc >= 2;
        if ("HLEN".equals(n)) return argc >= 2;
        if ("HSCAN".equals(n)) return argc >= 3;
        if ("LPUSH".equals(n)) return argc >= 3;
        if ("RPUSH".equals(n)) return argc >= 3;
        if ("LPOP".equals(n)) return argc >= 2;
        if ("RPOP".equals(n)) return argc >= 2;
        if ("LLEN".equals(n)) return argc >= 2;
        if ("LRANGE".equals(n)) return argc >= 4;
        if ("SADD".equals(n)) return argc >= 3;
        if ("SREM".equals(n)) return argc >= 3;
        if ("SMEMBERS".equals(n)) return argc >= 2;
        if ("SISMEMBER".equals(n)) return argc >= 3;
        if ("SCARD".equals(n)) return argc >= 2;
        if ("ZADD".equals(n)) return argc >= 4;
        if ("ZREM".equals(n)) return argc >= 3;
        if ("ZRANGE".equals(n)) return argc >= 4;
        if ("ZSCORE".equals(n)) return argc >= 3;
        if ("ZCARD".equals(n)) return argc >= 2;
        if ("SELECT".equals(n)) return argc >= 2;
        if ("PING".equals(n)) return argc >= 1;
        if ("ECHO".equals(n)) return argc >= 2;
        if ("SCAN".equals(n)) return argc >= 2;
        if ("MEMORY".equals(n)) return argc >= 2;
        // Stream 命令参数验证
        if ("XADD".equals(n)) return argc >= 4;      // XADD key ID field value [field value ...]
        if ("XLEN".equals(n)) return argc >= 2;      // XLEN key
        if ("XRANGE".equals(n)) return argc >= 4;    // XRANGE key start end [COUNT count]
        if ("XREVRANGE".equals(n)) return argc >= 4; // XREVRANGE key end start [COUNT count]
        if ("XDEL".equals(n)) return argc >= 3;      // XDEL key ID [ID ...]
        if ("XTRIM".equals(n)) return argc >= 4;     // XTRIM key MAXLEN|MINID threshold
        if ("XREAD".equals(n)) return argc >= 4;     // XREAD STREAMS key ID
        if ("XINFO".equals(n)) return argc >= 3;     // XINFO STREAM key | XINFO GROUPS key | XINFO CONSUMERS key group
        if ("XGROUP".equals(n)) return argc >= 4;    // XGROUP CREATE key group ID | XGROUP DESTROY key group
        if ("XREADGROUP".equals(n)) return argc >= 6; // XREADGROUP GROUP group consumer STREAMS key ID
        if ("XACK".equals(n)) return argc >= 4;      // XACK key group ID [ID ...]
        if ("XPENDING".equals(n)) return argc >= 3;  // XPENDING key group
        if ("XCLAIM".equals(n)) return argc >= 6;    // XCLAIM key group consumer min-idle-time ID [ID ...]
        if ("XAUTOCLAIM".equals(n)) return argc >= 6; // XAUTOCLAIM key group consumer min-idle-time start
        return true;
    }

    // ==================== 复制传播辅助方法 ====================

    /**
     * 判断命令是否应被传播到复制 backlog 与从节点。
     * <p>
     * 不传播的情况：
     * <ul>
     *   <li>响应为 null（异常或空响应）</li>
     *   <li>响应为错误字符串（以 "-" 开头，如 -ERR / -MOVED / -ASK / -CLUSTERDOWN / -NOAUTH 等）</li>
     *   <li>命令为只读命令（GET / HGET / LRANGE 等）</li>
     * </ul>
     * 其余写命令均传播。
     * </p>
     *
     * @param commandName 命令名（原始大小写）
     * @param response    命令处理响应
     * @return true 表示应传播
     */
    private boolean shouldPropagate(String commandName, Object response) {
        if (response == null) {
            return false;
        }
        if (response instanceof String) {
            String resp = (String) response;
            if (resp.startsWith("-")) {
                // 错误响应不传播：-ERR / -MOVED / -ASK / -CLUSTERDOWN / -EXECABORT
                // / -NOPROTO / -LOADING / -READONLY / -NOAUTH 等
                return false;
            }
        }
        String upper = commandName != null ? commandName.toUpperCase() : "";
        if (isReadOnlyCommand(upper)) {
            return false;
        }
        return true;
    }

    /**
     * 从节点门控专用的读/写判定（仅 {@link #checkSlaveRedirect} 使用）。
     * <p>
     * 对 EVAL/EVALSHA 做脚本级只读分析（对齐 Redis 7 {@code evalGetCommandFlags}）：
     * 通过 {@link LuaScriptAnalyzer} 判定脚本内容是否只读，只读脚本视为读命令可在从节点执行，
     * 含写操作的脚本仍判为写。取不到脚本原文（如 EVALSHA 在本节点未缓存）保守判为写。
     * 其余命令沿用 {@link #isReadOnlyCommand} 的命令级白名单。
     * </p>
     * <p>
     * 注意：此方法仅影响"从节点是否拒绝/MOVED"，不影响 {@link #shouldPropagate}
     * 的复制传播决策——后者对 EVAL 仍默认按写传播，保证写脚本正确复制。
     * </p>
     *
     * @param commandName 命令名（大小写不敏感）
     * @param args        原始命令参数（EVAL/EVALSHA 用于提取脚本）
     * @return true 表示该命令在从节点上应被视为写（拒绝）
     */
    private boolean isWriteCommandOnSlave(String commandName, String[] args) {
        String cmdUpper = commandName != null ? commandName.toUpperCase() : "";
        if ("EVAL".equals(cmdUpper) || "EVALSHA".equals(cmdUpper)) {
            String script = commandHandler.resolveScriptBody(cmdUpper, args);
            // 取不到脚本（EVALSHA 未命中）保守判为写，触发 -READONLY，
            // 客户端收到后会回退到 EVAL（带原文），再次判定即可放行。
            return script == null || !LuaScriptAnalyzer.isReadOnlyScript(script);
        }
        return !isReadOnlyCommand(cmdUpper);
    }

    /**
     * 判断命令是否为只读命令（不应传播到复制 backlog 与 AOF）。
     * <p>
     * 默认返回 false（即视为写命令），保证未列出的命令默认被传播，
     * 避免遗漏新写命令导致从节点数据缺失或 AOF 数据缺失。
     * </p>
     * <p>
     * 注意：{@code SELECT} 不在此只读白名单中。SELECT 作为 db 上下文标记需传播到
     * backlog 与 AOF，使从节点与 AOF 重放时能正确切换当前 db（与 Redis 行为一致）。
     * </p>
     *
     * @param upper 命令名（大写）
     * @return true 表示只读命令
     */
    private boolean isReadOnlyCommand(String upper) {
        switch (upper) {
            case "GET":
            case "MGET":
            case "SUNION":
            case "SINTER":
            case "SDIFF":
            case "HGET":
            case "HGETALL":
            case "HMGET":
            case "HKEYS":
            case "HVALS":
            case "HLEN":
            case "HEXISTS":
            case "HSCAN":
            case "LINDEX":
            case "LRANGE":
            case "LLEN":
            case "SMEMBERS":
            case "SISMEMBER":
            case "SCARD":
            case "SSCAN":
            case "SRANDMEMBER":
            case "ZSCORE":
            case "ZRANGE":
            case "ZRANGEBYSCORE":
            case "ZRANGEBYLEX":
            case "ZREVRANGE":
            case "ZREVRANGEBYSCORE":
            case "ZCARD":
            case "ZCOUNT":
            case "ZRANK":
            case "ZREVRANK":
            case "ZSCAN":
            case "EXISTS":
            case "TYPE":
            case "TTL":
            case "PTTL":
            case "EXPIRETIME":
            case "PEXPIRETIME":
            case "OBJECT":
            case "MEMORY":
            case "INFO":
            case "DBSIZE":
            case "KEYS":
            case "SCAN":
            case "RANDOMKEY":
            case "STRLEN":
            case "GETRANGE":
            case "SUBSTR":
            case "BITCOUNT":
            case "GETBIT":
            case "BITPOS":
            case "PING":
            case "ECHO":
            case "AUTH":
            case "HELLO":
            case "CLIENT":
            case "COMMAND":
            case "CONFIG":
            case "DEBUG":
            case "SLOWLOG":
            case "MONITOR":
            case "CLUSTER":
            case "WAIT":
            case "PSYNC":
            case "SYNC":
            case "REPLCONF":
            case "SLAVEOF":
            case "REPLICAOF":
            case "MULTI":
            case "EXEC":
            case "DISCARD":
            case "WATCH":
            case "UNWATCH":
            case "LATENCY":
            case "QUIT":
            case "XLEN":
            case "XRANGE":
            case "XREVRANGE":
            case "XREAD":
            case "XREADGROUP":
            case "XINFO":
            case "XPENDING":
                return true;
            default:
                return false;
        }
    }

    /**
     * 将原始 RESP 帧传播到主节点复制 backlog 与在线从节点。
     * <p>
     * 通过复制协调器获取 {@link MasterReplicationManager} 并委托其
     * {@link MasterReplicationManager#propagateCommand(byte[])}。
     * 若协调器未注入或本节点为从节点，跳过传播，避免：
     * <ul>
     *   <li>直接调用 {@code getInstance()} 懒创建非预期的单例（I2）</li>
     *   <li>从节点误传播写命令（I3）</li>
     * </ul>
     * 异常被捕获并记录告警，不影响主命令处理流程。
     * </p>
     *
     * @param rawRespFrame 原始 RESP 帧字节
     */
    private void propagateCommand(byte[] rawRespFrame) {
        try {
            if (replicationCoordinator == null || replicationCoordinator.isSlave()) {
                return;
            }
            MasterReplicationManager manager = replicationCoordinator.getMasterManager();
            if (manager != null) {
                manager.propagateCommand(rawRespFrame);
            }
        } catch (Exception e) {
            logger.warn("命令传播失败（不影响客户端响应）", e);
        }
    }

    /**
     * 将 Command 序列化为 RESP 格式字节数组（用于事务命令传播）。
     * <p>
     * 事务（MULTI/EXEC）中入队的命令没有原始 RESP 帧可用（入队时仅保存了
     * {@link Command} 对象），因此 EXEC 成功后需重新序列化每条写命令以传播到
     * backlog 与从节点。
     * </p>
     *
     * @param args 命令参数数组（args[0] 为命令名）
     * @return RESP 格式字节数组
     */
    private byte[] serializeCommandToResp(String[] args) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(args.length).append("\r\n");
        for (String arg : args) {
            byte[] argBytes = arg.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
            sb.append("$").append(argBytes.length).append("\r\n");
            sb.append(arg).append("\r\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // 处理 WATCH 命令
    private void handleWatchCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, int currentDatabase, String[] args) {
        try {
            // 检查是否在事务中
            if (clientInfo.isInTransaction()) {
                ByteBuf b = protocolParser.serialize("-ERR WATCH inside MULTI is not allowed");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 检查参数
            if (args.length < 2) {
                ByteBuf b = protocolParser.serialize("-ERR wrong number of arguments for 'watch' command");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 限制监视的键数量，防止内存溢出
            if (args.length - 1 > 1000) {
                ByteBuf b = protocolParser.serialize("-ERR too many keys to watch");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }

            // N-16：集群模式下 WATCH 键的路由校验（对齐 Redis 7.2 watchCommand：
            // 每个被监视键经 getNodeByQuery 校验，键归属非本节点即 MOVED/ASK 重定向，
            // cluster 状态异常即 -CLUSTERDOWN，均不注册监视）。修复：在错误节点
            // WATCH 不存在的键导致 EXEC 永不中止。
            if (clusterEnabled && clusterConfig != null) {
                String stateGate = checkClusterStateGate("WATCH");
                if (stateGate != null) {
                    writeRedirect(ctx, stateGate);
                    return;
                }
                for (int i = 1; i < args.length; i++) {
                    String redirect = checkSlotAndRedirect(args[i]);
                    if (redirect == null) {
                        redirect = checkAskRedirect(args[i], clientInfo);
                    }
                    if (redirect != null) {
                        writeRedirect(ctx, redirect);
                        return;
                    }
                }
            }
            
            // 处理要监视的键
            for (int i = 1; i < args.length; i++) {
                String key = args[i];
                String keyWithDb = currentDatabase + "|" + key;
                long version = memoryStore.getKeyVersion(currentDatabase, key);
                clientInfo.getWatchedVersions().put(keyWithDb, version);
            }
            
            // 返回OK响应
            ByteBuf b = protocolParser.serialize(RdsResponseConstant.OK);
            if (b != null && b.isReadable()) {
                ctx.writeAndFlush(b);
            } else if (b != null) {
                b.release();
            }
        } catch (Exception e) {
            logger.error("Error handling WATCH command", e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling WATCH command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
        }
    }
    
    // 处理 UNWATCH 命令
    private void handleUnwatchCommand(ChannelHandlerContext ctx, ClientInfo clientInfo) {
        try {
            // 清除所有监视的键
            clientInfo.getWatchedVersions().clear();
            
            // 返回OK响应
            ByteBuf b = protocolParser.serialize(RdsResponseConstant.OK);
            if (b != null && b.isReadable()) {
                ctx.writeAndFlush(b);
            } else if (b != null) {
                b.release();
            }
        } catch (Exception e) {
            logger.error("Error handling UNWATCH command", e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling UNWATCH command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
        }
    }
    
    // 处理 MULTI 命令
    private void handleMultiCommand(ChannelHandlerContext ctx, ClientInfo clientInfo) {
        try {
            // 检查是否已经在事务中
            if (clientInfo.isInTransaction()) {
                ByteBuf b = protocolParser.serialize("-ERR MULTI calls can not be nested");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 开始事务
            clientInfo.setInTransaction(true);
            clientInfo.getTxQueue().clear();
            clientInfo.setTxQueueError(false);
            
            // 返回OK响应
            ByteBuf b = protocolParser.serialize(RdsResponseConstant.OK);
            if (b != null && b.isReadable()) {
                ctx.writeAndFlush(b);
            } else if (b != null) {
                b.release();
            }
        } catch (Exception e) {
            logger.error("Error handling MULTI command", e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling MULTI command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
        }
    }
    
    // 处理 EXEC 命令
    private void handleExecCommand(ChannelHandlerContext ctx, ClientInfo clientInfo) {
        long execStartTime = System.nanoTime();
        logger.debug("[EXEC] 处理中: client={}, db={}", 
            ctx.channel().remoteAddress(), clientInfo.getCurrentDatabase());
        
        try {
            if (!clientInfo.isInTransaction()) {
                logger.debug("[EXEC] 不在事务中，返回错误");
                ByteBuf b = protocolParser.serialize("-ERR EXEC without MULTI");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            if (clientInfo.isTxQueueError()) {
                logger.debug("[EXEC] 事务队列有错误，返回EXECABORT");
                ByteBuf b = protocolParser.serialize("-EXECABORT Transaction discarded because of previous errors.");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            boolean watchedChanged = false;
            java.util.Map<String, Long> watchedVersions = clientInfo.getWatchedVersions();
            logger.debug("[EXEC] 监视键数量: {}", watchedVersions.size());
            
            if (!watchedVersions.isEmpty()) {
                for (Map.Entry<String, Long> entry : watchedVersions.entrySet()) {
                    String keyWithDb = entry.getKey();
                    int sepIndex = keyWithDb.indexOf('|');
                    if (sepIndex == -1) continue;
                    
                    try {
                        int db = Integer.parseInt(keyWithDb.substring(0, sepIndex));
                        String key = keyWithDb.substring(sepIndex + 1);
                        long currentVersion = memoryStore.getKeyVersion(db, key);
                        long watchedVersion = entry.getValue();
                        if (currentVersion != watchedVersion) {
                            watchedChanged = true;
                            logger.debug("[EXEC] 监视键被修改: key={}", key);
                            break;
                        }
                    } catch (NumberFormatException ex) {
                        logger.debug("[EXEC] 键格式无效: {}", keyWithDb);
                        continue;
                    }
                }
            }
            
            if (watchedChanged) {
                logger.debug("[EXEC] 监视键被修改，返回Null Array");
                ByteBuf b = protocolParser.serialize("*-1\r\n");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            java.util.List<Command> txQueue = clientInfo.getTxQueue();
            int txQueueSize = txQueue.size();
            logger.debug("[EXEC] 事务队列大小: {}", txQueueSize);
            
            if (txQueueSize > 1000) {
                logger.warn("[EXEC] 事务队列过大: size={}", txQueueSize);
                ByteBuf b = protocolParser.serialize("-ERR transaction queue too large");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }

            // N-16：集群模式下事务执行前的路由裁决（对齐 Redis 7 execCommand：
            // 事务内任一命令需重定向（CLUSTERDOWN/READONLY/MOVED/ASK/CROSSSLOT）时，
            // 整个事务以该错误中止并丢弃）。修复：跨槽事务在错误节点静默执行
            // （写后键"消失"）、多键命令跨槽不校验、slave 上事务写被绕过。
            // 与普通命令共用 clusterRedirectForCommand，保证事务内外行为一致。
            if (clusterEnabled && clusterConfig != null) {
                for (Command cmd : txQueue) {
                    String cmdName = cmd.getName() != null ? cmd.getName().trim().toUpperCase() : "";
                    String redirect = clusterRedirectForCommand(cmdName, cmd.getArgs(), clientInfo);
                    if (redirect != null) {
                        logger.debug("[EXEC] 事务命令需集群重定向，中止整个事务: cmd={}, redirect={}",
                                cmdName, redirect.trim());
                        writeRedirect(ctx, redirect);
                        clientInfo.resetTransaction();
                        return;
                    }
                }
            }
            
            java.util.List<Object> results = new ArrayList<>(txQueueSize);
            long startTime = System.currentTimeMillis();
            
            for (Command cmd : txQueue) {
                if (System.currentTimeMillis() - startTime > 5000) {
                    logger.warn("[EXEC] 事务执行超时");
                    ByteBuf b = protocolParser.serialize("-ERR transaction execution timed out");
                    if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                    else if (b != null) b.release();
                    clientInfo.resetTransaction();
                    return;
                }
                
                String commandName = cmd.getName();
                String[] args = cmd.getArgs();
                logger.debug("[EXEC] 执行命令: {}", commandName);
                
                // 传递完整参数数组（包含命令名）
                long cmdStartTime = System.nanoTime();

                // MONITOR hook for transaction commands
                MonitorManager.getInstance().submit(clientInfo.getCurrentDatabase(), ctx.channel().remoteAddress().toString(), commandName, args);

                // 执行命令并获取原始结果
                Object result;
                if (commandName.equals("INCR") || commandName.equals("DECR") || commandName.equals("INCRBY") || commandName.equals("DECRBY")) {
                    // 对于递增递减命令，直接调用内存存储的方法获取原始Long结果
                    String key = args[1];
                    long increment = 0;
                    if (commandName.equals("INCR")) {
                        increment = 1;
                    } else if (commandName.equals("DECR")) {
                        increment = -1;
                    } else if (commandName.equals("INCRBY")) {
                        increment = Long.parseLong(args[2]);
                    } else if (commandName.equals("DECRBY")) {
                        increment = -Long.parseLong(args[2]);
                    }
                    result = memoryStore.incrby(clientInfo.getCurrentDatabase(), key, increment);
                    logger.debug("[EXEC] 数据处理 - 命令: {}, key: {}, increment: {}, 结果类型: {}, 结果值: {}", 
                        commandName, key, increment, result != null ? result.getClass().getName() : "null", result);
                } else if (commandName.equals("SET")) {
                    // 对于SET命令，直接调用内存存储的方法
                    String key = args[1];
                    String value = args[2];
                    memoryStore.set(clientInfo.getCurrentDatabase(), key, value);
                    result = "OK";
                    logger.debug("[EXEC] 数据处理 - 命令: SET, key: {}, 结果类型: String, 结果值: {}", key, result);
                } else if (commandName.equals("GET")) {
                    // 对于GET命令，直接调用内存存储的方法
                    String key = args[1];
                    result = memoryStore.get(clientInfo.getCurrentDatabase(), key);
                    logger.debug("[EXEC] 数据处理 - 命令: GET, key: {}, 结果类型: {}, 结果值: {}", 
                        key, result != null ? result.getClass().getName() : "null", result);
                } else if (commandName.equals("DEL")) {
                    // 对于DEL命令，直接调用内存存储的方法
                    String key = args[1];
                    result = memoryStore.del(clientInfo.getCurrentDatabase(), key) ? 1 : 0;
                    logger.debug("[EXEC] 数据处理 - 命令: DEL, key: {}, 结果类型: Integer, 结果值: {}", key, result);
                } else {
                    // 对于其他命令，使用命令处理器
                    Object response = commandHandler.handle(commandName, clientInfo.getCurrentDatabase(), args, memoryStore);
                    result = response;
                    logger.debug("[EXEC] 数据处理 - 命令: {}, 结果类型: {}, 结果值: {}", 
                        commandName, result != null ? result.getClass().getName() : "null", result);
                }
                long cmdDuration = (System.nanoTime() - cmdStartTime) / 1000; // microseconds
                SlowLogManager.getInstance().push(cmdDuration, java.util.Arrays.asList(args), ctx.channel().remoteAddress().toString(), clientInfo.getName());
                
                results.add(result);
                logger.debug("[EXEC] 结果已添加, 数量: {}", results.size());

                // 传播事务中的写命令（EXEC 成功后逐条传播）。
                // 事务入队命令没有原始 RESP 帧，故重新序列化后传播。
                if (shouldPropagate(commandName, result)) {
                    byte[] respFrame = serializeCommandToResp(args);
                    propagateCommand(respFrame);
                    // AOF 记录：与复制传播同位置、同写命令集合，保证 AOF 与 backlog 一致。
                    // 非 AOF 模式下 recordCommand 为 default 空实现（no-op），注入 null 时跳过。
                    if (persistService != null) {
                        persistService.recordCommand(respFrame);
                    }
                }

                if ("SELECT".equals(commandName) && args.length >= 2) {
                    try {
                        int database = Integer.parseInt(args[1]);
                        clientInfo.setCurrentDatabase(database);
                        logger.debug("[EXEC] 数据库已切换: {}", database);
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            logger.debug("[EXEC] 序列化结果, 数量: {}", results.size());
            
            // 直接构建RESP响应字符串
            StringBuilder respBuilder = new StringBuilder();
            respBuilder.append("*").append(results.size()).append("\r\n");
            
            for (Object result : results) {
                if (result == null) {
                    respBuilder.append("$-1\r\n");
                } else if (result instanceof Long || result instanceof Integer) {
                    // 整数响应
                    respBuilder.append(":").append(result).append("\r\n");
                } else if (result instanceof String) {
                    String str = (String) result;
                    // 检查是否已经是 RESP 格式的响应（以 +, -, :, $, * 开头）
                    if (isRespFormatted(str)) {
                        // 已经是 RESP 格式，直接追加
                        respBuilder.append(str);
                    } else if ("OK".equals(str)) {
                        // 简单字符串响应
                        respBuilder.append("+").append(str).append("\r\n");
                    } else {
                        // 批量字符串响应
                        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                        respBuilder.append("$").append(bytes.length).append("\r\n").append(str).append("\r\n");
                    }
                } else if (result instanceof byte[]) {
                    byte[] bytes = (byte[]) result;
                    respBuilder.append("$").append(bytes.length).append("\r\n");
                    respBuilder.append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
                    respBuilder.append("\r\n");
                } else {
                    // 其他类型，转换为字符串
                    String str = result.toString();
                    byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
                    respBuilder.append("$").append(bytes.length).append("\r\n").append(str).append("\r\n");
                }
            }
            
            String respStr = respBuilder.toString();
            logger.debug("[EXEC] 响应已发送, 长度={} bytes", respStr.length());
            
            ByteBuf b = Unpooled.directBuffer(respStr.length());
            b.writeBytes(respStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ctx.writeAndFlush(b);
            
            clientInfo.resetTransaction();
            long execEndTime = System.nanoTime();
            logger.debug("[EXEC] 事务完成, 耗时={} us", (execEndTime - execStartTime) / 1000);
        } catch (Exception e) {
            logger.error("Error handling EXEC command: {}", e.getMessage(), e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling EXEC command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
            clientInfo.resetTransaction();
        }
    }
    
/**
     * 检查字符串是否已经是 RESP 格式的响应
     * RESP 格式以 +, -, :, $, * 开头
     */
    private boolean isRespFormatted(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        char firstChar = str.charAt(0);
        return firstChar == '+' || firstChar == '-' || firstChar == ':' || firstChar == '$' || firstChar == '*';
    }
    
    // 处理 DISCARD 命令
    private void handleDiscardCommand(ChannelHandlerContext ctx, ClientInfo clientInfo) {
        try {
            // 检查是否在事务中
            if (!clientInfo.isInTransaction()) {
                ByteBuf b = protocolParser.serialize("-ERR DISCARD without MULTI");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 放弃事务
            clientInfo.resetTransaction();
            
            // 返回OK响应
            ByteBuf b = protocolParser.serialize(RdsResponseConstant.OK);
            if (b != null && b.isReadable()) {
                ctx.writeAndFlush(b);
            } else if (b != null) {
                b.release();
            }
        } catch (Exception e) {
            logger.error("Error handling DISCARD command", e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling DISCARD command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
        }
    }
    
    // 处理 MONITOR 命令
    private void handleMonitorCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, String[] args) {
        int db = -1;
        String pattern = null;
        
        // Support extended syntax: MONITOR [DB <dbid>] [MATCH <pattern>]
        for (int i = 1; i < args.length; i++) {
            if ("DB".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                try {
                    db = Integer.parseInt(args[i+1]);
                    i++;
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if ("MATCH".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                pattern = args[i+1];
                i++;
            }
        }
        
        MonitorManager.getInstance().addMonitor(ctx.channel(), db, pattern);
        clientInfo.setInMonitorMode(true);
    }

    // 处理 QUIT 命令
    private void handleQuitCommand(ChannelHandlerContext ctx) {
        ByteBuf b = protocolParser.serialize(RdsResponseConstant.OK);
        if (b != null && b.isReadable()) {
            ctx.writeAndFlush(b).addListener(ChannelFutureListener.CLOSE);
        } else if (b != null) {
            b.release();
            ctx.close();
        }
    }
    
    // ==================== 阻塞命令处理 ====================
    
    /**
     * 处理 LPUSH/RPUSH 后唤醒阻塞请求
     */
    private void handlePushWakeUp(int database, final String key) {
        final int db = database;
        final String k = key;
        
        BlockingRequestManager blockingMgr = BlockingRequestManager.getInstance();
        BlockingRequestManager.BlockingRequest request = blockingMgr.tryWakeUpWithPop(
            database, key,
            () -> memoryStore.lpop(db, k),
            () -> memoryStore.rpop(db, k)
        );
        
        if (request != null) {
            logger.debug("Woke up blocking request after push: key={}", key);
        }
    }
    
    /**
     * 发送阻塞响应给客户端
     */
    private void sendBlockingResponse(Channel channel, String key, String value) {
        if (!channel.isActive()) {
            return;
        }
        
        // 构建响应: *2\r\n$keyLen\r\nkey\r\n$valueLen\r\nvalue\r\n
        StringBuilder sb = new StringBuilder();
        sb.append("*2\r\n");
        byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        sb.append("$").append(keyBytes.length).append("\r\n");
        sb.append(new String(keyBytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        byte[] valueBytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        sb.append("$").append(valueBytes.length).append("\r\n");
        sb.append(new String(valueBytes, java.nio.charset.StandardCharsets.ISO_8859_1)).append("\r\n");
        
        ByteBuf responseBuffer = io.netty.buffer.Unpooled.copiedBuffer(sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        channel.writeAndFlush(responseBuffer);
        
        logger.debug("Sent blocking response: key={}, value={}", key, value);
    }
    
    /**
     * 处理阻塞命令结果
     * 
     * <p>当 BLPOP/BRPOP/XREAD/XREADGROUP 返回 BlockingResult 时调用此方法。
     */
    private void handleBlockingResult(ChannelHandlerContext ctx, ClientInfo clientInfo, BlockingResult blockingResult) {
        logger.debug("Handling blocking result: type={}, keys={}", blockingResult.getType(), blockingResult.getKeys());
        
        // 处理 List 类型的阻塞 (BLPOP/BRPOP)
        if (blockingResult.isListBlocking()) {
            handleListBlockingResult(ctx, clientInfo, blockingResult);
            return;
        }
        
        // 处理 Stream 类型的阻塞 (XREAD/XREADGROUP)
        handleStreamBlockingResult(ctx, clientInfo, blockingResult);
    }
    
    // 用于执行阻塞等待的线程池
    private static final java.util.concurrent.ExecutorService blockingExecutor = 
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "blocking-wait-executor");
            t.setDaemon(true);
            return t;
        });
    
    /**
     * 处理 List 类型的阻塞 (BLPOP/BRPOP)
     */
    private void handleListBlockingResult(ChannelHandlerContext ctx, ClientInfo clientInfo, BlockingResult blockingResult) {
        int database = blockingResult.getDatabase();
        final String[] keys = blockingResult.getKeyArray();
        long timeoutMs = blockingResult.getTimeout();
        
        // 转换阻塞类型
        BlockingRequestManager.BlockingType type = 
            blockingResult.isBLPop() 
                ? BlockingRequestManager.BlockingType.BLPOP 
                : BlockingRequestManager.BlockingType.BRPOP;
        
        // 添加阻塞请求
        final BlockingRequestManager blockingMgr = BlockingRequestManager.getInstance();
        final BlockingRequestManager.BlockingRequest request = blockingMgr.addRequest(ctx.channel(), database, keys, type, timeoutMs);
        
        logger.debug("Added list blocking request: database={}, keys={}, type={}, timeout={}ms", 
            database, java.util.Arrays.toString(keys), type, timeoutMs);
        
        // 在单独的线程中阻塞等待结果
        blockingExecutor.submit(() -> {
            try {
                // 计算等待超时
                long waitTimeout = timeoutMs > 0 ? timeoutMs : Long.MAX_VALUE;
                String[] result = request.future.get(waitTimeout, TimeUnit.MILLISECONDS);
                
                if (result != null) {
                    // 成功获取元素，发送响应
                    sendBlockingResponse(ctx.channel(), result[0], result[1]);
                } else {
                    // 超时或取消，发送 null 响应
                    ByteBuf nullBuffer = protocolParser.serialize(null);
                    if (nullBuffer != null && nullBuffer.isReadable()) {
                        ctx.writeAndFlush(nullBuffer);
                    } else if (nullBuffer != null) {
                        nullBuffer.release();
                    }
                }
            } catch (TimeoutException e) {
                // 超时
                request.completeTimeout();
                ByteBuf nullBuffer = protocolParser.serialize(null);
                if (nullBuffer != null && nullBuffer.isReadable()) {
                    ctx.writeAndFlush(nullBuffer);
                } else if (nullBuffer != null) {
                    nullBuffer.release();
                }
            } catch (InterruptedException e) {
                // 被中断
                Thread.currentThread().interrupt();
                request.cancel();
            } catch (ExecutionException e) {
                // 执行异常
                logger.error("Error waiting for blocking result", e);
                request.cancel();
            }
        });
    }
    
    /**
     * 处理 Stream 类型的阻塞 (XREAD/XREADGROUP)
     * 
     * <p>当 XREAD/XREADGROUP 返回 BlockingResult 时调用此方法。
     * 该方法会阻塞当前线程，直到有新消息到达或超时。
     */
    private void handleStreamBlockingResult(ChannelHandlerContext ctx, ClientInfo clientInfo, BlockingResult blockingResult) {
        logger.debug("Handling blocking result: {}", blockingResult);
        
        // 获取所有要监听的流
        List<String> keys = blockingResult.getKeys();
        List<StreamId> startIds = blockingResult.getStartIds();
        long timeout = blockingResult.getTimeout();
        int count = blockingResult.getCount();
        
        // 创建等待者列表
        List<StreamWaiterHolder> waiterHolders = new ArrayList<>();
        
        try {
            // 为每个流创建等待者
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i);
                StreamId startId = startIds.get(i);
                
                Stream stream = memoryStore.getStream(blockingResult.getDatabase(), key);
                if (stream == null) {
                    continue;
                }
                
                // 创建 Condition 和等待者
                stream.lockForWait();
                try {
                    StreamWaiter waiter = new StreamWaiter(startId, stream.newCondition());
                    stream.addWaiter(waiter);
                    waiterHolders.add(new StreamWaiterHolder(stream, waiter));
                } finally {
                    stream.unlockAfterWait();
                }
            }
            
            // 如果没有有效的流，直接返回空结果
            if (waiterHolders.isEmpty()) {
                sendBlockingTimeoutResponse(ctx, blockingResult);
                return;
            }
            
            // 计算超时时间
            long deadline = timeout > 0 ? System.currentTimeMillis() + timeout : Long.MAX_VALUE;
            
            // 等待新消息
            while (true) {
                // 检查是否有新消息
                Object result = checkForNewMessages(ctx, blockingResult);
                if (result != null) {
                    // 有新消息，返回结果
                    ByteBuf responseBuffer = protocolParser.serialize(result);
                    if (responseBuffer != null && responseBuffer.isReadable()) {
                        ctx.writeAndFlush(responseBuffer);
                    } else if (responseBuffer != null) {
                        responseBuffer.release();
                    }
                    return;
                }
                
                // 检查超时
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    // 超时，返回空结果
                    sendBlockingTimeoutResponse(ctx, blockingResult);
                    return;
                }
                
                // 等待通知
                boolean gotNotification = false;
                for (StreamWaiterHolder holder : waiterHolders) {
                    holder.stream.lockForWait();
                    try {
                        if (holder.waiter.isNotified()) {
                            gotNotification = true;
                            break;
                        }
                        // 等待一小段时间
                        try {
                            gotNotification = holder.waiter.getCondition().await(
                                Math.min(remaining, 100), TimeUnit.MILLISECONDS);
                            if (gotNotification) {
                                break;
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            sendBlockingTimeoutResponse(ctx, blockingResult);
                            return;
                        }
                    } finally {
                        holder.stream.unlockAfterWait();
                    }
                    
                    // 重新计算剩余时间
                    remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) {
                        sendBlockingTimeoutResponse(ctx, blockingResult);
                        return;
                    }
                }
                
                // 如果收到通知，检查是否有新消息
                if (gotNotification) {
                    result = checkForNewMessages(ctx, blockingResult);
                    if (result != null) {
                        ByteBuf responseBuffer = protocolParser.serialize(result);
                        if (responseBuffer != null && responseBuffer.isReadable()) {
                            ctx.writeAndFlush(responseBuffer);
                        } else if (responseBuffer != null) {
                            responseBuffer.release();
                        }
                        return;
                    }
                }
            }
        } finally {
            // 清理等待者
            for (StreamWaiterHolder holder : waiterHolders) {
                holder.stream.lockForWait();
                try {
                    holder.stream.removeWaiter(holder.waiter);
                } finally {
                    holder.stream.unlockAfterWait();
                }
            }
        }
    }
    
    /**
     * 检查是否有新消息
     *
     * @return 如果有新消息返回响应字符串，否则返回 null
     */
    private Object checkForNewMessages(ChannelHandlerContext ctx, BlockingResult blockingResult) {
        List<String> keys = blockingResult.getKeys();
        List<StreamId> startIds = blockingResult.getStartIds();
        int count = blockingResult.getCount();
        
        List<String> resultKeys = new ArrayList<>();
        List<List<StreamEntry>> resultEntries = new ArrayList<>();
        
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            StreamId startId = startIds.get(i);
            
            Stream stream = memoryStore.getStream(blockingResult.getDatabase(), key);
            if (stream == null) {
                continue;
            }
            
            // 从指定 ID 之后读取消息
            List<StreamEntry> entries;
            if (count > 0) {
                entries = stream.getRangeFrom(startId, true, count);
            } else {
                entries = stream.getRangeFrom(startId, true, Integer.MAX_VALUE);
            }
            
            if (!entries.isEmpty()) {
                resultKeys.add(key);
                resultEntries.add(entries);
            }
        }
        
        if (!resultEntries.isEmpty()) {
            return buildBlockingResponse(blockingResult, resultKeys, resultEntries);
        }
        
        return null;
    }
    
    /**
     * 构建阻塞命令的响应
     */
    private String buildBlockingResponse(BlockingResult blockingResult, 
                                         List<String> keys, 
                                         List<List<StreamEntry>> entriesList) {
        StringBuilder result = new StringBuilder();
        result.append("*").append(keys.size()).append("\r\n");
        
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<StreamEntry> entries = entriesList.get(i);
            
            // 每个流是一个包含 2 个元素的数组：[key, entries]
            result.append("*2\r\n");
            
            // key
            byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            result.append("$").append(keyBytes.length).append("\r\n").append(key).append("\r\n");
            
            // entries
            result.append("*").append(entries.size()).append("\r\n");
            for (StreamEntry entry : entries) {
                result.append(encodeStreamEntry(entry));
            }
        }
        
        return result.toString();
    }
    
    /**
     * 编码 StreamEntry 为 RESP 格式
     */
    private String encodeStreamEntry(StreamEntry entry) {
        StringBuilder sb = new StringBuilder();
        
        // 消息条目是一个包含 2 个元素的数组：[id, field-value pairs]
        sb.append("*2\r\n");
        
        // 第一个元素：ID
        String idStr = entry.getId().toString();
        sb.append("$").append(idStr.length()).append("\r\n").append(idStr).append("\r\n");
        
        // 第二个元素：字段值对数组
        java.util.LinkedHashMap<String, String> fields = entry.getFieldsInternal();
        int fieldCount = fields.size() * 2;
        sb.append("*").append(fieldCount).append("\r\n");
        
        for (Map.Entry<String, String> fieldEntry : fields.entrySet()) {
            // 字段名
            String fieldName = fieldEntry.getKey();
            sb.append("$").append(fieldName.length()).append("\r\n")
              .append(fieldName).append("\r\n");
            
            // 字段值
            String fieldValue = fieldEntry.getValue();
            if (fieldValue == null) {
                sb.append("$-1\r\n");
            } else {
                sb.append("$").append(fieldValue.length()).append("\r\n")
                  .append(fieldValue).append("\r\n");
            }
        }
        
        return sb.toString();
    }
    
    /**
     * 发送阻塞超时响应
     */
    private void sendBlockingTimeoutResponse(ChannelHandlerContext ctx, BlockingResult blockingResult) {
        // 超时返回 Null Array（与 Redis 行为一致）
        ByteBuf responseBuffer = protocolParser.serialize("*-1\r\n");
        if (responseBuffer != null && responseBuffer.isReadable()) {
            ctx.writeAndFlush(responseBuffer);
        } else if (responseBuffer != null) {
            responseBuffer.release();
        }
    }
    
    /**
     * 等待者持有者（用于管理流和等待者的关联）
     */
    private static class StreamWaiterHolder {
        final Stream stream;
        final StreamWaiter waiter;
        
        StreamWaiterHolder(Stream stream, StreamWaiter waiter) {
            this.stream = stream;
            this.waiter = waiter;
        }
    }
    
    // 辅助方法：将ByteBuf转换为十六进制字符串
    private String bytesToHex(ByteBuf buf) {
        StringBuilder sb = new StringBuilder();
        while (buf.isReadable()) {
            byte b = buf.readByte();
            sb.append(String.format("%02x ", b));
            if (b >= 32 && b <= 126) {
                sb.append('(').append((char) b).append(')');
            }
        }
        buf.release();
        return sb.toString();
    }
    
    // ==================== 集群重定向相关方法 ====================
    
    /**
     * 不需要键的命令集合（这些命令不需要重定向检查）
     */
    private static final java.util.Set<String> NO_KEY_COMMANDS = new HashSet<>();
    static {
        // 管理命令
        NO_KEY_COMMANDS.add("PING");
        NO_KEY_COMMANDS.add("ECHO");
        NO_KEY_COMMANDS.add("QUIT");
        NO_KEY_COMMANDS.add("AUTH");
        NO_KEY_COMMANDS.add("COMMAND");
        NO_KEY_COMMANDS.add("INFO");
        NO_KEY_COMMANDS.add("CONFIG");
        NO_KEY_COMMANDS.add("TIME");
        NO_KEY_COMMANDS.add("BGSAVE");
        NO_KEY_COMMANDS.add("BGREWRITEAOF");
        NO_KEY_COMMANDS.add("LASTSAVE");
        NO_KEY_COMMANDS.add("SLOWLOG");
        NO_KEY_COMMANDS.add("MEMORY");
        NO_KEY_COMMANDS.add("MONITOR");
        
        // 集群命令
        NO_KEY_COMMANDS.add("ASKING");
        NO_KEY_COMMANDS.add("READONLY");
        NO_KEY_COMMANDS.add("READWRITE");
        NO_KEY_COMMANDS.add("CLUSTER");
        
        // 事务命令
        NO_KEY_COMMANDS.add("MULTI");
        NO_KEY_COMMANDS.add("EXEC");
        NO_KEY_COMMANDS.add("DISCARD");
        NO_KEY_COMMANDS.add("WATCH");
        NO_KEY_COMMANDS.add("UNWATCH");
        
        // 数据库命令
        NO_KEY_COMMANDS.add("SELECT");
        NO_KEY_COMMANDS.add("FLUSHDB");
        NO_KEY_COMMANDS.add("FLUSHALL");
        NO_KEY_COMMANDS.add("DBSIZE");
        NO_KEY_COMMANDS.add("KEYS");
        NO_KEY_COMMANDS.add("SCAN");
        
        // Pub/Sub 命令
        NO_KEY_COMMANDS.add("SUBSCRIBE");
        NO_KEY_COMMANDS.add("UNSUBSCRIBE");
        NO_KEY_COMMANDS.add("PSUBSCRIBE");
        NO_KEY_COMMANDS.add("PUNSUBSCRIBE");
        NO_KEY_COMMANDS.add("PUBLISH");
        NO_KEY_COMMANDS.add("SSUBSCRIBE");
        NO_KEY_COMMANDS.add("SUNSUBSCRIBE");
        
        // 脚本命令
        NO_KEY_COMMANDS.add("SCRIPT");
        // NOTE: EVAL/EVALSHA 不在此列表中。集群模式下它们需要按 KEYS[1] 所在 slot
        // 进行 MOVED/ASK 重定向，并对多 key 脚本校验 CROSSSLOT（所有 KEYS 必须同 slot），
        // 以对齐 Redis 原生集群语义。EVAL 的 key 提取由 extractKeyFromCommand 的专用分支
        // 处理，CROSSSLOT 校验在集群重定向检查块中完成。
    }
    
    /**
     * 检查命令是否需要键参数
     *
     * @param commandName 命令名称
     * @return 是否需要键参数
     */
    private boolean commandRequiresKey(String commandName) {
        return !NO_KEY_COMMANDS.contains(commandName.toUpperCase());
    }
    
    /**
     * 从命令参数中提取首个键（MOVED/ASK 重定向使用）。
     *
     * <p>委托 {@link #extractKeysFromCommand(String, String[])}，返回键列表的首个元素，
     * 行为与历史 {@code extractKeyFromCommand} 保持一致，便于向后兼容。
     *
     * @param commandName 命令名称
     * @param args        命令参数（包含命令名）
     * @return 首个键名，若命令无键返回 null
     */
    private String extractKeyFromCommand(String commandName, String[] args) {
        List<String> keys = extractKeysFromCommand(commandName, args);
        return (keys == null || keys.isEmpty()) ? null : keys.get(0);
    }

    /**
     * 从命令参数中提取所有涉及槽位归属的键列表。
     *
     * <p>对齐 Redis 7 集群语义：多键命令需先校验所有键 hash 到同一 slot（CROSSSLOT），
     * 再以首键判定 MOVED/ASK。本方法按命令类型枚举参与槽位校验的键位置：
     * <ul>
     *   <li>trailing-keys：MGET/DEL/EXISTS/UNLINK/TOUCH/SUNION/SINTER/SDIFF —— args[1..end]</li>
     *   <li>K-V pairs：MSET/MSETNX —— args[1]/args[3]/args[5]...（奇数下标，从 1 起）</li>
     *   <li>DEST + source：SDIFFSTORE/SINTERSTORE/SUNIONSTORE/ZUNIONSTORE/ZINTERSTORE
     *       —— args[1] + args[3..end]（args[2] 为 numkeys）</li>
     *   <li>BITOP op destkey srckey... —— args[2] + args[3..end]（args[1] 为 op）</li>
     *   <li>SORT key [BY pattern] [STORE dstkey] —— 简化：仅返回源键 args[1]</li>
     *   <li>SRC+DST：RENAME/RENAMENX/COPY —— args[1] + args[2]</li>
     *   <li>SMOVE src dst member —— args[1] + args[2]</li>
     *   <li>EVAL/EVALSHA —— KEYS[1..numkeys]（与历史 checkCrossSlotForScript 互补）</li>
     *   <li>其余命令 —— args[1]（单键）</li>
     * </ul>
     *
     * @param commandName 命令名称
     * @param args        命令参数（包含命令名）
     * @return 键列表，可能为空或 null（无键命令）；不重复保留原始顺序中的键
     */
    private List<String> extractKeysFromCommand(String commandName, String[] args) {
        if (args == null || args.length < 2) {
            return null;
        }
        String cmd = commandName.toUpperCase();

        // trailing-keys 命令：keys = args[1..end]
        if ("MGET".equals(cmd) || "DEL".equals(cmd) || "EXISTS".equals(cmd)
                || "UNLINK".equals(cmd) || "TOUCH".equals(cmd)
                || "SUNION".equals(cmd) || "SINTER".equals(cmd) || "SDIFF".equals(cmd)) {
            return trailingKeys(args, 1);
        }

        // K-V pairs 命令：MSET/MSETNX keys = args[1]/args[3]/args[5]...
        if ("MSET".equals(cmd) || "MSETNX".equals(cmd)) {
            List<String> keys = new ArrayList<>();
            for (int i = 1; i < args.length; i += 2) {
                keys.add(args[i]);
            }
            return keys;
        }

        // DEST + source keys: *STORE destination numkeys key [key ...]
        if ("ZUNIONSTORE".equals(cmd) || "ZINTERSTORE".equals(cmd)) {
            // args[1]=dest, args[2]=numkeys, args[3..3+numkeys-1]=source keys
            return destPlusSourceAfterNumkeys(args);
        }
        if ("SDIFFSTORE".equals(cmd) || "SINTERSTORE".equals(cmd) || "SUNIONSTORE".equals(cmd)) {
            // args[1]=dest, args[2..end]=source keys
            return destPlusTrailing(args, 2);
        }

        // BITOP op destkey srckey...: args[1]=op, args[2]=dest, args[3..end]=srckey
        if ("BITOP".equals(cmd)) {
            return destPlusTrailing(args, 2);
        }

        // SORT key [BY pattern] [STORE dstkey]：简化处理，仅以 args[1] 作为参与 slot 校验的键。
        // Redis 集群允许 SORT 同 slot 键；STORE 目标通常与源同 slot（hash tag）才被允许，
        // 此处不解析 STORE 目标以保持与历史行为一致，避免引入额外复杂度。
        if ("SORT".equals(cmd)) {
            return trailingKeys(args, 1);
        }

        // SRC + DST 二键命令
        if ("RENAME".equals(cmd) || "RENAMENX".equals(cmd) || "COPY".equals(cmd)
                || "SMOVE".equals(cmd)) {
            List<String> keys = new ArrayList<>();
            if (args.length >= 2) {
                keys.add(args[1]);
            }
            if (args.length >= 3) {
                keys.add(args[2]);
            }
            return keys;
        }

        // EVAL/EVALSHA: <cmd> <script|sha1> <numkeys> <key...> <arg...>
        if ("EVAL".equals(cmd) || "EVALSHA".equals(cmd)) {
            return scriptKeys(args);
        }

        // XREAD/XREADGROUP: [COUNT n] [BLOCK ms] [NOACK] STREAMS key [key...] id [id...]
        // 键位于 STREAMS 关键字之后的"前半段"（键与 ID 各占一半，键在前）。
        // 修复：默认单键分支取 args[1]（"COUNT"/"GROUP"/"STREAMS" 等关键字）作路由键，
        // 导致集群中已实现命令被 MOVED 到无关节点静默返回空、多流 CROSSSLOT 不校验。
        // 对齐 Redis xreadGetKeys。
        if ("XREAD".equals(cmd) || "XREADGROUP".equals(cmd)) {
            return streamsKeys(args);
        }

        // XINFO: XINFO [STREAM key] [GROUPS key] [CONSUMERS key group] [HELP]
        // STREAM/GROUPS/CONSUMERS 子命令的键为 args[2]；HELP 无键。
        // 修复：默认分支取 args[1]（子命令名）作路由键。对齐 Redis xinfoGetKeys。
        if ("XINFO".equals(cmd)) {
            return xinfoKeys(args);
        }

        // 默认单键命令：args[1]
        List<String> keys = new ArrayList<>();
        keys.add(args[1]);
        return keys;
    }

    /** 收集 args[start..end] 作为键列表。 */
    private List<String> trailingKeys(String[] args, int start) {
        List<String> keys = new ArrayList<>();
        for (int i = start; i < args.length; i++) {
            keys.add(args[i]);
        }
        return keys;
    }

    /** STORE 型：dest = args[destIdx] + source = args[destIdx+1..end]。 */
    private List<String> destPlusTrailing(String[] args, int destIdx) {
        List<String> keys = new ArrayList<>();
        if (args.length > destIdx) {
            keys.add(args[destIdx]);
            keys.addAll(trailingKeys(args, destIdx + 1));
        }
        return keys;
    }

    /** ZUNIONSTORE/ZINTERSTORE：dest = args[1] + source = args[3..3+numkeys-1]。 */
    private List<String> destPlusSourceAfterNumkeys(String[] args) {
        List<String> keys = new ArrayList<>();
        if (args.length < 3) {
            return keys;
        }
        if (args.length >= 2) {
            keys.add(args[1]); // dest
        }
        int numkeys;
        try {
            numkeys = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return keys;
        }
        if (numkeys <= 0) {
            return keys;
        }
        for (int i = 3; i < args.length && i < 3 + numkeys; i++) {
            keys.add(args[i]);
        }
        return keys;
    }

    /** EVAL/EVALSHA：依据 numkeys 收集 KEYS[1..numkeys]。 */
    private List<String> scriptKeys(String[] args) {
        List<String> keys = new ArrayList<>();
        if (args.length < 3) {
            return keys;
        }
        int numkeys;
        try {
            numkeys = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return keys;
        }
        if (numkeys <= 0) {
            return keys;
        }
        for (int i = 3; i < args.length && i < 3 + numkeys; i++) {
            keys.add(args[i]);
        }
        return keys;
    }

    /**
     * XREAD/XREADGROUP：提取 STREAMS 关键字之后的键。
     * <p>
     * 语法：XREAD [COUNT n] [BLOCK ms] STREAMS key [key ...] id [id ...]。
     * STREAMS 之后的参数键与 ID 各占一半且键在前，因此取前半段为键。
     * 与 Redis 的 xreadGetKeys 语义一致（按参数位置切分，不解析 ID 值）。
     * STREAMS 关键字缺失或参数个数为奇数（语法错误）时返回空列表，
     * 由命令处理器后续报语法错误。
     * </p>
     */
    private List<String> streamsKeys(String[] args) {
        List<String> keys = new ArrayList<>();
        int streamsIdx = -1;
        for (int i = 1; i < args.length; i++) {
            if ("STREAMS".equalsIgnoreCase(args[i])) {
                streamsIdx = i;
                break;
            }
        }
        if (streamsIdx < 0) {
            return keys;
        }
        int streamArgs = args.length - streamsIdx - 1;
        int numKeys = streamArgs / 2;
        for (int i = 0; i < numKeys; i++) {
            keys.add(args[streamsIdx + 1 + i]);
        }
        return keys;
    }

    /**
     * XINFO：按子命令提取键（对齐 Redis xinfoGetKeys）。
     * <p>
     * XINFO STREAM key / XINFO GROUPS key / XINFO CONSUMERS key group → 键为 args[2]；
     * XINFO HELP 无键。
     * </p>
     */
    private List<String> xinfoKeys(String[] args) {
        List<String> keys = new ArrayList<>();
        if (args.length < 3) {
            return keys;
        }
        String sub = args[1].toUpperCase();
        if ("STREAM".equals(sub) || "GROUPS".equals(sub) || "CONSUMERS".equals(sub)) {
            keys.add(args[2]);
        }
        return keys;
    }

    /**
     * 校验多键命令的所有键是否落在同一 hash slot。
     *
     * <p>对齐 Redis 7：当键数 ≥ 2 且不全在同一 slot 时，返回 {@code -CROSSSLOT}
     * 错误响应；键数 ≤ 1 时返回 null。
     *
     * @param keys 命令涉及的键列表
     * @return null 表示同 slot（或单键以下无需校验），否则为 CROSSSLOT 错误响应
     */
    private String checkCrossSlot(List<String> keys) {
        if (keys == null || keys.size() <= 1) {
            return null;
        }
        int firstSlot = SlotUtils.keyHashSlot(keys.get(0));
        for (int i = 1; i < keys.size(); i++) {
            if (SlotUtils.keyHashSlot(keys.get(i)) != firstSlot) {
                return "-CROSSSLOT Keys in request don't hash to the same slot\r\n";
            }
        }
        return null;
    }

    /**
     * 将重定向/错误响应序列化并写回客户端，统一处理 buffer 释放。
     *
     * <p>原 dispatch 处四处重复的 {@code protocolParser.serialize(resp)} + 可读则写回 / 否则释放
     * 模式抽取为该辅助方法，消除重复。
     *
     * @param ctx        通道上下文
     * @param rawResp    已构造好的 RESP 字符串（含尾部 {@code \r\n}）
     */
    private void writeRedirect(ChannelHandlerContext ctx, String rawResp) {
        ByteBuf buffer = protocolParser.serialize(rawResp);
        if (buffer != null && buffer.isReadable()) {
            ctx.writeAndFlush(buffer);
        } else if (buffer != null) {
            buffer.release();
        }
    }

    /**
     * 检查 EVAL/EVALSHA 脚本的多个 KEYS 是否属于同一 slot。
     * <p>
     * Redis 集群要求脚本中所有 KEYS 必须落在同一 hash slot（通常通过 {@code {tag}} 保证），
     * 否则返回 {@code -CROSSSLOT} 错误，拒绝执行。此方法对齐该语义。
     * </p>
     *
     * @param commandName 命令名称（EVAL/EVALSHA）
     * @param args        命令参数（包含命令名）
     * @return null 表示所有 KEYS 同 slot（或非脚本命令/单 key/无 key），否则返回 CROSSSLOT 错误响应
     */
    private String checkCrossSlotForScript(String commandName, String[] args) {
        if (args == null || args.length < 3) {
            return null;
        }
        String cmd = commandName.toUpperCase();
        if (!"EVAL".equals(cmd) && !"EVALSHA".equals(cmd)) {
            return null;
        }
        // EVAL/EVALSHA 格式: <cmd> <script|sha1> <numkeys> <key...> <arg...>
        int numkeys;
        try {
            numkeys = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        if (numkeys <= 1) {
            // 单 key 或无 key，无需 CROSSSLOT 校验
            return null;
        }
        if (args.length < 3 + numkeys) {
            return null;
        }
        int firstSlot = SlotUtils.keyHashSlot(args[3]);
        for (int i = 4; i < 3 + numkeys; i++) {
            if (SlotUtils.keyHashSlot(args[i]) != firstSlot) {
                return "-CROSSSLOT Keys in request don't hash to the same slot\r\n";
            }
        }
        return null;
    }

    /**
     * P1-13：集群状态门控。
     * <p>
     * 对齐 Redis 7 {@code getNodeByQuery}：当 {@code cluster_state == fail} 时，
     * 所有键命令默认返回 {@code -CLUSTERDOWN The cluster is down}；仅在开启
     * {@code cluster-allow-reads-when-down}（{@link #clusterAllowReadsWhenDown}）
     * 且命令为只读时放行。无键命令（PING/INFO/CLUSTER 等）不受此门控约束。
     * </p>
     *
     * @param commandName 命令名称
     * @return null 表示放行，否则返回 {@code -CLUSTERDOWN} 响应字符串
     */
    private String checkClusterStateGate(String commandName) {
        if (!clusterEnabled || clusterConfig == null) {
            return null;
        }
        if (clusterConfig.isClusterOk()) {
            return null;
        }
        // state=fail：默认拒绝；仅当允许"宕机读"且命令只读时放行。
        if (clusterAllowReadsWhenDown && isReadOnlyCommand(commandName.toUpperCase())) {
            return null;
        }
        return "-CLUSTERDOWN The cluster is down\r\n";
    }

    /**
     * P1-14：从节点角色路由门控。
     * <p>
     * 对齐 Redis 7 {@code getNodeByQuery} 中 slave 的处理：
     * <ul>
     *   <li>本节点是 slave，且命令涉及的槽位属于其 master：
     *     <ul>
     *       <li>写命令 → {@code -READONLY You can't write against a read only replica.}
     *           （slave 永不接受写，无论 READONLY 标志）</li>
     *       <li>读命令 + 客户端未声明 READONLY → MOVED 到 master
     *           （slave 不擅自服务读，除非客户端显式允许）</li>
     *       <li>读命令 + 已声明 READONLY → 放行，本 slave 服务读</li>
     *     </ul>
     *   </li>
     *   <li>本节点非 slave、或槽位不属于本 slave 的 master → 返回 null，
     *       交由 {@link #checkSlotAndRedirect} 统一裁决 MOVED。</li>
     * </ul>
     * 判定仅当槽位 owner 是本 slave 的 master 时才介入，避免误拦远程 master 槽位
     * （那应由后续 checkSlotAndRedirect 返回 MOVED 到正确远程节点）。
     * </p>
     *
     * @param commandName 命令名称（用于判定读/写）
     * @param key         首键
     * @param args        原始命令参数（用于 EVAL/EVALSHA 的脚本只读性判定）
     * @param clientInfo  客户端信息
     * @return null 表示放行，否则返回 -READONLY 或 -MOVED 响应字符串
     */
    private String checkSlaveRedirect(String commandName, String key, String[] args, ClientInfo clientInfo) {
        if (!clusterEnabled || clusterConfig == null) {
            return null;
        }
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isSlave()) {
            return null;
        }
        String masterId = me.getMasterNodeId();
        if (masterId == null) {
            return null;
        }

        int slot = SlotUtils.keyHashSlot(key);
        String ownerNodeId = clusterConfig.getSlotOwner(slot);
        // 仅当槽位 owner 是本 slave 的 master 才介入；否则交由后续 checkSlotAndRedirect 处理。
        if (ownerNodeId == null || !masterId.equals(ownerNodeId)) {
            return null;
        }

        boolean isWrite = isWriteCommandOnSlave(commandName, args);
        if (isWrite) {
            // slave 永不接受写命令（即使客户端声明了 READONLY）。
            return "-READONLY You can't write against a read only replica.\r\n";
        }

        // 读命令：仅当客户端声明 READONLY 时本 slave 服务读；否则 MOVED 到 master。
        if (clientInfo != null && clientInfo.isReadonly()) {
            return null;
        }
        ClusterNode master = clusterConfig.getNode(masterId);
        if (master == null) {
            return null;
        }
        return "-MOVED " + slot + " " + master.getIp() + ":" + master.getPort() + "\r\n";
    }

    /**
     * 检查键所属槽位是否在本节点（MOVED 重定向检查）
     *
     * @param key 键名
     * @return null 表示在本节点，否则返回 MOVED 响应字符串
     */
    private String checkSlotAndRedirect(String key) {
        if (!clusterEnabled || slotManager == null) {
            return null;
        }
        
        int slot = SlotUtils.keyHashSlot(key);

        // IMPORTING 槽位（本节点是迁移目标）：跳过 MOVED 判定，放行交由 checkAskRedirect
        // 裁决（带 ASKING 放行 / 无 ASKING 返回 ASK 到源节点）。对齐 Redis getNodeByQuery：
        // importing 槽位虽不归属本节点，但允许 ASKING 客户端访问；否则与源节点的 ASK
        // 重定向形成 A↔B 无限循环。
        if (slotManager.isSlotImporting(slot)) {
            return null;
        }

        // 检查槽位是否已分配。
        // ⚠ 优先从 clusterConfig（Gossip 维护的权威槽位表）读取，因为 slotManager
        // (DefaultSlotManager) 的 slotOwners[] 数组仅在启动时从 clusterConfig 同步一次，
        // 后续 Gossip 更新不会同步到 slotManager，导致从节点始终返回 CLUSTERDOWN。
        // 当 clusterConfig 不可用或 slot 在其中未分配时，回退到 slotManager。
        String ownerNodeId = null;
        if (clusterConfig != null) {
            ownerNodeId = clusterConfig.getSlotOwner(slot);
        }
        if (ownerNodeId == null) {
            ownerNodeId = slotManager.getSlotOwner(slot);
        }
        if (ownerNodeId == null) {
            return "-CLUSTERDOWN Hash slot not served\r\n";
        }

        // 检查槽位是否在本节点。
        // ⚠ 本地性判定必须与 owner 来源一致：owner 已从 clusterConfig（权威表）读取，
        // 本地性也应由 clusterConfig 推导——ownerNodeId == 本节点 id。
        // 原实现读 slotManager.isSlotLocal（mySlots BitSet），但该 BitSet 仅在启动时
        // 从 clusterConfig 同步一次，Gossip 学到的远程 failover/reshard 槽位变更不会回写它，
        // 导致槽位已被远程接管后本节点仍"越权服务"（P1-1 双表分叉根因）。
        // 仅当 clusterConfig 不可用时才回退到 slotManager.isSlotLocal。
        boolean slotLocal;
        if (clusterConfig != null) {
            String myNodeId = clusterConfig.getMyNodeId();
            slotLocal = myNodeId != null && myNodeId.equals(ownerNodeId);
            // P1-14：slave 在 READONLY 下可服务其 master 的槽位读。checkSlaveRedirect
            // 已对写/非 READONLY 读做了拒绝或 MOVED；此处为放行的 READONLY 读，
            // 把 master 的槽位视为"本地"以避免重复 MOVED。slave 角色且 owner == master
            // 即视为本地可达。
            if (!slotLocal) {
                ClusterNode me = clusterConfig.getMyNode();
                if (me != null && me.isSlave() && ownerNodeId.equals(me.getMasterNodeId())) {
                    slotLocal = true;
                }
            }
        } else {
            slotLocal = slotManager.isSlotLocal(slot);
        }

        if (!slotLocal) {
            if (clusterConfig == null) {
                return "-CLUSTERDOWN No cluster config\r\n";
            }

            ClusterNode owner = clusterConfig.getNode(ownerNodeId);

            if (owner == null) {
                return "-CLUSTERDOWN Slot owner not found\r\n";
            }

            return "-MOVED " + slot + " " + owner.getIp() + ":" + owner.getPort() + "\r\n";
        }

        return null;
    }
    
    /**
     * 检查 ASK 重定向
     * 用于槽位迁移过程中
     * <p>
     * 判定顺序对齐 Redis 7 getNodeByQuery：
     * <ol>
     *   <li>IMPORTING 槽位：带 ASKING → 放行并消费一次性标志；不带 ASKING → ASK
     *       重定向回源节点（客户端应回源取键）。</li>
     *   <li>MIGRATING 槽位：键存在 → 放行（消费 ASKING 模拟 Redis 命令执行后的标志
     *       清除）；键已迁走 → 无条件 ASK 到目标节点。注意 MIGRATING 状态下 ASKING
     *       无效（ASKING 仅对 IMPORTING 槽位生效），修复旧实现"带 ASKING 即放行"
     *       导致已迁移键返回 nil、写命令产生孤儿键的缺陷。</li>
     *   <li>普通槽位（归属本节点）：ASKING 无路由效果，按"命令执行后清除一次性标志"
     *       语义消费，防止残留污染后续命令。</li>
     * </ol>
     * </p>
     *
     * @param key        键名
     * @param clientInfo 客户端信息
     * @return null 表示正常处理，否则返回 ASK 响应字符串
     */
    private String checkAskRedirect(String key, ClientInfo clientInfo) {
        if (!clusterEnabled || slotManager == null) {
            return null;
        }

        int slot = SlotUtils.keyHashSlot(key);

        // (1) IMPORTING 槽位
        if (slotManager.isSlotImporting(slot)) {
            if (clientInfo != null && clientInfo.isAsking()) {
                clientInfo.setAsking(false);
                return null;
            }
            String sourceNodeId = slotManager.getImportingSource(slot);
            if (sourceNodeId == null || clusterConfig == null) {
                return null;
            }
            ClusterNode source = clusterConfig.getNode(sourceNodeId);
            if (source == null) {
                return null;
            }
            return "-ASK " + slot + " " + source.getIp() + ":" + source.getPort() + "\r\n";
        }

        // (2) MIGRATING 槽位
        if (slotManager.isSlotMigrating(slot)) {
            if (memoryStore.exists(clientInfo != null ? clientInfo.getCurrentDatabase() : 0, key)) {
                if (clientInfo != null && clientInfo.isAsking()) {
                    clientInfo.setAsking(false);
                }
                return null; // 键还在本节点，正常处理
            }

            // 键已迁移，返回 ASK 重定向（不消费 ASKING：MIGRATING 状态下 ASKING 无效）
            String targetNodeId = slotManager.getMigratingTarget(slot);
            if (targetNodeId == null || clusterConfig == null) {
                return null;
            }

            ClusterNode target = clusterConfig.getNode(targetNodeId);
            if (target == null) {
                return null;
            }

            return "-ASK " + slot + " " + target.getIp() + ":" + target.getPort() + "\r\n";
        }

        // (3) 普通槽位：消费 ASKING 一次性标志（对齐 Redis 命令执行后清除）
        if (clientInfo != null && clientInfo.isAsking()) {
            clientInfo.setAsking(false);
        }
        return null;
    }

    /**
     * 对单条键命令做集群路由裁决（N-16：普通命令与 EXEC 事务内命令共用）。
     * <p>
     * 对齐 Redis 7 getNodeByQuery 的裁决顺序：
     * <ol>
     *   <li>cluster_state 门控：state=fail 时拒绝所有键命令（-CLUSTERDOWN）；仅在开启
     *       cluster-allow-reads-when-down 时放行只读命令。</li>
     *   <li>slave 角色路由：slave + 写命令 → -READONLY（slave 永不可写）；
     *       slave + 读命令 + 未声明 READONLY → MOVED 到 master。</li>
     *   <li>CROSSSLOT：多键命令全键须落在同一 slot（EVAL/EVALSHA 走脚本专用校验）。</li>
     *   <li>MOVED / ASK：以首键判定槽位归属与迁移状态重定向。</li>
     * </ol>
     * 任一检查失败即返回需写回客户端的错误/重定向响应；全部通过返回 null。
     * </p>
     *
     * @param commandName 命令名称
     * @param args        命令参数（包含命令名）
     * @param clientInfo  客户端信息
     * @return null 表示放行；否则返回 -CLUSTERDOWN/-READONLY/-MOVED/-ASK/-CROSSSLOT 响应字符串
     */
    private String clusterRedirectForCommand(String commandName, String[] args, ClientInfo clientInfo) {
        if (!clusterEnabled || clusterConfig == null || !commandRequiresKey(commandName)) {
            return null;
        }
        List<String> keys = extractKeysFromCommand(commandName, args);
        if (keys == null || keys.isEmpty()) {
            return null;
        }

        // ① cluster_state 门控
        String stateGate = checkClusterStateGate(commandName);
        if (stateGate != null) {
            return stateGate;
        }

        // ② slave 角色路由
        String slaveGate = checkSlaveRedirect(commandName, keys.get(0), args, clientInfo);
        if (slaveGate != null) {
            return slaveGate;
        }

        // ③ CROSSSLOT 校验
        if ("EVAL".equalsIgnoreCase(commandName) || "EVALSHA".equalsIgnoreCase(commandName)) {
            String scriptCross = checkCrossSlotForScript(commandName, args);
            if (scriptCross != null) {
                return scriptCross;
            }
        } else {
            String crossSlot = checkCrossSlot(keys);
            if (crossSlot != null) {
                return crossSlot;
            }
        }

        // ④ MOVED / ASK 重定向（以首键判定）
        String key = keys.get(0);
        String redirect = checkSlotAndRedirect(key);
        if (redirect != null) {
            return redirect;
        }
        return checkAskRedirect(key, clientInfo);
    }
    
    /**
     * 处理 ASKING 命令
     * 设置连接的 ASK 状态，允许访问导入中的槽位
     *
     * @param clientInfo 客户端信息
     * @return 响应字符串
     */
    private String handleAsking(ClientInfo clientInfo) {
        if (clientInfo != null) {
            clientInfo.setAsking(true);
        }
        return "+OK\r\n";
    }
    
    /**
     * 处理 READONLY 命令
     * 允许从节点处理读请求
     *
     * @param clientInfo 客户端信息
     * @return 响应字符串
     */
    private String handleReadonly(ClientInfo clientInfo) {
        if (clientInfo != null) {
            clientInfo.setReadonly(true);
        }
        return "+OK\r\n";
    }
    
    /**
     * 处理 READWRITE 命令
     * 取消只读模式
     *
     * @param clientInfo 客户端信息
     * @return 响应字符串
     */
    private String handleReadwrite(ClientInfo clientInfo) {
        if (clientInfo != null) {
            clientInfo.setReadonly(false);
        }
        return "+OK\r\n";
    }
}
