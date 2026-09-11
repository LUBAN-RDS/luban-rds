package com.janeluo.luban.rds.mesh.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q6（2026-09-11 mesh 审计 P2）：总线建连单飞去重——同一节点的并发 connect
 * 复用同一次连接尝试（防"建连进行中被绕过 existing 检查→重复建连→旧 channel 泄漏"）。
 */
class ConnectSingleFlightTest {

    private MeshBusServer server;
    private MeshBusClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void concurrentConnectsEstablishSingleConnection() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        MeshBusHandler handler = new MeshBusHandler();
        server = new MeshBusServer("server-node", port, handler);
        server.start();

        client = new MeshBusClient("client-node", handler);

        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    client.connect("server-node", "127.0.0.1", port).await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发 connect 应全部返回");
        assertEquals(0, failures.get());
        // 等待 server 侧全部 accept 落定（多余的连接即使建立也会因未使用而保持）
        Thread.sleep(300);
        assertEquals(1, client.getConnectedCount(),
                "同一节点并发 connect 应只建立一条客户端连接（单飞）");
    }
}
