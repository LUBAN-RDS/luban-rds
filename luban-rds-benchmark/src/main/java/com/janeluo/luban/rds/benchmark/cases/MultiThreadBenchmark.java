package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程吞吐量基准测试
 * 
 * 测试在高并发场景下的系统吞吐量和延迟表现
 */
public class MultiThreadBenchmark implements Benchmark {

    private static final Logger logger = LoggerFactory.getLogger(MultiThreadBenchmark.class);

    private JedisPool pool;

    @Override
    public String getName() {
        return "Multi-thread Throughput";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        // 初始化连接池
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(config.getThreads() + 10);
        poolConfig.setMaxIdle(config.getThreads());
        poolConfig.setMinIdle(5);
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        pool = new JedisPool(poolConfig, config.getHost(), config.getPort(), 10000);

        // 测试连接
        try (Jedis jedis = pool.getResource()) {
            String response = jedis.ping();
            if (!"PONG".equals(response)) {
                throw new RuntimeException("Ping failed: " + response);
            }
        }

        logger.info("Multi-thread benchmark setup complete with {} threads", config.getThreads());
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        int threads = config.getThreads();
        int operations = config.getTotalOperations();
        int opsPerThread = operations / threads;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicLong totalOps = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < threads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = pool.getResource()) {
                        for (int j = 0; j < opsPerThread; j++) {
                            try {
                                long opStart = System.nanoTime();
                                String key = "benchmark-key-" + threadId + "-" + j;
                                jedis.set(key, "value-" + j);
                                jedis.get(key);
                                totalLatency.addAndGet(System.nanoTime() - opStart);
                                totalOps.addAndGet(2);
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                logger.debug("Operation error in thread {}", threadId, e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed", threadId, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 启动所有线程
        endLatch.await(5, TimeUnit.MINUTES); // 等待完成

        long endTime = System.currentTimeMillis();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long duration = endTime - startTime;
        double durationSeconds = duration / 1000.0;
        double throughput = (totalOps.get() / durationSeconds);
        double avgLatency = totalOps.get() > 0 
                ? (totalLatency.get() / (double) totalOps.get() / 1_000_000.0) 
                : 0; // ms

        logger.info("Multi-thread benchmark completed: {} ops, {} ms, {} errors", 
                totalOps.get(), duration, errorCount.get());

        return new BenchmarkResult(getName(), totalOps.get(), durationSeconds, errorCount.get());
    }

    /**
     * 运行不同并发级别的压力测试
     * 
     * @param config 基准测试配置
     * @return 各并发级别的测试结果列表
     */
    public List<StressTestResult> runStressTest(BenchmarkConfig config) throws Exception {
        List<StressTestResult> results = new ArrayList<>();
        int[] concurrencyLevels = {10, 50, 100, 200, 500};

        for (int concurrency : concurrencyLevels) {
            logger.info("Running stress test with {} concurrent connections", concurrency);
            
            BenchmarkConfig stressConfig = new BenchmarkConfig();
            stressConfig.setHost(config.getHost());
            stressConfig.setPort(config.getPort());
            stressConfig.setThreads(concurrency);
            stressConfig.setTotalOperations(config.getTotalOperations());

            setup(stressConfig);
            BenchmarkResult result = run(stressConfig);
            teardown();

            results.add(new StressTestResult(concurrency, result));
        }

        return results;
    }

    /**
     * 运行并发连接压力测试
     * 
     * @param config 基准测试配置
     * @param concurrency 并发连接数
     * @return 测试结果
     */
    public BenchmarkResult runConcurrencyTest(BenchmarkConfig config, int concurrency) throws Exception {
        logger.info("Running concurrency test with {} connections", concurrency);

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(concurrency + 10);
        poolConfig.setMaxIdle(concurrency);
        poolConfig.setMinIdle(5);

        JedisPool testPool = new JedisPool(poolConfig, config.getHost(), config.getPort(), 10000);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrency);
        AtomicLong totalOps = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        int opsPerConnection = config.getTotalOperations() / concurrency;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrency; i++) {
            final int connectionId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = testPool.getResource()) {
                        for (int j = 0; j < opsPerConnection; j++) {
                            try {
                                long opStart = System.nanoTime();
                                String key = "concurrent-key-" + connectionId + "-" + j;
                                jedis.set(key, "value-" + j);
                                jedis.get(key);
                                totalLatency.addAndGet(System.nanoTime() - opStart);
                                totalOps.addAndGet(2);
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Connection {} failed", connectionId, e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.MINUTES);

        long endTime = System.currentTimeMillis();
        executor.shutdown();
        testPool.close();

        double durationSeconds = (endTime - startTime) / 1000.0;
        return new BenchmarkResult("Concurrency-" + concurrency, totalOps.get(), durationSeconds, errorCount.get());
    }

    @Override
    public void teardown() throws Exception {
        if (pool != null) {
            pool.close();
            pool = null;
        }
        logger.info("Multi-thread benchmark teardown complete");
    }

    /**
     * 压力测试结果
     */
    public static class StressTestResult {
        private final int concurrency;
        private final BenchmarkResult result;

        public StressTestResult(int concurrency, BenchmarkResult result) {
            this.concurrency = concurrency;
            this.result = result;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public BenchmarkResult getResult() {
            return result;
        }

        @Override
        public String toString() {
            return String.format("Concurrency %d: %s", concurrency, result.toString());
        }
    }
}
