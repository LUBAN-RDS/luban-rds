package com.janeluo.luban.rds.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Luban RDS 配置属性类
 * 
 * <p>通过 application.yml 或 application.properties 配置 Luban RDS 服务器行为。
 * 配置前缀为 {@code luban.rds}。
 * 
 * @author janeluo
 * @since 1.0.0
 */
@ConfigurationProperties(prefix = "luban.rds")
public class LubanRdsProperties {

    /**
     * 是否启用 Luban RDS 自动配置
     */
    private boolean enabled = true;

    /**
     * 服务器监听端口，0 表示随机分配端口
     */
    private int port = 9736;

    /**
     * 服务器监听主机地址
     */
    private String host = "localhost";

    /**
     * Boss 线程数（连接接受线程）
     */
    private int bossThreads = 1;

    /**
     * Worker 线程数（I/O 处理线程），0 表示使用 CPU 核心数 * 2
     */
    private int workerThreads = 0;

    /**
     * 业务线程数（命令处理线程），0 表示使用 CPU 核心数
     */
    private int businessThreads = 0;

    /**
     * 最大允许连接数
     */
    private int maxConnections = 10000;

    /**
     * 连接空闲超时时间（秒）
     */
    private int idleTimeout = 300;

    /**
     * 最大 MONITOR 客户端数
     */
    private int maxMonitorClients = 100;

    /**
     * Redis 风格的 AUTH 命令密码
     */
    private String password;

    /**
     * 是否启用统计信息
     */
    private boolean statisticsEnabled = true;

    /**
     * 数据库数量
     */
    private int databases = 16;

    /**
     * 是否启用 RDB 持久化
     */
    private boolean rdbEnabled = false;

    /**
     * RDB 文件路径
     */
    private String rdbFilePath = "dump.rdb";

    /**
     * RDB 持久化间隔时间（秒）
     */
    private int rdbIntervalSeconds = 60;

    /**
     * 是否启用 AOF 持久化
     */
    private boolean aofEnabled = false;

    /**
     * AOF 文件路径
     */
    private String aofFilePath = "appendonly.aof";

    /**
     * AOF 同步策略：always, everysec, no
     */
    private AofSyncStrategy aofSyncStrategy = AofSyncStrategy.everysec;

    /**
     * Lua 脚本最大执行时间（毫秒）
     */
    private long luaScriptTimeout = 5000;

    /**
     * Lua 脚本最大大小（字节）
     */
    private int luaScriptMaxSize = 1024 * 1024;

    /**
     * 是否启用 Lua 沙箱模式
     */
    private boolean luaSandboxEnabled = true;

    /**
     * AOF 同步策略枚举
     */
    public enum AofSyncStrategy {

        /**
         * 每次写入都同步
         */
        always,

        /**
         * 每秒同步一次
         */
        everysec,

        /**
         * 不同步
         */
        no
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getBossThreads() {
        return bossThreads;
    }

    public void setBossThreads(int bossThreads) {
        this.bossThreads = bossThreads;
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

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getIdleTimeout() {
        return idleTimeout;
    }

    public void setIdleTimeout(int idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    public int getMaxMonitorClients() {
        return maxMonitorClients;
    }

    public void setMaxMonitorClients(int maxMonitorClients) {
        this.maxMonitorClients = maxMonitorClients;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isStatisticsEnabled() {
        return statisticsEnabled;
    }

    public void setStatisticsEnabled(boolean statisticsEnabled) {
        this.statisticsEnabled = statisticsEnabled;
    }

    public int getDatabases() {
        return databases;
    }

    public void setDatabases(int databases) {
        this.databases = databases;
    }

    public boolean isRdbEnabled() {
        return rdbEnabled;
    }

    public void setRdbEnabled(boolean rdbEnabled) {
        this.rdbEnabled = rdbEnabled;
    }

    public String getRdbFilePath() {
        return rdbFilePath;
    }

    public void setRdbFilePath(String rdbFilePath) {
        this.rdbFilePath = rdbFilePath;
    }

    public int getRdbIntervalSeconds() {
        return rdbIntervalSeconds;
    }

    public void setRdbIntervalSeconds(int rdbIntervalSeconds) {
        this.rdbIntervalSeconds = rdbIntervalSeconds;
    }

    public boolean isAofEnabled() {
        return aofEnabled;
    }

    public void setAofEnabled(boolean aofEnabled) {
        this.aofEnabled = aofEnabled;
    }

    public String getAofFilePath() {
        return aofFilePath;
    }

    public void setAofFilePath(String aofFilePath) {
        this.aofFilePath = aofFilePath;
    }

    public AofSyncStrategy getAofSyncStrategy() {
        return aofSyncStrategy;
    }

    public void setAofSyncStrategy(AofSyncStrategy aofSyncStrategy) {
        this.aofSyncStrategy = aofSyncStrategy;
    }

    public long getLuaScriptTimeout() {
        return luaScriptTimeout;
    }

    public void setLuaScriptTimeout(long luaScriptTimeout) {
        this.luaScriptTimeout = luaScriptTimeout;
    }

    public int getLuaScriptMaxSize() {
        return luaScriptMaxSize;
    }

    public void setLuaScriptMaxSize(int luaScriptMaxSize) {
        this.luaScriptMaxSize = luaScriptMaxSize;
    }

    public boolean isLuaSandboxEnabled() {
        return luaSandboxEnabled;
    }

    public void setLuaSandboxEnabled(boolean luaSandboxEnabled) {
        this.luaSandboxEnabled = luaSandboxEnabled;
    }
}
