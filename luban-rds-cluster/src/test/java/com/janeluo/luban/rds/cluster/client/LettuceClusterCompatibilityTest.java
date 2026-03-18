package com.janeluo.luban.rds.cluster.client;

import io.lettuce.core.RedisURI;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.cluster.models.partitions.RedisClusterNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lettuce Cluster 客户端兼容性测试
 * <p>
 * 此测试需要实际的集群环境运行，默认禁用。
 * 运行前请确保：
 * 1. 启动 Luban-RDS 集群（至少3个主节点）
 * 2. 集群节点端口：7000, 7001, 7002
 * 3. 集群已正确配置槽位分配
 */
@Disabled("需要实际的集群环境运行，请手动启用")
class LettuceClusterCompatibilityTest {

    private static RedisClusterClient clusterClient;
    private static StatefulRedisClusterConnection<String, String> connection;

    @BeforeAll
    static void setUp() {
        // 创建集群客户端
        clusterClient = RedisClusterClient.create(
                Arrays.asList(
                        RedisURI.create("127.0.0.1", 7000),
                        RedisURI.create("127.0.0.1", 7001),
                        RedisURI.create("127.0.0.1", 7002)
                )
        );

        // 设置超时
        clusterClient.setDefaultTimeout(Duration.ofSeconds(5));

        // 建立连接
        connection = clusterClient.connect();
    }

    @Test
    @DisplayName("测试基本读写操作")
    void testBasicReadWrite() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:key";
        String value = "value-" + System.currentTimeMillis();

        // SET 操作
        String setResult = commands.set(key, value);
        assertEquals("OK", setResult);

        // GET 操作
        String getResult = commands.get(key);
        assertEquals(value, getResult);

        // 清理
        commands.del(key);
    }

    @Test
    @DisplayName("测试 Hash 操作")
    void testHashOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:hash";

        // HSET 操作
        Boolean hsetResult = commands.hset(key, "field1", "value1");
        assertTrue(hsetResult);

        // HGET 操作
        String hgetResult = commands.hget(key, "field1");
        assertEquals("value1", hgetResult);

        // HSET 多个字段
        commands.hset(key, "field2", "value2");

        // HLEN 操作
        Long hlenResult = commands.hlen(key);
        assertEquals(2L, hlenResult);

        // 清理
        commands.del(key);
    }

    @Test
    @DisplayName("测试 List 操作")
    void testListOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:list";

        // LPUSH 操作
        Long lpushResult = commands.lpush(key, "item1", "item2", "item3");
        assertEquals(3L, lpushResult);

        // LLEN 操作
        Long llenResult = commands.llen(key);
        assertEquals(3L, llenResult);

        // RPOP 操作
        String rpopResult = commands.rpop(key);
        assertEquals("item1", rpopResult);

        // 清理
        commands.del(key);
    }

    @Test
    @DisplayName("测试 Set 操作")
    void testSetOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:set";

        // SADD 操作
        Long saddResult = commands.sadd(key, "member1", "member2", "member3");
        assertEquals(3L, saddResult);

        // SCARD 操作
        Long scardResult = commands.scard(key);
        assertEquals(3L, scardResult);

        // SISMEMBER 操作
        Boolean isMember = commands.sismember(key, "member1");
        assertTrue(isMember);

        // 清理
        commands.del(key);
    }

    @Test
    @DisplayName("测试 ZSet 操作")
    void testZSetOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:zset";

        // ZADD 操作
        Long zaddResult = commands.zadd(key, 1.0, "member1");
        assertEquals(1L, zaddResult);

        commands.zadd(key, 2.0, "member2");
        commands.zadd(key, 3.0, "member3");

        // ZCARD 操作
        Long zcardResult = commands.zcard(key);
        assertEquals(3L, zcardResult);

        // ZRANGE 操作
        var rangeResult = commands.zrange(key, 0, -1);
        assertEquals(3, rangeResult.size());

        // 清理
        commands.del(key);
    }

    @Test
    @DisplayName("测试拓扑刷新")
    void testTopologyRefresh() {
        // 验证拓扑刷新功能正常
        Partitions partitions = clusterClient.getPartitions();
        assertNotNull(partitions);

        // 验证分区信息
        assertFalse(partitions.isEmpty());

        // 验证每个分区都有槽位范围
        for (var partition : partitions) {
            assertNotNull(partition.getUri());
            assertNotNull(partition.getSlots());
        }
    }

    @Test
    @DisplayName("测试 MOVED 重定向处理")
    void testMovedRedirect() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // Lettuce 客户端应自动处理 MOVED 重定向
        // 测试多个不同槽位的键，验证重定向功能
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

        // 使用 {tag} 语法确保键在同一槽位
        String tag = "user:2000";

        commands.set("{" + tag + "}:name", "Bob");
        commands.set("{" + tag + "}:age", "30");
        commands.set("{" + tag + "}:email", "bob@example.com");

        // 验证数据
        assertEquals("Bob", commands.get("{" + tag + "}:name"));
        assertEquals("30", commands.get("{" + tag + "}:age"));
        assertEquals("bob@example.com", commands.get("{" + tag + "}:email"));

        // 清理
        commands.del("{" + tag + "}:name");
        commands.del("{" + tag + "}:age");
        commands.del("{" + tag + "}:email");
    }

    @Test
    @DisplayName("测试批量操作")
    void testBatchOperations() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // 批量写入
        for (int i = 0; i < 100; i++) {
            String key = "test:lettuce:batch:" + i;
            commands.set(key, "value-" + i);
        }

        // 验证数据
        for (int i = 0; i < 100; i++) {
            String key = "test:lettuce:batch:" + i;
            assertEquals("value-" + i, commands.get(key));
            commands.del(key);
        }
    }

    @Test
    @DisplayName("测试过期时间")
    void testExpire() {
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        String key = "test:lettuce:expire";

        commands.set(key, "value");
        commands.expire(key, Duration.ofSeconds(10));

        Long ttl = commands.ttl(key);
        assertTrue(ttl != null && ttl > 0 && ttl <= 10);

        commands.del(key);
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
    @DisplayName("测试异步操作")
    void testAsyncOperations() {
        var asyncCommands = connection.async();

        String key = "test:lettuce:async";
        String value = "async-value";

        // 异步 SET
        var setFuture = asyncCommands.set(key, value);
        setFuture.thenAccept(result -> assertEquals("OK", result));

        // 异步 GET
        var getFuture = asyncCommands.get(key);
        getFuture.thenAccept(result -> assertEquals(value, result));

        // 等待异步操作完成
        try {
            setFuture.toCompletableFuture().get();
            getFuture.toCompletableFuture().get();
        } catch (Exception e) {
            fail("异步操作失败: " + e.getMessage());
        }

        // 清理
        asyncCommands.del(key);
    }

    @Test
    @DisplayName("测试连接状态")
    void testConnectionState() {
        // 验证连接状态
        assertTrue(connection.isOpen());

        // 获取同步命令
        RedisAdvancedClusterCommands<String, String> commands = connection.sync();

        // 执行 PING 命令
        String pingResult = commands.ping();
        assertEquals("PONG", pingResult);
    }

    @Test
    @DisplayName("测试集群节点信息")
    void testClusterNodes() {
        // 获取集群分区信息
        var partitions = clusterClient.getPartitions();

        // 验证分区数量
        assertTrue(partitions.size() >= 3, "集群应该至少有3个节点");

        // 验证每个节点的信息
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
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
        if (clusterClient != null) {
            try {
                clusterClient.shutdown();
            } catch (Exception e) {
                // 忽略关闭异常
            }
        }
    }
}
