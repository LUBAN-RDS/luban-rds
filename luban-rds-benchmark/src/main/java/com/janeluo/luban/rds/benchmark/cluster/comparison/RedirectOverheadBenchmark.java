package com.janeluo.luban.rds.benchmark.cluster.comparison;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import com.janeluo.luban.rds.server.cluster.testinfra.TestNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class RedirectOverheadBenchmark {
    private static final Logger log = LoggerFactory.getLogger(RedirectOverheadBenchmark.class);
    private final int iterations = 1000;

    public void run() {
        try {
            TestCluster cluster = TestCluster.builder()
                    .nodes(3).basePort(7950).build();
            cluster.start();
            cluster.assignSlotsEvenly();
            cluster.waitForClusterOnline(5000);
            Thread.sleep(1000);

            // 测试 1: 直接连接负责节点（无重定向）
            int directPort = findDirectPort(cluster);
            BenchmarkResult direct = testDirect(directPort);
            writeResult(direct);

            // 测试 2: 通过 ClusterAwareClient（可能有重定向）
            BenchmarkResult redirected = testRedirected(7950, cluster);
            writeResult(redirected);

            log.info("重定向开销: direct p50={}μs, redirected p50={}μs, redirects={}",
                    direct.getLatency().getP50(), redirected.getLatency().getP50(),
                    redirected.getRedirectCount());

            cluster.stop();
        } catch (Exception e) {
            log.error("重定向开销测试失败", e);
        }
    }

    private int findDirectPort(TestCluster cluster) {
        // 找到负责 "bench:key" 槽位的节点端口
        int slot = SlotUtils.keyHashSlot("bench:key");
        for (TestNode node : cluster.getNodes()) {
            if (node.getSlotManager().isSlotLocal(slot)) {
                return node.getPort();
            }
        }
        return 7950;
    }

    private BenchmarkResult testDirect(int port) throws Exception {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("RedirectOverhead-Direct");
        result.setMode("direct");
        LatencyDistribution latency = new LatencyDistribution();

        NettyRedisClient client = new NettyRedisClient("127.0.0.1", port);
        client.connect();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            client.set("bench:key:" + i, "value");
            latency.addSample((System.nanoTime() - start) / 1000);
        }
        client.disconnect();

        result.setLatency(latency);
        result.setTotalOperations(iterations);
        return result;
    }

    private BenchmarkResult testRedirected(int basePort, TestCluster cluster) throws Exception {
        BenchmarkResult result = new BenchmarkResult();
        result.setScenarioName("RedirectOverhead-Redirected");
        result.setMode("redirected");
        LatencyDistribution latency = new LatencyDistribution();

        ClusterAwareClient client = new ClusterAwareClient("127.0.0.1", basePort);
        client.connect();
        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            client.set("bench:key:" + i, "value");
            latency.addSample((System.nanoTime() - start) / 1000);
        }
        client.disconnect();
        result.setRedirectCount(client.getRedirectCount());
        result.setLatency(latency);
        result.setTotalOperations(iterations);
        return result;
    }

    private void writeResult(BenchmarkResult result) {
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            Path file = metricsDir.resolve("L3-RedirectOverhead-" + result.getMode()
                    + "-" + System.currentTimeMillis() + ".json");
            Files.writeString(file, "{\n  \"layer\": \"L3\",\n  \"mode\": \""
                    + result.getMode() + "\",\n  \"p50\": " + result.getLatency().getP50()
                    + ",\n  \"redirectCount\": " + result.getRedirectCount() + "\n}\n");
        } catch (Exception e) { log.error("写入失败", e); }
    }

    public static void main(String[] args) { new RedirectOverheadBenchmark().run(); }
}
