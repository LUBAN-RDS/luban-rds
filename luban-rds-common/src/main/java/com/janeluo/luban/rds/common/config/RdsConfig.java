package com.janeluo.luban.rds.common.config;

/**
 * Redis 服务配置类
 * 参考 Redis 配置文件格式，支持从配置文件加载配置
 */
public class RdsConfig {

    // ==================== 网络配置 ====================
    
    /**
     * 绑定地址
     */
    private String bind = "0.0.0.0";
    
    /**
     * 监听端口
     */
    private int port = 9736;
    
    /**
     * TCP 连接队列长度
     */
    private int tcpBacklog = 511;
    
    /**
     * 客户端空闲超时时间（秒），0 表示禁用
     */
    private int timeout = 0;
    
    /**
     * TCP keepalive 时间（秒）
     */
    private int tcpKeepalive = 300;

    // ==================== 通用配置 ====================
    
    /**
     * 是否以守护进程方式运行
     */
    private boolean daemonize = false;
    
    /**
     * 日志级别：debug, verbose, notice, warning
     */
    private String loglevel = "notice";
    
    /**
     * 日志文件路径
     */
    private String logfile = "";
    
    /**
     * 数据库数量
     */
    private int databases = 16;

    // ==================== 持久化配置 ====================
    
    /**
     * 持久化模式：rdb, aof, mixed, none
     */
    private String persistMode = "rdb";
    
    /**
     * 数据目录
     */
    private String dir = "./data";
    
    /**
     * RDB 文件名
     */
    private String dbfilename = "dump.rdb";
    
    /**
     * RDB 自动保存间隔（秒）
     */
    private int rdbSaveInterval = 60;
    
    /**
     * AOF 文件名
     */
    private String appendfilename = "appendonly.aof";
    
    /**
     * AOF 同步策略：always, everysec, no
     */
    private String appendfsync = "everysec";
    
    /**
     * AOF 同步间隔（秒）
     */
    private int aofFsyncInterval = 1;

    // ==================== 内存管理 ====================
    
    /**
     * 最大内存限制（字节），0 表示不限制
     */
    private long maxmemory = 0;
    
    /**
     * 内存淘汰策略
     */
    private String maxmemoryPolicy = "noeviction";

    // ==================== 安全配置 ====================
    
    /**
     * 访问密码
     */
    private String requirepass = "";
    
    // ==================== SlowLog 配置 ====================
    
    /**
     * 慢查询阈值（微秒），默认 10000
     */
    private long slowlogLogSlowerThan = 10000;
    
    /**
     * 慢查询日志最大长度，默认 128
     */
    private long slowlogMaxLen = 128;
    
    // ==================== Monitor 配置 ====================

    /**
     * 监控最大客户端连接数，默认 100
     */
    private int monitorMaxClients = 100;

    /**
     * 最大客户端连接数，默认 10000
     */
    private int maxclients = 10000;

    // ==================== 内存池配置 ====================

    /**
     * 是否使用池化 ByteBuf，默认 true
     * 池化内存可以减少内存分配和 GC 压力
     */
    private boolean usePool = true;

    /**
     * 内存泄漏检测级别：disabled, simple, advanced, paranoid
     * 默认 simple，生产环境建议 disabled 或 simple
     */
    private String leakDetection = "simple";

    /**
     * 内存碎片率阈值（百分比），超过此值自动触发碎片整理
     */
    private int memoryFragThreshold = 30;

    // ==================== 线程池配置 ====================

    /**
     * I/O 线程数，0 表示自动检测（CPU 核心数 * 2）
     */
    private int ioThreads = 0;

    /**
     * Worker 线程数，0 表示自动检测（CPU 核心数 * 2）
     */
    private int workerThreads = 0;

    /**
     * 业务线程数，0 表示自动检测（CPU 核心数）
     */
    private int businessThreads = 0;

    // ==================== Lua 配置 ====================

    /**
     * Lua 脚本执行超时时间（毫秒），默认 5000
     */
    private long luaTimeout = 5000L;

    /**
     * 是否启用 Lua 沙箱模式，默认 true
     */
    private boolean luaSandboxEnabled = true;

    /**
     * Lua 脚本最大字节数，默认 65536
     */
    private long luaMaxScriptBytes = 65536L;

