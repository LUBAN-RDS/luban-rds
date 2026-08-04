package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;

/**
 * Leader 侧日志复制器（DESIGN.md §5.1 步骤 3-5 / 阶段 4.2）。
 * <p>
 * 职责：
 * <ul>
 *   <li>持有 {@code nextIndex[peer]} / {@code matchIndex[peer]}（becomeLeader 时初始化）。</li>
 *   <li>{@link #replicate(LogEntry, boolean)}：给所有 Follower 发 AppendEntries（携带从
 *       {@code nextIndex[peer]} 开始到 log 末尾的所有 entries，支持积压补发）。</li>
 *   <li>{@link #onAppendEntriesResponse(String, AppendEntriesResponse, boolean)}：处理 Follower 响应
 *       —— 任期裁决、成功推进 matchIndex/nextIndex、失败回退 nextIndex。</li>
 *   <li>{@link #maybeAdvanceCommitIndex()}：Raft §5.2/§5.4 commit 规则——找最大 N，
 *       {@code N > commitIndex && majority(matchIndex >= N) && log[N].term == currentTerm}。</li>
 *   <li>{@link #applyCommittedEntries()}：对 {@code lastApplied < commitIndex} 的条目逐条 apply
 *       （调 {@link LogApplier}），apply 完成后更新 lastApplied、complete 对应 pendingProposals future。</li>
 * </ul>
 * </p>
 *
 * <h3>commit 推进规则（Raft §5.4.2）</h3>
 * <p>
 * 「如果存在一个 N &gt; commitIndex，使得 majority 的 matchIndex[i] &ge; N，且 log[N].term == currentTerm，
 * 则 commitIndex = N」。<b>term == currentTerm 约束</b>保证 Leader 不会通过计数来提交旧任期的日志条目
 * （防止 Fig 8 问题：旧任期日志只能随新任期日志间接提交）。
 * </p>
 *
 * <h3>线程模型</h3>
 * <p>
 * 本类<b>所有</b>方法（{@code replicate}/{@code onAppendEntriesResponse}/
 * {@code maybeAdvanceCommitIndex}/{@code applyCommittedEntries}）必须在 {@code MeshNode.raftExecutor}
 * 单线程上串行调用——与阶段 3 的串行化模型一致（DESIGN §3.1：所有 Raft 状态访问串行）。
 * apply 在同一 raftExecutor 串行（v1 推荐，DESIGN §5.7：apply 串行保证互斥）。
 * 本类内部除 ConcurrentHashMap 外不做额外加锁，依赖调用方串行。
 * </p>
 *
 * <h3>多数派续租</h3>
 * <p>
 * 成功推进 matchIndex 后触发一次「多数派 ACK 续租」回调（阶段 3 的 LeaseManager.refreshOnMajorityAck），
 * 与 DESIGN §5.7 一致。本类不直接持有 LeaseManager，通过注入的 {@code leaseRefresher} 回调触发。
 * </p>
 */
public class LogReplicator {

    private static final Logger logger = LoggerFactory.getLogger(LogReplicator.class);

    private final String nodeId;
    private final MeshConfig config;
    private final MeshState state;
    private final MeshBusClient busClient;

    /** peer → nextIndex。becomeLeader 时初始化为 lastLogIndex+1。 */
    private final Map<String, Long> nextIndex = new ConcurrentHashMap<>();
    /** peer → matchIndex。becomeLeader 时初始化为 0。 */
    private final Map<String, Long> matchIndex = new ConcurrentHashMap<>();

    private final LogApplier applier;

    /**
     * apply 完成一条后的回调：参数1 = 该条目的 index，参数2 = apply 返回的响应对象（Object）。
     * MeshNode 注册此回调，用于 complete 对应 pendingProposals future（Leader 序列化响应对象为字节）。
     * Follower 侧 future 不存在，回调内 no-op。
     */
    private volatile BiConsumer<Long, Object> appliedNotifier;
    /** 续租回调：matchIndex 多数派 ACK 后触发（Leader Lease，阶段 3 LeaseManager.refreshOnMajorityAck）。 */
    private volatile Runnable leaseRefresher;

    /**
     * @param nodeId       本节点 nodeId
     * @param config       集群配置
     * @param state        Raft 状态（读写 log/commitIndex/lastApplied）
     * @param busClient    总线客户端（发 AppendEntries）
     * @param applier      日志应用器（apply 到 raw store）
     */
    public LogReplicator(String nodeId, MeshConfig config, MeshState state,
                         MeshBusClient busClient, LogApplier applier) {
        this.nodeId = nodeId;
        this.config = config;
        this.state = state;
        this.busClient = busClient;
        this.applier = applier;
    }

