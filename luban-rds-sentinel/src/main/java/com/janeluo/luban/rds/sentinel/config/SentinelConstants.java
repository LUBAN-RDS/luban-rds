package com.janeluo.luban.rds.sentinel.config;

/**
 * 哨兵相关常量
 * 参考 Redis Sentinel 协议定义
 */
public class SentinelConstants {
    
    /**
     * 默认哨兵端口
     */
    public static final int DEFAULT_SENTINEL_PORT = 26379;
    
    /**
     * 默认监控间隔（毫秒）
     */
    public static final long DEFAULT_MONITOR_INTERVAL = 1000;
    
    /**
     * 默认下线检测时间（毫秒）
     */
    public static final long DEFAULT_DOWN_AFTER_MILLISECONDS = 30000;
    
    /**
     * 默认故障转移超时时间（毫秒）
     */
    public static final long DEFAULT_FAILOVER_TIMEOUT = 180000;
    
    /**
     * 默认并行同步数
     */
    public static final int DEFAULT_PARALLEL_SYNCS = 1;
    
    /**
     * 默认仲裁数量
     */
    public static final int DEFAULT_QUORUM = 2;
    
    /**
     * 默认 PING 间隔（毫秒）
     */
    public static final long DEFAULT_PING_INTERVAL = 1000;
    
    /**
     * 默认 INFO 间隔（毫秒）
     */
    public static final long DEFAULT_INFO_INTERVAL = 10000;
    
    /**
     * 默认 Pub/Sub hello 频道名称
     */
    public static final String SENTINEL_HELLO_CHANNEL = "__sentinel__:hello";
    
    /**
     * 默认 Pub/Sub 间隔（毫秒）
     */
    public static final long DEFAULT_PUBSUB_INTERVAL = 2000;
    
    /**
     * 哨兵 ID 长度（40 字符十六进制）
     */
    public static final int SENTINEL_ID_LENGTH = 40;
    
    /**
     * 默认选举超时时间（毫秒）
     */
    public static final long DEFAULT_ELECTION_TIMEOUT = 10000;
    
    /**
     * 默认重连间隔（毫秒）
     */
    public static final long DEFAULT_RECONNECT_INTERVAL = 5000;
    
    /**
     * 主观下线标志
     */
    public static final String S_DOWN = "s_down";
    
    /**
     * 客观下线标志
     */
    public static final String O_DOWN = "o_down";
    
    /**
     * 正常状态标志
     */
    public static final String NORMAL = "normal";
    
    /**
     * 故障转移进行中标志
     */
    public static final String FAILOVER_IN_PROGRESS = "failover_in_progress";
    
    /**
     * 哨兵配置文件后缀
     */
    public static final String CONFIG_FILE_SUFFIX = ".conf";
    
    private SentinelConstants() {
        // 私有构造函数，防止实例化
    }
}
