package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 异步落盘回归：慢 fsync 不阻塞 raft 线程、durableIndex 门控 commit、
 * fsync 失败回滚、单节点异步落盘后 future 完成。
 * <p>包私有方法（awaitIdle/pendingProposalsCount）经反射访问（与 MeshNodeProposeTest 同模式）。</p>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshNodePersistAsyncTest {

    /** 测试用总线：只记录发送，不真正建连（与 MeshNodeProposeTest 同模式）。 */
    private static class CaptureBus extends MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) { /* no-op */ }
    }

    private static byte[] setFrame(String k, String v) {
        return ("*3\r\n$3\r\nSET\r\n$" + k.length() + "\r\n" + k
                + "\r\n$" + v.length() + "\r\n" + v + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 3 节点配置：长选举超时防干扰，但心跳保持默认 100ms。 */
    private static MeshConfig threeNodeConfig() {
        return MeshConfig.builder("a")
                .addPeer("b", "127.0.0.1:11001")
                .addPeer("c", "127.0.0.1:11002")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
    }

    private static MeshConfig singleNodeConfig() {
        return MeshConfig.builder("solo")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
    }

    /** 把节点直接置为 Leader（反射调 onWinElection，与 MeshNodeProposeTest 同模式）。 */
    private static void makeLeader(MeshNode node) {
        invokePrivate(node, "onWinElection");
    }

    /** 在 raftExecutor 上同步执行（包私有方法，用反射访问）。 */
    private static void awaitIdle(MeshNode node) {
        invokePrivate(node, "awaitIdle");
    }

    /** 取 pending propose 数量（包私有方法，用反射访问）。 */
    private static int pendingProposalsCount(MeshNode node) {
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
    void slowPersist_doesNotBlockRaftThread() throws Exception {
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        CountDownLatch persistEntered = new CountDownLatch(1);
        CountDownLatch releasePersist = new CountDownLatch(1);
        node.setPersistHook(() -> {
            persistEntered.countDown();
            try {
                releasePersist.await(5, TimeUnit.SECONDS); // 模拟慢盘：阻塞在 fsync
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            assertTrue(persistEntered.await(2, TimeUnit.SECONDS), "落盘任务应已进入持久化线程");

            // 落盘仍被阻塞时，raft 线程必须可执行新任务（心跳/RPC 不受影响）
            // 修复前：raft 线程同步阻塞在 persistHook 约 5s（hook 内 latch 自超时）后才恢复，后续 f.get(3s) 超时失败（红）
            awaitIdle(node);

            assertFalse(f.isDone(), "落盘未完成时 future 不应完成");

            // 两个 Follower 都 ACK（多数派 2/3），但自身仍阻塞在落盘 → commit 不得推进、future 不得完成
            AppendEntriesResponse ok = new AppendEntriesResponse(1L, true, 1L);
            node.onMessage("b", new MeshFrame("b", MessageType.APPEND_ENTRIES_RESP.getCode(), ok.encode()));
            node.onMessage("c", new MeshFrame("c", MessageType.APPEND_ENTRIES_RESP.getCode(), ok.encode()));
            awaitIdle(node);

            assertFalse(f.isDone(), "自身未落盘前 future 不得完成");

            releasePersist.countDown();
            byte[] resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
        } finally {
            releasePersist.countDown();
            node.stop();
        }
    }

    @Test
    void commit_gatedUntilSelfDurable() throws Exception {
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        CountDownLatch releasePersist = new CountDownLatch(1);
        node.setPersistHook(() -> {
            try {
                releasePersist.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            awaitIdle(node);

            // 两个 Follower 都 ACK（多数派 2/3），但自身未落盘 → commit 不得推进
            AppendEntriesResponse ok = new AppendEntriesResponse(1L, true, 1L);
            node.onMessage("b", new MeshFrame("b", MessageType.APPEND_ENTRIES_RESP.getCode(), ok.encode()));
            node.onMessage("c", new MeshFrame("c", MessageType.APPEND_ENTRIES_RESP.getCode(), ok.encode()));
            awaitIdle(node);

            assertEquals(0L, state.commitIndex, "自身未落盘前 commitIndex 不得推进");
            assertFalse(f.isDone(), "自身未落盘前 future 不得完成");

            // 落盘完成 → commit 推进 + future 完成
            releasePersist.countDown();
            byte[] resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals(1L, state.commitIndex, "落盘完成后 commitIndex 应推进");
            assertEquals("v", rawStore.get(0, "k"), "apply 应生效");
        } finally {
            releasePersist.countDown();
            node.stop();
        }
    }

    /**
     * 落盘抛异常 → 日志条目回滚 + pending future 以 IllegalStateException 完成。
     * <p>实现约束：回滚（truncateAfter）与 fail future 须在 raft 线程同一任务内完成
     * （truncate 先于 complete），本用例断言依赖该顺序。</p>
     */
    @Test
    void persistFailure_rollsBackAndFailsPending() throws Exception {
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        node.setPersistHook(() -> {
            throw new RuntimeException("disk full");
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            awaitIdle(node);

            java.util.concurrent.ExecutionException ee =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof IllegalStateException, "cause 应为 IllegalStateException");
            assertEquals(0L, state.getLastLogIndex(), "落盘失败应回滚日志条目");
            assertEquals(0, pendingProposalsCount(node), "pending 应清空");
        } finally {
            node.stop();
        }
    }

    @Test
    void singleNode_asyncPersist_completesFuture() throws Exception {
        MeshConfig config = singleNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("solo");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        CountDownLatch releasePersist = new CountDownLatch(1);
        node.setPersistHook(() -> {
            try {
                releasePersist.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            awaitIdle(node);
            assertFalse(f.isDone(), "单节点也应等落盘完成");

            releasePersist.countDown();
            byte[] resp = f.get(3, TimeUnit.SECONDS);
            assertNotNull(resp);
            assertEquals("v", rawStore.get(0, "k"));
        } finally {
            releasePersist.countDown();
            node.stop();
        }
    }

    @Test
    void persistFailure_afterLosingLeadership_doesNotTruncateLog() throws Exception {
        // 落盘失败回调在失去领导后才到达：非 Leader 不得截断（新 Leader 复制会修复日志）
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        CountDownLatch persistEntered = new CountDownLatch(1);
        CountDownLatch releasePersist = new CountDownLatch(1);
        AtomicBoolean firstHookCall = new AtomicBoolean(true);
        node.setPersistHook(() -> {
            if (!firstHookCall.getAndSet(false)) {
                // 后续调用在 raft 线程（decideAppendEntries 内 fsync）：不得阻塞/抛异常
                return;
            }
            // 首个调用 = persist 线程的落盘任务：阻塞至测试释放，最终落盘失败
            persistEntered.countDown();
            try {
                releasePersist.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("disk full");
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            assertTrue(persistEntered.await(2, TimeUnit.SECONDS));

            // 失去领导（更高任期 AppendEntries，leaderId 已知）
            com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage higher =
                    new com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage(
                            10L, "b", 0L, 0L, java.util.Collections.emptyList(), 0L);
            node.onMessage("b", new MeshFrame("b", MessageType.APPEND_ENTRIES.getCode(), higher.encode()));
            awaitIdle(node);
            assertFalse(node.isLeader());

            releasePersist.countDown();
            awaitIdle(node);

            // 非 Leader 的落盘失败回调不得截断日志（日志留给新 Leader 复制修复）
            assertEquals(1L, state.getLastLogIndex(), "非 Leader 不得因落盘失败截断日志");
        } finally {
            releasePersist.countDown();
            node.stop();
        }
    }

    @Test
    void stop_failsPendingProposals() throws Exception {
        // stop 时必须 fail 在途 propose，否则 gate 层 get() 永久悬挂
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);

        CountDownLatch persistEntered = new CountDownLatch(1);
        CountDownLatch releasePersist = new CountDownLatch(1);
        node.setPersistHook(() -> {
            persistEntered.countDown();
            try {
                releasePersist.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        node.start();
        try {
            makeLeader(node);

            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            assertTrue(persistEntered.await(2, TimeUnit.SECONDS));

            node.stop(); // 在途 propose 未完成时 stop

            java.util.concurrent.ExecutionException ee =
                    assertThrows(java.util.concurrent.ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof IllegalStateException, "stop 后 pending 应以异常完成");
        } finally {
            releasePersist.countDown();
        }
    }
}
