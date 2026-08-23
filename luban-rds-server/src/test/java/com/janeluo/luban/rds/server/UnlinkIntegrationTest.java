package com.janeluo.luban.rds.server;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;
import redis.clients.jedis.args.FlushMode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnlinkIntegrationTest {

    private static final int PORT = 6385;
    private static NettyRedisServer server;
    private static JedisPool pool;

    @BeforeAll
    static void startServer() throws Exception {
        server = new NettyRedisServer(PORT);
        server.start();
        Thread.sleep(1000);
        pool = new JedisPool(new JedisPoolConfig(), "localhost", PORT, 10000);
    }

    @AfterAll
    static void stopServer() {
        if (pool != null) pool.close();
        if (server != null) server.stop();
    }

    @Test
    void unlinkEndToEnd() {
        try (Jedis j = pool.getResource()) {
            j.set("u:key", "v");
            assertEquals(1L, j.unlink("u:key"));
            assertFalse(j.exists("u:key"));
        }
    }

    @Test
    void unlinkMultiKeyMixedExisting() {
        try (Jedis j = pool.getResource()) {
            j.set("u:m1", "v1");
            j.set("u:m2", "v2");
            assertEquals(2L, j.unlink("u:m1", "u:m2", "u:no"));
        }
    }

    @Test
    void unlinkInTransaction() {
        try (Jedis j = pool.getResource()) {
            j.set("u:tx", "v");
            Transaction t = j.multi();
            t.unlink("u:tx");
            List<Object> res = t.exec();
            assertEquals(1L, res.get(0));
            assertFalse(j.exists("u:tx"));
        }
    }

    @Test
    void unlinkExpiredKey() throws InterruptedException {
        try (Jedis j = pool.getResource()) {
            // 注：服务端仅实现 PSETEX，未实现 SETEX，此处用 SET + EXPIRE 设置 1 秒过期
            j.set("u:exp", "v");
            j.expire("u:exp", 1);
            Thread.sleep(1500);
            assertEquals(0L, j.unlink("u:exp"));
        }
    }

    @Test
    void flushAsyncAndSyncOptions() {
        try (Jedis j = pool.getResource()) {
            j.set("u:f1", "v");
            assertEquals("OK", j.flushAll(FlushMode.ASYNC));
            j.set("u:f2", "v");
            assertEquals("OK", j.flushAll(FlushMode.SYNC));
            j.set("u:f3", "v");
            assertEquals("OK", j.flushDB(FlushMode.ASYNC));
        }
    }

    @Test
    void flushInvalidOptionReturnsSyntaxError() {
        try (Jedis j = pool.getResource()) {
            redis.clients.jedis.Connection conn = j.getConnection();
            conn.sendCommand(redis.clients.jedis.Protocol.Command.FLUSHALL, "BOGUS");
            redis.clients.jedis.exceptions.JedisDataException ex =
                    assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
                            conn::getOne);
            assertTrue(ex.getMessage().contains("syntax error"), "非法参数应报 syntax error: " + ex.getMessage());
            conn.sendCommand(redis.clients.jedis.Protocol.Command.FLUSHDB, "BOGUS");
            assertThrows(redis.clients.jedis.exceptions.JedisDataException.class, conn::getOne);
        }
    }
}