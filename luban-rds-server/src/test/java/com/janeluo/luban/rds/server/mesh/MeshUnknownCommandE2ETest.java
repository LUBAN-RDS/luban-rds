package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;
import redis.clients.jedis.exceptions.JedisDataException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4（2026-09-11 mesh 审计 P2）端到端：未知命令（拼写错误）不进 Raft，
 * 直接返回 {@code -ERR unknown command 'X'}（对齐 Redis 错误串）。
 */
class MeshUnknownCommandE2ETest {

    @TempDir
    Path tempDir;

    private NettyRedisServer server;
    private Jedis jedis;

    @BeforeEach
    void startServer() throws Exception {
        int port = findRandomPort();
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(port);
        config.setMeshEnabled(true);
        config.setMeshPeers("n1@127.0.0.1:" + (port + 100) + ":" + port);
        config.setMeshSelfNodeId("n1");
        config.setMeshServicePort(port);
        server = new NettyRedisServer(config);
        server.start();
        waitMeshWritable();
        jedis = new Jedis("127.0.0.1", port);
        jedis.ping();
    }

    @AfterEach
    void stopServer() {
        if (jedis != null) {
            jedis.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void unknownCommandReturnsRedisStyleError() {
        // SETT 是自造拼写错误命令，Jedis 枚举没有——用 ProtocolCommand lambda 发原始命令名
        redis.clients.jedis.commands.ProtocolCommand sett = () -> "SETT".getBytes(StandardCharsets.US_ASCII);
        JedisDataException e = assertThrows(JedisDataException.class, () ->
                jedis.sendCommand(sett,
                        "k".getBytes(StandardCharsets.US_ASCII), "v".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(e.getMessage().contains("unknown command"),
                "错误串应含 unknown command，实际: " + e.getMessage());
        assertTrue(e.getMessage().contains("SETT"), "错误串应含命令名，实际: " + e.getMessage());
    }

    @Test
    void normalCommandsStillWork() {
        assertEquals("OK", jedis.set("k", "v"));
        assertEquals("v", jedis.get("k"));
    }

    private void waitMeshWritable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        try (Jedis probe = new Jedis("127.0.0.1", server.getPort())) {
            while (System.currentTimeMillis() < deadline) {
                try {
                    if ("OK".equals(probe.set("mesh:q4:ready", "1"))) {
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
