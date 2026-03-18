package com.janeluo.luban.rds.replication;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主节点复制管理器
 */
public class MasterReplicationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterReplicationManager.class);
    private static volatile MasterReplicationManager instance;
    
    private final List<SlaveInfo> slaves;
    private final Map<Channel, SlaveInfo> slaveChannelMap;
    private final ReplicationBacklog backlog;
    private String requirepass;
    
    private final AtomicInteger connectedSlaves = new AtomicInteger(0);
    private final AtomicLong syncFull = new AtomicLong(0);
    private final AtomicLong syncPartialOk = new AtomicLong(0);
    private final AtomicLong syncPartialErr = new AtomicLong(0);
    
    private MasterReplicationManager(int backlogSize) {
        this.slaves = new CopyOnWriteArrayList<>();
        this.slaveChannelMap = new ConcurrentHashMap<>();
        this.backlog = new ReplicationBacklog(backlogSize);
    }
    
    public static MasterReplicationManager getInstance() {
        if (instance == null) {
            synchronized (MasterReplicationManager.class) {
                if (instance == null) {
                    instance = new MasterReplicationManager(ReplicationConstants.DEFAULT_BACKLOG_SIZE);
                }
            }
        }
        return instance;
    }
    
    public static synchronized void initialize(int backlogSize) {
        if (instance == null) {
            instance = new MasterReplicationManager(backlogSize);
        }
    }
    
    public void setRequirepass(String requirepass) { this.requirepass = requirepass; }
    
    public SlaveInfo addSlave(Channel channel) {
        SlaveInfo slave = new SlaveInfo(channel);
        slaves.add(slave);
        slaveChannelMap.put(channel, slave);
        connectedSlaves.incrementAndGet();
        logger.info("Slave connected: {}, total slaves: {}", slave.getSlaveId(), connectedSlaves.get());
        return slave;
    }
    
    public void removeSlave(Channel channel) {
        SlaveInfo slave = slaveChannelMap.remove(channel);
        if (slave != null) {
            slaves.remove(slave);
            connectedSlaves.decrementAndGet();
            logger.info("Slave disconnected: {}, remaining slaves: {}", slave.getSlaveId(), connectedSlaves.get());
        }
    }
    
    public SlaveInfo getSlave(Channel channel) {
        return slaveChannelMap.get(channel);
    }
    
    public String handleReplconf(Channel channel, String[] args) {
        if (args.length < 2) return "-ERR wrong number of arguments for 'replconf' command\r\n";
        
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave == null) slave = addSlave(channel);
        
        String subcommand = args[1].toLowerCase();
        
        switch (subcommand) {
            case "listening-port":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf listening-port' command\r\n";
                try {
                    int port = Integer.parseInt(args[2]);
                    slave.setListeningPort(port);
                    logger.debug("Slave {} listening-port: {}", slave.getSlaveId(), port);
                    return "+OK\r\n";
                } catch (NumberFormatException e) {
                    return "-ERR invalid port number\r\n";
                }
                
            case "ip-address":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf ip-address' command\r\n";
                slave.setIp(args[2]);
                logger.debug("Slave {} ip-address: {}", slave.getSlaveId(), args[2]);
                return "+OK\r\n";
                
            case "capa":
                for (int i = 2; i < args.length; i++) slave.addCapability(args[i]);
                logger.debug("Slave {} capabilities: {}", slave.getSlaveId(), slave.getCapabilities());
                return "+OK\r\n";
                
            case "ack":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf ack' command\r\n";
                try {
                    long offset = Long.parseLong(args[2]);
                    slave.updateOffset(offset);
                    slave.setState(ReplicationState.ONLINE);
                    slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
                    slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
                    logger.trace("Slave {} ACK offset: {}", slave.getSlaveId(), offset);
                    return null;
                } catch (NumberFormatException e) {
                    return "-ERR invalid offset\r\n";
                }
                
            default:
                return "-ERR unknown subcommand: " + subcommand + "\r\n";
        }
    }
    
    public PsyncResponse handlePsync(Channel channel, String[] args) {
        if (args.length < 3) return new PsyncResponse("-ERR wrong number of arguments for 'psync' command\r\n", null);
        
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave == null) slave = addSlave(channel);
        
        String replId = args[1];
        long offset;
        try {
            offset = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return new PsyncResponse("-ERR invalid offset\r\n", null);
        }
        
        if (requirepass != null && !requirepass.isEmpty() && !slave.isAuthenticated()) {
            return new PsyncResponse("-NOAUTH Authentication required\r\n", null);
        }
        
        if (!"?".equals(replId) && backlog.canPartialSync(replId, offset)) {
            slave.setReplId(replId);
            slave.updateOffset(offset);
            slave.setState(ReplicationState.ONLINE);
            slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE | SlaveInfo.SLAVE_FLAG_PARTIAL_SYNC);
            slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
            
            syncPartialOk.incrementAndGet();
            
            byte[] backlogData = backlog.getBacklogData(offset);
            String response = String.format("+CONTINUE %s\r\n", backlog.getReplId());
            
            logger.info("Partial sync accepted for slave {}, offset: {}, data length: {}", 
                       slave.getSlaveId(), offset, backlogData != null ? backlogData.length : 0);
            
            return new PsyncResponse(response, backlogData);
            
        } else {
            slave.setReplId(backlog.getReplId());
            slave.updateOffset(0);
            slave.setState(ReplicationState.FULL_SYNC);
            slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
            slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
            
            syncFull.incrementAndGet();
            
            String response = String.format("+FULLRESYNC %s %d\r\n", 
                                           backlog.getReplId(), backlog.getMasterReplOffset());
            
            logger.info("Full sync requested for slave {}, replid: {}, offset: {}", 
                       slave.getSlaveId(), backlog.getReplId(), backlog.getMasterReplOffset());
            
            return new PsyncResponse(response, null, true);
        }
    }
    
    public void propagateCommand(byte[] command) {
        if (slaves.isEmpty()) return;
        
        backlog.append(command);
        
        for (SlaveInfo slave : slaves) {
            if (slave.isOnline() && slave.getChannel().isActive()) {
                try {
                    ByteBuf buf = Unpooled.wrappedBuffer(command);
                    slave.getChannel().writeAndFlush(buf);
                    slave.incrementOffset(command.length);
                } catch (Exception e) {
                    logger.error("Failed to propagate command to slave: {}", slave.getSlaveId(), e);
                }
            }
        }
    }
    
    public void propagateCommand(String command) {
        propagateCommand(command.getBytes(CharsetUtil.UTF_8));
    }
    
    public void sendPingToSlaves() {
        if (slaves.isEmpty()) return;
        
        byte[] ping = "*1\r\n$4\r\nPING\r\n".getBytes(CharsetUtil.UTF_8);
        
        for (SlaveInfo slave : slaves) {
            if (slave.getChannel().isActive()) {
                try {
                    ByteBuf buf = Unpooled.wrappedBuffer(ping);
                    slave.getChannel().writeAndFlush(buf);
                } catch (Exception e) {
                    logger.error("Failed to send PING to slave: {}", slave.getSlaveId(), e);
                }
            }
        }
    }
    
    public void checkSlaveTimeout(long timeout) {
        long now = System.currentTimeMillis();
        
        Iterator<SlaveInfo> iterator = slaves.iterator();
        while (iterator.hasNext()) {
            SlaveInfo slave = iterator.next();
            
            if (now - slave.getLastInteractionTime() > timeout) {
                logger.warn("Slave {} timed out, last interaction: {} ms ago", 
                           slave.getSlaveId(), now - slave.getLastInteractionTime());
                
                if (slave.getChannel().isActive()) {
                    slave.getChannel().close();
                }
            }
        }
    }
    
    public void markSlaveAuthenticated(Channel channel) {
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave != null) slave.setAuthenticated(true);
    }
    
    public ReplicationBacklog getBacklog() { return backlog; }
    public int getConnectedSlaves() { return connectedSlaves.get(); }
    public List<SlaveInfo> getSlaves() { return new ArrayList<>(slaves); }
    public long getSyncFull() { return syncFull.get(); }
    public long getSyncPartialOk() { return syncPartialOk.get(); }
    public long getSyncPartialErr() { return syncPartialErr.get(); }
    
    public String getReplicationInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("# Replication\r\n");
        info.append("role:master\r\n");
        info.append("connected_slaves:").append(connectedSlaves.get()).append("\r\n");
        
        int index = 0;
        for (SlaveInfo slave : slaves) {
            info.append("slave").append(index++).append(":")
                .append("ip=").append(slave.getIp())
                .append(",port=").append(slave.getPort())
                .append(",state=").append(slave.getState().getName())
                .append(",offset=").append(slave.getOffset())
                .append(",lag=").append((System.currentTimeMillis() - slave.getLastInteractionTime()) / 1000)
                .append("\r\n");
        }
        
        info.append("master_replid:").append(backlog.getReplId()).append("\r\n");
        info.append("master_repl_offset:").append(backlog.getMasterReplOffset()).append("\r\n");
        info.append(backlog.getInfo());
        
        info.append("sync_full:").append(syncFull.get()).append("\r\n");
        info.append("sync_partial_ok:").append(syncPartialOk.get()).append("\r\n");
        info.append("sync_partial_err:").append(syncPartialErr.get()).append("\r\n");
        
        return info.toString();
    }
    
    public static class PsyncResponse {
        private final String response;
        private final byte[] backlogData;
        private final boolean needRdb;
        
        public PsyncResponse(String response, byte[] backlogData) {
            this(response, backlogData, false);
        }
        
        public PsyncResponse(String response, byte[] backlogData, boolean needRdb) {
            this.response = response;
            this.backlogData = backlogData;
            this.needRdb = needRdb;
        }
        
        public String getResponse() { return response; }
        public byte[] getBacklogData() { return backlogData; }
        public boolean isNeedRdb() { return needRdb; }
    }
}
