package com.janeluo.luban.rds.sentinel.core;

/**
 * 故障转移状态枚举
 */
public enum FailoverState {
    /**
     * 无故障转移
     */
    NONE("none"),
    
    /**
     * 等待故障转移开始
     */
    WAIT_START("wait_start"),
    
    /**
     * 选择新主节点
     */
    SELECT_SLAVE("select_slave"),
    
    /**
     * 提升从节点
     */
    PROMOTE_SLAVE("promote_slave"),
    
    /**
     * 重新配置从节点
     */
    RECONF_SLAVES("reconf_slaves"),
    
    /**
     * 故障转移完成
     */
    FAILOVER_DONE("failover_done");
    
    private final String name;
    
    FailoverState(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
}
