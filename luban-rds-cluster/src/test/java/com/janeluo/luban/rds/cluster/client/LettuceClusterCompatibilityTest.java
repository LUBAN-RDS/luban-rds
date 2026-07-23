package com.janeluo.luban.rds.cluster.client;

import com.janeluo.luban.rds.cluster.testinfra.EmbeddedCluster;
import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lettuce Cluster 客户端兼容性测试
 * <p>
 * 启动嵌入式 Luban-RDS 集群（3 节点），用 Lettuce 客户端验证集群协议兼容性。
 * </p>
 */
class LettuceClusterCompatibilityTest {

    private static final int BASE_PORT = 17100;
    private static EmbeddedCluster testCluster;
    private static RedisClusterClient clusterClient;
    private static StatefulRedisClusterConnection<String, String> connection;

    @BeforeAll
    static void setUp() {
        testCluster = EmbeddedCluster.builder()
                .nodes(3)
                .basePort(BASE_PORT)
                .build();
        testCluster.start();
        testCluster.assignSlotsEvenly();

        List<RedisURI> uris = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            uris.add(RedisURI.create("127.0.0.1", BASE_PORT + i));
        }
        clusterClient = RedisClusterClient.create(uris);
        clusterClient.setDefaultTimeout(Duration.ofSeconds(5));
        connection = clusterClient.connect();
    }

    @Test
    @DisplayName("测试基本读写操作")
    void testBasicReadWrite() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:key";
        String value = "value-" + System.currentTimeMillis();

        String setResult = commands.set(key, value);
        assertEquals("OK", setResult);

        String getResult = commands.get(key);
        assertEquals(value, getResult);

        commands.del(key);
    }

    @Test
    @DisplayName("测试 Hash 操作")
    void testHashOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:hash";

        Boolean hsetResult = commands.hset(key, "field1", "value1");
        assertTrue(hsetResult);

        String hgetResult = commands.hget(key, "field1");
        assertEquals("value1", hgetResult);

        commands.hset(key, "field2", "value2");

        Long hlenResult = commands.hlen(key);
        assertEquals(2L, hlenResult);

        commands.del(key);
    }

    @Test
    @DisplayName("测试 List 操作")
    void testListOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:list";

        Long lpushResult = commands.lpush(key, "item1", "item2", "item3");
        assertEquals(3L, lpushResult);

        Long llenResult = commands.llen(key);
        assertEquals(3L, llenResult);

        String rpopResult = commands.rpop(key);
        assertEquals("item1", rpopResult);

        commands.del(key);
    }

    @Test
    @DisplayName("测试 Set 操作")
    void testSetOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:set";

        Long saddResult = commands.sadd(key, "member1", "member2", "member3");
        assertEquals(3L, saddResult);

        Long scardResult = commands.scard(key);
        assertEquals(3L, scardResult);

        Boolean isMember = commands.sismember(key, "member1");
        assertTrue(isMember);

        commands.del(key);
    }

    @Test
    @DisplayName("测试 ZSet 操作")
    void testZSetOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:zset";

        Long zaddResult = commands.zadd(key, 1.0, "member1");
        assertEquals(1L, zaddResult);

        commands.zadd(key, 2.0, "member2");
        commands.zadd(key, 3.0, "member3");

        Long zcardResult = commands.zcard(key);
        assertEquals(3L, zcardResult);

        var rangeResult = commands.zrange(key, 0, -1);
        assertEquals(3, rangeResult.size());

        commands.del(key);
    }

    @Test
    @DisplayName("测试拓扑刷新")
    void testTopologyRefresh() {
        Partitions partitions = clusterClient.getPartitions();
        assertNotNull(partitions);
        assertFalse(partitions.isEmpty());

        for (var partition : partitions) {
            assertNotNull(partition.getUri());
            assertNotNull(partition.getSlots());
        }
    }

    @Test
    @DisplayName("测试 MOVED 重定向处理")
    void testMovedRedirect() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        for (int i = 0; i < 10; i++) {
            String key = "test:lettuce:redirect:" + i;
            String value = "value-" + i;

            commands.set(key, value);
            assertEquals(value, commands.get(key));

            commands.del(key);
        }
    }

    @Test
    @DisplayName("测试 Hash Tag")
    void testHashTag() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String tag = "user:2000";

        commands.set("{" + tag + "}:name", "Bob");
        commands.set("{" + tag + "}:age", "30");
        commands.set("{" + tag + "}:email", "bob@example.com");

        assertEquals("Bob", commands.get("{" + tag + "}:name"));
        assertEquals("30", commands.get("{" + tag + "}:age"));
        assertEquals("bob@example.com", commands.get("{" + tag + "}:email"));

        commands.del("{" + tag + "}:name");
        commands.del("{" + tag + "}:age");
        commands.del("{" + tag + "}:email");
    }

    @Test
    @DisplayName("测试 INCR/DECR 操作")
    void testIncrDecr() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:counter";

        commands.set(key, "0");
        assertEquals(1L, commands.incr(key));
        assertEquals(2L, commands.incr(key));
        assertEquals(1L, commands.decr(key));

        commands.del(key);
    }

    @Test
    @DisplayName("测试 EXISTS 和 DEL 操作")
    void testExistsAndDel() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:exists";

        commands.set(key, "value");
        assertEquals(1L, commands.exists(key));

        commands.del(key);
        assertEquals(0L, commands.exists(key));
    }

    @Test
    @DisplayName("测试连接状态")
    void testConnectionState() {
        assertTrue(connection.isOpen());

        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String pingResult = commands.ping();
        assertEquals("PONG", pingResult);
    }

    @Test
    @DisplayName("测试集群节点信息")
    void testClusterNodes() {
        var partitions = clusterClient.getPartitions();

        assertTrue(partitions.size() >= 3, "集群应该至少有3个节点");

        for (var partition : partitions) {
            assertNotNull(partition.getUri());
            assertNotNull(partition.getNodeId());
        }
    }

    @AfterAll
    static void tearDown() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignore) {
            }
        }
        if (clusterClient != null) {
            try {
                clusterClient.shutdown();
            } catch (Exception ignore) {
            }
        }
        if (testCluster != null) {
            testCluster.stop();
        }
    }
}
