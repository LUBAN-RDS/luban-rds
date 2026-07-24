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
}
