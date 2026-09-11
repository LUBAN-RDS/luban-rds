package com.janeluo.luban.rds.mesh.bus;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-12b（2026-09-11 mesh 审计）：总线成员校验 + 入站连接上限。
 * <ul>
 *   <li>fromNodeId 不在 peers 成员集的帧被丢弃并计数（不进 Raft）；</li>
 *   <li>入站连接数超上限被拒并计数。</li>
 * </ul>
 */
class MeshBusInboundCapTest {

    private static final String A = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String B = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String C = "c1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    private MeshBusServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void unknownMemberFrameIsDroppedAndCounted() {
        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(50, 100)
                .heartbeatIntervalMs(30)
                .leaseDurationMs(200)
                .build();
        MeshNode node = new MeshNode(config, new MeshState(), null);

        node.onMessage("intruder-node", new MeshFrame("intruder-node",
                MessageType.REQUEST_VOTE.getCode(), new byte[]{1}));

        assertEquals(1, node.getUnknownPeerFrameCount(), "非成员帧应被计数丢弃");
    }

    @Test
    void knownMemberFrameIsNotCounted() {
        MeshConfig config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .electionTimeout(50, 100)
                .heartbeatIntervalMs(30)
                .leaseDurationMs(200)
                .build();
        MeshNode node = new MeshNode(config, new MeshState(), null);

        node.onMessage(B, new MeshFrame(B,
                MessageType.REQUEST_VOTE.getCode(), new byte[]{1, 2, 3}));

        assertEquals(0, node.getUnknownPeerFrameCount(), "成员帧不应被成员校验丢弃");
    }

    @Test
    void inboundConnectionCapRejectsExcess() throws Exception {
        int port = findRandomPort();
        MeshBusHandler handler = new MeshBusHandler();
        server = new MeshBusServer("server-node", port, handler);
        server.setMaxInboundConnections(1);
        server.start();

        java.nio.channels.SocketChannel s1 = java.nio.channels.SocketChannel.open(
                new java.net.InetSocketAddress("127.0.0.1", port));
        // 等 server 侧 accept 完成
        Thread.sleep(200);
        java.nio.channels.SocketChannel s2 = java.nio.channels.SocketChannel.open(
                new java.net.InetSocketAddress("127.0.0.1", port));

        // 第二条连接应被服务端关闭（读端返回 -1）
        long deadline = System.currentTimeMillis() + 3000;
        boolean closed = false;
        while (System.currentTimeMillis() < deadline && !closed) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(16);
            int n = s2.read(buf);
            if (n < 0) {
                closed = true;
            } else {
                Thread.sleep(50);
            }
        }
        assertTrue(closed, "超限连接应被服务端关闭");
        assertTrue(server.getRejectedInboundCount() >= 1,
                "超限应计数，实际: " + server.getRejectedInboundCount());
        s1.close();
        s2.close();
    }

    private static int findRandomPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