    /** 注入 apply 完成回调（参数1=已 apply 的 index，参数2=apply 返回的响应对象）。 */
    public void setAppliedNotifier(BiConsumer<Long, Object> notifier) {
        this.appliedNotifier = notifier;
    }

    /** 注入续租回调（多数派 ACK 后触发）。 */
    public void setLeaseRefresher(Runnable refresher) {
        this.leaseRefresher = refresher;
    }

    // ==================== nextIndex / matchIndex 管理 ====================

    /** becomeLeader 时初始化 nextIndex/matchIndex（DESIGN §5.2）。 */
    public void initOnBecomeLeader(Collection<String> peers) {
        nextIndex.clear();
        matchIndex.clear();
        long lastLogIndex = state.getLastLogIndex();
        if (peers != null) {
            for (String peer : peers) {
                if (peer.equals(nodeId)) {
                    continue;
                }
                nextIndex.put(peer, lastLogIndex + 1);
                matchIndex.put(peer, 0L);
            }
        }
        logger.info("Leader 初始化复制状态: nextIndex={}, matchIndex={}", nextIndex, matchIndex);
    }

    /** 失去 Leader 身份时清空（防陈旧 nextIndex/matchIndex 干扰新角色）。 */
    public void clearOnLoseLeadership() {
        nextIndex.clear();
        matchIndex.clear();
    }

    public Map<String, Long> getNextIndexView() {
        return new HashMap<>(nextIndex);
    }

    public Map<String, Long> getMatchIndexView() {
        return new HashMap<>(matchIndex);
    }

    public long getNextIndex(String peer) {
        return nextIndex.getOrDefault(peer, state.getLastLogIndex() + 1);
    }

    public long getMatchIndex(String peer) {
        return matchIndex.getOrDefault(peer, 0L);
    }

    // ==================== 复制（Leader → Follower）====================

    /**
     * 向所有 peer 发送 AppendEntries（携带从各自 nextIndex 到 log 末尾的 entries）。
     * <p>
     * 支持积压补发：每次取 {@code [nextIndex[peer], lastLogIndex]} 全段，不只新条目。
     * Follower 落盘后返回 success=true + matchIndex。
     * </p>
     *
     * @param newEntry 触发本次复制的新条目（仅用于日志；实际发送范围由 nextIndex 决定）
     * @param isHeartbeatCandidate true=可用于续租判定（多数派成功即续租）；
     *                              false=仅触发复制（如 propose 内联触发时不主动续租）
     */
    public void replicate(LogEntry newEntry, boolean isHeartbeatCandidate) {
        long term = state.currentTerm;
        long leaderCommit = state.commitIndex;
        long lastLogIndex = state.getLastLogIndex();

        for (String peer : config.getOtherNodeIds()) {
            long ni = getNextIndex(peer);
            if (ni < 1) {
                ni = 1;
                nextIndex.put(peer, ni);
            }
            // 携带从 nextIndex[peer] 到 log 末尾的所有 entries（批量补发）
            List<LogEntry> toSend = collectEntriesFrom(ni, lastLogIndex);
            long prevLogIndex = ni - 1;
            long prevLogTerm = state.getLogTerm(prevLogIndex);

            AppendEntriesMessage msg = new AppendEntriesMessage(
                    term, nodeId, prevLogIndex, prevLogTerm, toSend, leaderCommit);
            MeshFrame frame = new MeshFrame(nodeId, MessageType.APPEND_ENTRIES.getCode(), msg.encode());
            try {
                busClient.send(peer, frame);
            } catch (Exception e) {
                logger.warn("复制 AppendEntries 发往 {} 失败", peer, e);
            }
        }

        // 单节点集群：无 peer，matchIndex 仅含自己。propose 后立即检查 commit。
        if (config.getOtherNodeIds().isEmpty()) {
            maybeAdvanceCommitIndex();
            applyCommittedEntries();
            if (isHeartbeatCandidate && leaseRefresher != null) {
                leaseRefresher.run();
            }
        }
    }

