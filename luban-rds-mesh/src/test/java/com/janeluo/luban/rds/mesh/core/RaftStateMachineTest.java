package com.janeluo.luban.rds.mesh.core;

import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RaftStateMachine} 单元测试（DESIGN.md §5.2 + §5.4.1）。
 * <p>
 * 覆盖：becomeFollower / becomeCandidate / becomeLeader 转换正确性、RequestVote 裁决
 * （任期裁决、日志 up-to-date、已投别人拒投、PreVote 不记 votedFor）、AppendEntries Follower 裁决
 * （任期校验、prevLog 一致性、追加截断、commitIndex 推进）。
 * </p>
 * <p>纯逻辑测试：无网络/定时器/mock，直接断言 MeshState 字段与裁决结果。</p>
 */
class RaftStateMachineTest {

    private static final String SELF = "nodeA";
    private static final String PEER_B = "nodeB";
    private static final String PEER_C = "nodeC";

    private RaftStateMachine sm;
    private MeshState state;

    @BeforeEach
    void setUp() {
        sm = new RaftStateMachine();
        state = new MeshState();
    }

    private static LogEntry entry(long term, long index) {
        return new LogEntry(term, index, ("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n")
                .getBytes(StandardCharsets.UTF_8), 0, null);
    }

    // ==================== becomeFollower ====================

    @Test
    void becomeFollower_updatesTermAndClearsVotedFor() {
        state.currentTerm = 5;
        state.votedFor = SELF;
        state.role = MeshRole.CANDIDATE;

        RaftStateMachine.Transition t = sm.becomeFollower(state, 7L, "nodeB");

        assertEquals(MeshRole.FOLLOWER, state.role);
        assertEquals(7L, state.currentTerm);
        assertNull(state.votedFor, "新任期应清空 votedFor");
        assertEquals("nodeB", state.leaderId);
        assertEquals(RaftStateMachine.Transition.Kind.TO_FOLLOWER, t.kind);
        assertEquals(7L, t.newTerm);
    }

    @Test
    void becomeFollower_smallerTerm_keepsTermButTransitionsRole() {
        // term 更小：不更新 currentTerm，但仍转 follower
        state.currentTerm = 10;
        state.role = MeshRole.CANDIDATE;

        RaftStateMachine.Transition t = sm.becomeFollower(state, 8L, null);

        assertEquals(MeshRole.FOLLOWER, state.role);
        assertEquals(10L, state.currentTerm, "更小 term 不应回退 currentTerm");
        assertEquals(RaftStateMachine.Transition.Kind.TO_FOLLOWER, t.kind, "CANDIDATE→FOLLOWER 仍报 TO_FOLLOWER");
    }

    @Test
    void becomeFollower_sameTerm_sameRole_isNone() {
        state.currentTerm = 5;
        state.role = MeshRole.FOLLOWER;

        RaftStateMachine.Transition t = sm.becomeFollower(state, 5L, "nodeB");

        assertEquals(RaftStateMachine.Transition.Kind.NONE, t.kind,
                "同 term 同 role 应为 NONE");
        assertEquals("nodeB", state.leaderId);
    }

    // ==================== becomeCandidate ====================

    @Test
    void becomeCandidate_incrementsTermAndVotesSelf() {
        state.currentTerm = 5;
        state.votedFor = "nodeB";
        state.appendEntry(entry(5L, 1L));
        state.appendEntry(entry(5L, 2L));

        RaftStateMachine.Transition t = sm.becomeCandidate(state, SELF);

        assertEquals(MeshRole.CANDIDATE, state.role);
        assertEquals(6L, state.currentTerm, "term 应 +1");
        assertEquals(SELF, state.votedFor, "应投自己");
        assertNull(state.leaderId);
        assertEquals(RaftStateMachine.Transition.Kind.TO_CANDIDATE, t.kind);
        assertEquals(2L, t.lastLogIndex, "lastLogIndex 应反映 log 末尾");
        assertEquals(5L, t.lastLogTerm, "lastLogTerm 应反映 log 末尾 term");
    }

