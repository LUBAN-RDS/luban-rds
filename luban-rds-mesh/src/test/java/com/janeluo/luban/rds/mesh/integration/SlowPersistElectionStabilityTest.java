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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事故回归集成测试（2026-08-06 生产事故）：
 * 慢落盘写高峰下 Leader 不被选举推翻。
 * <p>
 * 背景：生产上节点磁盘写高峰时，单次落盘可达数百毫秒。旧实现把 fsync 放在 raft 线程上同步执行
 * （propose 路径 {@code persistHook.run()} 直接阻塞 {@code raftExecutor}），写高峰 8 连写会连续停摆
 * 心跳 250ms × 8 ≈ 2s → Follower 选举超时（300-600ms）→ 反复 PreVote / 新选举 → 选举风暴，
 * 原 Leader 被推翻、在途写全部失败。修复（T2/T3）把 Leader 侧落盘移到独立 persistExecutor，
 * fsync 不再阻塞 raft 线程/心跳，Leader 在写高峰下应稳定保持。
 * </p>
 * <p>
 * 本测试与 {@link ThreeNodeIntegrationTest} 共用同一套建簇模式：{@link RoutingBus} 内存直接路由
 * 3 个真实 {@link MeshNode}（不经 Netty），ElectionTimer 驱动真实 PreVote 选举；仅有的差异是
 * 每个节点在 start 前注入慢盘 {@link #SLOW_PERSIST}（落盘 sleep 250ms）。
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SlowPersistElectionStabilityTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    /**
     * 慢盘模拟：每次落盘 sleep 250ms。
     * <p>写高峰时同步落盘会停摆心跳 250ms——旧代码会触发选举风暴。修复后落盘在独立
     * persistExecutor 线程执行，不影响 raft 线程心跳。</p>
     */
    private static final Runnable SLOW_PERSIST = () -> {
        try {
            Thread.sleep(250);
        } catch (InterruptedException e) {
            // 节点 stop 时 shutdownNow 会中断在途落盘；恢复中断标志后正常返回
            Thread.currentThread().interrupt();
        }
    };

    /**
     * 内存路由总线：与 {@link ThreeNodeIntegrationTest.RoutingBus} 完全一致——
     * 把 {@code send(target, frame)} 直接投递到目标节点的 {@link MeshNode#onMessage}。
     * 继承 {@link MeshBusClient} 复用类型（MeshNode 构造器要求），不建立任何 Netty 连接。
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

    /** 3 节点集群夹具：与 {@link ThreeNodeIntegrationTest.ThreeNodeCluster} 同构。 */
    private static final class ThreeNodeCluster {
        final Map<String, MeshNode> nodes = new LinkedHashMap<>();
        final Map<String, MemoryStore> stores = new LinkedHashMap<>();
        final Map<String, RoutingBus> buses = new LinkedHashMap<>();

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
            // 先停节点（ElectionTimer/心跳/executor），再关总线（释放 NioEventLoopGroup）
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

    /**
     * 集群配置：心跳 100ms、选举超时 300-600ms、lease 1200ms——与生产默认一致
     * （显式写出，风格同 ThreeNodeIntegrationTest；端口段独立 23000+ 避免与其他测试冲突）。
     */
    private MeshConfig threeNodeConfig(String selfId) {
        return MeshConfig.builder(selfId)
                .addPeer(A, "127.0.0.1:23001")
                .addPeer(B, "127.0.0.1:23002")
                .addPeer(C, "127.0.0.1:23003")
                .electionTimeout(300, 600)
                .heartbeatIntervalMs(100)
                .leaseDurationMs(1200)
                .totalNodes(3)
                .build();
    }

    /** 构造 SET 命令帧（RESP 字节）。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
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

    /**
     * 事故回归主线：3 节点 + 慢盘模拟（落盘 250ms）→ 选出 Leader → 连续 propose 8 个写 →
     * 写高峰 1.5s 后 Leader 仍保持（未被选举推翻）+ 8 个写全部完成。
     * <p>
     * 回归点：修复前 Leader 侧落盘同步阻塞 raft 线程，8 连写 ≈2s 心跳停摆 → Follower 选举超时
     * → 原 Leader 被推翻、在途写失败。修复后落盘异步化，写高峰下心跳照常，Leader 稳定。
     * </p>
     */
    @Test
    void slowPersist_writePeak_leaderNotOverthrown_allWritesComplete() throws Exception {
        ThreeNodeCluster cluster = new ThreeNodeCluster();
        try {
            // 1. 构造 3 节点（各自独立的 state/store/handler）
            cluster.addNode(A, threeNodeConfig(A), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(B, threeNodeConfig(B), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());
            cluster.addNode(C, threeNodeConfig(C), new MeshState(),
                    new DefaultMemoryStore(), new DefaultCommandHandler());

            // 2. 每个节点注入慢盘模拟：落盘 sleep 250ms（写高峰时若同步落盘会停摆心跳）
            for (MeshNode node : cluster.nodes.values()) {
                node.setPersistHook(SLOW_PERSIST);
            }

            // 3. 启动所有节点 → ElectionTimer 驱动真实 PreVote 选举（慢落盘下选举会稍慢）
            cluster.startAll();
            MeshNode leader = cluster.waitForLeader(5_000);
            assertNotNull(leader, "5s 内应选举出 Leader（慢落盘下选举稍慢，但不应超时）");
            String leaderId = nodeIdOf(cluster, leader);
            assertNotNull(leaderId, "应能解析 Leader 的 nodeId");

            // 4. 写高峰：向 Leader 连续 propose 8 个写（每个落盘 250ms，排队 ≈2s）
            List<CompletableFuture<byte[]>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(leader.propose(setFrame("k" + i, "v" + i), 0, null));
            }

            // 5. 写高峰期间等待 1.5s：旧代码下心跳已停摆 2s，Follower 会选举超时并推翻 Leader
            Thread.sleep(1_500);

            // 6. 断言：所有节点仍认原 Leader（选举风暴回归核心断言）
            for (Map.Entry<String, MeshNode> e : cluster.nodes.entrySet()) {
                assertEquals(leaderId, e.getValue().getLeaderId(),
                        "慢落盘写高峰下节点 " + e.getKey() + " 的 Leader 不应被改变（选举风暴回归）");
            }
            // 原 Leader 仍是 Leader（未被选举推翻）
            assertTrue(leader.isLeader(), "慢落盘写高峰下原 Leader 不应被选举推翻");

            // 7. 断言：8 个写全部完成（慢盘排队 ≈2s，future.get 超时放宽到 10s）
            byte[] ok = "+OK\r\n".getBytes(StandardCharsets.ISO_8859_1);
            for (int i = 0; i < futures.size(); i++) {
                byte[] resp = futures.get(i).get(10, TimeUnit.SECONDS);
                assertArrayEquals(ok, resp, "写 #" + i + " 应返回 +OK（慢落盘写高峰下不应失败）");
            }
        } finally {
            cluster.stopAll();
        }
    }
}
