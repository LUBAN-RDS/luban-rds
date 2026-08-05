package com.janeluo.luban.rds.benchmark.mesh.comparison;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import com.janeluo.luban.rds.benchmark.mesh.MeshTestCluster;
import com.janeluo.luban.rds.client.NettyRedisClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 7.x 单机 vs mesh 3 节点强一致 性能对比（同一客户端栈、同一测量代码）。
 * <p>
 * 目标：量化 mesh 全栈（Raft 多数派复制 + 租约本地读）相对官方 Redis 7.0.12 的开销，
 * 并区分「复制协议开销」与「持久化 fsync 开销」，为此设置三组对照：
 * <ul>
 *   <li>{@code redis7-SET / redis7-GET}：官方 Redis 7.0.12 单机（save 已禁用、无 AOF），
 *       与 mesh 持久化关闭场景对齐；</li>
 *   <li>{@code redis7-SET-AOF-always}：Redis appendfsync always（每写 fsync），
 *       与 mesh 生产默认（每次 propose 全量序列化 raft-nodes.conf + fsync）对齐持久化语义；</li>
 *   <li>{@code mesh3-SET / mesh3-GET}：本仓库 mesh 3 节点（默认持久化关闭）；
 *       {@code -Dbench.mesh.persist=true} 时追加 {@code mesh3-SET-persist}（生产默认配置）。</li>
 * </ul>
 * 客户端统一 {@link NettyRedisClient}（Redis 直连）/ {@link ClusterAwareClient}（mesh 跟随 MOVED
 * 直连 Leader），32 线程 × 5000 ops，nanoTime 逐 op 采延迟，输出 L3-redis7-vs-mesh-*.json/.md。
 * </p>
 *
 * <p>前提：本机 7400（无持久化）/ 7410（AOF always）已起 Redis 7.0.12 实例（受控配置，
 * 禁用 RDB save 避免测试中途快照污染）。</p>
 */
public class RedisVsMeshBenchmark {
    private static final Logger log = LoggerFactory.getLogger(RedisVsMeshBenchmark.class);

    /** 每线程操作数（-Dbench.ops=N 可调，诊断/长跑用）。 */
    private final int operationsPerThread = Integer.getInteger("bench.ops", 5000);
    private final int threads = 32;
    private final int seedKeys = 10000;

    private static final String REDIS_HOST = "127.0.0.1";
    private static final int REDIS_PLAIN_PORT = 7400;
    private static final int REDIS_AOF_PORT = 7410;

    public void run() {
        List<BenchmarkResult> results = new ArrayList<>();
        try {
            results.add(runRedis("redis7-SET", REDIS_PLAIN_PORT, "SET", null));
            results.add(runRedis("redis7-GET", REDIS_PLAIN_PORT, "GET", "get:"));
            results.add(runRedis("redis7-SET-AOF-always", REDIS_AOF_PORT, "SET", null));

            results.add(runMesh("mesh3-SET", 7800, 10800, "SET", null, false, operationsPerThread));
            results.add(runMesh("mesh3-GET", 7900, 10900, "GET", "get:", false, operationsPerThread));
            if (Boolean.getBoolean("bench.mesh.persist")) {
                int persistOps = Math.max(200, operationsPerThread / 10);
                log.info("检测到 -Dbench.mesh.persist=true，追加 mesh3-SET-persist 场景（ops/线程={}）", persistOps);
                results.add(runMesh("mesh3-SET-persist", 8000, 11000, "SET", null, true, persistOps));
            }

            writeResults(results);
            printTable(results);
        } catch (Exception e) {
            log.error("基准测试失败", e);
        }
    }

    // ==================== Redis 7.x 场景 ====================