    @Test
    void becomeCandidate_emptyLog_reportsZeroIndex() {
        // 空日志：lastLogIndex=0（lastIncludedIndex），lastLogTerm=0
        RaftStateMachine.Transition t = sm.becomeCandidate(state, SELF);

        assertEquals(0L, t.lastLogIndex);
        assertEquals(0L, t.lastLogTerm);
    }

    // ==================== becomeLeader ====================

    @Test
    void becomeLeader_setsNextIndexAndMatchIndex() {
        state.currentTerm = 6;
        state.appendEntry(entry(5L, 1L));
        state.appendEntry(entry(6L, 2L));
        state.appendEntry(entry(6L, 3L));

        RaftStateMachine.Transition t = sm.becomeLeader(state, SELF,
                Arrays.asList(PEER_B, PEER_C, SELF));

        assertEquals(MeshRole.LEADER, state.role);
        assertEquals(SELF, state.leaderId);
        assertEquals(RaftStateMachine.Transition.Kind.TO_LEADER, t.kind);
        // nextIndex[peer] = lastLogIndex+1 = 4
        assertEquals(4L, t.nextIndex.get(PEER_B));
        assertEquals(4L, t.nextIndex.get(PEER_C));
        assertFalse(t.nextIndex.containsKey(SELF), "nextIndex 不应含自身");
        // matchIndex[peer] = 0
        assertEquals(0L, t.matchIndex.get(PEER_B));
        assertEquals(0L, t.matchIndex.get(PEER_C));
    }

    @Test
    void becomeLeader_emptyLog_nextIndexIsOne() {
        RaftStateMachine.Transition t = sm.becomeLeader(state, SELF,
                Collections.singletonList(PEER_B));

        assertEquals(1L, t.nextIndex.get(PEER_B), "空日志 nextIndex 应为 1");
    }

    // ==================== RequestVote 裁决 ====================

