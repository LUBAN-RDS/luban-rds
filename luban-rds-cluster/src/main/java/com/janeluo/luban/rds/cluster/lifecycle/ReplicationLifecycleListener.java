package com.janeluo.luban.rds.cluster.lifecycle;

import com.janeluo.luban.rds.cluster.node.ClusterNode;

/**
 * 集群角色生命周期回调接口。
 * <p>
 * 在 cluster 模块定义，不依赖 replication 模块，由 server 层实现。
 * 当集群角色变更时，cluster 模块通过此接口通知 server 层启动或停止复制连接。
 * 实现必须保证重复相同目标的通知幂等。
 * </p>
 */
public interface ReplicationLifecycleListener {

    /**
     * 节点成为 slave 或更换 master。
     * 实现应停止旧连接（如有）并向新 master 发起 PSYNC。
     * 相同目标的重复调用不应创建重复连接。
     *
     * @param master 新的主节点，不应为 null
     */
    void replicateTo(ClusterNode master);

    /**
     * 本节点提升为 master。
     * 实现应停止上游复制连接但保留本地已同步数据。
     */
    void promoteToMaster();

    /**
     * 本节点降级为 slave。
     * 实现应按新 master 地址重新发起 PSYNC。
     * 相同目标的重复调用不应创建重复连接。
     *
     * @param master 新的主节点，不应为 null
     */
    void demoteToSlave(ClusterNode master);

    /**
     * 本节点当前的复制偏移量（master_repl_offset）。
     * <p>
     * 用于 failover 选举：候选 slave 在广播 AUTH_REQUEST 时携带自身复制偏移量，
     * 投票 master 据此择优（偏移量大者代表数据更新鲜，优先获票）。
     * </p>
     * <p>
     * 语义：slave 模式应返回已从上游同步到的偏移量；master 模式可返回本地
     * backlog 的 master_repl_offset（master 不参与 failover 候选，此值仅供查询）。
     * 默认返回 0，保证未装配复制组件的场景（NoOp、单测）向后兼容。
     * </p>
     *
     * @return 当前复制偏移量，不可用时返回 0
     */
    default long getReplicationOffset() {
        return 0L;
    }
}
