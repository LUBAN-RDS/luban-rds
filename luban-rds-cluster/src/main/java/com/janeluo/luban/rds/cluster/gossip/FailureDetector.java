package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
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
     * PFAIL 投票过期阈值（毫秒），对齐 Redis {@code NODE_TIMEOUT * 2}。
     * 超过此时间未被刷新的 failure report 会被 {@link #cleanupStaleFailureReports()} 丢弃。
     */
    private final long failureReportTtlMs;

    /**
     * 记录每个节点被哪些主节点标记为 PFAIL。
     * <p>
     * key: 被标记的节点ID；
     * value: 投票人 nodeId -> 投票时刻（ms）。
     * </p>
     * <p>
     * 带时间戳是 P1-8 的关键：对齐 Redis {@code clusterNodeCleanupFailureReports}，
     * 投票在 {@code NODE_TIMEOUT * 2} 后过期并被周期性清理；节点恢复时撤销其全部 failure reports。
     * 取代原先的 {@code Set<String>}（无时间戳、永不过期）。
     * </p>
     */
    private final Map<String, Map<String, Long>> pfailVotes;

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
        this.failureReportTtlMs = 2L * nodeTimeout;
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

                    // 记录本节点的 PFAIL 投票（P1-7：仅 master 投票可推 FAIL，
                    // 对齐 Redis——slave 的主观 PFAIL 不计入多数判定）。
                    if (myNode.isMaster()) {
                        recordPfailVote(node.getNodeId(), myNode.getNodeId());
                    }
                }
            } else {
                // 节点恢复正常，清除 PFAIL 状态
                if (node.isPfail()) {
                    node.removeState(ClusterNodeState.PFAIL);
                    logger.info("节点恢复正常，清除 PFAIL: nodeId={}", node.getNodeId());
                    // P1-8：节点恢复时撤销其收到的全部 failure reports，
                    // 对齐 Redis 节点恢复即清报告的语义，避免历史票在下一轮抖动中累积误 FAIL。
                    pfailVotes.remove(node.getNodeId());
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

        // 获取当前投票数（P1-7：仅统计来自 master 的有效票，
        // 对齐 Redis 只有 master 的 PFAIL 报告能把节点推 FAIL 的语义）。
        Map<String, Long> votes = pfailVotes.get(nodeId);
        int voteCount = countMasterVotes(votes);

        logger.debug("检查 FAIL 多数条件: nodeId={}, votes={}, majority={}, masterCount={}",
                nodeId, voteCount, majority, masterCount);

        return voteCount >= majority;
    }

    /**
     * 清除节点的 FAIL/PFAIL 状态
     * <p>
     * 当节点恢复时由 {@code GossipProtocol.handlePing/handlePong} 调用。
     * </p>
     * <p>
     * <b>FAIL 保护期</b>（对齐 Redis Cluster）：节点被标记 FAIL 后，MUST 在至少
     * {@code NODE_TIMEOUT * 2} 时间内保持 FAIL 状态。保护期内收到 PING/PONG
     * 不清除 FAIL（仅清除 PFAIL），防止 master 短暂抖动导致 slave failover 被取消。
     * 保护期过后，若节点确实恢复，方可清除 FAIL。
     * </p>
     * <p>
     * 注意：failover 提升路径（{@code FailoverManager.performFailover} /
     * {@code onFailoverResult} 中角色变更导致的 {@code removeState(FAIL)}）直接调用
     * {@code ClusterNode.removeState}，不经过本方法，因此不受保护期约束。
     * </p>
     *
     * @param nodeId 节点ID
     */
    public void clearNodeFailState(String nodeId) {
        ClusterNode node = clusterConfig.getNode(nodeId);
        if (node == null) {
            return;
        }

        // PFAIL 清除不受保护期影响（PFAIL 是本节点主观判断，收到 PONG 即可清除）
        if (node.isPfail()) {
            node.removeState(ClusterNodeState.PFAIL);
            // P1-8：撤销该节点收到的全部 failure reports，
            // 与 checkNodeTimeout 恢复分支保持一致，避免历史残留票误推 FAIL。
            pfailVotes.remove(nodeId);
            logger.info("节点恢复，清除 PFAIL 状态: nodeId={}", nodeId);
        }

        // FAIL 清除受保护期约束
        if (node.isFail()) {
            long failDuration = System.currentTimeMillis() - node.getFailTime();
            if (failDuration < 2L * nodeTimeout) {
                // 保护期内，拒绝清除 FAIL（对齐 Redis：FAIL 至少保持 NODE_TIMEOUT*2）
                logger.debug("FAIL 保护期内，拒绝清除 FAIL 状态: nodeId={}, failDuration={}ms, 保护期={}ms",
                        nodeId, failDuration, 2L * nodeTimeout);
                return;
            }
            node.removeState(ClusterNodeState.FAIL);
            confirmedFailNodes.remove(nodeId);
            pfailVotes.remove(nodeId);
            logger.info("FAIL 保护期已过，节点恢复清除 FAIL 状态: nodeId={}, failDuration={}ms",
                    nodeId, failDuration);
        }
    }

    /**
     * 记录 PFAIL 投票（带时间戳）。
     * <p>
     * 同一 voter 重复投票会刷新其时间戳，重置过期计时。
     * </p>
     *
     * @param targetNodeId 被投票的节点ID
     * @param voterNodeId  投票的主节点ID
     */
    public void recordPfailVote(String targetNodeId, String voterNodeId) {
        pfailVotes.computeIfAbsent(targetNodeId, k -> new ConcurrentHashMap<>())
                .put(voterNodeId, System.currentTimeMillis());
        logger.debug("记录 PFAIL 投票: targetNodeId={}, voterNodeId={}", targetNodeId, voterNodeId);
    }

    /**
     * 从 Gossip 消息中处理 PFAIL 投票。
     * <p>
     * Gossip section 携带的是"消息发送方"对目标节点的看法：
     * 如果发送方认为目标节点处于 PFAIL，则把发送方记入目标节点的投票集合。
     * 这是跨节点 PFAIL 共识传播的关键路径——缺少此步，
     * {@link #pfailVotes} 永远只含本节点自己的投票，
     * {@link #isMajorityAgreed(String)} 永远无法达到多数，
     * 节点永远不会被标记为 FAIL，自动故障转移无法触发。
     * </p>
     *
     * @param nodeInfo    Gossip section 中描述的目标节点信息
     * @param voterNodeId 消息发送方节点 ID（即投票人）
     */
    public void processGossipPfailVote(GossipNodeInfo nodeInfo, String voterNodeId) {
        if (nodeInfo == null || voterNodeId == null) {
            return;
        }

        // 仅在目标节点被标记为 PFAIL 时登记投票
        if (!nodeInfo.isPfail()) {
            return;
        }

        String targetNodeId = nodeInfo.getNodeId();
        if (targetNodeId == null) {
            return;
        }

        // 跳过自投票（本节点对自己的 PFAIL 投票无意义；
        // 本节点对其他节点的 PFAIL 投票已在 checkNodeTimeout 中通过 recordPfailVote 记录）
        if (voterNodeId.equals(targetNodeId)) {
            return;
        }

        // P1-7：仅 master 的 gossip PFAIL 投票才登记。
        // 对齐 Redis：只有 master 的 PFAIL 报告能把节点推 FAIL；
        // slave 的主观 PFAIL 视图不计入多数判定，避免 N 个 slave 票 + 1 个 master 票凑多数误 FAIL。
        ClusterNode voter = clusterConfig.getNode(voterNodeId);
        if (voter == null || !voter.isMaster()) {
            return;
        }

        recordPfailVote(targetNodeId, voterNodeId);
        logger.debug("处理 Gossip PFAIL 投票: targetNodeId={}, voterNodeId={}",
                targetNodeId, voterNodeId);
    }

    /**
     * 获取节点的 PFAIL 投票数（仅 master 投票，P1-7）。
     *
     * @param nodeId 节点ID
     * @return 投票数
     */
    public int getPfailVoteCount(String nodeId) {
        return countMasterVotes(pfailVotes.get(nodeId));
    }

    /**
     * 统计投票集合中来自 master 的有效票数（P1-7 双保险）。
     * <p>
     * 计票时再次校验投票人角色：即使历史残留 slave 票（来自旧版本或角色变更后未清理）也不计入，
     * 与 {@link #processGossipPfailVote} / {@link #checkNodeTimeout} 的记票门控形成纵深防御。
     * </p>
     *
     * @param votes 投票集合（voterId -> 投票时刻），可为 null
     * @return 来自 master 的有效票数
     */
    private int countMasterVotes(Map<String, Long> votes) {
        if (votes == null || votes.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String voterId : votes.keySet()) {
            ClusterNode voter = clusterConfig.getNode(voterId);
            if (voter != null && voter.isMaster()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 清理过期的 PFAIL failure reports（P1-8，对齐 Redis {@code clusterNodeCleanupFailureReports}）。
     * <p>
     * 丢弃投票时刻距今超过 {@code failureReportTtlMs}（{@code NODE_TIMEOUT * 2}）的票。
     * 由 {@code GossipTask} 每轮在 {@code checkNodeTimeouts} 之后调用，
     * 确保历史分区的旧票不会永久有效、避免抖动节点 "FAIL→恢复→再 FAIL" 循环。
     * </p>
     */
    public void cleanupStaleFailureReports() {
        if (pfailVotes.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Map<String, Long>>> outer = pfailVotes.entrySet().iterator();
        while (outer.hasNext()) {
            Map.Entry<String, Map<String, Long>> entry = outer.next();
            Map<String, Long> voters = entry.getValue();
            voters.entrySet().removeIf(e -> (now - e.getValue()) > failureReportTtlMs);
            if (voters.isEmpty()) {
                outer.remove();
            }
        }
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