    /**
     * 收集 [fromIndex, lastLogIndex] 范围内的日志条目（批量补发用）。
     * {@code fromIndex <= lastIncludedIndex} 的部分已被快照截断，从 lastIncludedIndex+1 开始取。
     */
    private List<LogEntry> collectEntriesFrom(long fromIndex, long lastLogIndex) {
        List<LogEntry> result = new ArrayList<>();
        long start = Math.max(fromIndex, state.lastIncludedIndex + 1);
        for (long idx = start; idx <= lastLogIndex; idx++) {
            LogEntry e = state.getEntry(idx);
            if (e != null) {
                result.add(e);
            }
        }
        return result;
    }

    // ==================== 响应处理（Leader 侧）====================

    /**
     * 处理 Follower 的 AppendEntries 响应。
     * <p>
     * <b>注意</b>：任期裁决（resp.term &gt; currentTerm → becomeFollower）由 {@code MeshNode} 在
     * 调用本方法<b>之前</b>完成（与阶段 3 handleAppendEntriesResponse 一致）。本方法假定任期已校验
     * 通过、自身仍为 Leader。
     * </p>
     *
     * @param fromPeer 响应来源 peer
     * @param resp     AppendEntries 响应
     * @return true=本次响应后可能推进了 commitIndex（调用方据此决定是否触发 apply）
     */
    public boolean onAppendEntriesResponse(String fromPeer, AppendEntriesResponse resp, boolean refreshLease) {
        if (!nextIndex.containsKey(fromPeer)) {
            // 非已知 peer（可能已 clearOnLoseLeadership），忽略
            return false;
        }

        if (resp.isSuccess()) {
            long prevMatch = matchIndex.getOrDefault(fromPeer, 0L);
            if (resp.getMatchIndex() > prevMatch) {
                matchIndex.put(fromPeer, resp.getMatchIndex());
                nextIndex.put(fromPeer, resp.getMatchIndex() + 1);
            }
            boolean advanced = maybeAdvanceCommitIndex();
            if (advanced) {
                applyCommittedEntries();
            }
            if (refreshLease && leaseRefresher != null) {
                leaseRefresher.run();
            }
            return advanced;
        } else {
            // success=false：回退 nextIndex 并重发（Raft §5.3 日志一致性修复）
            long ni = getNextIndex(fromPeer);
            if (ni > 1) {
                // 若 Follower 上报了 conflict matchIndex（resp.matchIndex），可快速回退到它；
                // 否则保守回退一格。此处保守回退一格（兼容 Follower 只回退一格的实现）。
                long fallback = ni - 1;
                // 若 resp.matchIndex 有效且 < ni-1，回退到 resp.matchIndex+1（加速）
                if (resp.getMatchIndex() > 0 && resp.getMatchIndex() + 1 < fallback) {
                    fallback = resp.getMatchIndex() + 1;
                }
                nextIndex.put(fromPeer, Math.max(fallback, 1));
                logger.debug("AppendEntries 失败，回退 nextIndex: peer={} → {}", fromPeer, nextIndex.get(fromPeer));
            }
            // 立即重发（带回退后的 nextIndex）
            resendTo(fromPeer);
            return false;
        }
    }

    /** 单独向某 peer 重发 AppendEntries（回退后补发）。 */
    private void resendTo(String peer) {
        long term = state.currentTerm;
        long leaderCommit = state.commitIndex;
        long lastLogIndex = state.getLastLogIndex();
        long ni = getNextIndex(peer);
        List<LogEntry> toSend = collectEntriesFrom(ni, lastLogIndex);
        long prevLogIndex = ni - 1;
        long prevLogTerm = state.getLogTerm(prevLogIndex);
        AppendEntriesMessage msg = new AppendEntriesMessage(
                term, nodeId, prevLogIndex, prevLogTerm, toSend, leaderCommit);
        MeshFrame frame = new MeshFrame(nodeId, MessageType.APPEND_ENTRIES.getCode(), msg.encode());
        try {
            busClient.send(peer, frame);
        } catch (Exception e) {
            logger.warn("重发 AppendEntries 到 {} 失败", peer, e);
        }
    }

    // ==================== commitIndex 推进（Raft §5.4.2）====================

