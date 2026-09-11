package com.janeluo.luban.rds.mesh.core;

import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q1（2026-09-11 mesh 审计 P2）：冲突截断 commitIndex 守卫——
 * 冲突点 ≤ commitIndex（上游不变量破坏）时拒绝截断，已提交条目不得被静默丢弃。
 */
class TruncateGuardTest {

    private static final String A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String B = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private RaftStateMachine sm;
    private MeshState state;

    private static byte[] setFrame(String k, String v) {
        return ("*3\r\n$3\r\nSET\r\n$" + k + "\r\n$" + v + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @BeforeEach
    void setUp() {
        sm = new RaftStateMachine();
        state = new MeshState();
        state.currentTerm = 1;
        state.role = MeshRole.FOLLOWER;
        // 三条已提交条目
        for (long i = 1; i <= 3; i++) {
            state.appendEntry(new LogEntry(1L, i, setFrame("k" + i, "v" + i), 0, null));
        }
        state.commitIndex = 3;
        state.lastApplied = 3;
    }

    @Test
    void conflictAtOrBelowCommitIndexIsRefused() {
        // idx=2（≤ commitIndex=3）的冲突条目（term 不同）
        LogEntry conflicting = new LogEntry(2L, 2, setFrame("evil", "x"), 0, null);
        AppendEntriesMessage msg = new AppendEntriesMessage(
                1L, B, 1L, 1L, Collections.singletonList(conflicting), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg);

        assertFalse(d.response.isSuccess(), "不变量破坏应响应失败");
        assertEquals(3L, state.getLastLogIndex(), "本地 log 不得被截断");
        assertEquals(3L, state.commitIndex, "commitIndex 不得回退");
        assertEquals(1L, state.getLogTerm(2), "index 2 的已提交条目必须原样保留");
    }

    @Test
    void conflictAboveCommitIndexStillTruncates() {
        // idx=4（> commitIndex=3）的冲突条目：正常截断追加路径不受影响
        state.appendEntry(new LogEntry(1L, 4, setFrame("k4", "v4"), 0, null));
        LogEntry conflicting = new LogEntry(2L, 4, setFrame("k4new", "new"), 0, null);
        AppendEntriesMessage msg = new AppendEntriesMessage(
                2L, B, 3L, 1L, Collections.singletonList(conflicting), 0L);

        RaftStateMachine.AppendDecision d = sm.decideAppendEntries(state, msg);

        assertTrue(d.response.isSuccess());
        assertEquals(4L, state.getLastLogIndex(), "冲突截断后应追加新条目");
        assertEquals(2L, state.getLogTerm(4), "index 4 应替换为新 term 条目");
    }
}
