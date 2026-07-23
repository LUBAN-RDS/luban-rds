package com.janeluo.luban.rds.cluster.client;

import com.janeluo.luban.rds.cluster.testinfra.EmbeddedCluster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RFuture;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Redisson Cluster 客户端兼容性测试
 * <p>
 * 启动嵌入式 Luban-RDS 集群（3 节点），用 Redisson 客户端验证集群协议兼容性。
 * </p>
 */
class RedissonClusterCompatibilityTest {

    private static final int BASE_PORT = 17200;
    private static EmbeddedCluster testCluster;
    private static RedissonClient redisson;

    @BeforeAll
    static void setUp() {
        testCluster = EmbeddedCluster.builder()
                .nodes(3)
                .basePort(BASE_PORT)
                .build();
        testCluster.start();
        testCluster.assignSlotsEvenly();

        Config config = new Config();
        ClusterServersConfig clusterConfig = config.useClusterServers();

        clusterConfig.addNodeAddress(
                "redis://127.0.0.1:" + BASE_PORT,
                "redis://127.0.0.1:" + (BASE_PORT + 1),
                "redis://127.0.0.1:" + (BASE_PORT + 2)
        );

        clusterConfig.setConnectTimeout(5000);
        clusterConfig.setTimeout(3000);
        clusterConfig.setRetryAttempts(3);
        clusterConfig.setRetryInterval(1500);

        redisson = Redisson.create(config);
    }

    @Test
    @DisplayName("测试基本读写操作")
    void testBasicReadWrite() {
        String key = "test:redisson:key";
        String value = "test-value-" + System.currentTimeMillis();

        RBucket<String> bucket = redisson.getBucket(key);

        bucket.set(value);
        assertEquals(value, bucket.get());

        bucket.delete();
        assertFalse(bucket.isExists());
    }

    @Test
    @DisplayName("测试 Map 操作")
    void testMapOperations() {
        String key = "test:redisson:map";
        RMap<String, String> map = redisson.getMap(key);

        map.put("field1", "value1");
        map.put("field2", "value2");
        map.put("field3", "value3");

        assertEquals("value1", map.get("field1"));
        assertEquals(3, map.size());

        assertTrue(map.containsKey("field1"));
        assertTrue(map.containsValue("value2"));

        map.remove("field1");
        assertEquals(2, map.size());

        map.delete();
    }

    @Test
    @DisplayName("测试 List 操作")
    void testListOperations() {
        String key = "test:redisson:list";
        RList<String> list = redisson.getList(key);

        list.add("item1");
        list.add("item2");
        list.add("item3");

        assertEquals(3, list.size());
        assertEquals("item1", list.get(0));
        assertEquals("item2", list.get(1));
        assertEquals("item3", list.get(2));

        list.remove(0);
        assertEquals(2, list.size());
        assertEquals("item2", list.get(0));

        list.delete();
    }

    @Test
    @DisplayName("测试 Set 操作")
    void testSetOperations() {
        String key = "test:redisson:set";
        RSet<String> set = redisson.getSet(key);

        set.add("member1");
        set.add("member2");
        set.add("member3");

        assertEquals(3, set.size());
        assertTrue(set.contains("member1"));
        assertTrue(set.contains("member2"));

        set.remove("member1");
        assertEquals(2, set.size());
        assertFalse(set.contains("member1"));

        set.delete();
    }

    @Test
    @DisplayName("测试 SortedSet 操作")
    void testSortedSetOperations() {
        String key = "test:redisson:sortedset";
        RSortedSet<String> sortedSet = redisson.getSortedSet(key);

        sortedSet.add("member3");
        sortedSet.add("member1");
        sortedSet.add("member2");

        assertEquals(3, sortedSet.size());

        String first = sortedSet.first();
        String last = sortedSet.last();
        assertEquals("member1", first);
        assertEquals("member3", last);

        sortedSet.delete();
    }