    private BenchmarkResult runRedis(String mode, int port, String scenario, String seedPrefix) throws Exception {
        log.info("=== {}: Redis 7.0.12@{} ===", mode, port);
        if (seedPrefix != null) {
            seedRedis(port, seedPrefix);
        }

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName(scenario);
        result.setMode(mode);
        result.setThreadCount(threads);

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        LatencyDistribution latency = new LatencyDistribution();
        AtomicInteger opsCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    NettyRedisClient client = new NettyRedisClient(REDIS_HOST, port);
                    client.connect();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = buildKey(threadId, i, seedPrefix);
                        long start = System.nanoTime();
                        if ("SET".equals(scenario)) {
                            client.set(key, "value");
                        } else {
                            client.get(key);
                        }
                        long elapsed = (System.nanoTime() - start) / 1000;
                        synchronized (latency) { latency.addSample(elapsed); }
                        opsCount.incrementAndGet();
                    }
                    client.disconnect();
                } catch (Exception e) {
                    log.error("线程 {} 失败", threadId, e);
                } finally { latch.countDown(); }
            });
        }
        latch.await();
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        fillResult(result, opsCount.get(), duration, latency);
        log.info("{}: {} ops, {} ops/s, p50={}μs", mode, opsCount.get(),
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    // ==================== mesh 场景 ====================

    private BenchmarkResult runMesh(String mode, int serviceBasePort, int busBasePort, String scenario,
                                    String seedPrefix, boolean persist, int opsPerThread) throws Exception {
        log.info("=== {}: mesh 3 节点(persist={}) ===", mode, persist);
        MeshTestCluster cluster = new MeshTestCluster(serviceBasePort, busBasePort);
        cluster.setPersistEnabled(persist);
        cluster.start();
        try {
            if (seedPrefix != null) {
                seedMesh(cluster, seedPrefix);
            }

            BenchmarkResult result = new BenchmarkResult();
            result.setScenarioName(scenario);
            result.setMode(mode);
            result.setThreadCount(threads);

            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            LatencyDistribution latency = new LatencyDistribution();
            AtomicInteger opsCount = new AtomicInteger(0);

            long startTime = System.currentTimeMillis();
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        ClusterAwareClient client = new ClusterAwareClient(REDIS_HOST, serviceBasePort);
                        client.connect();
                        for (int i = 0; i < opsPerThread; i++) {
                            String key = buildKey(threadId, i, seedPrefix);
                            long start = System.nanoTime();
                            if ("SET".equals(scenario)) {
                                client.set(key, "value");
                            } else {
                                client.get(key);
                            }
                            long elapsed = (System.nanoTime() - start) / 1000;
                            synchronized (latency) { latency.addSample(elapsed); }
                            opsCount.incrementAndGet();
                        }
                        client.disconnect();
                    } catch (Exception e) {
                        log.error("线程 {} 失败", threadId, e);
                    } finally { latch.countDown(); }
                });
            }
            latch.await();
            long duration = System.currentTimeMillis() - startTime;
            executor.shutdown();

            fillResult(result, opsCount.get(), duration, latency);
            log.info("{}: {} ops, {} ops/s, p50={}μs", mode, opsCount.get(),
                    result.getThroughputOpsPerSec(), latency.getP50());
            return result;
        } finally {
            cluster.stop();
        }
    }

    // ==================== 公共 ====================

    /** GET 场景 key 与 cluster 套件一致：分散到 seedKeys 个预置 key（threadId*997+i 错开热点）。 */
    private static String buildKey(int threadId, int i, String seedPrefix) {
        return seedPrefix == null
                ? "set:" + threadId + ":" + i
                : seedPrefix + ((threadId * 997 + i) % 10000);
    }

    private static void seedRedis(int port, String prefix) throws Exception {
        log.info("预置读基准 key: {}0..{}9999", prefix, prefix);
        NettyRedisClient seeder = new NettyRedisClient(REDIS_HOST, port);
        seeder.connect();
        for (int i = 0; i < 10000; i++) {
            seeder.set(prefix + i, "value");
        }
        seeder.disconnect();
    }

    private static void seedMesh(MeshTestCluster cluster, String prefix) {
        log.info("预置读基准 key（经 Raft 复制）: {}0..{}9999", prefix, prefix);
        ClusterAwareClient seeder = new ClusterAwareClient(REDIS_HOST, cluster.getBaseServicePort());
        seeder.connect();
        for (int i = 0; i < 10000; i++) {
            seeder.set(prefix + i, "value");
        }
        seeder.disconnect();
    }

    private static void fillResult(BenchmarkResult result, int totalOps, long durationMs, LatencyDistribution latency) {
        result.setTotalOperations(totalOps);
        result.setDurationMs(durationMs);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(totalOps / (durationMs / 1000.0));
    }

    // ==================== 输出 ====================

    private void writeResults(List<BenchmarkResult> results) throws Exception {
        Path metricsDir = Path.of("target", "test-metrics");
        Files.createDirectories(metricsDir);
        long ts = System.currentTimeMillis();

        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"layer\": \"L3\",\n");
        json.append("  \"title\": \"redis7-vs-mesh\",\n");
        json.append("  \"redis\": \"7.0.12 (Windows x64, 受控配置)\",\n");
        json.append("  \"mesh\": \"3 节点 Raft 强一致 (本仓库)\",\n");
        json.append("  \"threadCount\": ").append(threads).append(",\n");
        json.append("  \"results\": [\n");
        for (int i = 0; i < results.size(); i++) {
            BenchmarkResult r = results.get(i);
            json.append("    {\"scenario\":\"").append(r.getScenarioName())
                    .append("\",\"mode\":\"").append(r.getMode())
                    .append("\",\"totalOperations\":").append(r.getTotalOperations())
                    .append(",\"durationMs\":").append(r.getDurationMs())
                    .append(",\"throughputOpsPerSec\":").append((long) r.getThroughputOpsPerSec())
                    .append(",\"latency\":{\"p50\":").append(r.getLatency().getP50())
                    .append(",\"p95\":").append(r.getLatency().getP95())
                    .append(",\"p99\":").append(r.getLatency().getP99())
                    .append(",\"mean\":").append(r.getLatency().getMean())
                    .append(",\"max\":").append(r.getLatency().getMax()).append("}}");
            json.append(i < results.size() - 1 ? ",\n" : "\n");
        }
        json.append("  ]\n}\n");
        Path jsonFile = metricsDir.resolve("L3-redis7-vs-mesh-" + ts + ".json");
        Files.writeString(jsonFile, json.toString());
        log.info("结果已写入 {}", jsonFile.getFileName());

        StringBuilder md = new StringBuilder();
        md.append("| 场景 | ops/s | p50(μs) | p95(μs) | p99(μs) | mean(μs) | max(μs) |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (BenchmarkResult r : results) {
            md.append("| ").append(r.getMode())
                    .append(" | ").append((long) r.getThroughputOpsPerSec())
                    .append(" | ").append(r.getLatency().getP50())
                    .append(" | ").append(r.getLatency().getP95())
                    .append(" | ").append(r.getLatency().getP99())
                    .append(" | ").append(r.getLatency().getMean())
                    .append(" | ").append(r.getLatency().getMax()).append(" |\n");
        }
        Path mdFile = metricsDir.resolve("L3-redis7-vs-mesh-" + ts + ".md");
        Files.writeString(mdFile, md.toString());
        log.info("表格已写入 {}", mdFile.getFileName());
    }

    private void printTable(List<BenchmarkResult> results) {
        System.out.println();
        System.out.println("=================== Redis 7.0.12 vs mesh-3 对比 ===================");
        System.out.printf("%-24s %10s %8s %8s %8s %8s %8s%n",
                "场景", "ops/s", "p50", "p95", "p99", "mean", "max(μs)");
        for (BenchmarkResult r : results) {
            System.out.printf("%-24s %10d %8d %8d %8d %8d %8d%n",
                    r.getMode(), (long) r.getThroughputOpsPerSec(),
                    r.getLatency().getP50(), r.getLatency().getP95(), r.getLatency().getP99(),
                    r.getLatency().getMean(), r.getLatency().getMax());
        }
        System.out.println("==================================================================");
    }

    public static void main(String[] args) {
        new RedisVsMeshBenchmark().run();
        // Netty 非守护线程会阻止 exec:java 在 main 返回后退出，显式退出
        System.exit(0);
    }
}
