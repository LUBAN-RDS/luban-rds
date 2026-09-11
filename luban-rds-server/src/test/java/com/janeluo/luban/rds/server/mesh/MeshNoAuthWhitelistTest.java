package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.server.RedisServerHandler;
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
import static org.junit.jupiter.api.Assertions.fail;

/**
 * P1-11（2026-09-11 mesh 审计）：认证检查前移——未认证客户端不得执行事务族
 * （MULTI/EXEC/WATCH/DISCARD/UNWATCH）与 CLUSTER 族（拓扑获取）。
 * <p>
 * 原 NOAUTH 检查位于事务前置分支之后，未认证连接可入队事务（follower 上还会本地落地）
 * 并获取集群拓扑。本测试锁定白名单语义：仅 AUTH/QUIT/HELLO/RESET 可在认证前执行。
 * </p>
 * <p>单机嵌入式 server（requirepass 配置，认证缺陷与 mesh 无关）+ Jedis 客户端。</p>
 */
class MeshNoAuthWhitelistTest {

    private static final String PASSWORD = "secret";

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
        config.setRequirepass(PASSWORD);
        server = new NettyRedisServer(config);
        server.start();
        // 未认证连接：不做 PING 探活（PING 不在认证前白名单，会被 NOAUTH 拦截）
        jedis = new Jedis("127.0.0.1", port);
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
    void unauthenticatedCannotQueueTransactions() {
        JedisDataException e = assertThrows(JedisDataException.class,
                () -> jedis.sendCommand(Protocol.Command.MULTI));
        assertTrue(e.getMessage().contains("NOAUTH"),
                "MULTI 未认证应返回 NOAUTH，实际: " + e.getMessage());
    }

    @Test
    void unauthenticatedCannotRunExec() {
        JedisDataException e = assertThrows(JedisDataException.class,
                () -> jedis.sendCommand(Protocol.Command.EXEC));
        assertTrue(e.getMessage().contains("NOAUTH"),
                "EXEC 未认证应返回 NOAUTH，实际: " + e.getMessage());
    }

    @Test
    void unauthenticatedCannotRunWatch() {
        JedisDataException e = assertThrows(JedisDataException.class,
                () -> jedis.sendCommand(Protocol.Command.WATCH, "k"));
        assertTrue(e.getMessage().contains("NOAUTH"),
                "WATCH 未认证应返回 NOAUTH，实际: " + e.getMessage());
    }

    @Test
    void unauthenticatedCannotRunCluster() {
        JedisDataException e = assertThrows(JedisDataException.class,
                () -> jedis.sendCommand(Protocol.Command.CLUSTER,
                        "INFO".getBytes(StandardCharsets.US_ASCII)));
        assertTrue(e.getMessage().contains("NOAUTH"),
                "CLUSTER 未认证应返回 NOAUTH，实际: " + e.getMessage());
    }

    @Test
    void whitelistCommandsAllowedBeforeAuth() {
        // AUTH 正常放行并完成认证
        assertEquals("OK", jedis.auth(PASSWORD));
        // 认证后命令可用
        assertEquals("OK", jedis.set("k", "v"));
    }

    @Test
    void whitelistContainsConnectionManagementCommands() {
        // QUIT/HELLO 无法经 Jedis 枚举发送（此版本无常量）——直接锁定白名单判定
        assertTrue(RedisServerHandler.isPreAuthAllowed("QUIT"), "QUIT 应属白名单");
        assertTrue(RedisServerHandler.isPreAuthAllowed("hello"), "HELLO 大小写不敏感");
        assertTrue(RedisServerHandler.isPreAuthAllowed("reset"));
        assertTrue(RedisServerHandler.isPreAuthAllowed("AUTH"));
        // 非白名单
        assertTrue(!RedisServerHandler.isPreAuthAllowed("MULTI"));
        assertTrue(!RedisServerHandler.isPreAuthAllowed("CLUSTER"));
        assertTrue(!RedisServerHandler.isPreAuthAllowed("GET"));
        assertTrue(!RedisServerHandler.isPreAuthAllowed(null));
    }

    private static int findRandomPort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
