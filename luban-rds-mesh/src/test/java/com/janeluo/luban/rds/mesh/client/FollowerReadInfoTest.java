package com.janeluo.luban.rds.mesh.client;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14：INFO 暴露 follower 读计数（取点/命中/失效/合并/回落/本地/拒绝）。
 */
class FollowerReadInfoTest {

    /** 全部 8 个计数键（含预留的 not_ready_rejected）。 */
    private static final String[] KEYS = {
            "mesh_follower_read_index_fetch",
            "mesh_follower_read_cache_hit",
            "mesh_follower_read_cache_invalidated",
            "mesh_follower_read_coalesced",
            "mesh_follower_read_fallback_moved",
            "mesh_follower_read_local",
            "mesh_follower_read_rejected",
            "mesh_follower_read_not_ready_rejected"
    };

    @Test
    void infoContainsFollowerReadCounters() {
        String info = MeshClusterCommands.buildMeshInfoSection(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
        for (String key : KEYS) {
            assertTrue(info.contains(key + ":"), key + " missing in: " + info);
        }
    }

    /**
     * 默认配置（read-from-follower=OFF）下计数必须全为 0：
     * gate 不进入 follower 读路径，计数不应被任何路径推动。
     */
    @Test
    void offConfiguredNodeReportsZeroCounters() {
        MeshConfig config = MeshConfig.builder("nodeA")
                .addPeer("nodeA", "127.0.0.1:19736")
                .build();
        assertEquals(MeshConfig.ReadFromFollower.OFF, config.getReadFromFollower(),
                "默认必须为 OFF（零回归）");

        MeshNode node = new MeshNode(config, new MeshState(), null);
        assertEquals(0L, node.readIndexFetchCount());
        assertEquals(0L, node.readIndexCacheHitCount());
        assertEquals(0L, node.readIndexCacheInvalidationCount());
        assertEquals(0L, node.readIndexCoalescedCount());
        assertEquals(0L, node.followerReadFallbackCount());
        assertEquals(0L, node.followerReadLocalCount());
        assertEquals(0L, node.followerReadRejectedCount());

        String info = MeshClusterCommands.buildMeshInfoSection(
                node.readIndexFetchCount(),
                node.readIndexCacheHitCount(),
                node.readIndexCacheInvalidationCount(),
                node.readIndexCoalescedCount(),
                node.followerReadFallbackCount(),
                node.followerReadLocalCount(),
                node.followerReadRejectedCount(),
                0L);
        for (String key : KEYS) {
            assertTrue(info.contains(key + ":0"), key + " 应为 0: " + info);
        }
    }
}
