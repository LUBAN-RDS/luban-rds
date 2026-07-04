package com.janeluo.luban.rds.server.cluster.testinfra;

import com.janeluo.luban.rds.cluster.bus.ClusterBusServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 应用层网络模拟器（L1）。
 * <p>
 * 通过停止/恢复节点的集群总线与服务器，在应用进程内模拟节点宕机、
 * 网络分区等故障场景。真实网络延迟/丢包由 L2 层 ProcessManager 实现。
 * </p>
 */
public class NetworkSimulator {
    private static final Logger log = LoggerFactory.getLogger(NetworkSimulator.class);

    private final TestCluster cluster;
    private final Set<String> downNodes = ConcurrentHashMap.newKeySet();

    public NetworkSimulator(TestCluster cluster) {
        this.cluster = cluster;
    }

    /**
     * 模拟节点宕机：停止集群总线，标记节点为 down。
     */
    public void simulateNodeDown(String nodeId) {
        TestNode node = cluster.getNode(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        log.info("模拟节点 {} 宕机", nodeId);
        ClusterBusServer busServer = node.getClusterBusServer();
        if (busServer != null) {
            busServer.stop();
        }
        downNodes.add(nodeId);
    }

    /**
     * 模拟网络延迟（L1 层为 no-op）。
     * <p>
     * Gossip 心跳由 ScheduledExecutorService 调度，无法直接注入延迟。
     * 真实延迟测试在 L2 层通过 ProcessManager 实现。
     * </p>
     *
     * @param delayMs 延迟毫秒数（仅用于日志记录）
     */
    public void simulateNetworkDelay(String nodeId, long delayMs) {
        TestNode node = cluster.getNode(nodeId);
        if (node == null) throw new IllegalArgumentException("Unknown node: " + nodeId);
        log.info("模拟节点 {} 网络延迟 {}ms (L1 no-op)", nodeId, delayMs);
    }

    /**
     * 模拟网络分区：停止两个节点的集群总线。
     */
    public void simulateNetworkPartition(String nodeId1, String nodeId2) {
        TestNode node1 = cluster.getNode(nodeId1);
        TestNode node2 = cluster.getNode(nodeId2);
        if (node1 == null) throw new IllegalArgumentException("Unknown node: " + nodeId1);
        if (node2 == null) throw new IllegalArgumentException("Unknown node: " + nodeId2);
        log.info("模拟网络分区: {} <-> {}", nodeId1, nodeId2);
        ClusterBusServer bus1 = node1.getClusterBusServer();
        if (bus1 != null) {
            bus1.stop();
        }
        ClusterBusServer bus2 = node2.getClusterBusServer();
        if (bus2 != null) {
            bus2.stop();
        }
        downNodes.add(nodeId1);
        downNodes.add(nodeId2);
    }

    /**
     * 恢复节点网络：重启被停止的集群总线。
     * <p>
     * simulateNodeDown / simulateNetworkPartition 仅停止集群总线（ClusterBusServer），
     * 因此恢复时只需重新启动总线即可。不调用 node.forceStop()+start() 做整节点重启，
     * 因为 GossipProtocol 的 scheduler 在 stop() 后已终止，无法重新调度。
     * </p>
     */
    public void restoreNetwork(String nodeId) {
        TestNode node = cluster.getNode(nodeId);
        if (node == null) return;
        log.info("恢复节点 {} 网络", nodeId);
        if (downNodes.remove(nodeId)) {
            ClusterBusServer busServer = node.getClusterBusServer();
            if (busServer != null && !busServer.isRunning()) {
                try {
                    busServer.start();
                } catch (Exception e) {
                    downNodes.add(nodeId);
                    log.error("恢复节点 {} 网络失败", nodeId, e);
                }
            }
        }
    }

    /**
     * 恢复所有被模拟为 down 的节点。
     */
    public void restoreAll() {
        for (String nodeId : new HashSet<>(downNodes)) {
            restoreNetwork(nodeId);
        }
    }
}
