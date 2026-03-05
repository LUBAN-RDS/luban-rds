package com.janeluo.luban.rds.client;

import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.bootstrap.Bootstrap;
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
        
        public RedisClientHandler(BlockingQueue<Object> responseQueue, RedisProtocolParser protocolParser) {
            this.responseQueue = responseQueue;
            this.protocolParser = protocolParser;
        }
        
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof io.netty.buffer.ByteBuf) {
                io.netty.buffer.ByteBuf buffer = (io.netty.buffer.ByteBuf) msg;
                try {
                    Object response = protocolParser.parseResp(buffer);
                    responseQueue.offer(response);
                } finally {
                    buffer.release();
                }
            }
        }
        
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            logger.error("Exception caught in RedisClientHandler", cause);
            ctx.close();
        }
    }
}
