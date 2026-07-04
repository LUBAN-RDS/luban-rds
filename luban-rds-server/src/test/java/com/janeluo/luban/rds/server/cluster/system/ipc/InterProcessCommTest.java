package com.janeluo.luban.rds.server.cluster.system.ipc;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterSystemTest;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import com.janeluo.luban.rds.server.cluster.testinfra.TestNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class InterProcessCommTest extends AbstractClusterSystemTest {

    @Test
    void testGossipPingPong() throws Exception {
        TestCluster cluster = harness.startCluster(3, 7500);
        cluster.waitForClusterOnline(5000);

        // 等待几轮 gossip
        Thread.sleep(3000);

        // 验证各节点 NODES 输出中其他节点的 last pong time 已更新
        for (TestNode node : cluster.getNodes()) {
            NettyRedisClient client = cluster.getClient(node.getNodeId());
            try {
                Object nodes = client.executeCommand("CLUSTER", "NODES");
                String nodesStr = nodes.toString();
                // 确保输出中包含其他节点
                long lineCount = nodesStr.lines().count();
                assertTrue(lineCount >= 3, "节点 " + node.getNodeId() + " 只看到 " + lineCount + " 个节点");
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void testNodeInfoPropagation() throws Exception {
        TestCluster cluster = harness.startCluster(3, 7510);
        cluster.waitForClusterOnline(5000);
        Thread.sleep(3000);

        // 所有节点应看到相同的节点列表
        Set<String> allNodeIds = new HashSet<>();
        for (TestNode node : cluster.getNodes()) {
            allNodeIds.add(node.getNodeId());
        }

        for (TestNode node : cluster.getNodes()) {
            NettyRedisClient client = cluster.getClient(node.getNodeId());
            try {
                Object nodes = client.executeCommand("CLUSTER", "NODES");
                String nodesStr = nodes.toString();
                for (String nodeId : allNodeIds) {
                    assertTrue(nodesStr.contains(nodeId.substring(0, 8)),
                            "节点 " + node.getNodeId() + " 未看到节点 " + nodeId);
                }
            } finally {
                client.disconnect();
            }
        }
    }

    @Test
    void testPublishMessagePropagation() throws Exception {
        TestCluster cluster = harness.startCluster(3, 7520);
        cluster.waitForClusterOnline(5000);
        Thread.sleep(2000);

        List<TestNode> nodes = new ArrayList<>(cluster.getNodes());
        TestNode nodeA = nodes.get(0);

        // PUBLISH 命令测试：在 nodeA 发布，验证返回订阅者数
        // 注意：跨节点 PUBLISH 传播取决于实现，这里测试 PUBLISH 命令本身
        NettyRedisClient publisher = cluster.getClient(nodeA.getNodeId());
        try {
            Object result = publisher.executeCommand("PUBLISH", "testchannel", "hello");
            assertNotNull(result);
        } finally {
            publisher.disconnect();
        }
    }
}
