package com.janeluo.luban.rds.sentinel.core;

import com.janeluo.luban.rds.sentinel.handler.SentinelCommandHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.redis.ArrayRedisMessage;
import io.netty.handler.codec.redis.FullBulkStringRedisMessage;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.handler.codec.redis.SimpleStringRedisMessage;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 哨兵服务器处理器
 * 处理客户端连接和命令
 */
public class SentinelServerHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(SentinelServerHandler.class);
    
    private final Sentinel sentinel;
    private final SentinelCommandHandler commandHandler;
    
    public SentinelServerHandler(Sentinel sentinel) {
        this.sentinel = sentinel;
        this.commandHandler = new SentinelCommandHandler(sentinel);
    }
    
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.debug("Client connected: {}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Client disconnected: {}", ctx.channel().remoteAddress());
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof RedisMessage)) {
            return;
        }
        
        String[] args = parseRedisMessage((RedisMessage) msg);
        if (args == null || args.length == 0) {
            sendError(ctx, "ERR empty command");
            return;
        }
        
        logger.debug("Received command: {}", String.join(" ", args));
        
        try {
            String response = commandHandler.handleCommand(ctx, args);
            if (response != null) {
                sendResponse(ctx, response);
            }
        } catch (Exception e) {
            logger.error("Error handling command: {}", String.join(" ", args), e);
            sendError(ctx, "ERR internal error: " + e.getMessage());
        }
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in sentinel server handler", cause);
        ctx.close();
    }
    
    /**
     * 解析 Redis 消息
     */
    private String[] parseRedisMessage(RedisMessage message) {
        if (message instanceof ArrayRedisMessage) {
            ArrayRedisMessage arrayMessage = (ArrayRedisMessage) message;
            List<String> args = new ArrayList<>();
            
            for (RedisMessage child : arrayMessage.children()) {
                if (child instanceof FullBulkStringRedisMessage) {
                    FullBulkStringRedisMessage bulkMessage = (FullBulkStringRedisMessage) child;
                    args.add(bulkMessage.content().toString(CharsetUtil.UTF_8));
                } else if (child instanceof SimpleStringRedisMessage) {
                    SimpleStringRedisMessage simpleMessage = (SimpleStringRedisMessage) child;
                    args.add(simpleMessage.content());
                }
            }
            
            return args.toArray(new String[0]);
        } else if (message instanceof SimpleStringRedisMessage) {
            SimpleStringRedisMessage simpleMessage = (SimpleStringRedisMessage) message;
            return simpleMessage.content().split("\\s+");
        }
        
        return null;
    }
    
    /**
     * 发送响应
     */
    private void sendResponse(ChannelHandlerContext ctx, String response) {
        if (response.startsWith("+")) {
            ctx.writeAndFlush(new SimpleStringRedisMessage(response.substring(1)));
        } else if (response.startsWith("-")) {
            ctx.writeAndFlush(new SimpleStringRedisMessage(response));
        } else if (response.startsWith(":")) {
            ctx.writeAndFlush(new SimpleStringRedisMessage(response));
        } else if (response.startsWith("$")) {
            if (response.equals("$-1\r\n")) {
                ctx.writeAndFlush(FullBulkStringRedisMessage.NULL_INSTANCE);
            } else {
                String content = response.substring(response.indexOf("\r\n") + 2, 
                                                   response.lastIndexOf("\r\n"));
                ctx.writeAndFlush(new FullBulkStringRedisMessage(
                    io.netty.buffer.Unpooled.copiedBuffer(content, CharsetUtil.UTF_8)));
            }
        } else if (response.startsWith("*")) {
            // Array response - simplified handling
            ctx.writeAndFlush(new SimpleStringRedisMessage(response));
        } else {
            ctx.writeAndFlush(new SimpleStringRedisMessage(response));
        }
    }
    
    /**
     * 发送错误响应
     */
    private void sendError(ChannelHandlerContext ctx, String error) {
        ctx.writeAndFlush(new SimpleStringRedisMessage("-" + error + "\r\n"));
    }
}
