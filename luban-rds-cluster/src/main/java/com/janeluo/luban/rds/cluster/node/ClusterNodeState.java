package com.janeluo.luban.rds.cluster.node;

/**
 * 集群节点状态枚举
 * <p>
 * 定义了Redis集群节点可能的状态标志
 * </p>
 */
public enum ClusterNodeState {
    /**
     * 握手状态 - 节点正在进行握手过程
     */
    HANDSHAKE,

    /**
     * 无地址 - 节点没有已知地址
     */
    NOADDR,

    /**
     * 无标志 - 节点没有任何特殊标志
     */
    NOFLAGS,

    /**
     * 主节点 - 节点是主节点
     */
    MASTER,

    /**
     * 从节点 - 节点是从节点
     */
    SLAVE,

    /**
     * 本节点 - 表示当前节点自身
     */
    MYSELF,

    /**
     * 下线 - 节点已被标记为下线
     */
    FAIL,

    /**
     * 可能下线 - 节点可能已下线（PFAIL，Possible Fail）
     */
    PFAIL
}
