package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 故障检测器
 * <p>
 * 实现 PFAIL/FAIL 状态转换逻辑，负责检测节点故障并协调集群达成故障共识
 * </p>
 */
public class FailureDetector {

    private static final Logger logger = LoggerFactory.getLogger(FailureDetector.class);

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * 节点超时时间（毫秒）
     */
    private final long nodeTimeout;

    /**
     * 记录每个节点被哪些主节点标记为 PFAIL
     * key: 被标记的节点ID
     * value: 标记该节点为 PFAIL 的主节点ID集合
     */
    private final Map<String, Set<String>> pfailVotes;

    /**
     * 记录已确认 FAIL 的节点，避免重复广播
     */
    private final Set<String> confirmedFailNodes;

    /**
     * 构造方法
     *
     * @param clusterConfig 集群配置
     * @param nodeTimeout   节点超时时间（毫秒）
     */
    public FailureDetector(ClusterConfig clusterConfig, long nodeTimeout) {
        this.clusterConfig = clusterConfig;
        this.nodeTimeout = nodeTimeout;
        this.pfailVotes = new ConcurrentHashMap<>();
        this.confirmedFailNodes = ConcurrentHashMap.newKeySet();
    }

    /**
     * 检测节点是否超时
     * 超时则标记为 PFAIL
     */
    public void checkNodeTimeout() {
        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();
        ClusterNode myNode = clusterConfig.getMyNode();

        if (myNode == null) {
            return;
        }

        for (ClusterNode node : allNodes) {
            // 跳过本节点
            if (node.isMyself()) {
                continue;
            }

            // 跳过 HANDSHAKE 状态的节点（握手未完成，不进行故障检测）
            if (node.hasState(ClusterNodeState.HANDSHAKE)) {
                continue;
            }

            // 跳过已经标记为 FAIL 的节点
            if (node.isFail()) {
                continue;
            }

            // 检查节点是否超时
            long timeSinceLastPong = node.getTimeSinceLastPong();
            if (timeSinceLastPong > nodeTimeout) {
                // 标记为 PFAIL
                if (!node.isPfail()) {
                    node.addState(ClusterNodeState.PFAIL);
                    logger.warn("节点超时，标记为 PFAIL: nodeId={}, timeSinceLastPong={}ms, timeout={}ms",
                            node.getNodeId(), timeSinceLastPong, nodeTimeout);

                    // 记录本节点的 PFAIL 投票
                    recordPfailVote(node.getNodeId(), myNode.getNodeId());
                }
            } else {
                // 节点恢复正常，清除 PFAIL 状态
                if (node.isPfail()) {
                    node.removeState(ClusterNodeState.PFAIL);
                    logger.info("节点恢复正常，清除 PFAIL: nodeId={}", node.getNodeId());
                }
            }
        }
    }

    /**
     * 确认节点下线
     * 当多数主节点确认 PFAIL 后标记为 FAIL
     *
     * @param nodeId 节点ID
     * @return 是否成功标记为 FAIL
     */
    public boolean confirmNodeFail(String nodeId) {
        ClusterNode node = clusterConfig.getNode(nodeId);
        if (node == null) {
            logger.warn("无法确认节点 FAIL: 节点不存在, nodeId={}", nodeId);
            return false;
        }

        // 已经是 FAIL 状态
        if (node.isFail()) {
            return true;
        }

        // 必须先处于 PFAIL 状态
        if (!node.isPfail()) {
            return false;
        }

        // 检查是否达到多数同意
        if (isMajorityAgreed(nodeId)) {
            node.addState(ClusterNodeState.FAIL);
            node.removeState(ClusterNodeState.PFAIL);
            confirmedFailNodes.add(nodeId);

            logger.warn("节点被确认 FAIL: nodeId={}, address={}",
                    nodeId, node.getAddress());

            return true;
        }

        return false;
    }

    /**
     * 检查是否达到 FAIL 条件
     * 需要多数主节点同意
     *
     * @param nodeId 节点ID
     * @return 是否达到多数同意
     */
    public boolean isMajorityAgreed(String nodeId) {
        int masterCount = clusterConfig.getMasterCount();

        // 至少需要 2 个主节点才能进行多数投票
        if (masterCount < 2) {
            // 单节点集群，本节点标记 PFAIL 即可确认 FAIL
            return true;
        }

        // 计算需要的多数票数
        int majority = (masterCount / 2) + 1;

        // 获取当前投票数
        Set<String> votes = pfailVotes.get(nodeId);
        int voteCount = votes != null ? votes.size() : 0;

        logger.debug("检查 FAIL 多数条件: nodeId={}, votes={}, majority={}, masterCount={}",
                nodeId, voteCount, majority, masterCount);

        return voteCount >= majority;
    }

