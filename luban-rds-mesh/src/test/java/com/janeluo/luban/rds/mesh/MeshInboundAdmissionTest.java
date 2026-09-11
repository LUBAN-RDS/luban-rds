package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-13（2026-09-11 mesh 审计）：入站帧准入控制——许可耗尽时新入站帧被丢弃并计数；
 * 内部任务不经信号量（durableIndex 推进不受影响）。
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MeshInboundAdmissionTest {

    private static final String A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String B = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private static class CaptureBus extends MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) { /* no-op */ }
    }

    @Test
    void exhaustedPermitsDropInboundFramesAndCount() throws Exception {
        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
        MeshNode node = new MeshNode(config, new MeshState(), new CaptureBus(A));
        node.start();
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // 仅剩 1 个许可：先往 raft 线程灌入一个阻塞任务，占住分发
            node.setInboundPermits(1);
            submitBlockingTask(node, blocked, release);
            assertTrue(blocked.await(2, TimeUnit.SECONDS), "阻塞任务应已进入 raft 线程");
            // 合法编码的 RequestVote 帧（与 MeshNodeTest 同模式）
            com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage voteMsg =
                    new com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage(1, B, 0, 0, true);
            MeshFrame voteFrame = new MeshFrame(B,
                    MessageType.REQUEST_VOTE.getCode(), voteMsg.encode());
            // 第 1 帧拿到许可后排队（阻塞任务占线程，dispatch 无法完成 → 许可不释放）
            node.onMessage(B, voteFrame);
            // 后续帧应被丢弃
            node.onMessage(B, voteFrame);
            node.onMessage(B, voteFrame);
            assertEquals(2, node.getInboundDroppedCount(),
                    "许可耗尽后的入站帧应被丢弃并计数");
        } finally {
            release.countDown();
            node.stop();
        }
    }

    /** 向 raftExecutor 提交一个阻塞任务（经 onMessage 入站路径以占用许可）。 */
    private void submitBlockingTask(MeshNode node, CountDownLatch blocked, CountDownLatch release)
            throws Exception {
        // 通过反射往 raftExecutor 提交：保持测试不依赖包私有入口
        java.lang.reflect.Field f = MeshNode.class.getDeclaredField("raftExecutor");
        f.setAccessible(true);
        java.util.concurrent.Executor executor = (java.util.concurrent.Executor) f.get(node);
        executor.execute(() -> {
            blocked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
