package com.janeluo.luban.rds.benchmark.cases;

import com.janeluo.luban.rds.benchmark.api.Benchmark;
import com.janeluo.luban.rds.benchmark.api.BenchmarkConfig;
import com.janeluo.luban.rds.benchmark.api.BenchmarkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 内存碎片率基准测试
 * 
 * 测试内存碎片化情况，通过分配和释放不同大小的数据来观察内存碎片率变化
 */
public class MemoryFragmentationBenchmark implements Benchmark {

    private static final Logger logger = LoggerFactory.getLogger(MemoryFragmentationBenchmark.class);

    // 测试阶段
    private static final int PHASE_ALLOCATE = 1;
    private static final int PHASE_PARTIAL_DELETE = 2;
    private static final int PHASE_REALLOCATE = 3;
    private static final int PHASE_CLEANUP = 4;

    @Override
    public String getName() {
        return "Memory Fragmentation";
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
        logger.info("Memory fragmentation benchmark setup complete");
    }

    @Override
    public BenchmarkResult run(BenchmarkConfig config) throws Exception {
        long startTime = System.currentTimeMillis();
        long totalOps = 0;
        long errorCount = 0;

        try (Jedis jedis = new Jedis(config.getHost(), config.getPort(), 10000)) {
            // 获取初始内存状态
            MemoryStats initialStats = getMemoryStats(jedis);
            logger.info("Initial memory stats: {}", initialStats);

            // 阶段1: 分配大量不同大小的数据
            logger.info("Phase 1: Allocating data...");
            totalOps += allocateData(jedis, config);
            MemoryStats afterAllocate = getMemoryStats(jedis);
            logger.info("After allocation: {}", afterAllocate);

            // 阶段2: 部分删除（造成碎片）
            logger.info("Phase 2: Partial deletion to create fragmentation...");
            totalOps += partialDelete(jedis, config);
            MemoryStats afterPartialDelete = getMemoryStats(jedis);
            logger.info("After partial deletion: {}", afterPartialDelete);

            // 阶段3: 重新分配（观察碎片影响）
            logger.info("Phase 3: Reallocating data...");
            totalOps += reallocateData(jedis, config);
            MemoryStats afterReallocate = getMemoryStats(jedis);
            logger.info("After reallocation: {}", afterReallocate);

            // 阶段4: 清理所有数据
            logger.info("Phase 4: Cleanup...");
            totalOps += cleanup(jedis);
            MemoryStats finalStats = getMemoryStats(jedis);
            logger.info("Final memory stats: {}", finalStats);

            // 计算碎片率变化
            double fragmentationIncrease = afterReallocate.fragmentationRatio - initialStats.fragmentationRatio;

            logger.info("Fragmentation analysis:");
            logger.info("  Initial fragmentation ratio: {:.2f}", initialStats.fragmentationRatio);
            logger.info("  Peak fragmentation ratio: {:.2f}", afterPartialDelete.fragmentationRatio);
            logger.info("  Final fragmentation ratio: {:.2f}", finalStats.fragmentationRatio);
            logger.info("  Fragmentation increase: {:.2f}", fragmentationIncrease);
        }

        long endTime = System.currentTimeMillis();
        double durationSeconds = (endTime - startTime) / 1000.0;

        return new BenchmarkResult(getName(), totalOps, durationSeconds, errorCount);
    }

    /**
     * 阶段1: 分配数据
     */
    private long allocateData(Jedis jedis, BenchmarkConfig config) {
        long ops = 0;
        Random random = new Random();
        int keyCount = config.getTotalOperations() / 10;

        // 分配不同大小的数据
        for (int i = 0; i < keyCount; i++) {
            String key = "frag-key-" + i;

            // 随机选择数据类型和大小
            int type = random.nextInt(5);
            int size = random.nextInt(config.getValueSize() * 10) + 100; // 100 - valueSize*10+100

            switch (type) {
                case 0: // String
                    jedis.set(key, generateRandomString(size));
                    ops++;
                    break;
                case 1: // Hash
                    Map<String, String> hash = new HashMap<>();
                    for (int j = 0; j < 5; j++) {
                        hash.put("field-" + j, generateRandomString(size / 5));
                    }
                    jedis.hset(key, hash);
                    ops++;
                    break;
                case 2: // List
                    for (int j = 0; j < 10; j++) {
                        jedis.lpush(key, generateRandomString(size / 10));
                    }
                    ops += 10;
                    break;
                case 3: // Set
                    for (int j = 0; j < 10; j++) {
                        jedis.sadd(key, generateRandomString(size / 10));
                    }
                    ops += 10;
                    break;
                case 4: // ZSet
                    for (int j = 0; j < 10; j++) {
                        jedis.zadd(key, j, generateRandomString(size / 10));
                    }
                    ops += 10;
                    break;
            }
        }

        return ops;
    }

