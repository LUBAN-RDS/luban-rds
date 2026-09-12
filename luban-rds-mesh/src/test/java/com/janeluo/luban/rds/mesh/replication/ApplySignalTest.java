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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ApplyBarrier 接线测试（fix-mesh-follower-read Task 2）：
 * 每条成功 apply 后触发 {@code appliedSignal}，供 follower 读路径唤醒屏障等待者。
 */
class ApplySignalTest {

    /** 构造 SET 命令 RESP 帧（LogEntry.respPayload 为原始 RESP 字节）。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void applyCommittedEntries_signalsBarrier_perEntry() {
        MeshState state = new MeshState();
        MeshConfig config = MeshConfig.builder("n1").build();
        DefaultMemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        // apply 路径不触碰 busClient，null 安全（仅构造器赋值）
        LogReplicator replicator = new LogReplicator("n1", config, state, null, applier);

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
    void applyHalted_doesNotSignal() {
        MeshState state = new MeshState();
        MeshConfig config = MeshConfig.builder("n1").build();
        DefaultMemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        LogReplicator replicator = new LogReplicator("n1", config, state, null, applier);
        AtomicInteger signals = new AtomicInteger();
        replicator.setAppliedSignal(signals::incrementAndGet);
        state.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        state.commitIndex = 1;

        assertTrue(replicator.applyCommittedEntries() > 0);
        int after = signals.get();
        replicator.applyCommittedEntries();          // 无新条目
        assertEquals(after, signals.get(), "无条目可 apply 时不应 signal");
    }
}
