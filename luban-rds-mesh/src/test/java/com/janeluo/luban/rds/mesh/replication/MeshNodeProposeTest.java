package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshNode#propose(byte[], int, byte[])} 端到端测试（阶段 4 集成）。
 * <p>
 * 验证 propose → commit → apply → future complete 链路：
 * <ul>
 *   <li>非 Leader propose 抛 {@link MovedToLeaderException}；</li>
 *   <li>单节点集群（自多数派）propose 后 future 携带 apply 响应字节；</li>
 *   <li>apply 写入 raw store（且不写 AOF）；</li>
 *   <li>失去 Leader 身份后 pending propose future 以异常 complete。</li>
 * </ul>
 * </p>
 * <p>使用单节点集群避开真实网络，propose 后立即自多数派 commit + apply。</p>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshNodeProposeTest {

    /** 单节点集群：自己即多数派，无需 peer。 */
    private MeshConfig singleNodeConfig() {
        return MeshConfig.builder("solo").build();
    }

    /** 捕获总线（单节点不发真实网络）。 */
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

    /** 构造 SET 命令帧。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 把节点直接置为 Leader（绕过选举，测试聚焦 propose 链路）。 */
    private void makeLeader(MeshNode node, MeshState state) {
        invokePrivate(node, "onWinElection");
    }

    /** 在 raftExecutor 上同步执行（包私有方法，用反射访问）。 */
    private void awaitIdle(MeshNode node) {
        invokePrivate(node, "awaitIdle");
    }

    /** 取 pending propose 数量（包私有方法，用反射访问）。 */
    private int pendingProposalsCount(MeshNode node) {
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("pendingProposalsCount");
            m.setAccessible(true);
            return (int) m.invoke(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

    @Test
    void propose_nonLeader_throwsMovedToLeader() {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        // 4 参构造器（无 applier→replicator null）。这里用 6 参构造器注入 applier
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            // 默认 FOLLOWER
            assertEquals(MeshRole.FOLLOWER, node.getRole());

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);

            // 应以 MovedToLeaderException 完成
            CompletionException ce = assertThrows(CompletionException.class, f::join);
            Throwable cause = ce.getCause();
            assertTrue(cause instanceof MovedToLeaderException,
                    "cause 应为 MovedToLeaderException, 实际=" + cause);
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_nonLeader_exceptionCarriesRealKey() {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            CompletableFuture<byte[]> f = node.propose(setFrame("diag-key", "v"), 0, null);
            CompletionException ce = assertThrows(CompletionException.class, f::join);
            Throwable cause = ce.getCause();
            assertTrue(cause instanceof MovedToLeaderException,
                    "cause 应为 MovedToLeaderException, 实际=" + cause);
            MovedToLeaderException ex = (MovedToLeaderException) cause;
            assertEquals("diag-key", ex.getKey(),
                    "写路径 MOVED 异常应携带真实 key（供 slot 计算）");
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_nonLeader_malformedFrameKeyIsNull() {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            // 畸形帧：无数组头
            byte[] bad = "garbage-no-resp".getBytes(StandardCharsets.ISO_8859_1);
            CompletableFuture<byte[]> f = node.propose(bad, 0, null);
            CompletionException ce = assertThrows(CompletionException.class, f::join);
            Throwable cause = ce.getCause();
            assertTrue(cause instanceof MovedToLeaderException,
                    "cause 应为 MovedToLeaderException, 实际=" + cause);
            assertTrue(((MovedToLeaderException) cause).getKey() == null,
                    "畸形帧应回退 null key（不抛异常）");
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_singleNodeLeader_commitsAppliesAndCompletesFuture() throws Exception {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            makeLeader(node, state);
            assertTrue(node.isLeader());

            CompletableFuture<byte[]> f = node.propose(setFrame("foo", "bar"), 0, null);

            // 单节点：propose 后立即自多数派 commit + apply，future 应完成
            byte[] resp = f.get(3, TimeUnit.SECONDS);
            // 响应字节 = +OK\r\n（apply 返回值序列化）
            assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);

            // raw store 被 apply 写入
            assertEquals("bar", rawStore.get(0, "foo"));
            // commitIndex / lastApplied 推进
            assertEquals(1L, state.commitIndex);
            assertEquals(1L, state.lastApplied);
            // pending 已清空
            assertEquals(0, pendingProposalsCount(node));
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_multipleEntries_allCommittedAndApplied() throws Exception {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            makeLeader(node, state);

            CompletableFuture<byte[]> f1 = node.propose(setFrame("a", "1"), 0, null);
            CompletableFuture<byte[]> f2 = node.propose(setFrame("b", "2"), 0, null);
            CompletableFuture<byte[]> f3 = node.propose(setFrame("c", "3"), 0, null);

            assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), f1.get(3, TimeUnit.SECONDS));
            assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), f2.get(3, TimeUnit.SECONDS));
            assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), f3.get(3, TimeUnit.SECONDS));

            assertEquals("1", rawStore.get(0, "a"));
            assertEquals("2", rawStore.get(0, "b"));
            assertEquals("3", rawStore.get(0, "c"));
            assertEquals(3L, state.commitIndex);
            assertEquals(3L, state.lastApplied);
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_getCommand_returnsBulkStringResponse() throws Exception {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        rawStore.set(0, "foo", "bar"); // 预置数据
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            makeLeader(node, state);

            byte[] getFrame = "*2\r\n$3\r\nGET\r\n$3\r\nfoo\r\n".getBytes(StandardCharsets.ISO_8859_1);
            CompletableFuture<byte[]> f = node.propose(getFrame, 0, null);

            byte[] resp = f.get(3, TimeUnit.SECONDS);
            // GET 返回 bulk string $3\r\nbar\r\n
            assertArrayEquals("$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);
        } finally {
            node.stop();
        }
    }

    @Test
    void leadershipLoss_failsPendingPropose() throws Exception {
        // 用 3 节点配置，propose 后立即降级（无多数派 ACK，pending 不 complete）
        MeshConfig config = MeshConfig.builder("solo")
                .addPeer("b", "127.0.0.1:11001")
                .addPeer("c", "127.0.0.1:11002")
                .electionTimeout(5000, 10000) // 长超时，避免选举干扰
                .heartbeatIntervalMs(5000)
                .build();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            makeLeader(node, state);
            assertTrue(node.isLeader());

            // propose（3 节点但无 peer ACK，不会 commit，future 悬挂）
            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);

            // 等一下让 propose 入队
            awaitIdle(node);
            assertEquals(1, pendingProposalsCount(node), "应有 1 个 pending propose");

            // 模拟收到更高任期 → 降级 follower，pending 应被 fail
            com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse higher =
                    new com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse(10L, true, 0L);
            node.onMessage("b", new com.janeluo.luban.rds.mesh.bus.MeshFrame("b",
                    com.janeluo.luban.rds.mesh.bus.MessageType.APPEND_ENTRIES_RESP.getCode(),
                    higher.encode()));
            awaitIdle(node);

            assertFalse(node.isLeader(), "应已降级");
            assertEquals(0, pendingProposalsCount(node), "pending 应被清空");

            // future 以异常 complete
            ExecutionException ee = assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof IllegalStateException,
                    "cause 应为 IllegalStateException (leadership lost)");
        } finally {
            node.stop();
        }
    }

    @Test
    void propose_noApplier_returnsIllegalStateFuture() {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        CaptureBus bus = new CaptureBus("solo");
        // 不注入 applier（3 参构造器）→ replicator 为 null
        MeshNode node = new MeshNode(config, state, bus);
        node.start();
        try {
            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            CompletionException ce = assertThrows(CompletionException.class, f::join);
            assertTrue(ce.getCause() instanceof IllegalStateException);
        } finally {
            node.stop();
        }
    }

    @Test
    void followerAppliesCommittedEntriesOnLeaderCommitAdvance() throws Exception {
        // Follower 收到带 leaderCommit 的 AppendEntries → apply 到 raw store
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new com.janeluo.luban.rds.mesh.core.RaftStateMachine(),
                applier, rawStore);
        node.start();
        try {
            // 构造一条已提交的 entry（模拟 Leader 发来的 AppendEntries 携带 entries + leaderCommit）
            byte[] setFrame = setFrame("foo", "bar");
            com.janeluo.luban.rds.mesh.core.LogEntry entry =
                    new com.janeluo.luban.rds.mesh.core.LogEntry(1L, 1L, setFrame, 0, null);

            com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage msg =
                    new com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage(
                            1L, "leaderX", 0L, 0L, java.util.Collections.singletonList(entry), 1L);

            node.onMessage("leaderX", new com.janeluo.luban.rds.mesh.bus.MeshFrame("leaderX",
                    com.janeluo.luban.rds.mesh.bus.MessageType.APPEND_ENTRIES.getCode(), msg.encode()));
            awaitIdle(node);

            // Follower apply 到 raw store（响应对象丢弃，仅推进 lastApplied）
            assertEquals("bar", rawStore.get(0, "foo"));
            assertEquals(1L, state.commitIndex);
            assertEquals(1L, state.lastApplied);
        } finally {
            node.stop();
        }
    }
}
