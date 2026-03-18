package com.janeluo.luban.rds.replication;

/**
 * 复制相关常量
 * 参考 Redis 复制协议定义
 */
public class ReplicationConstants {
    
    /**
     * 默认复制 ID 长度（40 字符十六进制）
     */
    public static final int REPL_ID_LENGTH = 40;
    
    /**
     * 默认复制 ID（未初始化时使用）
     */
    public static final String DEFAULT_REPL_ID = "0000000000000000000000000000000000000000";
    
    /**
     * 复制能力标志：支持部分重同步
     */
    public static final String REPL_CAPA_PSYNC2 = "psync2";
    
    /**
     * 复制能力标志：支持部分重同步（旧版本）
     */
    public static final String REPL_CAPA_PSYNC = "psync";
    
    /**
     * 复制能力标志：支持 EOF 风格的 RDB 传输
     */
    public static final String REPL_CAPA_EOF = "eof";
    
    /**
     * 默认心跳间隔（秒）
     */
    public static final int DEFAULT_PING_PERIOD = 10;
    
    /**
     * 默认复制超时时间（秒）
     */
    public static final int DEFAULT_REPL_TIMEOUT = 60;
    
    /**
     * 默认复制积压缓冲区大小（1MB）
     */
    public static final int DEFAULT_BACKLOG_SIZE = 1024 * 1024;
    
    /**
     * 默认复制积压缓冲区 TTL（秒）
     */
    public static final int DEFAULT_BACKLOG_TTL = 3600;
    
    /**
     * 复制协议版本
     */
    public static final int REPL_PROTOCOL_VERSION = 4;
    
    /**
     * 从节点监听端口（默认使用主服务器端口 + 10000）
     */
    public static final int DEFAULT_SLAVE_LISTEN_PORT_OFFSET = 10000;
    
    private ReplicationConstants() {
        // 私有构造函数，防止实例化
    }
}
