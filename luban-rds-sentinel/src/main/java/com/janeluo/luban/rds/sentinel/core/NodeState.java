package com.janeluo.luban.rds.sentinel.core;

/**
 * 节点状态枚举
 */
public enum NodeState {
    /**
     * 正常状态
     */
    NORMAL("normal"),
    
    /**
     * 主观下线
     */
    S_DOWN("s_down"),
    
    /**
     * 客观下线
     */
    O_DOWN("o_down"),
    
    /**
     * 已断开连接
     */
    DISCONNECTED("disconnected");
    
    private final String name;
    
    NodeState(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
}
