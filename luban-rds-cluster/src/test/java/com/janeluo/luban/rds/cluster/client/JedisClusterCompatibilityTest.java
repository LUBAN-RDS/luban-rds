package com.janeluo.luban.rds.cluster.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * 此测试需要实际的集群环境运行，默认禁用。
 * 运行前请确保：
 * 1. 启动 Luban-RDS 集群（至少3个主节点）
 * 2. 集群节点端口：7000, 7001, 7002
 * 3. 集群已正确配置槽位分配
 */
@Disabled("需要实际的集群环境运行，请手动启用")
class JedisClusterCompatibilityTest {

    private static JedisCluster jedisCluster;

    @BeforeAll
    static void setUp() {
        // 连接到测试集群
        Set<HostAndPort> nodes = new HashSet<>();
        nodes.add(new HostAndPort("127.0.0.1", 7000));
        nodes.add(new HostAndPort("127.0.0.1", 7001));
        nodes.add(new HostAndPort("127.0.0.1", 7002));

        // 创建 JedisCluster 客户端
        jedisCluster = new JedisCluster(nodes);
    }

    @Test
    @DisplayName("测试基本读写操作")
    void testBasicReadWrite() {
        String key = "test:jedis:key";
        String value = "test-value-" + System.currentTimeMillis();

        // SET 操作
        String setResult = jedisCluster.set(key, value);
        assertEquals("OK", setResult);

        // GET 操作
        String getResult = jedisCluster.get(key);
        assertEquals(value, getResult);

        // 清理
        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 Hash 操作")
    void testHashOperations() {
        String key = "test:jedis:hash";

        // HSET 操作
        long hsetResult = jedisCluster.hset(key, "field1", "value1");
        assertEquals(1L, hsetResult);

        // HGET 操作
        String hgetResult = jedisCluster.hget(key, "field1");
        assertEquals("value1", hgetResult);

        // HSET 多个字段
        jedisCluster.hset(key, "field2", "value2");
        jedisCluster.hset(key, "field3", "value3");

        // HLEN 操作
        long hlenResult = jedisCluster.hlen(key);
        assertEquals(3L, hlenResult);

        // 清理
        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 List 操作")
    void testListOperations() {
        String key = "test:jedis:list";

        // LPUSH 操作
        long lpushResult = jedisCluster.lpush(key, "item1", "item2", "item3");
        assertEquals(3L, lpushResult);

        // LLEN 操作
        long llenResult = jedisCluster.llen(key);
        assertEquals(3L, llenResult);

        // RPOP 操作
        String rpopResult = jedisCluster.rpop(key);
        assertEquals("item1", rpopResult);

        // 清理
        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 Set 操作")
    void testSetOperations() {
        String key = "test:jedis:set";

        // SADD 操作
        long saddResult = jedisCluster.sadd(key, "member1", "member2", "member3");
        assertEquals(3L, saddResult);

        // SCARD 操作
        long scardResult = jedisCluster.scard(key);
        assertEquals(3L, scardResult);

        // SISMEMBER 操作
        boolean isMember = jedisCluster.sismember(key, "member1");
        assertTrue(isMember);

        // 清理
        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 ZSet 操作")
    void testZSetOperations() {
        String key = "test:jedis:zset";

        // ZADD 操作
        long zaddResult = jedisCluster.zadd(key, 1.0, "member1");
        assertEquals(1L, zaddResult);

        jedisCluster.zadd(key, 2.0, "member2");
        jedisCluster.zadd(key, 3.0, "member3");

        // ZCARD 操作
        long zcardResult = jedisCluster.zcard(key);
        assertEquals(3L, zcardResult);

        // ZRANGE 操作
        var rangeResult = jedisCluster.zrange(key, 0, -1);
        assertEquals(3, rangeResult.size());

        // 清理
        jedisCluster.del(key);
    }

    @Test
    @DisplayName("测试 MOVED 重定向处理")
    void testMovedRedirect() {
        // Jedis 客户端应自动处理 MOVED 重定向
        // 测试多个不同槽位的键，验证重定向功能
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
        // 使用 {tag} 语法确保键在同一槽位
        String tag = "user:1000";

        jedisCluster.set("{" + tag + "}:name", "Alice");
        jedisCluster.set("{" + tag + "}:age", "25");
        jedisCluster.set("{" + tag + "}:email", "alice@example.com");

        // 验证数据
        assertEquals("Alice", jedisCluster.get("{" + tag + "}:name"));
        assertEquals("25", jedisCluster.get("{" + tag + "}:age"));
        assertEquals("alice@example.com", jedisCluster.get("{" + tag + "}:email"));

        // 清理
        jedisCluster.del("{" + tag + "}:name");
        jedisCluster.del("{" + tag + "}:age");
        jedisCluster.del("{" + tag + "}:email");
    }

    @Test
    @DisplayName("测试批量操作")
    void testBatchOperations() {
        // 使用 Pipeline 或批量操作
        for (int i = 0; i < 100; i++) {
            String key = "test:jedis:batch:" + i;
            jedisCluster.set(key, "value-" + i);
        }

        // 验证数据
        for (int i = 0; i < 100; i++) {
            String key = "test:jedis:batch:" + i;
            assertEquals("value-" + i, jedisCluster.get(key));
            jedisCluster.del(key);
        }
    }

    @Test
    @DisplayName("测试过期时间")
    void testExpire() {
        String key = "test:jedis:expire";

        jedisCluster.set(key, "value");
        jedisCluster.expire(key, 10);

        long ttl = jedisCluster.ttl(key);
        assertTrue(ttl > 0 && ttl <= 10);

        jedisCluster.del(key);
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

    @Test
    @DisplayName("测试集群信息获取")
    void testClusterInfo() {
        // 通过 JedisCluster 获取集群信息
        // 注意：JedisCluster 不直接暴露 CLUSTER INFO 命令
        // 但可以验证集群连接正常工作
        String key = "test:jedis:info";
        jedisCluster.set(key, "test");
        assertNotNull(jedisCluster.get(key));
        jedisCluster.del(key);
    }

    @AfterAll
    static void tearDown() {
        if (jedisCluster != null) {
            try {
                jedisCluster.close();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
