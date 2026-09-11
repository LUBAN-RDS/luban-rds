package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.Protocol;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P0-1（2026-09-11 mesh 审计）：mesh 模式禁用 MULTI/EXEC/DISCARD/WATCH/UNWATCH。
 * <p>
 * EXEC 曾在事务前置分支被本地截走执行——事务写只落单节点不经 Raft，三节点发散，切主即丢
 * 整批事务数据。事务 Raft 化是审计路线图第四组长期项，本测试锁定止血行为：
 * 五命令一律返回 {@code -ERR Transactions are not supported in mesh mode}；
 * 普通读写命令不受影响。
 * </p>
 * <p>嵌入式单节点 mesh server（peers 仅自身 → 自己即多数派，SET 立即 commit）+ Jedis 客户端
 * （与 ConcurrentTransactionTest 同款端口预留/客户端模式）。</p>
 */
class MeshTransactionDisabledTest {

    /** mesh 模式事务禁用错误串（与 BLOCK 禁用错误同风格，P0-1 止血契约）。 */
    static final String EXPECTED_ERROR = "ERR Transactions are not supported in mesh mode";

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
        // 等待 mesh 就绪（单节点即多数派，首轮流内即可选出 Leader 并可写）
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
    void multiReturnsErrInMeshMode() {
        assertTransactionDisabledError(Protocol.Command.MULTI);
    }

    @Test
    void execReturnsErrInMeshMode() {
        assertTransactionDisabledError(Protocol.Command.EXEC);
    }

    @Test
    void discardWatchUnwatchReturnErrInMeshMode() {
        assertTransactionDisabledError(Protocol.Command.DISCARD);
        assertTransactionDisabledError(Protocol.Command.WATCH, "k");
        assertTransactionDisabledError(Protocol.Command.UNWATCH);
    }

    @Test
    void normalWriteReadUnaffected() {
        assertEquals("OK", jedis.set("k1", "v1"));
        assertEquals("v1", jedis.get("k1"));
    }

    // ==================== 辅助 ====================

    /** 断言命令返回事务禁用错误串（Jedis 对 -ERR 响应抛 JedisDataException）。 */
    private void assertTransactionDisabledError(Protocol.Command cmd, String... args) {
        try {
            Object resp = jedis.sendCommand(cmd, args);
            String text = resp instanceof byte[]
                    ? new String((byte[]) resp, java.nio.charset.StandardCharsets.US_ASCII)
                    : String.valueOf(resp);
            fail("命令 " + cmd + " 应返回事务禁用错误，实际: " + text);
        } catch (redis.clients.jedis.exceptions.JedisDataException e) {
            assertEquals(EXPECTED_ERROR, e.getMessage().trim(),
                    "命令 " + cmd + " 错误串应为事务禁用提示");
        }
    }

    /** 单节点 mesh：等 Leader 产生且写立即可用（最多 10s）。 */
    private void waitMeshWritable() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        Jedis probe = new Jedis("127.0.0.1", server.getPort());
        try {
            while (System.currentTimeMillis() < deadline) {
                try {
                    if ("OK".equals(probe.set("mesh:tx:ready", "1"))) {
                        return;
                    }
                } catch (Exception retry) {
                    Thread.sleep(200);
                }
            }
            fail("mesh 单节点 10s 内未就绪（无 Leader 或写不可用）");
        } finally {
            probe.close();
        }
    }

    /** 查找随机可用端口（与 ConcurrentTransactionTest 同款惯例）。 */
    private static int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }
}
