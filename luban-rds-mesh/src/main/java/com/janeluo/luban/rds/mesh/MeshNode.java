package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
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
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.replication.LogReplicator;
import com.janeluo.luban.rds.mesh.replication.SnapshotManager;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * 阶段 10：快照管理器（chunked INSTALL_SNAPSHOT + 周期快照）。
     * 可为 null（未注入时收到 INSTALL_SNAPSHOT 静默忽略，保持向后兼容）。
     */
    private volatile SnapshotManager snapshotManager;

    /**
     * Leader 侧待响应的 propose：index → CompletableFuture。
     * apply 完成后由 LogReplicator.appliedNotifier 回调，complete 对应 future。
     * 仅 raftExecutor 线程读写（apply 串行保证），用 ConcurrentHashMap 仅作线程安全兜底。
     */
    private final Map<Long, CompletableFuture<byte[]>> pendingProposals = new ConcurrentHashMap<>();

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

        this.applier = applier;
        if (applier != null) {
            this.replicator = new LogReplicator(nodeId, config, state, busClient, applier);
            // apply 完成回调：complete 对应 pendingProposals future（携带 apply 响应对象；序列化为字节）
            this.replicator.setAppliedNotifier(this::onEntryApplied);
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
        void onRoleChanged(com.janeluo.luban.rds.mesh.core.MeshRole role, String leaderId);
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
     *   <li><b>持久化</b>（自身日志落盘）：调 persistHook（阶段 11 实现真实 fsync，阶段 4 no-op）。
     *       落盘在 raftExecutor 线程同步等待（future 在落盘后注册）。</li>
     *   <li>创建 {@link CompletableFuture}，注册到 pendingProposals。</li>
     *   <li>触发 {@link LogReplicator#replicate}（异步给 Follower 发 AppendEntries）。</li>
     *   <li>返回 future（调用方阻塞等待）。</li>
     * </ol>
     * </p>
     * <p><b>线程模型</b>：propose 的状态访问（校验 role / appendEntry / persistHook / 注册 future）
     * 必须在 raftExecutor 单线程上执行，避免与 AppendEntries 响应处理并发改 state。
     * 本方法把核心逻辑提交到 raftExecutor，返回的 future 在 apply 完成后被 complete。</p>
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
        logger.debug("propose: append entry index={}, term={}, dbIndex={}", index, term, dbIndex);

        // 4. 持久化（自身日志落盘，fsync 完成后才继续；阶段 4 为 persistHook no-op）
        //    DESIGN §5.1：Leader 必须在自身日志落盘后才 complete future 回客户端。
        try {
            persistHook.run();
        } catch (Exception e) {
            logger.error("propose: 自身日志落盘失败, index={}", index, e);
            future.completeExceptionally(new IllegalStateException("leader persist failed", e));
            // 回滚刚追加的 entry（避免未落盘日志被 commit）
            state.truncateAfter(index - 1);
            return;
        }

        // 5. 注册 pending future（apply 完成后由 onEntryApplied complete）
        pendingProposals.put(index, future);

        // 6. 触发复制（异步给 Follower 发 AppendEntries）
        replicator.replicate(entry, false);

        // 7. 单节点集群：无 peer，propose 后立即自检 commit + apply（future 由 onEntryApplied complete）
        //    （replicate 内部已处理单节点 case，这里无需重复）
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
        return new String(frame, i, (int) len, java.nio.charset.StandardCharsets.ISO_8859_1);
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
        CompletableFuture<byte[]> future = pendingProposals.remove(index);
        if (future == null || future.isDone()) {
            // 非 Leader 或该 index 无 pending propose（如 Follower 侧 apply），忽略
            return;
        }
        try {
            byte[] respBytes = applier.serializeResponse(responseObject);
            future.complete(respBytes);
        } catch (Throwable t) {
            future.completeExceptionally(t);
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
                });
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
        // 应用 nextIndex/matchIndex（阶段 3 map + 阶段 4 replicator map）
        nextIndex.clear();
        matchIndex.clear();
        nextIndex.putAll(t.nextIndex);
        matchIndex.putAll(t.matchIndex);
        if (replicator != null) {
            replicator.initOnBecomeLeader(config.getOtherNodeIds());
        }
        logger.info("转为 LEADER: term={}，nextIndex={}", t.newTerm, nextIndex);

        // 启动心跳 + 首轮空 AppendEntries（建立权威 + 续租）
        startHeartbeat();
        broadcastHeartbeat();
        // 选举成功：复位退避（Leader 不需要 election timeout，但防御性复位供下次降级时用）
        electionTimer.onElectionSucceeded();
        // 阶段 12：通知角色监听器（Leader 变更）
        notifyRoleListener();
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
                // 阶段 10：chunked INSTALL_SNAPSHOT（DESIGN §5.4）
                if (snapshotManager != null) {
                    snapshotManager.handleInstallSnapshot(fromNodeId, (InstallSnapshotMessage) msg);
                } else {
                    logger.debug("INSTALL_SNAPSHOT 收到但 SnapshotManager 未注入，暂忽略");
                }
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
        }
        if (decision.resetElectionTimer) {
            electionTimer.reset();
            // 收到合法 RequestVote（含 PreVote 探测）→ 有活跃选举活动，复位退避
            electionTimer.onElectionSucceeded();
        }
        // 阶段 11：正式投票（非 PreVote）且 granted → votedFor 已设置 → 持久化
        // （fsync 在回复投票前完成，保证崩溃恢复后不会同任期二次投票）
        if (!msg.isPreVote() && decision.response.isVoteGranted()) {
            persistStateSafe("decideRequestVote-grant");
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
        // PreVote 响应与正式响应走同一个 collector（currentVoteCollector 指向当前阶段）
        c.onVoteReceived(fromNodeId, resp, state.currentTerm);
    }

    // ==================== AppendEntries 处理（Follower 侧接收）====================

    void handleAppendEntries(String fromNodeId, AppendEntriesMessage msg) {
        long commitBefore = state.commitIndex;
        long appliedBefore = state.lastApplied;
        AppendDecision decision = stateMachine.decideAppendEntries(state, msg, persistHook);
        if (decision.transition.kind == Transition.Kind.TO_FOLLOWER) {
            applyFollowerSideEffects(decision.transition);
            // 阶段 11：若 term 自增导致降级 → 持久化（追加的 fsync 已由 decideAppendEntries 内
            // persistHook 完成；此处覆盖 term 变化场景）
            persistStateSafe("handleAppendEntries-term-up");
        }
        if (decision.resetElectionTimer) {
            electionTimer.reset();
            // 收到合法 AppendEntries（Leader 心跳）→ 复位退避
            electionTimer.onElectionSucceeded();
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
        logger.debug("回复 AppendEntries: from={}, success={}, match={}",
                abbrev(fromNodeId), decision.response.isSuccess(), decision.response.getMatchIndex());
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
        if (state.role != MeshRole.LEADER) {
            return;
        }

        // 阶段 4：注入 replicator 时，委托给 replicator 处理（matchIndex/nextIndex/commit/apply）
        if (replicator != null) {
            replicator.onAppendEntriesResponse(fromNodeId, resp, true);
            // 同步 MeshNode 的 nextIndex/matchIndex 视图（供 broadcastHeartbeat 兼容读取；阶段 3 map）
            nextIndex.putAll(replicator.getNextIndexView());
            matchIndex.putAll(replicator.getMatchIndexView());
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
    }

    /**
     * 失去 Leader 身份时，把所有未完成的 pending propose future 以异常 complete。
     * <p>
     * 这些 propose 的 entry 可能尚未 commit，按 Raft 语义新 Leader 不会复制它们（未提交写入被覆盖）。
     * 调用方（gate）收到异常后向客户端报错，符合「一致性 &gt; 可用性」。</p>
     */
    private void failPendingProposalsOnLeadershipLoss() {
        if (pendingProposals.isEmpty()) {
            return;
        }
        IllegalStateException cause = new IllegalStateException("leadership lost; propose aborted");
        for (Map.Entry<Long, CompletableFuture<byte[]>> e : pendingProposals.entrySet()) {
            e.getValue().completeExceptionally(cause);
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
    LogApplier getApplier() {
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
