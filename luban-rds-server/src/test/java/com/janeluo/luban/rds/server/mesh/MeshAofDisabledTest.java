package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.persistence.impl.AofPersistService;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * P1-17（2026-09-11 mesh 审计）：mesh 模式 AOF 退役（DESIGN v1.2）——appendonly 配置忽略，
 * 防止"AOF 写入却不经 Raft"的双持久化源冲突（EXEC 路径已被 P0-1 事务禁用堵死，
 * 此项为配置面强制收口）。持久化仅由 WAL + SnapshotManager RDB 承担。
 */
class MeshAofDisabledTest {

    /** AOF 持有文件句柄，Windows 上无法删除——忽略清理失败。 */
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.NEVER)
    Path tempDir;

    @Test
    void meshModeForcesAofOff() {
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(findRandomPort());
        config.setMeshEnabled(true);
        config.setMeshPeers("n1@127.0.0.1:" + (config.getPort() + 100) + ":" + config.getPort());
        config.setMeshSelfNodeId("n1");
        config.setMeshServicePort(config.getPort());
        config.setPersistMode("aof");

        NettyRedisServer server = new NettyRedisServer(config);
        try {
            assertFalse(server.getPersistService() instanceof AofPersistService,
                    "mesh 模式下 appendonly 应被忽略（不得装配 AofPersistService）");
        } finally {
            server.stop();
        }
    }

    @Test
    void nonMeshKeepsAof() {
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(findRandomPort());
        config.setMeshEnabled(false);
        config.setPersistMode("aof");

        NettyRedisServer server = new NettyRedisServer(config);
        try {
            org.junit.jupiter.api.Assertions.assertTrue(
                    server.getPersistService() instanceof AofPersistService,
                    "非 mesh 模式 appendonly 行为不变");
        } finally {
            server.stop();
        }
    }

    private static int findRandomPort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            return 19736;
        }
    }
}
