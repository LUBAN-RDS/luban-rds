package com.janeluo.luban.rds.mesh.bus;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-12a（2026-09-11 mesh 审计）：总线 HELLO 握手认证。
 * <ul>
 *   <li>token 配置后，正确 HELLO 的连接可通信；</li>
 *   <li>无 HELLO 直发业务帧 / 错 token → 连接被关、拒绝计数增长；</li>
 *   <li>token 未配置（默认）→ 行为与旧版完全一致（兼容）。</li>
 * </ul>
 */
class BusHelloAuthTest {

    private MeshBusServer server;
    private MeshBusClient client;
    private MeshBusHandler handler;

    private final ConcurrentLinkedQueue<MeshFrame> received = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> receivedFrom = new ConcurrentLinkedQueue<>();

    private final CountDownLatch receivedLatch = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    private int startServer(String token) throws Exception {
        int port = findRandomPort();
        handler = new MeshBusHandler();
        handler.setMessageConsumer((from, frame) -> {
            receivedFrom.add(from);
            received.add(frame);
            receivedLatch.countDown();
        });
        server = new MeshBusServer("server-node", port, handler);
        if (token != null) {
            server.setAuthToken(token);
        }
        server.start();
        return server.getBusPort();
    }

    private MeshBusClient startClient(String token) {
        client = new MeshBusClient("client-node", handler);
        if (token != null) {
            client.setAuthToken(token);
        }
        return client;
    }

    @Test
    void correctTokenPassesAndFramesFlow() throws Exception {
        int port = startServer("s3cret");
        MeshBusClient c = startClient("s3cret");
        c.connect("server-node", "127.0.0.1", port).await(5, TimeUnit.SECONDS);
        // 等握手完成（HELLO 是 fire-and-forget；留出时间窗）
        Thread.sleep(300);
        c.send("server-node", new MeshFrame("client-node",
                MessageType.APPEND_ENTRIES.getCode(), new byte[]{1, 2, 3}));
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "认证通过的连接应能收到业务帧");
        assertEquals("client-node", receivedFrom.peek());
        assertEquals(0, server.getRejectedAuthCount());
    }

    @Test
    void clientWithoutTokenIsRejected() throws Exception {
        int port = startServer("s3cret");
        MeshBusClient c = startClient(null);   // 旧版本客户端：不发 HELLO
        c.connect("server-node", "127.0.0.1", port).await(5, TimeUnit.SECONDS);
        Thread.sleep(200);
        c.send("server-node", new MeshFrame("client-node",
                MessageType.APPEND_ENTRIES.getCode(), new byte[]{1}));
        // 业务帧到达前连接已被关（等待窗口内不投递）
        Thread.sleep(500);
        assertEquals(0, received.size(), "未认证帧不得投递上层");
        assertTrue(server.getRejectedAuthCount() >= 1,
                "未认证业务帧应计数，实际: " + server.getRejectedAuthCount());
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        int port = startServer("s3cret");
        MeshBusClient c = startClient("wrong-token");
        c.connect("server-node", "127.0.0.1", port).await(5, TimeUnit.SECONDS);
        Thread.sleep(500);
        assertEquals(0, received.size(), "错误 token 的连接不得投递");
        assertTrue(server.getRejectedAuthCount() >= 1,
                "错误 token 应计数，实际: " + server.getRejectedAuthCount());
    }

    @Test
    void noTokenOnBothSidesKeepsLegacyBehavior() throws Exception {
        int port = startServer(null);       // 服务端认证关闭
        MeshBusClient c = startClient(null); // 客户端不发 HELLO（旧版行为）
        c.connect("server-node", "127.0.0.1", port).await(5, TimeUnit.SECONDS);
        c.send("server-node", new MeshFrame("client-node",
                MessageType.APPEND_ENTRIES.getCode(), new byte[]{9}));
        assertTrue(receivedLatch.await(5, TimeUnit.SECONDS), "认证关闭时行为与旧版一致");
        assertEquals(0, server.getRejectedAuthCount());
    }

    private static int findRandomPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
