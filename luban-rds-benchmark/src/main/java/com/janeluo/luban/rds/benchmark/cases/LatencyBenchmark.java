package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 延迟分布基准测试
 * 
 * 收集延迟样本并计算 P50, P90, P99, P99.9 延迟分布
 */
public class LatencyBenchmark implements Benchmark {

    private static final Logger logger = LoggerFactory.getLogger(LatencyBenchmark.class);

    // 延迟样本存储（使用数组避免并发集合开销）
    private long[] latencySamples;
    private int sampleIndex;
    private final Object sampleLock = new Object();

    @Override
    public String getName() {
        return "Latency Distribution";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        // 初始化延迟样本数组
        int sampleSize = config.getTotalOperations() * 2; // SET + GET
        latencySamples = new long[sampleSize];
        sampleIndex = 0;

        // 测试连接
        try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
            String response = jedis.ping();
            if (!"PONG".equals(response)) {
                throw new RuntimeException("Ping failed: " + response);
            }
        }

        logger.info("Latency benchmark setup complete, sample size: {}", sampleSize);
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        int threads = config.getThreads();
        int operations = config.getTotalOperations();
        int opsPerThread = operations / threads;

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threads);
        AtomicLong totalOps = new AtomicLong(0);
        AtomicLong errorCount = new AtomicLong(0);

        ExecutorService executor = Executors.newFixedThreadPool(threads);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
                        for (int i = 0; i < opsPerThread; i++) {
                            try {
                                String key = "latency-key-" + threadId + "-" + i;

                                // SET 操作延迟
                                long setStart = System.nanoTime();
                                jedis.set(key, "value-" + i);
                                long setLatency = System.nanoTime() - setStart;
                                recordLatency(setLatency);
                                totalOps.incrementAndGet();

                                // GET 操作延迟
                                long getStart = System.nanoTime();
                                jedis.get(key);
                                long getLatency = System.nanoTime() - getStart;
                                recordLatency(getLatency);
                                totalOps.incrementAndGet();
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

        startLatch.countDown();
        endLatch.await(5, TimeUnit.MINUTES);

        long endTime = System.currentTimeMillis();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        double durationSeconds = (endTime - startTime) / 1000.0;

        // 计算延迟分布
        LatencyDistribution distribution = calculateLatencyDistribution();

        logger.info("Latency benchmark completed:");
        logger.info("  P50: {:.3f} ms", distribution.p50);
        logger.info("  P90: {:.3f} ms", distribution.p90);
        logger.info("  P99: {:.3f} ms", distribution.p99);
        logger.info("  P99.9: {:.3f} ms", distribution.p999);
        logger.info("  Max: {:.3f} ms", distribution.max);
        logger.info("  Min: {:.3f} ms", distribution.min);
        logger.info("  Avg: {:.3f} ms", distribution.avg);

        return new LatencyBenchmarkResult(
                getName(),
                totalOps.get(),
                durationSeconds,
                errorCount.get(),
                distribution
        );
    }

    /**
     * 记录延迟样本
     */
    private void recordLatency(long latencyNanos) {
        synchronized (sampleLock) {
            if (sampleIndex < latencySamples.length) {
                latencySamples[sampleIndex++] = latencyNanos;
            }
        }
    }

    /**
     * 计算延迟分布
     */
    private LatencyDistribution calculateLatencyDistribution() {
        // 复制并排序有效样本
        int validCount = sampleIndex;
        if (validCount == 0) {
            return new LatencyDistribution(0, 0, 0, 0, 0, 0, 0);
        }

        long[] samples = Arrays.copyOf(latencySamples, validCount);
        Arrays.sort(samples);

        // 计算百分位延迟（转换为毫秒）
        double p50 = getPercentile(samples, 50) / 1_000_000.0;
        double p90 = getPercentile(samples, 90) / 1_000_000.0;
        double p99 = getPercentile(samples, 99) / 1_000_000.0;
        double p999 = getPercentile(samples, 99.9) / 1_000_000.0;
        double min = samples[0] / 1_000_000.0;
        double max = samples[validCount - 1] / 1_000_000.0;

        // 计算平均延迟
        double sum = 0;
        for (int i = 0; i < validCount; i++) {
            sum += samples[i];
        }
        double avg = (sum / validCount) / 1_000_000.0;

        return new LatencyDistribution(p50, p90, p99, p999, min, max, avg);
    }

    /**
     * 获取指定百分位的值
     */
    private long getPercentile(long[] sortedSamples, double percentile) {
        if (sortedSamples.length == 0) {
            return 0;
        }
        double rank = percentile / 100.0 * (sortedSamples.length - 1);
        int lower = (int) Math.floor(rank);
        int upper = (int) Math.ceil(rank);
        
        if (lower == upper) {
            return sortedSamples[lower];
        }
        
        // 线性插值
        double fraction = rank - lower;
        return (long) (sortedSamples[lower] + fraction * (sortedSamples[upper] - sortedSamples[lower]));
    }

    @Override
    public void teardown() throws Exception {
        latencySamples = null;
        sampleIndex = 0;
        logger.info("Latency benchmark teardown complete");
    }

    /**
     * 延迟分布数据结构
     */
    public static class LatencyDistribution {
        public final double p50;
        public final double p90;
        public final double p99;
        public final double p999;
        public final double min;
        public final double max;
        public final double avg;

        public LatencyDistribution(double p50, double p90, double p99, double p999, 
                                   double min, double max, double avg) {
            this.p50 = p50;
            this.p90 = p90;
            this.p99 = p99;
            this.p999 = p999;
            this.min = min;
            this.max = max;
            this.avg = avg;
        }

        @Override
        public String toString() {
            return String.format(
                    "P50=%.3fms, P90=%.3fms, P99=%.3fms, P99.9=%.3fms, Min=%.3fms, Max=%.3fms, Avg=%.3fms",
                    p50, p90, p99, p999, min, max, avg
            );
        }
    }

    /**
     * 延迟基准测试结果
     */
    public static class LatencyBenchmarkResult extends BenchmarkResult {
        private final LatencyDistribution distribution;

        public LatencyBenchmarkResult(String name, long operations, double durationSeconds,
                                      long errorCount, LatencyDistribution distribution) {
            super(name, operations, durationSeconds, errorCount);
            this.distribution = distribution;
        }

        public LatencyDistribution getDistribution() {
            return distribution;
        }

        @Override
        public String toString() {
            return String.format("%-20s: %,15.0f ops/sec | %s | Errors: %d",
                    getName(), getOpsPerSec(), distribution.toString(), getErrorCount());
        }
    }
}
