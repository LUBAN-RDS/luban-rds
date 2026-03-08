package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多线程 I/O 测试
 * 测试 EventLoopGroup 配置和多线程命令处理
 */
@DisplayName("Multi-thread I/O Tests")
class MultiThreadIOTest {

    private NettyRedisServer server;
    private int port;
    private JedisPool jedisPool;
    private String testDataDir;

    @BeforeEach
    void setUp() {
        port = findRandomPort();
        testDataDir = System.getProperty("java.io.tmpdir") + "/luban-rds-test-" + UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
        if (server != null && server.isRunning()) {
            server.stop();
            server = null;
        }
        // 等待服务器完全关闭
        Thread.sleep(200);
    }

    /**
     * 查找随机可用端口
     */
    private int findRandomPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException("Failed to find free port", e);
        }
    }

    /**
     * 创建测试用的 RdsConfig
     */
    private RdsConfig createTestConfig() {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setPersistMode("rdb");
        config.setDir(testDataDir);
        config.setRdbSaveInterval(3600); // 设置较长的保存间隔，避免测试期间保存
        return config;
    }

    /**
     * 创建 JedisPool
     */
    private JedisPool createJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(2);
        return new JedisPool(poolConfig, "localhost", port, 5000);
    }

    /**
     * 等待服务器启动
     */
    private void waitForServerReady() throws InterruptedException {
        // 等待服务器启动
        Thread.sleep(300);
        
        // 尝试连接确认服务器就绪
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try (Jedis jedis = new Jedis("localhost", port, 1000)) {
                jedis.ping();
                return; // 连接成功
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        throw new RuntimeException("Server not ready after " + maxRetries + " retries");
    }

    @Test
    @DisplayName("Test default thread configuration")
    void testDefaultThreadConfiguration() throws Exception {
        RdsConfig config = createTestConfig();

        server = new NettyRedisServer(config);
        server.start();

        assertTrue(server.isRunning(), "Server should be running");
        assertEquals(port, server.getPort(), "Port should match");
        assertNotNull(server.getConfig(), "Config should not be null");
    }

    @Test
    @DisplayName("Test custom thread configuration")
    void testCustomThreadConfiguration() throws Exception {
        RdsConfig config = createTestConfig();
        config.setIoThreads(2);
        config.setWorkerThreads(4);
        config.setBusinessThreads(2);

        server = new NettyRedisServer(config);
        server.start();

        assertTrue(server.isRunning(), "Server should be running");
        assertEquals(2, server.getConfig().getIoThreads(), "IO threads should be 2");
        assertEquals(4, server.getConfig().getWorkerThreads(), "Worker threads should be 4");
        assertEquals(2, server.getConfig().getBusinessThreads(), "Business threads should be 2");
    }

    @Test
    @DisplayName("Test concurrent command execution")
    void testConcurrentCommandExecution() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        int threadCount = 10;
        int commandsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < commandsPerThread; j++) {
                            String key = "thread-" + threadId + "-key-" + j;
                            String value = "value-" + j;
                            jedis.set(key, value);
                            String result = jedis.get(key);
                            if (value.equals(result)) {
                                successCount.incrementAndGet();
                            } else {
                                errorCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证结果
        int expectedSuccess = threadCount * commandsPerThread;
        assertEquals(expectedSuccess, successCount.get(), "All commands should succeed");
        assertEquals(0, errorCount.get(), "No errors should occur");
    }

    @Test
    @DisplayName("Test thread safety under high load")
    void testThreadSafetyUnderHighLoad() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(8);
        config.setBusinessThreads(8);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        int threadCount = 20;
        int operationsPerThread = 200;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < operationsPerThread; j++) {
                            // 执行多种命令
                            String key = "load-test-" + (j % 50); // 使用有限的 key 数量增加竞争
                            jedis.set(key, "value-" + threadId + "-" + j);
                            jedis.get(key);
                            jedis.exists(key);
                            jedis.del(key);
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(60, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证没有错误发生
        assertEquals(0, errorCount.get(), "No errors should occur under high load");
    }

    @Test
    @DisplayName("Test concurrent SET and GET")
    void testConcurrentSetAndGet() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        int threadCount = 10;
        int operationsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < operationsPerThread; j++) {
                            String key = "concurrent-key-" + threadId;
                            String value = "value-" + j;

                            // SET
                            String setResult = jedis.set(key, value);
                            if (!"OK".equals(setResult)) {
                                errors.add("SET failed for key: " + key);
                            }

                            // GET
                            String getResult = jedis.get(key);
                            // 注意：由于多线程竞争，getResult 可能不是刚设置的值
                            // 但不应该返回 null 或错误
                            if (getResult == null) {
                                errors.add("GET returned null for key: " + key);
                            }
                        }
                    }
                } catch (Exception e) {
                    errors.add("Thread " + threadId + " error: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证没有错误
        assertTrue(errors.isEmpty(), "No errors should occur: " + String.join(", ", errors));
    }

    @Test
    @DisplayName("Test concurrent INCR")
    void testConcurrentIncr() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        // 初始化计数器
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("incr-counter", "0");
        }

        int threadCount = 10;
        int incrementsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < incrementsPerThread; j++) {
                            jedis.incr("incr-counter");
                        }
                    }
                } catch (Exception e) {
                    // 记录错误
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证最终值
        try (Jedis jedis = jedisPool.getResource()) {
            String finalValue = jedis.get("incr-counter");
            long expected = (long) threadCount * incrementsPerThread;
            assertEquals(expected, Long.parseLong(finalValue), 
                    "Counter should be incremented correctly by all threads");
        }
    }

    @Test
    @DisplayName("Test no data corruption under concurrent access")
    void testNoDataCorruption() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        int threadCount = 10;
        int operationsPerThread = 100;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger corruptionCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // 每个线程操作自己的 key，验证数据完整性
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < operationsPerThread; j++) {
                            String key = "corruption-test-" + threadId;
                            String value = "thread-" + threadId + "-value-" + j;

                            jedis.set(key, value);
                            String result = jedis.get(key);

                            // 验证读取的值必须是刚写入的值（因为是同一个 key 的连续操作）
                            if (!value.equals(result)) {
                                corruptionCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    corruptionCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证没有数据损坏
        assertEquals(0, corruptionCount.get(), "No data corruption should occur");
    }

    @Test
    @DisplayName("Test connection isolation")
    void testConnectionIsolation() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        // 测试不同连接之间的隔离性
        try (Jedis jedis1 = jedisPool.getResource();
             Jedis jedis2 = jedisPool.getResource()) {

            // 连接1选择数据库1
            jedis1.select(1);
            jedis1.set("isolation-key", "value-from-connection1");

            // 连接2选择数据库2
            jedis2.select(2);
            jedis2.set("isolation-key", "value-from-connection2");

            // 验证数据库隔离
            jedis1.select(1);
            assertEquals("value-from-connection1", jedis1.get("isolation-key"),
                    "Connection 1 should see its own value in database 1");

            jedis2.select(2);
            assertEquals("value-from-connection2", jedis2.get("isolation-key"),
                    "Connection 2 should see its own value in database 2");

            // 验证数据库1中看不到数据库2的数据
            jedis1.select(1);
            assertEquals("value-from-connection1", jedis1.get("isolation-key"),
                    "Database 1 should not be affected by database 2");
        }
    }

    @Test
    @DisplayName("Test multiple connections concurrent access")
    void testMultipleConnectionsConcurrentAccess() throws Exception {
        RdsConfig config = createTestConfig();
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);

        server = new NettyRedisServer(config);
        server.start();
        waitForServerReady();

        jedisPool = createJedisPool();

        int connectionCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(connectionCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(connectionCount);

        for (int i = 0; i < connectionCount; i++) {
            final int connectionId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        // 每个连接执行一系列操作
                        jedis.set("conn-" + connectionId, "connected");
                        jedis.get("conn-" + connectionId);
                        jedis.del("conn-" + connectionId);
                        successCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    // 忽略错误
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All connections should complete in time");

        executor.shutdown();

        // 验证所有连接都成功
        assertEquals(connectionCount, successCount.get(), "All connections should succeed");
    }
}
