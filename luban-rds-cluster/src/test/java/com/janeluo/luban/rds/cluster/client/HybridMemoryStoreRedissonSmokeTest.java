package com.janeluo.luban.rds.cluster.client;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.ClusterServersConfig;
import org.redisson.config.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HybridMemoryStore 冒烟测试（真实 mesh 集群 + Redisson 集群模式客户端）。
 * <p>
 * 前置条件：test/mesh 下 3 节点（9736/9737/9738）已用 hybrid 模式启动
 * （配置 memory-store-kind hybrid，日志出现 "使用 HybridMemoryStore"）。
 * </p>
 * <p>
 * 覆盖场景：
 * <ul>
 *   <li>大 value string（≥256B 阈值 → OffHeapStringEngine 堆外路径）</li>
 *   <li>小 value string（<256B → OnHeapStructEngine 堆上路径）</li>
 *   <li>hash / list / set（堆上结构路径，Redisson 实际业务主要类型）</li>
 *   <li>同 key 类型切换（string → hash → string，验证路由正确性）</li>
 *   <li>TTL 过期</li>
 *   <li>MOVED 重定向（Redisson 集群模式自动跟随 Leader）</li>
 *   <li>并发读写</li>
 * </ul>
 * </p>
 */
class HybridMemoryStoreRedissonSmokeTest {

    private static final String[] NODES = {
            "redis://127.0.0.1:9736",
            "redis://127.0.0.1:9737",
            "redis://127.0.0.1:9738"
    };

    private static final int OFFHEAP_THRESHOLD = 256;

    private static RedissonClient redisson;

