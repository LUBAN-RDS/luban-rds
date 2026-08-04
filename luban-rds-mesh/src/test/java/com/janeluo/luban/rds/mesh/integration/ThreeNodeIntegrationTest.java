package com.janeluo.luban.rds.mesh.integration;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3 节点端到端集成测试（阶段 13）。
 * <p>
 * 这是 mesh 模块唯一一个真正的「多节点」集成测试——前序阶段（MeshNodeTest / MeshNodeProposeTest 等）
 * 都用单节点 + 捕获总线 + 反射模拟入站消息，绕过真实选举。本测试用 {@link RoutingBus} 把 3 个真实
 * {@link MeshNode} 通过内存直接路由互联（不经 Netty，避免端口/网络脆弱性），让 {@link MeshNode#start()}
 * 自动驱动的 {@code ElectionTimer} 完成真实选举：
 * <ol>
 *   <li>3 节点同时启动 → 选举超时随机化 → 恰好 1 个节点赢多数派 → 成为唯一 LEADER，其余 2 个 FOLLOWER；</li>
 *   <li>LEADER propose(SET foo bar) → 复制到多数派 → commit → apply；</li>
 *   <li>3 节点最终 GET foo 一致（Leader apply 立即可见，Follower 经 AppendEntries 复制后可见）。</li>
 * </ol>
 * </p>
 *
 * <h3>为什么用内存路由而非 Netty</h3>
 * <p>
 * 真实 3 进程 + Netty loopback 的集成测试需要随机端口、连接建立握手时序、重连退避，单测环境下既慢又脆弱
 * （CI 上偶发端口占用 / 时序抖动）。{@link RoutingBus} 直接把出站 {@link MeshFrame} 投递到目标节点的
 * {@link MeshNode#onMessage}，等价于「理想的可靠网络」，聚焦验证 Raft 协议正确性（选举、复制、一致性），
 * 而非 Netty 传输。真实网络传输已有 MeshBusCodecTest + MeshBusServer/Client 的单元覆盖。
 * </p>
 *
 * <h3>仅验证 Happy Path</h3>
 * <p>
 * 故障注入（kill leader、网络分区、时钟偏移）见 DESIGN §十「测试策略」的故障注入行——这类场景需要
 * 可控的「拔线 / 注入时钟」，内存路由可扩展，但属于一致性测试范畴（并发 SET、重启恢复），v1 留作手动
 * 验证或独立测试类。本测试只覆盖最关键的「1 Leader + 多数派写 + 3 节点一致」主线。
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class ThreeNodeIntegrationTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    /**
     * 内存路由总线：把 {@code send(target, frame)} 直接投递到目标节点的 {@link MeshNode#onMessage}。
     * <p>
     * 继承 {@link MeshBusClient} 以复用类型（{@link MeshNode} 构造器要求），但完全重写 {@link #send}，
     * 不建立任何 Netty 连接。父类构造的 {@code NioEventLoopGroup} 保持空闲（close 时统一释放）。
     * </p>
     * <p>
     * 线程模型：{@code send} 在源节点的 raftExecutor 线程上被调用，直接同步调用目标节点的
     * {@code onMessage}——后者会把任务提交到<b>目标节点自己的</b> raftExecutor 异步执行，故不会
     * 造成源线程阻塞，也不会重入源节点的状态。投递顺序：单线程 raftExecutor 内 send 是串行的，
     * 目标节点 onMessage 内部也是串行的，天然保序。
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
                // 未知目标（如自身过滤后的残留）——静默丢弃，等价于网络不可达
                return;
            }
            // frame.fromNodeId 是源节点；直接投递给目标节点的入站处理器
            target.onMessage(selfNodeId, frame);
        }
    }

    /** 3 节点集群夹具：持有 3 个 MeshNode + 各自的 rawStore + 共享的路由表。 */
    private static final class ThreeNodeCluster {
        final Map<String, MeshNode> nodes = new LinkedHashMap<>();
        final Map<String, MemoryStore> stores = new LinkedHashMap<>();
        final Map<String, RoutingBus> buses = new LinkedHashMap<>();

        ThreeNodeCluster() {
            // 第一阶段：先建节点占位（路由表需要引用所有节点），再回头注入 RoutingBus。
            // 但 MeshNode 构造器需要 busClient，故采用两步：
            //   1) 先建空的 nodes map（可变），每个 RoutingBus 持有这个 map 的引用；
            //   2) 依次构造 node → put 进 map → RoutingBus.send 即可路由到已注册节点。
            // 起始时 map 为空，但选举发生在所有节点 start 之后（start 由 buildAndStart 统一触发），
            // 故 send 时所有节点均已注册。
        }

        void addNode(String nodeId, MeshConfig config, MeshState state, MemoryStore store,
                     DefaultCommandHandler handler) {
            LogApplier applier = new LogApplier(handler, store);
            // RoutingBus 持有 nodes map 引用（此时可能尚未填满，但 start 前会填满）
            RoutingBus bus = new RoutingBus(nodeId, nodes);
            buses.put(nodeId, bus);
            MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, store);
            nodes.put(nodeId, node);
            stores.put(nodeId, store);
        }

        void startAll() {
            // 所有节点已注册到 nodes map，此时 start 才会让 ElectionTimer 驱动选举
            for (MeshNode node : nodes.values()) {
                node.start();
            }
        }

        void stopAll() {
            for (MeshNode node : nodes.values()) {
                try {
                    node.stop();
                } catch (Exception ignored) {
                    // 测试清理阶段忽略单个节点 stop 异常
                }
            }
            for (RoutingBus bus : buses.values()) {
                try {
                    bus.close();
                } catch (Exception ignored) {
                    // 释放 NioEventLoopGroup（虽未使用，构造时已创建）
                }
            }
        }

        /** 找到当前 LEADER（轮询直到选举完成或超时）。 */
        MeshNode waitForLeader(long timeoutMs) throws InterruptedException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int leaderCount = 0;
                MeshNode leader = null;
                for (Map.Entry<String, MeshNode> e : nodes.entrySet()) {
                    if (e.getValue().isLeader()) {
                        leaderCount++;
                        leader = e.getValue();
                    }
                }
                if (leaderCount == 1 && leader != null
                        && leader.getRole() == MeshRole.LEADER) {
                    return leader;
                }
                Thread.sleep(20);
            }
            return null;
        }
    }

    private MeshConfig threeNodeConfig(String selfId) {
        // electionTimeout 100-200ms：足够快（测试不拖沓），足够慢（避免过度抢占）。
        // 三节点用相同种子区间，随机化保证不会同时超时（split vote 风险低）。
        return MeshConfig.builder(selfId)
                .addPeer(A, "127.0.0.1:11001")
                .addPeer(B, "127.0.0.1:11002")
                .addPeer(C, "127.0.0.1:11003")
                .electionTimeout(100, 200)
                .heartbeatIntervalMs(50)
                .leaseDurationMs(400)
                .totalNodes(3)
                .build();
    }

    /** 构造 SET 命令帧（RESP 字节）。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 构造 GET 命令帧（RESP 字节）。 */
    private static byte[] getFrame(String key) {
        String f = "*2\r\n$3\r\nGET\r\n$" + key.length() + "\r\n" + key + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 等待 store 中 key 出现期望值（Follower 经 AppendEntries 复制有微小延迟）。 */
    private static void waitForValue(MemoryStore store, String key, String expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Object v = store.get(0, key);
            if (expected.equals(v)) {
                return;
            }
            Thread.sleep(10);
        }
        // 最后一次断言（会抛出有意义的失败信息）
        assertEquals(expected, store.get(0, key),
                "key=" + key + " 未在 " + timeoutMs + "ms 内出现期望值 " + expected);
    }

    // ==================== 测试用例 ====================

    /**
     * 主线场景：3 节点启动 → 选举出唯一 Leader → Leader 写 SET → 多数派确认 → 3 节点 GET 一致。
     * <p>
     * 这是 mesh 强一致卖点的端到端验证：写入必须经多数派（2/3）确认才返回 OK，且最终所有节点
     * （含未参与多数派确认的第 3 个 Follower）状态一致。
     * </p>
     */
    @Test
    void threeNodes_electLeader_writeThroughMajority_allNodesConsistent() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            // 1. 构造 3 节点（各自独立的 state/store/handler）
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());

            // 2. 启动所有节点 → ElectionTimer 自动驱动选举
            cluster.startAll();

            // 3. 等待选出唯一 Leader（3 节点集群，多数派 = 2）
            MeshNode leader = cluster.waitForLeader(5_000);
            assertNotNull(leader, "5s 内应选举出 Leader");
            assertTrue(leader.isLeader(), "选出者应为 LEADER");
            assertEquals(MeshRole.LEADER, leader.getRole());

            // 校验：恰有 1 个 Leader，其余 2 个 FOLLOWER
            int leaderCount = 0;
            int followerCount = 0;
            for (MeshNode n : cluster.nodes.values()) {
                if (n.isLeader()) {
                    leaderCount++;
                } else if (n.getRole() == MeshRole.FOLLOWER) {
                    followerCount++;
                }
            }
            assertEquals(1, leaderCount, "整个集群恰有 1 个 Leader");
            assertEquals(2, followerCount, "其余 2 个为 FOLLOWER");

            // 4. Leader propose(SET foo bar) → 多数派复制 → commit → apply
            CompletableFuture<byte[]> f = leader.propose(setFrame("foo", "bar"), 0, null);
            byte[] resp = f.get(5, TimeUnit.SECONDS);
            assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), resp,
                    "propose 应返回 apply 的 +OK");

            // Leader 立即可见（apply 同步完成）
            assertEquals("bar", cluster.stores.get(nodeIdOf(cluster, leader)).get(0, "foo"),
                    "Leader apply 后立即可见");

            // 5. 3 节点最终一致（Follower 经 AppendEntries 复制，有微小延迟）
            for (Map.Entry<String, MemoryStore> e : cluster.stores.entrySet()) {
                waitForValue(e.getValue(), "foo", "bar", 3_000);
            }

            // 6. commitIndex / lastApplied 在 Leader 上推进
            MeshState leaderState = leader.getState();
            assertTrue(leaderState.commitIndex >= 1, "Leader commitIndex 应 >= 1");
            assertTrue(leaderState.lastApplied >= 1, "Leader lastApplied 应 >= 1");
        } finally {
            cluster.stopAll();
        }
    }

    /**
     * 连续多次写入：验证日志按顺序复制 + apply，所有节点最终一致。
     * <p>覆盖 LogReplicator 在多次 propose 下的 nextIndex/matchIndex 推进正确性。</p>
     */
    @Test
    void threeNodes_multipleWrites_allReplicatedAndConsistent() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.startAll();

            MeshNode leader = cluster.waitForLeader(5_000);
            assertNotNull(leader, "应选举出 Leader");

            // 连续 5 次 SET
            String[] keys = {"k1", "k2", "k3", "k4", "k5"};
            String[] vals = {"v1", "v2", "v3", "v4", "v5"};
            for (int i = 0; i < keys.length; i++) {
                CompletableFuture<byte[]> f = leader.propose(setFrame(keys[i], vals[i]), 0, null);
                byte[] resp = f.get(5, TimeUnit.SECONDS);
                assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), resp);
            }

            // 3 节点全部一致
            for (Map.Entry<String, MemoryStore> e : cluster.stores.entrySet()) {
                for (int i = 0; i < keys.length; i++) {
                    waitForValue(e.getValue(), keys[i], vals[i], 3_000);
                }
            }

            // Leader commitIndex 应推进到 5
            assertTrue(leader.getState().commitIndex >= 5,
                    "5 次写入后 Leader commitIndex 应 >= 5，实际=" + leader.getState().commitIndex);
        } finally {
            cluster.stopAll();
        }
    }

    /**
     * 读路径：Leader 上 GET 命令经 propose 链路返回正确值（验证 apply 返回值即客户端响应字节）。
     * <p>GET 在 mesh 模式下也走 Raft propose（线性一致读的简化实现，见 DESIGN §5.7 / 阶段 7）。</p>
     */
    @Test
    void leader_getCommand_returnsConsistentValue() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.startAll();

            MeshNode leader = cluster.waitForLeader(5_000);
            assertNotNull(leader);

            // 先 SET
            leader.propose(setFrame("counter", "42"), 0, null).get(5, TimeUnit.SECONDS);

            // GET 经 propose 链路返回 bulk string
            CompletableFuture<byte[]> getF = leader.propose(getFrame("counter"), 0, null);
            byte[] resp = getF.get(5, TimeUnit.SECONDS);
            // GET 返回 $2\r\n42\r\n
            assertArrayEquals("$2\r\n42\r\n".getBytes(StandardCharsets.ISO_8859_1), resp,
                    "GET 经 propose 应返回 bulk string 响应");
        } finally {
            cluster.stopAll();
        }
    }

    /**
     * Leader 唯一性：任意时刻最多 1 个 Leader（Raft 安全性）。
     * <p>等价于「split vote 不会产生 2 个 Leader」——本测试通过观察稳定状态下的 leaderCount 验证。
     * 真正的 split vote 场景（偶发）由选举算法的任期裁决保证，单元测试已覆盖（RaftStateMachineTest）。</p>
     */
    @Test
    void atMostOneLeader_afterStableElection() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.startAll();

            assertNotNull(cluster.waitForLeader(5_000), "应选出 Leader");

            // 稳定后多次采样，leaderCount 恒为 1
            for (int i = 0; i < 5; i++) {
                int cnt = 0;
                for (MeshNode n : cluster.nodes.values()) {
                    if (n.isLeader()) {
                        cnt++;
                    }
                }
                assertEquals(1, cnt, "采样 #" + i + " 时 leaderCount 应恒为 1");
                Thread.sleep(50);
            }
        } finally {
            cluster.stopAll();
        }
    }

    /**
     * 新选举：记录原 Leader，验证选举收敛后 Leader 的 nodeId 与 Follower 集合互斥（非空且不同）。
     * <p>这是「至少有一个 Leader 且其余不是 Leader」的最小验证，避免和上面的用例完全重复。</p>
     */
    @Test
    void electedLeader_hasDistinctNodeId_fromFollowers() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.startAll();

            MeshNode leader = cluster.waitForLeader(5_000);
            assertNotNull(leader);
            String leaderId = nodeIdOf(cluster, leader);
            assertNotNull(leaderId);
            assertNotEquals("", leaderId);

            // 其余 2 个节点的 nodeId 与 Leader 不同
            for (String nid : cluster.nodes.keySet()) {
                if (nid.equals(leaderId)) {
                    continue;
                }
                assertNotEquals(leaderId, nid, "Follower nodeId 不应等于 Leader nodeId");
                MeshNode follower = cluster.nodes.get(nid);
                assertEquals(MeshRole.FOLLOWER, follower.getRole(),
                        "非 Leader 节点应为 FOLLOWER");
            }
        } finally {
            cluster.stopAll();
        }
    }

    /** 从 cluster 中找出 leader 对应的 nodeId。 */
    private static String nodeIdOf(ThreeNodeCluster cluster, MeshNode leader) {
        for (Map.Entry<String, MeshNode> e : cluster.nodes.entrySet()) {
            if (e.getValue() == leader) {
                return e.getKey();
            }
        }
        return null;
    }
}
