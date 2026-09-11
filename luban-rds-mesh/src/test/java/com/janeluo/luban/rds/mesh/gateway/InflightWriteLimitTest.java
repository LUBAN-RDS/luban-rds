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
 * P1-15（2026-09-11 mesh 审计 P1）：全局在途写并发上限——
 * 超限立即 TRYAGAIN（不进入 propose）；落定后计数归零；无上限（&lt;=0）不限流。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class InflightWriteLimitTest {

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
    void saturatedInflightRejectsWithTryagainImmediately() throws Exception {
        MeshConfig config = MeshConfig.builder("a").electionTimeout(5000, 10000).build();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        MeshNode node = new MeshNode(config, state, new CaptureBus("a"),
                new RaftStateMachine(), new LogApplier(new DefaultCommandHandler(), rawStore), rawStore);
        CountDownLatch release = new CountDownLatch(2);
        // 用可计数阻塞 hook：前两次落盘阻塞（制造 2 个在途），之后放行
        node.setPersistHook(() -> {
            try {
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

            MeshWriteGate gate = new MeshWriteGate(node, rawStore, new DefaultCommandHandler(),
                    new com.janeluo.luban.rds.protocol.RedisProtocolParser(), 300L);
            gate.setMaxInflightWrites(2);

            // 占满 2 个在途（各 300ms 后超时抛 TRYAGAIN，future 未落定 → 计数仍为 2）
            final byte[] f1 = setFrame("k1", "v1");
            final byte[] f2 = setFrame("k2", "v2");
            Thread t1 = new Thread(() -> assertThrows(
                    com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("c1", f1, 0, null)));
            Thread t2 = new Thread(() -> assertThrows(
                    com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("c2", f2, 0, null)));
            t1.start();
            t2.start();
            t1.join(5000);
            t2.join(5000);

            assertEquals(2, gate.getInflightWrites(), "两个在途 proposal 应占用计数");

            // 第 3 条写：立即 TRYAGAIN（不阻塞、不 propose）
            long begin = System.currentTimeMillis();
            assertThrows(com.janeluo.luban.rds.mesh.client.RetryableMeshException.class,
                    () -> gate.write("c3", setFrame("k3", "v3"), 0, null));
            assertTrue(System.currentTimeMillis() - begin < 100,
                    "超限应立即拒绝而非阻塞");
            assertEquals(2, gate.getInflightWrites(), "被拒的第 3 条不得占用计数");
        } finally {
            release.countDown();
            release.countDown();
            node.stop();
        }
    }
}
