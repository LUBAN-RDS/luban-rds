package com.janeluo.luban.rds.replication;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 从节点信息
 * 
 * 存储从节点的连接信息、状态和统计信息
 */
public class SlaveInfo {
    
    private final String slaveId;
    private String ip;
    private int port;
    private final StringBuilder capabilities;
    private String replId;
    private final AtomicLong offset;
    private volatile int currentDb = 0;
    private volatile ReplicationState state;
    private final Channel channel;
    private final long connectTime;
    private volatile long lastInteractionTime;
    private volatile boolean authenticated = false;
    private volatile int flags = 0;
    
    // 复制延迟统计
    private final AtomicLong replicationLag = new AtomicLong(0);
    private final AtomicLong lastAckTime = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private final AtomicLong totalCommandsReceived = new AtomicLong(0);
    
    public static final int SLAVE_FLAG_NONE = 0;
    public static final int SLAVE_FLAG_ONLINE = 1 << 0;
    public static final int SLAVE_FLAG_SYNCING = 1 << 1;
    public static final int SLAVE_FLAG_READONLY = 1 << 2;
    public static final int SLAVE_FLAG_FULL_SYNC = 1 << 3;
    public static final int SLAVE_FLAG_PARTIAL_SYNC = 1 << 4;
    
    public SlaveInfo(Channel channel) {
        this.channel = channel;
        this.connectTime = System.currentTimeMillis();
        this.lastInteractionTime = connectTime;
        this.capabilities = new StringBuilder();
        this.offset = new AtomicLong(0);
        this.state = ReplicationState.DISCONNECTED;
        
        InetSocketAddress remoteAddress = (InetSocketAddress) channel.remoteAddress();
        this.ip = remoteAddress.getAddress().getHostAddress();
        this.port = remoteAddress.getPort();
        this.slaveId = this.ip + ":" + this.port;
    }
    
    public void setListeningPort(int port) { this.port = port; }
    public void setIp(String ip) { this.ip = ip; }
    
    public void addCapability(String capa) {
        if (capabilities.length() > 0) capabilities.append(",");
        capabilities.append(capa);
    }
    
    public boolean hasCapability(String capa) {
        return capabilities.toString().contains(capa);
    }
    
    public void updateOffset(long newOffset) {
        offset.set(newOffset);
        lastInteractionTime = System.currentTimeMillis();
        lastAckTime.set(lastInteractionTime);
    }
    
    public void incrementOffset(long delta) {
        offset.addAndGet(delta);
        lastInteractionTime = System.currentTimeMillis();
    }
    
    /**
     * 更新复制延迟
     * 
     * @param masterOffset 主节点当前偏移量
     */
    public void updateReplicationLag(long masterOffset) {
        long lag = masterOffset - offset.get();
        replicationLag.set(Math.max(0, lag));
    }
    
    /**
     * 增加接收字节数
     */
    public void addBytesReceived(long bytes) {
        totalBytesReceived.addAndGet(bytes);
    }
    
    /**
     * 增加接收命令数
     */
    public void incrementCommandsReceived() {
        totalCommandsReceived.incrementAndGet();
    }
    
    public void setFlags(int flags) { this.flags = flags; }
    public void addFlag(int flag) { this.flags |= flag; }
    public void removeFlag(int flag) { this.flags &= ~flag; }
    public boolean hasFlag(int flag) { return (this.flags & flag) != 0; }
    
    public String getSlaveId() { return slaveId; }
    public String getIp() { return ip; }
    public int getPort() { return port; }
    public String getCapabilities() { return capabilities.toString(); }
    public String getReplId() { return replId; }
    public void setReplId(String replId) { this.replId = replId; }
    public long getOffset() { return offset.get(); }
    public int getCurrentDb() { return currentDb; }
    public void setCurrentDb(int currentDb) { this.currentDb = currentDb; }
    public ReplicationState getState() { return state; }
    public void setState(ReplicationState state) { this.state = state; }
    public Channel getChannel() { return channel; }
    public long getConnectTime() { return connectTime; }
    public long getLastInteractionTime() { return lastInteractionTime; }
    public boolean isAuthenticated() { return authenticated; }
    public void setAuthenticated(boolean authenticated) { this.authenticated = authenticated; }
    public boolean isOnline() { return state == ReplicationState.ONLINE && hasFlag(SLAVE_FLAG_ONLINE); }
    public boolean isSyncing() { return hasFlag(SLAVE_FLAG_SYNCING); }
    
    /**
     * 获取复制延迟（字节）
     */
    public long getReplicationLag() {
        return replicationLag.get();
    }
    
    /**
     * 获取复制延迟（秒）
     */
    public long getReplicationLagSeconds() {
        long lastAck = lastAckTime.get();
        if (lastAck == 0) {
            return -1;
        }
        return (System.currentTimeMillis() - lastAck) / 1000;
    }
    
    /**
     * 获取总接收字节数
     */
    public long getTotalBytesReceived() {
        return totalBytesReceived.get();
    }
    
    /**
     * 获取总接收命令数
     */
    public long getTotalCommandsReceived() {
        return totalCommandsReceived.get();
    }
    
    /**
     * 获取连接时长（毫秒）
     */
    public long getConnectionDuration() {
        return System.currentTimeMillis() - connectTime;
    }
    
    public String getInfoString() {
        return String.format("slave%d:ip=%s,port=%d,state=%s,offset=%d,lag=%d",
                            slaveId.hashCode(), ip, port, state.getName(),
                            offset.get(), getReplicationLagSeconds());
    }
    
    @Override
    public String toString() {
        return "SlaveInfo{slaveId='" + slaveId + "', ip='" + ip + "', port=" + port +
               ", state=" + state + ", offset=" + offset.get() + 
               ", lag=" + getReplicationLag() + " bytes}";
    }
}
