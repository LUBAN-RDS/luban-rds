package com.janeluo.luban.rds.mesh.core;

import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Raft 角色转换与消息裁决的纯逻辑（DESIGN.md §5.2 + §3.1）。
 * <p>
 * 本类<strong>只做 {@link MeshState} 字段转换与 RPC 裁决计算</strong>，不直接持有网络客户端、定时器或线程池。
 * 所有"副作用"（发心跳、重启 ElectionTimer、启动 LeaseManager、发 RequestVote）通过返回的
 * {@link Transition} 对象交由 {@code MeshNode} 解释执行——这是本类的核心解耦设计（见类末「解耦设计说明」）。
 * </p>
 *
 * <h3>裁决规则（Raft 论文 §5.2/§5.4.1）</h3>
 * <ul>
 *   <li><b>RequestVote 裁决</b>：term &lt; currentTerm → 拒；term &gt; currentTerm → 先降级 follower；
 *       已投别人（votedFor 非 null 且 != candidate） → 拒；candidate 日志落后（lastLogTerm &lt; 自己 / 索引更小）
 *       → 拒；否则投票（持久化 votedFor）。</li>
 *   <li><b>AppendEntries Follower 裁决</b>：term &lt; currentTerm → 拒（success=false）；
 *       term &gt;= currentTerm 且 prevLog 不一致 → 拒（让 Leader 回退 nextIndex）；否则追加/截断、推进 commit、
 *       返回 success=true。</li>
 *   <li><b>任期裁决</b>：任何 RPC（含响应）term &gt; currentTerm → 立即降级 follower 并更新 currentTerm。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>
 * 本类无内部锁（MeshState 自身用读写锁保护 log 字段）；调用方（MeshNode）保证同一时刻只有一个线程
 * 在驱动状态机（典型用单线程化 dispatcher / actor 模式）。Raft 正确性要求状态变更串行化，
 * 故本类不做细粒度并发控制，依赖调用方串行调用。
 * </p>
 */
public class RaftStateMachine {

    /**
     * 角色转换结果。携带<strong>纯数据提示</strong>：转换发生 → 调用方据此执行网络/定时器副作用。
     * 调用方通过 {@link #kind} 决定动作，通过字段读取转换后的具体值。
     */
    public static final class Transition {
        public enum Kind {
            /** 无变化（例如 becomeFollower 时 term 未变） */
            NONE,
            /** 转为 FOLLOWER（term 可能更新） */
            TO_FOLLOWER,
            /** 转为 CANDIDATE（已自增 term、投自己） */
            TO_CANDIDATE,
            /** 转为 LEADER（已初始化 nextIndex/matchIndex） */
            TO_LEADER
        }

        public final Kind kind;
        /** 转换后的 currentTerm。 */
        public final long newTerm;
        /** 转换后的 role。 */
        public final MeshRole newRole;
        /** 转换后的 leaderId（可为 null）。 */
        public final String newLeaderId;
        /** Leader 转换时的 nextIndex（仅 TO_LEADER 有效）。 */
        public final Map<String, Long> nextIndex;
        /** Leader 转换时的 matchIndex（仅 TO_LEADER 有效）。 */
        public final Map<String, Long> matchIndex;
        /** becomeCandidate 用于发 RequestVote 的字段（lastLogIndex/Term）。 */
        public final long lastLogIndex;
        public final long lastLogTerm;

        private Transition(Kind kind, long newTerm, MeshRole newRole, String newLeaderId,
                           Map<String, Long> nextIndex, Map<String, Long> matchIndex,
                           long lastLogIndex, long lastLogTerm) {
            this.kind = kind;
            this.newTerm = newTerm;
            this.newRole = newRole;
            this.newLeaderId = newLeaderId;
            this.nextIndex = nextIndex;
            this.matchIndex = matchIndex;
            this.lastLogIndex = lastLogIndex;
            this.lastLogTerm = lastLogTerm;
        }

        /** 构造 NONE（无变化）。 */
        public static Transition none(MeshRole role, long term) {
            return new Transition(Kind.NONE, term, role, null,
                    Collections.emptyMap(), Collections.emptyMap(), 0L, 0L);
        }

