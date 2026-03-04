package com.janeluo.luban.rds.benchmark.core;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractBenchmark implements Benchmark {
    
    protected static final Logger logger = LoggerFactory.getLogger(AbstractBenchmark.class);

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        // Default setup: check connection
        try (Jedis jedis = createJedis(config)) {
            String response = jedis.ping();
            if (!"PONG".equals(response)) {
                throw new RuntimeException("Ping failed: " + response);
            }
        }
    }

    protected Jedis createJedis(BenchmarkConfig config) {
        return new Jedis(config.getHost(), config.getPort(), 10000);
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        int threads = config.getThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong actualOps = new AtomicLong(0);

        long startTime = System.currentTimeMillis();
        boolean isTimeBased = config.getDurationSeconds() > 0;
        long endTimeTarget = startTime + config.getDurationSeconds() * 1000L;
        long opsPerThread = config.getTotalOperations() / threads;

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Jedis workerJedis = createJedis(config)) {
                    int iteration = 0;
                    if (isTimeBased) {
                        while (System.currentTimeMillis() < endTimeTarget) {
                            try {
                                executeOperation(workerJedis, threadId, iteration++, config);
                                actualOps.incrementAndGet();
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                logger.error("Error in thread {}", threadId, e);
                            }
                        }
                    } else {
                        for (int i = 0; i < opsPerThread; i++) {
                            try {
                                executeOperation(workerJedis, threadId, i, config);
                                actualOps.incrementAndGet();
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                logger.error("Error in thread {}", threadId, e);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed to connect or execute", threadId, e);
                    // If connection fails, we might want to count missed operations or just log error
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long endTime = System.currentTimeMillis();
        executor.shutdown();

        double duration = (endTime - startTime) / 1000.0;
        return new BenchmarkResult(getName(), actualOps.get(), duration, errorCount.get());
    }

    @Override
    public void teardown() throws Exception {
        // Default teardown
    }

    protected abstract void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config);
}