    /**
     * 尝试推进 commitIndex（Raft §5.4.2）。
     * <p>
     * 找最大的 N，满足：{@code N > commitIndex && majority(matchIndex[peer] >= N) && log[N].term == currentTerm}。
     * 置 commitIndex = N。<b>term == currentTerm 约束</b>防止旧任期日志被新 Leader 直接提交（Fig 8）。
     * </p>
     * <p>
     * 算法：把所有 matchIndex（含自己=lastLogIndex）排序，取第 majority 大的值作为候选 commitIndex。
     * </p>
     *
     * @return true=commitIndex 被推进
     */
    public boolean maybeAdvanceCommitIndex() {
        // 收集所有 matchIndex（含自己：Leader 自己拥有完整日志，match=lastLogIndex）
        List<Long> indexes = new ArrayList<>();
        indexes.add(state.getLastLogIndex()); // 自己
        for (Long m : matchIndex.values()) {
            if (m != null && m > 0) {
                indexes.add(m);
            }
        }

        // 降序排序，取第 majority 大（majority 含自己）
        indexes.sort((a, b) -> Long.compare(b, a));
        int majority = config.majority();
        if (indexes.size() < majority) {
            return false;
        }
        // 第 majority-1 个（0-based）即「多数派都 >= 该值」的最大值
        long candidate = indexes.get(majority - 1);
        if (candidate <= state.commitIndex) {
            return false;
        }
        // Raft §5.4.2：只能提交 currentTerm 的日志（防 Fig 8）
        long termAtCandidate = state.getLogTerm(candidate);
        if (termAtCandidate != state.currentTerm) {
            // 候选条目非当前任期，不能直接提交；等待当前任期条目被复制后间接提交
            return false;
        }
        state.commitIndex = candidate;
        logger.debug("commitIndex 推进 → {} (term={})", candidate, state.currentTerm);
        return true;
    }

    // ==================== apply 已提交日志 ====================

    /**
     * apply 所有 {@code lastApplied < index <= commitIndex} 的日志条目到 raw store。
     * <p>
     * 逐条 apply（顺序保证，DESIGN §5.7 串行互斥），更新 lastApplied，并通知
     * pendingProposals（Leader 侧 complete 对应 future）。
     * </p>
     *
     * @return 本次 apply 的条目数
     */
    public int applyCommittedEntries() {
        int applied = 0;
        while (state.lastApplied < state.commitIndex) {
            long next = state.lastApplied + 1;
            LogEntry entry = state.getEntry(next);
            if (entry == null) {
                // 已被快照截断或缺失：跳过推进 lastApplied，避免死循环
                logger.warn("apply: 日志缺失 index={}, lastApplied={}, commitIndex={}（可能已快照截断）",
                        next, state.lastApplied, state.commitIndex);
                state.lastApplied = next;
                continue;
            }
            try {
                Object resp = applier.apply(entry);
                applied++;
                // 更新 lastApplied（apply 成功后才推进）
                state.lastApplied = next;
                // 通知 pendingProposals：Leader 侧 complete 对应 future（携带 apply 响应对象）；
                // Follower 侧无 future，回调内 no-op（响应对象丢弃，DESIGN §5.1 步骤5）。
                if (appliedNotifier != null) {
                    try {
                        appliedNotifier.accept(next, resp);
                    } catch (Exception e) {
                        logger.warn("apply 通知回调异常, index={}", next, e);
                    }
                }
            } catch (UnsupportedOperationException e) {
                // 事务暂不支持（阶段 9）：推进 lastApplied 避免 apply 循环阻塞，但不 complete future
                logger.warn("apply: 暂不支持的条目类型, index={}, 跳过", next);
                state.lastApplied = next;
            } catch (Exception e) {
                logger.error("apply 失败, index={}", next, e);
                // apply 失败不推进 lastApplied，下次 apply 会重试同一条（幂等设计需 store 支撑）
                // 但为避免持续阻塞 apply 循环，这里仍推进（apply 错误响应已由 LogApplier 返回）
                state.lastApplied = next;
            }
        }
        return applied;
    }

    // ==================== Follower 侧 apply（由 leaderCommit 推进）====================

    /**
     * Follower 侧 apply：当 leaderCommit 推进本地 commitIndex 后，apply 已提交条目。
     * <p>
     * Follower 的 apply 响应对象丢弃（仅推进 lastApplied，DESIGN §5.1 步骤5）。
     * 与 {@link #applyCommittedEntries()} 共用循环逻辑，区别是不通知 pendingProposals
     * （Follower 无客户端 future）。
     * </p>
     *
     * @return 本次 apply 的条目数
     */
    public int applyCommittedEntriesFollower() {
        return applyCommittedEntries();
    }
}