        @Override
        public String toString() {
            return "Transition{" + kind + ", term=" + newTerm + ", role=" + newRole
                    + ", leader=" + newLeaderId + (nextIndex.isEmpty() ? "" : ", nextIndex=" + nextIndex) + '}';
        }
    }

    /**
     * 转为 FOLLOWER（DESIGN §5.2）。
     * <p>
     * 若 newTerm &gt; currentTerm，则更新 currentTerm 并清空 votedFor（新任期未投票）。
     * 清 leaderId（后续由 AppendEntries 写入）。若原本是 LEADER/CANDIDATE 则返回 TO_FOLLOWER
     * 提示调用方停心跳、失效 LeaseManager、停 VoteCollector、重启 ElectionTimer。
     * </p>
     *
     * @param state    节点状态
     * @param newTerm  新任期（应 >= currentTerm；若更小则忽略 term 更新但仍转 follower）
     * @param leaderId 新 Leader nodeId（可 null；从 AppendEntries 带来时填 leaderId）
     * @return 转换结果
     */
    public Transition becomeFollower(MeshState state, long newTerm, String leaderId) {
        MeshRole oldRole = state.role;
        boolean termUpdated = false;
        if (newTerm > state.currentTerm) {
            state.currentTerm = newTerm;
            state.votedFor = null;   // 新任期清空投票
            termUpdated = true;
        }
        state.role = MeshRole.FOLLOWER;
        if (leaderId != null) {
            state.leaderId = leaderId;
        } else if (oldRole == MeshRole.LEADER) {
            // 失去 Leader 身份时清掉旧 leaderId（自己不再是 Leader）
            state.leaderId = null;
        }
        // CANDIDATE → FOLLOWER 保留可能已知的 leaderId（无），统一置 null 更稳妥
        if (oldRole == MeshRole.CANDIDATE) {
            state.leaderId = leaderId;
        }

        Transition.Kind kind = (oldRole != MeshRole.FOLLOWER || termUpdated)
                ? Transition.Kind.TO_FOLLOWER : Transition.Kind.NONE;
        return new Transition(kind, state.currentTerm, state.role, state.leaderId,
                Collections.emptyMap(), Collections.emptyMap(), 0L, 0L);
    }

    /**
     * 转为 CANDIDATE（DESIGN §5.2）。
     * <p>
     * currentTerm++，votedFor=self（投自己），leaderId=null，role=CANDIDATE。
     * 返回 TO_CANDIDATE + lastLogIndex/lastLogTerm，供调用方构造 RequestVote。
     * </p>
     * <p><b>注意</b>：PreVote 流程下，本方法仅在 PreVote 获多数派后才调用（正式选举才自增 term）。
     * PreVote 探测本身<strong>不调用本方法</strong>，故不自增 term。</p>
     *
     * @param state      节点状态
     * @param selfNodeId 本节点 nodeId
     * @return 转换结果（含 lastLogIndex/Term）
     */
    public Transition becomeCandidate(MeshState state, String selfNodeId) {
        state.role = MeshRole.CANDIDATE;
        state.currentTerm = state.currentTerm + 1;
        state.votedFor = selfNodeId;
        state.leaderId = null;
        long lastLogIndex = state.getLastLogIndex();
        long lastLogTerm = state.getLastLogTerm();
        return new Transition(Transition.Kind.TO_CANDIDATE, state.currentTerm, state.role, null,
                Collections.emptyMap(), Collections.emptyMap(), lastLogIndex, lastLogTerm);
    }

    /**
     * 转为 LEADER（DESIGN §5.2）。
     * <p>
     * role=LEADER，leaderId=self，初始化 nextIndex[peer]=lastLogIndex+1、matchIndex[peer]=0。
     * 返回 TO_LEADER + nextIndex/matchIndex，供调用方：启动心跳定时器、启动 LeaseManager、
     * 立即广播一轮空 AppendEntries 建立权威 + 首轮续租。
     * </p>
     *
     * @param state      节点状态
     * @param selfNodeId 本节点 nodeId
     * @param peers      其他节点 id 集合（不含自己）
     * @return 转换结果（含 nextIndex/matchIndex）
     */
    public Transition becomeLeader(MeshState state, String selfNodeId, Collection<String> peers) {
        state.role = MeshRole.LEADER;
        state.leaderId = selfNodeId;
        long lastLogIndex = state.getLastLogIndex();
        Map<String, Long> nextIndex = new HashMap<>();
        Map<String, Long> matchIndex = new HashMap<>();
        if (peers != null) {
            for (String peer : peers) {
                if (peer.equals(selfNodeId)) {
                    continue;
                }
                nextIndex.put(peer, lastLogIndex + 1);
                matchIndex.put(peer, 0L);
            }
        }
        return new Transition(Transition.Kind.TO_LEADER, state.currentTerm, state.role, selfNodeId,
                nextIndex, matchIndex, lastLogIndex, state.getLastLogTerm());
    }

