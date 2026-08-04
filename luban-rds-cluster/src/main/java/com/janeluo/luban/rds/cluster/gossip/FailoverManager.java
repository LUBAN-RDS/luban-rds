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

    /**
     * rank 退避步长（毫秒，P1-6）。
     * <p>
     * delay = gracePeriod + rank * RANK_DELAY_MS + jitter，
     * rank=0 为同 master 中 replOffset 最大的 slave，对齐 Redis {@code CLUSTER_MF_RETRY_MULT} 量级。
     * </p>
     */
    private static final long RANK_DELAY_MS = 500L;

    private final ClusterConfig clusterConfig;
    private final SlotManager slotManager;
    private final ClusterStateManager stateManager;
    private final ClusterBusClient busClient;
    private final Runnable onTopologyChanged;
    private final long nodeTimeout;
    private final long gracePeriod;

    /**
     * replica 有效性因子（cluster-slave-validity-factor，P1-6）。
     * <p>
     * ≤0 时禁用有效性校验（向后兼容，保留旧行为）；>0 时表示允许 slave 数据落后于
     * 同 master 最新 slave 的偏移量上限（粗略对齐 Redis data_age 阈值语义）。
     * </p>
     */
    private final long slaveValidityFactor;

    /**
     * 复制生命周期监听器（由 NettyRedisServer 注入，用于在 failover 提升/降级时启停复制连接）。
     * 默认 NoOp，保证未注入时不触发复制逻辑。
     */
    private volatile ReplicationLifecycleListener replicationLifecycleListener =
            new NoOpReplicationLifecycleListener();

    /**
     * 写暂停门控（P1-12，由 NettyRedisServer 注入，用于手动 failover 普通模式暂停 master 写）。
     * 默认 NoOp，保证未注入时手动 failover 降级为直接提升（向后兼容）。
     */
    private volatile com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate writePauseGate =
            new com.janeluo.luban.rds.cluster.lifecycle.NoOpWritePauseGate();

    // ==================== 候选侧状态（slave 发起选举用） ====================
    private FailoverState state = FailoverState.IDLE;
    private long electionStartTime;
    private long requestDeadline;
    private long electionEpoch;
    /**
     * 最近一次计算的 failover rank（P1-6，供测试观察）。rank=0 表示本 slave 是同 master
     * 中 replOffset 最大者（数据最新鲜）。未装配复制时所有 slave rank=0。
     */
    private int computedRank;
    private final Set<String> authVotes = new HashSet<>();
    private String failedMasterId;
    private boolean requestBroadcasted;

    // ==================== 投票侧状态（master 授权用，与本节点状态共存） ====================
    /**
     * 已投票记录：被投 slaveId -> 投票时的 currentEpoch
     * <p>
     * 注：lastVoteEpoch 已上移至 {@link ClusterConfig#getLastVoteEpoch()} 并持久化到 nodes.conf，
     * 本表仅作"本纪元已投候选"的去重/幂等用途，重启后不恢复（由 lastVoteEpoch 兜底拒绝同纪元重投）。
     * </p>
     */
    private final Map<String, Long> votesCast = new HashMap<>();
    /**
     * 本纪元首投候选的复制偏移量，用于拒绝同纪元后续候选时的日志比较。
     * 设计 §2.9 "首投即定"：本纪元首个有效候选即获票，后续候选即使偏移量更大也不改票
     * （ACK 是广播消息，其他节点可能已收到旧投票，撤票重投会造成双投不一致）。
     * 数据新鲜度由 rank 退避（tryStartElection）保证 offset 大的 slave 先发起、先获票。
     */
    private long votedReplOffset;

    // ==================== 手动 failover 状态机（P1-12，独立于自动选举） ====================
    /**
     * 手动 failover 状态（候选 slave 侧）。
     */
    private volatile ManualFailoverState manualState = ManualFailoverState.NONE;
    /**
     * 手动 failover 发起时刻（用于超时保护）。
     */
    private long mfStartTime;
    /**
     * master 暂停写时回传的偏移量，slave 须追平到此值后才提升。
     */
    private volatile long mfTargetOffset;
    /**
     * 待接管的原 master 节点。
     */
    private ClusterNode pendingManualMaster;
    /**
     * 手动 failover 超时上限（毫秒），超时后回退 NONE 并解除 master 写暂停。
     */
    private static final long MANUAL_FAILOVER_TIMEOUT_MS = 30000L;
    /**
     * slave offset 追平判定的轮询间隔内容忍的微小落后（字节），避免精确相等才放行的死锁。
     */
    private static final long OFFSET_CATCHUP_TOLERANCE = 0L;

    /**
     * 构造方法（向后兼容，等价于 slaveValidityFactor=0，即禁用有效性校验）。
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
        this(clusterConfig, slotManager, stateManager, busClient, onTopologyChanged,
                nodeTimeout, gracePeriod, 0L);
    }

    /**
     * 完整构造方法。
     *
     * @param clusterConfig       集群配置
     * @param slotManager         槽位管理器
     * @param stateManager        集群状态管理器
     * @param busClient           集群总线客户端
     * @param onTopologyChanged   拓扑变更回调（持久化 nodes.conf）
     * @param nodeTimeout         节点超时时间（毫秒）
     * @param gracePeriod         选举退避窗口（毫秒，cluster-failover-grace-period）
     * @param slaveValidityFactor replica 有效性因子（P1-6，≤0 禁用校验）
     */
    public FailoverManager(ClusterConfig clusterConfig, SlotManager slotManager,
                           ClusterStateManager stateManager, ClusterBusClient busClient,
                           Runnable onTopologyChanged, long nodeTimeout, long gracePeriod,
                           long slaveValidityFactor) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.stateManager = stateManager;
        this.busClient = busClient;
        this.onTopologyChanged = onTopologyChanged;
        this.nodeTimeout = nodeTimeout;
        this.gracePeriod = gracePeriod;
        this.slaveValidityFactor = slaveValidityFactor;
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

    /**
     * 设置写暂停门控（P1-12，由 NettyRedisServer 在装配时注入）。
     *
     * @param gate 写暂停门控，null 时回退为 NoOp 实现
     */
    public void setWritePauseGate(com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate gate) {
        this.writePauseGate = gate != null ? gate
                : new com.janeluo.luban.rds.cluster.lifecycle.NoOpWritePauseGate();
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
            // P1-12：推进手动 failover 状态机（独立于自动选举，普通模式异步进行）
            advanceManualFailover();
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

        // replica-validity 校验（P1-6，对齐 Redis clusterSlaveValidityFactor）：
        // slaveValidityFactor > 0 时，若本 slave 的 replOffset 明显落后于同 master 中
        // 数据最新鲜的 slave（落后量超过 nodeTimeout * factor 表示数据过旧），则不发起选举，
        // 让更新鲜的 slave 优先接管，避免陈旧 slave 胜选丢数据。
        // slaveValidityFactor <= 0 时跳过（向后兼容）。offset 全 0（未装配复制）时跳过。
        if (slaveValidityFactor > 0 && !skipValidityCheck(me, masterId)) {
            logger.debug("slave 数据过于陈旧，暂不发起选举: nodeId={}, failedMasterId={}",
                    me.getNodeId(), masterId);
            return;
        }

        // 满足触发条件
        state = FailoverState.REQUESTING;
        electionStartTime = System.currentTimeMillis();

        // rank 退避（P1-6，对齐 Redis 7：delay = gracePeriod + rank * RANK_DELAY_MS + jitter）。
        // rank = 同 master 中 replOffset 严格大于本节点的 slave 个数（offset 最大者 rank=0）。
        // offset 全 0（未装配复制，或 ClusterNode.replOffset 未被 gossip 填充）时所有 slave
        // 等价于 rank=0，退化为 gracePeriod + jitter（向后兼容旧行为）。
        int rank = computeFailoverRank(me, masterId);
        computedRank = rank;
        // 退避抖动：不同 slave 的 nodeId hashCode 不同以错峰广播，降低同纪元多候选同时
        // 发起导致票数分散的概率。修复 Math.abs(Integer.MIN_VALUE) 仍为负的 bug：先取模再取绝对值
        long jitter = Math.abs(me.getNodeId().hashCode() % JITTER_BOUND_MS);
        requestDeadline = electionStartTime + gracePeriod + rank * RANK_DELAY_MS + jitter;
        failedMasterId = masterId;
        authVotes.clear();
        requestBroadcasted = false;
        logger.warn("slave 进入选举: nodeId={}, failedMasterId={}, replOffset={}, rank={}, {}ms 后广播请求",
                me.getNodeId(), failedMasterId,
                replicationLifecycleListener.getReplicationOffset(), rank,
                (requestDeadline - electionStartTime));
    }

    /**
     * 计算本 slave 的 failover rank（P1-6）。
     * <p>
     * rank = 同 master 下 replOffset 严格大于本节点的 slave 数量。
     * rank=0 表示本节点是同 master 中数据最新鲜者，优先发起选举（配合 onAuthRequest 首投即定，
     * 数据更新鲜者先获票）。
     * </p>
     * <p>
     * 依赖 ClusterNode.replOffset 已被 gossip 填充。未装配复制（所有 offset=0）时 rank=0，
     * 所有 slave 同时发起，退化为原 jitter 退避行为。
     * </p>
     *
     * @param me       本节点（slave）
     * @param masterId master 节点ID
     * @return rank（≥0）
     */
    private int computeFailoverRank(ClusterNode me, String masterId) {
        long myOffset = me.getReplOffset();
        int rank = 0;
        for (ClusterNode sibling : clusterConfig.getSlavesOfMaster(masterId)) {
            if (sibling == null || sibling.getNodeId().equals(me.getNodeId())) {
                continue;
            }
            // replOffset 更大者排在本节点之前（rank 更小）
            if (sibling.getReplOffset() > myOffset) {
                rank++;
            }
        }
        return rank;
    }

    /**
     * 判断是否应跳过 replica-validity 校验（即本 slave 数据是否过于陈旧）。
     * <p>
     * 返回 true 表示可以跳过/通过（允许发起选举）；false 表示数据过旧应阻止。
     * 实际阻止逻辑由调用方据返回值处理。本方法返回 false 当且仅当：
     * 本 slave 的 replOffset 落后同 master 中最新 slave 超过 nodeTimeout * slaveValidityFactor。
     * </p>
     * <p>
     * 简化策略（避免引入无法精确计算的 master repl_offset 时间线）：
     * 以同 master 各 slave 的 replOffset 差值近似 data_age。offset 全 0 时返回 true（跳过）。
     * </p>
     *
     * @param me       本节点（slave）
     * @param masterId master 节点ID
     * @return true 表示数据足够新鲜可发起选举
     */
    private boolean skipValidityCheck(ClusterNode me, String masterId) {
        long myOffset = me.getReplOffset();
        long maxSiblingOffset = myOffset;
        for (ClusterNode sibling : clusterConfig.getSlavesOfMaster(masterId)) {
            if (sibling == null || sibling.getNodeId().equals(me.getNodeId())) {
                continue;
            }
            if (sibling.getReplOffset() > maxSiblingOffset) {
                maxSiblingOffset = sibling.getReplOffset();
            }
        }
        // 所有 offset 都为 0（未装配复制）→ 跳过校验，保留旧行为
        if (maxSiblingOffset == 0L && myOffset == 0L) {
            return true;
        }
        long allowedLag = nodeTimeout * slaveValidityFactor;
        return (maxSiblingOffset - myOffset) <= allowedLag;
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
        computedRank = 0;
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
     *       先发起 AUTH_REQUEST、先获票（P1-6 已实现真实 rank 退避，依赖 gossip 传播的
     *       ClusterNode.replOffset 计算排序；未装配复制时 rank 全 0，退化为 gracePeriod+jitter）。</li>
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

        // (2) 落后则追平 currentEpoch，新纪元清旧票。
        //     注：lastVoteEpoch 不在此推进——它只在真正投出票后更新（见 step 5），保证语义为"最后投出的票"。
        if (reqEpoch > myEpoch) {
            clusterConfig.setCurrentEpoch(reqEpoch);
            votesCast.clear();
            votedReplOffset = 0L;
        }

        // (3) 本纪元已投该 slave -> 幂等重发 ACK（处理网络重复投递，非重新请求）
        //     必须在 lastVoteEpoch 闸门前判定：重发同一票不改变选举结果，应继续放行。
        Long votedAt = votesCast.get(candidateId);
        if (votedAt != null && votedAt == reqEpoch) {
            sendAuthAck(candidateId, reqEpoch);
            return;
        }

        // (4) lastVoteEpoch 闸门（对齐 Redis 7 server.cluster->lastVoteEpoch）：
        //   若已在 reqEpoch 或更晚纪元投过票则拒绝。此值持久化到 nodes.conf，
        //   重启后 votesCast 清空但 lastVoteEpoch 保留，仍能拒绝同纪元二次投票，杜绝双 master。
        //   到达此处的请求必是"新票"（非 (3) 幂等路径），故闸门只拦新投。
        long myLastVoteEpoch = clusterConfig.getLastVoteEpoch();
        if (reqEpoch <= myLastVoteEpoch) {
            logger.debug("拒绝 AUTH_REQUEST：投票纪元不晚于已投纪元: reqEpoch={}, lastVoteEpoch={}",
                    reqEpoch, myLastVoteEpoch);
            return;
        }

        // (5) 本纪元已投他 slave -> 拒绝（首投即定，不撤票）
        //     即使新候选 replOffset 更大也不改票：ACK 已广播，撤票重投会造成同纪元双投。
        //     数据新鲜度择优由 tryStartElection 的 rank 退避保证 offset 大者先发起。
        //     注：(4) 闸门在同纪元二次请求时也会拒绝，此处为防御性双保险。
        if (!votesCast.isEmpty()) {
            logger.debug("本纪元已投他 slave，拒绝（首投即定，不撤票）: votedFor={}, votedReplOffset={}, candidate={}, candidateReplOffset={}",
                    votesCast.keySet(), votedReplOffset, candidateId, candidateReplOffset);
            return;
        }

        // (6) 首投：记录候选及其偏移量，授权
        votesCast.put(candidateId, reqEpoch);
        votedReplOffset = candidateReplOffset;
        // 记录投票纪元并持久化（P0-4：重启后仍拒绝同纪元重投）
        clusterConfig.recordVoteEpoch(reqEpoch);
        sendAuthAck(candidateId, reqEpoch);
    }

    private void sendAuthAck(String candidateId, long epoch) {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null) {
            return;
        }
        // P0-4：ACK 必须携带被投候选ID。ACK 为广播消息，不带 candidateId 会使同纪元
        // 其他候选误计此票导致双 master。
        FailoverAuthAckMessage ack = new FailoverAuthAckMessage(
                me.getNodeId(),
                me.getConfigEpoch(),
                epoch,
                epoch,
                candidateId);
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
        // P0-4：ACK 必须是投给"本候选"的，否则忽略。
        // ACK 为广播消息，同纪元多候选并存时，投给候选 A 的 ACK 会被候选 B 收到，
        // 不校验 candidateId 会让 B 误计 A 的票数、可能各自过半 → 双 master。
        ClusterNode me = clusterConfig.getMyNode();
        String myId = me != null ? me.getNodeId() : null;
        String ackCandidate = ack.getCandidateId();
        if (myId == null || !myId.equals(ackCandidate)) {
            logger.debug("忽略非本候选 ACK: voter={}, ackCandidate={}, me={}", voterId, ackCandidate, myId);
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

    // ==================== 手动 failover 状态机（P1-12，普通模式异步流程） ====================

    /**
     * 启动手动故障转移（CLUSTER FAILOVER 普通模式，P1-12）。
     * <p>
     * 异步流程：发 MFStart → master 暂停写并回传 offset → slave 追平 → 提升。
     * FORCE/TAKEOVER 仍走同步 {@link #performManualFailover}，本方法仅普通模式调用。
     * 调用方（ClusterCommandHandler）应在调用前完成角色校验（必须是 slave）与
     * master 健康校验（非 FAIL/PFAIL）。
     * </p>
     *
     * @param slaveNode  当前 slave 节点
     * @param masterNode 原 master 节点（接管目标）
     */
    public synchronized void startManualFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        if (manualState != ManualFailoverState.NONE) {
            logger.warn("手动 failover 已在进行中，忽略重复请求: state={}", manualState);
            return;
        }
        if (busClient == null) {
            // 无总线（单测/未装配），降级为同步提升，保持向后兼容
            logger.warn("busClient 未注入，手动 failover 降级为同步提升");
            performManualFailover(slaveNode, masterNode);
            return;
        }
        pendingManualMaster = masterNode;
        mfStartTime = System.currentTimeMillis();
        mfTargetOffset = 0L;
        manualState = ManualFailoverState.MF_REQUESTED;
        // 向 master 发送 MFStart，请求其暂停写并回传 offset
        ManualFailoverStartMessage msg = new ManualFailoverStartMessage(slaveNode.getNodeId());
        busClient.send(masterNode.getNodeId(), msg);
        logger.info("手动 failover 已启动：slave={}, master={}，等待 master 回传暂停 offset",
                slaveNode.getNodeId(), masterNode.getNodeId());
    }

    /**
     * master 侧：收到候选 slave 的 MFStart（P1-12）。
     * <p>
     * 本节点作为被接管的目标 master：暂停客户端写、记录当前复制偏移量、回传给发起 slave。
     * </p>
     *
     * @param msg MFStart 消息
     */
    public synchronized void onManualFailoverStart(ManualFailoverStartMessage msg) {
        ClusterNode me = clusterConfig.getMyNode();
        if (me == null || !me.isMaster()) {
            logger.warn("收到 MFStart 但本节点非 master，忽略: sender={}", msg.getSenderNodeId());
            return;
        }
        // 仅处理来自本节点 slave 的请求
        ClusterNode sender = clusterConfig.getNode(msg.getSenderNodeId());
        if (sender == null || !sender.isSlave()
                || !me.getNodeId().equals(sender.getMasterNodeId())) {
            logger.warn("收到非本节点 slave 的 MFStart，忽略: sender={}", msg.getSenderNodeId());
            return;
        }
        // 暂停写并记录当前偏移量
        writePauseGate.pause();
        long currentOffset = replicationLifecycleListener.getReplicationOffset();
        logger.info("收到 MFStart，已暂停写并记录 offset={}，回传给 slave={}",
                currentOffset, msg.getSenderNodeId());
        // 回传 offset 给发起 slave
        ManualFailoverOffsetMessage reply = new ManualFailoverOffsetMessage(me.getNodeId(), currentOffset);
        busClient.send(msg.getSenderNodeId(), reply);
    }

    /**
     * slave 侧：收到 master 回传的暂停 offset（P1-12）。
     * <p>
     * 记录目标 offset，转入 WAITING_OFFSET，等待本 slave 复制偏移量追平后提升。
     * </p>
     *
     * @param msg master 暂停写时的 offset 回传消息
     */
    public synchronized void onManualFailoverOffset(ManualFailoverOffsetMessage msg) {
        if (manualState != ManualFailoverState.MF_REQUESTED) {
            logger.warn("收到 MFOffset 但未处于 MF_REQUESTED 态，忽略: state={}, offset={}",
                    manualState, msg.getMasterOffset());
            return;
        }
        mfTargetOffset = msg.getMasterOffset();
        manualState = ManualFailoverState.MF_WAITING_OFFSET;
        logger.info("收到 master 暂停 offset={}，转入 WAITING_OFFSET 等待追平", mfTargetOffset);
    }

    /**
     * 推进手动 failover 状态机（由 tick 每轮调用，P1-12）。
     * <p>
     * WAITING_OFFSET 态：检查本 slave 复制偏移量是否追平目标 offset，追平则提升；
     * 超时保护：超过 {@link #MANUAL_FAILOVER_TIMEOUT_MS} 未完成则回退 NONE 并解除 master 写暂停。
     * </p>
     */
    private synchronized void advanceManualFailover() {
        if (manualState == ManualFailoverState.NONE) {
            return;
        }
        // 超时保护
        if ((System.currentTimeMillis() - mfStartTime) > MANUAL_FAILOVER_TIMEOUT_MS) {
            logger.warn("手动 failover 超时（{}ms），回退 NONE 并解除 master 写暂停",
                    MANUAL_FAILOVER_TIMEOUT_MS);
            abortManualFailover();
            return;
        }
        if (manualState == ManualFailoverState.MF_WAITING_OFFSET) {
            long myOffset = replicationLifecycleListener.getReplicationOffset();
            // offset 全 0（未装配复制）或 master offset 为 0 → 视为已追平，避免永久阻塞
            boolean caughtUp = mfTargetOffset <= 0L
                    || myOffset >= (mfTargetOffset - OFFSET_CATCHUP_TOLERANCE);
            if (caughtUp) {
                logger.info("slave offset={} 已追平 master 暂停 offset={}，执行手动提升",
                        myOffset, mfTargetOffset);
                ClusterNode me = clusterConfig.getMyNode();
                if (me != null && pendingManualMaster != null) {
                    manualState = ManualFailoverState.MF_READY;
                    performManualFailover(me, pendingManualMaster);
                }
                abortManualFailover();
            }
        }
    }

    /**
     * 中止手动 failover：回退状态、解除 master 写暂停、清空待接管引用。
     */
    private synchronized void abortManualFailover() {
        manualState = ManualFailoverState.NONE;
        pendingManualMaster = null;
        mfTargetOffset = 0L;
        // 解除 master 写暂停（本节点可能是被请求接管的 master，暂停可能仍在生效）
        writePauseGate.resume();
    }

    /**
     * 获取手动 failover 状态（供测试观察，P1-12）。
     *
     * @return 当前手动 failover 状态
     */
    public synchronized ManualFailoverState getManualFailoverState() {
        return manualState;
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

    /**
     * 测试辅助：获取最近一次计算的 failover rank（P1-6）。
     * 仅供同包测试观察 rank 退避逻辑。
     *
     * @return 最近一次计算的 rank，未进入选举前为 0
     */
    synchronized int getComputedRankForTest() {
        return computedRank;
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
