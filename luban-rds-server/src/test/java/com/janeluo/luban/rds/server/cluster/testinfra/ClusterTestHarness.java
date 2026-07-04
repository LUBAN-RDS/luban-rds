package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.client.NettyRedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterTestHarness {
    private static final Logger log = LoggerFactory.getLogger(ClusterTestHarness.class);
    private TestCluster cluster;
    private NetworkSimulator networkSimulator;

    public TestCluster startCluster(int nodeCount, int basePort) {
        cluster = TestCluster.builder()
                .nodes(nodeCount)
                .basePort(basePort)
                .build();
        cluster.start();
        networkSimulator = new NetworkSimulator(cluster);
        return cluster;
    }

    public TestCluster startClusterWithSlots(int nodeCount, int basePort) {
        cluster = TestCluster.builder()
                .nodes(nodeCount)
                .basePort(basePort)
                .build();
        cluster.start();
        cluster.assignSlotsEvenly();
        cluster.waitForClusterOnline(5000);
        networkSimulator = new NetworkSimulator(cluster);
        return cluster;
    }

    public NetworkSimulator getNetworkSimulator() {
        return networkSimulator;
    }

    public TestCluster getCluster() { return cluster; }

    public void stopAll() {
        if (cluster != null) {
            cluster.stop();
            cluster = null;
        }
    }

    public NettyRedisClient getClient(String nodeId) {
        return cluster.getClient(nodeId);
    }
}
