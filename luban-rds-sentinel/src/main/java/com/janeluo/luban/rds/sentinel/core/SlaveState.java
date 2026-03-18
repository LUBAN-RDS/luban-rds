package com.janeluo.luban.rds.sentinel.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 从节点状态信息
 */
public class SlaveState {
    private final String slaveId;
    private String host;
    private int port;
    private volatile NodeState state = NodeState.NORMAL;
    
    private volatile String masterHost;
    private volatile int masterPort;
    private volatile String replId;
    private volatile long replOffset = 0;
    private volatile int priority = 100;
    private volatile boolean online = true;
    
    private final AtomicLong lastPingTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastPongTime = new AtomicLong(System.currentTimeMillis());
    private volatile long lastOkPingReply = System.currentTimeMillis();
    
    /**
     * 复制延迟（秒）
     */
    private volatile long lag = 0;
    
    public SlaveState(String slaveId, String host, int port) {
        this.slaveId = slaveId;
        this.host = host;
        this.port = port;
    }
    
    // Getters and Setters
    public String getSlaveId() { return slaveId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public NodeState getState() { return state; }
    public void setState(NodeState state) { this.state = state; }
    
    public String getMasterHost() { return masterHost; }
    public void setMasterHost(String masterHost) { this.masterHost = masterHost; }
    public int getMasterPort() { return masterPort; }
    public void setMasterPort(int masterPort) { this.masterPort = masterPort; }
    public String getReplId() { return replId; }
    public void setReplId(String replId) { this.replId = replId; }
    public long getReplOffset() { return replOffset; }
    public void setReplOffset(long replOffset) { this.replOffset = replOffset; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }
    
    public long getLastPingTime() { return lastPingTime.get(); }
    public void setLastPingTime(long time) { lastPingTime.set(time); }
    public long getLastPongTime() { return lastPongTime.get(); }
    public void setLastPongTime(long time) { lastPongTime.set(time); }
    public long getLastOkPingReply() { return lastOkPingReply; }
    public void setLastOkPingReply(long time) { lastOkPingReply = time; }
    
    public long getLag() { return lag; }
    public void setLag(long lag) { this.lag = lag; }
    
    public boolean isSDown() { return state == NodeState.S_DOWN; }
}
