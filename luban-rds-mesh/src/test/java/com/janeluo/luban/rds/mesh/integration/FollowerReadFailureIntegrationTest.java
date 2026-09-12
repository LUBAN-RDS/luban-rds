package com.janeluo.luban.rds.mesh.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * follower 读故障注入（fix-mesh-follower-read）。
 * <p>核心断言：<b>任何情况下 follower 读都不得返回"未达 readIndex 的状态"</b>——
 * 拿到读点就等屏障，等不到就回落 MOVED。</p>
 *
 * <p>命名用 {@code *IntegrationTest} 而非计划里的 {@code *IT}：本项目 surefire 走 Maven 默认匹配，
 * {@code *IT} 不会在 {@code test} 阶段执行（详见 {@link FollowerReadConsistencyIntegrationTest} 注释）。</p>
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class FollowerReadFailureIntegrationTest {

    /**
     * 分区 follower 绝不能返回旧值：隔离后写新值，follower 要么回落 MOVED（null），
     * 要么（不可能地）读到新值——唯一禁止项是返回旧值 v1。
     */
    @Test
    void partitionedFollower_neverReturnsStaleValue() throws Exception {
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(0L, 300L))) {
            String leader = cluster.awaitLeader();
            String follower = cluster.awaitFollowerExcluding(leader);

            cluster.write(leader, "p1", "v1");
            cluster.get(follower, "p1");

            cluster.partitionNode(follower);                 // 掐断该 follower 的双向收发
            cluster.write(leader, "p1", "v2");               // 多数派 = Leader + 另一副本，仍可提交

            byte[] resp = cluster.getOrNull(follower, "p1");
            // 允许：MOVED/错误（回落，表现为 null）；不允许：返回旧值 v1
            assertNotEquals("$2\r\nv1\r\n",
                    resp == null ? null : new String(resp, StandardCharsets.ISO_8859_1),
                    "分区 follower 绝不能返回旧值");
            // 证明隔离后的读确实进入并失败于 readIndex 取点路径：预热 1 次 + 分区后 1 次
            assertTrue(cluster.fetchCount(follower) >= 2,
                    "分区 follower 的读必须实际尝试取读点后回落，而非直达本地陈旧值");
        }
    }

    /**
     * kill Leader 后 follower 读回落，待新 Leader 选出并写入新值后恢复。
     *
     * <p>此处不断言「kill 后立即 MOVED」：那一刻剩余节点可能已选出新 Leader 并对自己本地读
     * （返回仍是已提交的 v1，并非陈旧），该中间态不确定，强行断言会引入 flake。
     * 确定性的断言是「新 Leader 稳定 + 新写落定后，读必然恢复到新值」。</p>
     */
    @Test
    void leaderKilled_followerReadFallsBack_thenRecovers() throws Exception {
        try (MeshIntegrationHarness cluster = MeshIntegrationHarness.start(
                MeshIntegrationHarness.followerReadConfig(0L, 300L))) {
            String leader = cluster.awaitLeader();
            cluster.write(leader, "p2", "v1");

            cluster.killNode(leader);

            String newLeader = cluster.awaitLeaderExcluding(leader);
            assertNotNull(newLeader, "kill Leader 后其余两节点应选出新 Leader");
            cluster.write(newLeader, "p2", "v2");

            // 读目标显式选「新 Leader 之外仍存活的副本」，确保读走 follower 本地读路径，
            // 而不是碰巧新 Leader 就是原 follower 时的 Leader 本地读。
            String readNode = cluster.awaitFollowerExcluding(newLeader);
            byte[] resp = cluster.getEventually(readNode, "p2",
                    "$2\r\nv2\r\n".getBytes(StandardCharsets.ISO_8859_1), 10_000);
            assertNotNull(resp, "新 Leader 选举 + 新写落定后 follower 读应恢复并读到新值");
        }
    }
}
