package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-10（2026-09-11 mesh 审计 P1）：PUBLISH 经 Raft 复制——mesh 下不本地截走，
 * 经 gate propose，apply 时向本节点订阅者投递；响应 = 接收者数（:N，Redis 语义）。
 * <p>3 节点跨节点投递行为由 mesh 模块 MeshPubSubReplicationIT 锁定。</p>
 */
class MeshPublishRaftTest {

    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    private NettyRedisServer server;
    private int port;

    @BeforeEach
    void startServer() throws Exception {
        // 原子预留 service + bus 两个端口（避免 +N 偏移与其他监听冲突）
        int busPort;
        try (java.net.ServerSocket probe = new java.net.ServerSocket(0)) {
            busPort = probe.getLocalPort();
        }
        port = findRandomPort();
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(port);
        config.setMeshEnabled(true);
        config.setMeshBusPort(busPort);
        config.setMeshPeers("n1@127.0.0.1:" + busPort + ":" + port);
        config.setMeshSelfNodeId("n1");
        config.setMeshServicePort(port);
        server = new NettyRedisServer(config);
        server.start();
        waitMeshWritable();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void publishGoesThroughRaftAndDeliversToSubscriber() throws Exception {
        // 订阅者：裸 socket RESP
        Socket subscriber = new Socket("127.0.0.1", port);
        subscriber.setSoTimeout(5000);
        OutputStream out = subscriber.getOutputStream();
        out.write("*2\r\n$9\r\nSUBSCRIBE\r\n$4\r\nnews\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        BufferedReader reader = new BufferedReader(new InputStreamReader(subscriber.getInputStream()));
        // 读 SUBSCRIBE 确认（*3\r\n subscribe/news/1）
        String first = reader.readLine();
        assertNotNull(first, "应收到 SUBSCRIBE 确认");
        assertTrue(first.startsWith("*3"), "SUBSCRIBE 确认应为 3 元素数组");

        // 发布者：Jedis（经 gate 写路径）
        try (Jedis publisher = new Jedis("127.0.0.1", port)) {
            // mesh 下 PUBLISH 经 gate → Raft → apply 投递；响应 = 接收者数 :1
            Object resp = publisher.publish("news", "hello-mesh");
            assertEquals(1L, ((Number) resp).longValue(), "PUBLISH 响应应为接收者数 1");
        }

        // 订阅者应收到 message 推送（RESP 逐行读：*3/$9/subscribe/... → *3/$7/message/...）
        String line;
        StringBuilder payload = new StringBuilder();
        long deadline = System.currentTimeMillis() + 5000;
        subscriber.setSoTimeout(500);
        while (System.currentTimeMillis() < deadline) {
            try {
                line = reader.readLine();
            } catch (java.net.SocketTimeoutException e) {
                break;
            }
            if (line == null) {
                break;
            }
            payload.append(line).append('\n');
            if ("message".equals(line)) {
                // 其后 4 行：频道 len+值、消息 len+值
                payload.append(reader.readLine()).append('\n');
                payload.append(reader.readLine()).append('\n');
                payload.append(reader.readLine()).append('\n');
                payload.append(reader.readLine()).append('\n');
                break;
            }
        }
        assertTrue(payload.toString().contains("message"), "应收到 message 推送: " + payload);
        assertTrue(payload.toString().contains("news"), "推送应含频道名: " + payload);
        assertTrue(payload.toString().contains("hello-mesh"), "推送应含消息体: " + payload);
        subscriber.close();
    }

    @Test
    void publishWithoutSubscribersReturnsZero() {
        try (Jedis publisher = new Jedis("127.0.0.1", port)) {
            Object resp = publisher.publish("empty-channel", "nobody");
            assertEquals(0L, ((Number) resp).longValue(), "无订阅者应返回 :0");
        }
    }

    private void waitMeshWritable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        try (Jedis probe = new Jedis("127.0.0.1", port)) {
            while (System.currentTimeMillis() < deadline) {
                try {
                    if ("OK".equals(probe.set("mesh:p10:ready", "1"))) {
                        return;
                    }
                } catch (Exception retry) {
                    Thread.sleep(200);
                }
            }
            throw new IllegalStateException("mesh 单节点 10s 内未就绪");
        }
    }

    private static int findRandomPort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
