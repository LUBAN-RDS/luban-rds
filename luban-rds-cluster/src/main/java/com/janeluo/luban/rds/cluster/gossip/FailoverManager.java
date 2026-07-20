package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 集群自动故障转移管理器
 * <p>
 * 持有选举状态机（候选侧）与投票授权记录（master 侧），
 * 由 {@link GossipTask#run()} 每轮调用 {@link #tick()} 驱动。
 * </p>
 * <p>
 * 线程模型：所有公共方法 synchronized 保护跨线程访问。
 * tick() 跑在 gossip-protocol 单线程调度器；
 * onAuthRequest/onAuthAck/onFailoverResult 跑在 Netty nioEventLoopGroup 线程。
 * </p>
 * <p>
 * 选举算法对齐 Redis Cluster：
 * <ul>
 *   <li>slave 检测 master FAIL → 退避抖动后广播 AUTH_REQUEST</li>
 *   <li>每个 master 每 currentEpoch 仅投一票（votesCast 去重）</li>
 *   <li>候选 slave 收到过半 master 授权 → 胜选 → performFailover 提升 → 广播 FailoverResult</li>
 *   <li>全网收到 FailoverResult 后按纪元裁决收敛拓扑</li>
 * </ul>
 * </p>
 */
public class FailoverManager {

    private static final Logger logger = LoggerFactory.getLogger(FailoverManager.class);

    /**
     * 退避抖动上限（毫秒），不同 slave 的 nodeId hashCode 不同以错峰广播
     */
    private static final long JITTER_BOUND_MS = 500L;

    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;
    private final ClusterStateManager stateManager;
    private final ClusterBusClient busClient;
    private final Runnable onTopologyChanged;
    private final long nodeTimeout;
    private final long gracePeriod;

    // ==================== 候选侧状态（slave 发起选举用） ====================
    private FailoverState state = FailoverState.IDLE;
    private long electionStartTime;
    private long requestDeadline;
    private long electionEpoch;
    private final Set<String> authVotes = new HashSet<>();
    private String failedMasterId;
    private boolean requestBroadcasted;

    // ==================== 投票侧状态（master 授权用，与本节点状态共存） ====================
    /**
     * 已投票记录：被投 slaveId → 投票时的 currentEpoch
     */
    private final Map<String, Long> votesCast = new HashMap<>();
    private long lastVoteEpoch;

    /**
     * 构造方法
     *
     * @param clusterConfig      集群配置
     * @param slotManager        槽位管理器
     * @param stateManager       集群状态管理器
     * @param busClient          集群总线客户端
     * @param onTopologyChanged  拓扑变更回调（持久化 nodes.conf）
     * @param nodeTimeout        节点超时时间（毫秒）
     * @param gracePeriod        选举退避窗口（毫秒，cluster-failover-grace-period）
     */
    public FailoverManager(ClusterConfig clusterConfig, SlotManager slotManager,
                           ClusterStateManager stateManager, ClusterBusClient busClient,
                           Runnable onTopologyChanged, long nodeTimeout, long gracePeriod) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.stateManager = stateManager;
        this.busClient = busClient;
        this.onTopologyChanged = onTopologyChanged;
        this.nodeTimeout = nodeTimeout;
        this.gracePeriod = gracePeriod;
    }

    public synchronized FailoverState getState() {
        return state;
    }

    // ==================== 候选侧：tick 驱动 ====================

    /**
     * 每轮由 GossipTask 调用，驱动选举状态机。
     */
    public synchronized void tick() {
        try {
            switch (state) {
                case IDLE:
                    tryStartElection();
                    break;
                case REQUESTING:
                    handleRequestingState();
                    break;
                case ELECTED:
                    // 瞬态，不应停留；安全回 IDLE
                    resetElectionState();
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            logger.error("FailoverManager.tick 异常", e);
        }
    }

    /**
     * IDLE 态：检查是否应进入选举（本节点是 slave 且其 master 已 FAIL）
     */
    private void tryStartElection() {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isSlave()) {
            return;
        }
        if (me.isFail() || me.isPfail()) {
            return;
        }
        String masterId = me.getMasterNodeId();
        if (masterId == null) {
            return;
        }
        ClusterNode master = clusterConfig.getNode(masterId);
        if (master == null || !master.isFail()) {
            return;
        }

        // 满足触发条件
        state = FailoverState.REQUESTING;
        electionStartTime = System.currentTimeMillis();
        long jitter = Math.abs(me.getNodeId().hashCode()) % JITTER_BOUND_MS;
        requestDeadline = electionStartTime + gracePeriod + jitter;
        failedMasterId = masterId;
        authVotes.clear();
        requestBroadcasted = false;
        logger.warn("slave 进入选举: nodeId={}, failedMasterId={}, {}ms 后广播请求",
                me.getNodeId(), failedMasterId, (requestDeadline - electionStartTime));
    }

    /**
     * REQUESTING 态：检查 master 恢复/选举超时/退避到期
     */
    private void handleRequestingState() {
        // master 已恢复（FAIL 清除）→ 回 IDLE
        ClusterNode master =
                failedMasterId != null ? clusterConfig.getNode(failedMasterId) : null;
        if (master != null && !master.isFail()) {
            logger.info("原 master 已恢复，取消选举: masterId={}", failedMasterId);
            resetElectionState();
            return;
        }

        // 选举超时（2 * nodeTimeout 未过半授权）→ 回 IDLE
        if (System.currentTimeMillis() - electionStartTime > 2L * nodeTimeout) {
            logger.warn("选举超时，回退 IDLE: failedMasterId={}", failedMasterId);
            resetElectionState();
            return;
        }

        // 退避到期 → 广播 AUTH_REQUEST
        if (!requestBroadcasted && System.currentTimeMillis() >= requestDeadline) {
            broadcastAuthRequest();
        }
    }

    /**
     * 广播 AUTH_REQUEST，自增 currentEpoch
     */
    private void broadcastAuthRequest() {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        electionEpoch = clusterConfig.getCurrentEpoch() + 1;
        clusterConfig.setCurrentEpoch(electionEpoch);
        requestBroadcasted = true;

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                me.getNodeId(),
                me.getConfigEpoch(),
                electionEpoch,
                0L);
        busClient.broadcast(req);
        logger.warn("广播选举请求: candidate={}, epoch={}", me.getNodeId(), electionEpoch);
    }

    private void resetElectionState() {
        state = FailoverState.IDLE;
        authVotes.clear();
        failedMasterId = null;
        requestBroadcasted = false;
        electionStartTime = 0L;
        requestDeadline = 0L;
    }

    // ==================== 投票侧：master 处理 AUTH_REQUEST ====================

    /**
     * master 节点处理 AUTH_REQUEST（候选 slave 请求投票）。
     * 由 GossipProtocol.handleFailoverAuthRequest 委托调用。
     *
     * @param req 授权请求消息
     */
    public synchronized void onAuthRequest(FailoverAuthRequestMessage req) {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isMaster() || me.isFail()) {
            // 仅健康 master 投票（FAIL master 视为不可达，对齐 Redis 行为）
            return;
        }

        long reqEpoch = req.getCurrentEpoch();
        long myEpoch = clusterConfig.getCurrentEpoch();

        // (1) 过期纪元拒绝
        if (reqEpoch < myEpoch) {
            logger.debug("拒绝过期 AUTH_REQUEST: reqEpoch={}, myEpoch={}", reqEpoch, myEpoch);
            return;
        }

        // (2) 落后则追平，新纪元清旧票
        if (reqEpoch > myEpoch) {
            clusterConfig.setCurrentEpoch(reqEpoch);
            lastVoteEpoch = reqEpoch;
            votesCast.clear();
        }

        String candidateId = req.getSenderNodeId();

        // (3) 本纪元已投该 slave → 幂等重发
        Long votedAt = votesCast.get(candidateId);
        if (votedAt != null && votedAt == reqEpoch) {
            sendAuthAck(candidateId, reqEpoch);
            return;
        }

        // (4) 本纪元已投他 slave → 拒绝
        if (!votesCast.isEmpty()) {
            logger.debug("本纪元已投他 slave，拒绝: votedFor={}, candidate={}",
                    votesCast.keySet(), candidateId);
            return;
        }

        // (5) 首投
        votesCast.put(candidateId, reqEpoch);
        sendAuthAck(candidateId, reqEpoch);
    }

    private void sendAuthAck(String candidateId, long epoch) {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        FailoverAuthAckMessage ack = new FailoverAuthAckMessage(
                me.getNodeId(),
                me.getConfigEpoch(),
                epoch,
                epoch);
        busClient.broadcast(ack);
        logger.info("投票授权: voter={}, candidate={}, epoch={}", me.getNodeId(), candidateId, epoch);
    }

    // ==================== 候选侧：slave 处理 AUTH_ACK ====================

    /**
     * 候选 slave 处理 AUTH_ACK（master 投票响应）。
     * 由 GossipProtocol.handleFailoverAuthAck 委托调用。
     *
     * @param ack 授权确认消息
     */
    public synchronized void onAuthAck(FailoverAuthAckMessage ack) {
        if (state != FailoverState.REQUESTING) {
            return;
        }
        String voterId = ack.getSenderNodeId();
        if (voterId == null) {
            return;
        }
        if (!authVotes.add(voterId)) {
            return;  // 重复授权，忽略
        }

        int masterCount = clusterConfig.getMasterCount();
        int majority = masterCount / 2 + 1;
        logger.info("收到授权票: voter={}, totalVotes={}, majority={}",
                voterId, authVotes.size(), majority);

        if (authVotes.size() >= majority) {
            performFailoverAndBroadcast();
        }
    }

    /**
     * 胜选：performFailover 提升 + 自增 epoch + 广播 FailoverResult
     */
    private void performFailoverAndBroadcast() {
        ClusterNode me = clusterConfig.getMyNode();
        ClusterNode oldMaster =
                failedMasterId != null ? clusterConfig.getNode(failedMasterId) : null;

        if (me == null || oldMaster == null) {
            resetElectionState();
            return;
        }

        performFailover(me, oldMaster);

        clusterConfig.incrementEpoch();
        me.setConfigEpoch(clusterConfig.getCurrentEpoch());
        state = FailoverState.ELECTED;

        FailoverResultMessage result = new FailoverResultMessage(
                me.getNodeId(),
                me.getNodeId(),
                clusterConfig.getCurrentEpoch(),
                me.getSlots());
        busClient.broadcast(result);
        notifyTopologyChanged();
        logger.warn("slave 自动提升为 master: nodeId={}, epoch={}, slotCount={}",
                me.getNodeId(), clusterConfig.getCurrentEpoch(), me.getSlotCount());

        resetElectionState();
    }

    // ==================== 手动故障转移入口 ====================

    /**
     * 手动 CLUSTER FAILOVER [FORCE|TAKEOVER] 入口。
     * 不经选举状态机、不广播 RESULT（直接接管语义），
     * 但保留原 performFailover 的 epoch 自增行为（手动接管也需要更高的 configEpoch 使全网收敛）。
     *
     * @param slaveNode  当前 slave 节点（将被提升）
     * @param masterNode 原 master 节点（将被降级）
     */
    public synchronized void performManualFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        performFailover(slaveNode, masterNode);
        clusterConfig.incrementEpoch();
        slaveNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
        notifyTopologyChanged();
    }

    /**
     * 执行实际的 slave→master 提升（槽位继承、master 降级）。
     * 从 ClusterCommandHandler 抽取，手动/自动共用。
     */
    private void performFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        slaveNode.removeState(ClusterNodeState.SLAVE);
        slaveNode.addState(ClusterNodeState.MASTER);
        slaveNode.setMasterNodeId(null);

        BitSet masterSlots = masterNode.getSlots();
        for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
            slaveNode.addSlot(i);
            slotManager.setSlotOwner(i, slaveNode.getNodeId());
        }

        masterNode.clearSlots();
        masterNode.removeState(ClusterNodeState.MASTER);
        masterNode.addState(ClusterNodeState.SLAVE);
        masterNode.setMasterNodeId(slaveNode.getNodeId());
        // 降级时清除原 master 的 FAIL/PFAIL（它已恢复为新 master 的 slave 角色）
        masterNode.removeState(ClusterNodeState.FAIL);
        masterNode.removeState(ClusterNodeState.PFAIL);

        stateManager.updateClusterState();
    }

    // ==================== 全节点：处理 FailoverResult ====================

    /**
     * 全节点处理 FailoverResult（胜选广播）。
     * 由 GossipProtocol.handleFailoverResult 委托调用。
     *
     * @param msg 胜选结果消息
     */
    public synchronized void onFailoverResult(FailoverResultMessage msg) {
        long myEpoch = clusterConfig.getCurrentEpoch();

        // 纪元裁决：旧纪元忽略（防回放）
        if (msg.getNewConfigEpoch() < myEpoch) {
            logger.debug("忽略旧纪元 FailoverResult: msgEpoch={}, myEpoch={}",
                    msg.getNewConfigEpoch(), myEpoch);
            return;
        }

        ClusterNode winner = clusterConfig.getNode(msg.getWinnerNodeId());
        if (winner == null) {
            logger.warn("收到 FailoverResult 但 winner 不存在: winnerId={}",
                    msg.getWinnerNodeId());
            return;
        }

        // winner 提权
        winner.removeState(ClusterNodeState.SLAVE);
        winner.addState(ClusterNodeState.MASTER);
        winner.removeState(ClusterNodeState.FAIL);
        winner.removeState(ClusterNodeState.PFAIL);
        winner.setMasterNodeId(null);
        winner.setConfigEpoch(msg.getNewConfigEpoch());

        // 槽位转移
        BitSet inherited = msg.getInheritedSlots();
        if (inherited != null) {
            winner.setSlots((BitSet) inherited.clone());
            for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
                slotManager.setSlotOwner(i, winner.getNodeId());
            }
        }

        // 原 master（持有这些槽位且非 winner 的旧 master）降级为 winner 的 slave
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.isMaster() && !node.getNodeId().equals(winner.getNodeId())
                    && sharesAnySlot(node, inherited)) {
                node.clearSlots();
                node.removeState(ClusterNodeState.MASTER);
                node.addState(ClusterNodeState.SLAVE);
                node.setMasterNodeId(winner.getNodeId());
                // 降级时清除 FAIL/PFAIL（原 master 已恢复为 winner 的 slave 角色）
                node.removeState(ClusterNodeState.FAIL);
                node.removeState(ClusterNodeState.PFAIL);
                logger.info("原 master 降级为 slave: oldMaster={}, newMaster={}",
                        node.getNodeId(), winner.getNodeId());
            }
        }

        clusterConfig.setCurrentEpoch(msg.getNewConfigEpoch());
        notifyTopologyChanged();
        logger.warn("应用 FailoverResult: winner={}, epoch={}, slotCount={}",
                winner.getNodeId(), msg.getNewConfigEpoch(), winner.getSlotCount());

        // 若本节点正在对该 master 选举（自己胜选的消息回来），取消
        if (state == FailoverState.REQUESTING
                && msg.getWinnerNodeId().equals(clusterConfig.getMyNodeId() == null
                        ? "" : clusterConfig.getMyNodeId())) {
            resetElectionState();
        }
    }

    /**
     * 检查 node 是否持有 slots 中的任意槽位
     */
    private boolean sharesAnySlot(ClusterNode node, BitSet slots) {
        if (slots == null) {
            return false;
        }
        BitSet nodeSlots = node.getSlots();
        if (nodeSlots == null) {
            return false;
        }
        for (int i = slots.nextSetBit(0); i >= 0; i = slots.nextSetBit(i + 1)) {
            if (nodeSlots.get(i)) {
                return true;
            }
        }
        return false;
    }

    private void notifyTopologyChanged() {
        if (onTopologyChanged != null) {
            try {
                onTopologyChanged.run();
            } catch (Exception e) {
                logger.error("onTopologyChanged 回调异常", e);
            }
        }
    }
}
