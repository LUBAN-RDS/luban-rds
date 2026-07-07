package com.janeluo.luban.rds.client;

import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于Netty的Redis客户端实现
 * 
 * <p>提供完整的Redis客户端功能，支持：
 * <ul>
 *   <li>字符串、哈希、列表、集合、有序集合操作</li>
 *   <li>键过期和通用操作</li>
 *   <li>连接管理和自动重连</li>
 *   <li>异步响应处理</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class NettyRedisClient implements RedisClient {
    
    private static final Logger logger = LoggerFactory.getLogger(NettyRedisClient.class);
    
    private final String host;
    private final int port;
    private final RedisProtocolParser protocolParser;
    
    private EventLoopGroup group;
    private Channel channel;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final BlockingQueue<Object> responseQueue = new LinkedBlockingQueue<>();
    
    public NettyRedisClient() {
        this("localhost", 9736);
    }
    
    public NettyRedisClient(String host, int port) {
        this.host = host;
        this.port = port;
        this.protocolParser = new RedisProtocolParser();
    }
    
    @Override
    public void connect() {
        if (connected.get()) {
            logger.warn("Client is already connected");
            return;
        }
        
        group = new NioEventLoopGroup();
        
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
             .channel(NioSocketChannel.class)
             .option(ChannelOption.TCP_NODELAY, true)
             .option(ChannelOption.SO_KEEPALIVE, true)
             .handler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline pipeline = ch.pipeline();
                     pipeline.addLast(new RedisClientHandler(responseQueue, protocolParser));
                 }
             });
            
            ChannelFuture f = b.connect(host, port).sync();
            channel = f.channel();
            connected.set(true);
            logger.info("Connected to LbRDS server at {}:{}", host, port);
        } catch (Exception e) {
            logger.error("Failed to connect to LbRDS server", e);
            disconnect();
        }
    }
    
    @Override
    public void disconnect() {
        if (!connected.get()) {
            return;
        }
        
        try {
            if (channel != null) {
                channel.close().sync();
            }
        } catch (Exception e) {
            logger.error("Error closing channel", e);
        } finally {
            if (group != null) {
                group.shutdownGracefully();
            }
            connected.set(false);
            logger.info("Disconnected from LbRDS server");
        }
    }
    
    @Override
    public boolean isConnected() {
        return connected.get();
    }
    
    @Override
    public void set(String key, String value) {
        sendCommand("SET", key, value);
    }
    
    @Override
    public String get(String key) {
        return (String) sendCommand("GET", key);
    }
    
    @Override
    public Long incr(String key) {
        Object response = sendCommand("INCR", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long decr(String key) {
        Object response = sendCommand("DECR", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long incrBy(String key, long increment) {
        return (Long) sendCommand("INCRBY", key, String.valueOf(increment));
    }
    
    @Override
    public Long decrBy(String key, long decrement) {
        return (Long) sendCommand("DECRBY", key, String.valueOf(decrement));
    }
    
    @Override
    public Long append(String key, String value) {
        Object response = sendCommand("APPEND", key, value);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long strlen(String key) {
        Object response = sendCommand("STRLEN", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long hset(String key, String field, String value) {
        Object response = sendCommand("HSET", key, field, value);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public String hget(String key, String field) {
        return (String) sendCommand("HGET", key, field);
    }
    
    @Override
    public Map<String, String> hgetAll(String key) {
        return (Map<String, String>) sendCommand("HGETALL", key);
    }
    
    @Override
    public Long hdel(String key, String... fields) {
        String[] args = new String[fields.length + 1];
        args[0] = key;
        System.arraycopy(fields, 0, args, 1, fields.length);
        Object response = sendCommand("HDEL", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Boolean hexists(String key, String field) {
        Object response = sendCommand("HEXISTS", key, field);
        return response != null ? Long.parseLong(response.toString()) == 1 : false;
    }
    
    @Override
    public Set<String> hkeys(String key) {
        return (Set<String>) sendCommand("HKEYS", key);
    }
    
    @Override
    public List<String> hvals(String key) {
        return (List<String>) sendCommand("HVALS", key);
    }
    
    @Override
    public Long hlen(String key) {
        Object response = sendCommand("HLEN", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long lpush(String key, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        System.arraycopy(values, 0, args, 1, values.length);
        Object response = sendCommand("LPUSH", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long rpush(String key, String... values) {
        String[] args = new String[values.length + 1];
        args[0] = key;
        System.arraycopy(values, 0, args, 1, values.length);
        Object response = sendCommand("RPUSH", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public String lpop(String key) {
        return (String) sendCommand("LPOP", key);
    }
    
    @Override
    public String rpop(String key) {
        return (String) sendCommand("RPOP", key);
    }
    
    @Override
    public Long llen(String key) {
        Object response = sendCommand("LLEN", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public List<String> lrange(String key, long start, long stop) {
        return (List<String>) sendCommand("LRANGE", key, String.valueOf(start), String.valueOf(stop));
    }
    
    @Override
    public Long sadd(String key, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        System.arraycopy(members, 0, args, 1, members.length);
        Object response = sendCommand("SADD", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long srem(String key, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        System.arraycopy(members, 0, args, 1, members.length);
        Object response = sendCommand("SREM", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Set<String> smembers(String key) {
        return (Set<String>) sendCommand("SMEMBERS", key);
    }
    
    @Override
    public Boolean sismember(String key, String member) {
        Object response = sendCommand("SISMEMBER", key, member);
        return response != null ? Long.parseLong(response.toString()) == 1 : false;
    }
    
    @Override
    public Long scard(String key) {
        Object response = sendCommand("SCARD", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long zadd(String key, double score, String member) {
        return (Long) sendCommand("ZADD", key, String.valueOf(score), member);
    }
    
    @Override
    public List<String> zrange(String key, long start, long stop) {
        return (List<String>) sendCommand("ZRANGE", key, String.valueOf(start), String.valueOf(stop));
    }
    
    @Override
    public Double zscore(String key, String member) {
        Object response = sendCommand("ZSCORE", key, member);
        return response != null ? Double.parseDouble(response.toString()) : null;
    }
    
    @Override
    public Long zrem(String key, String... members) {
        String[] args = new String[members.length + 1];
        args[0] = key;
        System.arraycopy(members, 0, args, 1, members.length);
        Object response = sendCommand("ZREM", args);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long zcard(String key) {
        Object response = sendCommand("ZCARD", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long exists(String... keys) {
        Object response = sendCommand("EXISTS", keys);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Long del(String... keys) {
        Object response = sendCommand("DEL", keys);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public Boolean expire(String key, long seconds) {
        Object response = sendCommand("EXPIRE", key, String.valueOf(seconds));
        return response != null ? Long.parseLong(response.toString()) == 1 : false;
    }
    
    @Override
    public Long ttl(String key) {
        Object response = sendCommand("TTL", key);
        return response != null ? Long.parseLong(response.toString()) : null;
    }
    
    @Override
    public void flushAll() {
        sendCommand("FLUSHALL");
    }
    
    @Override
    public String type(String key) {
        return (String) sendCommand("TYPE", key);
    }
    
    public Object executeCommand(String command, String... args) {
        return sendCommand(command, args);
    }
    
    private Object sendCommand(String command, String... args) {
        if (!connected.get()) {
            throw new IllegalStateException("Client is not connected");
        }
        
        try {
            // 构建RESP格式的命令
            StringBuilder sb = new StringBuilder();
            sb.append("*").append(args.length + 1).append("\r\n");
            sb.append("$").append(command.length()).append("\r\n").append(command).append("\r\n");
            
            for (String arg : args) {
                sb.append("$").append(arg.length()).append("\r\n").append(arg).append("\r\n");
            }
            
            // 发送命令 - 使用ISO-8859-1编码确保二进制安全
            channel.writeAndFlush(io.netty.buffer.Unpooled.copiedBuffer(sb.toString().getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).sync();
            
            // 等待响应
            Object response = responseQueue.poll(5, TimeUnit.SECONDS);
            return response;
        } catch (Exception e) {
            logger.error("Error sending command", e);
            return null;
        }
    }
    
    private static class RedisClientHandler extends ChannelInboundHandlerAdapter {
        private final BlockingQueue<Object> responseQueue;
        private final RedisProtocolParser protocolParser;

        /**
         * 累积入站字节的缓冲区。
         * <p>
         * RESP 响应可能跨越多个 TCP 段（半包），单次 channelRead 拿到的 ByteBuf
         * 未必包含完整响应。本缓冲区累积入站字节，配合 parseResp 的 mark/reset
         * 语义循环解析：完整响应入队，不完整则保留等待下次 channelRead。
         * </p>
         */
        private ByteBuf accumulationBuf;

        public RedisClientHandler(BlockingQueue<Object> responseQueue, RedisProtocolParser protocolParser) {
            this.responseQueue = responseQueue;
            this.protocolParser = protocolParser;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
            // 用 channel 的分配器创建累积缓冲，对齐服务端 RedisServerHandler.ClientInfo.initInboundBuf 的做法
            accumulationBuf = ctx.alloc().buffer(1024);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
            releaseAccumulationBuf();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // 连接关闭时释放累积缓冲，避免泄漏
            releaseAccumulationBuf();
            super.channelInactive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (!(msg instanceof ByteBuf)) {
                return;
            }
            ByteBuf in = (ByteBuf) msg;
            try {
                // 累积本次入站字节
                accumulationBuf.writeBytes(in);
                // 循环解析：一个 TCP 段可能包含多个完整响应（粘包），逐个入队；
                // parseResp 返回 null 有两种语义：
                //   1) 半包——parseResp 已 reset readerIndex 到解析起点，readerIndex 未前进；
                //   2) 合法 RESP null（如 $-1、*_null_）——字节已完整消费，readerIndex 已前进。
                // 通过比较解析前后 readerIndex 区分：未前进视为半包，保留字节等待下次 channelRead；
                // 已前进视为已消费，将 null 入队并继续解析后续字节。
                while (accumulationBuf.isReadable()) {
                    int markBefore = accumulationBuf.readerIndex();
                    accumulationBuf.markReaderIndex();
                    Object response = protocolParser.parseResp(accumulationBuf);
                    if (response == null && accumulationBuf.readerIndex() == markBefore) {
                        // 半包：parseResp 已回退，无需再 reset，跳出等待更多数据
                        break;
                    }
                    // response 非 null（完整响应），或为已消费的合法 null；入队继续
                    responseQueue.offer(response);
                }
                // 丢弃已消费字节，压缩缓冲区，防止无限增长
                accumulationBuf.discardReadBytes();
            } finally {
                in.release();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("Exception caught in RedisClientHandler", cause);
            ctx.close();
        }

        private void releaseAccumulationBuf() {
            if (accumulationBuf != null) {
                accumulationBuf.release();
                accumulationBuf = null;
            }
        }
    }
}
