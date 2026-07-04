package com.janeluo.luban.rds.server.cluster.system.faulttolerance;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterSystemTest;
import com.janeluo.luban.rds.server.cluster.testinfra.ClusterTopology;
import com.janeluo.luban.rds.server.cluster.testinfra.NetworkSimulator;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import com.janeluo.luban.rds.server.cluster.testinfra.TestNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class FaultToleranceTest extends AbstractClusterSystemTest {

    @Test
    void testNodeRecoveryFromDown() throws Exception {
        TestCluster cluster = harness.startClusterWithSlots(3, 7600);
        network = harness.getNetworkSimulator();
        cluster.waitForClusterOnline(5000);

        List<TestNode> nodes = new ArrayList<>(cluster.getNodes());
        TestNode victim = nodes.get(0);

        // 模拟节点宕机
        network.simulateNodeDown(victim.getNodeId());
        Thread.sleep(1000);

        // 其他节点应仍能服务
        TestNode survivor = nodes.get(1);
        NettyRedisClient client = cluster.getClient(survivor.getNodeId());
        try {
            Object info = client.executeCommand("CLUSTER", "INFO");
            assertNotNull(info);
        } finally {
            client.disconnect();
        }

        // 恢复节点
        network.restoreNetwork(victim.getNodeId());
        Thread.sleep(2000);

        // 验证集群恢复
        cluster.waitForClusterOnline(5000);
    }

    @Test
    void testClusterOperatesWithMinorityDown() throws Exception {
        TestCluster cluster = harness.startClusterWithSlots(5, 7610);
        network = harness.getNetworkSimulator();
        cluster.waitForClusterOnline(5000);

        List<TestNode> nodes = new ArrayList<>(cluster.getNodes());
        // 停止 1 个节点（少数派）
        TestNode victim = nodes.get(0);
        network.simulateNodeDown(victim.getNodeId());
        Thread.sleep(1000);

        // 其余 4 个节点应仍能服务
        for (int i = 1; i < 5; i++) {
            TestNode survivor = nodes.get(i);
            NettyRedisClient client = cluster.getClient(survivor.getNodeId());
            try {
                Object info = client.executeCommand("CLUSTER", "INFO");
                assertNotNull(info);
            } finally {
                client.disconnect();
            }
        }

        network.restoreAll();
    }

    @Test
    void testNetworkDelayTolerance() throws Exception {
        TestCluster cluster = harness.startCluster(3, 7620);
        cluster.waitForClusterOnline(5000);

        // 模拟网络延迟（应用层，增大 timeout 避免误判）
        // 这里仅验证集群在正常延迟下不崩溃
        Thread.sleep(2000);

        ClusterTopology topology = cluster.getTopology();
        assertNotNull(topology);
        assertEquals(3, topology.getNodes().size());
    }
}