    // ==================== RPC 裁决 ====================

    /**
     * RequestVote 裁决（Follower/Candidate 收到投票请求）。
     * <p>
     * 实现遵循 Raft §5.4.1（投票者完整性）：
     * <ol>
     *   <li>term &lt; currentTerm → 拒（term=currentTerm）</li>
     *   <li>term &gt; currentTerm → 降级 follower（更新 currentTerm、清 votedFor）后再裁决</li>
     *   <li>candidate 日志落后（lastLogTerm &lt; 自己 lastLogTerm，或 term 相等但 index 更小）→ 拒</li>
     *   <li>已投给别人（votedFor 非 null 且 != candidate）→ 拒</li>
     *   <li>否则同意投票：<strong>正式投票</strong>（preVote=false）时设 votedFor=candidate（持久化）；
     *       <strong>PreVote</strong>（preVote=true）时不记 votedFor，仅返回 granted</li>
     * </ol>
     * 收到合法 RequestVote（term &gt;= currentTerm）应重置 ElectionTimer——由调用方根据返回值判定。
     * </p>
     *
     * @param state 节点状态
     * @param msg   投票请求
     * @return [response, transition]：response 含 term/voteGranted；transition 描述是否降级 follower
     */
    public VoteDecision decideRequestVote(MeshState state, RequestVoteMessage msg) {
        long currentTerm = state.currentTerm;
        Transition t = Transition.none(state.role, currentTerm);

        // (1) term < currentTerm → 直接拒
        if (msg.getTerm() < currentTerm) {
            return new VoteDecision(
                    new RequestVoteResponse(currentTerm, false), t, false);
        }

        // (2) term > currentTerm → 降级 follower
        if (msg.getTerm() > currentTerm) {
            t = becomeFollower(state, msg.getTerm(), null);
            currentTerm = state.currentTerm;
        }

        // (3) candidate 日志是否 >= 自己日志（Raft §5.4.1 up-to-date 判定）
        boolean candidateUpToDate = isCandidateUpToDate(state, msg.getLastLogIndex(), msg.getLastLogTerm());

        // (4) 是否已投给别人（PreVote 不检查 votedFor——它只探测"能否赢"，不占用本任期投票权）
        boolean canVote;
        if (msg.isPreVote()) {
            // PreVote：忽略 votedFor，本任期正式投票权不受影响
            canVote = true;
        } else if (state.votedFor == null || state.votedFor.equals(msg.getCandidateId())) {
            canVote = true;
        } else {
            canVote = false;
        }

        boolean grant = candidateUpToDate && canVote;
        if (grant && !msg.isPreVote()) {
            // 正式投票才记录 votedFor（PreVote 不记）
            state.votedFor = msg.getCandidateId();
        }

        // 合法请求（term >= currentTerm）且候选者日志合格 → 重置 election timer
        boolean resetElectionTimer = (msg.getTerm() >= currentTerm) && candidateUpToDate;

        return new VoteDecision(
                new RequestVoteResponse(currentTerm, grant), t, resetElectionTimer);
    }

    /**
     * Raft §5.4.1「candidate 至少 up-to-date」判定：
     * candidate 的 (lastLogTerm, lastLogIndex) 在字典序上 >= 自己的 (lastLogTerm, lastLogIndex)。
     */
    private boolean isCandidateUpToDate(MeshState state, long candidateLastLogIndex, long candidateLastLogTerm) {
        long myLastLogTerm = state.getLastLogTerm();
        long myLastLogIndex = state.getLastLogIndex();
        if (candidateLastLogTerm != myLastLogTerm) {
            return candidateLastLogTerm > myLastLogTerm;
        }
        return candidateLastLogIndex >= myLastLogIndex;
    }

