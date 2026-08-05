package com.janeluo.luban.rds.benchmark.mesh;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.server.NettyRedisServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3 节点 mesh 全栈测试夹具：单 JVM 内启动 3 个 mesh-enabled {@link NettyRedisServer}。
 * <p>
 * 对齐 cluster 套件 {@code TestCluster} 的风格（start/stop/端口查询），但走真实 mesh 装配链
 * （{@code MeshBootstrap} → MeshNode → Raft 选举），客户端经 {@code ClusterAwareClient}
 * 跟随 MOVED 直连 Leader。
 * </p>
 *
 * <h3>节点配置要点</h3>
 * <ul>
 *   <li>每节点独立 data dir（{@code <tmp>/luban-mesh-bench-<ts>/<nodeId>}）：raft-nodes.conf /
 *       dump.rdb 由 mesh 装配层落盘，三节点必须隔离，启动前清空旧状态；</li>
 *   <li>{@code mesh-peers} 显式给出第三段 servicePort（单机多实例必需，否则 service 地址塌缩
 *       触发 D1 启动校验失败）；</li>
 *   <li>{@code persist-mode=none}：server 级 RDB/AOF 退役（dump.rdb 由 SnapshotManager 管理，
 *       避免双写者冲突）；</li>
 *   <li>快速选举参数（election 100-200ms / heartbeat 50ms / lease 400ms），与 mesh 模块
 *       协议层套件同参，便于两套数字对照。</li>
 * </ul>
 */
