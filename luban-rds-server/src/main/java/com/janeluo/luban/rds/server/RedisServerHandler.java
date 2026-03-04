package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.slowlog.SlowLogManager;
import com.janeluo.luban.rds.protocol.Command;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelId;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class RedisServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger logger = LoggerFactory.getLogger(RedisServerHandler.class);
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
                "SUBSCRIBE","UNSUBSCRIBE","PUBLISH",
                "EVAL","EVALSHA","SCRIPT","SCRIPT LOAD","SCRIPT EXISTS","SCRIPT FLUSH","SCRIPT KILL",
                "MULTI","EXEC","DISCARD","WATCH","UNWATCH","QUIT",
                "MEMORY", "MONITOR",
                "SLOWLOG"
        };
        for (String n : names) KNOWN_COMMANDS.add(n);
    }
    
    // 服务器状态管理
    private static final long SERVER_START_TIME = System.currentTimeMillis();
    private static final AtomicLong TOTAL_COMMANDS_PROCESSED = new AtomicLong(0);
    private static final AtomicLong TOTAL_CONNECTIONS_RECEIVED = new AtomicLong(0);
    
    // 客户端连接管理
    private static final Map<ChannelId, ClientInfo> CLIENT_INFO_MAP = new ConcurrentHashMap<>();
    // Pub/Sub 管理
    private static final PubSubManager PUB_SUB_MANAGER = new PubSubManager();
    
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
                ClientInfo clientInfo = CLIENT_INFO_MAP.get(ctx.channel().id());
                if (clientInfo == null) {
                    clientInfo = new ClientInfo(null);
                    CLIENT_INFO_MAP.put(ctx.channel().id(), clientInfo);
                }
                clientInfo.updateLastActiveTime();
                clientInfo.getInboundBuf().writeBytes(buffer);
                while (true) {
                    Command command = protocolParser.parse(clientInfo.getInboundBuf());
                    if (command == null) {
                        break;
                    }
                    processCommand(ctx, clientInfo, command);
                }
            } finally {
                buffer.release();
            }
        }
    }
    
    private void processCommand(ChannelHandlerContext ctx, ClientInfo clientInfo, Command command) {
        try {
            String rawCommandName = command.getName();
            String commandName = rawCommandName != null ? rawCommandName.trim().toUpperCase() : "";
            String[] args = command.getArgs();
            logger.debug("Command: {} Args: {}", commandName, java.util.Arrays.toString(args));
            TOTAL_COMMANDS_PROCESSED.incrementAndGet();
            int currentDatabase = clientInfo.getCurrentDatabase();
            logger.debug("Processing command: {}", commandName);
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
                        && !"PING".equals(commandName)) {
                    ByteBuf errorBuffer = protocolParser.serialize("-ERR only (SUBSCRIBE/UNSUBSCRIBE/PING) allowed in Pub/Sub mode\r\n");
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
            } else if ("PUBLISH".equals(commandName)) {
                handlePublish(ctx, args);
                return;
            }

            // MONITOR hook
            MonitorManager.getInstance().submit(currentDatabase, ctx.channel().remoteAddress().toString(), commandName, args);

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
        CLIENT_INFO_MAP.put(ctx.channel().id(), new ClientInfo(null));
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        // 断开连接时清理订阅
        PUB_SUB_MANAGER.unsubscribeAll(ctx.channel());
        MonitorManager.getInstance().removeMonitor(ctx.channel());
        ClientInfo info = CLIENT_INFO_MAP.remove(ctx.channel().id());
        if (info != null && info.getInboundBuf() != null) {
            info.getInboundBuf().release();
        }
    }
    
    // 客户端信息类
    private static class ClientInfo {
        private final String name;
        private final long connectedTime;
        private int currentDatabase; // 当前选择的数据库
        private long lastActiveTime; // 最后活跃时间
        private boolean authenticated; // 是否已认证
        private boolean inPubSubMode; // 是否处于Pub/Sub模式
        private boolean inMonitorMode;
        private boolean inTransaction;
        private java.util.List<Command> txQueue;
        private boolean txQueueError;
        private final java.util.Map<String, Long> watchedVersions = new HashMap<>();
        private final io.netty.buffer.ByteBuf inboundBuf = io.netty.buffer.Unpooled.buffer();
        
        public ClientInfo(String name) {
            this.name = name;
            this.connectedTime = System.currentTimeMillis();
            this.lastActiveTime = System.currentTimeMillis();
            this.currentDatabase = 0; // 默认选择0号数据库
            this.authenticated = false;
            this.inTransaction = false;
            this.txQueue = new ArrayList<>();
            this.txQueueError = false;
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
        ClientInfo clientInfo = CLIENT_INFO_MAP.get(ctx.channel().id());
        for (int i = 1; i < args.length; i++) {
            String channelName = args[i];
            PUB_SUB_MANAGER.subscribe(ctx.channel(), channelName);
            int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel());
            ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList("subscribe", channelName, String.valueOf(count)));
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

    // 处理 UNSUBSCRIBE 命令
    private void handleUnsubscribe(ChannelHandlerContext ctx, String[] args) {
        ClientInfo clientInfo = CLIENT_INFO_MAP.get(ctx.channel().id());
        if (args.length <= 1) {
            java.util.Set<String> subs = PUB_SUB_MANAGER.subscriptions(ctx.channel());
            if (subs.isEmpty()) {
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList("unsubscribe", "", "0"));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            } else {
                for (String ch : subs.toArray(new String[0])) {
                    PUB_SUB_MANAGER.unsubscribe(ctx.channel(), ch);
                    int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel());
                    ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList("unsubscribe", ch, String.valueOf(count)));
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
                int count = PUB_SUB_MANAGER.subscriptionCount(ctx.channel());
                ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList("unsubscribe", channelName, String.valueOf(count)));
                if (resp != null && resp.isReadable()) {
                    ctx.writeAndFlush(resp);
                } else if (resp != null) {
                    resp.release();
                }
            }
        }
        if (clientInfo != null) {
            clientInfo.setInPubSubMode(PUB_SUB_MANAGER.subscriptionCount(ctx.channel()) > 0);
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
        int receivers = 0;
        java.util.List<Channel> snapshot = new java.util.ArrayList<>(PUB_SUB_MANAGER.subscribers(channel));
        for (Channel ch : snapshot) {
            ByteBuf resp = protocolParser.serialize(java.util.Arrays.asList("message", channel, message));
            if (resp != null && resp.isReadable()) {
                ch.writeAndFlush(resp);
                receivers++;
            } else if (resp != null) {
                resp.release();
            }
        }
        ByteBuf countBuf = protocolParser.serialize(receivers);
        if (countBuf != null && countBuf.isReadable()) {
            ctx.writeAndFlush(countBuf);
        } else if (countBuf != null) {
            countBuf.release();
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
    
    // 获取当前连接数
    public static int getCurrentConnections() {
        return CLIENT_INFO_MAP.size();
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
        try {
            // 检查是否在事务中
            if (!clientInfo.isInTransaction()) {
                ByteBuf b = protocolParser.serialize("-ERR EXEC without MULTI");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                return;
            }
            
            // 检查事务队列是否有错误
            if (clientInfo.isTxQueueError()) {
                ByteBuf b = protocolParser.serialize("-EXECABORT Transaction discarded because of previous errors.");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            // 检查监视的键是否被修改
            boolean watchedChanged = false;
            java.util.Map<String, Long> watchedVersions = clientInfo.getWatchedVersions();
            if (!watchedVersions.isEmpty()) {
                for (Map.Entry<String, Long> entry : watchedVersions.entrySet()) {
                    String keyWithDb = entry.getKey();
                    int sepIndex = keyWithDb.indexOf('|');
                    if (sepIndex == -1) continue;
                    
                    try {
                        int db = Integer.parseInt(keyWithDb.substring(0, sepIndex));
                        String key = keyWithDb.substring(sepIndex + 1);
                        long currentVersion = memoryStore.getKeyVersion(db, key);
                        if (currentVersion != entry.getValue()) {
                            watchedChanged = true;
                            break;
                        }
                    } catch (NumberFormatException ex) {
                        // 忽略格式错误的键
                        continue;
                    }
                }
            }
            
            // 如果监视的键被修改，放弃事务（返回 RESP Null Array）
            if (watchedChanged) {
                ByteBuf b = protocolParser.serialize("*-1\r\n");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            // 执行事务队列中的命令
            java.util.List<Command> txQueue = clientInfo.getTxQueue();
            
            // 限制事务队列大小，防止内存溢出
            if (txQueue.size() > 1000) {
                ByteBuf b = protocolParser.serialize("-ERR transaction queue too large");
                if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                else if (b != null) b.release();
                clientInfo.resetTransaction();
                return;
            }
            
            java.util.List<Object> results = new ArrayList<>(txQueue.size());
            long startTime = System.currentTimeMillis();
            
            for (Command cmd : txQueue) {
                // 检查执行时间，防止死循环
                if (System.currentTimeMillis() - startTime > 5000) { // 5秒超时
                    ByteBuf b = protocolParser.serialize("-ERR transaction execution timed out");
                    if (b != null && b.isReadable()) ctx.writeAndFlush(b);
                    else if (b != null) b.release();
                    clientInfo.resetTransaction();
                    return;
                }
                
                String commandName = cmd.getName();
                String[] args = cmd.getArgs();
                
                // 传递完整参数数组（包含命令名）
                long cmdStartTime = System.nanoTime();

                // MONITOR hook for transaction commands
                MonitorManager.getInstance().submit(clientInfo.getCurrentDatabase(), ctx.channel().remoteAddress().toString(), commandName, args);

                Object response = commandHandler.handle(commandName, clientInfo.getCurrentDatabase(), args, memoryStore);
                long cmdDuration = (System.nanoTime() - cmdStartTime) / 1000; // microseconds
                SlowLogManager.getInstance().push(cmdDuration, java.util.Arrays.asList(args), ctx.channel().remoteAddress().toString(), clientInfo.getName());
                
                results.add(response);
                
                // 特殊处理SELECT命令，更新客户端数据库状态
                if ("SELECT".equals(commandName) && args.length >= 2) {
                    try {
                        int database = Integer.parseInt(args[1]);
                        clientInfo.setCurrentDatabase(database);
                    } catch (NumberFormatException ignored) {}
                }
            }
            
            // 返回执行结果
            ByteBuf b = protocolParser.serialize(results);
            if (b != null && b.isReadable()) {
                ctx.writeAndFlush(b);
            } else if (b != null) {
                b.release();
            }
            
            // 重置事务状态
            clientInfo.resetTransaction();
        } catch (Exception e) {
            logger.error("Error handling EXEC command", e);
            ByteBuf b = protocolParser.serialize("-ERR Error handling EXEC command");
            if (b != null && b.isReadable()) ctx.writeAndFlush(b);
            else if (b != null) b.release();
            clientInfo.resetTransaction();
        }
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
}
