package com.janeluo.luban.rds.server.cluster.system.startup;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterSystemTest;
import com.janeluo.luban.rds.server.cluster.testinfra.ClusterTopology;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import com.janeluo.luban.rds.server.cluster.testinfra.TestNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClusterStartupTest extends AbstractClusterSystemTest {

    @Test
    void testClusterStartup_3Nodes() {
        TestCluster cluster = harness.startCluster(3, 7300);
        cluster.waitForClusterOnline(5000);

        assertEquals(3, cluster.getNodeCount());
        for (TestNode node : cluster.getNodes()) {
            NettyRedisClient client = cluster.getClient(node.getNodeId());
            try {
                Object info = client.executeCommand("CLUSTER", "INFO");
                assertNotNull(info);
                String infoStr = info.toString();
                assertTrue(infoStr.contains("cluster_known_nodes:3"));
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void testSlotDistribution_Even() {
        TestCluster cluster = harness.startClusterWithSlots(3, 7310);
        ClusterTopology topology = cluster.getTopology();

        int totalSlots = topology.getNodes().stream()
                .mapToInt(n -> n.assignedSlots)
                .sum();
        assertEquals(SlotUtils.CLUSTER_SLOTS, totalSlots);

        // 每节点约 5461 槽位
        for (ClusterTopology.NodeInfo node : topology.getNodes()) {
            assertTrue(node.assignedSlots > 5000 && node.assignedSlots < 6000,
                    "节点 " + node.nodeId + " 槽位数 " + node.assignedSlots + " 不在预期范围");
        }
    }

    @Test
    void testNodeDiscoveryViaMeet() {
        TestCluster cluster = harness.startCluster(3, 7320);
        cluster.waitForClusterOnline(5000);

        // 所有节点应能通过 CLUSTER NODES 看到其他节点
        for (TestNode node : cluster.getNodes()) {
            NettyRedisClient client = cluster.getClient(node.getNodeId());
            try {
                Object nodes = client.executeCommand("CLUSTER", "NODES");
                String nodesStr = nodes.toString();
                long nodeCount = nodesStr.lines().count();
                assertTrue(nodeCount >= 3, "节点 " + node.getNodeId() + " 只看到 " + nodeCount + " 个节点");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void testGossipConvergence() throws Exception {
        TestCluster cluster = harness.startCluster(3, 7330);
        cluster.waitForClusterOnline(5000);

        // 等待几轮 gossip 传播
        Thread.sleep(3000);

        // 所有节点拓扑视图应一致
        ClusterTopology topology = cluster.getTopology();
        assertTrue(topology.isConsistent(), "集群拓扑不一致");
        for (ClusterTopology.NodeInfo node : topology.getNodes()) {
            assertEquals(3, node.nodeCount, "节点 " + node.nodeId + " 看到的节点数不正确");
        }
    }

    @Test
    void testClusterInfoConsistency() {
        TestCluster cluster = harness.startClusterWithSlots(3, 7340);
        cluster.waitForClusterOnline(5000);

        List<ClusterTopology.NodeInfo> nodes = cluster.getTopology().getNodes();
        String firstState = nodes.get(0).state;
        for (ClusterTopology.NodeInfo node : nodes) {
            assertEquals(firstState, node.state,
                    "节点 " + node.nodeId + " 状态不一致: " + node.state + " vs " + firstState);
        }
    }
}
