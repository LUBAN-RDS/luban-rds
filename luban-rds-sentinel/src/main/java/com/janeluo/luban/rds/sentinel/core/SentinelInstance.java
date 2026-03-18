package com.janeluo.luban.rds.sentinel.core;

/**
 * 哨兵实例信息
 */
public class SentinelInstance {
    private final String sentinelId;
    private String host;
    private int port;
    private volatile long lastPingTime = System.currentTimeMillis();
    private volatile long lastPongTime = System.currentTimeMillis();
    private volatile long lastHelloTime = System.currentTimeMillis();
    
    /**
     * 该哨兵认为主节点下线的投票
     */
    private volatile boolean votedMasterDown = false;
    
    /**
     * 该哨兵投票给的领导者
     */
    private volatile String votedLeader;
    private volatile long votedLeaderEpoch = 0;
    
    public SentinelInstance(String sentinelId, String host, int port) {
        this.sentinelId = sentinelId;
        this.host = host;
        this.port = port;
    }
    
    // Getters and Setters
    public String getSentinelId() { return sentinelId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public long getLastPingTime() { return lastPingTime; }
    public void setLastPingTime(long lastPingTime) { this.lastPingTime = lastPingTime; }
    public long getLastPongTime() { return lastPongTime; }
    public void setLastPongTime(long lastPongTime) { this.lastPongTime = lastPongTime; }
    public long getLastHelloTime() { return lastHelloTime; }
    public void setLastHelloTime(long lastHelloTime) { this.lastHelloTime = lastHelloTime; }
    public boolean isVotedMasterDown() { return votedMasterDown; }
    public void setVotedMasterDown(boolean votedMasterDown) { this.votedMasterDown = votedMasterDown; }
    public String getVotedLeader() { return votedLeader; }
    public void setVotedLeader(String votedLeader) { this.votedLeader = votedLeader; }
    public long getVotedLeaderEpoch() { return votedLeaderEpoch; }
    public void setVotedLeaderEpoch(long votedLeaderEpoch) { this.votedLeaderEpoch = votedLeaderEpoch; }
}
