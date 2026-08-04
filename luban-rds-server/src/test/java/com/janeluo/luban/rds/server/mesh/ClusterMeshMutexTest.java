package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cluster / mesh 互斥校验测试（阶段 12 / DESIGN §8）。
 * <p>
 * 验证同一进程不能同时启用 cluster 与 mesh：
 * 构造 {@link NettyRedisServer} 时若 config.clusterEnabled && config.meshEnabled，
 * 应抛 {@link IllegalStateException}（DESIGN §8：cluster 与 mesh 在 server 侧由配置互斥）。
 * </p>
 *
 * <h3>测试覆盖</h3>
 * <ul>
 *   <li>cluster + mesh 同时启用 → 构造抛 IllegalStateException；</li>
 *   <li>异常消息明确指出互斥约束。</li>
 * </ul>
 *
 * @author janeluo
 * @since 阶段 12
 */
class ClusterMeshMutexTest {

    @TempDir
    Path tempDir;

    @Test
    void clusterAndMeshBothEnabled_throwsIllegalState() {
        RdsConfig config = new RdsConfig();
        config.setDir(tempDir.toString());
        config.setPort(0); // 不实际绑定
        config.setClusterEnabled(true);
        config.setMeshEnabled(true);
        // mesh peers 必须配置（否则 MeshBootstrap 会先因 peers 空报错，但互斥校验在前，应先触发）
        config.setMeshPeers("n1@127.0.0.1:11000");
        config.setMeshSelfNodeId("n1");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new NettyRedisServer(config),
                "cluster 与 mesh 同时启用应抛 IllegalStateException");

        // 异常消息应明确指出互斥约束
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        assertTrue(msg.contains("cluster") && msg.contains("mesh"),
                "异常消息应提及 cluster 与 mesh 互斥: " + msg);
        assertTrue(msg.contains("互斥") || msg.contains("不能同时") || msg.toLowerCase().contains("mutex"),
                "异常消息应说明互斥关系: " + msg);
    }
}
