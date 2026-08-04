package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.AppendDecision;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.Transition;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.VoteDecision;
import com.janeluo.luban.rds.mesh.election.ElectionTimer;
import com.janeluo.luban.rds.mesh.election.LeaseManager;
import com.janeluo.luban.rds.mesh.election.VoteCollector;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mesh 节点主体（DESIGN.md §7.1）。
 * <p>
 * 阶段 3 实现：选举（ElectionTimer + VoteCollector + PreVote）、心跳广播、AppendEntries Follower 接收、
 * Leader Lease 续租。propose/LogReplicator/LogApplier 在阶段 4-5 补全。
 * </p>
 *
 * <h3>串行化模型（Raft 正确性前提）</h3>
 * <p>
 * 所有对 {@link MeshState} 的读写与角色转换通过单线程 dispatcher（{@link #raftExecutor}）串行执行。
 * 入站 RPC（来自 {@code MeshBusHandler}）与内部回调（ElectionTimer / VoteCollector）都提交到该线程。
 * 这避免「becomeCandidate 与 handleAppendEntries 并发改 currentTerm」类竞态，简化并发设计。
 * </p>
 *
 * <h3>PreVote 流程（DESIGN §5.2 + delta spec）</h3>
 * <ol>
 *   <li>{@code onElectionTimeout} → {@code runPreVote}：不自增 term、不发 votedFor，
 *       发 preVote=true 的 RequestVote 探测</li>
 *   <li>PreVote 获多数派 → {@code runRealElection}：RaftStateMachine.becomeCandidate（自增 term、投自己）
 *       → 正式 RequestVote</li>
 *   <li>PreVote 未获多数派 → 保持 FOLLOWER（不动 term），等下次 electionTimeout</li>
 * </ol>
 * </p>
 */
public class MeshNode {

    private static final Logger logger = LoggerFactory.getLogger(MeshNode.class);

    private final String nodeId;
    private final MeshConfig config;
    private final MeshState state;
    private final MeshBusClient busClient;
    private final RaftStateMachine stateMachine;

    /** 串行化 Raft 状态访问的单线程调度器。 */
    private final ScheduledExecutorService raftExecutor;
    /** ElectionTimer 与心跳定时器复用的调度器（可与 raftExecutor 同一个）。 */
    private final ScheduledExecutorService scheduler;

    private final ElectionTimer electionTimer;
    private final LeaseManager lease;

    /** 当前正在进行的投票收集器（preVote 或正式选举其一）；volatile，仅 raftExecutor 线程修改。 */
    private volatile VoteCollector currentVoteCollector;
    /** Leader 专属：peer → nextIndex（阶段 4 完善回退；阶段 3 心跳用）。 */
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    /** Leader 专属：peer → matchIndex。 */
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();
    /** 心跳/日志复制定时任务。 */
    private volatile ScheduledFuture<?> heartbeatTask;

    /** 落盘 hook 占位（阶段 11 实现真实 fsync）；阶段 3 为 no-op。 */
    private volatile Runnable persistHook = () -> { };

    private volatile boolean started;
    private volatile boolean stopped;

    public MeshNode(MeshConfig config, MeshState state, MeshBusClient busClient) {
        this(config, state, busClient, new RaftStateMachine());
    }

    /**
     * 测试与定制构造器：可注入自定义 {@link RaftStateMachine}（如 mock 裁决逻辑）。
     */
    public MeshNode(MeshConfig config, MeshState state, MeshBusClient busClient, RaftStateMachine stateMachine) {
        this.config = config;
        this.nodeId = config.getSelfNodeId();
        this.state = state;
        this.busClient = busClient;
        this.stateMachine = stateMachine;
        // 单线程：保证 Raft 状态变更串行化
        this.raftExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-raft-" + abbrev(nodeId));
            t.setDaemon(true);
            return t;
        });
        this.scheduler = this.raftExecutor;
        this.lease = new LeaseManager(config.getLeaseDurationMs());
        this.electionTimer = new ElectionTimer(
                config.getElectionTimeoutMinMs(),
                config.getElectionTimeoutMaxMs(),
                this::onElectionTimeout,
                this.scheduler);
    }

    // ==================== 生命周期 ====================

    /** 启动：角色=FOLLOWER，启动 ElectionTimer。 */
    public synchronized void start() {
        if (started) {
            return;
        }
        if (stopped) {
            throw new IllegalStateException("MeshNode 已 stop，不可再 start");
        }
        started = true;
        logger.info("MeshNode 启动: nodeId={}, term={}, role={}", abbrev(nodeId), state.currentTerm, state.role);
        electionTimer.start();
    }

    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        electionTimer.stop();
        stopHeartbeat();
        lease.invalidate();
        raftExecutor.shutdownNow();
        logger.info("MeshNode 已停止: nodeId={}", abbrev(nodeId));
    }

    // ==================== 角色查询 ====================

    public boolean isLeader() {
        return state.role == MeshRole.LEADER;
    }

    public String getLeaderId() {
        return state.leaderId;
    }

    public MeshRole getRole() {
        return state.role;
    }

    public long getCurrentTerm() {
        return state.currentTerm;
    }

    public long getCommitIndex() {
        return state.commitIndex;
    }

    public LeaseManager lease() {
        return lease;
    }

    public MeshState getState() {
        return state;
    }

    /** 注入落盘 hook（阶段 11 替换为真实 fsync）。 */
    public void setPersistHook(Runnable hook) {
        this.persistHook = hook == null ? () -> { } : hook;
    }

    // ==================== 选举：ElectionTimer 回调（在 raftExecutor 上执行）====================

    /**
     * ElectionTimer 超时回调。先 PreVote 探测，多数派后才正式选举。
     * <p>本方法在 {@link #raftExecutor} 单线程上执行，故可安全读写 state。</p>
     */
    private void onElectionTimeout() {
        if (stopped) {
            return;
        }
        // LEADER 不该触发 election timeout（心跳定时器与之分离）；防御性忽略
        if (state.role == MeshRole.LEADER) {
            logger.debug("LEADER 忽略 election timeout");
            return;
        }
        logger.info("选举超时，发起 PreVote 探测: term={}, role={}", state.currentTerm, state.role);
        runPreVote();
    }

    /**
     * PreVote 探测：不自增 term、不改 votedFor，发 preVote=true 的 RequestVote。
     * 多数派预投 → 进入正式选举；未达多数派 → 保持现状。
     */
    private void runPreVote() {
        // 取消可能残留的收集器
        cancelCurrentCollector();

        long term = state.currentTerm;
        long lastLogIndex = state.getLastLogIndex();
        long lastLogTerm = state.getLastLogTerm();
        int total = config.getTotalNodes();

        VoteCollector collector = new VoteCollector(nodeId, total, true /* preVote */,
                (won, t, granted, tot) -> {
                    // 在 raftExecutor 线程回调（scheduler 是 raftExecutor）
                    if (won) {
                        logger.info("PreVote 获多数派 (granted={}/{})，发起正式选举", granted, tot);
                        runRealElection();
                    } else {
                        logger.info("PreVote 未获多数派 (granted={}/{})，保持 FOLLOWER，不自增 term",
                                granted, tot);
                    }
                });
        currentVoteCollector = collector;

        RequestVoteMessage msg = new RequestVoteMessage(term, nodeId, lastLogIndex, lastLogTerm, true);
        collector.start(config.getPeerNodeIds(), msg, busClient, term);
    }

    /**
     * 正式选举：becomeCandidate（自增 term、投自己）→ 正式 RequestVote。
     */
    private void runRealElection() {
        if (stopped) {
            return;
        }
        cancelCurrentCollector();

        Transition t = stateMachine.becomeCandidate(state, nodeId);
        logger.info("转为 CANDIDATE: term={}, lastLog={}/{}", t.newTerm, t.lastLogIndex, t.lastLogTerm);

        // 重置 election timer（candidate 状态下继续计时，超时则下一轮选举）
        electionTimer.reset();

        long term = state.currentTerm;
        VoteCollector collector = new VoteCollector(nodeId, config.getTotalNodes(), false /* real */,
                (won, tt, granted, tot) -> {
                    if (won) {
                        onWinElection();
                    } else {
                        logger.info("正式选举未达多数派 (granted={}/{})，继续等下一轮", granted, tot);
                    }
                });
        currentVoteCollector = collector;

        RequestVoteMessage msg = new RequestVoteMessage(term, nodeId, t.lastLogIndex, t.lastLogTerm, false);
        collector.start(config.getPeerNodeIds(), msg, busClient, term);
    }

    /** 赢得正式选举 → becomeLeader。 */
    private void onWinElection() {
        if (stopped) {
            return;
        }
        Transition t = stateMachine.becomeLeader(state, nodeId, config.getPeerNodeIds());
        // 应用 nextIndex/matchIndex
        nextIndex.clear();
        matchIndex.clear();
        nextIndex.putAll(t.nextIndex);
        matchIndex.putAll(t.matchIndex);
        logger.info("转为 LEADER: term={}，nextIndex={}", t.newTerm, nextIndex);

        // 启动心跳 + 首轮空 AppendEntries（建立权威 + 续租）
        startHeartbeat();
        broadcastHeartbeat();
    }

    private void cancelCurrentCollector() {
        VoteCollector c = currentVoteCollector;
        if (c != null && !c.isCompleted()) {
            c.cancel(state.currentTerm);
        }
        currentVoteCollector = null;
    }

    // ==================== 心跳（Leader 侧）====================

    /** 启动周期心跳（每 heartbeatIntervalMs 广播空 AppendEntries）。 */
    private void startHeartbeat() {
        stopHeartbeat();
        long interval = config.getHeartbeatIntervalMs();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (state.role == MeshRole.LEADER) {
                    broadcastHeartbeat();
                } else {
                    // 已非 Leader：定时器自身会因 stopHeartbeat 而停，防御性忽略
                }
            } catch (Exception e) {
                logger.error("心跳广播异常", e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) {
            t.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * 向所有 peer 广播空 AppendEntries（心跳），收集多数派 ACK 续租。
     * <p>阶段 4 会扩展为按 nextIndex 发送真实 entries（LogReplicator）。</p>
     */
    private void broadcastHeartbeat() {
        long term = state.currentTerm;
        long leaderCommit = state.commitIndex;
        for (String peer : config.getOtherNodeIds()) {
            long ni = nextIndex.getOrDefault(peer, state.getLastLogIndex() + 1);
            long prevLogIndex = ni - 1;
            long prevLogTerm = prevLogIndex > 0 ? state.getLogTerm(prevLogIndex) : 0L;
            AppendEntriesMessage msg = new AppendEntriesMessage(
                    term, nodeId, prevLogIndex, prevLogTerm, Collections.emptyList(), leaderCommit);
            MeshFrame frame = new MeshFrame(nodeId, MessageType.APPEND_ENTRIES.getCode(), msg.encode());
            try {
                busClient.send(peer, frame);
            } catch (Exception e) {
                logger.warn("心跳发送到 {} 失败", peer, e);
            }
        }
        // 注意：续租在收到多数派 success=true 时进行（onAppendEntriesResponse），此处不立即续租。
        // 单节点集群（无 peer）下 Leader 无需多数派 ACK，直接续租。
        if (config.getOtherNodeIds().isEmpty()) {
            lease.refreshOnMajorityAck(System.currentTimeMillis());
        }
    }

    // ==================== 入站 RPC 分发（在 raftExecutor 上执行）====================

    /**
     * 入站消息总入口（由 {@code MeshBusHandler} 的 consumer 调用）。
     * 反序列化后提交到 raftExecutor 串行处理。
     *
     * @param fromNodeId 发送者 nodeId
     * @param frame      总线帧
     */
    public void onMessage(String fromNodeId, MeshFrame frame) {
        MessageType type;
        try {
            type = MessageType.fromCode(frame.getType());
        } catch (IllegalArgumentException e) {
            logger.warn("未知消息类型，丢弃: {}", frame, e);
            return;
        }
        MeshRpcMessage msg;
        try {
            msg = MeshRpcMessage.decode(type, frame.getBody());
        } catch (Exception e) {
            logger.warn("消息反序列化失败，丢弃: from={}, type={}", fromNodeId, type, e);
            return;
        }
        raftExecutor.execute(() -> {
            try {
                dispatch(fromNodeId, type, msg);
            } catch (Exception e) {
                logger.error("处理消息异常: from={}, type={}", fromNodeId, type, e);
            }
        });
    }

    private void dispatch(String fromNodeId, MessageType type, MeshRpcMessage msg) {
        switch (type) {
            case REQUEST_VOTE:
                handleRequestVote(fromNodeId, (RequestVoteMessage) msg);
                break;
            case REQUEST_VOTE_RESP:
                handleRequestVoteResponse(fromNodeId, (RequestVoteResponse) msg);
                break;
            case APPEND_ENTRIES:
                handleAppendEntries(fromNodeId, (AppendEntriesMessage) msg);
                break;
            case APPEND_ENTRIES_RESP:
                handleAppendEntriesResponse(fromNodeId, (AppendEntriesResponse) msg);
                break;
            case INSTALL_SNAPSHOT:
                // 阶段 10 实现
                logger.debug("INSTALL_SNAPSHOT 阶段 10 实现，暂忽略");
                break;
            default:
                logger.warn("未处理的消息类型: {}", type);
        }
    }

    // ==================== RequestVote 处理（被投票方）====================

    void handleRequestVote(String fromNodeId, RequestVoteMessage msg) {
        VoteDecision decision = stateMachine.decideRequestVote(state, msg);
        // 若发生降级（term > currentTerm），应用副作用
        if (decision.transition.kind == Transition.Kind.TO_FOLLOWER) {
            applyFollowerSideEffects(decision.transition);
        }
        if (decision.resetElectionTimer) {
            electionTimer.reset();
        }
        // 回复投票结果
        sendResponse(fromNodeId, MessageType.REQUEST_VOTE_RESP, decision.response);
        logger.debug("回复 RequestVote: from={}, granted={}, preVote={}",
                abbrev(fromNodeId), decision.response.isVoteGranted(), msg.isPreVote());
    }

    // ==================== RequestVote 响应处理（发起方 Candidate）====================

    void handleRequestVoteResponse(String fromNodeId, RequestVoteResponse resp) {
        // 任期裁决：resp.term > currentTerm → 降级 follower
        if (resp.getTerm() > state.currentTerm) {
            Transition t = stateMachine.becomeFollower(state, resp.getTerm(), null);
            applyFollowerSideEffects(t);
            electionTimer.reset();
            return;
        }
        VoteCollector c = currentVoteCollector;
        if (c == null || c.isCompleted()) {
            // 无进行中的选举，丢弃（可能是过期响应）
            return;
        }
        // PreVote 响应与正式响应走同一个 collector（currentVoteCollector 指向当前阶段）
        c.onVoteReceived(fromNodeId, resp, state.currentTerm);
    }

    // ==================== AppendEntries 处理（Follower 侧接收）====================

    void handleAppendEntries(String fromNodeId, AppendEntriesMessage msg) {
        AppendDecision decision = stateMachine.decideAppendEntries(state, msg, persistHook);
        if (decision.transition.kind == Transition.Kind.TO_FOLLOWER) {
            applyFollowerSideEffects(decision.transition);
        }
        if (decision.resetElectionTimer) {
            electionTimer.reset();
        }
        sendResponse(fromNodeId, MessageType.APPEND_ENTRIES_RESP, decision.response);
        logger.debug("回复 AppendEntries: from={}, success={}, match={}",
                abbrev(fromNodeId), decision.response.isSuccess(), decision.response.getMatchIndex());
    }

    // ==================== AppendEntries 响应处理（Leader 侧）====================

    void handleAppendEntriesResponse(String fromNodeId, AppendEntriesResponse resp) {
        // 任期裁决
        if (resp.getTerm() > state.currentTerm) {
            Transition t = stateMachine.becomeFollower(state, resp.getTerm(), null);
            applyFollowerSideEffects(t);
            electionTimer.reset();
            return;
        }
        if (state.role != MeshRole.LEADER) {
            return;
        }
        if (resp.isSuccess()) {
            long prevMatch = matchIndex.getOrDefault(fromNodeId, 0L);
            if (resp.getMatchIndex() > prevMatch) {
                matchIndex.put(fromNodeId, resp.getMatchIndex());
                // 同步推进 nextIndex（阶段 4 在 propose 时精确控制）
                nextIndex.put(fromNodeId, resp.getMatchIndex() + 1);
            }
            // 多数派 ACK 续租：累计自己 + success 的 peer 数 >= majority
            maybeRefreshLease();
        } else {
            // success=false：回退 nextIndex（阶段 4 完善精确日志补发，阶段 3 先回退）
            long ni = nextIndex.getOrDefault(fromNodeId, state.getLastLogIndex() + 1);
            if (ni > 1) {
                nextIndex.put(fromNodeId, ni - 1);
                logger.debug("AppendEntries 失败，回退 nextIndex: peer={} → {}", fromNodeId, ni - 1);
            }
        }
    }

    /** 统计含自己在内的 success ACK 数，达多数派则续租。 */
    private void maybeRefreshLease() {
        int acks = 1; // 自己
        long myLastLog = state.getLastLogIndex();
        for (Long m : matchIndex.values()) {
            if (m != null && m >= myLastLog) {
                acks++;
            }
        }
        // 心跳续租条件：收到多数派 success（含自己）。
        // 注：阶段 3 心跳为空 entries，matchIndex 反映此前已确认值；这里用"多数派 peer 已响应过"
        // 作为续租近似。更精确的 commit 多数派推进在阶段 4 LogReplicator。
        if (acks >= config.majority()) {
            lease.refreshOnMajorityAck(System.currentTimeMillis());
        }
    }

    // ==================== 副作用（解析 Transition）====================

    /**
     * 应用 TO_FOLLOWER 副作用：停心跳、失效租约、取消当前投票收集器。
     * ElectionTimer 的 reset 由调用方按 resetElectionTimer 决定。
     */
    private void applyFollowerSideEffects(Transition t) {
        if (t.kind == Transition.Kind.NONE) {
            return;
        }
        stopHeartbeat();
        lease.invalidate();
        cancelCurrentCollector();
        logger.info("转为 FOLLOWER: term={}, leader={}", t.newTerm, t.newLeaderId);
    }

    // ==================== 发送响应 ====================

    private void sendResponse(String targetNodeId, MessageType type, MeshRpcMessage resp) {
        byte[] body = resp.encode();
        MeshFrame frame = new MeshFrame(nodeId, type.getCode(), body);
        try {
            busClient.send(targetNodeId, frame);
        } catch (Exception e) {
            logger.warn("发送响应到 {} 失败: type={}", targetNodeId, type, e);
        }
    }

    // ==================== 测试辅助（包级可见）====================

    /** 同步执行一个任务在 raftExecutor 上（测试用，便于确定性断言）。 */
    void submitSync(Runnable r) {
        try {
            raftExecutor.submit(r).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 等待 raftExecutor 已提交任务全部执行完（测试用）。 */
    void awaitIdle() {
        try {
            raftExecutor.submit(() -> { }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    Map<String, Long> getNextIndexView() {
        return new HashMap<>(nextIndex);
    }

    Map<String, Long> getMatchIndexView() {
        return new HashMap<>(matchIndex);
    }

    VoteCollector getCurrentVoteCollector() {
        return currentVoteCollector;
    }

    private static String abbrev(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }

    @SuppressWarnings("unused")
    private static List<String> emptyIfNull(List<String> in) {
        return in == null ? new ArrayList<>() : in;
    }

    @SuppressWarnings("unused")
    private static Set<String> emptyIfNull(Set<String> in) {
        return in == null ? Collections.emptySet() : in;
    }
}
