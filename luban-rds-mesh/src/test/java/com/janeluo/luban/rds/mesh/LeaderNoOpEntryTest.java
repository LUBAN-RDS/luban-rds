package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-3（2026-09-11 mesh 审计）：新 Leader 追加 no-op 条目。
 * <p>
 * §5.4.2 只直接提交 currentTerm 条目——无 no-op 时重启遗留的旧 term 未确认条目在无新写入
 * 场景下永远无法间接提交（一致性窗口无限拉长）。no-op 与 P0-2（commitIndex 保持快照边界）
 * 配套：新 Leader 当选即以当前任期条目锚定间接提交。
 * </p>
 */
class LeaderNoOpEntryTest {

    private static final String A = "nodeA";

    private static class CaptureBus extends MeshBusClient {
        CaptureBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) {
            // no-op：单节点无 peer，仅为满足构造器类型
        }
    }

    @Test
    void singleNodeElection_appendsNoOp_andIndirectlyCommitsOldTermEntries() throws Exception {
        // 重启遗留场景：旧 term(5) 条目 1..3 已落盘未确认（P0-2 后 commitIndex=0）
        MeshState state = new MeshState();
        state.currentTerm = 5;
        for (long i = 1; i <= 3; i++) {
            state.appendEntry(new LogEntry(5L, i, new byte[]{1}, 0, null));
        }
        state.commitIndex = 0;
        state.lastApplied = 0;

        MemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        CaptureBus bus = new CaptureBus();
        // 单节点集群（totalNodes=1，自己即多数派），选举超时短
        MeshConfig config = MeshConfig.builder(A)
                .electionTimeout(50, 100)
                .heartbeatIntervalMs(200)
                .build();
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, store);
        node.start();
        try {
            // 等待选举完成（单节点：PreVote/正式选举立即多数派）
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline && node.getRole() != MeshRole.LEADER) {
                Thread.sleep(20);
            }
            assertEquals(MeshRole.LEADER, node.getRole(), "单节点应快速当选");

            // no-op 追加在日志末位：term=新任期，isNoOp=true
            long lastLogIndex = state.getLastLogIndex();
            LogEntry last = state.getEntry(lastLogIndex);
            assertTrue(last != null && last.isNoOp(), "日志末位应为 no-op 条目");
            assertEquals(state.currentTerm, last.getTerm(), "no-op 为当前任期");

            // 等 no-op 落盘回调 + commit 推进 + apply 完成（间接提交旧 term 条目 1..3）
            deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline
                    && (state.commitIndex < lastLogIndex || state.lastApplied < lastLogIndex)) {
                Thread.sleep(20);
            }
            assertTrue(state.commitIndex >= lastLogIndex,
                    "no-op 提交应带动旧 term 条目间接提交: commitIndex=" + state.commitIndex);
            assertEquals(lastLogIndex, state.lastApplied, "lastApplied 应推进到 no-op");
        } finally {
            node.stop();
        }
    }

    @Test
    void applierSkipsNoOpEntry() {
        MemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        LogEntry noOp = new LogEntry(6L, 4L, new byte[0], 0, LogEntry.NO_OP_EXTRA);
        Object resp = applier.apply(noOp);
        assertEquals("+OK\r\n", resp, "no-op 条目 apply 应跳过执行返回 +OK");
        // store 无副作用（无 key）
        assertEquals(null, store.get(0, "any"));
    }
}
