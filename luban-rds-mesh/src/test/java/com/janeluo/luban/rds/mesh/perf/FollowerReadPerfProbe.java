package com.janeluo.luban.rds.mesh.perf;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * follower 读性能探针（fix-mesh-follower-read Task 19）：三组配置的并发读对比。
 *
 * <h3>被测口径</h3>
 * <p>
 * 3 节点真实 {@link MeshPerfCluster}（netty 总线，TCP 回环），每轮 8 线程共 {@code mesh.perf.ops}
 * （默认 20000）次「客户端读」；读操作在 Follower 上发起，客户端语义 = 先问 Follower，
 * 被 MOVED 则跟随到 Leader（等价真实客户端的重定向行为）：
 * <ol>
 *   <li><b>off</b>（基线）：Follower 必抛 MOVED → 双跳（本夹具中两跳均为进程内调用）；</li>
 *   <li><b>readindex + cacheMs=0</b>：每次取新读点（真实 netty 总线 RTT）+ apply 屏障 + 本地读；</li>
 *   <li><b>readindex + cacheMs=100</b>：100ms 窗口内复用读点（single-flight）+ apply 屏障 + 本地读；</li>
 * </ol>
 * 另测一组 <b>Leader 本地读</b> 作为「一次进程内 handler 执行」的标尺。
 * </p>
 *
 * <h3>为什么这个夹具不能给出生产真实加速比（重要）</h3>
 * <p>
 * 本夹具直接调用 {@link MeshWriteGate#read}，<b>完全绕开客户端 ↔ 服务端的网络层</b>：
 * 真实部署中 off 基线的两跳各含一次客户端网络往返（读 Follower 拿 MOVED + 读 Leader 拿值），
 * cache=100 只需一次往返，这才是生产读吞吐/延迟差异的主因。本夹具里的「跳」是进程内方法调用，
 * 故 <b>绝对值不代表生产</b>，off 基线被显著低估。夹具仍可信的部分是：
 * readindex 取点在 cache=0 时确实走真实 netty 总线（回环 RTT），cache=100 通过窗口复用摊薄；
 * 以及 gate/handler/apply 屏障的进程内开销对比。
 * </p>
 *
 * <h3>运行方式（名字不含 *Test，surefire 默认不拾取，需显式指定）</h3>
 * <pre>
 * mvn -pl luban-rds-mesh -am test-compile
 * mvn -pl luban-rds-mesh test -Dtest=FollowerReadPerfProbe
 * # 可调：-Dmesh.perf.ops=20000 -Dmesh.perf.threads=8 -Dmesh.perf.basePort=15100
 * </pre>
 * IDE 可直接运行 {@link #main}。结果同步落盘 {@code target/test-metrics/follower-read-perf.md}。
 */
@Timeout(value = 900, unit = TimeUnit.SECONDS)
public class FollowerReadPerfProbe {

    private static final Logger log = LoggerFactory.getLogger(FollowerReadPerfProbe.class);

    /** 每轮总操作数。 */
    private static final int OPS = Integer.getInteger("mesh.perf.ops", 20000);
    /** 并发线程数。 */
    private static final int THREADS = Integer.getInteger("mesh.perf.threads", 8);
    /** netty bus 端口基址（各轮依次 +100）。 */
    private static final int BASE_PORT = Integer.getInteger("mesh.perf.basePort", 15100);
    /** 预置 key 数（避免单 key 热点）。 */
    private static final int KEYS = 1000;
    private static final String KEY_PREFIX = "perf:fr:";
    private static final String VALUE = "v";

    private static final List<MeshPerfResult> RESULTS = Collections.synchronizedList(new ArrayList<>());
    /** 各轮 follower 读计数增量（证明读确实在本地服务 / 回落比例）。 */
    private static final List<String> COUNTER_LINES = Collections.synchronizedList(new ArrayList<>());

    /** 单次「客户端读」：返回响应字节；抛异常 = 该次读失败。 */
    @FunctionalInterface
    private interface ReadOp {
        byte[] read(String key) throws Exception;
    }

    @Test
    void threeWayComparison() throws Exception {
        RESULTS.clear();
        COUNTER_LINES.clear();
        RESULTS.addAll(runConfig("followerReadOff", MeshConfig.ReadFromFollower.OFF, 0L, BASE_PORT));
        RESULTS.addAll(runConfig("followerReadCache0", MeshConfig.ReadFromFollower.READ_INDEX,
                0L, BASE_PORT + 100));
        RESULTS.addAll(runConfig("followerReadCache100", MeshConfig.ReadFromFollower.READ_INDEX,
                100L, BASE_PORT + 200));
        writeReport();
        log.info("\n{}", buildMarkdown());
    }

    /** 独立运行入口（IDE 便捷）。 */
    public static void main(String[] args) throws Exception {
        new FollowerReadPerfProbe().threeWayComparison();
    }

    // ==================== 单组配置 ====================

    /**
     * 起一套 3 节点集群，按 {@code mode/cacheMs} 配置跑一轮并发读。
     *
     * @return 本轮结果（读操作 + Leader 本地读标尺）
     */
    private static List<MeshPerfResult> runConfig(String scenario,
                                                  MeshConfig.ReadFromFollower mode,
                                                  long cacheMs, int basePort) throws Exception {
        log.info("=== {}: mode={} cacheMs={} ops={} threads={} ===",
                scenario, mode, cacheMs, OPS, THREADS);
        MeshPerfCluster cluster = new MeshPerfCluster(3, true, basePort, false, mode, cacheMs, 500L);
        List<MeshPerfResult> out = new ArrayList<>();
        try {
            cluster.startAll();
            MeshNode leader = cluster.waitForLeader(10_000);
            if (leader == null) {
                throw new IllegalStateException("3 节点未选出 Leader");
            }
            // 就绪门（Task 4）：未 markReady 则 gate.read 一律 -TRYAGAIN
            cluster.markAllReady();
            String leaderId = leader.getNodeId();
            String followerId = pickFollower(cluster, leaderId);
            if (followerId == null) {
                throw new IllegalStateException("未找到 Follower");
            }

            // 预置 KEYS 个 key（写全部经 Leader Raft 复制）
            for (int i = 0; i < KEYS; i++) {
                byte[] resp = leader.propose(setFrame(KEY_PREFIX + i, VALUE), 0, null)
                        .get(10, TimeUnit.SECONDS);
                if (!"+OK\r\n".equals(new String(resp, StandardCharsets.ISO_8859_1))) {
                    throw new IllegalStateException("预置写未返回 +OK");
                }
            }
            // 等 Follower apply 追平（前几轮 follower 读依赖本地已有数据）
            awaitFollowerValue(cluster, followerId, KEY_PREFIX + (KEYS - 1), 10_000);

            long fetch0 = followerNode(cluster, followerId).readIndexFetchCount();
            long hit0 = followerNode(cluster, followerId).readIndexCacheHitCount();
            long coal0 = followerNode(cluster, followerId).readIndexCoalescedCount();
            long local0 = followerNode(cluster, followerId).followerReadLocalCount();
            long fb0 = followerNode(cluster, followerId).followerReadFallbackCount();

            MeshWriteGate fg = cluster.getGate(followerId);
            MeshWriteGate lg = cluster.getGate(leaderId);
            ReadOp op = readOpFor(mode, fg, lg);

            MeshPerfResult r = measure(scenario, cluster,
                    "mode=" + mode + " cacheMs=" + cacheMs, op);
            out.add(r);
            log.info("{}: {}", scenario, r.summary());

            MeshNode fn = followerNode(cluster, followerId);
            String counters = String.format(
                    "follower=%s fetch=%d cacheHit=%d coalesced=%d local=%d fallback=%d rejected=%d",
                    followerId, fn.readIndexFetchCount() - fetch0,
                    fn.readIndexCacheHitCount() - hit0, fn.readIndexCoalescedCount() - coal0,
                    fn.followerReadLocalCount() - local0, fn.followerReadFallbackCount() - fb0,
                    fn.followerReadRejectedCount());
            String counterLine = scenario + " " + counters;
            COUNTER_LINES.add(counterLine);
            System.out.println("[perf-counter] " + counterLine);

            // Leader 本地读标尺（同集群同轮，作为「一次进程内 handler 执行」的归一化基线；
            // 读路径不受 readFromFollower 配置影响，逐轮重复测量以吸收跨轮 JIT/GC 抖动）
            MeshPerfResult ref = measure("leaderLocalReadRef", cluster,
                    "config=" + scenario, key -> lg.read(0, new String[]{"GET", key}));
            out.add(ref);
            log.info("leaderLocalReadRef: {}", ref.summary());

            // 正确性冒烟：读回值必须等于预置值
            byte[] smoke = op.read(KEY_PREFIX + 0);
            String smokeStr = new String(smoke, StandardCharsets.ISO_8859_1);
            if (!smokeStr.contains(VALUE)) {
                throw new IllegalStateException("读回值与预置值不符: " + smokeStr);
            }
        } finally {
            cluster.stopAll();
        }
        return out;
    }

    /** 按模式构造「客户端读」：off 必 MOVED 双跳；readindex 先本地读、失败跟随 Leader。 */
    private static ReadOp readOpFor(MeshConfig.ReadFromFollower mode,
                                    MeshWriteGate followerGate, MeshWriteGate leaderGate) {
        if (mode == MeshConfig.ReadFromFollower.OFF) {
            return key -> {
                try {
                    followerGate.read(0, new String[]{"GET", key});
                    throw new IllegalStateException("off 模式 Follower 读不应成功");
                } catch (MovedToLeaderException expected) {
                    // 期望：第一跳拿 MOVED
                }
                return leaderGate.read(0, new String[]{"GET", key});
            };
        }
        return key -> {
            try {
                return followerGate.read(0, new String[]{"GET", key});
            } catch (MovedToLeaderException | LeaseInvalidException | RetryableMeshException e) {
                // 回落：客户端跟随 MOVED 到 Leader（真实客户端行为）
                return leaderGate.read(0, new String[]{"GET", key});
            }
        };
    }

    // ==================== 负载与统计 ====================

    /** 8 线程并发读，返回吞吐 + 延迟分位（含 JIT 预热，不计量）。 */
    private static MeshPerfResult measure(String scenario, MeshPerfCluster cluster,
                                          String params, ReadOp op) throws Exception {
        int perThread = Math.max(1, OPS / THREADS);
        // 预热：让 handler/序列化/取点路径完成 JIT
        int warm = Math.min(2000, perThread * THREADS);
        for (int i = 0; i < warm; i++) {
            op.read(KEY_PREFIX + (i % KEYS));
        }

        long[][] samples = new long[THREADS][perThread];
        AtomicInteger errors = new AtomicInteger();
        // 任期诊断：跑分期间若发生选举，延迟尖峰可归因
        Map<String, Long> termBefore = terms(cluster);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);
        long t0 = System.nanoTime();
        for (int t = 0; t < THREADS; t++) {
            final int tid = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long s = System.nanoTime();
                        try {
                            op.read(KEY_PREFIX + (i % KEYS));
                            samples[tid][i] = (System.nanoTime() - s) / 1_000;
                        } catch (Exception e) {
                            errors.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await();
        long wallMs = (System.nanoTime() - t0) / 1_000_000;
        pool.shutdown();
        Map<String, Long> termAfter = terms(cluster);
        String termState = termGrew(termBefore, termAfter) ? "termGrew" : "termStable";
        return MeshPerfResult.fromSamples(scenario, params + " " + termState,
                wallMs, merge(samples), errors.get());
    }

    // ==================== 工具 ====================

    private static String pickFollower(MeshPerfCluster cluster, String leaderId) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (String id : cluster.nodeIds()) {
                if (id.equals(leaderId)) {
                    continue;
                }
                MeshNode n = cluster.getNode(id);
                if (n != null && n.getRole() == MeshRole.FOLLOWER
                        && n.getLeaderId() != null && !n.getLeaderId().isEmpty()) {
                    return id;
                }
            }
            Thread.sleep(20);
        }
        return null;
    }

    private static MeshNode followerNode(MeshPerfCluster cluster, String followerId) {
        return cluster.getNode(followerId);
    }

    /** 等 Follower 本地 store 出现期望值（确认 apply 追平）。 */
    private static void awaitFollowerValue(MeshPerfCluster cluster, String followerId,
                                           String key, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object v = cluster.getStore(followerId).get(0, key);
            if (VALUE.equals(v)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Follower " + followerId + " 未在 " + timeoutMs + "ms 内追平");
    }

    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

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

    private static Map<String, Long> terms(MeshPerfCluster cluster) {
        Map<String, Long> terms = new HashMap<>();
        for (String id : cluster.nodeIds()) {
            MeshNode n = cluster.getNode(id);
            if (n != null) {
                terms.put(id, n.getCurrentTerm());
            }
        }
        return terms;
    }

    private static boolean termGrew(Map<String, Long> before, Map<String, Long> after) {
        for (Map.Entry<String, Long> e : before.entrySet()) {
            Long afterTerm = after.get(e.getKey());
            if (afterTerm != null && afterTerm > e.getValue()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 报告 ====================

    private static void writeReport() {
        if (RESULTS.isEmpty()) {
            return;
        }
        try {
            Path dir = Path.of("target", "test-metrics");
            Files.createDirectories(dir);
            String ts = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            String md = buildMarkdown();
            Files.writeString(dir.resolve("follower-read-perf-" + ts + ".md"), md);
            Files.writeString(dir.resolve("follower-read-perf.md"), md);
            log.info("结果已写入 target/test-metrics/follower-read-perf.md");
        } catch (Exception e) {
            log.error("写报告失败", e);
        }
    }

    private static String buildMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# mesh follower 读性能探针（3 节点 netty 总线，8 线程）\n\n");
        sb.append("- ops/轮: ").append(OPS).append("，线程: ").append(THREADS)
                .append("，key 数: ").append(KEYS).append("\n");
        sb.append("- 口径：客户端读 = 先问 Follower，MOVED 则跟随 Leader（均为进程内 gate 调用，")
                .append("不含客户端↔服务端网络）\n\n");
        sb.append("| 场景 | 参数 | ops | duration(ms) | ops/s | p50(μs) | p95(μs) | p99(μs) | ")
                .append("max(μs) | avg(μs) | err |\n");
        sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        synchronized (RESULTS) {
            for (MeshPerfResult r : RESULTS) {
                sb.append("| ").append(r.toMarkdownRow()).append(" |\n");
            }
        }
        sb.append("\n## Follower 读计数（本轮增量，来自 CLUSTER INFO 口径）\n\n");
        synchronized (COUNTER_LINES) {
            for (String line : COUNTER_LINES) {
                sb.append("- ").append(line).append("\n");
            }
        }
        sb.append("\n> `local` = 本地服务成功（未回落）；`fallback` = 回落 MOVED（客户端需跟随到 Leader）；")
                .append("`fetch` = 取读点 RPC 次数；`cacheHit` = 窗口命中；`coalesced` = single-flight 合并。\n");
        return sb.toString();
    }
}
