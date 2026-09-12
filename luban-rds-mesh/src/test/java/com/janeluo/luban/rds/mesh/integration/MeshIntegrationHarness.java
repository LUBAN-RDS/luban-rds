package com.janeluo.luban.rds.mesh.integration;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import com.janeluo.luban.rds.mesh.replication.LogApplier;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * follower 读集成测试夹具（fix-mesh-follower-read Task 15-17）。
 *
 * <h3>与 {@code ThreeNodeIntegrationTest} 的关系</h3>
 * <p>
 * 本类从 {@code ThreeNodeIntegrationTest} 的 {@code RoutingBus} + 3 节点夹具提炼而来，额外做了三件该测试
 * 没有的事，缺一不可：
 * <ol>
 *   <li><b>装配真实 {@link MeshWriteGate}</b>：只有经 gate 的 {@code read} 才会走
 *       「非 Leader → readIndex RPC → apply 屏障 → 本地执行 → 失败回落 MOVED」这条被测路径；</li>
 *   <li><b>对全部节点 {@code markReady()}</b>：读入口有就绪门（Task 4），未就绪节点一律 -TRYAGAIN，
 *       测试会以「读全失败」的假象误导排查；</li>
 *   <li><b>故障注入能力</b>：{@link #partitionNode} / {@link #killNode} 在内存总线上模拟断网/进程消失，
 *       不触碰生产代码。</li>
 * </ol>
 * </p>
 *
 * <h3>为什么用内存路由</h3>
 * <p>
 * 与 ThreeNodeIntegrationTest 同口径：{@link RoutingBus#send} 把出站帧直接投递到目标节点
 * {@link MeshNode#onMessage}，等价「理想可靠网络」，聚焦 Raft 协议与读路径正确性，避开 Netty 端口/时序抖动。
 * </p>
 */
final class MeshIntegrationHarness implements AutoCloseable {

    static final String A = "nodeA";
    static final String B = "nodeB";
    static final String C = "nodeC";

    /** 固定 nodeId → bus 地址映射（内存路由不真正建连，仅占位满足配置完整性）。 */
    private static final Map<String, String> BUS_ADDRS = Map.of(
            A, "127.0.0.1:11001",
            B, "127.0.0.1:11002",
            C, "127.0.0.1:11003");
    private static final List<String> ALL_NODES = List.of(A, B, C);

    private static final String SET_OK = "+OK\r\n";
    private static final long POLL_INTERVAL_MS = 20L;

    private final Map<String, MeshNode> nodes = new LinkedHashMap<>();
    private final Map<String, MemoryStore> stores = new LinkedHashMap<>();
    private final Map<String, MeshWriteGate> gates = new LinkedHashMap<>();
    private final Map<String, RoutingBus> buses = new LinkedHashMap<>();
    /** 被分区（双向丢帧）的节点集合：模拟网络隔离。 */
    private final Set<String> partitioned = ConcurrentHashMap.newKeySet();
    /** 已被 kill 的节点集合：等效进程消失，选举/读点判定都要跳过。 */
    private final Set<String> killed = ConcurrentHashMap.newKeySet();
    /** 配置模板：真实 selfNodeId 由 {@link #configFor} 逐节点重建（MeshConfig 不可变）。 */
    private MeshConfig template;

    private MeshIntegrationHarness() {
        // 仅经 start(...) 构造，保证「建节点 → start → 等 Leader → markReady」顺序不被绕过
    }

    /**
     * 启动 3 节点（nodeA/nodeB/nodeC）集群并等到 Leader 选出。
     * <p>返回前对三个节点都 {@link MeshNode#markReady()}，否则读入口的就绪门会持续拒绝。</p>
     *
     * @param templateConfig 配置模板（selfNodeId 占位，peer set/读一致性策略由此复制）
     */
    static MeshIntegrationHarness start(MeshConfig templateConfig) {
        MeshIntegrationHarness h = new MeshIntegrationHarness();
        h.template = templateConfig;
        // 阶段一：先构造全部节点并注册到 nodes map。RoutingBus 持有该 map 引用，
        // 由于 start 在所有节点注册之后才触发，send 时目标必已存在。
        for (String nodeId : ALL_NODES) {
            MemoryStore store = new DefaultMemoryStore();
            DefaultCommandHandler handler = new DefaultCommandHandler();
            MeshConfig config = h.configFor(nodeId);
            RoutingBus bus = new RoutingBus(nodeId, h.nodes, h.partitioned);
            LogApplier applier = new LogApplier(handler, store);
            MeshNode node = new MeshNode(config, new MeshState(), bus,
                    new RaftStateMachine(), applier, store);
            h.buses.put(nodeId, bus);
            h.nodes.put(nodeId, node);
            h.stores.put(nodeId, store);
            h.gates.put(nodeId, new MeshWriteGate(node, store, handler, config));
        }
        // 阶段二：统一 start，让 ElectionTimer 驱动真实选举
        for (MeshNode node : h.nodes.values()) {
            node.start();
        }
        String leader = h.awaitLeader();
        if (leader == null) {
            h.close();
            throw new IllegalStateException("集群未在期限内选出 Leader");
        }
        // 就绪门：所有节点标记本地状态可信，否则 gate.read 一律 -TRYAGAIN
        for (MeshNode node : h.nodes.values()) {
            node.markReady();
        }
        return h;
    }

    /**
     * follower 读配置模板：{@code readFromFollower=READ_INDEX} + 指定缓存窗口/预算 + 完整 3 peer 集。
     *
     * <p>selfNodeId 用占位串 {@code "template"}（不在真实 peer 集内），这样
     * {@link MeshConfig#getPeerBusAddrs()} 不会被「过滤自身」掉任何一个真实节点，
     * 夹具才能为每个节点重建出完整 3 peer 配置。</p>
     *
     * @param cacheMs   readIndex 短窗口缓存有效期（0 = 每次取新读点）
     * @param maxWaitMs follower 读总预算（取读点 + apply 屏障）
     */
    static MeshConfig followerReadConfig(long cacheMs, long maxWaitMs) {
        return MeshConfig.builder("template")
                .addPeer(A, BUS_ADDRS.get(A))
                .addPeer(B, BUS_ADDRS.get(B))
                .addPeer(C, BUS_ADDRS.get(C))
                .totalNodes(3)
                .readFromFollower(MeshConfig.ReadFromFollower.READ_INDEX)
                .followerReadCacheMs(cacheMs)
                .followerReadMaxWaitMs(maxWaitMs)
                .build();
    }

    /** 逐节点重建配置：selfNodeId 换成真实节点，peer set 固定 3 成员，策略字段复制模板。 */
    private MeshConfig configFor(String selfId) {
        MeshConfig.Builder b = MeshConfig.builder(selfId);
        for (String peer : ALL_NODES) {
            b.addPeer(peer, BUS_ADDRS.get(peer));
        }
        return b.totalNodes(3)
                .electionTimeout(template.getElectionTimeoutMinMs(), template.getElectionTimeoutMaxMs())
                .heartbeatIntervalMs(template.getHeartbeatIntervalMs())
                .leaseDurationMs(template.getLeaseDurationMs())
                .readConsistency(template.getReadConsistency())
                .readLeaseWaitMs(template.getReadLeaseWaitMs())
                .readFromFollower(template.getReadFromFollower())
                .followerReadMaxWaitMs(template.getFollowerReadMaxWaitMs())
                .followerReadCacheMs(template.getFollowerReadCacheMs())
                .build();
    }

    // ==================== 角色等待 ====================

    /** 轮询直到集群恰有 1 个存活 Leader，返回其 nodeId；超时返回 null。 */
    String awaitLeader() {
        return awaitLeaderExcluding(null);
    }

    /** 轮询直到除 {@code excludeId} 外恰有 1 个存活 Leader（kill 场景跳过已死节点）。 */
    String awaitLeaderExcluding(String excludeId) {
        long deadline = System.nanoTime() + 20_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            String leader = null;
            int count = 0;
            for (String id : ALL_NODES) {
                if (id.equals(excludeId) || killed.contains(id)) {
                    continue;
                }
                MeshNode n = nodes.get(id);
                if (n.isLeader() && n.getRole() == MeshRole.LEADER) {
                    count++;
                    leader = id;
                }
            }
            if (count == 1) {
                return leader;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * 轮询直到出现一个「非 Leader、已认可 Leader、且已就绪」的 Follower（排除 {@code excludeId}）。
     * <p>要求 leaderId 非空是必要的：follower 读点 RPC 依赖 {@code state.leaderId}，
     * leaderId 未知时 fetchReadIndex 会立即失败回落，读断言会假失败。</p>
     */
    String awaitFollowerExcluding(String excludeId) {
        long deadline = System.nanoTime() + 20_000L * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (String id : ALL_NODES) {
                if (id.equals(excludeId) || killed.contains(id)) {
                    continue;
                }
                MeshNode n = nodes.get(id);
                if (n.getRole() == MeshRole.FOLLOWER && n.getLeaderId() != null
                        && !n.getLeaderId().isEmpty() && n.isReady()) {
                    return id;
                }
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        throw new IllegalStateException("未等到可用 Follower（排除 " + excludeId + "）");
    }

    // ==================== 读写（经真实 MeshWriteGate） ====================

    /** 在指定节点执行写（该节点必须是 Leader），并校验 apply 响应为 +OK。 */
    void write(String nodeId, String key, String value) {
        byte[] resp = gates.get(nodeId).write(setFrame(key, value), 0, null);
        if (!Arrays.equals(SET_OK.getBytes(StandardCharsets.ISO_8859_1), resp)) {
            throw new IllegalStateException("写未返回 +OK: node=" + nodeId
                    + ", resp=" + new String(resp, StandardCharsets.ISO_8859_1));
        }
    }

    /** 经目标节点 gate.read 读；读路径失败（MOVED/租约/未就绪）直接抛。 */
    byte[] get(String nodeId, String key) {
        return gates.get(nodeId).read(0, new String[]{"GET", key});
    }

    /** 同 {@link #get}，但把「回落类异常」统一折叠为 null（等价客户端收到 MOVED/错误）。 */
    byte[] getOrNull(String nodeId, String key) {
        try {
            return get(nodeId, key);
        } catch (MovedToLeaderException | LeaseInvalidException | RetryableMeshException e) {
            return null;
        }
    }

    /** 读并把 bulk string 值解析为 Long；MOVED/错误/键不存在返回 null。 */
    Long getAsLong(String nodeId, String key) {
        byte[] resp = getOrNull(nodeId, key);
        if (resp == null) {
            return null;
        }
        String s = new String(resp, StandardCharsets.ISO_8859_1);
        if (s.startsWith("$-1")) {
            return null;
        }
        int head = s.indexOf("\r\n");
        int tail = s.indexOf("\r\n", head + 2);
        if (head < 0 || tail < 0) {
            return null;
        }
        try {
            return Long.parseLong(s.substring(head + 2, tail));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 轮询读直到响应字节与 {@code expected} 逐字节相等；期限内未命中返回 null。
     * <p>刻意不返回「最后一次的其它响应」：调用方用 {@code != null} 表示「确实读到了期望值」，
     * 避免 MOVED 短暂恢复时把旧值误判为成功。</p>
     */
    byte[] getEventually(String nodeId, String key, byte[] expected, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            byte[] resp = getOrNull(nodeId, key);
            if (resp != null && Arrays.equals(resp, expected)) {
                return resp;
            }
            sleepQuietly(POLL_INTERVAL_MS);
        }
        return null;
    }

    /** 指定节点累计取读点次数（INFO 口径）。 */
    long fetchCount(String nodeId) {
        return nodes.get(nodeId).readIndexFetchCount();
    }

    // ==================== 故障注入 ====================

    /**
     * 模拟节点网络隔离：该节点收发方向的帧一律丢弃（入站心跳/日志到不了，出站 readIndex 请求也出不去）。
     * <p>双向丢弃比单向更接近真实分区，也避免被隔离节点持续发选举帧扰动剩余多数派。</p>
     */
    void partitionNode(String nodeId) {
        partitioned.add(nodeId);
    }

    /** 模拟进程消失：stop 该节点并纳入双向丢帧集合（stop 后其状态不再推进）。 */
    void killNode(String nodeId) {
        partitioned.add(nodeId);
        killed.add(nodeId);
        MeshNode node = nodes.get(nodeId);
        if (node != null) {
            node.stop();
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, MeshNode> e : nodes.entrySet()) {
            if (killed.contains(e.getKey())) {
                continue;   // 已 stop，重复 stop 幂等但没必要
            }
            try {
                e.getValue().stop();
            } catch (Exception ignored) {
                // 清理阶段忽略单节点 stop 异常
            }
        }
        for (RoutingBus bus : buses.values()) {
            try {
                bus.close();
            } catch (Exception ignored) {
                // 释放 NioEventLoopGroup（内存路由未使用，构造时已创建）
            }
        }
    }

    // ==================== 内部：RESP 帧构造 ====================

    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待被中断", e);
        }
    }

    /**
     * 内存路由总线：{@code send(target, frame)} 直投目标节点 {@link MeshNode#onMessage}。
     * <p>{@code send} 在源节点 raftExecutor 线程上同步调用，目标 onMessage 内部再异步提交到
     * 自己的 raftExecutor，故不阻塞源线程、不重入源状态；单线程串行天然保序。</p>
     */
    private static final class RoutingBus extends MeshBusClient {
        private final String selfNodeId;
        private final Map<String, MeshNode> nodes;
        /** 共享的分区集合；引用同一实例，任一 bus 都据此判定收发是否被隔离。 */
        private final Set<String> partitioned;

        RoutingBus(String selfNodeId, Map<String, MeshNode> nodes, Set<String> partitioned) {
            super(selfNodeId, new MeshBusHandler());
            this.selfNodeId = selfNodeId;
            this.nodes = nodes;
            this.partitioned = partitioned;
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            // 双向隔离：本节点被分区（出不去）或目标被分区（进不来）都丢弃
            if (partitioned.contains(selfNodeId)
                    || (targetNodeId != null && partitioned.contains(targetNodeId))) {
                return;
            }
            MeshNode target = nodes.get(targetNodeId);
            if (target == null) {
                // 未知目标：静默丢弃，等价网络不可达
                return;
            }
            target.onMessage(selfNodeId, frame);
        }
    }
}
