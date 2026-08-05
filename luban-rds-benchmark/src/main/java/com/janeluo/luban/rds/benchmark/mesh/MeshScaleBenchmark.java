package com.janeluo.luban.rds.benchmark.mesh;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mesh 3 节点并发写扩展性：线程数 1/2/4/8/16。
 * <p>
 * 全部写收敛到 Leader 的 raftExecutor 单线程串行——并发线程只增加排队，吞吐上限由
 * Raft 复制 RTT + 单线程 apply 决定（与 mesh 模块协议层套件的并发场景对应）。
 * </p>
 */
public class MeshScaleBenchmark {
    private static final Logger log = LoggerFactory.getLogger(MeshScaleBenchmark.class);
    private final int[] threadCounts = {1, 2, 4, 8, 16};
    private final int opsPerThread = 2000;

    public void run() {
        MeshTestCluster cluster = new MeshTestCluster(8000, 11000);
        try {
            cluster.start();
            for (int threads : threadCounts) {
                BenchmarkResult result = runWithThreads(cluster, threads);
                writeResult(result);
            }
        } catch (Exception e) {
            log.error("扩展性测试失败", e);
        } finally {
            cluster.stop();
        }
    }

    private BenchmarkResult runWithThreads(MeshTestCluster cluster, int threads) throws Exception {
        log.info("=== mesh 并发写扩展性: {} 线程 ===", threads);
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("Scale-SET");
        result.setMode("mesh-3-t" + threads);
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
                    ClusterAwareClient client =
                            new ClusterAwareClient("127.0.0.1", cluster.getBaseServicePort());
                    client.connect();
                    for (int i = 0; i < opsPerThread; i++) {
                        String key = "scale:" + threads + ":" + threadId + ":" + i;
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

        result.setTotalOperations(opsCount.get());
        result.setDurationMs(duration);
        result.setLatency(latency);
        result.setThroughputOpsPerSec(opsCount.get() / (duration / 1000.0));
        log.info("mesh-3 t{} SET: {} ops, {} ops/s, p50={}μs", threads, opsCount.get(),
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

    public static void main(String[] args) { new MeshScaleBenchmark().run(); }
}
