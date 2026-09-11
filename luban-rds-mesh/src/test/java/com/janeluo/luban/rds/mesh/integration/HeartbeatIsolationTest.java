package com.janeluo.luban.rds.mesh.integration;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-6（2026-09-11 mesh 审计）：心跳发送独立于 apply 线程。
 * <p>
 * 此前心跳 fixedRate 与 apply/RPC 处理共用 raftExecutor——按生产实测 4-6ms/apply 的地板，
 * 队列前面积压 ~200ms 即可让最敏感 follower（选举超时 300-600ms）触发 PreVote 推翻健康
 * Leader（8/6 选举风暴同构根因）。修复后心跳 tick 在独立 mesh-timer 线程只做帧构建+发送，
 * raft 线程被慢 apply 占住时心跳仍按期间隔发出（不变量 I3）。
 * </p>
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HeartbeatIsolationTest {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    /** 记录 AE 帧发送时间戳的内存路由总线。 */
    private static final class RecordingBus extends MeshBusClient {
        private final String selfNodeId;
        private final Map<String, MeshNode> nodes;
        /** (fromNodeId → AE 帧发送时刻列表)；send 在发送方线程调用，时间戳即发送时刻。 */
        final Map<String, List<Long>> aeSendTimestamps = new ConcurrentHashMap<>();

        RecordingBus(String selfNodeId, Map<String, MeshNode> nodes) {
            super(selfNodeId, new MeshBusHandler());
            this.selfNodeId = selfNodeId;
            this.nodes = nodes;
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            if (frame.getType() == MessageType.APPEND_ENTRIES.getCode()) {
                aeSendTimestamps.computeIfAbsent(selfNodeId, k -> new ArrayList<>())
                        .add(System.nanoTime());
            }
            MeshNode target = nodes.get(targetNodeId);
            if (target != null) {
                target.onMessage(selfNodeId, frame);
            }
        }
    }

    private final Map<String, MeshNode> nodes = new java.util.LinkedHashMap<>();
    private final Map<String, RecordingBus> buses = new java.util.LinkedHashMap<>();

    @AfterEach
    void stopAll() {
        for (MeshNode n : nodes.values()) {
            n.stop();
        }
    }

    private MeshNode buildCluster() {
        String[] all = {A, B, C};
        int port = 11001;
        for (String id : all) {
            MeshConfig.Builder builder = MeshConfig.builder(id);
            for (String peer : all) {
                if (!peer.equals(id)) {
                    builder.addPeer(peer, "127.0.0.1:" + (port + peerIndex(peer)));
                }
            }
            MeshConfig config = builder
                    .electionTimeout(100, 200)
                    .heartbeatIntervalMs(50)
                    .build();
            MemoryStore store = new DefaultMemoryStore();
            LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
            RecordingBus bus = new RecordingBus(id, nodes);
            buses.put(id, bus);
            MeshNode node = new MeshNode(config, new MeshState(), bus, new RaftStateMachine(), applier, store);
            nodes.put(id, node);
        }
        for (MeshNode n : nodes.values()) {
            n.start();
        }
        return waitLeader();
    }

    private static int peerIndex(String id) {
        return id.equals(A) ? 1 : id.equals(B) ? 2 : 3;
    }

    private MeshNode waitLeader() {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (MeshNode n : nodes.values()) {
                if (n.getRole() == MeshRole.LEADER) {
                    return n;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 Leader 被中断");
            }
        }
        throw new AssertionError("10s 内未选出 Leader");
    }

    /** 反射取 raftExecutor 并异步提交任务（不加锁等待完成）。 */
    private void submitAsyncToRaft(MeshNode node, Runnable task) throws Exception {
        java.lang.reflect.Field f = MeshNode.class.getDeclaredField("raftExecutor");
        f.setAccessible(true);
        java.util.concurrent.ScheduledExecutorService executor =
                (java.util.concurrent.ScheduledExecutorService) f.get(node);
        executor.submit(task);
    }

    @Test
    void heartbeatSentWhileRaftThreadBlockedBySlowApply() throws Exception {
        MeshNode leader = buildCluster();
        String leaderId = leader.getNodeId();
        RecordingBus leaderBus = buses.get(leaderId);

        // 等心跳稳定一轮（清理历史帧，从干净基线观察）
        Thread.sleep(300);
        List<Long> before = leaderBus.aeSendTimestamps.get(leaderId);
        int baseline = before == null ? 0 : before.size();
        assertTrue(baseline >= 2, "阻塞前应有稳定心跳（50ms 间隔 × 300ms），实际 " + baseline);

        // raft 线程注入 800ms 慢任务（模拟慢 apply / 慢 Lua）
        final long blockStart = System.nanoTime();
        submitAsyncToRaft(leader, () -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        // 观察窗口 400ms（阻塞中段）：心跳仍应按期间隔发出
        Thread.sleep(400);
        long observeStart = blockStart + TimeUnit.MILLISECONDS.toNanos(200);
        long observeEnd = blockStart + TimeUnit.MILLISECONDS.toNanos(600);
        List<Long> all = leaderBus.aeSendTimestamps.get(leaderId);
        assertNotNull(all);
        int inWindow = 0;
        for (Long t : all) {
            if (t >= observeStart && t <= observeEnd) {
                inWindow++;
            }
        }
        // 修复前：心跳 tick 排在 raftExecutor 阻塞任务之后，窗口内 0 帧 → follower PreVote
        assertTrue(inWindow >= 3,
                "raft 线程被阻塞期间心跳仍应发出（50ms 间隔 × 400ms 窗口 ≥ 3 帧），实际 " + inWindow);

        // 阻塞结束后集群仍健康（Leader 未被推翻）
        Thread.sleep(300);
        assertTrue(leader.getRole() == MeshRole.LEADER, "慢任务不应推翻健康 Leader");
        assertNotNull(nodes.values().stream().filter(n -> n.getRole() == MeshRole.LEADER).findFirst().orElse(null),
                "集群应有 Leader");
    }
}