public class MeshTestCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MeshTestCluster.class);

    private static final String N1 = "bmN1";
    private static final String N2 = "bmN2";
    private static final String N3 = "bmN3";
    private static final String HOST = "127.0.0.1";

    /** servicePort → server（RESP 端口即对外服务端口）。 */
    private final Map<Integer, NettyRedisServer> servers = new LinkedHashMap<>();
    /** servicePort → data dir。 */
    private final Map<Integer, Path> dataDirs = new LinkedHashMap<>();
    /** servicePort 列表（N1, N2, N3 顺序）。 */
    private final List<Integer> servicePorts = new ArrayList<>();
    private final int baseServicePort;
    private final int baseBusPort;
    /** raft-nodes.conf 落盘开关：默认跟随 -Dbench.mesh.persist，可 per-scenario 覆盖。 */
    private boolean persistEnabled = Boolean.getBoolean("bench.mesh.persist");
    private Path rootDir;
    private boolean started;

    public MeshTestCluster(int baseServicePort, int baseBusPort) {
        this.baseServicePort = baseServicePort;
        this.baseBusPort = baseBusPort;
        for (int i = 0; i < 3; i++) {
            servicePorts.add(baseServicePort + i);
        }
    }

    /** 覆盖默认落盘行为（{@link RedisVsMeshBenchmark} 用于同 JVM 内对照持久化开/关）。 */
    public void setPersistEnabled(boolean persistEnabled) {
        this.persistEnabled = persistEnabled;
    }

    // ==================== 生命周期 ====================

    /** 启动 3 节点 mesh 集群并等待 Leader 就绪（写探针）。 */
    public void start() throws Exception {
        if (started) {
            return;
        }
        prepareDataDirs();
        String peers = N1 + "@" + HOST + ":" + baseBusPort + ":" + baseServicePort
                + "," + N2 + "@" + HOST + ":" + (baseBusPort + 1) + ":" + (baseServicePort + 1)
                + "," + N3 + "@" + HOST + ":" + (baseBusPort + 2) + ":" + (baseServicePort + 2);
        for (int i = 0; i < 3; i++) {
            String nodeId = nodeIdAt(i);
            int svcPort = baseServicePort + i;
            RdsConfig config = configFor(nodeId, svcPort, peers);
            NettyRedisServer server = new NettyRedisServer(config);
            server.start();
            servers.put(svcPort, server);
            log.info("mesh 节点 {} 已启动: servicePort={}, busPort={}", nodeId, svcPort, baseBusPort + i);
        }
        started = true;
        waitForWritable(10_000);
        log.info("mesh 集群就绪: 服务端口 {}", servicePorts);
    }

    /** 停止全部节点并清理临时 data dir。 */
    public void stop() {
        for (NettyRedisServer server : servers.values()) {
            try {
                server.stop();
            } catch (Exception e) {
                log.warn("停止 mesh 节点失败", e);
            }
        }
        servers.clear();
        started = false;
        if (rootDir != null) {
            try {
                deleteRecursively(rootDir);
            } catch (IOException e) {
                log.warn("清理临时目录失败: {}", rootDir, e);
            }
        }
    }

    /** 停止单个节点（故障注入）——其余 2 节点应触发 failover 选出新 Leader。 */
    public void stopNode(int servicePort) {
        NettyRedisServer server = servers.remove(servicePort);
        if (server == null) {
            return;
        }
        server.stop();
        log.info("mesh 节点已停止: servicePort={}", servicePort);
    }

    // ==================== 查询 ====================

    public List<Integer> getServicePorts() {
        return new ArrayList<>(servicePorts);
    }

    public int getBaseServicePort() {
        return baseServicePort;
    }

    /**
     * 探测当前 Leader 的 servicePort：对每节点发 SET，返回 +OK 者为 Leader
     * （Follower 会回 -MOVED/-MESHDOWN）。
     *
     * @return Leader 的 servicePort；无 Leader 时返回 -1
     */
    public int findLeaderPort() {
        for (int port : servicePorts) {
            NettyRedisClient client = new NettyRedisClient(HOST, port);
            try {
                client.connect();
                Object result = client.executeCommand("SET", "perf:leader-probe", "1");
                String s = result == null ? "" : result.toString();
                if (s.contains("OK") && !s.contains("MOVED") && !s.contains("MESHDOWN")) {
                    return port;
                }
            } catch (Exception e) {
                log.debug("leader 探测节点 {}:{} 失败: {}", HOST, port, e.getMessage());
            } finally {
                try {
                    client.disconnect();
                } catch (Exception ignore) {
                }
            }
        }
        return -1;
    }

    // ==================== 内部 ====================

    private void prepareDataDirs() throws IOException {
        rootDir = Files.createTempDirectory("luban-mesh-bench-");
        for (int i = 0; i < 3; i++) {
            Path dir = rootDir.resolve(nodeIdAt(i));
            Files.createDirectories(dir);
            dataDirs.put(baseServicePort + i, dir);
        }
    }

    private RdsConfig configFor(String nodeId, int svcPort, String peers) {
        RdsConfig config = new RdsConfig();
        config.setPort(svcPort);
        config.setMeshEnabled(true);
        config.setMeshPeers(peers);
        config.setMeshSelfNodeId(nodeId);
        config.setMeshElectionTimeoutMinMs(100);
        config.setMeshElectionTimeoutMaxMs(200);
        config.setMeshHeartbeatIntervalMs(50);
        config.setMeshLeaseDurationMs(400);
        // 默认关落盘：raft-nodes.conf 每次 propose 全量序列化+fsync 是写路径主要成本
        // （O(log) 每写，日志越长越慢）。-Dbench.mesh.persist=true 恢复生产行为。
        config.setMeshPersistEnabled(persistEnabled);
        config.setPersistMode("none"); // mesh 的 dump.rdb 由 SnapshotManager 管理，server 级持久化退役
        config.setDir(dataDirs.get(svcPort).toString());
        return config;
    }

    private static String nodeIdAt(int i) {
        return i == 0 ? N1 : (i == 1 ? N2 : N3);
    }

    /** 轮询写探针直到集群可写（有 Leader 且 propose 成功）。 */
    private void waitForWritable(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int leaderPort = findLeaderPort();
            if (leaderPort >= 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("mesh 集群未在 " + timeoutMs + "ms 内选出 Leader");
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<Path> all = new ArrayList<>();
        try (java.util.stream.Stream<Path> s = Files.walk(path)) {
            s.forEach(all::add);
        }
        all.sort(Comparator.reverseOrder());
        for (Path p : all) {
            Files.deleteIfExists(p);
        }
    }

    @Override
    public void close() {
        stop();
    }
}
