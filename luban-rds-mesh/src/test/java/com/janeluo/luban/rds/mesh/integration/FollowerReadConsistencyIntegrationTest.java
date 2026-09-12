package com.janeluo.luban.rds.mesh.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

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
}
