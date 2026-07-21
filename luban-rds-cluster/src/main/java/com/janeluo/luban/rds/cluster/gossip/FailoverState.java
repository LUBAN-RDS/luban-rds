package com.janeluo.luban.rds.cluster.gossip;

/**
 * FailoverManager 选举状态机状态
 */
public enum FailoverState {
    /**
     * 空闲：未参与选举
     */
    IDLE,
    /**
     * 候选态：已检测到 master FAIL，等待退避后广播 AUTH_REQUEST 或已广播正在收集 ACK
     */
    REQUESTING,
    /**
     * 已胜选：performFailover 已执行（瞬态，立即回 IDLE）
     */
    ELECTED
}
