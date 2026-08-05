package com.janeluo.luban.rds.mesh.perf;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * mesh 模块性能测试套件（协议层 + 真实 Netty 传输）。
 * <p>
 * 单 JVM 内起 1/3 个真实 {@link MeshNode}（总线默认走真实 MeshBusServer/MeshBusClient，
 * TCP 回环），覆盖 Raft 复制、编解码、心跳/租约、apply 全链路。场景：
 * <ol>
 *   <li>选举收敛时间；</li>
 *   <li>SET 串行写吞吐/延迟；</li>
 *   <li>并发写扩展性（1/2/4/8/16 线程）；</li>
 *   <li>管道写吞吐上限（in-flight 32/128）；</li>
 *   <li>读路径对比：租约本地读 vs 线性一致读（propose）；</li>
 *   <li>1 节点 vs 3 节点写延迟（量化多数派复制开销）；</li>
 *   <li>Leader 故障恢复时间（kill leader → 新 leader 选举 + 首次写成功）。</li>
 * </ol>
 * </p>
 *
 * <h3>运行方式</h3>
 * <pre>
 * mvn -pl luban-rds-mesh -am test-compile
 * mvn -pl luban-rds-mesh test -Dtest=MeshPerformanceSuite -Dmesh.perf=true
 * </pre>
 * IDE 可直接运行 {@link #main}。类名不含 {@code *Test} 后缀，默认 surefire 不会拾取；
 * 即使被拾取，各用例也以 {@code -Dmesh.perf=true} 门控（默认跳过），CI 零影响。
 *
 * <h3>可调参数</h3>
 * <ul>
 *   <li>{@code -Dmesh.perf.ops=N}：每场景操作数（默认 5000）；</li>
 *   <li>{@code -Dmesh.perf.bus=netty|memory}：总线模式（默认 netty，memory 量化传输开销）；</li>
 *   <li>{@code -Dmesh.perf.fsync=true}：注入真实 fsync persistHook（默认 false）；</li>
 *   <li>{@code -Dmesh.perf.basePort=N}：netty bus 端口基址（默认 13000）。</li>
 * </ul>
 *
 * <h3>结果输出</h3>
 * <p>stdout markdown 汇总表 + {@code target/test-metrics/mesh-perf-&lt;ts&gt;.json/.md}。</p>
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
public class MeshPerformanceSuite {

    private static final Logger log = LoggerFactory.getLogger(MeshPerformanceSuite.class);

    /** 是否启用性能测试（默认 false → 全部跳过）。 */
    private static final boolean ENABLED = Boolean.getBoolean("mesh.perf");
    /** 总线模式：netty（默认）/ memory。 */
    private static final boolean BUS_NETTY = !"memory".equalsIgnoreCase(
            System.getProperty("mesh.perf.bus", "netty"));
    /** 每场景操作数。 */
    private static final int OPS = Integer.getInteger("mesh.perf.ops", 5000);
    /** netty bus 端口基址。 */
    private static final int BASE_PORT = Integer.getInteger("mesh.perf.basePort", 13000);
    /** 是否注入真实 fsync persistHook。 */
    private static final boolean FSYNC = Boolean.getBoolean("mesh.perf.fsync");

    /** 全部场景结果（@AfterAll / main 统一写报告）。 */
    private static final List<MeshPerfResult> RESULTS = Collections.synchronizedList(new ArrayList<>());

    // ==================== JUnit 入口 ====================

    @Test
    void electionConvergence() throws Exception {
        assumePerfEnabled();
        runElectionConvergence();
    }

    @Test
    void sequentialWrite() throws Exception {
        assumePerfEnabled();
        runSequentialWrite();
    }

    @Test
    void concurrentWriteScaling() throws Exception {
        assumePerfEnabled();
        runConcurrentWriteScaling();
    }

    @Test
    void pipelinedWrite() throws Exception {
        assumePerfEnabled();
        runPipelinedWrite();
    }

    @Test
    void readPathComparison() throws Exception {
        assumePerfEnabled();
        runReadPathComparison();
    }

    @Test
    void oneVsThreeNodeWriteLatency() throws Exception {
        assumePerfEnabled();
        runOneVsThreeNodeWriteLatency();
    }

    @Test
    void leaderFailoverRecovery() throws Exception {
        assumePerfEnabled();
        runLeaderFailoverRecovery();
    }

    /**
     * 预热：先跑一轮 3 节点写/读，让 propose/replicate/编解码/apply 等热点方法完成 JIT 编译，
     * 避免首个场景（sequentialWrite）被 C2 编译停顿污染延迟分位数。
     */
    @BeforeAll
    static void warmUp() throws Exception {
        if (!ENABLED) {
            return;
        }
        log.info("=== 预热（JIT）: 1000 写 + 500 租约读 ===");
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT + 200, FSYNC);
        try {
            cluster.startAll();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));
            for (int i = 0; i < 1000; i++) {
                leader.propose(setFrame("perf:warmup:" + i, "v"), 0, null)
                        .get(5, TimeUnit.SECONDS);
            }
            MeshWriteGate gate = new MeshWriteGate(leader,
                    cluster.getStore(leader.getNodeId()), new DefaultCommandHandler());
            for (int i = 0; i < 500; i++) {
                gate.read(0, new String[]{"GET", "perf:warmup:" + (i % 1000)});
            }
            log.info("预热完成");
        } finally {
            cluster.stopAll();
        }
    }

    @AfterAll
    static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        try {
            Path metricsDir = Path.of("target", "test-metrics");
            Files.createDirectories(metricsDir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .format(LocalDateTime.now());
            StringBuilder json = new StringBuilder();
            json.append("{\n");
            json.append("  \"suite\": \"mesh-perf\",\n");
            json.append("  \"timestamp\": \"").append(ts).append("\",\n");
            json.append("  \"bus\": \"").append(busMode()).append("\",\n");
            json.append("  \"fsync\": ").append(FSYNC).append(",\n");
            json.append("  \"meshConfig\": {\"nodes\": 3, \"heartbeatMs\": 50, ")
                    .append("\"electionTimeoutMs\": \"100-200\", \"leaseMs\": 400},\n");
            json.append("  \"results\": [\n");
            synchronized (RESULTS) {
                for (int i = 0; i < RESULTS.size(); i++) {
                    json.append("    ").append(RESULTS.get(i).toJson());
                    json.append(i < RESULTS.size() - 1 ? ",\n" : "\n");
                }
            }
            json.append("  ]\n}\n");
            Files.writeString(metricsDir.resolve("mesh-perf-" + ts + ".json"), json.toString());
            Files.writeString(metricsDir.resolve("mesh-perf-" + ts + ".md"), buildMarkdown());
            log.info("结果已写入 target/test-metrics/mesh-perf-{}.json/.md", ts);
        } catch (Exception e) {
            log.error("写入结果失败", e);
        }
    }

    /** 独立运行入口（IDE 便捷）；等价于按序跑全部场景 + 写报告。 */
    public static void main(String[] args) throws Exception {
        log.info("====== mesh 性能测试套件: bus={}, fsync={}, ops={} ======",
                busMode(), FSYNC, OPS);
        warmUp();
        runElectionConvergence();
        runSequentialWrite();
        runConcurrentWriteScaling();
        runPipelinedWrite();
        runReadPathComparison();
        runOneVsThreeNodeWriteLatency();
        runLeaderFailoverRecovery();
        writeReport();
        log.info("====== 完成，共 {} 条结果 ======", RESULTS.size());
    }

    // ==================== 场景 1：选举收敛 ====================

    private static void runElectionConvergence() throws Exception {
        log.info("=== 场景 1: 选举收敛 ===");
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            cluster.startAll();
            long t0 = System.nanoTime();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));
            long ms = (System.nanoTime() - t0) / 1_000_000;

            // 冒烟写：选举后写入可用且 3 节点最终一致
            byte[] resp = leader.propose(setFrame("perf:election", "ok"), 0, null)
                    .get(5, TimeUnit.SECONDS);
            requireOk(resp);
            for (String id : cluster.nodeIds()) {
                waitForValue(cluster.getStore(id), "perf:election", "ok", 3_000);
            }

            RESULTS.add(MeshPerfResult.throughputOnly("electionConvergence",
                    "nodes=3 bus=" + busMode(), 1, ms));
            log.info("选举收敛 {}ms，写入 3 节点一致 ✓", ms);
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== 场景 2：SET 串行写 ====================

    private static void runSequentialWrite() throws Exception {
        log.info("=== 场景 2: SET 串行写（{} ops）===", OPS);
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            cluster.startAll();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));

            // 预热 200 次（租约/心跳进入稳态），不计量
            for (int i = 0; i < 200; i++) {
                leader.propose(setFrame("perf:warm:" + i, "v"), 0, null)
                        .get(5, TimeUnit.SECONDS);
            }

            long[] samples = new long[OPS];
            AtomicInteger errors = new AtomicInteger();
            // 任期诊断：跑分期间若有 PreVote/选举，term 会增长（用于归因延迟尖峰）
            Map<String, Long> termBefore = currentTerms(cluster);
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long s = System.nanoTime();
                try {
                    leader.propose(setFrame("perf:seq:" + i, "v"), 0, null)
                            .get(5, TimeUnit.SECONDS);
                    samples[i] = (System.nanoTime() - s) / 1_000;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            Map<String, Long> termAfter = currentTerms(cluster);
            String termState = termGrew(termBefore, termAfter) ? "termGrew" : "termStable";
            log.info("任期诊断: before={} after={}（增长=跑分期间发生过选举）", termBefore, termAfter);
            for (String id : cluster.nodeIds()) {
                waitForValue(cluster.getStore(id), "perf:seq:" + (OPS - 1), "v", 3_000);
            }

            MeshPerfResult r = MeshPerfResult.fromSamples("sequentialWrite",
                    "bus=" + busMode() + " " + termState, wallMs, samples, errors.get());
            RESULTS.add(r);
            log.info("串行写: {}", r.summary());
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== 场景 3：并发写扩展性 ====================

    private static void runConcurrentWriteScaling() throws Exception {
        log.info("=== 场景 3: 并发写扩展性（每线程数 {} ops 分摊）===", OPS);
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            cluster.startAll();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));

            int[] threadCounts = {1, 2, 4, 8, 16};
            for (int threads : threadCounts) {
                int perThread = Math.max(1, OPS / threads);
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch startLatch = new CountDownLatch(1);
                CountDownLatch doneLatch = new CountDownLatch(threads);
                AtomicInteger errors = new AtomicInteger();
                long[][] perThreadSamples = new long[threads][];
                for (int t = 0; t < threads; t++) {
                    perThreadSamples[t] = new long[perThread];
                }

                long t0 = System.nanoTime();
                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    pool.submit(() -> {
                        try {
                            startLatch.await();
                            for (int i = 0; i < perThread; i++) {
                                long s = System.nanoTime();
                                try {
                                    leader.propose(setFrame(
                                            "perf:conc:" + threads + ":" + tid + ":" + i, "v"),
                                            0, null).get(5, TimeUnit.SECONDS);
                                    perThreadSamples[tid][i] = (System.nanoTime() - s) / 1_000;
                                } catch (Exception e) {
                                    errors.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            errors.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                startLatch.countDown();
                doneLatch.await();
                long wallMs = (System.nanoTime() - t0) / 1_000_000;
                pool.shutdown();

                MeshPerfResult r = MeshPerfResult.fromSamples("concurrentWrite",
                        "threads=" + threads, wallMs, merge(perThreadSamples), errors.get());
                RESULTS.add(r);
                log.info("并发写 threads={}: {}", threads, r.summary());
            }
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== 场景 4：管道写吞吐上限 ====================

    private static void runPipelinedWrite() throws Exception {
        log.info("=== 场景 4: 管道写吞吐上限 ===");
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            cluster.startAll();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));

            int[] windows = {32, 128};
            for (int window : windows) {
                int batches = Math.max(1, OPS / window);
                AtomicInteger errors = new AtomicInteger();
                long t0 = System.nanoTime();
                for (int b = 0; b < batches; b++) {
                    List<CompletableFuture<byte[]>> futures = new ArrayList<>(window);
                    for (int i = 0; i < window; i++) {
                        int idx = b * window + i;
                        futures.add(leader.propose(setFrame("perf:pipe:" + window + ":" + idx, "v"),
                                0, null));
                    }
                    for (CompletableFuture<byte[]> f : futures) {
                        try {
                            f.get(10, TimeUnit.SECONDS);
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                }
                long wallMs = (System.nanoTime() - t0) / 1_000_000;
                long total = (long) batches * window;

                MeshPerfResult r = MeshPerfResult.throughputOnly("pipelinedWrite",
                        "window=" + window + " err=" + errors.get(), total, wallMs);
                RESULTS.add(r);
                log.info("管道写 window={}: {}", window, r.summary());
            }
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== 场景 5：读路径对比 ====================

    private static void runReadPathComparison() throws Exception {
        log.info("=== 场景 5: 读路径对比（租约本地读 vs 线性一致读）===");
        MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            cluster.startAll();
            MeshNode leader = requireLeader(cluster.waitForLeader(10_000));

            // 预置 1000 个 key 供读基准
            for (int i = 0; i < 1000; i++) {
                leader.propose(setFrame("perf:read:" + i, "v"), 0, null)
                        .get(5, TimeUnit.SECONDS);
            }

            // (a) 租约本地读：MeshWriteGate.read（Leader + 租约有效 → 本地 handler 执行）
            MeshWriteGate gate = new MeshWriteGate(leader,
                    cluster.getStore(leader.getNodeId()), new DefaultCommandHandler());
            long[] leaseSamples = new long[OPS];
            AtomicInteger errors = new AtomicInteger();
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long s = System.nanoTime();
                try {
                    gate.read(0, new String[]{"GET", "perf:read:" + (i % 1000)});
                    leaseSamples[i] = (System.nanoTime() - s) / 1_000;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            MeshPerfResult leaseR = MeshPerfResult.fromSamples("readLeaseLocal",
                    "bus=" + busMode(), wallMs, leaseSamples, errors.get());
            RESULTS.add(leaseR);
            log.info("租约本地读: {}", leaseR.summary());

            // (b) 线性一致读：GET 也走 Raft propose（读也复制到多数派）
            long[] linSamples = new long[OPS];
            errors.set(0);
            t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long s = System.nanoTime();
                try {
                    leader.propose(getFrame("perf:read:" + (i % 1000)), 0, null)
                            .get(5, TimeUnit.SECONDS);
                    linSamples[i] = (System.nanoTime() - s) / 1_000;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            wallMs = (System.nanoTime() - t0) / 1_000_000;
            MeshPerfResult linR = MeshPerfResult.fromSamples("readLinearizable",
                    "bus=" + busMode(), wallMs, linSamples, errors.get());
            RESULTS.add(linR);
            log.info("线性一致读: {}", linR.summary());
        } finally {
            cluster.stopAll();
        }
    }

    // ==================== 场景 6：1 节点 vs 3 节点写延迟 ====================

    private static void runOneVsThreeNodeWriteLatency() throws Exception {
        log.info("=== 场景 6: 写延迟对比（1 节点 vs 3 节点多数派复制）===");
        // 1 节点基线（无复制 RTT）
        MeshPerfCluster single = new MeshPerfCluster(1, BUS_NETTY, BASE_PORT + 100, FSYNC);
        try {
            single.startAll();
            MeshNode leader = requireLeader(single.waitForLeader(10_000));
            for (int i = 0; i < 200; i++) {
                leader.propose(setFrame("perf:single:warm:" + i, "v"), 0, null)
                        .get(5, TimeUnit.SECONDS);
            }
            long[] samples = new long[OPS];
            AtomicInteger errors = new AtomicInteger();
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long s = System.nanoTime();
                try {
                    leader.propose(setFrame("perf:single:" + i, "v"), 0, null)
                            .get(5, TimeUnit.SECONDS);
                    samples[i] = (System.nanoTime() - s) / 1_000;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            MeshPerfResult r1 = MeshPerfResult.fromSamples("writeLatency",
                    "nodes=1 bus=" + busMode(), wallMs, samples, errors.get());
            RESULTS.add(r1);
            log.info("1 节点写: {}", r1.summary());
        } finally {
            single.stopAll();
        }

        // 3 节点（多数派复制；与场景 2 同配置，独立跑保证口径一致）
        MeshPerfCluster three = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT, FSYNC);
        try {
            three.startAll();
            MeshNode leader = requireLeader(three.waitForLeader(10_000));
            for (int i = 0; i < 200; i++) {
                leader.propose(setFrame("perf:three:warm:" + i, "v"), 0, null)
                        .get(5, TimeUnit.SECONDS);
            }
            long[] samples = new long[OPS];
            AtomicInteger errors = new AtomicInteger();
            long t0 = System.nanoTime();
            for (int i = 0; i < OPS; i++) {
                long s = System.nanoTime();
                try {
                    leader.propose(setFrame("perf:three:" + i, "v"), 0, null)
                            .get(5, TimeUnit.SECONDS);
                    samples[i] = (System.nanoTime() - s) / 1_000;
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            }
            long wallMs = (System.nanoTime() - t0) / 1_000_000;
            MeshPerfResult r3 = MeshPerfResult.fromSamples("writeLatency",
                    "nodes=3 bus=" + busMode(), wallMs, samples, errors.get());
            RESULTS.add(r3);
            log.info("3 节点写: {}", r3.summary());
        } finally {
            three.stopAll();
        }
    }

    // ==================== 场景 7：Leader 故障恢复 ====================

    private static void runLeaderFailoverRecovery() throws Exception {
        log.info("=== 场景 7: Leader 故障恢复（kill leader × 3 取中位数）===");
        List<Long> electionMs = new ArrayList<>();
        List<Long> recoveryMs = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            MeshPerfCluster cluster = new MeshPerfCluster(3, BUS_NETTY, BASE_PORT + round * 10, FSYNC);
            try {
                cluster.startAll();
                MeshNode leader = requireLeader(cluster.waitForLeader(10_000));
                String deadId = leader.getNodeId();
                List<String> survivors = new ArrayList<>(cluster.nodeIds());
                survivors.remove(deadId);

                long t0 = System.nanoTime();
                cluster.stopNode(deadId);
                MeshNode newLeader = cluster.waitForLeaderAmong(survivors, 10_000);
                if (newLeader == null) {
                    throw new IllegalStateException("failover 后 10s 内未选出新 Leader");
                }
                long t1 = System.nanoTime();
                newLeader.propose(setFrame("perf:failover:" + round, "ok"), 0, null)
                        .get(10, TimeUnit.SECONDS);
                long t2 = System.nanoTime();

                long election = (t1 - t0) / 1_000_000;
                long recovery = (t2 - t0) / 1_000_000;
                electionMs.add(election);
                recoveryMs.add(recovery);
                log.info("failover round {}: 死节点={}, 新 Leader 选举 {}ms, 首次写成功 {}ms",
                        round, deadId, election, recovery);
            } finally {
                cluster.stopAll();
            }
        }
        RESULTS.add(MeshPerfResult.throughputOnly("leaderFailoverRecovery",
                "newLeaderElectionMs median-of-3", 3, median(electionMs)));
        RESULTS.add(MeshPerfResult.throughputOnly("leaderFailoverRecovery",
                "firstWriteMs median-of-3", 3, median(recoveryMs)));
    }

    // ==================== 工具 ====================

    private static void assumePerfEnabled() {
        Assumptions.assumeTrue(ENABLED,
                "mesh 性能测试默认跳过；运行: mvn -pl luban-rds-mesh test "
                        + "-Dtest=MeshPerformanceSuite -Dmesh.perf=true");
    }

    private static String busMode() {
        return BUS_NETTY ? "netty" : "memory";
    }

    private static MeshNode requireLeader(MeshNode leader) {
        if (leader == null) {
            throw new IllegalStateException("10s 内未选举出唯一 Leader");
        }
        return leader;
    }

    /** SET 命令帧（RESP 字节）。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** GET 命令帧（RESP 字节）。 */
    private static byte[] getFrame(String key) {
        String f = "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void requireOk(byte[] resp) {
        if (!"+OK\r\n".equals(new String(resp, StandardCharsets.ISO_8859_1))) {
            throw new IllegalStateException("propose 未返回 +OK: "
                    + new String(resp, StandardCharsets.ISO_8859_1));
        }
    }

    /** 等待 store 中 key 出现期望值（Follower 复制有微小延迟）。 */
    private static void waitForValue(MemoryStore store, String key, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object v = store.get(0, key);
            if (expected.equals(v)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("key=" + key + " 未在 " + timeoutMs + "ms 内复制到期望值 "
                + expected + "，实际=" + store.get(0, key));
    }

    /** 合并各线程延迟样本。 */
    private static long[] merge(long[][] perThreadSamples) {
        int total = 0;
        for (long[] a : perThreadSamples) {
            total += a.length;
        }
        long[] merged = new long[total];
        int pos = 0;
        for (long[] a : perThreadSamples) {
            System.arraycopy(a, 0, merged, pos, a.length);
            pos += a.length;
        }
        return merged;
    }

    /** 各节点当前任期快照（选举诊断）。 */
    private static Map<String, Long> currentTerms(MeshPerfCluster cluster) {
        Map<String, Long> terms = new HashMap<>();
        for (String id : cluster.nodeIds()) {
            MeshNode n = cluster.getNode(id);
            if (n != null) {
                terms.put(id, n.getCurrentTerm());
            }
        }
        return terms;
    }

    /** 任一节点任期增长 = 跑分期间发生过 PreVote/选举。 */
    private static boolean termGrew(Map<String, Long> before, Map<String, Long> after) {
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long afterTerm = after.get(e.getKey());
            if (afterTerm != null && afterTerm > e.getValue()) {
                return true;
            }
        }
        return false;
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static String buildMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# mesh 性能测试报告\n\n");
        sb.append("- bus: ").append(busMode()).append("\n");
        sb.append("- fsync: ").append(FSYNC).append("\n");
        sb.append("- mesh: 3 节点, heartbeat=50ms, election=100-200ms, lease=400ms\n\n");
        sb.append("| 场景 | 参数 | ops | duration(ms) | ops/s | p50(μs) | p95(μs) | p99(μs) | "
                + "max(μs) | avg(μs) | err |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        synchronized (RESULTS) {
            for (MeshPerfResult r : RESULTS) {
                sb.append("| ").append(r.toMarkdownRow()).append(" |\n");
            }
        }
        return sb.toString();
    }
}
