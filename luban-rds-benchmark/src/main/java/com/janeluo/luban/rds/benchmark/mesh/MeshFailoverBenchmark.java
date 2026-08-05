package com.janeluo.luban.rds.benchmark.mesh;

import com.janeluo.luban.rds.benchmark.cluster.ClusterAwareClient;
import com.janeluo.luban.rds.benchmark.cluster.model.BenchmarkResult;
import com.janeluo.luban.rds.benchmark.cluster.model.LatencyDistribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Leader 故障恢复时间基准：停掉 Leader 节点（含 bus/server），测
 * 「新 Leader 选举完成」与「首次写成功」两个时点，3 轮取中位数。
 * <p>
 * 每轮用全新集群（MeshNode 不可重启）。测量方式与 mesh 模块协议层套件一致：
 * 写探针轮询（{@link MeshTestCluster#findLeaderPort()}）检测新 Leader，经
 * {@link ClusterAwareClient} 跟随 MOVED 完成首次写。
 * </p>
 */
public class MeshFailoverBenchmark {
    private static final Logger log = LoggerFactory.getLogger(MeshFailoverBenchmark.class);
    private final int rounds = 3;

    public void run() {
        List<Long> electionMs = new ArrayList<>();
        List<Long> recoveryMs = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            try {
                long[] timings = runRound(round);
                electionMs.add(timings[0]);
                recoveryMs.add(timings[1]);
                log.info("failover round {}: 新 Leader 选举 {}ms, 首次写成功 {}ms",
                        round, timings[0], timings[1]);
            } catch (Exception e) {
                log.error("failover round {} 失败", round, e);
            }
        }
        if (electionMs.isEmpty()) {
            return;
        }
        writeResult(median(electionMs), median(recoveryMs));
    }

    /** @return [新Leader选举耗时ms, 首次写成功耗时ms] */
    private long[] runRound(int round) throws Exception {
        int serviceBase = 8100 + round * 10;
        int busBase = 11100 + round * 10;
        MeshTestCluster cluster = new MeshTestCluster(serviceBase, busBase);
        try {
            cluster.start();
            int leaderPort = cluster.findLeaderPort();
            if (leaderPort < 0) {
                throw new IllegalStateException("未探测到 Leader");
            }
            log.info("round {}: 当前 Leader servicePort={}", round, leaderPort);

            long t0 = System.currentTimeMillis();
            // server.stop() 含 ~5s persistExecutor 优雅关闭停顿；后台停止 Leader，
            // 从 t0 起并行轮询，测得真实 failover 时间（连接断开 → 新 Leader 选举 → 可写）。
            Thread stopper = new Thread(() -> {
                try {
                    cluster.stopNode(leaderPort);
                } catch (Exception e) {
                    log.error("停止 Leader 节点失败", e);
                }
            }, "mesh-failover-stopper");
            stopper.setDaemon(true);
            stopper.start();

            // 1. 等待新 Leader（存活节点中选出，且非旧 Leader）
            long deadline = System.currentTimeMillis() + 10_000;
            int newLeaderPort = -1;
            while (System.currentTimeMillis() < deadline) {
                int candidate = cluster.findLeaderPort();
                if (candidate >= 0 && candidate != leaderPort) {
                    newLeaderPort = candidate;
                    break;
                }
                Thread.sleep(20);
            }
            if (newLeaderPort < 0) {
                throw new IllegalStateException("failover 后 10s 内未选出新 Leader");
            }
            long t1 = System.currentTimeMillis();

            // 2. 首次成功写（直连新 Leader 端口，MOVED 自动跟随）
            ClusterAwareClient client = new ClusterAwareClient("127.0.0.1", newLeaderPort);
            client.connect();
            client.set("perf:failover:" + round, "ok");
            client.disconnect();
            long t2 = System.currentTimeMillis();

            stopper.join(30_000);
            return new long[]{t1 - t0, t2 - t0};
        } finally {
            cluster.stop();
        }
    }

    private void writeResult(long electionMedianMs, long recoveryMedianMs) {
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            Path file = metricsDir.resolve("L3-mesh-Failover-mesh-3-"
                    + System.currentTimeMillis() + ".json");
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"layer\": \"L3\",\n");
            sb.append("  \"scenarioName\": \"Failover\",\n");
            sb.append("  \"mode\": \"mesh-3\",\n");
            sb.append("  \"rounds\": ").append(rounds).append(",\n");
            sb.append("  \"newLeaderElectionMsMedian\": ").append(electionMedianMs).append(",\n");
            sb.append("  \"firstWriteMsMedian\": ").append(recoveryMedianMs).append("\n");
            sb.append("}\n");
            Files.writeString(file, sb.toString());
            log.info("结果已写入 {}", file.getFileName());
        } catch (Exception e) { log.error("写入结果失败", e); }
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    public static void main(String[] args) { new MeshFailoverBenchmark().run(); }
}