    @BeforeAll
    static void setUp() {
        Config config = new Config();
        ClusterServersConfig clusterConfig = config.useClusterServers();
        clusterConfig.addNodeAddress(NODES);
        clusterConfig.setConnectTimeout(5000);
        clusterConfig.setTimeout(10000);
        clusterConfig.setRetryAttempts(3);
        clusterConfig.setRetryInterval(500);
        // StringCodec：与 Luban-RDS 内部 String 存储语义一致，避免二进制/序列化差异干扰冒烟
        config.setCodec(new StringCodec());
        redisson = Redisson.create(config);
    }

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
        }
    }

    // ==================== 大 value string（堆外路径）====================

    @Test
    @DisplayName("大 value string 读写一致性（1KB/16KB/64KB，堆外路径）")
    void testLargeStringOffHeapPath() {
        int[] sizes = {1024, 16 * 1024, 64 * 1024};
        for (int size : sizes) {
            String key = "smoke:offheap:str:" + size;
            String value = randomString(size);
            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(value);
            String got = bucket.get();
            assertEquals(value, got, "size=" + size + " 读写不一致");
            assertEquals(size, got.getBytes(StandardCharsets.UTF_8).length);
            bucket.delete();
        }
    }

    @Test
    @DisplayName("大 value 二进制数据（非 ASCII，验证 UTF-8 字节安全）")
    void testLargeBinaryString() {
        String key = "smoke:offheap:bin";
        StringBuilder sb = new StringBuilder();
        // 混入中文/emoji 等多字节字符，凑满 4KB（按字符数凑，字节数随编码变化）
        String chunk = "中文数据☃❄测试🎈";
        while (sb.length() < 4096) {
            sb.append(chunk);
        }
        String value = sb.substring(0, 4096);
        RBucket<String> bucket = redisson.getBucket(key);
        bucket.set(value);
        assertEquals(value, bucket.get());
        assertTrue(value.getBytes(StandardCharsets.UTF_8).length > 4096,
                "多字节字符应使 UTF-8 字节数超过字符数");
        bucket.delete();
    }

    // ==================== 小 value string（堆上路径）====================

    @Test
    @DisplayName("小 value string 读写一致性（<256B 堆上路径）")
    void testSmallStringOnHeapPath() {
        for (int size : new int[]{8, 64, 200, 255}) {
            String key = "smoke:onheap:str:" + size;
            String value = randomString(size);
            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(value);
            assertEquals(value, bucket.get(), "size=" + size + " 读写不一致");
            bucket.delete();
        }
    }

    // ==================== hash / list / set（堆上结构）====================

    @Test
    @DisplayName("hash 多字段读写 + 大 field value")
    void testHashOperations() {
        String key = "smoke:hash:main";
        RMap<String, String> map = redisson.getMap(key);
        map.clear();

        // 常规字段
        map.put("sessionId", "abc-12345");
        map.put("userId", "u-9999");
        map.put("status", "ACTIVE");

        // 大 field value（hash 整体走堆上，value 再大也不进堆外——验证路由正确性）
        String bigFieldValue = randomString(8192);
        map.put("payload", bigFieldValue);

        assertEquals("ACTIVE", map.get("status"));
        assertEquals(bigFieldValue, map.get("payload"));
        assertEquals(4, map.size());
        assertTrue(map.containsKey("userId"));
        assertTrue(map.containsValue("ACTIVE"));

        map.remove("userId");
        assertEquals(3, map.size());
        assertFalse(map.containsKey("userId"));

        map.clear();
    }

    @Test
    @DisplayName("list 压入/读取/区间")
    void testListOperations() {
        String key = "smoke:list:main";
        RList<String> list = redisson.getList(key);
        list.clear();

        for (int i = 0; i < 50; i++) {
            list.add("item-" + i);
        }
        assertEquals(50, list.size());
        assertEquals("item-0", list.get(0));
        assertEquals("item-49", list.get(49));

        List<String> range = list.range(10, 19);
        assertEquals(10, range.size());
        assertEquals("item-10", range.get(0));

        list.remove(0);
        assertEquals(49, list.size());
        assertEquals("item-1", list.get(0));

        list.clear();
    }

    @Test
    @DisplayName("set 添加/去重/删除")
    void testSetOperations() {
        String key = "smoke:set:main";
        RSet<String> set = redisson.getSet(key);
        set.clear();

        for (int i = 0; i < 100; i++) {
            set.add("member-" + (i % 50)); // 故意重复
        }
        assertEquals(50, set.size());
        assertTrue(set.contains("member-7"));
        set.remove("member-7");
        assertFalse(set.contains("member-7"));
        set.clear();
    }

    // ==================== 类型切换（路由正确性）====================

    @Test
    @DisplayName("同 key 类型切换 string → hash → string（路由一致性）")
    void testTypeSwitchRouting() {
        String key = "smoke:typeswitch:key";

        // 1. string（大 value 进堆外）
        RBucket<String> bucket = redisson.getBucket(key);
        String big = randomString(4096);
        bucket.set(big);
        assertEquals(big, bucket.get());

        // 2. 同 key 转 hash（必须清空另一引擎，验证 clearOnHeapBeforeNonString）
        RMap<String, String> map = redisson.getMap(key);
        map.put("field1", "v1");
        map.put("field2", "v2");
        assertEquals(2, map.size());
        assertEquals("v1", map.get("field1"));

        // 3. 再转回 string（验证 clearOtherEngineBeforeNonString）
        String big2 = randomString(2048);
        bucket.set(big2);
        assertEquals(big2, bucket.get());

        bucket.delete();
    }

    // ==================== TTL 过期 ====================

    @Test
    @DisplayName("大 value string 带 TTL（堆外路径过期）")
    void testLargeStringExpire() throws InterruptedException {
        String key = "smoke:ttl:offheap";
        RBucket<String> bucket = redisson.getBucket(key);
        bucket.set(randomString(4096), 2, TimeUnit.SECONDS);

        long ttl = bucket.remainTimeToLive();
        assertTrue(ttl > 0 && ttl <= 2000, "TTL 异常: " + ttl);

        Thread.sleep(2300);
        assertFalse(bucket.isExists(), "堆外 key 过期后应不存在");
    }

    @Test
    @DisplayName("小 value string 带 TTL（堆上路径过期）")
    void testSmallStringExpire() throws InterruptedException {
        String key = "smoke:ttl:onheap";
        RBucket<String> bucket = redisson.getBucket(key);
        bucket.set("tiny", 2, TimeUnit.SECONDS);
        Thread.sleep(2300);
        assertFalse(bucket.isExists(), "堆上 key 过期后应不存在");
    }

    // ==================== MOVED 重定向 ====================

    @Test
    @DisplayName("多 key 分布写入（Redisson 集群模式自动路由/MOVED 跟随）")
    void testMovedRedirect() {
        for (int i = 0; i < 200; i++) {
            String key = "smoke:moved:" + i;
            String value = "value-" + i;
            RBucket<String> bucket = redisson.getBucket(key);
            bucket.set(value);
            assertEquals(value, bucket.get());
            bucket.delete();
        }
    }

    @Test
    @DisplayName("hash tag 键（同槽批量操作）")
    void testHashTag() {
        String tag = "smoke:user:10001";
        RMap<String, String> profile = redisson.getMap("{" + tag + "}:profile");
        profile.clear();
        profile.put("name", "Alice");
        profile.put("age", "30");

        RBucket<String> bio = redisson.getBucket("{" + tag + "}:bio");
        bio.set(randomString(2048)); // 大 value 堆外

        assertEquals("Alice", profile.get("name"));
        assertEquals(bio.get().length(), 2048);
        profile.clear();
        bio.delete();
    }

    // ==================== 并发读写 ====================

    @Test
    @DisplayName("16 线程并发读写混合负载（大/小 value + hash + list）")
    void testConcurrentMixedLoad() throws InterruptedException {
        int threads = 16;
        int opsPerThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    Random rnd = new Random(tid * 1000L);
                    for (int i = 0; i < opsPerThread; i++) {
                        int op = i % 4;
                        String key = "smoke:conc:" + tid + ":" + i;
                        switch (op) {
                            case 0: {
                                // 大 value string（堆外）
                                String v = randomString(1024 + rnd.nextInt(4096));
                                RBucket<String> b = redisson.getBucket(key);
                                b.set(v);
                                if (!v.equals(b.get())) failures.incrementAndGet();
                                break;
                            }
                            case 1: {
                                // 小 value string（堆上）
                                String v = "small-" + rnd.nextInt(1000);
                                RBucket<String> b = redisson.getBucket(key);
                                b.set(v);
                                if (!v.equals(b.get())) failures.incrementAndGet();
                                break;
                            }
                            case 2: {
                                // hash
                                RMap<String, String> m = redisson.getMap(key);
                                m.put("f1", "v-" + rnd.nextInt(1000));
                                if (!m.get("f1").startsWith("v-")) failures.incrementAndGet();
                                break;
                            }
                            default: {
                                // list
                                RList<String> l = redisson.getList(key);
                                l.add("item-" + rnd.nextInt(1000));
                                if (l.size() < 1) failures.incrementAndGet();
                                break;
                            }
                        }
                        // 清理，避免残留
                        if (rnd.nextInt(4) == 0) {
                            redisson.getBucket(key).delete();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                    System.err.println("并发任务异常: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(done.await(120, TimeUnit.SECONDS), "并发任务超时");
        pool.shutdown();
        assertEquals(0, failures.get(), "并发读写存在失败，共 " + failures.get() + " 个");
    }

    // ==================== 辅助 ====================

    private static String randomString(int length) {
        // 只生成 a-z 纯 ASCII，保证 1 字符 = 1 字节，避免多字节字符干扰字节数断言
        Random rnd = new Random(42);
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + rnd.nextInt(26)));
        }
        return sb.toString();
    }
}
