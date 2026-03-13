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