    /**
     * AppendEntries Follower 侧裁决与日志一致性处理（DESIGN §5.1 步骤3 + §5.2）。
     * <p>
     * 流程：
     * <ol>
     *   <li>term &lt; currentTerm → 拒（success=false, matchIndex=当前 lastLogIndex）</li>
     *   <li>term &gt; currentTerm → 降级 follower；term == currentTerm 且自己是 candidate → 也转 follower</li>
     *   <li>记录 leaderId、重置 election timer（合法心跳）</li>
     *   <li>prevLogIndex &gt; 0 时校验 prevLogIndex 处任期 == prevLogTerm；不一致 → 拒（让 Leader 回退）</li>
     *   <li>对 entries：若本地已有同 index 但 term 不同 → 截断该 index 之后；追加新条目</li>
     *   <li>推进 commitIndex = min(leaderCommit, lastLogIndex)</li>
     *   <li>调 persistHook（落盘占位，阶段 11 实现 fsync）——由调用方在持久化完成后才组装 success=true 响应</li>
     *   <li>返回 success=true, matchIndex=最后一条 entry 的 index（或 prevLogIndex）</li>
     * </ol>
     * </p>
     * <p>
     * <b>持久化时序</b>：本方法在追加日志后调用 {@code persistHook.run()}（阶段 3 为空实现），
     * 调用方应确保落盘完成后才发响应（DESIGN 决策 18）。阶段 3 persistHook 占位为 no-op。
     * </p>
     *
     * @param state         节点状态
     * @param msg           AppendEntries 请求
     * @param persistHook   落盘钩子（追加后、返回 success 前调用；阶段 3 可传 null/空 Runnable）
     * @return [response, transition]
     */
    public AppendDecision decideAppendEntries(MeshState state, AppendEntriesMessage msg, Runnable persistHook) {
        long currentTerm = state.currentTerm;

        // (1) term < currentTerm → 拒
        if (msg.getTerm() < currentTerm) {
            AppendEntriesResponse resp = new AppendEntriesResponse(currentTerm, false, state.getLastLogIndex());
            return new AppendDecision(resp, Transition.none(state.role, currentTerm), false);
        }

        // (2) term > currentTerm → 降级；term == 但自己是 candidate → 也转 follower（认 Leader）
        Transition t = Transition.none(state.role, currentTerm);
        if (msg.getTerm() > currentTerm) {
            t = becomeFollower(state, msg.getTerm(), msg.getLeaderId());
        } else if (state.role == MeshRole.CANDIDATE) {
            // 同任期下收到合法 AppendEntries，说明别人已当选 Leader
            t = becomeFollower(state, currentTerm, msg.getLeaderId());
        } else {
            // 同任期 follower，仅更新 leaderId（若不同）
            state.role = MeshRole.FOLLOWER;
            state.leaderId = msg.getLeaderId();
        }

        // (3) prevLogIndex 一致性校验
        long prevLogIndex = msg.getPrevLogIndex();
        long prevLogTerm = msg.getPrevLogTerm();
        if (prevLogIndex > 0) {
            long localTermAtPrev = state.getLogTerm(prevLogIndex);
            if (localTermAtPrev != prevLogTerm) {
                // 不一致 → 拒，matchIndex 上报当前 lastLogIndex（Leader 会据此回退 nextIndex）
                AppendEntriesResponse resp = new AppendEntriesResponse(state.currentTerm, false,
                        Math.max(state.getLastLogIndex(), prevLogIndex - 1));
                return new AppendDecision(resp, t, true);
            }
        }

        // (4)(5) 追加/截断 entries
        long lastNewIndex = prevLogIndex;
        for (LogEntry entry : msg.getEntries()) {
            long idx = entry.getIndex();
            long localTerm = state.getLogTerm(idx);
            if (localTerm >= 0 && localTerm != entry.getTerm()) {
                // 冲突：截断该 index 之后
                state.truncateAfter(idx - 1);
                state.appendEntry(entry);
            } else if (localTerm >= 0 && localTerm == entry.getTerm()) {
                // 已存在且 term 相同：幂等跳过（不重复追加）
            } else {
                // localTerm == -1：本地无此条目，直接追加
                state.appendEntry(entry);
            }
            lastNewIndex = idx;
        }

        // (6) 落盘（阶段 3 占位）
        if (persistHook != null) {
            try {
                persistHook.run();
            } catch (Exception e) {
                // 落盘失败 → 视为不接受（success=false）。阶段 3 persistHook 为 no-op 不会到这。
                AppendEntriesResponse resp = new AppendEntriesResponse(state.currentTerm, false, prevLogIndex);
                return new AppendDecision(resp, t, true);
            }
        }

        // (7) 推进 commitIndex
        long leaderCommit = msg.getLeaderCommit();
        if (leaderCommit > state.commitIndex) {
            state.commitIndex = Math.min(leaderCommit, state.getLastLogIndex());
        }

        // (8) 返回 success，matchIndex = 最后一条 entry 的 index（entries 为空时 = prevLogIndex）
        long matchIdx = msg.getEntries().isEmpty() ? prevLogIndex : lastNewIndex;
        AppendEntriesResponse resp = new AppendEntriesResponse(state.currentTerm, true, matchIdx);
        return new AppendDecision(resp, t, true);
    }

