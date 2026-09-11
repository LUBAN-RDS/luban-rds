package com.janeluo.luban.rds.mesh.integration;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-7（2026-09-11 mesh 审计）：follower WAL 落盘异步化。
 * <p>
 * 此前 follower 在 raft 线程同步 fsync（decideAppendEntries 内联 persistHook）——磁盘一抖，
 * 心跳应答与选举定时器同时被卡（9/11 写尾部 78-103ms 的头号嫌疑）。修复：内存追加+校验完成
 * 即回 ACK，WAL force 挪 persistExecutor（与 8/7 的 Leader 侧修复对称化）。
 * 本测试在两个 follower 的落盘路径上放置闸门：写必须在闸门未放开时完成 commit+apply
 * （ACK 不等待 fsync）；闸门放开后 follower 内存/WAL 语义保持。
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class FollowerAsyncPersistTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    private final Map<String, MeshNode> nodes = new LinkedHashMap<>();

    @AfterEach
    void stopAll() {
        for (MeshNode n : nodes.values()) {
            n.stop();
        }
    }

    private MeshNode buildCluster() {
        String[] all = {A, B, C};
        for (String id : all) {
            MeshConfig.Builder builder = MeshConfig.builder(id);
            for (String peer : all) {
                if (!peer.equals(id)) {
                    builder.addPeer(peer, "127.0.0.1:" + (11010 + peerIdx(peer)));
                }
            }
            MeshConfig config = builder
                    .electionTimeout(100, 200)
                    .heartbeatIntervalMs(50)
                    .build();
            MemoryStore store = new DefaultMemoryStore();
            LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
            GatedBus bus = new GatedBus(id, nodes);
            MeshNode node = new MeshNode(config, new MeshState(), bus, new RaftStateMachine(), applier, store);
            nodes.put(id, node);
        }
        for (MeshNode n : nodes.values()) {
            n.start();
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (MeshNode n : nodes.values()) {
                if (n.getRole() == MeshRole.LEADER) {
                    return n;
                }
            }
            sleep(50);
        }
        throw new AssertionError("10s 内未选出 Leader");
    }

    private static int peerIdx(String id) {
        return id.equals(A) ? 1 : id.equals(B) ? 2 : 3;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** 内存直投路由 bus。 */
    private static final class GatedBus extends MeshBusClient {
        private final String selfNodeId;
        private final Map<String, MeshNode> nodes;
        GatedBus(String selfNodeId, Map<String, MeshNode> nodes) {
            super(selfNodeId, new MeshBusHandler());
            this.selfNodeId = selfNodeId;
            this.nodes = nodes;
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            MeshNode target = nodes.get(targetNodeId);
            if (target != null) {
                target.onMessage(selfNodeId, frame);
            }
        }
    }

    @Test
    void followerAckPrecedesFsync_writeCommitsWhilePersistGated() throws Exception {
        MeshNode leader = buildCluster();
        // 对两个 follower 放置落盘闸门：未放开前它们的 WAL force 永不完成
        CountDownLatch persistGate = new CountDownLatch(1);
        for (MeshNode n : nodes.values()) {
            if (n != leader) {
                n.setPersistHook(() -> {
                    try {
                        persistGate.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    // 闸门等待后抛异常也无妨——本测试关注 ACK 时序，不关注 WAL 内容
                });
            }
        }
        // 等一轮心跳让 follower 的 hook 生效路径稳定
        sleep(150);

        // Leader propose 一条写：必须在闸门未放开时完成 commit+apply
        //（两个 follower 均已内存追加并 ACK，多数派成立；旧实现 ACK 前同步跑 persistHook → 卡死）
        byte[] setFrame = ("*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n").getBytes(StandardCharsets.US_ASCII);
        CompletableFuture<byte[]> future = leader.propose(setFrame, 0, null);
        byte[] resp = future.get(5, TimeUnit.SECONDS);
        assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.US_ASCII), resp, "写应完成并返回 +OK");

        // commit 已推进（闸门仍未放开）
        assertTrue(leader.getState().commitIndex >= 1, "Leader commitIndex 应已推进");

        // 放开闸门：follower 异步落盘恢复，集群保持健康
        persistGate.countDown();
        sleep(300);
        assertEquals(MeshRole.LEADER, leader.getRole(), "闸门期间 Leader 不应被推翻");
        for (MeshNode n : nodes.values()) {
            assertEquals("v1", ((DefaultMemoryStore) storeOf(n)).get(0, "k1"),
                    "三节点都应 apply k1=v1: " + n.getNodeId());
        }
    }

    private Object storeOf(MeshNode n) {
        try {
            java.lang.reflect.Method getApplier = MeshNode.class.getDeclaredMethod("getApplier");
            getApplier.setAccessible(true);
            Object applier = getApplier.invoke(n);
            java.lang.reflect.Field storeField = applier.getClass().getDeclaredField("rawStore");
            storeField.setAccessible(true);
            return storeField.get(applier);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
