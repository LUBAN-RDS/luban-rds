package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-9（2026-09-11 mesh 审计 P1）：超时未决 proposal 去重——
 * 同连接同帧重试挂接原 future（不二次 propose），落定后窗口关闭。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class WriteDedupOnTimeoutTest {

    private static class CaptureBus extends MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) { /* no-op */ }
    }

    private static byte[] setFrame(String k, String v) {
        return ("*3\r\n$3\r\nSET\r\n$" + k.length() + "\r\n" + k
                + "\r\n$" + v.length() + "\r\n" + v + "\r\n").getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void retryWhileInFlightAttachesToSameProposal() throws Exception {
        MeshConfig config = MeshConfig.builder("a").electionTimeout(5000, 10000).build();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        MeshNode node = new MeshNode(config, state, new CaptureBus("a"),
                new RaftStateMachine(), new LogApplier(new DefaultCommandHandler(), rawStore), rawStore);
        CountDownLatch release = new CountDownLatch(1);
        node.setPersistHook(() -> {
            try {
                // 每次落盘都阻塞到放行：制造"5s 内不可能完成"的在途 proposal
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        node.start();
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("onWinElection");
            m.setAccessible(true);
            m.invoke(node);

            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), new com.janeluo.luban.rds.protocol.RedisProtocolParser(), 300L);
            byte[] frame = setFrame("k", "v");

            // 第一次写：300ms 超时 → RetryableMeshException + 进入去重索引
            assertThrows(com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("ch1", frame, 0, null));
            long logAfterFirst = state.getLastLogIndex();

            // 重试（同连接同帧，仍在途）：挂接原 future，不生成第二条条目；
            // 同样 300ms 超时（原 future 仍未落定）
            assertThrows(com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("ch1", frame, 0, null));
            assertEquals(logAfterFirst, state.getLastLogIndex(),
                    "在途重试不得生成第二条 Raft 条目");

            // 不同帧：正常生成新条目（不受去重影响）
            assertThrows(com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("ch1", setFrame("k2", "v2"), 0, null));
            assertEquals(logAfterFirst + 1, state.getLastLogIndex(),
                    "不同帧不去重，正常 propose");
        } finally {
            release.countDown();
            node.stop();
        }
    }

    @Test
    void windowClosesAfterProposalSettles() throws Exception {
        MeshConfig config = MeshConfig.builder("a").electionTimeout(5000, 10000).build();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        MeshNode node = new MeshNode(config, state, new CaptureBus("a"),
                new RaftStateMachine(), new LogApplier(new DefaultCommandHandler(), rawStore), rawStore);
        node.start();
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("onWinElection");
            m.setAccessible(true);
            m.invoke(node);
            // 等待 no-op 条目 apply 完成（leader 可服务）
            Thread.sleep(300);

            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(), new com.janeluo.luban.rds.protocol.RedisProtocolParser(), 3000L);
            byte[] frame = setFrame("k", "v");

            // 单节点立即 commit：第一次写成功、落定
            assertEquals("+OK", new String(gate.write("ch1", frame, 0, null),
                    StandardCharsets.US_ASCII).trim());
            long logAfterFirst = state.getLastLogIndex();

            // 落定后的同帧重试：窗口已关，生成新条目
            gate.write("ch1", frame, 0, null);
            assertEquals(logAfterFirst + 1, state.getLastLogIndex(),
                    "落定后窗口关闭，重试视为新请求");
        } finally {
            node.stop();
        }
    }
}