    // ==================== 裁决结果容器 ====================

    /** RequestVote 裁决结果。 */
    public static final class VoteDecision {
        public final RequestVoteResponse response;
        public final Transition transition;
        /** 是否应重置 ElectionTimer（合法且候选者日志合格）。 */
        public final boolean resetElectionTimer;

        public VoteDecision(RequestVoteResponse response, Transition transition, boolean resetElectionTimer) {
            this.response = response;
            this.transition = transition;
            this.resetElectionTimer = resetElectionTimer;
        }
    }

    /** AppendEntries 裁决结果。 */
    public static final class AppendDecision {
        public final AppendEntriesResponse response;
        public final Transition transition;
        /** 是否应重置 ElectionTimer（收到合法 Leader 心跳）。 */
        public final boolean resetElectionTimer;

        public AppendDecision(AppendEntriesResponse response, Transition transition, boolean resetElectionTimer) {
            this.response = response;
            this.transition = transition;
            this.resetElectionTimer = resetElectionTimer;
        }
    }

    /*
     * ==================== 解耦设计说明 ====================
     *
     * 本类采用「纯状态字段转换 + Transition 数据提示」的解耦模式：
     *
     *  - RaftStateMachine 只读写 MeshState（term/role/votedFor/leaderId/log/commitIndex）与
     *    计算 RPC 响应，不调用任何网络、定时器、线程池 API。这使得状态机逻辑可在单线程里用
     *    纯单元测试覆盖（无 mock、无时序）。
     *
     *  - 每个转换方法返回 Transition，描述「发生了什么」（TO_FOLLOWER/TO_CANDIDATE/TO_LEADER/NONE）
     *    以及「转换后的关键字段」（newTerm、newRole、nextIndex/matchIndex、lastLogIndex/Term）。
     *
     *  - MeshNode（调用方）拿到 Transition 后，在自己的线程里执行副作用：
     *      * TO_FOLLOWER → 停心跳定时器、LeaseManager.invalidate()、cancel VoteCollector、
     *                      若 !resetElectionTimer 则可能已是 stop 状态；通常还 restart ElectionTimer。
     *      * TO_CANDIDATE → 启动 PreVote/RequestVote（VoteCollector）、reset ElectionTimer。
     *      * TO_LEADER   → 启动心跳定时器、LeaseManager 首次 refresh、广播空 AppendEntries。
     *
     *  优势：
     *   1. 状态机可独立测试（PreVote 不自增 term、日志裁决、任期裁决等纯逻辑）；
     *   2. MeshNode 集中管理所有并发/IO，避免状态机里散落 send/schedule 调用；
     *   3. Transition 是数据（可日志、可断言），便于排查与回归测试；
     *   4. 未来若改用 actor 模型或单线程 dispatcher，状态机零改动。
     *
     * 不选「回调接口注入」方案的原因：会让 RaftStateMachine 间接依赖网络/定时器 API，
     * 测试时仍需 mock，且副作用顺序难以断言。Transition 数据返回更清晰。
     */
}
