package com.janeluo.luban.rds.sentinel.monitor;

import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.redis.FullBulkStringRedisMessage;
import io.netty.handler.codec.redis.RedisMessage;
import io.netty.handler.codec.redis.SimpleStringRedisMessage;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 节点响应处理器
 * 处理从节点返回的响应
 */
public class NodeResponseHandler extends ChannelInboundHandlerAdapter {
    
    private static final Logger logger = LoggerFactory.getLogger(NodeResponseHandler.class);
    
    private final Sentinel sentinel;
    private final String host;
    private final int port;
    
    public NodeResponseHandler(Sentinel sentinel, String host, int port) {
        this.sentinel = sentinel;
        this.host = host;
        this.port = port;
    }
    
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof RedisMessage)) {
            return;
        }
        
        RedisMessage redisMessage = (RedisMessage) msg;
        String response = extractResponse(redisMessage);
        
        if (response != null) {
            handleResponse(response);
        }
    }
    
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.debug("Connection to {}:{} closed", host, port);
        sentinel.getNodeMonitor().removeNodeChannel(host, port);
    }
    
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("Exception on connection to {}:{}: {}", host, port, cause.getMessage());
        ctx.close();
    }
    
    /**
     * 提取响应内容
     */
    private String extractResponse(RedisMessage message) {
        if (message instanceof SimpleStringRedisMessage) {
            return ((SimpleStringRedisMessage) message).content();
        } else if (message instanceof FullBulkStringRedisMessage) {
            FullBulkStringRedisMessage bulkMessage = (FullBulkStringRedisMessage) message;
            if (bulkMessage.content() != null) {
                return bulkMessage.content().toString(CharsetUtil.UTF_8);
            }
        }
        return null;
    }
    
    /**
     * 处理响应
     */
    private void handleResponse(String response) {
        long now = System.currentTimeMillis();
        
        // 查找对应的主节点或从节点
        for (MasterState master : sentinel.getMasters().values()) {
            // 检查是否是主节点
            if (master.getHost().equals(host) && master.getPort() == port) {
                handleMasterResponse(master, response, now);
                return;
            }
            
            // 检查是否是从节点
            for (SlaveState slave : master.getSlaves().values()) {
                if (slave.getHost().equals(host) && slave.getPort() == port) {
                    handleSlaveResponse(slave, response, now);
                    return;
                }
            }
        }
    }
    
    /**
     * 处理主节点响应
     */
    private void handleMasterResponse(MasterState master, String response, long now) {
        if (response.equalsIgnoreCase("PONG")) {
            master.setLastPongTime(now);
            master.setLastOkPingReply(now);
            logger.trace("Received PONG from master {}", master.getName());
        } else if (response.contains("role:master") || response.contains("connected_slaves")) {
            // INFO 响应
            master.setLastPongTime(now);
            sentinel.getNodeMonitor().discoverSlaves(master, response);
            logger.trace("Received INFO from master {}", master.getName());
        }
    }
    
    /**
     * 处理从节点响应
     */
    private void handleSlaveResponse(SlaveState slave, String response, long now) {
        if (response.equalsIgnoreCase("PONG")) {
            slave.setLastPongTime(now);
            slave.setLastOkPingReply(now);
            logger.trace("Received PONG from slave {}", slave.getSlaveId());
        } else if (response.contains("role:slave")) {
            // INFO 响应，解析从节点信息
            parseSlaveInfoResponse(slave, response);
            slave.setLastPongTime(now);
            logger.trace("Received INFO from slave {}", slave.getSlaveId());
        }
    }
    
    /**
     * 解析从节点 INFO 响应
     */
    private void parseSlaveInfoResponse(SlaveState slave, String info) {
        String[] lines = info.split("\r\n");
        for (String line : lines) {
            if (line.startsWith("master_host:")) {
                slave.setMasterHost(line.substring("master_host:".length()));
            } else if (line.startsWith("master_port:")) {
                try {
                    slave.setMasterPort(Integer.parseInt(line.substring("master_port:".length())));
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if (line.startsWith("slave_repl_offset:")) {
                try {
                    slave.setReplOffset(Long.parseLong(line.substring("slave_repl_offset:".length())));
                } catch (NumberFormatException e) {
                    // ignore
                }
            } else if (line.startsWith("slave_priority:")) {
                try {
                    slave.setPriority(Integer.parseInt(line.substring("slave_priority:".length())));
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
    }
}
