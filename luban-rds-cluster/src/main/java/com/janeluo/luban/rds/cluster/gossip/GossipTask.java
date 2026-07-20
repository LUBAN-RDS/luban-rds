package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Gossip 定时任务
 * <p>
 * 定期执行 Gossip 协议的核心任务：
 * 1. 发送心跳到随机选择的节点
 * 2. 检测节点超时
 * 3. 更新集群状态
 * </p>
 */
public class GossipTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(GossipTask.class);

    /**
     * 每次心跳发送的目标节点数量
     */
    private static final int PING_TARGET_COUNT = 1;

    /**
     * Gossip 协议实例
     */
    private final GossipProtocol gossipProtocol;

    /**
     * 故障检测器
     */
    private final FailureDetector failureDetector;

    /**
     * 随机数生成器
     */
    private final Random random;

    /**
     * 构造方法
     *
     * @param gossipProtocol  Gossip 协议实例
     * @param failureDetector 故障检测器
     */
    public GossipTask(GossipProtocol gossipProtocol, FailureDetector failureDetector) {
        this.gossipProtocol = gossipProtocol;
        this.failureDetector = failureDetector;
        this.random = new Random();
    }

    @Override
    public void run() {
        if (!gossipProtocol.isStarted()) {
            return;
        }

        try {
            // 1. 发送心跳到随机选择的节点
            sendHeartbeats();

            // 2. 检测节点超时
            checkNodeTimeouts();

            // 3. 检查并广播 FAIL 消息
            checkAndBroadcastFail();

            // 4. 驱动故障转移选举（FAIL 状态已更新，此时检查最准确）
            FailoverManager failoverManager = gossipProtocol.getFailoverManager();
            if (failoverManager != null) {
                failoverManager.tick();
            }

            // 5. 更新集群状态
            updateClusterState();

            // 6. 保存集群配置（参照 Redis 7 serverCron 中 clusterSaveConfig 的周期性检查）
            gossipProtocol.saveClusterConfigIfNeeded();

        } catch (Exception e) {
            logger.error("Gossip 任务执行失败", e);
        }
    }

    /**
     * 发送心跳到随机选择的节点
     * <p>
     * 对于处于 HANDSHAKE 状态的节点，发送 MEET 推动握手完成（对齐 Redis 行为）；
     * 对于正常节点，随机选择一个发送 PING 心跳。
     * </p>
     */
    private void sendHeartbeats() {
        Collection<ClusterNode> allNodes = gossipProtocol.getClusterConfig().getAllNodes();

        // 收集 HANDSHAKE 节点：发送 MEET 推动握手完成
        List<ClusterNode> handshakeNodes = new ArrayList<>();
        // 收集正常节点（排除本节点、FAIL、HANDSHAKE）：发送 PING 心跳
        List<ClusterNode> candidateNodes = new ArrayList<>();

        for (ClusterNode node : allNodes) {
            if (node.isMyself() || node.isFail()) {
                continue;
            }
            if (node.hasState(ClusterNodeState.HANDSHAKE)) {
                handshakeNodes.add(node);
            } else {
                candidateNodes.add(node);
            }
        }

        // 对 HANDSHAKE 节点发送 MEET 推动握手完成。
        // 注意：不能调用 sendMeet(ip, port)，因其内部 findNodeByAddress 会因该 HANDSHAKE
        // 节点已存在而提前返回，导致 MEET 永远不会发出。这里改用 initiateMeetForDiscoveredNode，
        // 直接以已知真实节点ID建连并发送 MEET。
        for (ClusterNode handshakeNode : handshakeNodes) {
            logger.debug("对 HANDSHAKE 节点发送 MEET: nodeId={}, address={}",
                    handshakeNode.getNodeId(), handshakeNode.getFullAddress());
            gossipProtocol.initiateMeetForDiscoveredNode(handshakeNode);
        }

        if (candidateNodes.isEmpty()) {
            logger.debug("没有可用的节点发送心跳");
            return;
        }

        // 随机选择节点发送 PING
        int targetCount = Math.min(PING_TARGET_COUNT, candidateNodes.size());

        for (int i = 0; i < targetCount; i++) {
            int index = random.nextInt(candidateNodes.size());
            ClusterNode targetNode = candidateNodes.get(index);

            if (logger.isTraceEnabled()) {
                logger.trace("发送心跳到节点: nodeId={}", targetNode.getNodeId());
            }
            gossipProtocol.sendPing(targetNode);

            // 移除已选择的节点，避免重复选择
            candidateNodes.remove(index);
        }
    }

    /**
     * 检测节点超时
     */
    private void checkNodeTimeouts() {
        logger.debug("检测节点超时");
        failureDetector.checkNodeTimeout();
    }

    /**
     * 检查并广播 FAIL 消息
     */
    private void checkAndBroadcastFail() {
        Set<String> nodesToBroadcast = failureDetector.getNodesToBroadcastFail();

        for (String nodeId : nodesToBroadcast) {
            // 确认节点 FAIL
            if (failureDetector.confirmNodeFail(nodeId)) {
                // 广播 FAIL 消息
                gossipProtocol.broadcastFail(nodeId);
            }
        }
    }

    /**
     * 更新集群状态
     */
    private void updateClusterState() {
        // 检查集群是否健康
        // 如果有超过一半的主节点不可用，则集群状态为 fail
        int totalMasters = gossipProtocol.getClusterConfig().getMasterCount();
        int failMasters = countFailedMasters();

        String currentState = gossipProtocol.getClusterConfig().getState();
        String newState;

        // 计算可用主节点比例
        if (totalMasters == 0) {
            newState = "fail";
        } else {
            int availableMasters = totalMasters - failMasters;
            int majority = (totalMasters / 2) + 1;

            if (availableMasters >= majority) {
                newState = "ok";
            } else {
                newState = "fail";
            }
        }

        if (!newState.equals(currentState)) {
            gossipProtocol.getClusterConfig().setState(newState);
            logger.info("集群状态变更: {} -> {}", currentState, newState);
        }
    }

    /**
     * 统计已下线的主节点数量
     *
     * @return 已下线的主节点数量
     */
    private int countFailedMasters() {
        int count = 0;
        Collection<ClusterNode> allNodes = gossipProtocol.getClusterConfig().getAllNodes();

        for (ClusterNode node : allNodes) {
            if (node.isMaster() && node.isFail()) {
                count++;
            }
        }

        return count;
    }
}