    /**
     * 清除节点的 FAIL/PFAIL 状态
     * 当节点恢复时调用
     *
     * @param nodeId 节点ID
     */
    public void clearNodeFailState(String nodeId) {
        ClusterNode node = clusterConfig.getNode(nodeId);
        if (node == null) {
            return;
        }

        boolean changed = false;

        if (node.isFail()) {
            node.removeState(ClusterNodeState.FAIL);
            confirmedFailNodes.remove(nodeId);
            changed = true;
            logger.info("节点恢复，清除 FAIL 状态: nodeId={}", nodeId);
        }

        if (node.isPfail()) {
            node.removeState(ClusterNodeState.PFAIL);
            changed = true;
            logger.info("节点恢复，清除 PFAIL 状态: nodeId={}", nodeId);
        }

        if (changed) {
            // 清除投票记录
            pfailVotes.remove(nodeId);
        }
    }

    /**
     * 记录 PFAIL 投票
     *
     * @param targetNodeId 被投票的节点ID
     * @param voterNodeId  投票的主节点ID
     */
    public void recordPfailVote(String targetNodeId, String voterNodeId) {
        pfailVotes.computeIfAbsent(targetNodeId, k -> ConcurrentHashMap.newKeySet()).add(voterNodeId);
        logger.debug("记录 PFAIL 投票: targetNodeId={}, voterNodeId={}", targetNodeId, voterNodeId);
    }

    /**
     * 从 Gossip 消息中处理 PFAIL 投票
     *
     * @param nodeInfo Gossip 节点信息
     */
    public void processGossipPfailVote(GossipNodeInfo nodeInfo) {
        if (nodeInfo == null) {
            return;
        }

        String targetNodeId = nodeInfo.getNodeId();

        // 检查是否包含 PFAIL 标志
        if (nodeInfo.isPfail()) {
            logger.debug("处理 Gossip PFAIL 信息: targetNodeId={}", targetNodeId);
        }
    }

    /**
     * 获取节点的 PFAIL 投票数
     *
     * @param nodeId 节点ID
     * @return 投票数
     */
    public int getPfailVoteCount(String nodeId) {
        Set<String> votes = pfailVotes.get(nodeId);
        return votes != null ? votes.size() : 0;
    }

    /**
     * 获取需要广播 FAIL 的节点列表
     *
     * @return 需要广播 FAIL 的节点ID集合
     */
    public Set<String> getNodesToBroadcastFail() {
        Set<String> result = new HashSet<>();

        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();
        for (ClusterNode node : allNodes) {
            // 跳过本节点
            if (node.isMyself()) {
                continue;
            }

            // 检查是否满足 FAIL 条件但尚未广播
            if (node.isPfail() && !node.isFail() && !confirmedFailNodes.contains(node.getNodeId())) {
                if (isMajorityAgreed(node.getNodeId())) {
                    result.add(node.getNodeId());
                }
            }
        }

        return result;
    }

    /**
     * 获取所有 PFAIL 节点
     *
     * @return PFAIL 节点ID集合
     */
    public Set<String> getPfailNodes() {
        Set<String> result = new HashSet<>();
        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();

        for (ClusterNode node : allNodes) {
            if (node.isPfail() && !node.isFail()) {
                result.add(node.getNodeId());
            }
        }

        return result;
    }

    /**
     * 获取所有 FAIL 节点
     *
     * @return FAIL 节点ID集合
     */
    public Set<String> getFailNodes() {
        Set<String> result = new HashSet<>();
        Collection<ClusterNode> allNodes = clusterConfig.getAllNodes();

        for (ClusterNode node : allNodes) {
            if (node.isFail()) {
                result.add(node.getNodeId());
            }
        }

        return result;
    }

    /**
     * 重置故障检测器状态
     */
    public void reset() {
        pfailVotes.clear();
        confirmedFailNodes.clear();
        logger.info("故障检测器状态已重置");
    }

    /**
     * 获取节点超时时间
     *
     * @return 节点超时时间（毫秒）
     */
    public long getNodeTimeout() {
        return nodeTimeout;
    }

    /**
     * 获取集群配置
     *
     * @return 集群配置
     */
    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }
}