    /**
     * 阶段2: 部分删除（造成碎片）
     */
    private long partialDelete(Jedis jedis, BenchmarkConfig config) {
        long ops = 0;
        int keyCount = config.getTotalOperations() / 10;

        // 删除每隔一个key，造成内存碎片
        for (int i = 0; i < keyCount; i += 2) {
            String key = "frag-key-" + i;
            Long deleted = jedis.del(key);
            if (deleted != null && deleted > 0) {
                ops++;
            }
        }

        return ops;
    }

    /**
     * 阶段3: 重新分配数据
     */
    private long reallocateData(Jedis jedis, BenchmarkConfig config) {
        long ops = 0;
        Random random = new Random();
        int keyCount = config.getTotalOperations() / 20;

        // 重新分配新数据（使用不同的key名）
        for (int i = 0; i < keyCount; i++) {
            String key = "frag-new-key-" + i;
            int size = random.nextInt(config.getValueSize() * 5) + 50;

            jedis.set(key, generateRandomString(size));
            ops++;
        }

        return ops;
    }

    /**
     * 阶段4: 清理所有数据
     */
    private long cleanup(Jedis jedis) {
        long ops = 0;

        // 删除所有测试key
        for (int i = 0; i < 10000; i++) {
            String key = "frag-key-" + i;
            Long deleted = jedis.del(key);
            if (deleted != null && deleted > 0) {
                ops++;
            }

            key = "frag-new-key-" + i;
            deleted = jedis.del(key);
            if (deleted != null && deleted > 0) {
                ops++;
            }
        }

        return ops;
    }

    /**
     * 生成随机字符串
     */
    private String generateRandomString(int length) {
        StringBuilder sb = new StringBuilder(length);
        Random random = new Random();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        
        return sb.toString();
    }

    /**
     * 获取内存统计信息
     */
    private MemoryStats getMemoryStats(Jedis jedis) {
        String info = jedis.info("memory");
        long usedMemory = 0;
        long usedMemoryRss = 0;
        long usedMemoryPeak = 0;
        double fragmentationRatio = 0;

        for (String line : info.split("\r\n")) {
            if (line.startsWith("used_memory:")) {
                usedMemory = Long.parseLong(line.split(":")[1]);
            } else if (line.startsWith("used_memory_rss:")) {
                usedMemoryRss = Long.parseLong(line.split(":")[1]);
            } else if (line.startsWith("used_memory_peak:")) {
                usedMemoryPeak = Long.parseLong(line.split(":")[1]);
            } else if (line.startsWith("mem_fragmentation_ratio:")) {
                fragmentationRatio = Double.parseDouble(line.split(":")[1]);
            }
        }

        return new MemoryStats(usedMemory, usedMemoryRss, usedMemoryPeak, fragmentationRatio);
    }

    @Override
    public void teardown() throws Exception {
        logger.info("Memory fragmentation benchmark teardown complete");
    }

    /**
     * 内存统计数据结构
     */
    public static class MemoryStats {
        public final long usedMemory;
        public final long usedMemoryRss;
        public final long usedMemoryPeak;
        public final double fragmentationRatio;

        public MemoryStats(long usedMemory, long usedMemoryRss, 
                          long usedMemoryPeak, double fragmentationRatio) {
            this.usedMemory = usedMemory;
            this.usedMemoryRss = usedMemoryRss;
            this.usedMemoryPeak = usedMemoryPeak;
            this.fragmentationRatio = fragmentationRatio;
        }

        @Override
        public String toString() {
            return String.format("used=%d bytes, rss=%d bytes, peak=%d bytes, frag_ratio=%.2f",
                    usedMemory, usedMemoryRss, usedMemoryPeak, fragmentationRatio);
        }
    }
}
