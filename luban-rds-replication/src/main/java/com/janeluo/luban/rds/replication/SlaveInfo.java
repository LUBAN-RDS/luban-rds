package com.janeluo.luban.rds.replication;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 从节点信息
 * 
 * 存储从节点的连接信息和状态
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
    }
    
    public void incrementOffset(long delta) {
        offset.addAndGet(delta);
        lastInteractionTime = System.currentTimeMillis();
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
    
    public String getInfoString() {
        return String.format("slave%d:ip=%s,port=%d,state=%s,offset=%d,lag=%d",
                            slaveId.hashCode(), ip, port, state.getName(),
                            offset.get(), (System.currentTimeMillis() - lastInteractionTime) / 1000);
    }
    
    @Override
    public String toString() {
        return "SlaveInfo{slaveId='" + slaveId + "', ip='" + ip + "', port=" + port +
               ", state=" + state + ", offset=" + offset.get() + '}';
    }
}
