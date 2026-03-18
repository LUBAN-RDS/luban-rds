package com.janeluo.luban.rds.sentinel.monitor;

import com.janeluo.luban.rds.sentinel.config.SentinelConstants;
import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SentinelInstance;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import com.janeluo.luban.rds.sentinel.util.SentinelUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 节点监控器
 * 负责监控主节点、从节点和其他哨兵节点
 */
public class NodeMonitor {
    
    private static final Logger logger = LoggerFactory.getLogger(NodeMonitor.class);
    
    private final Sentinel sentinel;
    private final EventLoopGroup workerGroup;
    private final Map<String, Channel> nodeChannels = new ConcurrentHashMap<>();
    
    public NodeMonitor(Sentinel sentinel) {
        this.sentinel = sentinel;
        this.workerGroup = new NioEventLoopGroup();
    }
    
    /**
     * 向所有节点发送 PING 命令
     */
    public void sendPingToAllNodes() {
        // 向主节点发送 PING
        for (MasterState master : sentinel.getMasters().values()) {
            sendPing(master.getHost(), master.getPort(), master.getName(), "master");
            master.setLastPingTime(System.currentTimeMillis());
        }
        
        // 向从节点发送 PING
        for (MasterState master : sentinel.getMasters().values()) {
            for (SlaveState slave : master.getSlaves().values()) {
                sendPing(slave.getHost(), slave.getPort(), slave.getSlaveId(), "slave");
                slave.setLastPingTime(System.currentTimeMillis());
            }
        }
        
        // 向其他哨兵发送 PING
        for (MasterState master : sentinel.getMasters().values()) {
            for (SentinelInstance si : master.getSentinels().values()) {
                sendPing(si.getHost(), si.getPort(), si.getSentinelId(), "sentinel");
                si.setLastPingTime(System.currentTimeMillis());
            }
        }
    }
    
    /**
     * 发送 PING 命令到指定节点
     */
    private void sendPing(String host, int port, String nodeId, String nodeType) {
        String key = host + ":" + port;
        Channel channel = nodeChannels.get(key);
        
        if (channel == null || !channel.isActive()) {
            channel = connectToNode(host, port);
            if (channel != null) {
                nodeChannels.put(key, channel);
            } else {
                return;
            }
        }
        
        try {
            String pingCmd = "*1\r\n$4\r\nPING\r\n";
            channel.writeAndFlush(Unpooled.copiedBuffer(pingCmd, CharsetUtil.UTF_8));
            logger.trace("Sent PING to {} node {}", nodeType, nodeId);
        } catch (Exception e) {
            logger.debug("Failed to send PING to {} node {}: {}", nodeType, nodeId, e.getMessage());
        }
    }
    