    @Test
    @DisplayName("测试过期时间")
    void testExpire() throws InterruptedException {
        String key = "test:redisson:expire";
        RBucket<String> bucket = redisson.getBucket(key);

        bucket.set("value", 5, TimeUnit.SECONDS);

        long ttl = bucket.remainTimeToLive();
        assertTrue(ttl > 0 && ttl <= 5000);

        Thread.sleep(100);
        ttl = bucket.remainTimeToLive();
        assertTrue(ttl > 0 && ttl < 5000);

        bucket.delete();
    }

    @Test
    @DisplayName("测试异步操作")
    void testAsyncOperations() throws Exception {
        String key = "test:redisson:async";
        RBucket<String> bucket = redisson.getBucket(key);

        RFuture<Void> setFuture = bucket.setAsync("async-value");
        setFuture.toCompletableFuture().get(5, TimeUnit.SECONDS);

        RFuture<String> getFuture = bucket.getAsync();
        String value = getFuture.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals("async-value", value);

        RFuture<Boolean> deleteFuture = bucket.deleteAsync();
        Boolean deleted = deleteFuture.toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(deleted);
    }

    @Test
    @DisplayName("测试 MOVED 重定向处理")
    void testMovedRedirect() {
        for (int i = 0; i < 10; i++) {
            String key = "test:redisson:redirect:" + i;
            String value = "value-" + i;

            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(value);
            assertEquals(value, bucket.get());
            bucket.delete();
        }
    }

    @Test
    @DisplayName("测试 Hash Tag")
    void testHashTag() {
        String tag = "user:1000";

        RBucket<String> nameBucket = redisson.getBucket("{" + tag + "}:name");
        RBucket<String> ageBucket = redisson.getBucket("{" + tag + "}:age");
        RBucket<String> emailBucket = redisson.getBucket("{" + tag + "}:email");

        nameBucket.set("Alice");
        ageBucket.set("25");
        emailBucket.set("alice@example.com");

        assertEquals("Alice", nameBucket.get());
        assertEquals("25", ageBucket.get());
        assertEquals("alice@example.com", emailBucket.get());

        nameBucket.delete();
        ageBucket.delete();
        emailBucket.delete();
    }

    @Test
    @DisplayName("测试原子操作")
    void testAtomicOperations() {
        String key = "test:redisson:atomic";
        RBucket<String> bucket = redisson.getBucket(key);

        bucket.set("0");

        bucket.compareAndSet("0", "1");
        assertEquals("1", bucket.get());

        bucket.compareAndSet("0", "2");
        assertEquals("1", bucket.get());

        bucket.delete();
    }

    @Test
    @DisplayName("测试 EXISTS 和 DEL 操作")
    void testExistsAndDel() {
        String key = "test:redisson:exists";
        RBucket<String> bucket = redisson.getBucket(key);

        bucket.set("value");
        assertTrue(bucket.isExists());

        bucket.delete();
        assertFalse(bucket.isExists());
    }

    @Test
    @DisplayName("测试集群连接状态")
    void testClusterConnection() {
        String key = "test:redisson:connection";
        RBucket<String> bucket = redisson.getBucket(key);

        bucket.set("test");
        assertNotNull(bucket.get());
        bucket.delete();

        assertTrue(redisson.getNodesGroup().getNodes().size() > 0);
    }

    @Test
    @DisplayName("测试多数据类型")
    void testMultipleDataTypes() {
        String prefix = "test:redisson:multi:";

        RBucket<String> stringBucket = redisson.getBucket(prefix + "string");
        stringBucket.set("string-value");
        assertEquals("string-value", stringBucket.get());

        RList<String> list = redisson.getList(prefix + "list");
        list.addAll(Arrays.asList("a", "b", "c"));
        assertEquals(3, list.size());

        RSet<String> set = redisson.getSet(prefix + "set");
        set.addAll(Arrays.asList("x", "y", "z"));
        assertEquals(3, set.size());

        RMap<String, String> map = redisson.getMap(prefix + "hash");
        map.put("k1", "v1");
        map.put("k2", "v2");
        assertEquals(2, map.size());

        stringBucket.delete();
        list.delete();
        set.delete();
        map.delete();
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            try {
                redisson.shutdown();
            } catch (Exception ignore) {
            }
        }
        if (testCluster != null) {
            testCluster.stop();
        }
    }
}