    /**
     * Lua 脚本最大返回字节数，默认 1048576
     */
    private long luaMaxReturnBytes = 1048576L;

    /**
     * Lua 脚本最大操作数，默认 1000
     */
    private long luaMaxOpsPerScript = 1000L;

    /**
     * Lua 脚本让步间隔（毫秒），默认 1
     */
    private long luaYieldMs = 1L;

    /**
     * Lua 允许的模块列表（逗号分隔）
     */
    private String luaAllowedModules = "";

    /**
     * Lua 阻止的函数列表（逗号分隔）
     */
    private String luaBlockedFunctions = "";

    // ==================== 集群配置 ====================

    /**
     * 是否启用集群模式
     */
    private boolean clusterEnabled = false;

    /**
     * 集群配置文件路径
     */
    private String clusterConfigFile = "nodes.conf";

    /**
     * 节点超时时间（毫秒）
     */
    private long clusterNodeTimeout = 15000;

    /**
     * 对外宣布的 IP 地址
     */
    private String clusterAnnounceIp = "";

    /**
     * 对外宣布的端口
     */
    private int clusterAnnouncePort = 0;

    /**
     * 对外宣布的总线端口
     */
    private int clusterAnnounceBusPort = 0;

    /**
     * 从节点有效性因子
     */
    private int clusterSlaveValidityFactor = 10;

    /**
     * 迁移屏障
     */
    private int clusterMigrationBarrier = 1;

    /**
     * 是否需要全部槽位覆盖
     */
    private boolean clusterRequireFullCoverage = true;

    // ==================== 主从复制配置 ====================

    /**
     * 主节点地址（格式：host:port），空表示当前节点是主节点
     * 对应 Redis 的 slaveof/replicaof 配置
     */
    private String replicaof = "";

    /**
     * 主节点认证密码
     */
    private String masterauth = "";

    /**
     * 从节点只读模式，默认 true
     */
    private boolean slaveReadOnly = true;

    /**
     * 复制超时时间（秒），默认 60
     */
    private int replTimeout = 60;

    /**
     * 复制积压缓冲区大小（字节），默认 1MB
     */
    private long replBacklogSize = 1024 * 1024;

    /**
     * 复制积压缓冲区存活时间（秒），默认 3600
     */
    private int replBacklogTtl = 3600;

    /**
     * 从节点发送心跳间隔（秒），默认 10
     */
    private int replPingSlavePeriod = 10;

    /**
     * 复制连接断开后重连间隔（毫秒），默认 5000
     */
    private long replReconnectInterval = 5000;

    /**
     * 复制连接 TCP keepalive（秒），默认 60
     */
    private int replTcpKeepalive = 60;

    /**
     * 是否禁用 TCP_NODELAY，默认 false
     */
    private boolean replDisableTcpNodelay = false;

    // ==================== 哨兵配置 ====================

    /**
     * 是否启用哨兵模式
     */
    private boolean sentinelEnabled = false;

    /**
     * 哨兵监听端口，默认 26379
     */
    private int sentinelPort = 26379;

    /**
     * 哨兵配置文件路径
     */
    private String sentinelConfigFile = "sentinel.conf";

    /**
     * 哨兵监控的主节点配置（格式：name host port quorum）
     */
    private String sentinelMonitor = "";

    /**
     * 节点下线检测时间（毫秒），默认 30000
     */
    private long sentinelDownAfterMilliseconds = 30000;

    /**
     * 故障转移超时时间（毫秒），默认 180000
     */
    private long sentinelFailoverTimeout = 180000;

    /**
     * 故障转移后同时同步的从节点数，默认 1
     */
    private int sentinelParallelSyncs = 1;

    /**
     * 哨兵公告 IP
     */
    private String sentinelAnnounceIp = "";

    /**
     * 哨兵公告端口
     */
    private int sentinelAnnouncePort = 0;

    /**
     * 哨兵心跳间隔（毫秒），默认 1000
     */
    private long sentinelHeartbeatInterval = 1000;

    // ==================== Getter 和 Setter ====================

    public String getBind() {
        return bind;
    }

