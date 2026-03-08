package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * 内存稳定性长期基准测试（72小时）
 * 
 * 持续运行测试以验证内存使用稳定性和内存泄漏检测
 */
public class MemoryStabilityBenchmark implements Benchmark {

    private static final Logger logger = LoggerFactory.getLogger(MemoryStabilityBenchmark.class);

    // 默认测试持续时间（小时）
    private static final long DEFAULT_TEST_DURATION_HOURS = 72;

    // 每小时报告间隔
    private static final long REPORT_INTERVAL_HOURS = 1;

    // 内存统计报告间隔（分钟）
    private static final long MEMORY_REPORT_INTERVAL_MINUTES = 10;

    @Override
    public String getName() {
        return "Memory Stability (72 hours)";
    }

    @Override
    public void setup(BenchmarkConfig config) throws Exception {
        // 测试连接
        try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
            String response = jedis.ping();
            if (!"PONG".equals(response)) {
                throw new RuntimeException("Ping failed: " + response);
            }
        }
        logger.info("Memory stability benchmark setup complete");
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        long testDurationHours = config.getDurationSeconds() > 0 
                ? config.getDurationSeconds() / 3600 
                : DEFAULT_TEST_DURATION_HOURS;
        
        long testDurationMs = TimeUnit.HOURS.toMillis(testDurationHours);
        long startTime = System.currentTimeMillis();
        long endTime = startTime + testDurationMs;

        Random random = new Random();
        long totalOps = 0;
        long errorCount = 0;
        long lastReportTime = startTime;
        long lastMemoryReportTime = startTime;

        // 存储每小时统计数据
        List<HourlyStats> hourlyStatsList = new ArrayList<>();

        logger.info("Starting memory stability test for {} hours", testDurationHours);

        try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
            while (System.currentTimeMillis() < endTime) {
                try {
                    // 执行随机操作
                    String key = "stability-key-" + random.nextInt(10000);
                    String value = "value-" + random.nextInt(1000000);

                    switch (random.nextInt(10)) {
                        case 0:
                            jedis.set(key, value);
                            break;
                        case 1:
                            jedis.get(key);
                            break;
                        case 2:
                            jedis.del(key);
                            break;
                        case 3:
                            jedis.hset("hash-" + key, "field", value);
                            break;
                        case 4:
                            jedis.hget("hash-" + key, "field");
                            break;
                        case 5:
                            jedis.lpush("list-" + key, value);
                            break;
                        case 6:
                            jedis.lpop("list-" + key);
                            break;
                        case 7:
                            jedis.sadd("set-" + key, value);
                            break;
                        case 8:
                            jedis.spop("set-" + key);
                            break;
                        case 9:
                            jedis.incr("counter-" + key);
                            break;
                    }

                    totalOps++;

                    // 每小时报告
                    if (System.currentTimeMillis() - lastReportTime >= TimeUnit.HOURS.toMillis(REPORT_INTERVAL_HOURS)) {
                        HourlyStats stats = collectHourlyStats(jedis, totalOps, startTime);
                        hourlyStatsList.add(stats);
                        logger.info("[Hourly Report] Hour {}: {} ops, Memory: {}", 
                                hourlyStatsList.size(), totalOps, stats.memoryInfo);
                        lastReportTime = System.currentTimeMillis();
                    }

                    // 每10分钟报告内存状态
                    if (System.currentTimeMillis() - lastMemoryReportTime >= TimeUnit.MINUTES.toMillis(MEMORY_REPORT_INTERVAL_MINUTES)) {
                        String memoryInfo = getMemoryInfo(jedis);
                        logger.debug("[Memory Report] {}", memoryInfo);
                        lastMemoryReportTime = System.currentTimeMillis();
                    }

                    // 小延迟避免过度占用CPU
                    Thread.sleep(1);

                } catch (Exception e) {
                    errorCount++;
                    if (errorCount % 100 == 0) {
                        logger.warn("Error count: {}", errorCount, e);
                    }
                }
            }
        }

        long actualEndTime = System.currentTimeMillis();
        long duration = actualEndTime - startTime;
        double durationSeconds = duration / 1000.0;
        double throughput = totalOps / durationSeconds;

        // 输出最终统计
        logger.info("Memory stability test completed:");
        logger.info("  Total operations: {}", totalOps);
        logger.info("  Duration: {} hours", duration / (1000.0 * 3600));
        logger.info("  Throughput: {:.2f} ops/sec", throughput);
        logger.info("  Errors: {}", errorCount);

        // 输出每小时统计摘要
        if (!hourlyStatsList.isEmpty()) {
            logger.info("Hourly statistics:");
            for (HourlyStats stats : hourlyStatsList) {
                logger.info("  Hour {}: {} ops", stats.hour, stats.operations);
            }
        }

        return new MemoryStabilityResult(
                getName(),
                totalOps,
                durationSeconds,
                errorCount,
                hourlyStatsList
        );
    }

    /**
     * 收集每小时统计数据
     */
    private HourlyStats collectHourlyStats(Jedis jedis, long totalOps, long startTime) {
        int hour = (int) ((System.currentTimeMillis() - startTime) / (1000 * 3600)) + 1;
        String memoryInfo = getMemoryInfo(jedis);
        return new HourlyStats(hour, totalOps, memoryInfo);
    }

    /**
     * 获取内存信息
     */
    private String getMemoryInfo(Jedis jedis) {
        try {
            String info = jedis.info("memory");
            // 提取关键内存指标
            StringBuilder sb = new StringBuilder();
            for (String line : info.split("\r\n")) {
                if (line.startsWith("used_memory:") ||
                    line.startsWith("used_memory_human:") ||
                    line.startsWith("used_memory_peak:") ||
                    line.startsWith("used_memory_peak_human:") ||
                    line.startsWith("used_memory_rss:") ||
                    line.startsWith("mem_fragmentation_ratio:")) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(line.trim());
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to get memory info: " + e.getMessage();
        }
    }

    @Override
    public void teardown() throws Exception {
        logger.info("Memory stability benchmark teardown complete");
    }

    /**
     * 每小时统计数据
     */
    public static class HourlyStats {
        public final int hour;
        public final long operations;
        public final String memoryInfo;

        public HourlyStats(int hour, long operations, String memoryInfo) {
            this.hour = hour;
            this.operations = operations;
            this.memoryInfo = memoryInfo;
        }
    }

    /**
     * 内存稳定性测试结果
     */
    public static class MemoryStabilityResult extends BenchmarkResult {
        private final List<HourlyStats> hourlyStats;

        public MemoryStabilityResult(String name, long operations, double durationSeconds,
                                     long errorCount, List<HourlyStats> hourlyStats) {
            super(name, operations, durationSeconds, errorCount);
            this.hourlyStats = hourlyStats;
        }

        public List<HourlyStats> getHourlyStats() {
            return hourlyStats;
        }

        @Override
        public String toString() {
            return String.format("%-25s: %,15d ops | Duration: %.1f hours | Errors: %d",
                    getName(), getOperations(), getDurationSeconds() / 3600, getErrorCount());
        }
    }
}
