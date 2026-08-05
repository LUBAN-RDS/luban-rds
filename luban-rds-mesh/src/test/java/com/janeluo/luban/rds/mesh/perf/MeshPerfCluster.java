package com.janeluo.luban.rds.mesh.perf;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshBusServer;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * mesh 集群性能夹具（单 JVM 内起 1 或 3 个真实 {@link MeshNode}）。
 * <p>
 * 与集成测试 {@code ThreeNodeIntegrationTest} 的夹具同构（MeshNode + MeshState +
 * DefaultMemoryStore + DefaultCommandHandler + LogApplier），但总线可插拔：
 * <ul>
 *   <li><b>netty（默认）</b>：每节点真实 {@link MeshBusServer} + {@link MeshBusClient}，TCP 回环。
 *       覆盖完整传输栈——MeshBusCodec 编解码、Netty EventLoop、回环 RTT；</li>
 *   <li><b>memory</b>：{@link RoutingBus} 内存直投（不经 Netty），量化传输层开销。</li>
 * </ul>
 * </p>
 * <p>
 * 启动顺序（netty 模式）：先起所有 busServer（监听端口）→ 再起所有 busClient（建连成功）→
 * 最后 node.start() 触发 ElectionTimer 选举。若建连未就绪即开始选举，心跳/投票帧会因
 * {@code MeshBusClient.send} 目标无 Channel 而静默丢弃，选举会被推迟到重连成功后。
 * </p>
 * <p>
 * 可选 fsync persistHook（{@code MeshPerfCluster(..., fsync=true)}）：propose 追加日志后同步
 * 写 marker 文件并 {@code FileChannel.force(true)}，近似生产装配层（MeshConfigPersister）的
 * 落盘成本；默认 no-op 与集成测试一致。
 * </p>
 */
public class MeshPerfCluster {

    private static final Logger log = LoggerFactory.getLogger(MeshPerfCluster.class);

    public static final String A = "perfA";
    public static final String B = "perfB";
    public static final String C = "perfC";

    private static final String HOST = "127.0.0.1";

    /** 节点数：1 或 3。 */
    private final int nodeCount;
    private final boolean nettyBus;
    private final boolean fsync;
    private final Path fsyncDir;

    private final Map<String, MeshNode> nodes = new LinkedHashMap<>();
    private final Map<String, MemoryStore> stores = new LinkedHashMap<>();
    private final Map<String, MeshBusClient> busClients = new LinkedHashMap<>();
    private final Map<String, MeshBusServer> busServers = new LinkedHashMap<>();
    private final Map<String, Integer> nodePorts = new LinkedHashMap<>();

    /**
     * @param nodeCount 节点数（1=单节点基线；3=标准集群）
     * @param nettyBus  true=真实 Netty TCP 回环；false=内存路由
     * @param basePort  netty 模式下 bus 端口基址（节点依次占用 basePort, basePort+1, ...）
     * @param fsync     true=注入真实 fsync persistHook（近似生产落盘成本）
     */
    public MeshPerfCluster(int nodeCount, boolean nettyBus, int basePort, boolean fsync) {
        if (nodeCount != 1 && nodeCount != 3) {
            throw new IllegalArgumentException("nodeCount 仅支持 1 或 3: " + nodeCount);
        }
        this.nodeCount = nodeCount;
        this.nettyBus = nettyBus;
        this.fsync = fsync;
        this.fsyncDir = fsync ? createFsyncDir() : null;
        for (int i = 0; i < nodeCount; i++) {
            nodePorts.put(nodeIds().get(i), basePort + i);
        }
    }

    // ==================== 生命周期 ====================

