package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-5（2026-09-11 mesh 审计）：apply 异常 fail-stop——毒条目后 lastApplied 冻结、
 * apply 循环挂起、失败计数+告警。心跳/复制应答不受影响（线程已分离，此处单测循环语义）。
 */
class ApplyFailStopTest {

    private static final String A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private MeshConfig config;
    private MeshState state;
    private LogApplier applier;
    private LogReplicator replicator;

    /** 投毒 applier：index==2 的条目抛 ApplyFailureException（模拟节点本地故障）。 */
    static class PoisonApplier extends LogApplier {
        PoisonApplier(DefaultMemoryStore store) {
            super(new DefaultCommandHandler(), store);
        }
        @Override
        public Object apply(LogEntry entry) {
            if (entry.getIndex() == 2) {
                throw new ApplyFailureException("injected node-local failure", new RuntimeException("boom"));
            }
            return super.apply(entry);
        }
    }

    @BeforeEach
    void setUp() {
        config = MeshConfig.builder(A).build();
        state = new MeshState();
        state.currentTerm = 1;
        applier = new PoisonApplier(new DefaultMemoryStore());
        replicator = new LogReplicator(A, config, state,
                new NoopBus(), applier);
    }

    private static class NoopBus extends MeshBusClient {
        NoopBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) { /* no-op */ }
    }

    private void appendEntry(long index, byte[] payload) {
        LogEntry e = new LogEntry(state.currentTerm, index, payload, 0, null);
        state.appendEntry(e);
    }

    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void poisonEntryHaltsApplyWithoutAdvancingLastApplied() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));   // 毒条目
        appendEntry(3, setFrame("c", "3"));
        state.commitIndex = 3;

        int applied = replicator.applyCommittedEntries();

        assertEquals(1, applied, "只有 index 1 应成功 apply");
        assertEquals(1L, state.lastApplied, "lastApplied 应冻结在毒条目之前");
        assertTrue(replicator.isApplyHalted(), "apply 循环应挂起");
        assertEquals(1, replicator.getApplyFailureCount(), "失败应计数");
    }

    @Test
    void haltedApplyStaysFrozenOnSubsequentCalls() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        state.commitIndex = 2;
        replicator.applyCommittedEntries();
        assertTrue(replicator.isApplyHalted());

        // 后续 commit 推进 / 重复调用均不再 apply（不跳过毒条目）
        int second = replicator.applyCommittedEntries();
        assertEquals(0, second, "halt 后不得继续 apply");
        assertEquals(1L, state.lastApplied, "lastApplied 保持冻结");
        assertFalse(state.lastApplied == 2, "不得静默跳过毒条目");
    }

    @Test
    void normalEntriesStillApplyWhenNoPoison() {
        LogApplier healthy = new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore());
        LogReplicator clean = new LogReplicator(A, config, state, new NoopBus(), healthy);
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        state.commitIndex = 2;

        assertEquals(2, clean.applyCommittedEntries());
        assertEquals(2L, state.lastApplied);
        assertFalse(clean.isApplyHalted());
    }
}
