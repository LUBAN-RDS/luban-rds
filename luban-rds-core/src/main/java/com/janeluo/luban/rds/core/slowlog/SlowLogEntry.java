package com.janeluo.luban.rds.core.slowlog;

import java.util.List;

/**
 * 慢日志条目
 * 
 * <p>表示一条慢日志记录，包含命令执行的相关信息。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class SlowLogEntry {
    
    /**
     * 日志唯一标识ID
     */
    private final long id;
    
    /**
     * Unix时间戳（秒）
     */
    private final long timestamp;
    
    /**
     * 执行时间（微秒）
     */
    private final long duration;
    
    /**
     * 命令参数列表
     */
    private final List<String> args;
    
    /**
     * 客户端IP地址
     */
    private final String clientIp;
    
    /**
     * 客户端名称
     */
    private final String clientName;

    public SlowLogEntry(long id, long timestamp, long duration, List<String> args, String clientIp, String clientName) {
        this.id = id;
        this.timestamp = timestamp;
        this.duration = duration;
        this.args = args;
        this.clientIp = clientIp;
        this.clientName = clientName;
    }

    public long getId() {
        return id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getDuration() {
        return duration;
    }

    public List<String> getArgs() {
        return args;
    }

    public String getClientIp() {
        return clientIp;
    }

    public String getClientName() {
        return clientName;
    }
}
