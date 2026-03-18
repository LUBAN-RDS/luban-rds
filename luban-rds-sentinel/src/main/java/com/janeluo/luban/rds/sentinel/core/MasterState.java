package com.janeluo.luban.rds.sentinel.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主节点状态信息
 */
public class MasterState {
    private String name;
    private String host;
    private int port;
    private volatile NodeState state = NodeState.NORMAL;
    private volatile FailoverState failoverState = FailoverState.NONE;
    private int quorum;
    private long downAfterMilliseconds;
    private long failoverTimeout;
    private int parallelSyncs;
    
    private final AtomicLong lastPingTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastPongTime = new AtomicLong(System.currentTimeMillis());
    private volatile long lastOkPingReply = System.currentTimeMillis();
    private volatile long lastPingReply = System.currentTimeMillis();
    private volatile int flags = 0;
    
    /**
     * 从节点信息
     */
    private final Map<String, SlaveState> slaves = new ConcurrentHashMap<>();
    
    /**
     * 其他哨兵信息
     */
    private final Map<String, SentinelInstance> sentinels = new ConcurrentHashMap<>();
    
    /**
     * 故障转移相关
     */
    private volatile String failoverSlave;
    private volatile long failoverStartTime;
    private volatile int failoverEpoch = 0;
    private volatile String leader;
    private volatile long leaderEpoch = 0;
    
    /**
     * 复制信息
     */
    private volatile String replId;
    private volatile long replOffset = 0;
    
    // Flags
    public static final int FLAG_NONE = 0;
    public static final int FLAG_S_DOWN = 1 << 0;
    public static final int FLAG_O_DOWN = 1 << 1;
    public static final int FLAG_FAILOVER_IN_PROGRESS = 1 << 2;
    public static final int FLAG_PROMOTED = 1 << 3;
    
    public MasterState(String name, String host, int port, int quorum) {
        this.name = name;
        this.host = host;
        this.port = port;
        this.quorum = quorum;
    }
    
    public void addFlag(int flag) { this.flags |= flag; }
    public void removeFlag(int flag) { this.flags &= ~flag; }
    public boolean hasFlag(int flag) { return (this.flags & flag) != 0; }
    
    // Getters and Setters
    public String getName() { return name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public NodeState getState() { return state; }
    public void setState(NodeState state) { this.state = state; }
    public FailoverState getFailoverState() { return failoverState; }
    public void setFailoverState(FailoverState failoverState) { this.failoverState = failoverState; }
    public int getQuorum() { return quorum; }
    public void setQuorum(int quorum) { this.quorum = quorum; }
    public long getDownAfterMilliseconds() { return downAfterMilliseconds; }
    public void setDownAfterMilliseconds(long downAfterMilliseconds) { 
        this.downAfterMilliseconds = downAfterMilliseconds; 
    }
    public long getFailoverTimeout() { return failoverTimeout; }
    public void setFailoverTimeout(long failoverTimeout) { this.failoverTimeout = failoverTimeout; }
    public int getParallelSyncs() { return parallelSyncs; }
    public void setParallelSyncs(int parallelSyncs) { this.parallelSyncs = parallelSyncs; }
    
    public long getLastPingTime() { return lastPingTime.get(); }
    public void setLastPingTime(long time) { lastPingTime.set(time); }
    public long getLastPongTime() { return lastPongTime.get(); }
    public void setLastPongTime(long time) { lastPongTime.set(time); }
    public long getLastOkPingReply() { return lastOkPingReply; }
    public void setLastOkPingReply(long time) { lastOkPingReply = time; }
    public long getLastPingReply() { return lastPingReply; }
    public void setLastPingReply(long time) { lastPingReply = time; }
    
    public Map<String, SlaveState> getSlaves() { return slaves; }
    public void addSlave(SlaveState slave) { slaves.put(slave.getSlaveId(), slave); }
    public void removeSlave(String slaveId) { slaves.remove(slaveId); }
    public SlaveState getSlave(String slaveId) { return slaves.get(slaveId); }
    
    public Map<String, SentinelInstance> getSentinels() { return sentinels; }
    public void addSentinel(SentinelInstance sentinel) { sentinels.put(sentinel.getSentinelId(), sentinel); }
    public void removeSentinel(String sentinelId) { sentinels.remove(sentinelId); }
    public SentinelInstance getSentinel(String sentinelId) { return sentinels.get(sentinelId); }
    
    public String getFailoverSlave() { return failoverSlave; }
    public void setFailoverSlave(String failoverSlave) { this.failoverSlave = failoverSlave; }
    public long getFailoverStartTime() { return failoverStartTime; }
    public void setFailoverStartTime(long failoverStartTime) { this.failoverStartTime = failoverStartTime; }
    public int getFailoverEpoch() { return failoverEpoch; }
    public void setFailoverEpoch(int failoverEpoch) { this.failoverEpoch = failoverEpoch; }
    public String getLeader() { return leader; }
    public void setLeader(String leader) { this.leader = leader; }
    public long getLeaderEpoch() { return leaderEpoch; }
    public void setLeaderEpoch(long leaderEpoch) { this.leaderEpoch = leaderEpoch; }
    
    public String getReplId() { return replId; }
    public void setReplId(String replId) { this.replId = replId; }
    public long getReplOffset() { return replOffset; }
    public void setReplOffset(long replOffset) { this.replOffset = replOffset; }
    
    public boolean isSDown() { return state == NodeState.S_DOWN || hasFlag(FLAG_S_DOWN); }
    public boolean isODown() { return state == NodeState.O_DOWN || hasFlag(FLAG_O_DOWN); }
    public boolean isFailoverInProgress() { return failoverState != FailoverState.NONE; }
}
