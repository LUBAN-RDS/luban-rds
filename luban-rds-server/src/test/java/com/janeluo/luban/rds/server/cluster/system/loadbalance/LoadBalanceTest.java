package com.janeluo.luban.rds.server.cluster.system.loadbalance;

import com.janeluo.luban.rds.client.NettyRedisClient;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.server.cluster.testinfra.AbstractClusterSystemTest;
import com.janeluo.luban.rds.server.cluster.testinfra.TestCluster;
import com.janeluo.luban.rds.server.cluster.testinfra.TestNode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadBalanceTest extends AbstractClusterSystemTest {

    @Test
    void testSlotBasedRouting() {
        TestCluster cluster = harness.startClusterWithSlots(3, 7400);
        List<TestNode> nodes = new ArrayList<>(cluster.getNodes());

        // 选一个 key，找到它属于哪个节点
        String key = "testkey";
        int slot = SlotUtils.keyHashSlot(key);

        // 找到负责该槽位的节点
        TestNode owner = null;
        for (TestNode node : nodes) {
            if (node.getSlotManager().isSlotLocal(slot)) {
                owner = node;
                break;
            }
        }
        assertNotNull(owner, "没有节点负责槽位 " + slot);

        // 向非负责节点发送命令，应返回 MOVED 或 CLUSTERDOWN
        for (TestNode node : nodes) {
            if (node == owner) {
                continue;
            }
            NettyRedisClient client = cluster.getClient(node.getNodeId());
            try {
                Object result = client.executeCommand("SET", key, "value");
                assertNotNull(result, "非负责节点应返回响应，不应为 null");
                String resultStr = result.toString();
                assertTrue(resultStr.contains("MOVED") || resultStr.contains("CLUSTERDOWN"),
                        "非负责节点应返回 MOVED 或 CLUSTERDOWN: " + resultStr);
            } finally {
                client.disconnect();
            }
        }

        // 向负责节点发送命令，应正常执行
        NettyRedisClient ownerClient = cluster.getClient(owner.getNodeId());
        try {
            Object result = ownerClient.executeCommand("SET", key, "value");
            assertEquals("OK", result.toString());
        } finally {
            ownerClient.disconnect();
        }
    }

    @Test
    void testKeyDistribution() {
        harness.startClusterWithSlots(3, 7410);

        // 本地计算 1000 个随机 key 的槽位分布
        Random random = new Random(42);
        Map<Integer, Integer> slotCount = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String key = "key:" + i + ":" + random.nextInt();
            int slot = SlotUtils.keyHashSlot(key);
            slotCount.merge(slot, 1, Integer::sum);
        }

        // 验证 key 分布在多个槽位
        assertTrue(slotCount.size() > 100,
                "key 分布在 " + slotCount.size() + " 个槽位，应 > 100");
    }

    @Test
    void testHashTagRouting() {
        harness.startClusterWithSlots(3, 7420);
        String tag = "{user:1000}";
        int slot1 = SlotUtils.keyHashSlot(tag + "name");
        int slot2 = SlotUtils.keyHashSlot(tag + "email");
        int slot3 = SlotUtils.keyHashSlot(tag + "age");

        // 相同 hash tag 的 key 应映射到同一槽位
        assertEquals(slot1, slot2, "hash tag 槽位不一致");
        assertEquals(slot2, slot3, "hash tag 槽位不一致");
    }

    @Test
    void testMovedRedirectFlow() {
        TestCluster cluster = harness.startClusterWithSlots(3, 7430);
        List<TestNode> nodes = new ArrayList<>(cluster.getNodes());

        String key = "redirectkey";
        int slot = SlotUtils.keyHashSlot(key);

        // 找到负责该槽位的节点和一个非负责节点
        TestNode owner = null;
        TestNode nonOwner = null;
        for (TestNode node : nodes) {
            if (node.getSlotManager().isSlotLocal(slot)) {
                owner = node;
            } else if (nonOwner == null) {
                nonOwner = node;
            }
        }
        assertNotNull(owner, "没有节点负责槽位 " + slot);
        assertNotNull(nonOwner, "没有非负责节点");

        // GossipProtocol.sendMeet 为 stub，节点间不会传播槽位属主信息。
        // 手动在非负责节点上配置槽位属主和属主节点信息，以触发 MOVED 重定向。
        nonOwner.getSlotManager().setSlotOwner(slot, owner.getNodeId());
        ClusterNode ownerInfo = new ClusterNode(owner.getNodeId());
        ownerInfo.setIp("127.0.0.1");
        ownerInfo.setPort(owner.getPort());
        ownerInfo.addState(ClusterNodeState.MASTER);
        nonOwner.getClusterConfig().addNode(ownerInfo);

        // 向非负责节点发送 SET，应返回 MOVED
        NettyRedisClient client = cluster.getClient(nonOwner.getNodeId());
        try {
            Object result = client.executeCommand("SET", key, "value");
            assertNotNull(result, "应返回响应，不应为 null");
            String resultStr = result.toString();

            // 协议解析器会去掉 RESP 错误前缀 "-"，MOVED 响应为 "MOVED <slot> <ip>:<port>"
            String normalized = resultStr.startsWith("-") ? resultStr.substring(1) : resultStr;
            assertTrue(normalized.startsWith("MOVED"),
                    "非负责节点应返回 MOVED: " + resultStr);

            // 解析 MOVED 响应: MOVED <slot> <ip>:<port>
            String[] parts = normalized.replace("MOVED", "").trim().split("\\s+");
            String[] hostPort = parts[1].split(":");
            int targetPort = Integer.parseInt(hostPort[1]);
            assertEquals(owner.getPort(), targetPort, "MOVED 目标端口应为属主节点端口");

            // 连接到目标节点重试
            NettyRedisClient targetClient = new NettyRedisClient("127.0.0.1", targetPort);
            try {
                targetClient.connect();
                Object retryResult = targetClient.executeCommand("SET", key, "value");
                assertEquals("OK", retryResult.toString());
            } finally {
                targetClient.disconnect();
            }
        } finally {
            client.disconnect();
        }
    }
}
