package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.mesh.core.ApplyBarrier;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.AppendDecision;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.Transition;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine.VoteDecision;
import com.janeluo.luban.rds.mesh.election.ElectionTimer;
import com.janeluo.luban.rds.mesh.election.LeaseManager;
import com.janeluo.luban.rds.mesh.election.VoteCollector;
import com.janeluo.luban.rds.mesh.gateway.ReadIndexCache;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.replication.LogReplicator;
import com.janeluo.luban.rds.mesh.replication.SnapshotManager;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexResponseMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mesh 节点主体（DESIGN.md §7.1）。
 * <p>
 * 阶段 3 实现：选举（ElectionTimer + VoteCollector + PreVote）、心跳广播、AppendEntries Follower 接收、
 * Leader Lease 续租。
 * <b>阶段 4 补全</b>：{@link #propose(byte[], int, byte[])} 客户端写入口、{@link LogReplicator} 日志复制、
 * {@link LogApplier} apply 到 raw store（不写 AOF）。当注入 {@link LogApplier} 后启用 propose/apply 能力。
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

    /** P1-12b：非成员来源帧丢弃计数（可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong unknownPeerFrames =
            new java.util.concurrent.atomic.AtomicLong();

    /** P1-13：入站帧分发许可（默认 4096）——重发风暴下有界排队；permits 可注入便于测试。 */
    private final java.util.concurrent.Semaphore inboundPermits;

    /** P1-13：入站帧因许可耗尽的丢弃计数。 */
    private final java.util.concurrent.atomic.AtomicLong inboundDropped =
            new java.util.concurrent.atomic.AtomicLong();

    /** 入站许可默认值。 */
    static final int DEFAULT_INBOUND_PERMITS = 4096;

    /** Q7：单条 entry 编码大小上限（12MB）——低于总线帧 16MB 上限留余量。 */
    static final long MAX_ENTRY_BYTES = 12L * 1024 * 1024;

    /** Q7：估算 entry 编码字节数（固定字段约 28B + payload + extra，与 LogReplicator 估算口径一致）。 */
    static long estimateEntryBytes(byte[] payload, byte[] extra) {
        long size = 28;
        if (payload != null) {
            size += payload.length;
        }
        if (extra != null) {
            size += extra.length;
        }
        return size;
    }
    private final MeshState state;
    private final MeshBusClient busClient;
    private final RaftStateMachine stateMachine;

    /** 串行化 Raft 状态访问的单线程调度器。 */
    private final ScheduledExecutorService raftExecutor;

    /** 投票收集超时：2× 选举超时上限（D3，9/3 事故）。响应永不到达时的兜底收尾窗口。 */
    private static final long VOTE_COLLECT_TIMEOUT_MS = 2L * ElectionTimer.DEFAULT_MAX_MS;
    /** ElectionTimer 使用的调度器（raftExecutor 单线程：定时器回调是重状态变更，必须与 AE 处理串行）。 */
    private final ScheduledExecutorService scheduler;
    /**
     * P0-6（2026-09-11 mesh 审计）：心跳发送/轻量周期任务专用单线程——与 raftExecutor 解耦。
     * 写积压（apply 挤占 raft 队列 ~200ms）曾延迟心跳 tick 致最敏感 follower PreVote 推翻
     * 健康 Leader（8/6 选举风暴同构根因）。心跳 tick 在此线程只做帧构建+发送，
     * 不做 commit 推进/apply/续租（raft 线程职责）。
     */
    private final ScheduledExecutorService timerExecutor;
    /** 落盘专用单线程调度器（与 raftExecutor 解耦：fsync 不得阻塞心跳/RPC 处理）。
     *  P1-16：具体类型 ScheduledThreadPoolExecutor——暴露 getQueue() 供积压采样。 */
    private final java.util.concurrent.ScheduledThreadPoolExecutor persistExecutor;
    /** 本节点已落盘的最大日志 index（仅 raft 线程读写；volatile 仅为可见性兜底）。 */
    private volatile long durableIndex;

    /**
     * Q3（2026-09-11 审计）：持久性门控开关——mesh-persist=yes（默认）时为 true，
     * commit 受 durableIndex 门控；mesh-persist=no 时为 false，durableIndex 视为已跟上
     * （弱持久语义，仅用于性能测试/低持久性场景，崩溃后已确认写可能丢失）。
     * 由 MeshBootstrap 装配时设置。
     */
    private volatile boolean durableGatingActive = true;

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

    /**
     * 角色/Leader 变更监听器（阶段 12 装配注入）。可为 {@code null}（无监听器）。
     * <p>在 {@code raftExecutor} 单线程上由 {@link #notifyRoleListener} 调用，故实现无需自身加锁。</p>
     */
    private volatile RoleChangeListener roleListener;

    // ==================== 阶段 4：日志复制与 apply ====================

    /**
     * Leader 侧日志复制器（nextIndex/matchIndex + 批量 AppendEntries + 多数派 commit + apply）。
     * 可为 null（未注入 LogApplier/Handler/RawStore 时，阶段 3 行为：无 propose 能力）。
     */
    private final LogReplicator replicator;
    /** apply 到 raw store 的应用器（仅用 raw store + handle，不写 AOF）。 */
    private final LogApplier applier;

    /** apply 屏障（follower 读路径用）；readIndex 取用与缓存见 gateway 层。 */
    private final ApplyBarrier applyBarrier;

    // ==================== follower 读：readIndex 收发（fix-mesh-follower-read）====================

    /** readIndex 请求序号（单调递增，请求-响应关联用）。 */
    private final AtomicLong readIndexSeq = new AtomicLong();

    /** 在途 readIndex 请求：requestId → future（响应在 raftExecutor 上落定）。 */
    private final Map<Long, CompletableFuture<ReadIndexResponseMessage>> pendingReadIndex =
            new ConcurrentHashMap<>();

    /** 在途 readIndex 请求上限（超限不排队，直接回落 MOVED：只降活性不破正确性）。 */
    static final int MAX_INFLIGHT_READ_INDEX = 256;
    private final Semaphore readIndexPermits = new Semaphore(MAX_INFLIGHT_READ_INDEX);

    /** 取读点总次数（每次进入 fetchReadIndex 即计，含成功与失败；INFO 用）。 */
    private final AtomicLong followerReadFetchTotal = new AtomicLong();
    /** 回落 MOVED 次数（fetch 失败，以及 gate 的回落分支）。 */
    private final AtomicLong followerReadFallback = new AtomicLong();
    /** 在途上限拒绝次数。 */
    private final AtomicLong followerReadRejected = new AtomicLong();
    /** follower 本地读成功次数（由 gate 递增）。 */
    private final AtomicLong followerReadLocal = new AtomicLong();

    /**
     * readIndex 短窗口缓存 + single-flight（fix-mesh-follower-read）。
     * <p>缓存窗口与 RPC 超时由 gate 按配置传入（{@code ReadIndexCache.get} 参数化），
     * 故本节点不持有配置字段；term/Leader 变更/apply halt 时由 gate 调 {@code invalidate()}。</p>
     */
    private final ReadIndexCache readIndexCache = new ReadIndexCache();

    /** 是否已就绪（启动加载完成且本地状态可信，或快照安装追平）；单向置位。 */
    private volatile boolean ready;

    /**
     * 阶段 10：快照管理器（chunked INSTALL_SNAPSHOT + 周期快照）。
     * 可为 null（未注入时收到 INSTALL_SNAPSHOT 静默忽略，保持向后兼容）。
     */
    private volatile SnapshotManager snapshotManager;

    /**
     * P0-8（2026-09-11 mesh 审计）：周期快照检查间隔（ms）。
     * takePeriodicSnapshotIfNeeded 此前零生产调用 → WAL/内存 log 无界增长、写路径成本随
     * 运行时长线性上升。timer 线程只投递，快照本体在 raft 线程执行（与 apply 串行保证
     * dump 一致性，P0-6 后心跳独立不受快照停顿影响）。
     */
    static final long SNAPSHOT_CHECK_INTERVAL_MS = 30_000L;
    /** 周期快照检查任务；setSnapshotManager 晚于 start 时由兜底注册（幂等）。 */
    private volatile ScheduledFuture<?> snapshotCheckTask;

    /**
     * P1-16（2026-09-11 mesh 审计）：持久化积压告警阈值（队列深度）。
     * 不设硬拒绝——丢弃或 CallerRuns 回灌持久化任务都会破坏正确性（回灌 raft 线程 = 复现 P0-6）。
     * 正确响应是告警 + 排查磁盘瓶颈。
     */
    static final int PERSIST_BACKLOG_WARN_THRESHOLD = 1_000;
    /** 积压告警累计次数（测试可观测）。 */
    private final java.util.concurrent.atomic.AtomicLong persistBacklogWarnCount =
            new java.util.concurrent.atomic.AtomicLong();

    /** 一条在途 propose：完成句柄 + 请求 key（领导丢失时生成 MOVED 用）。 */
    private static final class PendingProposal {
        final CompletableFuture<byte[]> future;
        final String key;

        PendingProposal(CompletableFuture<byte[]> future, String key) {
            this.future = future;
            this.key = key;
        }
    }

    /**
     * Leader 侧待响应的 propose：index → {@link PendingProposal}（完成句柄 + 请求 key）。
     * apply 完成后由 LogReplicator.appliedNotifier 回调，complete 对应 future。
     * 仅 raftExecutor 线程读写（apply 串行保证），用 ConcurrentHashMap 仅作线程安全兜底。
     */
    private final Map<Long, PendingProposal> pendingProposals = new ConcurrentHashMap<>();

    private volatile boolean started;
    private volatile boolean stopped;

    public MeshNode(MeshConfig config, MeshState state, MeshBusClient busClient) {
        this(config, state, busClient, new RaftStateMachine(), null, null);
    }

    /**
     * 测试与定制构造器：可注入自定义 {@link RaftStateMachine}（如 mock 裁决逻辑）。
     */
    public MeshNode(MeshConfig config, MeshState state, MeshBusClient busClient, RaftStateMachine stateMachine) {
        this(config, state, busClient, stateMachine, null, null);
    }

    /**
     * 阶段 4 完整构造器：注入 apply 所需的 {@link LogApplier}（含 raw store + handler）。
     * <p>
     * applier 非 null 时启用 propose / apply 能力（Leader 侧 commit 后 apply + complete future；
     * Follower 侧 leaderCommit 推进后 apply）。applier 为 null 时保持阶段 3 行为（无 propose）。
     * </p>
     *
     * @param config       集群配置
     * @param state        Raft 状态
     * @param busClient    总线客户端
     * @param stateMachine 状态机裁决器
     * @param applier      apply 应用器（null=不启用 propose/apply）
     * @param rawStoreRef  原始存储引用（当前未直接使用，保留供阶段 7 读路径；apply 走 applier）
     */
    public MeshNode(MeshConfig config, MeshState state, MeshBusClient busClient,
                    RaftStateMachine stateMachine, LogApplier applier, Object rawStoreRef) {
        this.config = config;
        this.nodeId = config.getSelfNodeId();
        this.state = state;
        this.busClient = busClient;
        this.inboundPermits = new java.util.concurrent.Semaphore(DEFAULT_INBOUND_PERMITS);
        this.stateMachine = stateMachine;
        // 单线程：保证 Raft 状态变更串行化
        this.raftExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-raft-" + abbrev(nodeId));
            t.setDaemon(true);
            return t;
        });
        this.scheduler = this.raftExecutor;
        // P0-6：心跳发送独立线程——apply 积压不再延迟心跳帧（ElectionTimer 留 raft 线程串行）
        this.timerExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mesh-timer-" + abbrev(nodeId));
            t.setDaemon(true);
            return t;
        });
        // 落盘线程独立于 raft 线程：写高峰 fsync 不再停摆心跳（选举风暴根因修复）。
        // P1-16：直接构造 ScheduledThreadPoolExecutor（暴露 getQueue 供积压采样）。
        this.persistExecutor = new java.util.concurrent.ScheduledThreadPoolExecutor(1, r -> {
            Thread t = new Thread(r, "mesh-persist-" + abbrev(nodeId));
            t.setDaemon(true);
            return t;
        });
        // 启动时状态已从 WAL 恢复 → 全部已持久化
        this.durableIndex = state.getLastLogIndex();
        this.lease = new LeaseManager(config.getLeaseDurationMs());
        this.electionTimer = new ElectionTimer(
                config.getElectionTimeoutMinMs(),
                config.getElectionTimeoutMaxMs(),
                this::onElectionTimeout,
                this.scheduler);

        this.applier = applier;
        // apply 屏障：follower 读路径按绝对索引等待本地 lastApplied 追平（fix-mesh-follower-read）
        this.applyBarrier = new ApplyBarrier(state);
        if (applier != null) {
            this.replicator = new LogReplicator(nodeId, config, state, busClient, applier);
            // 自身 match 以已落盘 index 为上限（未落盘不 commit，持久性语义）
            // Q3（2026-09-11 审计）：mesh-persist=no（durableGatingActive=false）时门控短路——
            // durableIndex 视为已跟上 lastLogIndex（"凭空 durable"显式化为弱持久语义）
            this.replicator.setDurableIndexSupplier(() ->
                    durableGatingActive ? durableIndex : state.getLastLogIndex());
            // apply 完成回调：complete 对应 pendingProposals future（携带 apply 响应对象；序列化为字节）
            this.replicator.setAppliedNotifier(this::onEntryApplied);
            // apply 每推进一条 → 唤醒屏障等待者（follower 读路径）
            this.replicator.setAppliedSignal(applyBarrier::signalApplied);
            // 多数派 ACK 续租回调（Leader Lease，DESIGN §5.7）
            this.replicator.setLeaseRefresher(() ->
                    lease.refreshOnMajorityAck(System.currentTimeMillis()));
        } else {
            this.replicator = null;
        }
    }

    // ==================== 生命周期 ====================

    /** 本节点 nodeId（来自 {@link MeshConfig#getSelfNodeId()}）。 */
    public String getNodeId() {
        return nodeId;
    }

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
        startSnapshotCheck();
        // P1-16：persistExecutor 积压周期采样（timer 线程，10s）
        timerExecutor.scheduleAtFixedRate(this::samplePersistBacklog,
                10_000L, 10_000L, TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        electionTimer.stop();
        stopHeartbeat();
        ScheduledFuture<?> sc = snapshotCheckTask;
        if (sc != null) {
            sc.cancel(false);
            snapshotCheckTask = null;
        }
        lease.invalidate();
        raftExecutor.shutdownNow();
        timerExecutor.shutdownNow();
        persistExecutor.shutdownNow();
        // 在途 propose 未完成时 stop：必须以异常 complete，否则 gate 层 get() 永久悬挂
        failAllPendingOnStop();
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

    /** apply 屏障（follower 读路径按绝对索引等待 lastApplied 追平）。 */
    public ApplyBarrier applyBarrier() {
        return applyBarrier;
    }

    /** 启动加载完成且本地状态可信，或快照安装追平。 */
    public boolean isReady() {
        return ready;
    }

    /** 单向置位就绪（幂等）。 */
    public void markReady() {
        if (!ready) {
            ready = true;
            logger.info("mesh 节点就绪：本地状态可信，开始服务读");
        }
    }

    /** 快照安装完成：唤醒屏障等待者 + 置位就绪。 */
    public void onSnapshotInstalled() {
        applyBarrier.signalApplied();
        markReady();
    }

    /** apply 是否已 fail-stop（gate 读入口据此短路 -TRYAGAIN）。 */
    public boolean isApplyHalted() {
        return replicator != null && replicator.isApplyHalted();
    }

    /** P1-12b：非成员来源帧丢弃计数（观测/测试）。 */
    public long getUnknownPeerFrameCount() {
        return unknownPeerFrames.get();
    }

    /** P1-13：入站帧因许可耗尽的丢弃计数（观测/测试）。 */
    public long getInboundDroppedCount() {
        return inboundDropped.get();
    }

    /** P1-13：调整入站许可数（仅测试/运维动态调参用；运行中调小不影响已获许可）。 */
    void setInboundPermits(int permits) {
        inboundPermits.drainPermits();
        inboundPermits.release(Math.max(1, permits));
    }

    /** Q3：持久性门控是否生效（mesh-persist=no 时为 false）。 */
    public boolean isDurableGatingActive() {
        return durableGatingActive;
    }

    /** Q3：由装配层设置（mesh-persist=no → false，门控短路）。 */
    public void setDurableGatingActive(boolean active) {
        this.durableGatingActive = active;
    }

    /** 注入落盘 hook（阶段 11 替换为真实 fsync）。 */
    public void setPersistHook(Runnable hook) {
        this.persistHook = hook == null ? () -> { } : hook;
    }

    /**
     * 取当前落盘 hook（阶段 12 装配用，供 {@link com.janeluo.luban.rds.mesh.replication.SnapshotManager}
     * 复用同一 fsync 路径）。
     */
    public Runnable getPersistHookRef() {
        return persistHook;
    }

    /**
     * 注入角色/Leader 变更监听器（阶段 12 装配注入）。
     * <p>在 {@code raftExecutor} 单线程上回调，故实现无需自身加锁。传 {@code null} 清除监听器。</p>
     *
     * @param listener 监听器；{@code null} 清除
     */
    public void setRoleChangeListener(RoleChangeListener listener) {
        this.roleListener = listener;
    }

    /**
     * 通知监听器角色/Leader 变更（在 raftExecutor 上调用）。
     * <p>异常仅记录日志——监听器异常不应中断 Raft 状态机转换。</p>
     */
    private void notifyRoleListener() {
        RoleChangeListener l = roleListener;
        if (l == null) {
            return;
        }
        try {
            l.onRoleChanged(state.role, state.leaderId);
        } catch (Exception e) {
            logger.warn("roleListener 回调异常 role={} leader={}", state.role, state.leaderId, e);
        }
    }

    /**
     * 角色/Leader 变更监听器接口（阶段 12）。
     * <p>回调在 {@code raftExecutor} 单线程上执行，实现无需自身加锁。
     * 典型用法：{@code MeshLifecycleListener} 收到 becomeLeader/becomeFollower 时刷新 leader 缓存。</p>
     */
    public interface RoleChangeListener {
        /**
         * @param role     当前角色（FOLLOWER/CANDIDATE/LEADER）
         * @param leaderId 当前已知 Leader nodeId；无 Leader 时为 {@code null}
         */
        void onRoleChanged(MeshRole role, String leaderId);
    }

    /**
     * 阶段 11：安全触发持久化 hook（term/votedFor/log/lastIncluded 变化时机）。
     * <p>persistHook 实际实现由装配层注入（调 {@code MeshConfigPersister.save}）。
     * 异常仅记录日志——persistHook 实现内部应自行决定 fail-fast 策略（如 propose 路径
     * 已在 {@code doPropose} 内 catch 并 completeExceptionally）。</p>
     * <p>本方法用于 term/votedFor 变化的「软」持久化点：落盘失败不应中断角色转换
     * （节点仍可继续运行，最坏情况下崩溃后 term 不一致由 Raft 任期裁决自愈）。</p>
     *
     * @param reason 持久化原因（日志用）
     */
    private void persistStateSafe(String reason) {
        try {
            persistHook.run();
        } catch (Exception e) {
            logger.warn("persistStateSafe: 持久化失败 reason={}, term={}", reason, state.currentTerm, e);
        }
    }

    /**
     * 注入快照管理器（阶段 10）。注入后入站 INSTALL_SNAPSHOT 走 chunked 接收路径，
     * Leader 侧可调 {@link SnapshotManager#sendSnapshot} / {@link SnapshotManager#takePeriodicSnapshotIfNeeded}。
     * 未注入时收到 INSTALL_SNAPSHOT 静默忽略。
     */
    public void setSnapshotManager(SnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
        // D4b（9/3 事故）：转发给 replicator，启用"回退越界/持续 NACK → 快照重同步"降级
        if (replicator != null) {
            replicator.setSnapshotManager(snapshotManager);
        }
        // P0-8：注入晚于 start 时兜底注册周期快照检查（幂等）
        if (started) {
            startSnapshotCheck();
        }
    }

    /**
     * P1-16：persistExecutor 队列积压采样——深度超阈值时 WARN（附 durableIndex 与日志末尾的
     * 滞后量）。积压的正确响应是告警 + 找磁盘瓶颈，不是拒绝任务。
     */
    private void samplePersistBacklog() {
        if (persistExecutor.isShutdown()) {
            return;
        }
        int depth = persistExecutor.getQueue().size();
        if (depth > PERSIST_BACKLOG_WARN_THRESHOLD) {
            persistBacklogWarnCount.incrementAndGet();
            logger.warn("persistExecutor 积压告警: queueDepth={}, durableIndex={}, lastLogIndex={}"
                            + "（fsync 慢于写速率，commit/apply 将滞后——检查磁盘）",
                    depth, durableIndex, state.getLastLogIndex());
        }
    }

    /** P1-16：手动触发一次积压采样（测试钩子，与 timer 周期采样同体）。 */
    void runPersistBacklogSampleForTest() {
        samplePersistBacklog();
    }

    /**
     * P0-8：注册周期快照检查（timer 线程投递 → raft 线程执行快照本体）。
     * 幂等：已注册或未注入 SnapshotManager 时跳过。
     */
    private void startSnapshotCheck() {
        if (snapshotCheckTask != null || snapshotManager == null) {
            return;
        }
        snapshotCheckTask = timerExecutor.scheduleAtFixedRate(() -> {
            if (stopped || snapshotManager == null) {
                return;
            }
            runSnapshotCheckOnce();
        }, SNAPSHOT_CHECK_INTERVAL_MS, SNAPSHOT_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * P0-8：执行一次周期快照检查——把 takePeriodicSnapshotIfNeeded 投递到 raft 线程
     * （快照创建必须与 apply 串行保证 dump 一致性）。包级可见供测试直驱。
     */
    void runSnapshotCheckOnce() {
        submitToRaft(() -> {
            try {
                snapshotManager.takePeriodicSnapshotIfNeeded();
            } catch (Exception e) {
                logger.error("周期快照执行异常", e);
            }
        });
    }

    /** 取快照管理器（测试用，可能为 null）。 */
    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    // ==================== 阶段 4：propose（客户端写入口）====================

    /**
     * 客户端写入口（gate 调用，DESIGN §5.1 / §7.1）：propose 后阻塞，apply 完成后 future 携带响应字节。
     * <p>
     * 流程（DESIGN §5.1 步骤 1-2）：
     * <ol>
     *   <li>校验 {@code role==LEADER}，否则抛 {@link MovedToLeaderException}（阶段 4 占位：leader 地址未知）。</li>
     *   <li>{@code index = lastIncludedIndex + log.size() + 1}（含快照偏移）。</li>
     *   <li>构造 {@link LogEntry}(currentTerm, index, respPayload, dbIndex, extra)，state.appendEntry。</li>
     *   <li>注册 pending（{@link PendingProposal}：future + 请求 key）。</li>
     *   <li><b>异步持久化</b>（自身日志落盘）：调 persistHook（阶段 11 实现真实 fsync，阶段 4 no-op），
     *       在独立 persistExecutor 线程执行——fsync 不阻塞 raft 线程（心跳/RPC 照常）；
     *       落盘成功后经 {@link #onPersistSucceeded} 回 raft 线程推进 durableIndex 并重触发 commit，
     *       失败经 {@link #onPersistFailed} 回滚日志并 fail 在途 future。</li>
     *   <li>触发 {@link LogReplicator#replicate}（异步给 Follower 发 AppendEntries）。</li>
     *   <li>返回 future（调用方阻塞等待；落盘 + commit + apply 完成后被 complete）。</li>
     * </ol>
     * </p>
     * <p><b>线程模型</b>：propose 的状态访问（校验 role / appendEntry / 注册 future）在 raftExecutor
     * 单线程上执行，避免与 AppendEntries 响应处理并发改 state；落盘在 persistExecutor 独立线程
     * （fsync 不阻塞 raft 线程），完成/失败回调提交回 raftExecutor 串行处理。
     * 返回的 future 在 apply 完成后被 complete。</p>
     *
     * @param respPayload 完整 RESP 命令帧（事务时为 MULTI 帧）
     * @param dbIndex     apply 时传给 handler 的 database 参数
     * @param extra       事务：命令帧序列 + WATCH 版本快照；普通写为 {@code null}
     * @return CompletableFuture，apply 完成后携带客户端响应字节
     * @throws MovedToLeaderException 当前不是 Leader
     */
    public CompletableFuture<byte[]> propose(byte[] respPayload, int dbIndex, byte[] extra) {
        if (applier == null || replicator == null) {
            CompletableFuture<byte[]> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("MeshNode 未启用 apply 能力（applier 未注入）"));
            return f;
        }

        // Q7（2026-09-11 审计）：条目大小预检——超限直接异常完成，不追加 log、不进复制，
        // 消灭"Encoder 静默丢弃 → 100ms 重发 → 再丢弃"死循环与 future 永久悬挂
        if (estimateEntryBytes(respPayload, extra) > MAX_ENTRY_BYTES) {
            CompletableFuture<byte[]> f = new CompletableFuture<>();
            f.completeExceptionally(new com.janeluo.luban.rds.mesh.gateway.RequestTooLargeException(
                    "request too large: " + estimateEntryBytes(respPayload, extra)
                            + " bytes exceeds limit " + MAX_ENTRY_BYTES));
            return f;
        }

        // 提交到 raftExecutor 串行执行状态访问；返回的 future 由 apply 回调 complete
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        raftExecutor.execute(() -> {
            try {
                doPropose(respPayload, dbIndex, extra, future);
            } catch (Throwable t) {
                // 异常路径：complete future 让调用方收到错误，不悬挂
                future.completeExceptionally(t);
                // 移除可能已注册的 pending（避免泄漏）
                // 注：index 此时未知，无法精确移除；doPropose 内部已处理正常移除
            }
        });
        return future;
    }

    /**
     * propose 核心逻辑（在 raftExecutor 上执行）。
     */
    private void doPropose(byte[] respPayload, int dbIndex, byte[] extra, CompletableFuture<byte[]> future) {
        // 1. 校验 Leader
        if (state.role != MeshRole.LEADER) {
            // 非 Leader：抛 MovedToLeaderException 让客户端 MOVED 到 Leader。
            // 只携带 leaderNodeId（serviceAddr 留空），由 MeshClientRedirector 经
            // nodeIdToServiceAddr 映射解析真实 ip:port。此前用单参构造器把 nodeId 塞进
            // serviceAddr 字段，导致 MOVED 地址无端口 → Redisson "Redis url doesn't contain a port"。
            // 写路径从 RESP 帧提取真实 key（此前恒为 null → slot 0，与读路径 MOVED 的 slot 不一致；
            // 集群感知客户端靠 slot 更新本地路由缓存，恒 0 会导致重定向风暴）。
            future.completeExceptionally(
                    new MovedToLeaderException(state.leaderId, null, extractFirstKey(respPayload)));
            return;
        }

        // 2. 计算 index（含快照偏移：lastIncludedIndex + log.size() + 1）
        long index = state.getLastLogIndex() + 1;
        long term = state.currentTerm;

        // 3. 构造 LogEntry 并追加
        LogEntry entry = new LogEntry(term, index, respPayload, dbIndex, extra);
        state.appendEntry(entry);
        logger.trace("propose: append entry index={}, term={}, dbIndex={}", index, term, dbIndex);

        // 4. 注册 pending（含请求 key，供领导丢失时生成 MOVED）并异步落盘。
        //    落盘在独立持久化线程执行（fsync 不阻塞 raft 线程/心跳）；future 在
        //    落盘成功 + commit + apply 后由 onEntryApplied complete。
        pendingProposals.put(index, new PendingProposal(future, extractFirstKey(respPayload)));
        final long persistIndex = index;
        persistExecutor.execute(() -> {
            try {
                persistHook.run();
                // 落盘成功：提交回 raft 线程推进 durableIndex 并重触发 commit；
                // 节点已停止/线程池已关时静默丢弃（不误判为落盘失败）
                submitToRaft(() -> onPersistSucceeded(persistIndex));
            } catch (Exception e) {
                // 落盘失败：回 raft 线程截断日志 + fail 在途 pending
                //（回调提交失败时 submitToRaft 内部已丢弃，不再抛到 persist 线程）
                logger.error("propose: 自身日志落盘失败, index={}", index, e);
                submitToRaft(() -> onPersistFailed(persistIndex, e));
            }
        });

        // 5. 触发复制（异步给 Follower 发 AppendEntries）
        replicator.replicate(entry, false);

        // 6. 单节点集群：无 peer，propose 后立即自检 commit + apply（future 由 onEntryApplied complete）
        //    （replicate 内部已处理单节点 case，这里无需重复）
    }

    /**
     * 把任务提交回 raft 线程；节点已停止时静默丢弃（记录 debug，不误判为落盘失败）。
     * <p>stop 竞态：raftExecutor 已 shutdownNow 后 execute 会抛
     * {@link RejectedExecutionException}——此时回调已无意义（状态机已停），单独捕获并丢弃，
     * 不把异常抛到 persist 线程（否则会被误当成落盘失败进入 onPersistFailed 截断路径）。</p>
     */
    private void submitToRaft(Runnable task) {
        if (stopped || raftExecutor.isShutdown()) {
            logger.debug("raftExecutor 已停止，丢弃回调");
            return;
        }
        try {
            raftExecutor.execute(task);
        } catch (RejectedExecutionException e) {
            logger.debug("raftExecutor 拒绝回调（节点停止竞态），丢弃: {}", e.toString());
        }
    }

    /**
     * 从完整 RESP 命令帧中提取第一个 key（args[1]，命令名后第一个参数）。
     * <p>
     * 仅解析数组头 + 前两个参数（{@code *N\r\n$len\r\nCMD\r\n$len\r\nkey\r\n}），
     * 不持有帧、不做完整解析；供非 Leader 写路径生成 MOVED 的真实 slot 用
     * （读路径已有 args[1] 口径一致）。事务帧（MULTI）取第一条子命令的 key——
     * slot 只需合理近似。帧畸形/不可解析返回 {@code null}（回退 slot 0，不抛异常）。
     * </p>
     *
     * @param respFrame 客户端原始 RESP 帧字节（propose 的 respPayload）
     * @return 第一个 key；不可解析时为 {@code null}
     */
    private static String extractFirstKey(byte[] respFrame) {
        if (respFrame == null || respFrame.length < 4) {
            return null;
        }
        try {
            int pos = 0;
            // 数组头：*N\r\n
            if (respFrame[pos++] != '*') {
                return null;
            }
            while (pos < respFrame.length && respFrame[pos] != '\r') {
                pos++;
            }
            if (pos + 1 >= respFrame.length || respFrame[pos + 1] != '\n') {
                return null;
            }
            pos += 2;
            // 第 1 个元素：命令名 bulk string
            String cmd = parseBulkAt(respFrame, pos);
            if (cmd == null) {
                return null;
            }
            pos = bulkEnd(respFrame, pos);
            // 第 2 个元素：key bulk string
            return parseBulkAt(respFrame, pos);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 pos 处的 bulk string（$len\r\n<data>），返回数据；非法返回 null。 */
    private static String parseBulkAt(byte[] frame, int pos) {
        if (pos >= frame.length || frame[pos] != '$') {
            return null;
        }
        int i = pos + 1;
        long len = 0;
        boolean hasLen = false;
        while (i < frame.length && frame[i] != '\r') {
            char c = (char) (frame[i] & 0xFF);
            if (c < '0' || c > '9') {
                return null;
            }
            len = len * 10 + (c - '0');
            hasLen = true;
            i++;
        }
        if (!hasLen || i + 1 >= frame.length || frame[i + 1] != '\n') {
            return null;
        }
        i += 2;
        if (i + len > frame.length) {
            return null;
        }
        return new String(frame, i, (int) len, StandardCharsets.ISO_8859_1);
    }

    /** 返回 pos 处 bulk string 之后的偏移（数据末尾 + CRLF）；非法返回原 pos。 */
    private static int bulkEnd(byte[] frame, int pos) {
        if (pos >= frame.length || frame[pos] != '$') {
            return pos;
        }
        int i = pos + 1;
        while (i < frame.length && frame[i] != '\r') {
            i++;
        }
        if (i + 1 >= frame.length || frame[i + 1] != '\n') {
            return pos;
        }
        i += 2;
        long len = 0;
        for (int j = pos + 1; j < i - 2; j++) {
            char c = (char) (frame[j] & 0xFF);
            if (c < '0' || c > '9') {
                return pos;
            }
            len = len * 10 + (c - '0');
        }
        long end = i + len;
        if (end + 2 > frame.length) {
            return pos;
        }
        return (int) end + 2;
    }

    /**
     * apply 完成回调（由 LogReplicator.appliedNotifier 调用，在 raftExecutor 上）。
     * <p>
     * complete 对应 index 的 pending propose future。响应字节 = LogApplier 已 apply 产出的响应对象
     * （{@code responseObject}）序列化为 RESP 字节。这里<b>不再重复 apply</b>——LogReplicator.applyCommittedEntries
     * 已经把 entry 作用于 raw store 一次，此处的 responseObject 即那次 apply 的返回值，直接序列化即可。
     * 避免对 INCR 等非幂等命令的双写。
     * </p>
     *
     * @param index         已 apply 的日志 index
     * @param responseObject apply 返回的响应对象（handle 的返回值；Follower 侧无 future 时丢弃）
     */
    private void onEntryApplied(long index, Object responseObject) {
        PendingProposal pp = pendingProposals.remove(index);
        if (pp == null || pp.future.isDone()) {
            // 非 Leader 或该 index 无 pending propose（如 Follower 侧 apply），忽略
            return;
        }
        try {
            byte[] respBytes = applier.serializeResponse(responseObject);
            pp.future.complete(respBytes);
        } catch (Throwable t) {
            pp.future.completeExceptionally(t);
        }
    }

    /**
     * 落盘成功回调（raft 线程执行）：推进 durableIndex 并重触发 commit/apply。
     * <p>必须重触发：自身 match 以 durableIndex 为上限，落盘完成后才可能推进 commitIndex；
     * 不重触发则 commit 永久停摆（死锁）。</p>
     */
    private void onPersistSucceeded(long persistIndex) {
        // 落盘成功可能晚于失败截断（FIFO 队列中后续任务成功、前序已截断日志）：
        // 只推进到「日志中仍存在」的 index，避免 durableIndex 虚高导致 commit 门控失效
        long capped = Math.min(persistIndex, state.getLastLogIndex());
        if (capped > durableIndex) {
            durableIndex = capped;
        }
        if (replicator != null && state.role == MeshRole.LEADER) {
            boolean advanced = replicator.maybeAdvanceCommitIndex();
            if (advanced) {
                replicator.applyCommittedEntries();
            }
        }
    }

    /**
     * 落盘失败回调（raft 线程执行）：回滚该条目并 fail 所有受影响的在途 propose。
     * <p>约束：truncateAfter 必须先于 complete（同任务内原子完成），否则排队中的后续
     * propose 条目被连带截断后其 future 会悬挂（MeshNodePersistAsyncTest.persistFailure 断言依赖此顺序）。</p>
     */
    private void onPersistFailed(long persistIndex, Exception cause) {
        // 已非 Leader：pending 已在失去领导时 fail 完，新 Leader 的 AppendEntries 会修复本节点日志，
        // 这里不得再截断（可能删除新 Leader 已复制/已提交的条目）
        if (state.role != MeshRole.LEADER) {
            return;
        }
        // 截断后日志缩短，durableIndex 不得高于截断边界（防虚高导致 commit 门控失效）
        if (durableIndex > persistIndex - 1) {
            durableIndex = persistIndex - 1;
        }
        // 防御：截断不得低于已 commit 边界（已提交已 apply 条目不可删）
        long truncateTo = Math.max(persistIndex - 1, state.commitIndex);
        state.truncateAfter(truncateTo);
        Iterator<Map.Entry<Long, PendingProposal>> it = pendingProposals.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, PendingProposal> e = it.next();
            if (e.getKey() >= persistIndex) {
                e.getValue().future.completeExceptionally(
                        new IllegalStateException("leader persist failed", cause));
                it.remove();
            }
        }
        if (replicator != null && state.role == MeshRole.LEADER) {
            replicator.maybeAdvanceCommitIndex();
        }
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
                        // 选举退避：连续失败后增大下次 election timeout 区间，
                        // 使先超时者有窗口赢得选举，避免并发争票致 term 飙升（选举风暴根因）
                        electionTimer.onElectionFailed();
                    }
                }, raftExecutor, VOTE_COLLECT_TIMEOUT_MS);
        currentVoteCollector = collector;

        RequestVoteMessage msg = new RequestVoteMessage(term, nodeId, lastLogIndex, lastLogTerm, true);
        collector.start(config.getPeerNodeIds(), msg, busClient, term);

        // ElectionTimer 是一次性 schedule（非 scheduleAtFixedRate），onElectionTimeout 触发后
        // 该 future 即被消费。runRealElection 在 L516 有 reset()，runPreVote 此前漏了 →
        // PreVote 未达多数派时 timer 不再排下一轮，节点永久静默，集群死锁无 leader（MESHDOWN）。
        // 这里补一次 reset，使 PreVote 胜负都重排下一轮选举超时，与 runRealElection 对称。
        electionTimer.reset();
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

        // 阶段 11：becomeCandidate 自增了 term、设了 votedFor=self → 持久化（fsync 在确认路径）
        persistStateSafe("becomeCandidate");

        // 重置 election timer（candidate 状态下继续计时，超时则下一轮选举）
        electionTimer.reset();

        long term = state.currentTerm;
        VoteCollector collector = new VoteCollector(nodeId, config.getTotalNodes(), false /* real */,
                (won, tt, granted, tot) -> {
                    if (won) {
                        onWinElection();
                    } else {
                        logger.info("正式选举未达多数派 (granted={}/{})，继续等下一轮", granted, tot);
                        electionTimer.onElectionFailed();
                    }
                }, raftExecutor, VOTE_COLLECT_TIMEOUT_MS);
        currentVoteCollector = collector;

        RequestVoteMessage msg = new RequestVoteMessage(term, nodeId, t.lastLogIndex, t.lastLogTerm, false);
        collector.start(config.getPeerNodeIds(), msg, busClient, term);
    }

    /** 赢得正式选举 → becomeLeader。 */
    private void onWinElection() {        if (stopped) {
            return;
        }
        Transition t = stateMachine.becomeLeader(state, nodeId, config.getPeerNodeIds());
        // 应用 nextIndex/matchIndex（阶段 3 map + 阶段 4 replicator map）
        nextIndex.clear();
        matchIndex.clear();
        nextIndex.putAll(t.nextIndex);
        matchIndex.putAll(t.matchIndex);
        if (replicator != null) {
            replicator.initOnBecomeLeader(config.getOtherNodeIds());
        }
        logger.info("转为 LEADER: term={}，nextIndex={}", t.newTerm, nextIndex);

        // P1-3（2026-09-11 mesh 审计）：新 Leader 追加当前任期 no-op（标准做法）——
        // §5.4.2 只直接提交 currentTerm 条目，无 no-op 时重启遗留的旧 term 未确认条目
        // 在无新写入场景下永远无法间接提交（与 P0-2 的 commitIndex 收敛配套）。
        appendNoOpEntry();

        // 启动心跳 + 首轮空 AppendEntries（建立权威 + 续租）
        startHeartbeat();
        broadcastHeartbeat();
        // 选举成功：复位退避（Leader 不需要 election timeout，但防御性复位供下次降级时用）
        electionTimer.onElectionSucceeded();
        // 阶段 12：通知角色监听器（Leader 变更）
        notifyRoleListener();
        // 成为 Leader 后读路径不再需要 follower 读屏障：唤醒等待者使其立即按新角色重判定
        applyBarrier.signalApplied();

        // 未就绪当选 Leader：Leader 只会向外发 INSTALL_SNAPSHOT，不会接收快照，
        // 故没有 peer 能替本节点补齐状态；就绪门会持续拒绝集群读，需运维介入。
        // 仅在角色切换时触发（罕见），无需限流。
        if (!isReady()) {
            logger.error("未就绪（本地状态不可信 / store 未追平）节点当选 LEADER：没有 peer 会向 "
                    + "Leader 发 INSTALL_SNAPSHOT，读请求将被就绪门持续拒绝（-TRYAGAIN）。"
                    + "运维处置：恢复与 lastIncludedIndex 匹配的 dump.rdb 后重启，"
                    + "或删除 raft-nodes.conf 强制以可信空状态重启（接受数据丢失）。nodeId={}", nodeId);
        }
    }

    /**
     * P1-3：追加当前任期 no-op 条目（新 Leader 间接提交锚点）。
     * <p>
     * 不注册 pendingProposals（无客户端 future）；走既有异步落盘 + 复制路径
     * （落盘成功后 onPersistSucceeded 推进 durableIndex 并重触发 commit——
     * no-op 的 currentTerm 使 §5.4.2 Fig-8 检查通过，旧 term tail 随之间接提交）。
     * 仅在启用 replicator/applier 的节点生效（阶段 3 无复制能力的测试节点跳过）。
     * </p>
     */
    private void appendNoOpEntry() {
        if (replicator == null) {
            return;
        }
        long index = state.getLastLogIndex() + 1;
        LogEntry noop = new LogEntry(state.currentTerm, index, new byte[0], 0, LogEntry.NO_OP_EXTRA);
        state.appendEntry(noop);
        logger.info("新 Leader 追加 no-op 条目: term={}, index={}", state.currentTerm, index);
        final long persistIndex = index;
        persistExecutor.execute(() -> {
            try {
                persistHook.run();
                submitToRaft(() -> onPersistSucceeded(persistIndex));
            } catch (Exception e) {
                logger.error("no-op 条目落盘失败: index={}", persistIndex, e);
            }
        });
        replicator.replicate(noop, true);
    }

    private void cancelCurrentCollector() {
        VoteCollector c = currentVoteCollector;
        if (c != null && !c.isCompleted()) {
            c.cancel(state.currentTerm);
        }
        currentVoteCollector = null;
    }

    // ==================== 心跳（Leader 侧）====================

    /** 启动周期心跳（每 heartbeatIntervalMs 广播 AppendEntries）。P0-6：挂独立 timerExecutor。 */
    private void startHeartbeat() {
        stopHeartbeat();
        long interval = config.getHeartbeatIntervalMs();
        heartbeatTask = timerExecutor.scheduleAtFixedRate(() -> {
            try {
                if (state.role != MeshRole.LEADER) {
                    // 已非 Leader：定时器自身会因 stopHeartbeat 而停，防御性忽略
                    return;
                }
                if (config.getOtherNodeIds().isEmpty()) {
                    // 单节点集群：commit/apply/续租是 raft 线程职责，投递回去串行执行
                    submitToRaft(MeshNode.this::singleNodeHeartbeatTick);
                    return;
                }
                // P0-6：心跳帧构建+发送在 timer 线程完成（读取线程安全访问器：
                // volatile 标量 + MeshState 读锁 + ConcurrentHashMap），raft 线程积压不影响发送
                replicator.sendHeartbeatFrames();
            } catch (Exception e) {
                logger.error("心跳广播异常", e);
            }
        }, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * 单节点集群心跳 tick（raft 线程执行）：commit 自检 + apply + 续租
     * （原 replicate() 的单节点分支——多节点时这些由 AE 响应处理路径驱动）。
     */
    private void singleNodeHeartbeatTick() {
        if (state.role != MeshRole.LEADER || replicator == null) {
            return;
        }
        boolean advanced = replicator.maybeAdvanceCommitIndex();
        if (advanced) {
            replicator.applyCommittedEntries();
        }
        lease.refreshOnMajorityAck(System.currentTimeMillis());
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> t = heartbeatTask;
        if (t != null) {
            t.cancel(false);
            heartbeatTask = null;
        }
    }

    /**
     * 向所有 peer 广播 AppendEntries（心跳 + 积压日志补发），收集多数派 ACK 续租。
     * <p>阶段 4：注入 replicator 时，按 nextIndex 携带真实 entries（含积压补发）；
     * 否则发空 entries（阶段 3 行为）。</p>
     */
    private void broadcastHeartbeat() {
        if (replicator != null) {
            // 阶段 4：心跳同时复用为「积压补发 + 续租」，携带真实 entries
            replicator.replicate(null, true);
            // 单节点集群：无 peer，replicate 内已处理 commit/apply/续租
            return;
        }

        // 阶段 3 回退：空 entries 心跳
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
        // P1-12b（2026-09-11 审计）：成员校验——非 peers 成员的来源直接丢弃，
        // 防任意能连 busPort 的主体注入高 term 帧（压制选举）或伪造 leaderId（MOVED 劫持）。
        if (fromNodeId == null || fromNodeId.isEmpty()
                || !config.getPeerNodeIds().contains(fromNodeId)) {
            long dropped = unknownPeerFrames.incrementAndGet();
            if (dropped % 1000 == 1) {
                logger.warn("未知来源帧丢弃（成员校验失败）: from={}, 累计={}", fromNodeId, dropped);
            }
            return;
        }
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
        // peer 在线信号：出站断连时立即重置重连退避（避免节点重启后干等最长 64s 退避窗口）
        busClient.notifyPeerAlive(fromNodeId);
        // P1-13（2026-09-11 审计）：入站准入控制——对端重发风暴下有界排队。
        // 只约束入站帧分发；内部任务（持久化回调/timer tick/propose）不经信号量（I7），
        // 其队列拒绝语义不受影响。所有 Raft RPC 均可安全重发，丢弃只降活性不破正确性。
        if (!inboundPermits.tryAcquire()) {
            long dropped = inboundDropped.incrementAndGet();
            if (dropped % 1000 == 1) {
                logger.warn("入站帧分发许可耗尽，丢弃: from={}, type={}, 累计丢弃={}",
                        fromNodeId, type, dropped);
            }
            return;
        }
        raftExecutor.execute(() -> {
            try {
                dispatch(fromNodeId, type, msg);
            } catch (Exception e) {
                logger.error("处理消息异常: from={}, type={}", fromNodeId, type, e);
            } finally {
                inboundPermits.release();
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
                // 阶段 10：chunked INSTALL_SNAPSHOT（DESIGN §5.4）
                if (snapshotManager != null) {
                    InstallSnapshotMessage snap = (InstallSnapshotMessage) msg;
                    // P0-4（2026-09-11 mesh 审计）：高 term 快照的任期抬升必须走 becomeFollower
                    // 完整转换（停心跳/失效租约/取消收集器/落盘/复位定时器）。此前
                    // SnapshotManager 直接改 currentTerm/votedFor/leaderId 而不降级——
                    // 自认 Leader 的旧节点收快照后以新 term 双主；term/votedFor 也不落盘。
                    if (snap.getTerm() > state.currentTerm) {
                        Transition t = stateMachine.becomeFollower(state, snap.getTerm(), snap.getLeaderId());
                        applyFollowerSideEffects(t);
                        persistStateSafe("installSnapshot-term-up");
                        electionTimer.onElectionSucceeded();
                        electionTimer.reset();
                    } else if (snap.getTerm() >= state.currentTerm) {
                        // P0-4 延伸：每个通过任期校验的合法 chunk 复位选举定时器——
                        // 数百 MB 快照传输数秒内不因超时发起 PreVote 打断传输
                        electionTimer.onElectionSucceeded();
                        electionTimer.reset();
                    }
                    snapshotManager.handleInstallSnapshot(fromNodeId, snap);
                } else {
                    logger.debug("INSTALL_SNAPSHOT 收到但 SnapshotManager 未注入，暂忽略");
                }
                break;
            case READ_INDEX_REQ:
                handleReadIndexRequest(fromNodeId, (ReadIndexRequestMessage) msg);
                break;
            case READ_INDEX_RESP:
                handleReadIndexResponse((ReadIndexResponseMessage) msg);
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
            // 降级时 term 已变化 → 持久化（阶段 11 fsync 在确认路径）
            persistStateSafe("decideRequestVote-term-up");
            // 降级后不在此 reset 选举定时器：老 pending 到点触发 PreVote 时用的已是新 term，
            // follower 无 Leader 时发起探测本就是 Raft 正常行为
        }
        // D2（9/3 事故）：收到 RequestVote（含 PreVote）不再复位退避/重排选举定时器。
        // RequestVote 不是 Leader 存在的证据；双孤 follower 互相探测会互踩定时器并架空
        // 选举退避（9/3 事故 22 秒无 Leader 僵局的放大器）。对齐 Raft §9.6 / etcd：
        // 只有合法 AppendEntries（Leader 心跳）才 reset 接收方选举定时器——见 handleAppendEntries。
        // Q2（2026-09-11 审计 P2）：唯一例外 = 授出选票（decision.resetElectionTimer 语义）——
        // §5.2 标准行为；votedFor 每 term 唯一，授予复位不可能被同 term 反复探测利用
        //（denied / 过期 term 均不复位，D2 语义不变）。
        // 阶段 11 + P1-1（2026-09-11 mesh 审计）：正式投票授予 fail-stop——
        // votedFor 先持久化，成功才发 granted，失败改发 denied。此前 persistStateSafe 吞异常后
        // 照发 granted：内存已投票、磁盘未写，崩溃恢复后同 term 二次投票 → 双 Leader。
        // 异步化到 persistExecutor 同时消除 raft 线程投票路径的同步 fsync（P0-6 放大器）。
        if (!msg.isPreVote() && decision.response.isVoteGranted()) {
            final RequestVoteResponse granted = decision.response;
            final RequestVoteResponse denied = new RequestVoteResponse(
                    granted.getTerm(), false, granted.isPreVote(), granted.getElectionTerm());
            final String voter = fromNodeId;
            persistExecutor.execute(() -> {
                try {
                    persistHook.run();
                    // Q2：授予生效（持久化成功）后复位选举定时器（§5.2）
                    electionTimer.reset();
                    sendResponse(voter, MessageType.REQUEST_VOTE_RESP, granted);
                } catch (Exception e) {
                    logger.error("votedFor 持久化失败，拒绝授予投票: term={}, candidate={}",
                            granted.getTerm(), abbrev(voter), e);
                    sendResponse(voter, MessageType.REQUEST_VOTE_RESP, denied);
                }
            });
            return;
        }
        // PreVote 授予不复位（Q2 收窄）：PreVote 不消耗 votedFor，双孤 follower 互相授予
        // PreVote 会互踩定时器架空退避——正是 D2 要断的路径（MeshNodeTest 既有断言锁定）。
        // PreVote / 拒绝票：无需持久化，立即回复
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
            // 阶段 11：term 自增 → 持久化（fsync 在确认路径）
            persistStateSafe("handleRequestVoteResponse-term-up");
            electionTimer.reset();
            return;
        }
        VoteCollector c = currentVoteCollector;
        if (c == null || c.isCompleted()) {
            // 无进行中的选举，丢弃（可能是过期响应）
            return;
        }
        // P0-3（2026-09-11 mesh 审计）：三重轮次校验——term 相等 + 阶段匹配 + 选举轮次相等。
        // PreVote(term N) 的迟到票不得计入正式选举(term N+1)——此前只按 fromNodeId 去重，
        // 幽灵票凑多数派可选出无真实多数派的 Leader（同 term 双主）；旧版本帧
        // electionTerm=0 同样被丢弃（滚动升级窗口保守安全）。
        if (resp.getTerm() != state.currentTerm
                || resp.isPreVote() != c.isPreVote()
                || resp.getElectionTerm() != state.currentTerm) {
            logger.debug("丢弃轮次不匹配的投票响应: from={}, respTerm={}, respPreVote={}, respElectionTerm={}, "
                            + "currentTerm={}, stagePreVote={}",
                    abbrev(fromNodeId), resp.getTerm(), resp.isPreVote(), resp.getElectionTerm(),
                    state.currentTerm, c.isPreVote());
            return;
        }
        // PreVote 响应与正式响应走同一个 collector（currentVoteCollector 指向当前阶段）
        c.onVoteReceived(fromNodeId, resp, state.currentTerm);
    }

    // ==================== AppendEntries 处理（Follower 侧接收）====================

    void handleAppendEntries(String fromNodeId, AppendEntriesMessage msg) {
        long commitBefore = state.commitIndex;
        long appliedBefore = state.lastApplied;
        AppendDecision decision = stateMachine.decideAppendEntries(state, msg);
        if (decision.transition.kind == Transition.Kind.TO_FOLLOWER) {
            // applyFollowerSideEffects 内部已失效读点缓存（角色/Leader 变更），此处不再重复调用，
            // 否则 readIndexCacheInvalidationCount 每次转移 +2（INFO 指标虚高）。
            applyFollowerSideEffects(decision.transition);
            // 阶段 11：若 term 自增导致降级 → 持久化（追加的 fsync 已由 decideAppendEntries 内
            // persistHook 完成；此处覆盖 term 变化场景）
            persistStateSafe("handleAppendEntries-term-up");
        }
        // 冲突截断会收缩本地日志（WAL 已同步重写）：durableIndex 不得高于收缩后的日志末尾，
        // 否则 commit 门控（candidate > durableIndex 不 commit）被架空——本节点曾为 Leader 时
        // durableIndex 可能远高于截断后的 lastLogIndex，重新当选后未落盘条目即可被 commit。
        if (state.getLastLogIndex() < durableIndex) {
            durableIndex = state.getLastLogIndex();
        }
        if (decision.resetElectionTimer) {
            // 先复位退避、再重排定时器：否则 reset 用过期 consecutiveFailures 多带一轮退避。
            // 收到合法 AppendEntries（Leader 心跳）→ 复位退避
            electionTimer.onElectionSucceeded();
            electionTimer.reset();
        }

        // 阶段 4：Follower 侧——commitIndex 被 leaderCommit 推进后，apply 已提交条目到 raw store。
        // DESIGN §5.1 步骤5：Follower apply 到 raw store，响应对象丢弃（仅推进 lastApplied）。
        if (replicator != null && state.commitIndex > appliedBefore) {
            try {
                replicator.applyCommittedEntriesFollower();
            } catch (Exception e) {
                logger.error("Follower apply 异常: commit {}→{}",
                        commitBefore, state.commitIndex, e);
            }
        }

        sendResponse(fromNodeId, MessageType.APPEND_ENTRIES_RESP, decision.response);
        // 心跳响应每 100ms 一次，trace 级别（帧级噪声，与 MeshBusCodec 一致）
        logger.trace("回复 AppendEntries: from={}, success={}, match={}",
                abbrev(fromNodeId), decision.response.isSuccess(), decision.response.getMatchIndex());

        // P0-7（2026-09-11 mesh 审计）：WAL 落盘挪 persistExecutor（与 Leader propose 路径对称）——
        // follower 不再在 raft 线程同步 fsync（磁盘抖动曾直接阻塞心跳应答与选举定时器）。
        // 内存追加成功即 ACK；持久化失败仅 ERROR（不回滚内存日志：WAL 落后由 Leader 重发兜底，
        // 与 onPersistFailed 的 follower 早退语义一致）。
        if (!msg.getEntries().isEmpty()) {
            final long persistIndex = state.getLastLogIndex();
            persistExecutor.execute(() -> {
                try {
                    persistHook.run();
                    submitToRaft(() -> onPersistSucceeded(persistIndex));
                } catch (Exception e) {
                    logger.error("follower AppendEntries 落盘失败: lastIndex={}", persistIndex, e);
                }
            });
        }
    }

    // ==================== AppendEntries 响应处理（Leader 侧）====================

    void handleAppendEntriesResponse(String fromNodeId, AppendEntriesResponse resp) {
        // 任期裁决
        if (resp.getTerm() > state.currentTerm) {
            Transition t = stateMachine.becomeFollower(state, resp.getTerm(), null);
            applyFollowerSideEffects(t);
            // 阶段 11：term 自增 → 持久化（fsync 在确认路径）
            persistStateSafe("handleAppendEntriesResponse-term-up");
            electionTimer.reset();
            return;
        }
        // P1-2（2026-09-11 mesh 审计）：旧任期迟到响应直接丢弃——旧 term 的 success 曾推高
        // matchIndex/nextIndex 并刷新租约（租约虚高旧数据可读窗口 + 复制跳段靠后续 NACK 自愈）
        if (resp.getTerm() != state.currentTerm) {
            logger.debug("丢弃过期 AppendEntries 响应: from={}, respTerm={}, currentTerm={}",
                    abbrev(fromNodeId), resp.getTerm(), state.currentTerm);
            return;
        }
        if (state.role != MeshRole.LEADER) {
            return;
        }

        // 阶段 4：注入 replicator 时，委托给 replicator 处理（matchIndex/nextIndex/commit/apply）
        if (replicator != null) {
            replicator.onAppendEntriesResponse(fromNodeId, resp, true);
            // Q9（2026-09-11 审计 P2）：删除每响应的双拷贝视图同步（getNextIndexView/
            // getMatchIndexView 各一次 HashMap 拷贝 + putAll）——replicator 路径下本地
            // nextIndex/matchIndex 只写不读（读取方均为 replicator==null 的阶段 3 回退路径）
            return;
        }

        // 阶段 3 回退路径（无 replicator）
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

    // ==================== readIndex：Leader 应答 / Follower 取读点（fix-mesh-follower-read）====================

    /**
     * Leader 侧 readIndex 处理。
     * <p>只有"自己是 Leader 且租约有效"才给出读点——与 DESIGN §5.7 现行 Leader 读同一假设，
     * 不新增时钟假设。非 Leader / 租约失效一律 {@code success=false}，让发起方立刻回落 MOVED
     * 而不是白等一个 RPC 超时。</p>
     * <p><b>故意不在 {@code req.getTerm() > currentTerm} 时自降级/抬 term</b>：readIndex 请求不是
     * 选举，term 收敛由既有 AppendEntries / RequestVote 路径负责；在此引入 term 变更会绕过
     * 那些路径的副作用（停心跳/失效租约/落盘），得不偿失。发起方按应答 term 自行裁决。</p>
     * <p>在 raftExecutor 单线程上执行（dispatch 保证），故直接读 {@link MeshState} 安全。</p>
     */
    void handleReadIndexRequest(String fromNodeId, ReadIndexRequestMessage req) {
        boolean leaderWithLease = isLeader() && lease.isValid(System.currentTimeMillis());
        long readIndex = leaderWithLease ? state.commitIndex : 0L;
        // leaderNodeId：成功时填自己（信息性）；自己是 Leader 但租约失效时填 null——绝不能把
        // 自己指为 Leader，否则 gate 会把 success=false 变成指向自身的 MOVED 重试自环，
        // 让发起方走自身已知 Leader / CLUSTERDOWN 回落；非 Leader 时给出已知 Leader。
        String leaderNodeId = leaderWithLease ? nodeId : (isLeader() ? null : state.leaderId);
        ReadIndexResponseMessage resp = new ReadIndexResponseMessage(
                state.currentTerm, req.getRequestId(), readIndex, leaderWithLease, leaderNodeId);
        sendResponse(fromNodeId, MessageType.READ_INDEX_RESP, resp);
        logger.trace("回复 READ_INDEX: from={}, success={}, readIndex={}",
                abbrev(fromNodeId), leaderWithLease, readIndex);
    }

    /** 入站 readIndex 应答：在 raftExecutor 单线程上落定在途 future。 */
    void handleReadIndexResponse(ReadIndexResponseMessage resp) {
        completePendingReadIndex(resp);
    }

    /**
     * 向 Leader 取读点。
     * <p>同步阻塞调用线程（业务线程）至响应到达或超时。<b>只允许在非 Leader 上调用。</b></p>
     *
     * @param timeoutMs 总等待上限
     * @return 读点索引；无 Leader / 无许可 / 应答失败 / 超时 / term 不匹配一律返回 {@code null}
     */
    public Long fetchReadIndex(long timeoutMs) {
        followerReadFetchTotal.incrementAndGet();    // 取读点总次数（含失败）
        String leaderId = state.leaderId;
        if (leaderId == null || leaderId.isEmpty() || leaderId.equals(nodeId)) {
            followerReadFallback.incrementAndGet();
            return null;
        }
        if (!readIndexPermits.tryAcquire()) {
            // 在途请求已达上限：不排队，直接回落（只降活性不破正确性）
            followerReadRejected.incrementAndGet();
            followerReadFallback.incrementAndGet();
            return null;
        }
        long requestId = readIndexSeq.incrementAndGet();
        CompletableFuture<ReadIndexResponseMessage> future = new CompletableFuture<>();
        pendingReadIndex.put(requestId, future);
        try {
            ReadIndexRequestMessage req = new ReadIndexRequestMessage(state.currentTerm, requestId);
            busClient.send(leaderId, new MeshFrame(nodeId, MessageType.READ_INDEX_REQ.getCode(), req.encode()));
            ReadIndexResponseMessage resp = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!resp.isSuccess()) {
                followerReadFallback.incrementAndGet();
                return null;
            }
            return resp.getReadIndex();
        } catch (TimeoutException e) {
            followerReadFallback.incrementAndGet();
            return null;
        } catch (Exception e) {
            logger.debug("fetchReadIndex 失败: leader={}, requestId={}", abbrev(leaderId), requestId, e);
            followerReadFallback.incrementAndGet();
            return null;
        } finally {
            pendingReadIndex.remove(requestId);
            readIndexPermits.release();
        }
    }

    /**
     * 在途 readIndex 应答落定（raftExecutor 上调用；{@link MeshState} 只在此线程改）。
     * <p>term 裁决：更高 term → 按 Raft 规则收敛（降级为 FOLLOWER + 落盘 + 复位定时器），
     * 但该读点不可采信；低于本节点 term → 过期响应丢弃；相等且成功 → 读点可用。</p>
     */
    void completePendingReadIndex(ReadIndexResponseMessage resp) {
        if (resp.getTerm() > state.currentTerm) {
            Transition t = stateMachine.becomeFollower(state, resp.getTerm(), null);
            // 失效读点缓存由 applyFollowerSideEffects 统一完成（见其收尾调用），此处不重复。
            applyFollowerSideEffects(t);
            persistStateSafe("readIndexResponse-term-up");
            electionTimer.reset();
            failPending(resp.getRequestId(), "higher term");
            return;
        }
        if (resp.getTerm() < state.currentTerm) {
            // 过期响应：丢弃（迟到/伪造都不采信）
            failPending(resp.getRequestId(), "stale term");
            return;
        }
        CompletableFuture<ReadIndexResponseMessage> f = pendingReadIndex.get(resp.getRequestId());
        if (f == null) {
            return;                                  // 已超时移除
        }
        f.complete(resp);
    }

    /** 以异常落定在途 future（响应不可采信时），使阻塞中的 fetchReadIndex 立即回落。 */
    private void failPending(long requestId, String reason) {
        CompletableFuture<ReadIndexResponseMessage> f = pendingReadIndex.get(requestId);
        if (f != null) {
            logger.debug("丢弃 readIndex 响应: requestId={}, reason={}", requestId, reason);
            f.completeExceptionally(new IllegalStateException("readIndex discarded: " + reason));
        }
    }

    /** 取读点总次数（每次进入 fetchReadIndex 即计，含成功与失败；INFO 用）。 */
    public long readIndexFetchCount() {
        return followerReadFetchTotal.get();
    }

    /** 回落 MOVED 次数（fetch 失败 + gate 回落分支；INFO 用）。 */
    public long followerReadFallbackCount() {
        return followerReadFallback.get();
    }

    /** 在途上限拒绝次数（INFO 用）。 */
    public long followerReadRejectedCount() {
        return followerReadRejected.get();
    }

    /** follower 本地读成功次数（INFO 用）。 */
    public long followerReadLocalCount() {
        return followerReadLocal.get();
    }

    /** follower 本地读成功计数 +1（gate 走本地读成功后调用）。 */
    public void incFollowerReadLocal() {
        followerReadLocal.incrementAndGet();
    }

    /** 回落 MOVED 计数 +1（gate 的回落分支调用）。 */
    public void incFollowerReadFallback() {
        followerReadFallback.incrementAndGet();
    }

    /** 本节点当前任期（读点缓存按 term 失效用）。 */
    public long currentTerm() {
        return state.currentTerm;
    }

    /** readIndex 缓存（gate 取读点与失效用）。 */
    public ReadIndexCache readIndexCache() {
        return readIndexCache;
    }

    /** readIndex 缓存命中次数（INFO 用）。 */
    public long readIndexCacheHitCount() {
        return readIndexCache.cacheHits();
    }

    /** readIndex 缓存失效次数（INFO 用）。 */
    public long readIndexCacheInvalidationCount() {
        return readIndexCache.invalidations();
    }

    /** readIndex 在途合并次数（INFO 用）。 */
    public long readIndexCoalescedCount() {
        return readIndexCache.coalesced();
    }

    /**
     * 读点缓存失效：term 变化 / Leader 变更时调用（唯一入口 {@code applyFollowerSideEffects}，
     * 所有抬 term/降级路径都经它，保证每次转移只失效一次、计数不虚高）。
     * <p>缓存里的读点只在"当时那个 term 的 Leader"下有效；角色/任期一变就必须立即失效，
     * 否则新 Follower 可能拿旧读点去等一个已无意义的 apply 屏障（陈旧读或白等）。</p>
     * <p>apply halt 不进这里：读入口已由 {@link #isApplyHalted()} <b>先于</b>缓存查询短路
     * （gate 的 read 顶部检查），停摆节点根本到不了缓存命中分支，无需重复处理。</p>
     */
    private void invalidateReadIndexCache() {
        readIndexCache.invalidate();
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
        // 阶段 4：失去 Leader 身份时，清空 replicator 复制状态 + fail 所有未完成的 pending propose
        if (replicator != null) {
            replicator.clearOnLoseLeadership();
        }
        failPendingProposalsOnLeadershipLoss();
        logger.info("转为 FOLLOWER: term={}, leader={}", t.newTerm, t.newLeaderId);
        // 阶段 12：通知角色监听器（失去 Leader / Leader 变更）
        notifyRoleListener();
        // 降级为 Follower 后等待语义变化：唤醒等待者立即按新角色重判定（避免等满超时）
        applyBarrier.signalApplied();
        // fix-mesh-follower-read：角色/Leader 变更 → 旧读点立即失效（唯一调用点：
        // 所有 term 抬升/降级路径都经此方法，避免在多处重复失效使计数虚高）
        invalidateReadIndexCache();
    }

    /**
     * 失去 Leader 身份时，把所有未完成的 pending propose future 以异常 complete。
     * <p>新 Leader 已知（收到其更高任期 AppendEntries，leaderId 已更新）且请求 key 可提取时抛
     * {@link MovedToLeaderException}（集群感知客户端自动跟随 MOVED 重试，避免 Redisson 对通用
     * ERR 不重试导致写失败）；新 Leader 尚未宣布（更高任期 RequestVote/响应路径）时返回
     * TRYAGAIN（{@link RetryableMeshException}），让集群感知客户端自动退避重试。
     * 这些 propose 的 entry 可能尚未 commit（未提交写入被新 Leader 覆盖），也可能已获多数派 ACK
     * 仅未推进 commitIndex（新 Leader 日志已有该条目并会 apply）——MOVED 重试为 at-least-once
     * 语义，与 Redis Cluster 故障转移一致，客户端重试可能重复执行非幂等命令（一致性 > 可用性的取舍）。</p>
     */
    private void failPendingProposalsOnLeadershipLoss() {
        if (pendingProposals.isEmpty()) {
            return;
        }
        String newLeaderId = state.leaderId;
        for (Map.Entry<Long, PendingProposal> e : pendingProposals.entrySet()) {
            PendingProposal pp = e.getValue();
            if (newLeaderId != null && pp.key != null) {
                pp.future.completeExceptionally(
                        new MovedToLeaderException(newLeaderId, null, pp.key));
            } else {
                pp.future.completeExceptionally(
                        new RetryableMeshException("leadership lost; propose aborted, retry"));
            }
        }
        pendingProposals.clear();
    }

    /**
     * stop 时把未完成的在途 propose 全部以异常 complete（防 gate 层永久悬挂）。
     * <p>调用方可能在 raft 线程之外（stop 由装配层/测试主线程调用）——pendingProposals
     * 是 ConcurrentHashMap，迭代安全；complete 后 clear，避免新提交的任务引用已 stop 的节点。</p>
     */
    private void failAllPendingOnStop() {
        if (pendingProposals.isEmpty()) {
            return;
        }
        IllegalStateException cause = new IllegalStateException("node stopped; propose aborted");
        for (Map.Entry<Long, PendingProposal> e : pendingProposals.entrySet()) {
            e.getValue().future.completeExceptionally(cause);
        }
        pendingProposals.clear();
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

    /** 阶段 4：取 replicator（测试用，可能为 null）。 */
    LogReplicator getReplicator() {
        return replicator;
    }

    /** 阶段 4：取 applier（测试用，可能为 null）。 */
    /** P1-10：取 applier（server 层装配 publish 投递回调用；可能为 null）。 */
    public LogApplier getApplier() {
        return applier;
    }

    /** 阶段 4：取 pending propose 数量（测试用）。 */
    int pendingProposalsCount() {
        return pendingProposals.size();
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
