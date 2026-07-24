package com.janeluo.luban.rds.cluster.lifecycle;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReplicationLifecycleListener 的 no-op 默认实现。
 * <p>
 * 供非集群模式、单元测试或未装配复制组件的场景使用。
 * </p>
 */
public class NoOpReplicationLifecycleListener implements ReplicationLifecycleListener {

    private static final Logger logger = LoggerFactory.getLogger(NoOpReplicationLifecycleListener.class);

    @Override
    public void replicateTo(ClusterNode master) {
        logger.debug("NoOp replicateTo: {}", master != null ? master.getNodeId() : "null");
    }

    @Override
    public void promoteToMaster() {
        logger.debug("NoOp promoteToMaster");
    }

    @Override
    public void demoteToSlave(ClusterNode master) {
        logger.debug("NoOp demoteToSlave: {}", master != null ? master.getNodeId() : "null");
    }
}
