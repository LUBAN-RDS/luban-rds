package com.janeluo.luban.rds.mesh.integration;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-10（2026-09-11 mesh 审计 P1）3 节点行为测试：PUBLISH 作为 Raft 条目复制——
 *Leader propose 后，<b>每个节点</b>的 apply 侧都会收到同一 PUBLISH 条目并向本地投递。
 * <p>用内存投递记录器替代 server 侧 PubSubManager（核心断言：三节点 apply 均执行 PUBLISH，
 * 即订阅者无论连哪个节点都能收到消息）。单节点端到端由 server 模块 MeshPublishRaftTest 锁定。</p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class MeshPubSubReplicationIT {

    private static final String A = "nodeA";
    private static final String B = "nodeB";
    private static final String C = "nodeC";

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
            if (target != null) {
                target.onMessage(selfNodeId, frame);
            }
        }
    }

    /** 记录本节点 apply 的 PUBLISH 投递（channel, message）。 */
    private static final class RecordingApplier extends LogApplier {
        final List<String> deliveries = new CopyOnWriteArrayList<>();

        RecordingApplier(DefaultMemoryStore store) {
            super(new DefaultCommandHandler(), store);
            // P1-10：模拟 server 层 PubSubManager 投递回调
            setPublishHandler((channel, message) -> {
                deliveries.add(channel + "=" + message);
                return 1;
            });
        }
    }

    private static byte[] publishFrame(String channel, String message) {
        String f = "*3\r\n$7\r\nPUBLISH\r\n$" + channel.length() + "\r\n" + channel
                + "\r\n$" + message.length() + "\r\n" + message + "\r\n";
        return f.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void publishEntryReplicatesAndDeliversOnAllNodes() throws Exception {
        Map<String, MeshNode> nodes = new LinkedHashMap<>();
        Map<String, RoutingBus> buses = new LinkedHashMap<>();
        Map<String, RecordingApplier> appliers = new LinkedHashMap<>();

        for (String id : new String[]{A, B, C}) {
            MeshConfig config = MeshConfig.builder(id)
                    .addPeer(A, "127.0.0.1:11001")
                    .addPeer(B, "127.0.0.1:11002")
                    .addPeer(C, "127.0.0.1:11003")
                    .electionTimeout(100, 200)
                    .heartbeatIntervalMs(50)
                    .leaseDurationMs(400)
                    .totalNodes(3)
                    .build();
            MeshState state = new MeshState();
            DefaultMemoryStore store = new DefaultMemoryStore();
            RecordingApplier applier = new RecordingApplier(store);
            appliers.put(id, applier);
            RoutingBus bus = new RoutingBus(id, nodes);
            buses.put(id, bus);
            MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, store);
            nodes.put(id, node);
        }

        for (MeshNode node : nodes.values()) {
            node.start();
        }
        try {
            // 等选主
            MeshNode leader = null;
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                int leaderCount = 0;
                for (MeshNode n : nodes.values()) {
                    if (n.isLeader() && n.getRole() == MeshRole.LEADER) {
                        leaderCount++;
                        leader = n;
                    }
                }
                if (leaderCount == 1) {
                    break;
                }
                Thread.sleep(20);
            }
            assertNotNull(leader, "10s 内应选出唯一 Leader");

            // Leader propose PUBLISH（经 gate 等价路径——直接 propose 原始帧）
            leader.propose(publishFrame("news", "hello-3node"), 0, null).get(5, TimeUnit.SECONDS);

            // 等 follower 复制 apply
            Thread.sleep(500);

            // 三节点的 apply 侧都应执行了 PUBLISH 投递
            for (Map.Entry<String, RecordingApplier> e : appliers.entrySet()) {
                assertTrue(e.getValue().deliveries.contains("news=hello-3node"),
                        "节点 " + e.getKey() + " 的 apply 应投递 PUBLISH（复制生效），实际: "
                                + e.getValue().deliveries);
            }
        } finally {
            for (MeshNode node : nodes.values()) {
                try {
                    node.stop();
                } catch (Exception ignored) {
                }
            }
            for (RoutingBus bus : buses.values()) {
                try {
                    bus.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
