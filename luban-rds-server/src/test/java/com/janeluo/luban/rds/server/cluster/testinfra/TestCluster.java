package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TestCluster.class);

    private final Map<String, TestNode> nodes = new ConcurrentHashMap<>();
    private final int basePort;
    private final int nodeCount;

    private TestCluster(int basePort, int nodeCount) {
        this.basePort = basePort;
        this.nodeCount = nodeCount;
    }

    public static Builder builder() { return new Builder(); }

    public void start() {
        for (TestNode node : nodes.values()) {
            node.start();
        }
        // 等待端口就绪
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        // 节点间互相 CLUSTER MEET
        meetAllNodes();
    }

    public void stop() {
        for (TestNode node : nodes.values()) {
            node.stop();
        }
    }

    private void meetAllNodes() {
        List<TestNode> nodeList = new ArrayList<>(nodes.values());
        for (int i = 0; i < nodeList.size(); i++) {
            for (int j = i + 1; j < nodeList.size(); j++) {
                TestNode a = nodeList.get(i);
                TestNode b = nodeList.get(j);
                // 在 a 上 CLUSTER MEET b
                NettyRedisClient client = new NettyRedisClient("127.0.0.1", a.getPort());
                try {
                    client.connect();
                    client.executeCommand("CLUSTER", "MEET", "127.0.0.1",
                            String.valueOf(b.getPort()));
                } catch (Exception e) {
                    log.warn("MEET failed: {} -> {} : {}", a.getNodeId(), b.getNodeId(), e.getMessage());
                } finally {
                    try {
                        client.disconnect();
                    } catch (Exception ignore) {
                        // best-effort cleanup
                    }
                }
            }
        }
    }

    public void assignSlotsEvenly() {
        List<TestNode> masters = new ArrayList<>(nodes.values());
        int total = SlotUtils.CLUSTER_SLOTS;
        int perNode = total / masters.size();
        for (int i = 0; i < masters.size(); i++) {
            int start = i * perNode;
            int end = (i == masters.size() - 1) ? total - 1 : (start + perNode - 1);
            TestNode node = masters.get(i);
            // 分配槽位
            for (int slot = start; slot <= end; slot++) {
                node.getClusterConfig().setSlotOwner(slot, node.getNodeId());
                node.getSlotManager().addSlots(slot);
            }
            log.info("节点 {} 分配槽位 {}-{}", node.getNodeId(), start, end);
        }
    }

    public TestNode getNode(String nodeId) { return nodes.get(nodeId); }
    public Collection<TestNode> getNodes() { return nodes.values(); }
    public int getNodeCount() { return nodes.size(); }

    public NettyRedisClient getClient(String nodeId) {
        TestNode node = nodes.get(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        NettyRedisClient client = new NettyRedisClient("127.0.0.1", node.getPort());
        client.connect();
        return client;
    }

    public void waitForClusterOnline(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allOk = true;
            for (TestNode node : nodes.values()) {
                if (!node.getClusterConfig().isClusterOk()) {
                    allOk = false;
                    break;
                }
            }
            if (allOk && !nodes.isEmpty()) return;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        log.warn("等待集群 online 超时 ({}ms)", timeoutMs);
    }

    public ClusterTopology getTopology() {
        return new ClusterTopology(this);
    }

    @Override
    public void close() {
        stop();
    }

    public static class Builder {
        private int nodeCount = 3;
        private int basePort = 7000;

        public Builder nodes(int count) { this.nodeCount = count; return this; }
        public Builder basePort(int port) { this.basePort = port; return this; }

        public TestCluster build() {
            TestCluster cluster = new TestCluster(basePort, nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                TestNodeConfig config = TestNodeConfig.builder()
                        .port(basePort + i)
                        .clusterEnabled(true)
                        .build();
                TestNode node = new TestNode(config);
                cluster.nodes.put(node.getNodeId(), node);
            }
            return cluster;
        }
    }
}
