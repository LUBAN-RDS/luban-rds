package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApplyBarrier 接线测试（fix-mesh-follower-read Task 2）：
 * 每条成功 apply 后触发 {@code appliedSignal}，供 follower 读路径唤醒屏障等待者。
 * <p>
 * 覆盖正向（每条 signal）与负向路径（无条目 / 缺失条目 / 暂不支持条目均不 signal），
 * 并钉住屏障 hook 异常不得被误判为毒条目触发 P1-5 fail-stop。
 * </p>
 */
class ApplySignalTest {

    /** 构造 SET 命令 RESP 帧（LogEntry.respPayload 为原始 RESP 字节）。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static LogReplicator newReplicator(MeshState state, LogApplier applier) {
        MeshConfig config = MeshConfig.builder("n1").build();
        // apply 路径不触碰 busClient，null 安全（仅构造器赋值）
        return new LogReplicator("n1", config, state, null, applier);
    }

    private static LogApplier setApplier() {
        return new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore());
    }

    @Test
    void applyCommittedEntries_signalsBarrier_perEntry() {
        MeshState state = new MeshState();
        LogReplicator replicator = newReplicator(state, setApplier());

        AtomicInteger signals = new AtomicInteger();
        replicator.setAppliedSignal(signals::incrementAndGet);

        state.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        state.appendEntry(new LogEntry(1L, 2L, setFrame("k2", "v2"), 0, null));
        state.commitIndex = 2;

        int applied = replicator.applyCommittedEntries();

        assertEquals(2, applied);
        assertEquals(2, signals.get(), "每条成功 apply 后都应 signal 一次");
    }

    @Test
    void noEntries_doesNotSignal() {
        MeshState state = new MeshState();
        LogReplicator replicator = newReplicator(state, setApplier());
        AtomicInteger signals = new AtomicInteger();
        replicator.setAppliedSignal(signals::incrementAndGet);
        state.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        state.commitIndex = 1;

        assertTrue(replicator.applyCommittedEntries() > 0);
        int after = signals.get();
        replicator.applyCommittedEntries();          // 无新条目
        assertEquals(after, signals.get(), "无条目可 apply 时不应 signal");
    }

    @Test
    void missingLogEntry_doesNotSignal() {
        MeshState state = new MeshState();
        LogReplicator replicator = newReplicator(state, setApplier());
        AtomicInteger signals = new AtomicInteger();
        replicator.setAppliedSignal(signals::incrementAndGet);
        // 无日志条目但 commitIndex 前移：getEntry 返回 null（缺失/已被快照截断）→ 跳过推进
        state.commitIndex = 1;

        int applied = replicator.applyCommittedEntries();

        assertEquals(0, applied, "缺失条目不计入 applied");
        assertEquals(0, signals.get(), "缺失条目跳过路径不应 signal");
        assertEquals(1L, state.lastApplied, "缺失条目应推进 lastApplied 避免死循环");
    }

    @Test
    void unsupportedOperationExceptionEntry_doesNotSignal() {
        MeshState state = new MeshState();
        // LogApplier 自身把所有执行异常包装为 ApplyFailureException；走「事务暂不支持」分支
        // 需 applier.apply 直接抛 UnsupportedOperationException（测试桩，非生产改动）
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore()) {
            @Override
            public Object apply(LogEntry entry) {
                throw new UnsupportedOperationException("injected unsupported entry");
            }
        };
        LogReplicator replicator = newReplicator(state, applier);
        AtomicInteger signals = new AtomicInteger();
        replicator.setAppliedSignal(signals::incrementAndGet);
        state.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        state.commitIndex = 1;

        int applied = replicator.applyCommittedEntries();

        assertEquals(0, applied, "暂不支持条目不计入 applied");
        assertEquals(0, signals.get(), "暂不支持条目不应 signal");
        assertEquals(1L, state.lastApplied, "暂不支持条目应推进 lastApplied");
        assertFalse(replicator.isApplyHalted(), "UnsupportedOperationException 不属于毒条目，不得 fail-stop");
    }

    @Test
    void throwingSignal_doesNotHaltApply() {
        MeshState state = new MeshState();
        LogReplicator replicator = newReplicator(state, setApplier());
        // 屏障 hook 抛异常：必须被吞掉（与 appliedNotifier 同样防御），不得触发毒条目 fail-stop
        replicator.setAppliedSignal(() -> {
            throw new IllegalStateException("barrier signal boom");
        });
        state.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        state.commitIndex = 1;

        int applied = replicator.applyCommittedEntries();

        assertEquals(1, applied, "hook 异常后条目仍应算作已 apply");
        assertFalse(replicator.isApplyHalted(), "屏障 hook 异常不得触发 apply fail-stop");
        assertEquals(1L, state.lastApplied);
    }
}
