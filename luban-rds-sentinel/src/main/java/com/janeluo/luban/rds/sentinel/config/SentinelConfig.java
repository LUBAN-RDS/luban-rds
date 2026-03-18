package com.janeluo.luban.rds.sentinel.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 哨兵配置类
 * 管理哨兵相关的所有配置项
 */
public class SentinelConfig {
    
    /**
     * 哨兵 ID
     */
    private String sentinelId;
    
    /**
     * 哨兵端口
     */
    private int port = SentinelConstants.DEFAULT_SENTINEL_PORT;
    
    /**
     * 哨兵绑定地址
     */
    private String bind = "0.0.0.0";
    
    /**
     * 监控间隔（毫秒）
     */
    private long monitorInterval = SentinelConstants.DEFAULT_MONITOR_INTERVAL;
    
    /**
     * 下线检测时间（毫秒）
     */
    private long downAfterMilliseconds = SentinelConstants.DEFAULT_DOWN_AFTER_MILLISECONDS;
    
    /**
     * 故障转移超时时间（毫秒）
     */
    private long failoverTimeout = SentinelConstants.DEFAULT_FAILOVER_TIMEOUT;
    
    /**
     * 并行同步数
     */
    private int parallelSyncs = SentinelConstants.DEFAULT_PARALLEL_SYNCS;
    
    /**
     * PING 间隔（毫秒）
     */
    private long pingInterval = SentinelConstants.DEFAULT_PING_INTERVAL;
    
    /**
     * INFO 间隔（毫秒）
     */
    private long infoInterval = SentinelConstants.DEFAULT_INFO_INTERVAL;
    
    /**
     * Pub/Sub 间隔（毫秒）
     */
    private long pubsubInterval = SentinelConstants.DEFAULT_PUBSUB_INTERVAL;
    
    /**
     * 选举超时时间（毫秒）
     */
    private long electionTimeout = SentinelConstants.DEFAULT_ELECTION_TIMEOUT;
    
    /**
     * 重连间隔（毫秒）
     */
    private long reconnectInterval = SentinelConstants.DEFAULT_RECONNECT_INTERVAL;
    
    /**
     * 配置文件路径
     */
    private String configFile;
    
    /**
     * 认证密码
     */
    private String authPassword;
    
    /**
     * 是否启用日志
     */
    private boolean logEnabled = true;
    
    /**
     * 日志级别
     */
    private String logLevel = "info";
    
    /**
     * 监控的主节点配置
     * key: masterName, value: MasterMonitorConfig
     */
    private Map<String, MasterMonitorConfig> masterConfigs = new HashMap<>();
    
    /**
     * 主节点监控配置
     */
    public static class MasterMonitorConfig {
        private String name;
        private String host;
        private int port;
        private int quorum;
        private long downAfterMilliseconds;
        private long failoverTimeout;
        private int parallelSyncs;
        
        public MasterMonitorConfig(String name, String host, int port, int quorum) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.quorum = quorum;
            this.downAfterMilliseconds = SentinelConstants.DEFAULT_DOWN_AFTER_MILLISECONDS;
            this.failoverTimeout = SentinelConstants.DEFAULT_FAILOVER_TIMEOUT;
            this.parallelSyncs = SentinelConstants.DEFAULT_PARALLEL_SYNCS;
        }
        
        public String getName() { return name; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getQuorum() { return quorum; }
        public long getDownAfterMilliseconds() { return downAfterMilliseconds; }
        public void setDownAfterMilliseconds(long downAfterMilliseconds) { 
            this.downAfterMilliseconds = downAfterMilliseconds; 
        }
        public long getFailoverTimeout() { return failoverTimeout; }
        public void setFailoverTimeout(long failoverTimeout) { 
            this.failoverTimeout = failoverTimeout; 
        }
        public int getParallelSyncs() { return parallelSyncs; }
        public void setParallelSyncs(int parallelSyncs) { 
            this.parallelSyncs = parallelSyncs; 
        }
    }
    
    /**
     * 添加主节点监控配置
     */
    public void addMasterConfig(String name, String host, int port, int quorum) {
        masterConfigs.put(name, new MasterMonitorConfig(name, host, port, quorum));
    }
    
    /**
     * 获取主节点监控配置
     */
    public MasterMonitorConfig getMasterConfig(String name) {
        return masterConfigs.get(name);
    }
    
    /**
     * 移除主节点监控配置
     */
    public void removeMasterConfig(String name) {
        masterConfigs.remove(name);
    }
    
    /**
     * 获取所有主节点监控配置
     */
    public Map<String, MasterMonitorConfig> getMasterConfigs() {
        return new HashMap<>(masterConfigs);
    }
    
    // Getter and Setter methods
    
    public String getSentinelId() { return sentinelId; }
    public void setSentinelId(String sentinelId) { this.sentinelId = sentinelId; }
    
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    
    public String getBind() { return bind; }
    public void setBind(String bind) { this.bind = bind; }
    
    public long getMonitorInterval() { return monitorInterval; }
    public void setMonitorInterval(long monitorInterval) { this.monitorInterval = monitorInterval; }
    
    public long getDownAfterMilliseconds() { return downAfterMilliseconds; }
    public void setDownAfterMilliseconds(long downAfterMilliseconds) { 
        this.downAfterMilliseconds = downAfterMilliseconds; 
    }
    
    public long getFailoverTimeout() { return failoverTimeout; }
    public void setFailoverTimeout(long failoverTimeout) { this.failoverTimeout = failoverTimeout; }
    
    public int getParallelSyncs() { return parallelSyncs; }
    public void setParallelSyncs(int parallelSyncs) { this.parallelSyncs = parallelSyncs; }
    
    public long getPingInterval() { return pingInterval; }
    public void setPingInterval(long pingInterval) { this.pingInterval = pingInterval; }
    
    public long getInfoInterval() { return infoInterval; }
    public void setInfoInterval(long infoInterval) { this.infoInterval = infoInterval; }
    
    public long getPubsubInterval() { return pubsubInterval; }
    public void setPubsubInterval(long pubsubInterval) { this.pubsubInterval = pubsubInterval; }
    
    public long getElectionTimeout() { return electionTimeout; }
    public void setElectionTimeout(long electionTimeout) { this.electionTimeout = electionTimeout; }
    
    public long getReconnectInterval() { return reconnectInterval; }
    public void setReconnectInterval(long reconnectInterval) { this.reconnectInterval = reconnectInterval; }
    
    public String getConfigFile() { return configFile; }
    public void setConfigFile(String configFile) { this.configFile = configFile; }
    
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }
    
    public boolean isLogEnabled() { return logEnabled; }
    public void setLogEnabled(boolean logEnabled) { this.logEnabled = logEnabled; }
    
    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }
}
