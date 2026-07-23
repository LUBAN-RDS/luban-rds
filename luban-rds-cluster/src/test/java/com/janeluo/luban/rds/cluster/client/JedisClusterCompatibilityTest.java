package com.janeluo.luban.rds.cluster.client;

import com.janeluo.luban.rds.cluster.testinfra.EmbeddedCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisCluster;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Jedis Cluster 客户端兼容性测试
 * <p>
 * 启动嵌入式 Luban-RDS 集群（3 节点），用 Jedis 客户端验证集群协议兼容性。
 * </p>
 */
class JedisClusterCompatibilityTest {

    private static final int BASE_PORT = 17000;
    private static EmbeddedCluster testCluster;
    private static JedisCluster jedisCluster;

    @BeforeAll
    static void setUp() {
        testCluster = EmbeddedCluster.builder()
                .nodes(3)
                .basePort(BASE_PORT)
                .build();
        testCluster.start();
        testCluster.assignSlotsEvenly();

        Set<HostAndPort> nodes = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            nodes.add(new HostAndPort("127.0.0.1", BASE_PORT + i));
        }
        jedisCluster = new JedisCluster(nodes, 5000, 5000, 3, null);
    }

    @Test
    @DisplayName("测试基本读写操作")
    void testBasicReadWrite() {
        String key = "test:jedis:key";
        String value = "test-value-" + System.currentTimeMillis();

        String setResult = jedisCluster.set(key, value);
        assertEquals("OK", setResult);

        String getResult = jedisCluster.get(key);
        assertEquals(value, getResult);

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 Hash 操作")
    void testHashOperations() {
        String key = "test:jedis:hash";

        long hsetResult = jedisCluster.hset(key, "field1", "value1");
        assertEquals(1L, hsetResult);

        String hgetResult = jedisCluster.hget(key, "field1");
        assertEquals("value1", hgetResult);

        jedisCluster.hset(key, "field2", "value2");
        jedisCluster.hset(key, "field3", "value3");

        long hlenResult = jedisCluster.hlen(key);
        assertEquals(3L, hlenResult);

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 List 操作")
    void testListOperations() {
        String key = "test:jedis:list";

        long lpushResult = jedisCluster.lpush(key, "item1", "item2", "item3");
        assertEquals(3L, lpushResult);

        long llenResult = jedisCluster.llen(key);
        assertEquals(3L, llenResult);

        String rpopResult = jedisCluster.rpop(key);
        assertEquals("item1", rpopResult);

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 Set 操作")
    void testSetOperations() {
        String key = "test:jedis:set";

        long saddResult = jedisCluster.sadd(key, "member1", "member2", "member3");
        assertEquals(3L, saddResult);

        long scardResult = jedisCluster.scard(key);
        assertEquals(3L, scardResult);

        boolean isMember = jedisCluster.sismember(key, "member1");
        assertTrue(isMember);

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 ZSet 操作")
    void testZSetOperations() {
        String key = "test:jedis:zset";

        long zaddResult = jedisCluster.zadd(key, 1.0, "member1");
        assertEquals(1L, zaddResult);

        jedisCluster.zadd(key, 2.0, "member2");
        jedisCluster.zadd(key, 3.0, "member3");

        long zcardResult = jedisCluster.zcard(key);
        assertEquals(3L, zcardResult);

        var rangeResult = jedisCluster.zrange(key, 0, -1);
        assertEquals(3, rangeResult.size());

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 MOVED 重定向处理")
    void testMovedRedirect() {
        for (int i = 0; i < 10; i++) {
            String key = "test:jedis:redirect:" + i;
            String value = "value-" + i;

            jedisCluster.set(key, value);
            assertEquals(value, jedisCluster.get(key));

            jedisCluster.del(key);
        }
    }

    @Test
    @DisplayName("测试 Hash Tag")
    void testHashTag() {
        String tag = "user:1000";

        jedisCluster.set("{" + tag + "}:name", "Alice");
        jedisCluster.set("{" + tag + "}:age", "25");
        jedisCluster.set("{" + tag + "}:email", "alice@example.com");

        assertEquals("Alice", jedisCluster.get("{" + tag + "}:name"));
        assertEquals("25", jedisCluster.get("{" + tag + "}:age"));
        assertEquals("alice@example.com", jedisCluster.get("{" + tag + "}:email"));

        jedisCluster.del("{" + tag + "}:name");
        jedisCluster.del("{" + tag + "}:age");
        jedisCluster.del("{" + tag + "}:email");
    }

    @Test
    @DisplayName("测试 INCR/DECR 操作")
    void testIncrDecr() {
        String key = "test:jedis:counter";

        jedisCluster.set(key, "0");
        assertEquals(1L, jedisCluster.incr(key));
        assertEquals(2L, jedisCluster.incr(key));
        assertEquals(1L, jedisCluster.decr(key));

        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 EXISTS 和 DEL 操作")
    void testExistsAndDel() {
        String key = "test:jedis:exists";

        jedisCluster.set(key, "value");
        assertTrue(jedisCluster.exists(key));

        jedisCluster.del(key);
        assertFalse(jedisCluster.exists(key));
    }

    @AfterAll
    static void tearDown() {
        if (jedisCluster != null) {
            try {
                jedisCluster.close();
            } catch (Exception ignore) {
            }
        }
        if (testCluster != null) {
            testCluster.stop();
        }
    }
}
