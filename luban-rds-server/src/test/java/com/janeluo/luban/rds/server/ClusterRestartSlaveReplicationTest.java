package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群冷重启后恢复 slave 身份时是否真正发起复制连接的回归测试。
 * <p>
 * 背景：{@code restoreClusterFromConfig} 从 nodes.conf 恢复了 MYSELF 的 slave 角色与
 * masterNodeId，但复制连接只在运行时事件（CLUSTER REPLICATE 命令 / failover）中建立。
 * 全集群重启后 slave 节点虽在 CLUSTER NODES 中显示为 slave，复制层却是孤立 master
 * （connected_slaves:0），写入不传播、从节点无数据，Redisson 会话读打到无数据从节点即
 * 抛 UnknownSessionException。
 * </p>
 * <p>
 * 修复：{@code NettyRedisServer.initClusterMode} 末尾对恢复为 slave 的 MYSELF 主动调用
 * {@code replicationCoordinator.replicateTo(master)}，对齐 Redis 重启后 replicationResume。
 * 本测试观测 {@link ReplicationCoordinator#isSlave()} 是否在构造（启动）后为 true。
 * </p>
 * <p>
 * 说明：master 地址指向一个无响应端口（{@code 127.0.0.1:9}）。SlaveReplicationService.start()
 * 仅启动异步重试客户端，不会因连接失败而抛出，{@code slaveService} 仍被赋值，
 * {@code isSlave()} 稳定返回 true，使断言确定且无网络依赖。
 * </p>
 */
class ClusterRestartSlaveReplicationTest {

    private static final String MASTER_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SLAVE_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @TempDir
    Path tempDir;

    /**
     * 恢复为 slave 的节点应在启动后发起复制（isSlave==true）。
     */
    @Test
    @DisplayName("冷重启恢复 slave 身份后应发起复制连接")
    void restoredSlaveStartsReplication() throws IOException {
        writeNodesConf(true);  // MYSELF 为 slave
        NettyRedisServer server = buildServer();

        ReplicationCoordinator coordinator = server.getReplicationCoordinator();
        assertTrue(coordinator != null && coordinator.isSlave(),
                "恢复 slave 身份的节点应在启动后处于 slave 复制状态");

        coordinator.stopSlave();
    }

    /**
     * 恢复为 master 的节点不应发起复制（isSlave==false）——对照组，确保修复不误伤 master。
     */
    @Test
    @DisplayName("冷重启恢复 master 身份不应发起复制")
    void restoredMasterDoesNotStartReplication() throws IOException {
        writeNodesConf(false);  // MYSELF 为 master
        NettyRedisServer server = buildServer();

        ReplicationCoordinator coordinator = server.getReplicationCoordinator();
        assertFalse(coordinator != null && coordinator.isSlave(),
                "恢复 master 身份的节点不应处于 slave 复制状态");
    }

    /**
     * 无 nodes.conf 的首次启动（MYSELF 为 master）不应发起复制。
     */
    @Test
    @DisplayName("首次启动（无 nodes.conf）不发起复制")
    void firstStartNoReplication() {
        // 不写 nodes.conf，让 loadClusterConfigFromFile 返回 null
        NettyRedisServer server = buildServer();

        ReplicationCoordinator coordinator = server.getReplicationCoordinator();
        assertFalse(coordinator != null && coordinator.isSlave(),
                "首次启动的 master 节点不应处于 slave 复制状态");
    }

    // ==================== 辅助方法 ====================

    private NettyRedisServer buildServer() {
        RdsConfig config = new RdsConfig();
        config.setBind("127.0.0.1");
        config.setPort(0);  // 不实际绑定（构造阶段不启动 netty）
        config.setPersistMode("none");
        config.setDir(tempDir.toAbsolutePath().toString());
        config.setDatabases(1);
        config.setClusterEnabled(true);
        config.setClusterConfigFile("nodes.conf");
        return new NettyRedisServer(config);
    }

    /**
     * 写入 nodes.conf。MYSELF 节点 ID 固定为 SLAVE_ID（slave 场景）或 MASTER_ID（master 场景）。
     */
    private void writeNodesConf(boolean myselfAsSlave) throws IOException {
        String myselfId = myselfAsSlave ? SLAVE_ID : MASTER_ID;
        String myselfFlags = myselfAsSlave ? "myself,slave" : "myself,master";
        String myselfMaster = myselfAsSlave ? MASTER_ID : "-";
        String masterFlags = "master";

        StringBuilder sb = new StringBuilder();
        sb.append("# Luban-RDS Cluster Configuration\n");
        sb.append("# Format: <nodeid> <ip:port@cport> <flags> <master> <ping> <pong> <epoch> <link> <slots>\n");
        sb.append("# Current Epoch: 3\n");
        sb.append("# My Config Epoch: ").append(myselfAsSlave ? "3" : "1").append("\n");
        sb.append("# Last Vote Epoch: 0\n");
        sb.append("vars currentEpoch 3 lastVoteEpoch 0\n");
        // master 节点行（持有槽位 0-5460），地址 127.0.0.1:9（无响应端口）
        sb.append(MASTER_ID).append(" 127.0.0.1:9@10009 ")
                .append(masterFlags).append(" - 0 0 1 connected 0-5460\n");
        // MYSELF 节点行
        sb.append(myselfId).append(" 127.0.0.1:9736@19736 ")
                .append(myselfFlags).append(' ').append(myselfMaster)
                .append(" 0 0 ").append(myselfAsSlave ? "3" : "1").append(" connected\n");

        Files.write(tempDir.resolve("nodes.conf"), sb.toString().getBytes());
    }
}