    /** 装配并启动全部节点（选举由 ElectionTimer 自动驱动）。 */
    public void startAll() throws InterruptedException {
        for (String nodeId : nodeIds()) {
            addNode(nodeId);
        }
        if (nettyBus) {
            // 先 server 后 client：保证 peer 已监听再建连，避免连接失败进指数退避拖延选举
            for (MeshBusServer s : busServers.values()) {
                s.start();
            }
            for (MeshNode n : nodes.values()) {
                busClients.get(n.getNodeId()).start(peerEndpoints());
            }
            if (nodeCount > 1) {
                awaitAllConnected(5_000);
            }
        }
        for (MeshNode n : nodes.values()) {
            n.start();
        }
    }

    /** 停止全部节点与总线（测试清理）。 */
    public void stopAll() {
        for (MeshNode n : nodes.values()) {
            try {
                n.stop();
            } catch (Exception ignored) {
                // 清理阶段忽略单节点异常
            }
        }
        for (MeshBusClient c : busClients.values()) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        for (MeshBusServer s : busServers.values()) {
            try {
                s.stop();
            } catch (Exception ignored) {
            }
        }
        if (fsyncDir != null) {
            try {
                Files.deleteIfExists(fsyncDir);
            } catch (IOException ignored) {
            }
        }
    }

