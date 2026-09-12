package com.janeluo.luban.rds.mesh.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * follower 读一致性（fix-mesh-follower-read）：
 * <ul>
 *   <li>{@code cache-ms=0}：写 ack 后立即从 follower 读必须看到新值（严格读己之写）；</li>
 *   <li>并发写下版本号单调不减。</li>
 * </ul>
 *
 * <p>命名用 {@code *IntegrationTest} 而非计划里的 {@code *IT}：本项目 surefire 未配置 includes/failsafe，
 * 走 Maven 默认匹配（{@code *Test}/{@code *Tests}/{@code *TestCase}），{@code *IT} 默认不会在
 * {@code test} 阶段执行。{@code ThreeNodeIntegrationTest} 即按此约定命名并实际运行。</p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class FollowerReadConsistencyIntegrationTest {

    @Test
    void readYourWrites_noCache() throws Exception {
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(0L, 500L))) {
            String leader = cluster.awaitLeader();
            cluster.write(leader, "k1", "v1");

            String follower = cluster.awaitFollowerExcluding(leader);
            byte[] followerResp = cluster.get(follower, "k1");
            assertArrayEquals(cluster.get(leader, "k1"), followerResp,
                    "写确认后 follower 读必须看到新值");
            // 证明走的是新路径（readIndex RPC + apply 屏障 + 本地执行），而非 gate 回落 MOVED
            assertTrue(cluster.fetchCount(follower) >= 1,
                    "follower 读必须实际进入 readIndex 取点路径");
        }
    }

    @Test
    void concurrentWrites_followerReadsAreMonotonic() throws Exception {
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(0L, 500L))) {
            String leader = cluster.awaitLeader();
            String follower = cluster.awaitFollowerExcluding(leader);

            long lastSeen = 0;
            for (int i = 1; i <= 50; i++) {
                cluster.write(leader, "counter", String.valueOf(i));
                Long seen = cluster.getAsLong(follower, "counter");
                if (seen != null) {
                    assertTrue(seen >= lastSeen, "follower 读版本号不得回退: " + seen + " < " + lastSeen);
                    lastSeen = seen;
                }
            }
            assertNotNull(cluster.get(follower, "counter"));
        }
    }

    /**
     * 有界陈旧窗口的上界：预热缓存窗口后写入新值，窗口过期再读必须看到新值。
     * <p>这是把「陈旧上界 = 缓存窗口 + RTT」钉死的用例——没有它，窗口可能悄悄退化成无界缓存。</p>
     * <p>此处的 {@code Thread.sleep(cacheMs + 200)} 不是「等它变好」的重试，而是被测语义本身
     * （必须让窗口自然过期），随后是一次性断言，不做轮询。</p>
     */
    @Test
    void boundedStaleness_windowExpires_seeNewValue() throws Exception {
        long cacheMs = 100L;
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(cacheMs, 500L))) {
            String leader = cluster.awaitLeader();
            String follower = cluster.awaitFollowerExcluding(leader);

            cluster.write(leader, "k2", "v1");
            // 预热：建立缓存窗口（读到 v1 即证明 follower 本地读成功）
            assertArrayEquals("$2\r\nv1\r\n".getBytes(StandardCharsets.ISO_8859_1),
                    cluster.get(follower, "k2"));
            cluster.write(leader, "k2", "v2");

            // 窗口内允许读到 v1（有界陈旧），窗口过后必须读到 v2
            Thread.sleep(cacheMs + 200);
            assertArrayEquals("$2\r\nv2\r\n".getBytes(StandardCharsets.ISO_8859_1),
                    cluster.get(follower, "k2"),
                    "缓存窗口过期后必须读到新值（陈旧上界 = 窗口 + RTT）");
        }
    }

    /**
     * 窗口复用：窗口内 20 次读最多再取一次读点（single-flight + 短缓存），防窗口退化为「每次读都取点」。
     */
    @Test
    void windowReuse_singleFetchForBurst() throws Exception {
        long cacheMs = 500L;
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(cacheMs, 500L))) {
            String leader = cluster.awaitLeader();
            String follower = cluster.awaitFollowerExcluding(leader);
            cluster.write(leader, "k3", "v1");
            // 预热窗口；读到值本身也证明本地读可用（否则后续 fetchCount 断言会假通过）
            assertArrayEquals("$2\r\nv1\r\n".getBytes(StandardCharsets.ISO_8859_1),
                    cluster.get(follower, "k3"));

            long before = cluster.fetchCount(follower);
            for (int i = 0; i < 20; i++) {
                assertNotNull(cluster.get(follower, "k3"), "窗口内读应持续命中本地");
            }
            long after = cluster.fetchCount(follower);

            assertTrue(after - before <= 1,
                    "窗口内 20 次读最多再取一次读点（single-flight + 缓存），实际=" + (after - before));
        }
    }
}
