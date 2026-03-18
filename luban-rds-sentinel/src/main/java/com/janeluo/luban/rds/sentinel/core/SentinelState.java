package com.janeluo.luban.rds.sentinel.core;

/**
 * 哨兵状态枚举
 */
public enum SentinelState {
    /**
     * 初始状态
     */
    INIT("init"),
    
    /**
     * 正常运行状态
     */
    RUNNING("running"),
    
    /**
     * 故障转移进行中
     */
    FAILOVER_IN_PROGRESS("failover_in_progress"),
    
    /**
     * 关闭中
     */
    SHUTTING_DOWN("shutting_down"),
    
    /**
     * 已关闭
     */
    SHUTDOWN("shutdown");
    
    private final String name;
    
    SentinelState(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
}
