package com.janeluo.luban.rds.benchmark.core;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

public abstract class AbstractBenchmark implements Benchmark {
    
    protected static final Logger logger = LoggerFactory.getLogger(AbstractBenchmark.class);
    protected JedisPool jedisPool;

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        // Default setup: check connection
        try (Jedis jedis = createJedis(config)) {
            String response = jedis.ping();
            if (!"PONG".equals(response)) {
                throw new RuntimeException("Ping failed: " + response);
            }
        }
        
        // Initialize connection pool if configured
        if (config.getConnectionPoolSize() > 0) {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(config.getConnectionPoolSize());
            poolConfig.setMaxIdle(config.getConnectionPoolSize());
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            jedisPool = new JedisPool(poolConfig, config.getHost(), config.getPort(), 10000);
        }
    }

    protected Jedis createJedis(BenchmarkConfig config) {
        return new Jedis(config.getHost(), config.getPort(), 10000);
    }

    protected Jedis getConnection(BenchmarkConfig config) {
        if (jedisPool != null) {
            return jedisPool.getResource();
        }
        return createJedis(config);
    }

    protected void returnConnection(Jedis jedis) {
        if (jedis != null) {
            if (jedisPool != null) {
                jedis.close();
            } else {
                jedis.disconnect();
            }
        }
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        int threads = config.getThreads();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicLong errorCount = new AtomicLong(0);
        AtomicLong actualOps = new AtomicLong(0);
        AtomicLong totalLatency = new AtomicLong(0);

        long startTime = System.currentTimeMillis();
        boolean isTimeBased = config.getDurationSeconds() > 0;
        long endTimeTarget = startTime + config.getDurationSeconds() * 1000L;
        long opsPerThread = config.getTotalOperations() / threads;
        boolean usePipeline = config.isPipelineEnabled();
        int batchSize = config.getPipelineBatchSize();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try (Jedis workerJedis = createJedis(config)) {
                    int iteration = 0;
                    if (isTimeBased) {
                        while (System.currentTimeMillis() < endTimeTarget) {
                            try {
                                if (usePipeline) {
                                    executePipelineOperation(workerJedis, threadId, iteration, config, batchSize, actualOps, totalLatency);
                                    iteration += batchSize;
                                } else {
                                    long opStart = System.nanoTime();
                                    executeOperation(workerJedis, threadId, iteration++, config);
                                    totalLatency.addAndGet(System.nanoTime() - opStart);
                                    actualOps.incrementAndGet();
                                }
                            } catch (Exception e) {
                                errorCount.incrementAndGet();
                                logger.error("Error in thread {}", threadId, e);
                            }
                        }
                    } else {
                        if (usePipeline) {
                            int batchCount = (int) Math.ceil((double) opsPerThread / batchSize);
                            for (int b = 0; b < batchCount; b++) {
                                try {
                                    int opsInBatch = (int) Math.min(batchSize, opsPerThread - (long) iteration);
                                    if (opsInBatch <= 0) break;
                                    executePipelineOperation(workerJedis, threadId, iteration, config, opsInBatch, actualOps, totalLatency);
                                    iteration += opsInBatch;
                                } catch (Exception e) {
                                    errorCount.incrementAndGet();
                                    logger.error("Error in thread {}", threadId, e);
                                }
                            }
                        } else {
                            for (int i = 0; i < opsPerThread; i++) {
                                try {
                                    long opStart = System.nanoTime();
                                    executeOperation(workerJedis, threadId, i, config);
                                    totalLatency.addAndGet(System.nanoTime() - opStart);
                                    actualOps.incrementAndGet();
                                } catch (Exception e) {
                                    errorCount.incrementAndGet();
                                    logger.error("Error in thread {}", threadId, e);
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.error("Thread {} failed to connect or execute", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long endTime = System.currentTimeMillis();
        executor.shutdown();

        double duration = (endTime - startTime) / 1000.0;
        double avgLatency = actualOps.get() > 0 ? totalLatency.get() / (double) actualOps.get() / 1_000_000.0 : 0;
        return new BenchmarkResult(getName(), actualOps.get(), duration, errorCount.get(), avgLatency, threads);
    }

    protected void executePipelineOperation(Jedis jedis, int threadId, int startIteration, 
            BenchmarkConfig config, int batchSize, AtomicLong actualOps, AtomicLong totalLatency) {
        Pipeline pipeline = jedis.pipelined();
        long opStart = System.nanoTime();
        
        for (int i = 0; i < batchSize; i++) {
            executePipelineCommand(pipeline, threadId, startIteration + i, config);
        }
        
        pipeline.sync();
        long latency = System.nanoTime() - opStart;
        totalLatency.addAndGet(latency);
        actualOps.addAndGet(batchSize);
    }

    protected void executePipelineCommand(Pipeline pipeline, int threadId, int iteration, BenchmarkConfig config) {
        String key = config.getKeyPrefix() + "_" + getName().toLowerCase() + "_" + threadId + "_" + iteration;
        String value = generateValue(config);
        pipeline.set(key, value);
    }

    protected String generateValue(BenchmarkConfig config) {
        StringBuilder sb = new StringBuilder(config.getValueSize());
        for (int i = 0; i < config.getValueSize(); i++) {
            sb.append('x');
        }
        return sb.toString();
    }

    @Override
    public void teardown() throws Exception {
        if (jedisPool != null) {
            jedisPool.close();
            jedisPool = null;
        }
    }

    protected abstract void executeOperation(Jedis jedis, int threadId, int iteration, BenchmarkConfig config);
}