    @Test
    void decideVote_grantsForUpToDateCandidate_whenNotVoted() {
        state.currentTerm = 5;
        state.votedFor = null;
        state.appendEntry(entry(5L, 1L)); // lastLog 1/5

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 1L, 5L, false);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertTrue(d.response.isVoteGranted());
        assertEquals(5L, d.response.getTerm());
        assertEquals(PEER_B, state.votedFor, "正式投票应记录 votedFor");
        assertTrue(d.resetElectionTimer);
    }

    @Test
    void decideVote_rejectsStaleTerm() {
        state.currentTerm = 5;
        RequestVoteMessage msg = new RequestVoteMessage(3L, PEER_B, 100L, 100L, false);

        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertFalse(d.response.isVoteGranted());
        assertEquals(5L, d.response.getTerm());
        assertNull(state.votedFor, "拒绝投票不应记录 votedFor");
        assertFalse(d.resetElectionTimer, "stale term 不应重置 timer");
    }

    @Test
    void decideVote_rejectsCandidateWithOlderLog() {
        // candidate 日志落后：自己 2/5，candidate 1/5
        state.currentTerm = 5;
        state.votedFor = null;
        state.appendEntry(entry(5L, 1L));
        state.appendEntry(entry(5L, 2L));

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 1L, 5L, false);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertFalse(d.response.isVoteGranted(), "candidate 日志落后应拒投");
        assertNull(state.votedFor, "拒投不应记录 votedFor");
    }

    @Test
    void decideVote_rejectsWhenAlreadyVotedForOther() {
        state.currentTerm = 5;
        state.votedFor = PEER_C; // 已投 C

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 0L, 0L, false);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertFalse(d.response.isVoteGranted(), "已投别人应拒投");
        assertEquals(PEER_C, state.votedFor, "votedFor 不应被改");
    }

    @Test
    void decideVote_grantsWhenAlreadyVotedForSameCandidate() {
        // 幂等：同 candidate 重复请求仍投
        state.currentTerm = 5;
        state.votedFor = PEER_B;

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 0L, 0L, false);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertTrue(d.response.isVoteGranted(), "同 candidate 重复请求应仍投");
        assertEquals(PEER_B, state.votedFor);
    }

    @Test
    void decideVote_higherTerm_downgradesAndGrants() {
        state.currentTerm = 5;
        state.votedFor = PEER_C; // 旧任期的投票
        state.role = MeshRole.CANDIDATE;

        RequestVoteMessage msg = new RequestVoteMessage(7L, PEER_B, 0L, 0L, false);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertEquals(7L, state.currentTerm, "更高 term 应更新 currentTerm");
        assertEquals(MeshRole.FOLLOWER, state.role, "应降级 follower");
        assertEquals(PEER_B, state.votedFor, "新任期清空后应投给 candidate");
        assertTrue(d.response.isVoteGranted());
        assertEquals(RaftStateMachine.Transition.Kind.TO_FOLLOWER, d.transition.kind);
    }

    @Test
    void decideVote_candidateWithHigherLogTerm_grantedEvenIfIndexSmaller() {
        // candidate term 更大 → up-to-date（Raft §5.4.1 先比 term）
        state.currentTerm = 5;
        state.votedFor = null;
        state.appendEntry(entry(3L, 100L)); // 自己 lastLogTerm=3
        state.appendEntry(entry(3L, 101L));

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 1L, 4L, false); // candidate term=4
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertTrue(d.response.isVoteGranted(), "candidate term 更大即使 index 更小也应投");
    }

    // ==================== PreVote ====================

    @Test
    void preVote_doesNotRecordVotedFor() {
        state.currentTerm = 5;
        state.votedFor = null;

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 0L, 0L, true);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertTrue(d.response.isVoteGranted(), "PreVote 合格应返回 granted");
        assertNull(state.votedFor, "PreVote 不应记录 votedFor");
    }

    @Test
    void preVote_doesNotIncrementTerm() {
        state.currentTerm = 5;

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 0L, 0L, true);
        sm.decideRequestVote(state, msg);

        assertEquals(5L, state.currentTerm, "PreVote 不应改变 currentTerm");
    }

    @Test
    void preVote_stillChecksLogUpToDate() {
        state.currentTerm = 5;
        state.votedFor = null;
        state.appendEntry(entry(5L, 5L)); // 自己日志更新

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 1L, 3L, true);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertFalse(d.response.isVoteGranted(), "PreVote 也要校验日志，落后应拒");
        assertNull(state.votedFor);
    }

    @Test
    void preVote_alreadyVotedForOther_doesNotBlockPreVote() {
        // PreVote 不看 votedFor（因为不记 votedFor），应仍能 granted（如果日志合格）
        state.currentTerm = 5;
        state.votedFor = PEER_C;

        RequestVoteMessage msg = new RequestVoteMessage(5L, PEER_B, 0L, 0L, true);
        RaftStateMachine.VoteDecision d = sm.decideRequestVote(state, msg);

        assertTrue(d.response.isVoteGranted(), "PreVote 不受 votedFor 影响");
        assertEquals(PEER_C, state.votedFor, "PreVote 不应改变已记录的 votedFor");
    }

    // ==================== AppendEntries Follower 裁决 ====================

    @Test
    void appendEntries_staleTerm_rejected() {
        state.currentTerm = 5;
        AppendEntriesMessage msg = new AppendEntriesMessage(
                3L, PEER_B, 0L, 0L, Collections.emptyList(), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertFalse(d.response.isSuccess());
        assertEquals(5L, d.response.getTerm());
        assertFalse(d.resetElectionTimer, "stale term 不应重置 timer");
    }

    @Test
    void appendEntries_higherTerm_downgradesFollower() {
        state.currentTerm = 5;
        state.role = MeshRole.CANDIDATE;

        AppendEntriesMessage msg = new AppendEntriesMessage(
                7L, PEER_B, 0L, 0L, Collections.emptyList(), 0L);
        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertEquals(7L, state.currentTerm);
        assertEquals(MeshRole.FOLLOWER, state.role);
        assertEquals(RaftStateMachine.Transition.Kind.TO_FOLLOWER, d.transition.kind);
        assertTrue(d.response.isSuccess());
        assertTrue(d.resetElectionTimer);
    }

    @Test
    void appendEntries_candidateSeesLeader_downgradesSameTerm() {
        // 同任期下 candidate 收到 AppendEntries → 别人当选，转 follower
        state.currentTerm = 5;
        state.role = MeshRole.CANDIDATE;

        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 0L, 0L, Collections.emptyList(), 0L);
        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertEquals(MeshRole.FOLLOWER, state.role);
        assertEquals(RaftStateMachine.Transition.Kind.TO_FOLLOWER, d.transition.kind);
        assertEquals(PEER_B, state.leaderId);
    }

    @Test
    void appendEntries_prevLogMismatch_rejected() {
        state.currentTerm = 5;
        state.appendEntry(entry(3L, 1L)); // 本地 index1 term3

        // leader 声称 prevLogIndex=1 prevLogTerm=5（不匹配）
        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 1L, 5L, Collections.emptyList(), 0L);
        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertFalse(d.response.isSuccess(), "prevLog 不一致应拒");
    }

    @Test
    void appendEntries_appendsNewEntries_success() {
        state.currentTerm = 5;
        // prevLogIndex=0 跳过校验
        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 0L, 0L,
                Arrays.asList(entry(5L, 1L), entry(5L, 2L)), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertTrue(d.response.isSuccess());
        assertEquals(2L, d.response.getMatchIndex());
        assertEquals(2L, state.getLastLogIndex());
    }

    @Test
    void appendEntries_conflictingEntry_truncatesAndAppends() {
        state.currentTerm = 5;
        state.appendEntry(entry(3L, 1L));
        state.appendEntry(entry(4L, 2L)); // 将被截断（term4 与新 term5 冲突）
        state.appendEntry(entry(4L, 3L));

        // leader 发 index=2 term=5（与本地 index=2 term=4 冲突）
        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 1L, 3L,
                Arrays.asList(entry(5L, 2L), entry(5L, 3L)), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertTrue(d.response.isSuccess());
        assertEquals(3L, state.getLastLogIndex());
        assertEquals(5L, state.getLogTerm(2L), "index2 应被覆盖为 term5");
        assertEquals(5L, state.getLogTerm(3L), "index3 应为 term5");
    }

    @Test
    void appendEntries_idempotent_sameTermEntryNotReappended() {
        // 已存在同 index 同 term → 幂等跳过，不重复追加
        state.currentTerm = 5;
        state.appendEntry(entry(5L, 1L));

        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 0L, 0L,
                Collections.singletonList(entry(5L, 1L)), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertTrue(d.response.isSuccess());
        assertEquals(1L, state.getLastLogIndex(), "幂等场景不应追加重复条目");
    }

    @Test
    void appendEntries_advancesCommitIndex() {
        state.currentTerm = 5;
        state.appendEntry(entry(5L, 1L));
        state.appendEntry(entry(5L, 2L));
        state.appendEntry(entry(5L, 3L));
        assertEquals(0L, state.commitIndex);

        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 2L, 5L, Collections.emptyList(), 2L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertTrue(d.response.isSuccess());
        assertEquals(2L, state.commitIndex, "commitIndex 应推进到 leaderCommit（≤lastLog）");
    }

    @Test
    void appendEntries_commitCappedAtLastLog() {
        state.currentTerm = 5;
        state.appendEntry(entry(5L, 1L)); // lastLog=1

        // leaderCommit=10 远超本地 lastLog=1 → commitIndex 取 min=1
        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 0L, 0L, Collections.emptyList(), 10L);

        sm.decideAppendEntries(state, msg, null);

        assertEquals(1L, state.commitIndex, "commitIndex 不应超过 lastLogIndex");
    }

    @Test
    void appendEntries_persistHookInvoked() {
        state.currentTerm = 5;
        boolean[] called = {false};
        Runnable hook = () -> called[0] = true;

        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 0L, 0L, Collections.singletonList(entry(5L, 1L)), 0L);

        sm.decideAppendEntries(state, msg, hook);

        assertTrue(called[0], "落盘 hook 应在追加后被调用");
    }

    @Test
    void appendEntries_heartbeat_emptyEntries_matchIndexIsPrevLogIndex() {
        state.currentTerm = 5;
        state.appendEntry(entry(5L, 1L));
        state.appendEntry(entry(5L, 2L));

        AppendEntriesMessage msg = new AppendEntriesMessage(
                5L, PEER_B, 2L, 5L, Collections.emptyList(), 2L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg, null);

        assertTrue(d.response.isSuccess());
        assertEquals(2L, d.response.getMatchIndex(), "心跳 matchIndex 应 = prevLogIndex");
    }
}
