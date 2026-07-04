package com.janeluo.luban.rds.server.cluster.testinfra;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NetworkSimulatorTest {

    @Test
    void testSimulateAndRestoreNodeDown() {
        TestCluster cluster = TestCluster.builder()
                .nodes(3)
                .basePort(7200)
                .build();
        try {
            cluster.start();

            NetworkSimulator simulator = new NetworkSimulator(cluster);
            String firstNodeId = cluster.getNodes().iterator().next().getNodeId();
            TestNode node = cluster.getNode(firstNodeId);
            assertNotNull(node, "节点应存在");

            simulator.simulateNodeDown(firstNodeId);
            assertFalse(node.getClusterBusServer().isRunning(),
                    "宕机后集群总线应已停止");

            simulator.restoreNetwork(firstNodeId);
            assertTrue(node.isStarted(), "恢复后节点应已启动");
            assertTrue(node.getClusterBusServer().isRunning(),
                    "恢复后集群总线应运行中");
        } finally {
            cluster.stop();
        }
    }
}
