package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
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
import java.util.concurrent.TimeUnit;
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
                "SET","GET","INCR","DECR","INCRBY","DECRBY","APPEND","STRLEN",
                "MSET", "MGET",
                "HSET","HSETNX","HMSET","HGET","HMGET","HGETALL","HDEL","HEXISTS","HLEN",
                "HSCAN",
                "LPUSH","RPUSH","LPOP","RPOP","LLEN","LRANGE",
                "SADD","SREM","SMEMBERS","SISMEMBER","SCARD",
                "ZADD","ZRANGE","ZSCORE","ZREM","ZCARD",
                "EXISTS","DEL","EXPIRE","TTL","FLUSHALL","TYPE","PING","ECHO","SELECT","INFO","SCAN","DBSIZE","TIME",
                "AUTH",
                "SUBSCRIBE","UNSUBSCRIBE","PUBLISH","PSUBSCRIBE","PUNSUBSCRIBE","SSUBSCRIBE","SUNSUBSCRIBE",
                "EVAL","EVALSHA","SCRIPT","SCRIPT LOAD","SCRIPT EXISTS","SCRIPT FLUSH","SCRIPT KILL",
                "MULTI","EXEC","DISCARD","WATCH","UNWATCH","QUIT",
                "MEMORY", "MONITOR",
                "SLOWLOG",
                // Stream 命令
                "XADD","XLEN","XRANGE","XREVRANGE","XDEL","XTRIM","XREAD","XINFO",
                "XGROUP","XREADGROUP","XACK","XPENDING","XCLAIM","XAUTOCLAIM"
        };
        for (String n : names) KNOWN_COMMANDS.add(n);
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

    static {
        String[] names = new String[]{
                "PING","ECHO","SELECT","QUIT","AUTH",
                "GET","SET","SETNX","GETSET","MGET","MSET","MSETNX","STRLEN","APPEND","INCR","DECR","INCRBY","DECRBY",
                "LPUSH","RPUSH","LPOP","RPOP","LLEN","LRANGE","LINDEX","LSET","LREM","LTRIM",
                "SADD","SREM","SISMEMBER","SMEMBERS","SCARD","SPOP","SRANDMEMBER","SMOVE","SUNION","SINTER","SDIFF",
                "HSET","HGET","HMSET","HMGET","HGETALL","HKEYS","HVALS","HLEN","HEXISTS","HDEL","HINCRBY",
                "ZADD","ZREM","ZSCORE","ZRANK","ZREVRANK","ZRANGE","ZREVRANGE","ZRANGEBYSCORE","ZCARD","ZCOUNT","ZINCRBY",
                "EXPIRE","PEXPIRE","TTL","PTTL","PERSIST","TYPE","KEYS","DEL","EXISTS","DBSIZE","FLUSHDB","FLUSHALL",
                "SUBSCRIBE","UNSUBSCRIBE","PUBLISH","PSUBSCRIBE","PUNSUBSCRIBE","SSUBSCRIBE","SUNSUBSCRIBE",
                "EVAL","EVALSHA","SCRIPT","SCRIPT LOAD","SCRIPT EXISTS","SCRIPT FLUSH","SCRIPT KILL",
                "MULTI","EXEC","DISCARD","WATCH","UNWATCH","QUIT",
                "INFO", "MONITOR",
                "MEMORY", "MEMORY USAGE", "MEMORY STATS", "MEMORY PURGE", "MEMORY DOCTOR", "MEMORY MALLOC-STATS", "MEMORY HELP",
                "HSCAN",
                // Stream 命令
                "XADD","XLEN","XRANGE","XREVRANGE","XDEL","XTRIM","XREAD","XINFO",
                "XGROUP","XREADGROUP","XACK","XPENDING","XCLAIM","XAUTOCLAIM"
        };
        for (String n : names) KNOWN_COMMANDS.add(n);
        
        // Register PubSubService
        com.janeluo.luban.rds.common.context.ServerContext.setPubSubService((channel, message) -> publishMessage(channel, message));
    }
    
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
    
    public RedisServerHandler(MemoryStore memoryStore, DefaultCommandHandler commandHandler, RedisProtocolParser protocolParser) {
        this(memoryStore, commandHandler, protocolParser, 0);
    }
    
    public RedisServerHandler(MemoryStore memoryStore, DefaultCommandHandler commandHandler, RedisProtocolParser protocolParser, int timeout) {
        this.memoryStore = memoryStore;
        this.commandHandler = commandHandler;
        this.protocolParser = protocolParser;
        this.timeout = timeout;
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
                    Command command = protocolParser.parse(clientInfo.getInboundBuf());
                    if (command == null) {
                        break;
                    }
                    try {
                        TraceContext.startTrace();
                        processCommand(ctx, clientInfo, command);
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
    
private void processCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, Command command) {
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

            long startTime = System.nanoTime();
            Object response = commandHandler.handle(commandName, currentDatabase, args, memoryStore);
            long duration = (System.nanoTime() - startTime) / 1000; // microseconds
            SlowLogManager.getInstance().push(duration, java.util.Arrays.asList(args), ctx.channel().remoteAddress().toString(), clientInfo.getName());
            
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
            
            ByteBuf responseBuffer = protocolParser.serialize(response);
            if (responseBuffer != null && responseBuffer.isReadable()) {
                ctx.writeAndFlush(responseBuffer);
            } else if (responseBuffer != null) {
                responseBuffer.release();
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
        logger.info("[EXEC] 入口 - 时间戳: {}, 客户端: {}, 数据库: {}", 
            execStartTime, ctx.channel().remoteAddress(), clientInfo.getCurrentDatabase());
        
        try {
            // 检查是否在事务中
            if (!clientInfo.isInTransaction()) {
                logger.warn("[EXEC] 分支判断 - 不在事务中，返回错误");
                ByteBuf b = protocolParser.serialize("-ERR EXEC without MULTI");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 检查事务队列是否有错误
            if (clientInfo.isTxQueueError()) {
                logger.warn("[EXEC] 分支判断 - 事务队列有错误，返回EXECABORT");
                ByteBuf b = protocolParser.serialize("-EXECABORT Transaction discarded because of previous errors.");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            // 检查监视的键是否被修改
            boolean watchedChanged = false;
            java.util.Map<String, Long> watchedVersions = clientInfo.getWatchedVersions();
            logger.debug("[EXEC] 分支判断 - 监视键数量: {}", watchedVersions.size());
            
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
                        logger.debug("[EXEC] 监视键检查 - key: {}, db: {}, 当前版本: {}, 监视版本: {}", 
                            key, db, currentVersion, watchedVersion);
                        if (currentVersion != watchedVersion) {
                            watchedChanged = true;
                            logger.info("[EXEC] 分支判断 - 监视键被修改: {}", key);
                            break;
                        }
                    } catch (NumberFormatException ex) {
                        logger.warn("[EXEC] 异常捕获 - 键格式错误: {}", keyWithDb);
                        continue;
                    }
                }
            }
            
            // 如果监视的键被修改，放弃事务（返回 RESP Null Array）
            if (watchedChanged) {
                logger.info("[EXEC] 分支判断 - 监视键被修改，返回Null Array");
                ByteBuf b = protocolParser.serialize("*-1\r\n");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            // 执行事务队列中的命令
            java.util.List<Command> txQueue = clientInfo.getTxQueue();
            int txQueueSize = txQueue.size();
            logger.info("[EXEC] 数据处理 - 事务队列大小: {}", txQueueSize);
            
            // 限制事务队列大小，防止内存溢出
            if (txQueueSize > 1000) {
                logger.error("[EXEC] 分支判断 - 事务队列过大: {}", txQueueSize);
                ByteBuf b = protocolParser.serialize("-ERR transaction queue too large");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            // 收集事务执行结果
            java.util.List<Object> results = new ArrayList<>(txQueueSize);
            long startTime = System.currentTimeMillis();
            
            for (Command cmd : txQueue) {
                // 检查执行时间，防止死循环
                if (System.currentTimeMillis() - startTime > 5000) { // 5秒超时
                    logger.error("[EXEC] 分支判断 - 事务执行超时");
                    ByteBuf b = protocolParser.serialize("-ERR transaction execution timed out");
                    if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                    else if (b != null) b.release();
                    clientInfo.resetTransaction();
                    return;
                }
                
                String commandName = cmd.getName();
                String[] args = cmd.getArgs();
                logger.debug("[EXEC] 数据处理 - 执行命令: {}, 参数: {}", commandName, java.util.Arrays.toString(args));
                
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
                logger.debug("[EXEC] 数据处理 - 添加结果到列表, 当前结果数量: {}", results.size());
                
                // 特殊处理SELECT命令，更新客户端数据库状态
                if ("SELECT".equals(commandName) && args.length >= 2) {
                    try {
                        int database = Integer.parseInt(args[1]);
                        clientInfo.setCurrentDatabase(database);
                        logger.info("[EXEC] 数据处理 - 切换数据库: {}", database);
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            // 返回执行结果 - 直接构建RESP响应字符串
            logger.info("[EXEC] 数据处理 - 准备序列化结果, 结果数量: {}", results.size());
            for (int i = 0; i < results.size(); i++) {
                Object r = results.get(i);
                logger.info("[EXEC] 数据处理 - 结果[{}]: 类型={}, 值={}", 
                    i, r != null ? r.getClass().getName() : "null", r);
            }
            
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
            logger.info("[EXEC] 出口 - 发送响应 (str): {}", respStr.replace("\r\n", "\\r\\n"));
            
            ByteBuf b = Unpooled.directBuffer(respStr.length());
            b.writeBytes(respStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ctx.writeAndFlush(b);
            
            // 重置事务状态
            clientInfo.resetTransaction();
            long execEndTime = System.nanoTime();
            logger.info("[EXEC] 出口 - 事务完成, 耗时: {} ns", (execEndTime - execStartTime));
        } catch (Exception e) {
            logger.error("Error handling EXEC command", e);
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
     * 处理阻塞命令结果
     * 
     * <p>当 XREAD/XREADGROUP 返回 BlockingResult 时调用此方法。
     * 该方法会阻塞当前线程，直到有新消息到达或超时。
     */
    private void handleBlockingResult(ChannelHandlerContext ctx, ClientInfo clientInfo, BlockingResult blockingResult) {
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
}
