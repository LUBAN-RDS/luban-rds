package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;

import java.util.ArrayList;
import java.util.List;

public class ClusterTopology {
    private final List<NodeInfo> nodes = new ArrayList<>();

    public ClusterTopology(TestCluster cluster) {
        for (TestNode node : cluster.getNodes()) {
            ClusterConfig config = node.getClusterConfig();
            NodeInfo info = new NodeInfo();
            info.nodeId = node.getNodeId();
            info.port = node.getPort();
            info.state = config.getState();
            info.nodeCount = config.getNodeCount();
            info.assignedSlots = config.getAssignedSlotCount();
            nodes.add(info);
        }
    }

    public List<NodeInfo> getNodes() { return nodes; }

    public boolean isConsistent() {
        if (nodes.isEmpty()) return false;
        String firstState = nodes.get(0).state;
        int firstCount = nodes.get(0).nodeCount;
        for (NodeInfo node : nodes) {
            if (!node.state.equals(firstState) || node.nodeCount != firstCount) {
                return false;
            }
        }
        return true;
    }

    public static class NodeInfo {
        public String nodeId;
        public int port;
        public String state;
        public int nodeCount;
        public int assignedSlots;
    }
}
