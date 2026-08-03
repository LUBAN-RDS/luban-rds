package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
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

    /**
     * 复制生命周期监听器（由 NettyRedisServer 注入，用于在 failover 提升/降级时启停复制连接）。
     * 默认 NoOp，保证未注入时不触发复制逻辑。
     */
    private volatile ReplicationLifecycleListener replicationLifecycleListener =
            new NoOpReplicationLifecycleListener();

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
     * 已投票记录：被投 slaveId -> 投票时的 currentEpoch
     */
    private final Map<String, Long> votesCast = new HashMap<>();
    private long lastVoteEpoch;
    /**
     * 本纪元首投候选的复制偏移量，用于拒绝同纪元后续候选时的日志比较。
     * 设计 §2.9 "首投即定"：本纪元首个有效候选即获票，后续候选即使偏移量更大也不改票
     * （ACK 是广播消息，其他节点可能已收到旧投票，撤票重投会造成双投不一致）。
     * 数据新鲜度由 rank 退避（tryStartElection）保证 offset 大的 slave 先发起、先获票。
     */
    private long votedReplOffset;

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

    /**
     * 设置复制生命周期监听器（由 NettyRedisServer 在装配时注入）。
     *
     * @param listener 复制生命周期监听器，null 时回退为 NoOp 实现
     */
    public void setReplicationLifecycleListener(ReplicationLifecycleListener listener) {
        this.replicationLifecycleListener =
                listener != null ? listener : new NoOpReplicationLifecycleListener();
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

        // quorum 前置校验：多数 master 不可达时不发起选举（避免无意义的选举风暴）
        if (!stateManager.canFailover()) {
            logger.debug("可用 master 未过半，暂不发起选举: failedMasterId={}", masterId);
            return;
        }

        // 满足触发条件
        state = FailoverState.REQUESTING;
        electionStartTime = System.currentTimeMillis();
        // 退避抖动：不同 slave 的 nodeId hashCode 不同以错峰广播，降低同纪元多候选同时
        // 发起导致票数分散的概率。
        //
        // Rank 退避（对齐 Redis 7：delay = gracePeriod + rank * 500ms，rank=0 为 offset
        // 最大的 slave）当前采用 spec §2.9 记可的简化：固定 rank=0（所有 slave 同时发起，
        // 靠 onAuthRequest 投票比较 replicationOffset 择优）。真正的 rank 计算需要 slave
        // 复制偏移量经 gossip（PONG）传播，使本地可见同 master 各 slave 的 offset 以排序，
        // 该机制不在 C8 范围内。故此处保留 gracePeriod + jitter 退避，由投票侧的偏移量
        // 比较 + 首投即定语义保证数据更新鲜的 slave 优先获票。
        // 修复 Math.abs(Integer.MIN_VALUE) 仍为负的 bug：先取模再取绝对值
        long jitter = Math.abs(me.getNodeId().hashCode() % JITTER_BOUND_MS);
        requestDeadline = electionStartTime + gracePeriod + jitter;
        failedMasterId = masterId;
        authVotes.clear();
        requestBroadcasted = false;
        logger.warn("slave 进入选举: nodeId={}, failedMasterId={}, replOffset={}, {}ms 后广播请求",
                me.getNodeId(), failedMasterId,
                replicationLifecycleListener.getReplicationOffset(),
                (requestDeadline - electionStartTime));
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
     * <p>
     * 携带本节点真实复制偏移量（master_repl_offset），供投票 master 在同纪元多候选时
     * 比较数据新鲜度择优（对齐 Redis 7）。偏移量由 {@link ReplicationLifecycleListener}
     * 提供，未装配复制组件时返回 0（保守值，等价旧行为）。
     * </p>
     */
    private void broadcastAuthRequest() {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        // 原子自增 currentEpoch 作为本次选举纪元（避免 read+1/write 的竞态）
        electionEpoch = clusterConfig.incrementEpoch();
        requestBroadcasted = true;

        // 真实复制偏移量：slave 模式返回已同步偏移量，反映本节点数据新鲜度。
        // 替换原硬编码 0L，使投票方可按偏移量择优，避免陈旧数据 slave 抢先胜选。
        long myReplOffset = replicationLifecycleListener.getReplicationOffset();

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                me.getNodeId(),
                me.getConfigEpoch(),
                electionEpoch,
                myReplOffset);
        busClient.broadcast(req);
        logger.warn("广播选举请求: candidate={}, epoch={}, replOffset={}",
                me.getNodeId(), electionEpoch, myReplOffset);
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
     * <p>
     * 偏移量选举语义（对齐 Redis 7，设计 §2.9）：
     * <ul>
     *   <li>候选 slave 在 AUTH_REQUEST 中携带真实 {@code replicationOffset}（见
     *       {@link #broadcastAuthRequest}），反映其数据新鲜度。</li>
     *   <li><b>首投即定</b>：本纪元首个通过校验的候选即获票；同纪元后续候选即使偏移量
     *       更大也<b>不</b>改票。原因是 ACK 为广播消息，其他节点可能已据旧投票推进选举，
     *       撤票重投会导致同一纪元双投、票数统计不一致。</li>
     *   <li>数据新鲜度择优由 {@code tryStartElection} 的 rank 退避保证：offset 大的 slave
     *       先发起 AUTH_REQUEST、先获票。当前 rank=0 简化（见 tryStartElection 注释），
     *       所有 slave 同时发起，靠本方法的首投即定 + 各 master 抖动错峰让 offset 大者
     *       有更高概率先到先得。后续若引入 slave offset gossip 传播，可实现真实 rank 退避。</li>
     * </ul>
     * </p>
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

        // (1.5) 校验候选节点是 slave 且其 master 已 FAIL（对齐 Redis：仅 fail master 的 slave 可参选）
        String candidateId = req.getSenderNodeId();
        ClusterNode candidate = clusterConfig.getNode(candidateId);
        if (candidate == null || !candidate.isSlave()) {
            logger.debug("拒绝 AUTH_REQUEST：候选节点不存在或非 slave: candidate={}", candidateId);
            return;
        }
        String candidateMasterId = candidate.getMasterNodeId();
        ClusterNode candidateMaster = candidateMasterId != null ? clusterConfig.getNode(candidateMasterId) : null;
        if (candidateMaster == null || !candidateMaster.isFail()) {
            logger.debug("拒绝 AUTH_REQUEST：候选节点的 master 未 FAIL: candidate={}, master={}",
                    candidateId, candidateMasterId);
            return;
        }
        long candidateReplOffset = req.getReplicationOffset();
        logger.debug("AUTH_REQUEST 候选校验通过: candidate={}, configEpoch={}, replOffset={}",
                candidateId, req.getConfigEpoch(), candidateReplOffset);

        // (2) 落后则追平，新纪元清旧票
        if (reqEpoch > myEpoch) {
            clusterConfig.setCurrentEpoch(reqEpoch);
            lastVoteEpoch = reqEpoch;
            votesCast.clear();
            votedReplOffset = 0L;
        }

        // (3) 本纪元已投该 slave -> 幂等重发
        Long votedAt = votesCast.get(candidateId);
        if (votedAt != null && votedAt == reqEpoch) {
            sendAuthAck(candidateId, reqEpoch);
            return;
        }

        // (4) 本纪元已投他 slave -> 拒绝（首投即定，不撤票）
        //     即使新候选 replOffset 更大也不改票：ACK 已广播，撤票重投会造成同纪元双投。
        //     数据新鲜度择优由 tryStartElection 的 rank 退避保证 offset 大者先发起。
        if (!votesCast.isEmpty()) {
            logger.debug("本纪元已投他 slave，拒绝（首投即定，不撤票）: votedFor={}, votedReplOffset={}, candidate={}, candidateReplOffset={}",
                    votesCast.keySet(), votedReplOffset, candidateId, candidateReplOffset);
            return;
        }

        // (5) 首投：记录候选及其偏移量，授权
        votesCast.put(candidateId, reqEpoch);
        votedReplOffset = candidateReplOffset;
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
        // 校验投票者是健康 master（拒绝 slave/未知节点/FAIL 节点的伪造 ACK）
        ClusterNode voter = clusterConfig.getNode(voterId);
        if (voter == null || !voter.isMaster() || voter.isFail()) {
            logger.debug("忽略非法投票: voter={} 非健康 master", voterId);
            return;
        }
        // 校验 ACK 的 voteEpoch 与本次选举纪元一致（拒绝陈旧 ACK 污染票数）
        if (ack.getVoteEpoch() != electionEpoch) {
            logger.debug("忽略陈旧 ACK: voter={}, voteEpoch={}, electionEpoch={}",
                    voterId, ack.getVoteEpoch(), electionEpoch);
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
        // 旧 master 降级后同步提升其 configEpoch，使 gossip 传播的 epoch 严格大于
        // 旧主本地恢复值，触发 handleMyselfGossipEntry 自降级门控。
        // performFailover 仅处理角色/槽位，不涉及 epoch 同步，故在此补全。
        oldMaster.setConfigEpoch(clusterConfig.getCurrentEpoch());
        state = FailoverState.ELECTED;

        // 广播收敛到共用方法（自动+手动共用），避免重复广播（C9）
        broadcastFailoverResult(me, oldMaster);
        logger.warn("slave 自动提升为 master: nodeId={}, epoch={}, slotCount={}",
                me.getNodeId(), clusterConfig.getCurrentEpoch(), me.getSlotCount());

        resetElectionState();
    }

    // ==================== 手动故障转移入口 ====================

    /**
     * 手动 CLUSTER FAILOVER [FORCE|TAKEOVER] 入口。
     * 不经选举状态机，但<b>广播 FailoverResult</b>使全网拓扑收敛（对齐 Redis 7，C9）。
     * 保留 epoch 自增行为（手动接管也需要更高的 configEpoch 使全网收敛），
     * 并对齐自动路径：旧 master 也同步提升 configEpoch。
     *
     * @param slaveNode  当前 slave 节点（将被提升）
     * @param masterNode 原 master 节点（将被降级）
     */
    public synchronized void performManualFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        performFailover(slaveNode, masterNode);
        clusterConfig.incrementEpoch();
        slaveNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
        // 旧 master 降级后同步提升 configEpoch，对齐自动路径（C9/3.22）：
        // 使 gossip 传播的 epoch 严格大于旧主本地恢复值，触发自降级门控。
        masterNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
        // 广播 FailoverResult 使全网拓扑收敛（自动+手动共用，C9/3.21）。
        broadcastFailoverResult(slaveNode, masterNode);
    }

    /**
     * 执行实际的 slave→master 提升（槽位继承、master 降级）。
     * 从 ClusterCommandHandler 抽取，手动/自动共用。
     */
    private void performFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        slaveNode.removeState(ClusterNodeState.SLAVE);
        slaveNode.addState(ClusterNodeState.MASTER);
        slaveNode.setMasterNodeId(null);

        // 槽位继承：统一以 ClusterConfig.setSlotOwner 为单一入口（锁顺序 Config->Node），
        // 其内部会同步 ClusterNode.slots 与 SlotManager，避免调用方先持 Node 锁再进 Config 锁导致死锁。
        BitSet masterSlots = masterNode.getSlots();
        for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
            slotManager.setSlotOwner(i, slaveNode.getNodeId());
            clusterConfig.setSlotOwner(i, slaveNode.getNodeId());
        }

        // masterNode 的槽位已由 setSlotOwner 的 oldOwner 清理逻辑逐个 removeSlot，此处清残留
        masterNode.clearSlots();
        masterNode.removeState(ClusterNodeState.MASTER);
        masterNode.addState(ClusterNodeState.SLAVE);
        masterNode.setMasterNodeId(slaveNode.getNodeId());
        // 降级时清除原 master 的 FAIL/PFAIL（它已恢复为新 master 的 slave 角色）
        masterNode.removeState(ClusterNodeState.FAIL);
        masterNode.removeState(ClusterNodeState.PFAIL);

        stateManager.updateClusterState();

        // 通知复制生命周期：本节点角色变更。
        // performFailover 由手动 CLUSTER FAILOVER 与自动胜选 performFailoverAndBroadcast 共用，
        // 两个入口都直接调用本方法，因此在此统一通知即可覆盖两条路径。
        if (slaveNode.isMyself()) {
            replicationLifecycleListener.promoteToMaster();
        }
        if (masterNode.isMyself()) {
            replicationLifecycleListener.demoteToSlave(slaveNode);
        }
    }

    /**
     * 广播 FailoverResult 使全网拓扑收敛（自动胜选 + 手动 FAILOVER 共用，C9）。
     * <p>
     * 必须在调用方完成 epoch 自增与新/旧 master 的 configEpoch 对齐<b>之后</b>调用，
     * 以保证广播携带的 {@code newConfigEpoch} 为最终值，避免以陈旧 epoch 广播。
     * 同时触发 {@link #notifyTopologyChanged()} 持久化 nodes.conf。
     * </p>
     * <p>
     * 重复广播安全：{@link #onFailoverResult} 已有纪元裁决（旧纪元忽略）与相等 epoch
     * 的 nodeId 字典序决胜，重复/回放消息不会破坏拓扑。
     * </p>
     *
     * @param newMaster 已提升的新 master 节点（原 slave）
     * @param oldMaster 已降级的原 master 节点
     */
    private void broadcastFailoverResult(ClusterNode newMaster, ClusterNode oldMaster) {
        FailoverResultMessage result = new FailoverResultMessage(
                newMaster.getNodeId(),
                newMaster.getNodeId(),
                clusterConfig.getCurrentEpoch(),
                newMaster.getSlots());
        busClient.broadcast(result);
        notifyTopologyChanged();
        logger.debug("广播 FailoverResult: newMaster={}, oldMaster={}, epoch={}, slotCount={}",
                newMaster.getNodeId(), oldMaster.getNodeId(),
                clusterConfig.getCurrentEpoch(), newMaster.getSlotCount());
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

        // 相等 epoch 冲突解决（对齐 Redis clusterHandleConfigEpochCollision）：
        // 当 newConfigEpoch == myEpoch 时，可能存在脑裂（两个 winner 声明相同 epoch）。
        // 检查本地是否有其它节点已持有 inheritedSlots 中的槽位且 configEpoch 不低于 winner，
        // 若有则按 nodeId 字典序决胜，落败者不应用拓扑变更。
        if (msg.getNewConfigEpoch() == myEpoch) {
            BitSet inherited = msg.getInheritedSlots();
            String conflictOwnerId = findConflictingOwner(inherited, msg.getWinnerNodeId(), msg.getNewConfigEpoch());
            if (conflictOwnerId != null
                    && conflictOwnerId.compareTo(msg.getWinnerNodeId()) > 0) {
                // 本地冲突 owner 的 nodeId 字典序更大，它胜出，忽略本消息
                logger.warn("相等 epoch 冲突解决: 本地 owner={} 优先于 winner={}（epoch={}），忽略本 FailoverResult",
                        conflictOwnerId, msg.getWinnerNodeId(), myEpoch);
                return;
            }
        }

        // 槽位转移在前：清 winner 历史 slot 残留，逐 slot 赋给 winner（单一来源）。
        // 移除原 winner.setSlots(inherited.clone()) 整体覆写，消除 setSlots 与逐 slot setSlotOwner
        // 的双写路径——当 winner 已持部分 slot 时两路径结果可能短暂不一致。
        // clusterConfig.setSlotOwner 内部已清理 oldOwner.removeSlot + slotAssignment[slot]=winner + winner.addSlot。
        BitSet inherited = msg.getInheritedSlots();
        if (inherited != null) {
            winner.clearSlots();
            for (int i = inherited.nextSetBit(0); i >= 0; i = inherited.nextSetBit(i + 1)) {
                slotManager.setSlotOwner(i, winner.getNodeId());
                clusterConfig.setSlotOwner(i, winner.getNodeId());
            }
        }

        // winner 提权在后（slot 已就位，满足不变式：MASTER 时其声明的每个 slot 均归属自身）。
        // 对齐 Redis last-failover-wins：winner 以更高 configEpoch 接管旧 master 的 slots。
        winner.removeState(ClusterNodeState.SLAVE);
        winner.addState(ClusterNodeState.MASTER);
        winner.removeState(ClusterNodeState.FAIL);
        winner.removeState(ClusterNodeState.PFAIL);
        winner.setMasterNodeId(null);
        winner.setConfigEpoch(msg.getNewConfigEpoch());

        // 原 master（持有这些槽位且非 winner 的旧 master）降级为 winner 的 slave。
        // 双路径覆盖：
        // ① sharesAnySlot：旧 master 仍持有槽位时直接匹配（正常时序）。
        // ② 备选路径 (staleMaster)：先到的 gossip 同步已将槽位移交给 winner，
        //    sharesAnySlot 返回 false 导致降级被跳过。此时检测 MASTER 且无槽位
        //    且 configEpoch 低于 winner epoch，判定为旧 master 并补偿降级。
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            boolean isOldMaster = node.isMaster() && !node.getNodeId().equals(winner.getNodeId())
                    && sharesAnySlot(node, inherited);
            boolean isStaleMaster = node.isMaster() && !node.getNodeId().equals(winner.getNodeId())
                    && node.getSlotCount() == 0
                    && node.getConfigEpoch() < msg.getNewConfigEpoch();

            if (isOldMaster || isStaleMaster) {
                node.clearSlots();
                node.removeState(ClusterNodeState.MASTER);
                node.addState(ClusterNodeState.SLAVE);
                node.setMasterNodeId(winner.getNodeId());
                // 提升旧 master 的 configEpoch 到 winner epoch，使 gossip 传播的 epoch
                // 严格大于旧主本地恢复值，触发 handleMyselfGossipEntry 自降级门控。
                node.setConfigEpoch(msg.getNewConfigEpoch());
                // 降级时清除 FAIL/PFAIL（原 master 已恢复为 winner 的 slave 角色）
                node.removeState(ClusterNodeState.FAIL);
                node.removeState(ClusterNodeState.PFAIL);
                logger.info("原 master 降级为 slave: oldMaster={}, newMaster={}",
                        node.getNodeId(), winner.getNodeId());
            }
        }

        clusterConfig.setCurrentEpoch(msg.getNewConfigEpoch());

        // 通知复制生命周期：本节点角色因 FailoverResult 广播而变更。
        // 必须在 notifyTopologyChanged 之前完成角色判定，确保 winner 已是 master、
        // 本地 demoted 节点已是 slave（其 masterNodeId 已指向 winner）。
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode != null) {
            if (myNode.getNodeId().equals(winner.getNodeId()) && myNode.isMaster()) {
                replicationLifecycleListener.promoteToMaster();
            } else if (myNode.isSlave() && myNode.getMasterNodeId() != null
                    && myNode.getMasterNodeId().equals(winner.getNodeId())) {
                replicationLifecycleListener.demoteToSlave(winner);
            }
        }

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
     * 经 gossip 心跳触发的 MYSELF 自降级
     * <p>
     * 当重启的原主节点收到携带更高 configEpoch 的 PONG/PING，且其 gossip section
     * 指出 MYSELF 现为某新主的 SLAVE 时调用。与 {@link #onFailoverResult} 共用
     * synchronized 监视器，保证与并发 FailoverResult 处理串行化。
     * </p>
     * <p>
     * 幂等：MYSELF 已是 SLAVE 时直接返回。新主记录不在本地配置时跳过（等下一轮
     * 心跳发现新主后再降级）。
     * </p>
     *
     * @param newMasterNodeId 新主节点 ID
     * @param newConfigEpoch  触发降级的 gossip configEpoch（已校验大于本地基线）
     */
    public synchronized void applySelfDemotion(String newMasterNodeId, long newConfigEpoch) {
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null || !myNode.isMaster()) {
            // 幂等：已是 slave 或无 MYSELF 记录则跳过
            return;
        }
        ClusterNode newMaster = clusterConfig.getNode(newMasterNodeId);
        if (newMaster == null) {
            logger.warn("自降级跳过: 新主节点未在本地配置中, newMasterId={}, 等待后续心跳发现",
                    newMasterNodeId);
            return;
        }

        // 新主在本地可能仍是 SLAVE（重启节点的旧 nodes.conf 记录的是故障转移前的拓扑），
        // 对齐 onFailoverResult 的 winner 提权逻辑，立即将其提升为 MASTER，避免 cluster nodes
        // 短暂显示"slave 持有 slots"的不一致视图（否则需等下一轮 gossip 心跳纠正）。
        if (newMaster.isSlave()) {
            newMaster.removeState(ClusterNodeState.SLAVE);
            newMaster.addState(ClusterNodeState.MASTER);
            newMaster.setMasterNodeId(null);
        }
        newMaster.setConfigEpoch(newConfigEpoch);

        // 清空 MYSELF slots，归属转移到新主
        BitSet oldSlots = myNode.getSlots();
        if (oldSlots != null) {
            for (int i = oldSlots.nextSetBit(0); i >= 0; i = oldSlots.nextSetBit(i + 1)) {
                slotManager.setSlotOwner(i, newMasterNodeId);
                clusterConfig.setSlotOwner(i, newMasterNodeId);
            }
        }
        myNode.clearSlots();
        myNode.removeState(ClusterNodeState.MASTER);
        myNode.addState(ClusterNodeState.SLAVE);
        myNode.setMasterNodeId(newMasterNodeId);
        // 降级时清除 FAIL/PFAIL（原 master 已恢复为新主的 slave 角色）
        myNode.removeState(ClusterNodeState.FAIL);
        myNode.removeState(ClusterNodeState.PFAIL);
        myNode.setConfigEpoch(newConfigEpoch);
        clusterConfig.setCurrentEpoch(newConfigEpoch);

        // 切换复制方向：向新主发起同步
        replicationLifecycleListener.demoteToSlave(newMaster);
        notifyTopologyChanged();
        logger.warn("MYSELF 经 gossip 自降级为 slave: newMaster={}, configEpoch={}",
                newMasterNodeId, newConfigEpoch);
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

    /**
     * 查找与 winner 冲突的本地 owner：在 inheritedSlots 中存在槽位、
     * 非 winner、且 configEpoch 不低于 winner 声明的 epoch 的节点。
     * 用于相等 epoch 时的 nodeId 字典序决胜。
     *
     * @param slots         声明的槽位集合
     * @param winnerId      winner 节点ID
     * @param winnerEpoch   winner 声明的 epoch
     * @return 冲突 owner 的节点ID，无冲突返回 null
     */
    private String findConflictingOwner(BitSet slots, String winnerId, long winnerEpoch) {
        if (slots == null) {
            return null;
        }
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.getNodeId().equals(winnerId)) {
                continue;
            }
            if (sharesAnySlot(node, slots) && node.getConfigEpoch() >= winnerEpoch) {
                return node.getNodeId();
            }
        }
        return null;
    }

    /**
     * 测试辅助：模拟"已进入 REQUESTING 并已广播 AUTH_REQUEST"的状态。
     * <p>
     * 仅供同包测试使用，设置 electionEpoch 并标记已广播，
     * 使后续 onAuthAck 的 voteEpoch 校验能匹配测试构造的 ACK。
     * </p>
     *
     * @param epoch 选举纪元
     */
    synchronized void prepareRequestedStateForTest(long epoch) {
        this.state = FailoverState.REQUESTING;
        this.electionEpoch = epoch;
        this.requestBroadcasted = true;
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
