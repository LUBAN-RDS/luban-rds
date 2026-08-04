package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusServer;
import com.janeluo.luban.rds.mesh.client.MeshClientRedirector;
import com.janeluo.luban.rds.mesh.client.MeshClusterCommands;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import com.janeluo.luban.rds.mesh.replication.SnapshotManager;

/**
 * Mesh 装配产物容器（DESIGN.md §6 / IMPLEMENTATION_PLAN 阶段 12）。
 * <p>
 * 由 {@link MeshBootstrap#bootstrap} 装配完成后返回，聚合 mesh 运行所需的全部组件实例，
 * 供 {@code NettyRedisServer.initMeshMode} 注入到 {@code RedisServerHandler} 并在
 * {@code start()}/{@code stop()} 时管理生命周期。
 * </p>
 *
 * <h3>持有组件</h3>
 * <ul>
 *   <li>{@link MeshNode}：Raft 节点主体（选举 + 复制 + apply）；{@code start/stop} 由装配方驱动。</li>
 *   <li>{@link MeshWriteGate}：handler 级写/读门面（写 propose、读租约校验、MOVED 生成）。</li>
 *   <li>{@link MeshClientRedirector}：{@code MovedToLeaderException} → {@code -MOVED/-MESHDOWN}。</li>
 *   <li>{@link MeshClusterCommands}：{@code CLUSTER SLOTS/NODES/INFO} 响应生成。</li>
 *   <li>{@link MeshLifecycleListener}：角色变更回调。</li>
 *   <li>{@link MeshBusClient}/{@link MeshBusServer}：总线客户端/服务端（{@code start/stop} 由装配方驱动）。</li>
 *   <li>{@link SnapshotManager}：快照管理器（可选，便于 {@code stop} 时关闭）。</li>
 * </ul>
 *
 * @author janeluo
 * @since 阶段 12
 */
public class MeshAssembly {

    private final MeshNode meshNode;
    private final MeshWriteGate writeGate;
    private final MeshClientRedirector clientRedirector;
    private final MeshClusterCommands clusterCommands;
    private final MeshLifecycleListener lifecycleListener;
    private final MeshBusClient busClient;
    private final MeshBusServer busServer;
    private final SnapshotManager snapshotManager;

    public MeshAssembly(MeshNode meshNode, MeshWriteGate writeGate,
                        MeshClientRedirector clientRedirector, MeshClusterCommands clusterCommands,
                        MeshLifecycleListener lifecycleListener,
                        MeshBusClient busClient, MeshBusServer busServer,
                        SnapshotManager snapshotManager) {
        this.meshNode = meshNode;
        this.writeGate = writeGate;
        this.clientRedirector = clientRedirector;
        this.clusterCommands = clusterCommands;
        this.lifecycleListener = lifecycleListener;
        this.busClient = busClient;
        this.busServer = busServer;
        this.snapshotManager = snapshotManager;
    }

    public MeshNode getMeshNode() {
        return meshNode;
    }

    public MeshWriteGate getWriteGate() {
        return writeGate;
    }

    public MeshClientRedirector getClientRedirector() {
        return clientRedirector;
    }

    public MeshClusterCommands getClusterCommands() {
        return clusterCommands;
    }

    public MeshLifecycleListener getLifecycleListener() {
        return lifecycleListener;
    }

    public MeshBusClient getBusClient() {
        return busClient;
    }

    public MeshBusServer getBusServer() {
        return busServer;
    }

    public SnapshotManager getSnapshotManager() {
        return snapshotManager;
    }
}
