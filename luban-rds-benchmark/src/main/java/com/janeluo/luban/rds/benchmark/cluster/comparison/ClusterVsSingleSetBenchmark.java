package com.janeluo.luban.rds.benchmark.cluster.comparison;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.server.NettyRedisServer;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterVsSingleSetBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ClusterVsSingleSetBenchmark.class);
    private final int operationsPerThread = 5000;
    private final int threads = 32;

    public void run() {
        try {
            BenchmarkResult singleResult = runSingleMode();
            writeResult(singleResult);
            BenchmarkResult clusterResult = runClusterMode(3, 7850);
            writeResult(clusterResult);
        } catch (Exception e) {
            log.error("基准测试失败", e);
        }
    }

    private BenchmarkResult runSingleMode() throws Exception {
        log.info("=== 单进程 SET 基准测试 ===");
        int port = 7750;
        NettyRedisServer server = new NettyRedisServer(port);
        server.start();
        Thread.sleep(500);

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("SET");
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
                        String key = "set:" + threadId + ":" + i;
                        long start = System.nanoTime();
                        client.set(key, "value");
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
        log.info("single SET: {} ops, {} ops/s, p50={}μs", opsCount.get(),
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    private BenchmarkResult runClusterMode(int nodeCount, int basePort) throws Exception {
        log.info("=== 集群({}节点) SET 基准测试 ===", nodeCount);
        TestCluster cluster = TestCluster.builder()
                .nodes(nodeCount).basePort(basePort).build();
        cluster.start();
        cluster.assignSlotsEvenly();
        cluster.waitForClusterOnline(5000);
        Thread.sleep(1000);

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("SET");
        result.setMode("cluster-" + nodeCount);
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
                    ClusterAwareClient client = new ClusterAwareClient("127.0.0.1", basePort);
                    client.connect();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "set:" + threadId + ":" + i;
                        long start = System.nanoTime();
                        client.set(key, "value");
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
        log.info("cluster-{} SET: {} ops, {} ops/s, p50={}μs", nodeCount, opsCount.get(),
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    private void writeResult(BenchmarkResult result) {
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            Path file = metricsDir.resolve("L3-" + result.getScenarioName() + "-"
                    + result.getMode() + "-" + System.currentTimeMillis() + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"layer\": \"L3\",\n");
            sb.append("  \"scenarioName\": \"").append(result.getScenarioName()).append("\",\n");
            sb.append("  \"mode\": \"").append(result.getMode()).append("\",\n");
            sb.append("  \"totalOperations\": ").append(result.getTotalOperations()).append(",\n");
            sb.append("  \"durationMs\": ").append(result.getDurationMs()).append(",\n");
            sb.append("  \"throughputOpsPerSec\": ").append(result.getThroughputOpsPerSec()).append(",\n");
            sb.append("  \"latency\": {\"p50\":").append(result.getLatency().getP50())
                    .append(",\"p99\":").append(result.getLatency().getP99()).append("}\n");
            sb.append("}\n");
            Files.writeString(file, sb.toString());
        } catch (Exception e) { log.error("写入结果失败", e); }
    }

    public static void main(String[] args) { new ClusterVsSingleSetBenchmark().run(); }
}
