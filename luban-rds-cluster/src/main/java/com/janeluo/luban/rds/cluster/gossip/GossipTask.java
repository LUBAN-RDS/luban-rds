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
     * 节点超时时间（毫秒），用于心跳优先策略（P1-9）。
     */
    private final long nodeTimeout;

    /**
     * 随机数生成器
     */
    private final Random random;

    /**
     * 构造方法（保留二参形式以向后兼容，内部按未启用优先策略处理）。
     *
     * @param gossipProtocol  Gossip 协议实例
     * @param failureDetector 故障检测器
     */
    public GossipTask(GossipProtocol gossipProtocol, FailureDetector failureDetector) {
        this(gossipProtocol, failureDetector, 0L);
    }

    /**
     * 完整构造方法。
     *
     * @param gossipProtocol  Gossip 协议实例
     * @param failureDetector 故障检测器
     * @param nodeTimeout     节点超时时间（毫秒），用于 P1-9 心跳优先策略；
     *                        传 0 时退化为纯随机（向后兼容旧行为）
     */
    public GossipTask(GossipProtocol gossipProtocol, FailureDetector failureDetector, long nodeTimeout) {
        this.gossipProtocol = gossipProtocol;
        this.failureDetector = failureDetector;
        this.nodeTimeout = nodeTimeout;
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

            // 2.1 清理过期的 PFAIL failure reports（P1-8，对齐 Redis clusterNodeCleanupFailureReports）
            failureDetector.cleanupStaleFailureReports();

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

            // 7. 清理 FORGET 黑名单过期条目（对齐 Redis clusterBlacklistCleanup 周期清理）
            gossipProtocol.cleanupForgetBlacklist();

            // 8. 清理超时未握手的 HANDSHAKE 节点（P1-21，对齐 Redis clusterCron 对 orphaned
            //    HANDSHAKE 节点的 free）。阈值取 2 * nodeTimeout，nodeTimeout<=0 时跳过。
            gossipProtocol.cleanupStaleHandshakeNodes(nodeTimeout * 2L);

        } catch (Exception e) {
            logger.error("Gossip 任务执行失败", e);
        }
    }

    /**
     * 发送心跳（P1-9：优先 PING pong 最老的节点，对齐 Redis clusterCron）。
     * <p>
     * 对于处于 HANDSHAKE 状态的节点，发送 MEET 推动握手完成（对齐 Redis 行为）；
     * 对于正常节点，采用"最老 pong 优先 + 随机兜底"策略发送 PING：
     * <ul>
     *   <li>第一优先：pong 时间最老且 PING 间隔已超过 nodeTimeout/2 的节点，
     *       确保故障检测延迟有界（对齐 Redis：先 PING 那些最久未应答的节点）；</li>
     *   <li>兜底：无优先目标时随机选 1 个，保留 gossip 传播的随机性。</li>
     * </ul>
     * 每轮仍只发 1 个 PING（PING_TARGET_COUNT），不增加心跳总流量。
     * nodeTimeout=0 时退化为纯随机（向后兼容）。
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

        // P1-9：优先 PING pong 最老且超 nodeTimeout/2 未 ping 的节点。
        ClusterNode target = selectPriorityPingTarget(candidateNodes);

        // 兜底：无优先目标时随机选 1 个
        if (target == null) {
            int index = random.nextInt(candidateNodes.size());
            target = candidateNodes.get(index);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("发送心跳到节点: nodeId={}, lastPongAgo={}ms",
                    target.getNodeId(), target.getTimeSinceLastPong());
        }
        gossipProtocol.sendPing(target);
    }

    /**
     * 选择本轮优先 PING 的目标：pong 时间最老且 PING 间隔已超 nodeTimeout/2 的节点（P1-9）。
     * <p>
     * 对齐 Redis clusterCron 的核心启发式：每轮优先 PING 那些最久未收到 PONG 的节点，
     * 使故障检测延迟在大集群下有界。只选一个目标（每轮 1 个 PING）。
     * </p>
     *
     * @param candidateNodes 候选节点（已排除本节点、FAIL、HANDSHAKE）
     * @return 优先目标节点，无满足条件者返回 null（由调用方随机兜底）
     */
    private ClusterNode selectPriorityPingTarget(List<ClusterNode> candidateNodes) {
        if (nodeTimeout <= 0) {
            // 未启用优先策略（向后兼容），交由随机兜底
            return null;
        }
        long stalePingThreshold = nodeTimeout / 2;
        long oldestPong = Long.MAX_VALUE;
        ClusterNode oldest = null;
        for (ClusterNode node : candidateNodes) {
            // 仅考虑 PING 间隔已超 nodeTimeout/2 的节点（近期已 PING 过的不重复打扰）
            if (node.getTimeSinceLastPing() < stalePingThreshold) {
                continue;
            }
            long pong = node.getLastPongTime();
            // lastPongTime 越小（越老）越优先
            if (pong < oldestPong) {
                oldestPong = pong;
                oldest = node;
            }
        }
        return oldest;
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
                // 多数主节点存活，还需确保所有 16384 个槽位已分配
                if (gossipProtocol.getClusterConfig().areAllSlotsAssigned()) {
                    newState = "ok";
                } else {
                    newState = "fail";
                }
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