    /** 停止单个节点（含其 bus server/client）——故障注入用。 */
    public void stopNode(String nodeId) {
        MeshNode n = nodes.get(nodeId);
        if (n == null) {
            return;
        }
        try {
            n.stop();
        } catch (Exception e) {
            log.warn("stopNode {} 失败", nodeId, e);
        }
        MeshBusClient c = busClients.get(nodeId);
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        MeshBusServer s = busServers.get(nodeId);
        if (s != null) {
            try {
                s.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== 节点装配 ====================

    private void addNode(String nodeId) {
        MeshConfig config = configFor(nodeId);
        MemoryStore store = new DefaultMemoryStore();
        DefaultCommandHandler cmdHandler = new DefaultCommandHandler();
        LogApplier applier = new LogApplier(cmdHandler, store);

        MeshBusHandler busHandler = new MeshBusHandler();
        MeshBusClient client = nettyBus
                ? new MeshBusClient(nodeId, busHandler)
                : new RoutingBus(nodeId, nodes);
        MeshNode node = new MeshNode(config, new MeshState(), client,
                new RaftStateMachine(), applier, store);
        // 总线入站 → 节点（阶段 2 消费者注入；漏注册会导致所有 Raft RPC 到站即丢）
        busHandler.setMessageConsumer(node::onMessage);
        if (fsync) {
            node.setPersistHook(fsyncHook(nodeId));
        }

        nodes.put(nodeId, node);
        stores.put(nodeId, store);
        busClients.put(nodeId, client);
        if (nettyBus) {
            busServers.put(nodeId, new MeshBusServer(nodeId, nodePorts.get(nodeId), busHandler));
        }
    }

    /** 节点配置：心跳 50ms / 选举超时 100-200ms / 租约 400ms（与集成测试同参）。 */
    private MeshConfig configFor(String nodeId) {
        MeshConfig.Builder b = MeshConfig.builder(nodeId)
                .electionTimeout(100, 200)
                .heartbeatIntervalMs(50)
                .leaseDurationMs(400);
        for (Map.Entry<String, Integer> e : nodePorts.entrySet()) {
            b.addPeer(e.getKey(), HOST + ":" + e.getValue());
        }
        return b.build();
    }

    /** nodeId → PeerEndpoint（客户端自动过滤自身）。 */
    private Map<String, MeshBusClient.PeerEndpoint> peerEndpoints() {
        Map<String, MeshBusClient.PeerEndpoint> eps = new HashMap<>();
        for (Map.Entry<String, Integer> e : nodePorts.entrySet()) {
            eps.put(e.getKey(), new MeshBusClient.PeerEndpoint(HOST, e.getValue()));
        }
        return eps;
    }

    /** 等待所有节点两两建连完成（netty 模式选举前置条件）。 */
    private void awaitAllConnected(long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (MeshNode n : nodes.values()) {
                for (String peer : nodeIds()) {
                    if (!peer.equals(n.getNodeId())
                            && !busClients.get(n.getNodeId()).isConnected(peer)) {
                        all = false;
                        break;
                    }
                }
                if (!all) {
                    break;
                }
            }
            if (all) {
                log.info("mesh 总线全部节点两两建连完成");
                return;
            }
            Thread.sleep(20);
        }
        throw new IllegalStateException("mesh 总线连接未在 " + timeoutMs + "ms 内全部就绪");
    }

    /** fsync persistHook：marker 文件追加 + force(true)（近似生产落盘成本）。 */
    private Runnable fsyncHook(String nodeId) {
        Path file = fsyncDir.resolve("persist-" + nodeId + ".log");
        return () -> {
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(ByteBuffer.wrap(new byte[]{0x01}));
                ch.force(true);
            } catch (IOException e) {
                // 落盘失败仅记录（与 persistStateSafe 语义一致，不中断 Raft 流程）
                log.warn("fsync 失败 node={}", nodeId, e);
            }
        };
    }

    private static Path createFsyncDir() {
        Path dir = Path.of(System.getProperty("java.io.tmpdir"),
                "mesh-perf-fsync-" + ProcessHandle.current().pid());
        try {
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("创建 fsync 目录失败", e);
        }
    }

    // ==================== 查询 ====================

    public List<String> nodeIds() {
        return nodeCount == 1 ? List.of(A) : List.of(A, B, C);
    }

    public MeshNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public MemoryStore getStore(String nodeId) {
        return stores.get(nodeId);
    }

    public Map<String, MeshNode> getNodes() {
        return nodes;
    }

    /**
     * 轮询等待选出唯一 Leader（{@code waitForLeaderAmong(all)}）。
     *
     * @return 唯一 LEADER；超时返回 {@code null}
     */
    public MeshNode waitForLeader(long timeoutMs) throws InterruptedException {
        return waitForLeaderAmong(nodeIds(), timeoutMs);
    }

    /**
     * 在指定节点集合中等待选出唯一 Leader（故障恢复场景用：排除已死节点）。
     *
     * @param nodeIds   候选节点集合
     * @param timeoutMs 超时
     * @return 唯一 LEADER；超时返回 {@code null}
     */
    public MeshNode waitForLeaderAmong(Collection<String> nodeIds, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int leaderCount = 0;
            MeshNode leader = null;
            for (String id : nodeIds) {
                MeshNode n = nodes.get(id);
                if (n != null && n.isLeader()) {
                    leaderCount++;
                    leader = n;
                }
            }
            if (leaderCount == 1 && leader != null && leader.getRole() == MeshRole.LEADER) {
                return leader;
            }
            Thread.sleep(20);
        }
        return null;
    }

    // ==================== 内存路由总线（memory 模式） ====================

    /**
     * 内存路由：把 {@code send(target, frame)} 直接投递到目标节点的 {@link MeshNode#onMessage}。
     * <p>
     * 与集成测试同构——继承 {@link MeshBusClient} 复用类型（{@link MeshNode} 构造器要求），
     * 完全重写 {@link #send} 不建立任何 Netty 连接；帧仍经过 RPC 层编解码
     * （{@code onMessage} 内 {@code MeshRpcMessage.decode}），仅省去 TCP/Netty 传输。
     * </p>
     */
    private static final class RoutingBus extends MeshBusClient {
        private final String selfNodeId;
        private final Map<String, MeshNode> nodes;

        RoutingBus(String selfNodeId, Map<String, MeshNode> nodes) {
            super(selfNodeId, new MeshBusHandler());
            this.selfNodeId = selfNodeId;
            this.nodes = nodes;
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            MeshNode target = nodes.get(targetNodeId);
            if (target == null) {
                return;
            }
            target.onMessage(selfNodeId, frame);
        }
    }
}
