package com.janeluo.luban.rds.benchmark.cluster.comparison;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class ClusterScaleBenchmark {
    private static final Logger log = LoggerFactory.getLogger(ClusterScaleBenchmark.class);
    private final int[] clusterSizes = {3, 5, 7};
    private final int threads = 32;
    private final int opsPerThread = 5000;

    public void run() {
        for (int size : clusterSizes) {
            try {
                BenchmarkResult result = runCluster(size, 7900 + size * 10);
                writeResult(result);
            } catch (Exception e) {
                log.error("集群规模 {} 测试失败", size, e);
            }
        }
    }

    private BenchmarkResult runCluster(int nodeCount, int basePort) throws Exception {
        log.info("=== 集群扩展性测试: {} 节点 ===", nodeCount);
        TestCluster cluster = TestCluster.builder()
                .nodes(nodeCount).basePort(basePort).build();
        cluster.start();
        cluster.assignSlotsEvenly();
        cluster.waitForClusterOnline(5000);
        Thread.sleep(1000);

        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("Scale-SET");
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
                    for (int i = 0; i < opsPerThread; i++) {
                        long start = System.nanoTime();
                        client.set("scale:" + threadId + ":" + i, "value");
                        long elapsed = (System.nanoTime() - start) / 1000;
                        synchronized (latency) { latency.addSample(elapsed); }
                        opsCount.incrementAndGet();
                    }
                    client.disconnect();
                } catch (Exception e) { log.error("线程失败", e); }
                finally { latch.countDown(); }
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
        log.info("cluster-{}: {} ops/s, p50={}μs", nodeCount,
                result.getThroughputOpsPerSec(), latency.getP50());
        return result;
    }

    private void writeResult(BenchmarkResult result) {
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            Path file = metricsDir.resolve("L3-Scale-" + result.getMode() + "-"
                    + System.currentTimeMillis() + ".json");
            Files.writeString(file, "{\n  \"layer\": \"L3\",\n  \"mode\": \""
                    + result.getMode() + "\",\n  \"throughputOpsPerSec\": "
                    + result.getThroughputOpsPerSec() + ",\n  \"p50\": "
                    + result.getLatency().getP50() + "\n}\n");
        } catch (Exception e) { log.error("写入失败", e); }
    }

    public static void main(String[] args) { new ClusterScaleBenchmark().run(); }
}
