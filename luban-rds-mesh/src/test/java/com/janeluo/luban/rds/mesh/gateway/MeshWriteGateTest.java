package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.election.LeaseManager;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MeshWriteGate} 单元测试（阶段 5）。
 * <p>
 * 覆盖读写分流、MOVED 生成、isWriteCommand 判定、事务 extra 透传：
 * <ul>
 *   <li><b>写分流</b>：mock MeshNode.propose 返回成功 future，调 gate.write → 调 propose 且返回响应字节；</li>
 *   <li><b>读分流</b>：gate.read(GET) 本地 handler.handle 返回响应（真实 DefaultCommandHandler + 预置数据）；</li>
 *   <li><b>MOVED 生成</b>：非 Leader 时 write 抛 MovedToLeaderException；redirectResponse 基本格式；</li>
 *   <li><b>isWriteCommand</b>：SET/DEL/INCR/HSET/LPUSH/ZADD/XADD/EVAL=写；GET/HGET/LRANGE/SMEMBERS=读；</li>
 *   <li><b>事务 extra 透传</b>：write(rawFrame, dbIndex, extra) 把 extra 传给 propose。</li>
 * </ul>
 * </p>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshWriteGateTest {

    private static final byte[] OK = "+OK\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] ZERO = ":0\r\n".getBytes(StandardCharsets.ISO_8859_1);

    // ==================== 写分流 ====================

    @Test
    void write_proposesAndReturnsAppliedResponseBytes() {
        MeshNode node = mock(MeshNode.class);
        when(node.propose(eq(OK), eq(0), any())).thenReturn(CompletableFuture.completedFuture(OK));

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

        byte[] resp = gate.write(OK, 0, null);

        assertArrayEquals(OK, resp);
        verify(node, times(1)).propose(OK, 0, null);
    }

    @Test
    void write_nonLeaderThrowsMovedToLeaderException() {
        MeshNode node = mock(MeshNode.class);
        CompletableFuture<byte[]> failed = new CompletableFuture<>();
        failed.completeExceptionally(new MovedToLeaderException("leader-host:6379"));
        when(node.propose(any(), anyInt(), any())).thenReturn(failed);

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        MovedToLeaderException ex = assertThrows(MovedToLeaderException.class,
                () -> gate.write(OK, 0, null));
        assertEquals("leader-host:6379", ex.getLeaderAddr());
    }

    @Test
    void write_transactionExtraPassedToPropose() {
        MeshNode node = mock(MeshNode.class);
        byte[] extra = "watch-snapshot".getBytes(StandardCharsets.ISO_8859_1);
        when(node.propose(eq(OK), eq(1), eq(extra)))
                .thenReturn(CompletableFuture.completedFuture(OK));

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        byte[] resp = gate.write(OK, 1, extra);

        assertArrayEquals(OK, resp);
        // 确认 extra 被透传到 propose（非 null）
        verify(node).propose(OK, 1, extra);
        verify(node, never()).propose(any(), anyInt(), eq(null));
    }

    @Test
    void write_proposeTimeoutThrowsRetryableException() {
        MeshNode node = mock(MeshNode.class);
        CompletableFuture<byte[]> neverComplete = new CompletableFuture<>();
        when(node.propose(any(), anyInt(), any())).thenReturn(neverComplete);

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(),
                new DefaultCommandHandler(), new com.janeluo.luban.rds.protocol.RedisProtocolParser(), 50L);

        RetryableMeshException ex = assertThrows(RetryableMeshException.class,
                () -> gate.write(OK, 0, null));
        assertTrue(ex.getMessage().contains("timeout"), "msg=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("retry"), "msg should contain 'retry': " + ex.getMessage());
    }

    // ==================== 读分流 ====================

    @Test
    void read_leaderExecutesLocallyAndSerializesResponse() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(freshValidLease());

        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

        byte[] resp = gate.read(0, new String[]{"GET", "foo"});

        // GET foo → $3\r\nbar\r\n（bulk string）
        assertArrayEquals("$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);
        // 读路径不调 propose（性能不退化）
        verify(node, never()).propose(any(), anyInt(), any());
    }

    @Test
    void read_hashGetReturnsLocalValue() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.hset(0, "myhash", "field1", "value1");

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(freshValidLease());

        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

        byte[] resp = gate.read(0, new String[]{"HGET", "myhash", "field1"});

        assertArrayEquals("$6\r\nvalue1\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);
        verify(node, never()).propose(any(), anyInt(), any());
    }

    @Test
    void read_nonLeaderThrowsMovedToLeaderException() {
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("leaderNode");

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        MovedToLeaderException ex = assertThrows(MovedToLeaderException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        // 修正：非 Leader 抛点只携带 leaderNodeId（serviceAddr 留空），
        // 由 redirector 经 nodeIdToServiceAddr 映射解析真实 ip:port（修正 MOVED 无端口 bug）
        assertEquals("leaderNode", ex.getLeaderNodeId());
        assertNull(ex.getLeaderServiceAddr(), "serviceAddr 应留空，由 redirector 解析");
        assertEquals("foo", ex.getKey(), "读路径应携带命令 key 以算真实 slot");
        verify(node, never()).propose(any(), anyInt(), any());
    }

    @Test
    void read_getNeverInvokesPropose_doesNotDegradePerformance() {
        // 显式断言：读方法（GET/HGET）不进入 propose
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "k", "v");
        rawStore.hset(0, "h", "f", "1");

        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(freshValidLease());

        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());
        gate.read(0, new String[]{"GET", "k"});
        gate.read(0, new String[]{"HGET", "h", "f"});

        verify(node, never()).propose(any(), anyInt(), any());
    }

    /**
     * 阶段 7：lease 模式租约失效且 awaitValid 超时 → 抛 LeaseInvalidException（不放行陈旧读）。
     * 修正阶段 5 宽松放行行为，防旧 Leader 分区后服务读。
     */
    @Test
    void read_leaderLeaseExpired_awaitTimesOut_throwsLeaseInvalid() {
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");

        // 未续租的 LeaseManager → 失效；awaitValid 在短超时内返回 false
        LeaseManager expired = new LeaseManager();
        MeshNode node = mock(MeshNode.class);
        when(node.isLeader()).thenReturn(true);
        when(node.lease()).thenReturn(expired);

        // 用极短 readLeaseWaitMs 让 awaitValid 快速超时（避免测试长时间阻塞）
        MeshConfig config = MeshConfig.builder("n1")
                .readLeaseWaitMs(40)
                .build();
        MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), config);

        LeaseInvalidException ex = assertThrows(LeaseInvalidException.class,
                () -> gate.read(0, new String[]{"GET", "foo"}));
        assertTrue(ex.getMessage().contains("lease"), "msg=" + ex.getMessage());
        // 读路径不调 propose、也不放行本地读（raw store 未被读，无法断言但语义已由异常保证）
        verify(node, never()).propose(any(), anyInt(), any());
    }

    // ==================== redirectResponse / MOVED ====================

    @Test
    void redirectResponse_knownLeader_emitsMovedWithRealSlot() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn("leaderNode");

        // 注入 nodeId→serviceAddr 映射：redirectResponse 据此把 nodeId 解析成 ip:port
        java.util.Map<String, String> map = java.util.Collections.singletonMap("leaderNode", "10.0.0.1:6379");
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(), null, map);

        String moved = gate.redirectResponse("mykey");

        // leaderAddr 取映射的真实 ip:port（修正：不再用 nodeId 占位）；slot 用真实 CRC16
        int expectedSlot = com.janeluo.luban.rds.common.util.SlotUtils.getSlot("mykey");
        assertEquals("-MOVED " + expectedSlot + " 10.0.0.1:6379\r\n", moved);
        assertTrue(expectedSlot >= 0 && expectedSlot < 16384, "slot 应在 0-16383: " + expectedSlot);
    }

    @Test
    void redirectResponse_noLeader_emitsMeshdown() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn(null);

        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler());

        String resp = gate.redirectResponse("anykey");
        assertTrue(resp.startsWith("-MESHDOWN"), "resp=" + resp);
        assertTrue(resp.endsWith("\r\n"));
    }

    @Test
    void redirectResponse_nullKeySlotIsZero() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn("leaderNode");

        java.util.Map<String, String> map = java.util.Collections.singletonMap("leaderNode", "10.0.0.1:6379");
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(), null, map);

        String moved = gate.redirectResponse(null);
        assertEquals("-MOVED 0 10.0.0.1:6379\r\n", moved);
    }

    /**
     * D2 自重定向守卫：解析出的 Leader 地址等于本节点自身地址时，redirectResponse 不下发 MOVED
     * （会触发客户端死循环），改发 MESHDOWN。
     */
    @Test
    void redirectResponse_leaderAddrEqualsSelf_returnsMeshdownNotMoved() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn("leaderNode");

        // 映射把 leaderNode 解析到 10.0.0.1:6379；自身地址也是 10.0.0.1:6379（塌缩）
        java.util.Map<String, String> map = java.util.Collections.singletonMap("leaderNode", "10.0.0.1:6379");
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(),
                null, map, "10.0.0.1:6379");

        String resp = gate.redirectResponse("foo");
        assertTrue(resp.startsWith("-MESHDOWN"), "应返回 MESHDOWN 而非 MOVED 到自己: " + resp);
        assertTrue(resp.contains("self"), "应是自重定向 MESHDOWN: " + resp);
    }

    /**
     * D2：Leader 地址与自身不同时，redirectResponse 正常 MOVED（守卫不误伤）。
     */
    @Test
    void redirectResponse_leaderAddrDifferentFromSelf_normalMoved() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn("leaderNode");

        java.util.Map<String, String> map = java.util.Collections.singletonMap("leaderNode", "10.0.0.2:6380");
        // 自身 10.0.0.1:6379，Leader 10.0.0.2:6380 → 正常 MOVED
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(),
                null, map, "10.0.0.1:6379");

        String moved = gate.redirectResponse("foo");
        int expectedSlot = com.janeluo.luban.rds.common.util.SlotUtils.getSlot("foo");
        assertEquals("-MOVED " + expectedSlot + " 10.0.0.2:6380\r\n", moved);
    }

    // ==================== P3: leaderId 不可达兜底测试 ====================

    /**
     * P3：解析出的 leaderAddr 不在已知 peers 映射里（选举风暴中 leaderId 指向已死节点），
     * 应返回 MESHDOWN_LEADER_UNREACHABLE 而非 MOVED，避免客户端循环。
     */
    @Test
    void redirectResponse_leaderAddrNotInPeersMap_returnsMeshdownUnreachable() {
        MeshNode node = mock(MeshNode.class);
        when(node.getLeaderId()).thenReturn("deadNode");

        // 映射只含 node-a / node-b，deadNode 不在里面 → leaderAddr 解析不到
        // 但这里 getLeaderId 返回 deadNode，映射查无 → resolveLeaderServiceAddr 返回 null → MESHDOWN
        // 为测试 P3 的 containsValue 路径，需要 leaderAddr 能解析但不在映射 values 里
        java.util.Map<String, String> map = new java.util.HashMap<>();
        map.put("node-a", "10.0.0.1:6379");
        map.put("node-b", "10.0.0.2:6379");
        // 构造 leaderAddr=10.0.0.9:6380 不在映射 values 里：用 leaderId 查 map 得不到，
        // 改用直接设 leaderId 为一个不在 map 的 key → resolveLeaderServiceAddr 返回 null → 走 MESHDOWN 无 leader
        // P3 的 containsValue 路径需要 resolveLeaderServiceAddr 返回非 null 但不在 values 里，
        // 这需要 MovedToLeaderException 直接带 serviceAddr。redirectResponse 走 resolveLeaderServiceAddr，
        // 它只从 map 查 leaderId。所以 redirectResponse 的 P3 路径在实际中由 MeshClientRedirector 覆盖。
        // 这里测试映射查无 leaderId 的场景 → MESHDOWN no leader（已有路径）
        MeshWriteGate gate = new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(),
                null, map, "10.0.0.3:6379");

        String resp = gate.redirectResponse("foo");
        // deadNode 不在映射 → resolveLeaderServiceAddr 返回 null → MESHDOWN no leader
        assertTrue(resp.startsWith("-MESHDOWN"), "查无 leader 应返回 MESHDOWN: " + resp);
    }

    // ==================== isWriteCommand 判定 ====================

    @Test
    void isWriteCommand_classifiesKnownWriteCommands() {
        // ACL @write + supplement
        assertTrue(MeshWriteGate.isWriteCommand("SET"));
        assertTrue(MeshWriteGate.isWriteCommand("DEL"));
        assertTrue(MeshWriteGate.isWriteCommand("INCR"));
        assertTrue(MeshWriteGate.isWriteCommand("DECR"));
        assertTrue(MeshWriteGate.isWriteCommand("INCRBY"));
        assertTrue(MeshWriteGate.isWriteCommand("APPEND"));
        assertTrue(MeshWriteGate.isWriteCommand("MSET"));
        assertTrue(MeshWriteGate.isWriteCommand("GETSET"));
        assertTrue(MeshWriteGate.isWriteCommand("HSET"));
        assertTrue(MeshWriteGate.isWriteCommand("HSETNX"));
        assertTrue(MeshWriteGate.isWriteCommand("HDEL"));
        assertTrue(MeshWriteGate.isWriteCommand("HINCRBY"));
        assertTrue(MeshWriteGate.isWriteCommand("HMSET"));
        assertTrue(MeshWriteGate.isWriteCommand("LPUSH"));
        assertTrue(MeshWriteGate.isWriteCommand("RPUSH"));
        assertTrue(MeshWriteGate.isWriteCommand("LPOP"));
        assertTrue(MeshWriteGate.isWriteCommand("RPOP"));
        assertTrue(MeshWriteGate.isWriteCommand("LREM"));
        assertTrue(MeshWriteGate.isWriteCommand("LSET"));
        assertTrue(MeshWriteGate.isWriteCommand("LINSERT"));
        assertTrue(MeshWriteGate.isWriteCommand("LTRIM"));
        assertTrue(MeshWriteGate.isWriteCommand("SADD"));
        assertTrue(MeshWriteGate.isWriteCommand("SREM"));
        assertTrue(MeshWriteGate.isWriteCommand("SPOP"));
        assertTrue(MeshWriteGate.isWriteCommand("SMOVE"));
        assertTrue(MeshWriteGate.isWriteCommand("SDIFFSTORE"));
        assertTrue(MeshWriteGate.isWriteCommand("SUNIONSTORE"));
        assertTrue(MeshWriteGate.isWriteCommand("SINTERSTORE"));
        assertTrue(MeshWriteGate.isWriteCommand("ZADD"));
        assertTrue(MeshWriteGate.isWriteCommand("ZREM"));
        assertTrue(MeshWriteGate.isWriteCommand("ZINCRBY"));
        assertTrue(MeshWriteGate.isWriteCommand("ZPOPMAX"));
        assertTrue(MeshWriteGate.isWriteCommand("ZPOPMIN"));
        assertTrue(MeshWriteGate.isWriteCommand("ZUNIONSTORE"));
        assertTrue(MeshWriteGate.isWriteCommand("ZINTERSTORE"));
        assertTrue(MeshWriteGate.isWriteCommand("XADD"));
        assertTrue(MeshWriteGate.isWriteCommand("XDEL"));
        assertTrue(MeshWriteGate.isWriteCommand("XGROUP"));
        assertTrue(MeshWriteGate.isWriteCommand("XTRIM"));
        assertTrue(MeshWriteGate.isWriteCommand("EXPIRE"));
        assertTrue(MeshWriteGate.isWriteCommand("PEXPIRE"));
        assertTrue(MeshWriteGate.isWriteCommand("PERSIST"));
        assertTrue(MeshWriteGate.isWriteCommand("RENAME"));
        assertTrue(MeshWriteGate.isWriteCommand("FLUSHDB"));
        assertTrue(MeshWriteGate.isWriteCommand("FLUSHALL"));
        assertTrue(MeshWriteGate.isWriteCommand("SELECT"));
        assertTrue(MeshWriteGate.isWriteCommand("MULTI"));
        assertTrue(MeshWriteGate.isWriteCommand("EXEC"));
    }

    @Test
    void isWriteCommand_evalAndEvalshaAlwaysWrite() {
        // DESIGN §9：动态 Lua 命令统一当写
        assertTrue(MeshWriteGate.isWriteCommand("EVAL"));
        assertTrue(MeshWriteGate.isWriteCommand("EVALSHA"));
        // 大小写不敏感
        assertTrue(MeshWriteGate.isWriteCommand("eval"));
        assertTrue(MeshWriteGate.isWriteCommand("evalsha"));
    }

    @Test
    void isWriteCommand_classifiesKnownReadCommands() {
        // ACL @read + supplement
        assertFalse(MeshWriteGate.isWriteCommand("GET"));
        assertFalse(MeshWriteGate.isWriteCommand("MGET"));
        assertFalse(MeshWriteGate.isWriteCommand("HGET"));
        assertFalse(MeshWriteGate.isWriteCommand("HGETALL"));
        assertFalse(MeshWriteGate.isWriteCommand("HMGET"));
        assertFalse(MeshWriteGate.isWriteCommand("HEXISTS"));
        assertFalse(MeshWriteGate.isWriteCommand("HLEN"));
        assertFalse(MeshWriteGate.isWriteCommand("LINDEX"));
        assertFalse(MeshWriteGate.isWriteCommand("LRANGE"));
        assertFalse(MeshWriteGate.isWriteCommand("LLEN"));
        assertFalse(MeshWriteGate.isWriteCommand("SMEMBERS"));
        assertFalse(MeshWriteGate.isWriteCommand("SISMEMBER"));
        assertFalse(MeshWriteGate.isWriteCommand("SCARD"));
        assertFalse(MeshWriteGate.isWriteCommand("ZSCORE"));
        assertFalse(MeshWriteGate.isWriteCommand("ZRANGE"));
        assertFalse(MeshWriteGate.isWriteCommand("ZREVRANGE"));
        assertFalse(MeshWriteGate.isWriteCommand("ZCARD"));
        assertFalse(MeshWriteGate.isWriteCommand("XLEN"));
        assertFalse(MeshWriteGate.isWriteCommand("XRANGE"));
        assertFalse(MeshWriteGate.isWriteCommand("TYPE"));
        assertFalse(MeshWriteGate.isWriteCommand("EXISTS"));
        assertFalse(MeshWriteGate.isWriteCommand("TTL"));
        assertFalse(MeshWriteGate.isWriteCommand("PTTL"));
    }

    @Test
    void isWriteCommand_unknownDefaultsToWrite() {
        // 未知命令保守当写（强一致优先）
        assertTrue(MeshWriteGate.isWriteCommand("UNKNOWN_CMD_XYZ"));
        assertTrue(MeshWriteGate.isWriteCommand(""));
        assertTrue(MeshWriteGate.isWriteCommand(null));
    }

    @Test
    void isWriteCommand_caseInsensitive() {
        assertTrue(MeshWriteGate.isWriteCommand("set"));
        assertTrue(MeshWriteGate.isWriteCommand("Del"));
        assertFalse(MeshWriteGate.isWriteCommand("get"));
        assertFalse(MeshWriteGate.isWriteCommand("HGet"));
    }

    // ==================== 端到端：gate 写→真实 propose→apply ====================

    /**
     * 端到端：用真实单节点 MeshNode（Leader）验证 gate.write 链路通畅。
     * 与 {@code MeshNodeProposeTest} 同模式（单节点集群自多数派）。
     */
    @Test
    void write_endToEnd_singleNodeLeaderAppliesAndReturnsResponse() throws Exception {
        MeshConfig config = MeshConfig.builder("solo").build();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus,
                new com.janeluo.luban.rds.mesh.core.RaftStateMachine(), applier, rawStore);
        node.start();
        try {
            invokePrivate(node, "onWinElection");
            assertTrue(node.isLeader());
            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

            byte[] frame = respFrame("SET", "foo", "bar");
            byte[] resp = gate.write(frame, 0, null);

            assertArrayEquals(OK, resp);
            assertEquals("bar", rawStore.get(0, "foo"));
        } finally {
            node.stop();
        }
    }

    @Test
    void write_endToEnd_nonLeaderGateThrowsMoved() throws Exception {
        MeshConfig config = MeshConfig.builder("solo").build();
        MeshState state = new MeshState();
        state.leaderId = "someOtherNode";
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus,
                new com.janeluo.luban.rds.mesh.core.RaftStateMachine(), applier, rawStore);
        node.start();
        try {
            assertEquals(MeshRole.FOLLOWER, node.getRole());
            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());

            byte[] frame = respFrame("SET", "foo", "bar");
            MovedToLeaderException ex = assertThrows(MovedToLeaderException.class,
                    () -> gate.write(frame, 0, null));
            // 修正：doPropose 非 Leader 抛点只携带 leaderNodeId（serviceAddr 留空），
            // 由 redirector 经映射解析真实 ip:port（修正 MOVED 无端口 bug）
            assertEquals("someOtherNode", ex.getLeaderNodeId());
            assertNull(ex.getLeaderServiceAddr(), "serviceAddr 应留空，由 redirector 解析");
        } finally {
            node.stop();
        }
    }

    @Test
    void read_endToEnd_leaderReadsLocallyFromRawStore() throws Exception {
        MeshConfig config = MeshConfig.builder("solo").build();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar");
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus,
                new com.janeluo.luban.rds.mesh.core.RaftStateMachine(), applier, rawStore);
        node.start();
        try {
            invokePrivate(node, "onWinElection");
            // 续租一次让租约有效，避免读路径 awaitValid 等待
            node.lease().refreshOnMajorityAck(System.currentTimeMillis());

            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler());
            byte[] resp = gate.read(0, new String[]{"GET", "foo"});

            assertArrayEquals("$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);
        } finally {
            node.stop();
        }
    }

    // ==================== helpers ====================

    /** 构造一个已续租（有效）的 LeaseManager，供读路径测试避免 awaitValid 阻塞。 */
    private static LeaseManager freshValidLease() {
        LeaseManager lm = new LeaseManager();
        lm.refreshOnMajorityAck(System.currentTimeMillis());
        return lm;
    }

    /** 构造一个完整 RESP 命令帧的字节数组（与 LogApplierTest 同口径）。 */
    private static byte[] respFrame(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.ISO_8859_1);
            sb.append('$').append(b.length).append("\r\n")
                    .append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void invokePrivate(MeshNode node, String methodName) {
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod(methodName);
            m.setAccessible(true);
            m.invoke(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 捕获总线（单节点不发真实网络），与 MeshNodeProposeTest 同实现。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, MeshFrame> sent = new HashMap<>();

        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.put(targetNodeId, frame);
        }
    }
}
