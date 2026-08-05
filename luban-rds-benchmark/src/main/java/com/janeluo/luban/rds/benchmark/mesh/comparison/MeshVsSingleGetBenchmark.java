package com.janeluo.luban.rds.benchmark.mesh.comparison;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import com.janeluo.luban.rds.benchmark.mesh.MeshTestCluster;
import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单进程 GET vs mesh 3 节点 GET 对比。
 * <p>
 * mesh 读路径：Leader 上租约有效则本地执行（{@code MeshWriteGate.read}），不复制到多数派；
 * 请求落到 Follower 则 MOVED 到 Leader。与 cluster 套件 {@code ClusterVsSingleGetBenchmark} 同构。
 * </p>
 */
public class MeshVsSingleGetBenchmark {
    private static final Logger log = LoggerFactory.getLogger(MeshVsSingleGetBenchmark.class);
    /** 每线程操作数（-Dbench.ops=N 可调，诊断用）。 */
    private final int operationsPerThread = Integer.getInteger("bench.ops", 5000);
    private final int threads = 32;
    private final int seedKeys = 10000;

    public void run() {
        try {
            BenchmarkResult singleResult = runSingleMode();
            writeResult(singleResult);
            BenchmarkResult meshResult = runMeshMode(7900, 10900);
            writeResult(meshResult);
        } catch (Exception e) {
            log.error("基准测试失败", e);
        }
    }

    private BenchmarkResult runSingleMode() throws Exception {
        log.info("=== 单进程 GET 基准测试 ===");
        int port = 7752;
        NettyRedisServer server = new NettyRedisServer(port);
        server.start();
        Thread.sleep(500);

        // 预置读基准 key
        NettyRedisClient seeder = new NettyRedisClient("127.0.0.1", port);
        seeder.connect();
        for (int i = 0; i < seedKeys; i++) {
            seeder.set("get:" + i, "value");
        }
        seeder.disconnect();

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("GET");
        result.setMode("single");
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
                    NettyRedisClient client = new NettyRedisClient("127.0.0.1", port);
                    client.connect();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "get:" + ((threadId * 997 + i) % seedKeys);
                        long start = System.nanoTime();
                        client.get(key);
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
        server.stop();

        result.setTotalOperations(opsCount.get());
        result.setDurationMs(duration);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(opsCount.get() / (duration / 1000.0));
        log.info("single GET: {} ops, {} ops/s, p50={}μs", opsCount.get(),
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    private BenchmarkResult runMeshMode(int serviceBasePort, int busBasePort) throws Exception {
        log.info("=== mesh(3节点强一致) GET 基准测试 ===");
        MeshTestCluster cluster = new MeshTestCluster(serviceBasePort, busBasePort);
        cluster.start();

        // 预置读基准 key（经 Raft 复制，3 节点一致）
        ClusterAwareClient seeder = new ClusterAwareClient("127.0.0.1", serviceBasePort);
        seeder.connect();
        for (int i = 0; i < seedKeys; i++) {
            seeder.set("get:" + i, "value");
        }
        seeder.disconnect();

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("GET");
        result.setMode("mesh-3");
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
                    ClusterAwareClient client = new ClusterAwareClient("127.0.0.1", serviceBasePort);
                    client.connect();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "get:" + ((threadId * 997 + i) % seedKeys);
                        long start = System.nanoTime();
                        client.get(key);
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
        cluster.stop();

        result.setTotalOperations(opsCount.get());
        result.setDurationMs(duration);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(opsCount.get() / (duration / 1000.0));
        log.info("mesh-3 GET: {} ops, {} ops/s, p50={}μs", opsCount.get(),
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    private void writeResult(BenchmarkResult result) {
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            Path file = metricsDir.resolve("L3-mesh-" + result.getScenarioName() + "-"
                    + result.getMode() + "-" + System.currentTimeMillis() + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"layer\": \"L3\",\n");
            sb.append("  \"scenarioName\": \"").append(result.getScenarioName()).append("\",\n");
            sb.append("  \"mode\": \"").append(result.getMode()).append("\",\n");
            sb.append("  \"threadCount\": ").append(result.getThreadCount()).append(",\n");
            sb.append("  \"totalOperations\": ").append(result.getTotalOperations()).append(",\n");
            sb.append("  \"durationMs\": ").append(result.getDurationMs()).append(",\n");
            sb.append("  \"throughputOpsPerSec\": ").append(result.getThroughputOpsPerSec()).append(",\n");
            sb.append("  \"latency\": {\"p50\":").append(result.getLatency().getP50())
                    .append(",\"p95\":").append(result.getLatency().getP95())
                    .append(",\"p99\":").append(result.getLatency().getP99()).append("}\n");
            sb.append("}\n");
            Files.writeString(file, sb.toString());
            log.info("结果已写入 {}", file.getFileName());
        } catch (Exception e) { log.error("写入结果失败", e); }
    }

    public static void main(String[] args) { new MeshVsSingleGetBenchmark().run(); }
}