    public void setBind(String bind) {
        this.bind = bind;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getTcpBacklog() {
        return tcpBacklog;
    }

    public void setTcpBacklog(int tcpBacklog) {
        this.tcpBacklog = tcpBacklog;
    }

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getTcpKeepalive() {
        return tcpKeepalive;
    }

    public void setTcpKeepalive(int tcpKeepalive) {
        this.tcpKeepalive = tcpKeepalive;
    }

    public boolean isDaemonize() {
        return daemonize;
    }

    public void setDaemonize(boolean daemonize) {
        this.daemonize = daemonize;
    }

    public String getLoglevel() {
        return loglevel;
    }

    public void setLoglevel(String loglevel) {
        this.loglevel = loglevel;
    }

    public String getLogfile() {
        return logfile;
    }

    public void setLogfile(String logfile) {
        this.logfile = logfile;
    }

    public int getDatabases() {
        return databases;
    }

    public void setDatabases(int databases) {
        this.databases = databases;
    }

    public String getPersistMode() {
        return persistMode;
    }

    public void setPersistMode(String persistMode) {
        this.persistMode = persistMode;
    }

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getDbfilename() {
        return dbfilename;
    }

    public void setDbfilename(String dbfilename) {
        this.dbfilename = dbfilename;
    }

    public int getRdbSaveInterval() {
        return rdbSaveInterval;
    }

    public void setRdbSaveInterval(int rdbSaveInterval) {
        this.rdbSaveInterval = rdbSaveInterval;
    }

    public String getAppendfilename() {
        return appendfilename;
    }

    public void setAppendfilename(String appendfilename) {
        this.appendfilename = appendfilename;
    }

    public String getAppendfsync() {
        return appendfsync;
    }

    public void setAppendfsync(String appendfsync) {
        this.appendfsync = appendfsync;
    }

    public int getAofFsyncInterval() {
        return aofFsyncInterval;
    }

    public void setAofFsyncInterval(int aofFsyncInterval) {
        this.aofFsyncInterval = aofFsyncInterval;
    }

    public long getMaxmemory() {
        return maxmemory;
    }

    public void setMaxmemory(long maxmemory) {
        this.maxmemory = maxmemory;
    }

    public String getMaxmemoryPolicy() {
        return maxmemoryPolicy;
    }

    public void setMaxmemoryPolicy(String maxmemoryPolicy) {
        this.maxmemoryPolicy = maxmemoryPolicy;
    }

    public String getRequirepass() {
        return requirepass;
    }

    public void setRequirepass(String requirepass) {
        this.requirepass = requirepass;
    }

    public long getSlowlogLogSlowerThan() {
        return slowlogLogSlowerThan;
    }

    public void setSlowlogLogSlowerThan(long slowlogLogSlowerThan) {
        this.slowlogLogSlowerThan = slowlogLogSlowerThan;
    }

    public long getSlowlogMaxLen() {
        return slowlogMaxLen;
    }

    public void setSlowlogMaxLen(long slowlogMaxLen) {
        this.slowlogMaxLen = slowlogMaxLen;
    }

    public int getMonitorMaxClients() {
        return monitorMaxClients;
    }

    public void setMonitorMaxClients(int monitorMaxClients) {
        this.monitorMaxClients = monitorMaxClients;
    }

    public int getMaxclients() {
        return maxclients;
    }

    public void setMaxclients(int maxclients) {
        this.maxclients = maxclients;
    }

    public int getIoThreads() {
        return ioThreads;
    }

    public void setIoThreads(int ioThreads) {
        this.ioThreads = ioThreads;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getBusinessThreads() {
        return businessThreads;
    }

    public void setBusinessThreads(int businessThreads) {
        this.businessThreads = businessThreads;
    }

    public boolean isUsePool() {
        return usePool;
    }

    public void setUsePool(boolean usePool) {
        this.usePool = usePool;
    }

    public String getLeakDetection() {
        return leakDetection;
    }

    public void setLeakDetection(String leakDetection) {
        this.leakDetection = leakDetection;
    }

    public int getMemoryFragThreshold() {
        return memoryFragThreshold;
    }

    public void setMemoryFragThreshold(int memoryFragThreshold) {
        this.memoryFragThreshold = memoryFragThreshold;
    }

    public long getLuaTimeout() {
        return luaTimeout;
    }

    public void setLuaTimeout(long luaTimeout) {
        this.luaTimeout = luaTimeout;
    }

    public boolean isLuaSandboxEnabled() {
        return luaSandboxEnabled;
    }

    public void setLuaSandboxEnabled(boolean luaSandboxEnabled) {
        this.luaSandboxEnabled = luaSandboxEnabled;
    }

    public long getLuaMaxScriptBytes() {
        return luaMaxScriptBytes;
    }

    public void setLuaMaxScriptBytes(long luaMaxScriptBytes) {
        this.luaMaxScriptBytes = luaMaxScriptBytes;
    }

    public long getLuaMaxReturnBytes() {
        return luaMaxReturnBytes;
    }

    public void setLuaMaxReturnBytes(long luaMaxReturnBytes) {
        this.luaMaxReturnBytes = luaMaxReturnBytes;
    }

    public long getLuaMaxOpsPerScript() {
        return luaMaxOpsPerScript;
    }

    public void setLuaMaxOpsPerScript(long luaMaxOpsPerScript) {
        this.luaMaxOpsPerScript = luaMaxOpsPerScript;
    }

    public long getLuaYieldMs() {
        return luaYieldMs;
    }

    public void setLuaYieldMs(long luaYieldMs) {
        this.luaYieldMs = luaYieldMs;
    }

    public String getLuaAllowedModules() {
        return luaAllowedModules;
    }

    public void setLuaAllowedModules(String luaAllowedModules) {
        this.luaAllowedModules = luaAllowedModules;
    }

    public String getLuaBlockedFunctions() {
        return luaBlockedFunctions;
    }

    public void setLuaBlockedFunctions(String luaBlockedFunctions) {
        this.luaBlockedFunctions = luaBlockedFunctions;
    }

    public boolean isClusterEnabled() {
        return clusterEnabled;
    }

    public void setClusterEnabled(boolean clusterEnabled) {
        this.clusterEnabled = clusterEnabled;
    }

    public String getClusterConfigFile() {
        return clusterConfigFile;
    }

    public void setClusterConfigFile(String clusterConfigFile) {
        this.clusterConfigFile = clusterConfigFile;
    }

    public long getClusterNodeTimeout() {
        return clusterNodeTimeout;
    }

    public void setClusterNodeTimeout(long clusterNodeTimeout) {
        this.clusterNodeTimeout = clusterNodeTimeout;
    }

    public String getClusterAnnounceIp() {
        return clusterAnnounceIp;
    }

    public void setClusterAnnounceIp(String clusterAnnounceIp) {
        this.clusterAnnounceIp = clusterAnnounceIp;
    }

    public int getClusterAnnouncePort() {
        return clusterAnnouncePort;
    }

    public void setClusterAnnouncePort(int clusterAnnouncePort) {
        this.clusterAnnouncePort = clusterAnnouncePort;
    }

    public int getClusterAnnounceBusPort() {
        return clusterAnnounceBusPort;
    }

    public void setClusterAnnounceBusPort(int clusterAnnounceBusPort) {
        this.clusterAnnounceBusPort = clusterAnnounceBusPort;
    }

    public int getClusterSlaveValidityFactor() {
        return clusterSlaveValidityFactor;
    }

    public void setClusterSlaveValidityFactor(int clusterSlaveValidityFactor) {
        this.clusterSlaveValidityFactor = clusterSlaveValidityFactor;
    }

    public int getClusterMigrationBarrier() {
        return clusterMigrationBarrier;
    }

    public void setClusterMigrationBarrier(int clusterMigrationBarrier) {
        this.clusterMigrationBarrier = clusterMigrationBarrier;
    }

    public boolean isClusterRequireFullCoverage() {
        return clusterRequireFullCoverage;
    }

    public void setClusterRequireFullCoverage(boolean clusterRequireFullCoverage) {
        this.clusterRequireFullCoverage = clusterRequireFullCoverage;
    }

    public String getReplicaof() {
        return replicaof;
    }

    public void setReplicaof(String replicaof) {
        this.replicaof = replicaof;
    }

    public String getMasterauth() {
        return masterauth;
    }

    public void setMasterauth(String masterauth) {
        this.masterauth = masterauth;
    }

    public boolean isSlaveReadOnly() {
        return slaveReadOnly;
    }

    public void setSlaveReadOnly(boolean slaveReadOnly) {
        this.slaveReadOnly = slaveReadOnly;
    }

    public int getReplTimeout() {
        return replTimeout;
    }

    public void setReplTimeout(int replTimeout) {
        this.replTimeout = replTimeout;
    }

    public long getReplBacklogSize() {
        return replBacklogSize;
    }

    public void setReplBacklogSize(long replBacklogSize) {
        this.replBacklogSize = replBacklogSize;
    }

    public int getReplBacklogTtl() {
        return replBacklogTtl;
    }

    public void setReplBacklogTtl(int replBacklogTtl) {
        this.replBacklogTtl = replBacklogTtl;
    }

    public int getReplPingSlavePeriod() {
        return replPingSlavePeriod;
    }

    public void setReplPingSlavePeriod(int replPingSlavePeriod) {
        this.replPingSlavePeriod = replPingSlavePeriod;
    }

    public long getReplReconnectInterval() {
        return replReconnectInterval;
    }

    public void setReplReconnectInterval(long replReconnectInterval) {
        this.replReconnectInterval = replReconnectInterval;
    }

    public int getReplTcpKeepalive() {
        return replTcpKeepalive;
    }

    public void setReplTcpKeepalive(int replTcpKeepalive) {
        this.replTcpKeepalive = replTcpKeepalive;
    }

    public boolean isReplDisableTcpNodelay() {
        return replDisableTcpNodelay;
    }

    public void setReplDisableTcpNodelay(boolean replDisableTcpNodelay) {
        this.replDisableTcpNodelay = replDisableTcpNodelay;
    }

    public boolean isSentinelEnabled() {
        return sentinelEnabled;
    }

    public void setSentinelEnabled(boolean sentinelEnabled) {
        this.sentinelEnabled = sentinelEnabled;
    }

    public int getSentinelPort() {
        return sentinelPort;
    }

    public void setSentinelPort(int sentinelPort) {
        this.sentinelPort = sentinelPort;
    }

    public String getSentinelConfigFile() {
        return sentinelConfigFile;
    }

    public void setSentinelConfigFile(String sentinelConfigFile) {
        this.sentinelConfigFile = sentinelConfigFile;
    }

    public String getSentinelMonitor() {
        return sentinelMonitor;
    }

    public void setSentinelMonitor(String sentinelMonitor) {
        this.sentinelMonitor = sentinelMonitor;
    }

    public long getSentinelDownAfterMilliseconds() {
        return sentinelDownAfterMilliseconds;
    }

    public void setSentinelDownAfterMilliseconds(long sentinelDownAfterMilliseconds) {
        this.sentinelDownAfterMilliseconds = sentinelDownAfterMilliseconds;
    }

    public long getSentinelFailoverTimeout() {
        return sentinelFailoverTimeout;
    }

    public void setSentinelFailoverTimeout(long sentinelFailoverTimeout) {
        this.sentinelFailoverTimeout = sentinelFailoverTimeout;
    }

    public int getSentinelParallelSyncs() {
        return sentinelParallelSyncs;
    }

    public void setSentinelParallelSyncs(int sentinelParallelSyncs) {
        this.sentinelParallelSyncs = sentinelParallelSyncs;
    }

    public String getSentinelAnnounceIp() {
        return sentinelAnnounceIp;
    }

    public void setSentinelAnnounceIp(String sentinelAnnounceIp) {
        this.sentinelAnnounceIp = sentinelAnnounceIp;
    }

    public int getSentinelAnnouncePort() {
        return sentinelAnnouncePort;
    }

    public void setSentinelAnnouncePort(int sentinelAnnouncePort) {
        this.sentinelAnnouncePort = sentinelAnnouncePort;
    }

    public long getSentinelHeartbeatInterval() {
        return sentinelHeartbeatInterval;
    }

    public void setSentinelHeartbeatInterval(long sentinelHeartbeatInterval) {
        this.sentinelHeartbeatInterval = sentinelHeartbeatInterval;
    }

    @Override
    public String toString() {
        return "RedisConfig{" +
                "bind='" + bind + '\'' +
                ", port=" + port +
                ", persistMode='" + persistMode + '\'' +
                ", dir='" + dir + '\'' +
                ", databases=" + databases +
                ", maxmemory=" + maxmemory +
                '}';
    }
}
