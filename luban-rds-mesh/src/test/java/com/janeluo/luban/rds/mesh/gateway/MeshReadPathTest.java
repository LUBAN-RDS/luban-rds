package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshConfig.ReadConsistency;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.election.LeaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 阶段 7 Leader 读路径单测（DESIGN.md §5.7 / IMPLEMENTATION_PLAN 阶段 7）。
 * <p>
 * 覆盖 lease / read-index 两模式切换，以及非 Leader、旧 Leader 租约过期等场景：
 * <ul>
 *   <li><b>lease 模式 + 租约有效</b>：直接本地读（不 awaitValid）；</li>
 *   <li><b>lease 模式 + 租约失效</b>：awaitValid 阻塞，另一线程续租后读成功；</li>
 *   <li><b>lease 模式 + 租约失效 + 超时</b>：抛 LeaseInvalidException（防陈旧读）；</li>
 *   <li><b>readindex 模式 + 主动确认成功</b>：读前 awaitValid（等当前心跳）后读成功；</li>
 *   <li><b>readindex 模式 + 确认失败</b>：抛 LeaseInvalidException；</li>
 *   <li><b>非 Leader</b>：抛 MovedToLeaderException；</li>
 *   <li><b>旧 Leader 分区后 invalidate</b>：读抛 LeaseInvalidException（防陈旧读）。</li>
 * </ul>
 * </p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MeshReadPathTest {

    private static final byte[] Bulk_BAR = "$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1);

    // ==================== lease 模式 ====================

    /**
     * lease 模式 + 租约有效：直接本地读，不调 awaitValid。
     */
    @Test
    void leaseMode_validLease_readsLocallyWithoutAwait() throws Exception {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        // spy LeaseManager 以验证 awaitValid 是否被调用
        LeaseManager lease = spy(new LeaseManager());
        lease.refreshOnMajorityAck(System.currentTimeMillis()); // 续租一次 → 有效

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.LEASE)
                .readLeaseWaitMs(1_000)
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        byte[] resp = gate.read(0, new String[]{"GET", "foo"});

        assertArrayEquals(Bulk_BAR, resp);
        // 关键断言：租约有效时不应触发 awaitValid
        verify(lease, never()).awaitValid(org.mockito.ArgumentMatchers.anyLong());
        verify(node, never()).propose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    /**
     * lease 模式 + 租约失效：awaitValid 阻塞，另一线程续租后唤醒，读成功。
     */
    @Test
    void leaseMode_expiredLease_awaitsThenRefreshed_readsSuccessfully() throws Exception {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        // 未续租的 LeaseManager（失效）
        LeaseManager lease = new LeaseManager();

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.LEASE)
                .readLeaseWaitMs(3_000) // 足够长，等异步续租
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        AtomicReference<byte[]> result = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                result.set(gate.read(0, new String[]{"GET", "foo"}));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                finished.countDown();
            }
        });
        reader.setDaemon(true);
        reader.start();

        // 确认读线程已进入 awaitValid 阻塞
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(150); // 让其充分阻塞
        assertEquals(null, result.get(), "读线程应仍阻塞在 awaitValid");

        // 模拟下一轮心跳多数派 ACK 续租 → 唤醒读线程
        lease.refreshOnMajorityAck(System.currentTimeMillis());

        assertTrue(finished.await(3, TimeUnit.SECONDS), "续租后读线程应完成");
        assertEquals(null, error.get(), "读不应抛异常: " + error.get());
        assertArrayEquals(Bulk_BAR, result.get());
    }

    /**
     * lease 模式 + 租约失效 + awaitValid 超时：抛 LeaseInvalidException（不放行陈旧读）。
     */
    @Test
    void leaseMode_expiredLease_awaitTimesOut_throwsLeaseInvalid() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        LeaseManager expired = new LeaseManager(); // 未续租 → 失效
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(expired);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.LEASE)
                .readLeaseWaitMs(60) // 极短超时
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        LeaseInvalidException ex = assertThrows(LeaseInvalidException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        assertTrue(ex.getMessage().contains("lease"), "msg=" + ex.getMessage());
        // 不调 propose
        verify(node, never()).propose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    // ==================== read-index 模式 ====================

    /**
     * readindex 模式：读前 ensureReadIndex（awaitValid 等当前心跳续租）成功后读。
     * <p>语义验证：readindex 模式下即使租约一开始有效，也会走 ensureReadIndex 分支（这里用已续租的
     * lease 让 awaitValid 立即返回 true，验证读成功）。关键区别在于 verify(lease).awaitValid 被调用，
     * 证明走的是「主动确认」分支。</p>
     */
    @Test
    void readIndexMode_ensureReadIndexThenRead_succeeds() throws Exception {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        // 已续租的 lease（readindex 的 awaitValid 立即返回 true）
        LeaseManager lease = spy(new LeaseManager());
        lease.refreshOnMajorityAck(System.currentTimeMillis());

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.READ_INDEX)
                .heartbeatIntervalMs(100) // readindex 等待 = 100*2+100 = 300ms
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        byte[] resp = gate.read(0, new String[]{"GET", "foo"});

        assertArrayEquals(Bulk_BAR, resp);
        // 关键断言：readindex 模式主动调 awaitValid（「主动确认」分支）
        verify(lease).awaitValid(org.mockito.ArgumentMatchers.anyLong());
    }

    /**
     * readindex 模式 + 主动确认失败（多数派未在当前心跳周期内 ACK）：抛 LeaseInvalidException。
     */
    @Test
    void readIndexMode_ensureReadIndexTimesOut_throwsLeaseInvalid() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        // 未续租的 lease → awaitValid 在 readindex 超时内返回 false
        LeaseManager lease = new LeaseManager();
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.READ_INDEX)
                .heartbeatIntervalMs(1) // readindex 等待 = 1*2+100 = 102ms（短超时）
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        LeaseInvalidException ex = assertThrows(LeaseInvalidException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        assertTrue(ex.getMessage().contains("read-index"), "msg=" + ex.getMessage());
    }

    /**
     * readindex 模式：主动确认期间被续租 → 读成功（模拟「当前心跳周期内多数派 ACK」）。
     */
    @Test
    void readIndexMode_refreshedDuringEnsure_readsSuccessfully() throws Exception {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        LeaseManager lease = new LeaseManager(); // 起初失效
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.READ_INDEX)
                .heartbeatIntervalMs(100) // readindex 等待 ~300ms
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        AtomicReference<byte[]> result = new AtomicReference<>(null);
        AtomicReference<Throwable> error = new AtomicReference<>(null);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        Thread reader = new Thread(() -> {
            started.countDown();
            try {
                result.set(gate.read(0, new String[]{"GET", "foo"}));
            } catch (Throwable t) {
                error.set(t);
            } finally {
                finished.countDown();
            }
        });
        reader.setDaemon(true);
        reader.start();

        // 等读线程进入 ensureReadIndex 的 awaitValid 阻塞
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(null, result.get(), "读线程应仍阻塞在 ensureReadIndex");

        // 模拟「当前心跳周期内多数派 ACK 续租」（readindex 的主动确认成功）
        lease.refreshOnMajorityAck(System.currentTimeMillis());

        assertTrue(finished.await(3, TimeUnit.SECONDS), "续租后读线程应完成");
        assertEquals(null, error.get(), "读不应抛异常: " + error.get());
        assertArrayEquals(Bulk_BAR, result.get());
    }

    // ==================== 非 Leader ====================

    /**
     * 非 Leader 读：抛 MovedToLeaderException（不进入租约/readindex 分支）。
     */
    @Test
    void nonLeader_readThrowsMovedToLeader() throws Exception {
        LeaseManager lease = spy(new LeaseManager());
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("leaderNode");
        when(node.lease()).thenReturn(lease);

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        MovedToLeaderException ex = assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        // 修正：抛点只携带 leaderNodeId（serviceAddr 留空），由 redirector 解析 ip:port
        assertEquals("leaderNode", ex.getLeaderNodeId());
        assertNull(ex.getLeaderServiceAddr(), "serviceAddr 应留空，由 redirector 解析");
        // 非 Leader 不查租约、不 propose
        verify(lease, never()).isValid(org.mockito.ArgumentMatchers.anyLong());
        verify(lease, never()).awaitValid(org.mockito.ArgumentMatchers.anyLong());
        verify(node, never()).propose(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.any());
    }

    // ==================== 旧 Leader 分区后租约过期 ====================

    /**
     * 旧 Leader 分区后被 invalidate：读抛 LeaseInvalidException（防陈旧读）。
     * <p>场景：节点角色仍是 Leader（isLeader=true，未感知到分区），但租约已 invalidate，
     * lease 模式 awaitValid 超时 → 抛异常，避免读到陈旧数据。</p>
     */
    @Test
    void staleLeader_invalidated_leaseMode_readThrows() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "stale"); // 陈旧值

        LeaseManager lease = new LeaseManager();
        lease.refreshOnMajorityAck(System.currentTimeMillis());
        lease.invalidate(); // 模拟失去 Leader 身份/分区后租约永久失效

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true); // 节点自身仍认为自己是 Leader（未感知分区）
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.LEASE)
                .readLeaseWaitMs(50) // 极短超时（invalidate 后 awaitValid 必然超时）
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        assertThrows(LeaseInvalidException.class, () -> gate.read(0, new String[]{"GET", "foo"}));
    }

    /**
     * 旧 Leader 分区后 invalidate：readindex 模式同样拒绝读（主动确认失败）。
     */
    @Test
    void staleLeader_invalidated_readIndexMode_readThrows() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "stale");

        LeaseManager lease = new LeaseManager();
        lease.refreshOnMajorityAck(System.currentTimeMillis());
        lease.invalidate();

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshConfig config = MeshConfig.builder("n1")
                .readConsistency(ReadConsistency.READ_INDEX)
                .heartbeatIntervalMs(1) // 短超时
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        assertThrows(LeaseInvalidException.class, () -> gate.read(0, new String[]{"GET", "foo"}));
    }

    // ==================== 默认配置 ====================

    /**
     * 默认配置（未显式设置 readConsistency）为 LEASE 模式。
     */
    @Test
    void defaultReadConsistencyIsLease() {
        MeshConfig config = MeshConfig.builder("n1").build();
        assertEquals(ReadConsistency.LEASE, config.getReadConsistency());
        assertEquals(1_000L, config.getReadLeaseWaitMs(),
                "默认 readLeaseWaitMs 应为 1000ms");
    }

    /**
     * 无 config 注入的 gate（旧构造器）：行为与默认 LEASE 一致。
     */
    @Test
    void gateWithoutConfig_defaultsToLeaseBehavior() throws Exception {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        LeaseManager lease = spy(new LeaseManager());
        lease.refreshOnMajorityAck(System.currentTimeMillis());

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(lease);

        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

        byte[] resp = gate.read(0, new String[]{"GET", "foo"});
        assertArrayEquals(Bulk_BAR, resp);
        // LEASE 模式 + 租约有效 → 不调 awaitValid
        verify(lease, never()).awaitValid(org.mockito.ArgumentMatchers.anyLong());
    }
}
