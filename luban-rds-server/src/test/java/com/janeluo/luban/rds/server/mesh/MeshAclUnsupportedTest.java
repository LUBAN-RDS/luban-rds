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
 * Q12（2026-09-11 mesh 审计 P3）：mesh 下 ACL 命令显式不支持。
 * <p>ACL 状态各节点独立，SETUSER 需 Raft 化才能一致——显式拒绝优于 Raft 空转 ERR；
 * 同时修正小写死键路由（{@code case "acl"} 永不匹配大写命令名）。</p>
 */
class MeshAclUnsupportedTest {

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
    void aclGetuserExplicitlyUnsupported() {
        JedisDataException e = assertThrows(JedisDataException.class, () ->
                jedis.sendCommand(Protocol.Command.valueOf("ACL"),
                        "GETUSER".getBytes(StandardCharsets.US_ASCII), "default".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("ERR ACL is not supported in mesh mode", e.getMessage().trim());
    }

    @Test
    void aclSetuserExplicitlyUnsupported() {
        JedisDataException e = assertThrows(JedisDataException.class, () ->
                jedis.sendCommand(Protocol.Command.valueOf("ACL"),
                        "SETUSER".getBytes(StandardCharsets.US_ASCII), "u".getBytes(StandardCharsets.US_ASCII),
                        "on".getBytes(StandardCharsets.US_ASCII)));
        assertEquals("ERR ACL is not supported in mesh mode", e.getMessage().trim());
    }

    @Test
    void aclErrorIsNotRaftError() {
        // 显式拒绝：错误串固定，不含 Raft/Raft日志字样（与 Raft 空转 ERR 区分）
        try {
            jedis.sendCommand(Protocol.Command.valueOf("ACL"), "LIST".getBytes(StandardCharsets.US_ASCII));
            throw new AssertionError("ACL LIST 应被拒绝");
        } catch (JedisDataException e) {
            assertTrue(e.getMessage().contains("ACL is not supported"),
                    "错误串应显式说明 ACL 不支持，实际: " + e.getMessage());
        }
    }

    // ==================== 辅助 ====================

    private void waitMeshWritable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        try (Jedis probe = new Jedis("127.0.0.1", server.getPort())) {
            while (System.currentTimeMillis() < deadline) {
                try {
                    if ("OK".equals(probe.set("mesh:q12:ready", "1"))) {
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