    /**
     * 连接到节点
     */
    private Channel connectToNode(String host, int port) {
        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(workerGroup)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                              .addLast(new RedisDecoder(true))
                              .addLast(new RedisEncoder())
                              .addLast(new NodeResponseHandler(sentinel, host, port));
                        }
                    });
            
            ChannelFuture future = bootstrap.connect(host, port).sync();
            if (future.isSuccess()) {
                logger.debug("Connected to node {}:{}", host, port);
                return future.channel();
            }
        } catch (Exception e) {
            logger.debug("Failed to connect to node {}:{} - {}", host, port, e.getMessage());
        }
        return null;
    }
    
    /**
     * 从所有节点查询 INFO 信息
     */
    public void queryInfoFromAllNodes() {
        // 查询主节点 INFO
        for (MasterState master : sentinel.getMasters().values()) {
            queryInfo(master.getHost(), master.getPort(), master.getName(), "master");
        }
        
        // 查询从节点 INFO
        for (MasterState master : sentinel.getMasters().values()) {
            for (SlaveState slave : master.getSlaves().values()) {
                queryInfo(slave.getHost(), slave.getPort(), slave.getSlaveId(), "slave");
            }
        }
    }
    
    /**
     * 查询节点 INFO 信息
     */
    private void queryInfo(String host, int port, String nodeId, String nodeType) {
        String key = host + ":" + port;
        Channel channel = nodeChannels.get(key);
        
        if (channel == null || !channel.isActive()) {
            channel = connectToNode(host, port);
            if (channel != null) {
                nodeChannels.put(key, channel);
            } else {
                return;
            }
        }
        
        try {
            String infoCmd = "*2\r\n$4\r\nINFO\r\n$11\r\nreplication\r\n";
            channel.writeAndFlush(Unpooled.copiedBuffer(infoCmd, CharsetUtil.UTF_8));
            logger.trace("Sent INFO to {} node {}", nodeType, nodeId);
        } catch (Exception e) {
            logger.debug("Failed to send INFO to {} node {}: {}", nodeType, nodeId, e.getMessage());
        }
    }
    
    /**
     * 发布 Hello 消息
     */
    public void publishHelloMessage() {
        for (MasterState master : sentinel.getMasters().values()) {
            publishHelloToMaster(master);
        }
    }
    
    /**
     * 向主节点发布 Hello 消息
     */
    private void publishHelloToMaster(MasterState master) {
        String key = master.getHost() + ":" + master.getPort();
        Channel channel = nodeChannels.get(key);
        
        if (channel == null || !channel.isActive()) {
            channel = connectToNode(master.getHost(), master.getPort());
            if (channel != null) {
                nodeChannels.put(key, channel);
            } else {
                return;
            }
        }
        
        try {
            // Hello 消息格式: sentinel_id, sentinel_ip, sentinel_port, master_name, master_ip, master_port, epoch
            String helloMsg = String.format("%s,%s,%d,%s,%s,%d,%d",
                    sentinel.getSentinelId(),
                    sentinel.getConfig().getBind(),
                    sentinel.getConfig().getPort(),
                    master.getName(),
                    master.getHost(),
                    master.getPort(),
                    sentinel.getCurrentEpoch());
            
            String publishCmd = String.format("*3\r\n$7\r\nPUBLISH\r\n$18\r\n%s\r\n$%d\r\n%s\r\n",
                    SentinelConstants.SENTINEL_HELLO_CHANNEL,
                    helloMsg.length(),
                    helloMsg);
            
            channel.writeAndFlush(Unpooled.copiedBuffer(publishCmd, CharsetUtil.UTF_8));
            logger.trace("Published hello message for master {}", master.getName());
        } catch (Exception e) {
            logger.debug("Failed to publish hello message: {}", e.getMessage());
        }
    }
    
    /**
     * 处理从节点发现
     */
    public void discoverSlaves(MasterState master, String infoResponse) {
        if (infoResponse == null || infoResponse.isEmpty()) {
            return;
        }
        
        // 解析 INFO replication 响应，发现从节点
        String[] lines = infoResponse.split("\r\n");
        for (String line : lines) {
            if (line.startsWith("slave")) {
                // 格式: slave0:ip=127.0.0.1,port=6380,state=online,offset=123,lag=0
                parseSlaveInfo(master, line);
            }
        }
    }
    
    /**
     * 解析从节点信息
     */
    private void parseSlaveInfo(MasterState master, String line) {
        try {
            int colonIndex = line.indexOf(':');
            if (colonIndex < 0) return;
            
            String info = line.substring(colonIndex + 1);
            String[] parts = info.split(",");
            
            String ip = null;
            int port = 0;
            long offset = 0;
            int priority = 100;
            
            for (String part : parts) {
                String[] kv = part.split("=");
                if (kv.length == 2) {
                    switch (kv[0]) {
                        case "ip":
                            ip = kv[1];
                            break;
                        case "port":
                            port = Integer.parseInt(kv[1]);
                            break;
                        case "offset":
                            offset = Long.parseLong(kv[1]);
                            break;
                        case "priority":
                            priority = Integer.parseInt(kv[1]);
                            break;
                    }
                }
            }
            
            if (ip != null && port > 0) {
                String slaveId = ip + ":" + port;
                SlaveState slave = master.getSlave(slaveId);
                
                if (slave == null) {
                    slave = new SlaveState(slaveId, ip, port);
                    master.addSlave(slave);
                    logger.info("Discovered new slave {} for master {}", slaveId, master.getName());
                }
                
                slave.setReplOffset(offset);
                slave.setPriority(priority);
                slave.setMasterHost(master.getHost());
                slave.setMasterPort(master.getPort());
            }
        } catch (Exception e) {
            logger.debug("Failed to parse slave info: {}", line);
        }
    }
    
    /**
     * 处理哨兵发现
     */
    public void discoverSentinel(MasterState master, String helloMessage) {
        if (helloMessage == null || helloMessage.isEmpty()) {
            return;
        }
        
        try {
            String[] parts = helloMessage.split(",");
            if (parts.length >= 7) {
                String sentinelId = parts[0];
                String sentinelIp = parts[1];
                int sentinelPort = Integer.parseInt(parts[2]);
                String masterName = parts[3];
                
                if (!masterName.equals(master.getName())) {
                    return;
                }
                
                SentinelInstance si = master.getSentinel(sentinelId);
                if (si == null) {
                    si = new SentinelInstance(sentinelId, sentinelIp, sentinelPort);
                    master.addSentinel(si);
                    logger.info("Discovered new sentinel {} for master {}", sentinelId, masterName);
                }
                
                si.setLastHelloTime(System.currentTimeMillis());
            }
        } catch (Exception e) {
            logger.debug("Failed to parse hello message: {}", helloMessage);
        }
    }
    
    /**
     * 关闭所有连接
     */
    public void shutdown() {
        for (Channel channel : nodeChannels.values()) {
            if (channel.isActive()) {
                channel.close();
            }
        }
        nodeChannels.clear();
        workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
    
    /**
     * 获取节点通道
     */
    public Channel getNodeChannel(String host, int port) {
        return nodeChannels.get(host + ":" + port);
    }
    
    /**
     * 移除节点通道
     */
    public void removeNodeChannel(String host, int port) {
        nodeChannels.remove(host + ":" + port);
    }
}
