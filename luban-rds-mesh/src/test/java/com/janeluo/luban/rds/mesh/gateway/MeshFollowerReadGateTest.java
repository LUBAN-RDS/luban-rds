package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.core.ApplyBarrier;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * follower 读 gate 分支（fix-mesh-follower-read / Task 10）。
 * <p>所有失败路径必须收敛到既有 MOVED（{@link MovedToLeaderException}）——结构上不可能
 * 返回"未达 readIndex 的状态"。</p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MeshFollowerReadGateTest {

    private static final byte[] BULK_BAR = "$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private MeshWriteGate gate(MeshNode node, DefaultMemoryStore store,
                               MeshConfig.ReadFromFollower mode, long cacheMs) {
        MeshConfig config = MeshConfig.builder("n1")
                .readFromFollower(mode)
                .followerReadMaxWaitMs(500)
                .followerReadCacheMs(cacheMs)
                .build();
        return new MeshWriteGate(node, store, new DefaultCommandHandler(""),
                config, Collections.emptyMap(), null);
    }

    /** READ_INDEX 模式 + 自定义 handler（驱动本地执行异常路径）。 */
    private MeshWriteGate gateWithHandler(MeshNode node, DefaultMemoryStore store,
                                          DefaultCommandHandler handler, long cacheMs) {
        MeshConfig config = MeshConfig.builder("n1")
                .readFromFollower(MeshConfig.ReadFromFollower.READ_INDEX)
                .followerReadMaxWaitMs(500)
                .followerReadCacheMs(cacheMs)
                .build();
        return new MeshWriteGate(node, store, handler, config, Collections.emptyMap(), null);
    }

    /** 非 Leader 节点的通用桩：ready、未 halt、已取到读点 10、本地已 apply 到 10。 */
    private MeshNode followerNode(Long readIndex, long localApplied, ReadIndexCache cache) {
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(false);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("n2");
        when(node.fetchReadIndex(anyLong())).thenReturn(readIndex);
        when(node.currentTerm()).thenReturn(1L);
        when(node.readIndexCache()).thenReturn(cache);
        MeshState applied = new MeshState();
        applied.lastApplied = localApplied;
        when(node.applyBarrier()).thenReturn(new ApplyBarrier(applied));
        return node;
    }

    @Test
    void off_keepsExistingMovedBehavior() {
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(false);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("n2");

        assertThrows(MovedToLeaderException.class,
                () -> gate(node, new DefaultMemoryStore(), MeshConfig.ReadFromFollower.OFF, 0)
                        .read(0, new String[]{"GET", "foo"}));
    }

    @Test
    void readindex_fetchesReadPoint_waitsBarrier_thenExecutesLocally() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        MeshWriteGate gate = gate(followerNode(10L, 10L, new ReadIndexCache()),
                store, MeshConfig.ReadFromFollower.READ_INDEX, 0);

        byte[] resp = gate.read(0, new String[]{"GET", "foo"});
        assertArrayEquals(BULK_BAR, resp);
    }

    @Test
    void readindex_barrierTimeout_fallsBackToMoved() {
        // 读点 50 永远追不上（本地只到 1）→ 屏障超时 → 回落 MOVED
        MeshWriteGate gate = gate(followerNode(50L, 1L, new ReadIndexCache()),
                new DefaultMemoryStore(), MeshConfig.ReadFromFollower.READ_INDEX, 0);

        assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
    }

    @Test
    void readindex_readPointUnavailable_fallsBackToMoved() {
        // fetchReadIndex 返回 null（无 Leader / 无许可 / 超时）→ 无读点 → 回落 MOVED
        MeshWriteGate gate = gate(followerNode(null, 100L, new ReadIndexCache()),
                new DefaultMemoryStore(), MeshConfig.ReadFromFollower.READ_INDEX, 0);

        assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
    }

    /**
     * cacheMs &gt; 0：第二次读复用短窗口缓存的读点，不再发第二次 readIndex RPC。
     * <p>窗口取 10s（而非生产默认 100ms）：测试只证明"命中即复用"，不受 JVM 冷启动
     * （首次本地读的类加载可能耗时可观）干扰，保证确定性。</p>
     */
    @Test
    void readindex_cacheHit_reusesReadPointWithoutSecondFetch() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        MeshNode node = followerNode(10L, 10L, new ReadIndexCache());
        MeshWriteGate gate = gate(node, store, MeshConfig.ReadFromFollower.READ_INDEX, 10_000);

        assertArrayEquals(BULK_BAR, gate.read(0, new String[]{"GET", "foo"}));
        assertArrayEquals(BULK_BAR, gate.read(0, new String[]{"GET", "foo"}));

        // 缓存命中：读点 RPC 只发一次
        verify(node, times(1)).fetchReadIndex(anyLong());
    }

    /** 取读点 RPC 抛异常 → 必须收敛到 MOVED（不把异常泄漏给客户端）。 */
    @Test
    void readindex_readPointRpcThrows_fallsBackToMoved() {
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(false);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("n2");
        when(node.readIndexCache()).thenThrow(new RuntimeException("read point rpc failed"));

        MeshWriteGate gate = gate(node, new DefaultMemoryStore(),
                MeshConfig.ReadFromFollower.READ_INDEX, 0);

        assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
    }

    /** apply 屏障被中断 → 收敛到 MOVED，且中断标志必须被恢复（不吞中断）。 */
    @Test
    void readindex_barrierInterrupted_fallsBackToMovedAndRestoresInterrupt() throws Exception {
        ApplyBarrier barrier = mock(ApplyBarrier.class);
        when(barrier.awaitApplied(anyLong(), anyLong())).thenThrow(new InterruptedException("test"));

        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(false);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("n2");
        when(node.fetchReadIndex(anyLong())).thenReturn(10L);
        when(node.currentTerm()).thenReturn(1L);
        when(node.readIndexCache()).thenReturn(new ReadIndexCache());
        when(node.applyBarrier()).thenReturn(barrier);

        MeshWriteGate gate = gate(node, new DefaultMemoryStore(),
                MeshConfig.ReadFromFollower.READ_INDEX, 0);

        assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        assertTrue(Thread.interrupted(), "中断标志必须被恢复（Thread.currentThread().interrupt()）");
    }

    /** 本地执行抛异常 → 必须收敛到 MOVED（不得把 -ERR 字符串当读结果返回）。 */
    @Test
    void readindex_localExecutionThrows_fallsBackToMoved() {
        DefaultCommandHandler throwingHandler = mock(DefaultCommandHandler.class);
        when(throwingHandler.handle(eq("GET"), anyInt(), any(String[].class), any()))
                .thenThrow(new RuntimeException("handler boom"));

        MeshWriteGate gate = gateWithHandler(followerNode(10L, 10L, new ReadIndexCache()),
                new DefaultMemoryStore(), throwingHandler, 0);

        assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
    }
}
