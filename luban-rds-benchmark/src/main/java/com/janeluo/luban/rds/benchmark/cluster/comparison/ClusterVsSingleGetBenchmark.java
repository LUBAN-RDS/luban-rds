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

public class ClusterVsSingleGetBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ClusterVsSingleGetBenchmark.class);

    private final int operationsPerThread = 5000;
    private final int warmupOperations = 500;

    public void run() {
        try {
            // 单进程基准
            BenchmarkResult singleResult = runSingleMode();
            writeResult(singleResult);

            // 集群 3 节点基准
            BenchmarkResult clusterResult = runClusterMode(3, 7800);
            writeResult(clusterResult);

        } catch (Exception e) {
            log.error("基准测试失败", e);
        }
    }

    private BenchmarkResult runSingleMode() throws Exception {
        log.info("=== 单进程 GET 基准测试 ===");
        int port = 7700;
        NettyRedisServer server = new NettyRedisServer(port);
        server.start();
        Thread.sleep(500);

        // 预热
        NettyRedisClient warmupClient = new NettyRedisClient("127.0.0.1", port);
        warmupClient.connect();
        for (int i = 0; i < warmupOperations; i++) {
            warmupClient.set("warmup:" + i, "value" + i);
        }
        warmupClient.disconnect();

        // 多线程 GET
        BenchmarkResult result = runGetBenchmark("127.0.0.1", port, "single");
        server.stop();
        return result;
    }

    private BenchmarkResult runClusterMode(int nodeCount, int basePort) throws Exception {
        log.info("=== 集群({}节点) GET 基准测试 ===", nodeCount);
        // 使用 TestCluster 启动集群
        TestCluster cluster = TestCluster.builder()
                .nodes(nodeCount)
                .basePort(basePort)
                .build();
        cluster.start();
        cluster.assignSlotsEvenly();
        cluster.waitForClusterOnline(5000);
        Thread.sleep(1000);

        // 预热
        NettyRedisClient warmupClient = cluster.getClient(
                cluster.getNodes().iterator().next().getNodeId());
        for (int i = 0; i < warmupOperations; i++) {
            warmupClient.set("warmup:" + i, "value" + i);
        }
        warmupClient.disconnect();

        // 多线程 GET（使用 ClusterAwareClient）
        BenchmarkResult result = runGetBenchmarkCluster(basePort, nodeCount);
        cluster.stop();
        return result;
    }

    private BenchmarkResult runGetBenchmark(String host, int port, String mode) throws Exception {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("GET");
        result.setMode(mode);
        result.setThreadCount(32);

        int threads = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        LatencyDistribution latency = new LatencyDistribution();
        AtomicInteger opsCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    NettyRedisClient client = new NettyRedisClient(host, port);
                    client.connect();
                    for (int i = 0; i < operationsPerThread; i++) {
                        String key = "warmup:" + ((threadId * operationsPerThread + i) % warmupOperations);
                        long start = System.nanoTime();
                        client.get(key);
                        long elapsed = (System.nanoTime() - start) / 1000; // 微秒
                        synchronized (latency) { latency.addSample(elapsed); }
                        opsCount.incrementAndGet();
                    }
                    client.disconnect();
                } catch (Exception e) {
                    log.error("线程 {} 失败", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        result.setTotalOperations(opsCount.get());
        result.setDurationMs(duration);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(opsCount.get() / (duration / 1000.0));

        log.info("{} GET: {} ops in {}ms, throughput={} ops/s, p50={}μs, p99={}μs",
                mode, opsCount.get(), duration, result.getThroughputOpsPerSec(),
                latency.getP50(), latency.getP99());

        return result;
    }

    private BenchmarkResult runGetBenchmarkCluster(int basePort, int nodeCount) throws Exception {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("GET");
        result.setMode("cluster-" + nodeCount);
        result.setThreadCount(32);

        int threads = 32;
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
                        String key = "warmup:" + ((threadId * operationsPerThread + i) % warmupOperations);
                        long start = System.nanoTime();
                        client.get(key);
                        long elapsed = (System.nanoTime() - start) / 1000;
                        synchronized (latency) { latency.addSample(elapsed); }
                        opsCount.incrementAndGet();
                    }
                    client.disconnect();
                } catch (Exception e) {
                    log.error("线程 {} 失败", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        long duration = System.currentTimeMillis() - startTime;
        executor.shutdown();

        result.setTotalOperations(opsCount.get());
        result.setDurationMs(duration);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(opsCount.get() / (duration / 1000.0));

        log.info("cluster-{} GET: {} ops in {}ms, throughput={} ops/s, p50={}μs, p99={}μs",
                nodeCount, opsCount.get(), duration, result.getThroughputOpsPerSec(),
                latency.getP50(), latency.getP99());

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
            sb.append("  \"threadCount\": ").append(result.getThreadCount()).append(",\n");
            sb.append("  \"totalOperations\": ").append(result.getTotalOperations()).append(",\n");
            sb.append("  \"durationMs\": ").append(result.getDurationMs()).append(",\n");
            sb.append("  \"throughputOpsPerSec\": ").append(result.getThroughputOpsPerSec()).append(",\n");
            sb.append("  \"latency\": {\n");
            sb.append("    \"p50\": ").append(result.getLatency().getP50()).append(",\n");
            sb.append("    \"p95\": ").append(result.getLatency().getP95()).append(",\n");
            sb.append("    \"p99\": ").append(result.getLatency().getP99()).append(",\n");
            sb.append("    \"min\": ").append(result.getLatency().getMin()).append(",\n");
            sb.append("    \"max\": ").append(result.getLatency().getMax()).append(",\n");
            sb.append("    \"mean\": ").append(result.getLatency().getMean()).append("\n");
            sb.append("  }\n");
            sb.append("}\n");
            Files.writeString(file, sb.toString());
            log.info("结果写入: {}", file);
        } catch (Exception e) {
            log.error("写入结果失败", e);
        }
    }

    public static void main(String[] args) {
        new ClusterVsSingleGetBenchmark().run();
    }
}
