package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Transaction;

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
 * 并发事务测试
 * 测试多线程环境下的事务功能
 */
@DisplayName("Concurrent Transaction Tests")
class ConcurrentTransactionTest {

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
     * 创建 JedisPool
     */
    private JedisPool createJedisPool() {
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(30);
        poolConfig.setMaxIdle(15);
        poolConfig.setMinIdle(2);
        return new JedisPool(poolConfig, "localhost", port, 5000);
    }

    /**
     * 启动服务器
     */
    private void startServer() throws InterruptedException {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setWorkerThreads(4);
        config.setBusinessThreads(4);
        config.setPersistMode("rdb");
        config.setDir(testDataDir);
        config.setRdbSaveInterval(3600);

        server = new NettyRedisServer(config);
        server.start();
        
        // 等待服务器启动
        Thread.sleep(300);
        
        // 尝试连接确认服务器就绪
        int maxRetries = 10;
        for (int i = 0; i < maxRetries; i++) {
            try (Jedis jedis = new Jedis("localhost", port, 1000)) {
                jedis.ping();
                break;
            } catch (Exception e) {
                Thread.sleep(100);
            }
        }
        
        jedisPool = createJedisPool();
    }

    @Test
    @DisplayName("Test concurrent transactions")
    void testConcurrentTransactions() throws Exception {
        startServer();

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        List<String> errors = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        Transaction tx = jedis.multi();
                        tx.set("key-" + threadId, "value-" + threadId);
                        tx.get("key-" + threadId);
                        List<Object> results = tx.exec();
                        
                        if (results != null && results.size() == 2) {
                            successCount.incrementAndGet();
                        } else {
                            errors.add("Thread " + threadId + " got unexpected results: " + results);
                        }
                    }
                } catch (Exception e) {
                    errors.add("Thread " + threadId + " error: " + e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证结果
        assertEquals(threadCount, successCount.get(), "All transactions should succeed");
        assertTrue(errors.isEmpty(), "No errors should occur: " + String.join(", ", errors));

        // 验证数据正确性
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < threadCount; i++) {
                assertEquals("value-" + i, jedis.get("key-" + i), 
                        "Key " + i + " should have correct value");
            }
        }
    }

    @Test
    @DisplayName("Test WATCH with concurrent modification")
    void testWatchWithConcurrentModification() throws Exception {
        startServer();

        // 初始化测试 key
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("watch-key", "initial");
        }

        CountDownLatch watchLatch = new CountDownLatch(1);
        CountDownLatch modifyLatch = new CountDownLatch(1);
        CountDownLatch execLatch = new CountDownLatch(1);
        AtomicInteger watchAbortedCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 线程1: WATCH -> MULTI -> SET -> (等待修改) -> EXEC
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch("watch-key");
                watchLatch.countDown();
                
                // 等待另一个线程开始修改
                modifyLatch.await(5, TimeUnit.SECONDS);
                
                Transaction tx = jedis.multi();
                tx.set("watch-key", "modified-by-tx1");
                Thread.sleep(100); // 给线程2时间完成修改
                List<Object> results = tx.exec();
                
                if (results == null) {
                    // WATCH 被中止
                    watchAbortedCount.incrementAndGet();
                } else {
                    successCount.incrementAndGet();
                }
                execLatch.countDown();
            } catch (Exception e) {
                execLatch.countDown();
            }
        });

        // 线程2: 等待 WATCH 后修改 key
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                watchLatch.await(5, TimeUnit.SECONDS);
                modifyLatch.countDown();
                jedis.set("watch-key", "modified-by-tx2");
            } catch (Exception e) {
                // 忽略
            }
        });

        // 等待执行完成
        assertTrue(execLatch.await(10, TimeUnit.SECONDS), "Transaction should complete");
        
        executor.shutdown();

        // 验证 WATCH 正确检测到并发修改
        assertEquals(1, watchAbortedCount.get(), "Transaction should be aborted due to WATCH");
        assertEquals(0, successCount.get(), "Transaction should not succeed when key was modified");

        // 验证最终值是线程2设置的
        try (Jedis jedis = jedisPool.getResource()) {
            assertEquals("modified-by-tx2", jedis.get("watch-key"), 
                    "Key should have value set by thread 2");
        }
    }

    @Test
    @DisplayName("Test concurrent MULTI/EXEC on same keys")
    void testConcurrentMultiExecOnSameKeys() throws Exception {
        startServer();

        // 初始化计数器
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("counter", "0");
        }

        int threadCount = 5;
        int incrementsPerThread = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        for (int j = 0; j < incrementsPerThread; j++) {
                            Transaction tx = jedis.multi();
                            tx.incr("counter");
                            tx.exec();
                        }
                    }
                } catch (Exception e) {
                    // 忽略错误
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证最终计数器值
        try (Jedis jedis = jedisPool.getResource()) {
            String finalValue = jedis.get("counter");
            long expected = (long) threadCount * incrementsPerThread;
            assertEquals(expected, Long.parseLong(finalValue), 
                    "Counter should be incremented correctly by all threads");
        }
    }

    @Test
    @DisplayName("Test DISCARD under concurrent access")
    void testDiscardUnderConcurrentAccess() throws Exception {
        startServer();

        int threadCount = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger discardCount = new AtomicInteger(0);
        AtomicInteger execCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        Transaction tx = jedis.multi();
                        tx.set("discard-key-" + threadId, "value-" + threadId);
                        
                        // 一半线程 DISCARD，一半 EXEC
                        if (threadId % 2 == 0) {
                            tx.discard();
                            discardCount.incrementAndGet();
                        } else {
                            tx.exec();
                            execCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // 忽略错误
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证结果
        int expectedDiscard = (threadCount + 1) / 2; // 偶数索引的线程 DISCARD
        int expectedExec = threadCount / 2; // 奇数索引的线程 EXEC
        
        assertEquals(expectedDiscard, discardCount.get(), "Correct number of DISCARDs");
        assertEquals(expectedExec, execCount.get(), "Correct number of EXECs");

        // 验证 DISCARD 的 key 不存在，EXEC 的 key 存在
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < threadCount; i++) {
                if (i % 2 == 0) {
                    // DISCARD 的 key 不应该存在
                    assertNull(jedis.get("discard-key-" + i), 
                            "Key " + i + " should not exist after DISCARD");
                } else {
                    // EXEC 的 key 应该存在
                    assertEquals("value-" + i, jedis.get("discard-key-" + i), 
                            "Key " + i + " should exist after EXEC");
                }
            }
        }
    }

    @Test
    @DisplayName("Test transaction isolation between connections")
    void testTransactionIsolationBetweenConnections() throws Exception {
        startServer();

        try (Jedis jedis1 = jedisPool.getResource();
             Jedis jedis2 = jedisPool.getResource()) {

            // 连接1开始事务
            Transaction tx1 = jedis1.multi();
            tx1.set("isolation-key", "value-from-tx1");

            // 连接2读取（应该看不到事务中的修改）
            assertNull(jedis2.get("isolation-key"), 
                    "Connection 2 should not see uncommitted transaction");

            // 连接1提交事务
            tx1.exec();

            // 连接2现在应该能看到修改
            assertEquals("value-from-tx1", jedis2.get("isolation-key"), 
                    "Connection 2 should see committed transaction");
        }
    }

    @Test
    @DisplayName("Test multiple WATCH keys")
    void testMultipleWatchKeys() throws Exception {
        startServer();

        // 初始化多个 key
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("watch-key-1", "value1");
            jedis.set("watch-key-2", "value2");
            jedis.set("watch-key-3", "value3");
        }

        CountDownLatch watchLatch = new CountDownLatch(1);
        CountDownLatch modifyLatch = new CountDownLatch(1);
        CountDownLatch execLatch = new CountDownLatch(1);
        AtomicInteger abortedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 线程1: WATCH 多个 key
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch("watch-key-1", "watch-key-2", "watch-key-3");
                watchLatch.countDown();
                
                modifyLatch.await(5, TimeUnit.SECONDS);
                Thread.sleep(100);
                
                Transaction tx = jedis.multi();
                tx.set("watch-key-1", "modified-by-tx1");
                tx.set("watch-key-2", "modified-by-tx1");
                tx.set("watch-key-3", "modified-by-tx1");
                List<Object> results = tx.exec();
                
                if (results == null) {
                    abortedCount.incrementAndGet();
                }
                execLatch.countDown();
            } catch (Exception e) {
                execLatch.countDown();
            }
        });

        // 线程2: 修改其中一个 key
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                watchLatch.await(5, TimeUnit.SECONDS);
                modifyLatch.countDown();
                jedis.set("watch-key-2", "modified-by-tx2");
            } catch (Exception e) {
                // 忽略
            }
        });

        // 等待执行完成
        assertTrue(execLatch.await(10, TimeUnit.SECONDS), "Transaction should complete");
        
        executor.shutdown();

        // 验证 WATCH 检测到任一 key 的修改
        assertEquals(1, abortedCount.get(), "Transaction should be aborted when any watched key changes");
    }

    @Test
    @DisplayName("Test transaction with errors")
    void testTransactionWithErrors() throws Exception {
        startServer();

        try (Jedis jedis = jedisPool.getResource()) {
            Transaction tx = jedis.multi();
            tx.set("error-key-1", "value1");
            // 执行一个会失败的命令（对字符串执行 LPUSH）
            tx.lpush("error-key-1", "list-value");
            tx.set("error-key-2", "value2");
            List<Object> results = tx.exec();

            // 事务应该执行完成，但包含错误
            assertNotNull(results, "Transaction should complete");
            assertEquals(3, results.size(), "Should have 3 results");
            
            // 第一个和第三个命令应该成功
            assertEquals("OK", results.get(0));
            assertEquals("OK", results.get(2));
        }
    }

    @Test
    @DisplayName("Test concurrent transactions with WATCH race condition")
    void testConcurrentTransactionsWithWatchRaceCondition() throws Exception {
        startServer();

        // 初始化测试 key
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("race-key", "0");
        }

        // 使用更简单的测试方式：验证 WATCH 能够检测到并发修改
        AtomicInteger abortCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);

        // 创建两个连接
        try (Jedis jedis1 = jedisPool.getResource();
             Jedis jedis2 = jedisPool.getResource()) {
            
            // 连接1 WATCH key
            jedis1.watch("race-key");
            String value1 = jedis1.get("race-key");
            
            // 连接2 修改 key（在 WATCH 之后）
            jedis2.set("race-key", "modified-by-j2");
            
            // 连接1 尝试执行事务
            Transaction tx = jedis1.multi();
            tx.set("race-key", String.valueOf(Long.parseLong(value1) + 1));
            List<Object> results = tx.exec();
            
            if (results == null) {
                abortCount.incrementAndGet();
            } else {
                successCount.incrementAndGet();
            }
        }

        // 验证事务被中止
        assertEquals(1, abortCount.get(), "Transaction should be aborted due to WATCH");
        assertEquals(0, successCount.get(), "Transaction should not succeed when key was modified");

        // 验证最终值
        try (Jedis jedis = jedisPool.getResource()) {
            assertEquals("modified-by-j2", jedis.get("race-key"), 
                    "Key should have value set by jedis2");
        }
    }

    @Test
    @DisplayName("Test UNWATCH command")
    void testUnwatchCommand() throws Exception {
        startServer();

        // 初始化测试 key
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set("unwatch-key", "initial");
        }

        CountDownLatch watchLatch = new CountDownLatch(1);
        CountDownLatch modifyLatch = new CountDownLatch(1);
        CountDownLatch execLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // 线程1: WATCH -> UNWATCH -> MULTI -> SET -> EXEC
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.watch("unwatch-key");
                watchLatch.countDown();
                
                modifyLatch.await(5, TimeUnit.SECONDS);
                Thread.sleep(100);
                
                // UNWATCH 后，事务不会被中止
                jedis.unwatch();
                
                Transaction tx = jedis.multi();
                tx.set("unwatch-key", "modified-by-tx1");
                List<Object> results = tx.exec();
                
                if (results != null) {
                    successCount.incrementAndGet();
                }
                execLatch.countDown();
            } catch (Exception e) {
                execLatch.countDown();
            }
        });

        // 线程2: 修改 key
        executor.submit(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                watchLatch.await(5, TimeUnit.SECONDS);
                modifyLatch.countDown();
                jedis.set("unwatch-key", "modified-by-tx2");
            } catch (Exception e) {
                // 忽略
            }
        });

        // 等待执行完成
        assertTrue(execLatch.await(10, TimeUnit.SECONDS), "Transaction should complete");
        
        executor.shutdown();

        // 验证 UNWATCH 后事务不会被中止
        assertEquals(1, successCount.get(), "Transaction should succeed after UNWATCH");

        // 验证最终值是事务设置的（因为 UNWATCH 后不再监视）
        try (Jedis jedis = jedisPool.getResource()) {
            assertEquals("modified-by-tx1", jedis.get("unwatch-key"), 
                    "Key should have value set by transaction after UNWATCH");
        }
    }

    @Test
    @DisplayName("Test transaction with SELECT command")
    void testTransactionWithSelectCommand() throws Exception {
        startServer();

        // 在数据库 1 中执行事务
        try (Jedis jedis1 = jedisPool.getResource()) {
            jedis1.select(1);
            Transaction tx1 = jedis1.multi();
            tx1.set("tx-select-key-1", "value-in-db1");
            List<Object> results1 = tx1.exec();
            assertNotNull(results1, "Transaction in database 1 should complete");
        }

        // 在数据库 2 中执行事务
        try (Jedis jedis2 = jedisPool.getResource()) {
            jedis2.select(2);
            Transaction tx2 = jedis2.multi();
            tx2.set("tx-select-key-2", "value-in-db2");
            List<Object> results2 = tx2.exec();
            assertNotNull(results2, "Transaction in database 2 should complete");
        }

        // 验证数据库 1 中的值
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(1);
            assertEquals("value-in-db1", jedis.get("tx-select-key-1"), 
                    "Key should exist in database 1");
            assertNull(jedis.get("tx-select-key-2"), 
                    "Database 1 should not have key from database 2");
        }

        // 验证数据库 2 中的值
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.select(2);
            assertEquals("value-in-db2", jedis.get("tx-select-key-2"), 
                    "Key should exist in database 2");
            assertNull(jedis.get("tx-select-key-1"), 
                    "Database 2 should not have key from database 1");
        }
    }

    @Test
    @DisplayName("Test concurrent transactions on different databases")
    void testConcurrentTransactionsOnDifferentDatabases() throws Exception {
        startServer();

        int threadCount = 10;
        int databases = 5;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            final int db = threadId % databases;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = jedisPool.getResource()) {
                        jedis.select(db);
                        Transaction tx = jedis.multi();
                        tx.set("db-key-" + threadId, "value-" + threadId);
                        tx.get("db-key-" + threadId);
                        List<Object> results = tx.exec();
                        
                        if (results != null && results.size() == 2) {
                            successCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    // 忽略错误
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 同时启动所有线程
        startLatch.countDown();

        // 等待所有线程完成
        assertTrue(endLatch.await(30, TimeUnit.SECONDS), "All threads should complete in time");

        executor.shutdown();

        // 验证所有事务成功
        assertEquals(threadCount, successCount.get(), "All transactions should succeed");

        // 验证数据正确性
        try (Jedis jedis = jedisPool.getResource()) {
            for (int i = 0; i < threadCount; i++) {
                int db = i % databases;
                jedis.select(db);
                assertEquals("value-" + i, jedis.get("db-key-" + i), 
                        "Key " + i + " should have correct value in database " + db);
            }
        }
    }
}
