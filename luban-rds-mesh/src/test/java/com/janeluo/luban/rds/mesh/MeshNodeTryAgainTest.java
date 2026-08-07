package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leader 失去领导权（更高任期 RequestVote 降级，新 Leader 未知）时，pending propose future
 * 应以 {@link RetryableMeshException} 完成（而非 {@link IllegalStateException}），
 * 让 {@code RedisServerHandler} 的专用 catch 返回 Redis 标准 {@code -TRYAGAIN}。
 * <p>
 * 关键：必须用 <b>RequestVote</b>（不带 leaderId）降级，而非 AppendEntries（带 leaderId 会走
 * {@code MovedToLeaderException} 分支）。{@code RequestVoteMessage} 不携带 leaderId，
 * 故 {@code state.leaderId} 保持 null → {@code failPendingProposalsOnLeadershipLoss} 走 else 分支。
 * </p>
 * <p>辅助方法沿用 {@code MeshNodePersistAsyncTest}（同包私有方法经反射访问）。</p>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class MeshNodeTryAgainTest {

    /** 测试用总线：只记录发送，不真正建连（与 MeshNodePersistAsyncTest 同模式）。 */
    private static class CaptureBus extends MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            /* no-op：pending propose 不会收到 ACK，future 保持未完成 */
        }
    }

    private static byte[] setFrame(String k, String v) {
        return ("*3\r\n$3\r\nSET\r\n$" + k.length() + "\r\n" + k
                + "\r\n$" + v.length() + "\r\n" + v + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 3 节点配置：长选举超时防干扰，心跳 100ms。 */
    private static MeshConfig threeNodeConfig() {
        return MeshConfig.builder("a")
                .addPeer("b", "127.0.0.1:11001")
                .addPeer("c", "127.0.0.1:11002")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
    }

    /** 把节点直接置为 Leader（反射调 onWinElection）。 */
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
    void leadershipLost_viaRequestVote_failsPendingWithRetryable() throws Exception {
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        state.currentTerm = 1;
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);
        node.start();
        try {
            makeLeader(node);
            assertTrue(node.isLeader(), "前置：节点应为 Leader");

            // 提议一个写：CaptureBus 不投递给真实 peer → 无 ACK → future 保持未完成
            CompletableFuture<byte[]> f = node.propose(setFrame("k", "v"), 0, null);
            awaitIdle(node);
            assertFalse(f.isDone(), "无 ACK 前 future 不应完成");
            assertEquals(1, pendingProposalsCount(node), "应有 1 个 pending propose");

            // 关键：用更高任期 RequestVote 降级（不带 leaderId → state.leaderId 保持 null → else 分支）
            RequestVoteMessage higherTerm = new RequestVoteMessage(10L, "b", 100L, 5L, false);
            node.onMessage("b", new MeshFrame("b", MessageType.REQUEST_VOTE.getCode(), higherTerm.encode()));
            awaitIdle(node);
            assertFalse(node.isLeader(), "降级后节点应为 Follower");

            // 断言：future 以 RetryableMeshException 完成（而非 IllegalStateException）
            ExecutionException ee =
                    assertThrows(ExecutionException.class, () -> f.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof RetryableMeshException,
                    "cause 应为 RetryableMeshException（实际：" + ee.getCause() + "）");
            assertTrue(ee.getCause().getMessage().contains("retry"),
                    "消息应包含 retry（实际：" + ee.getCause().getMessage() + "）");
            assertEquals(0, pendingProposalsCount(node), "降级后 pending 应清空");
        } finally {
            node.stop();
        }
    }
}
