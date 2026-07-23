package com.janeluo.luban.rds.cluster.integration;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.cluster.testinfra.EmbeddedCluster;
import com.janeluo.luban.rds.cluster.testinfra.EmbeddedNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 {@code CLUSTER REPLICATE} 配置的从节点角色能经 Gossip 传播给其它节点。
 * <p>
 * 这是修复"主节点宕机后故障转移永不触发"的回归测试：修复前，{@code selectGossipNodes}
 * 排除本节点且 PING/PONG 消息头不携带发送方角色，导致从节点角色无法传播，其它节点视图中
 * 所有节点均为 master，{@code FailoverManager.tryStartElection} 的 {@code me.isSlave()}
 * 前置条件永不满足。
 * </p>
 */
class ClusterReplicateGossipTest {

    private EmbeddedCluster cluster;
    private List<EmbeddedNode> masters;
    private List<EmbeddedNode> slaves;

    private static final int BASE_PORT = 9800;

    @BeforeEach
    void setUp() {
        // 2 主 + 2 从：master0=BASE_PORT, master1=BASE_PORT+1, slave0=BASE_PORT+2, slave1=BASE_PORT+3
        cluster = EmbeddedCluster.builder()
                .nodes(4)
                .basePort(BASE_PORT)
                .build();
        cluster.start();
        List<EmbeddedNode> all = new ArrayList<>(cluster.getNodes());
        // 按端口排序，保证稳定
        all.sort((a, b) -> Integer.compare(a.getPort(), b.getPort()));
        masters = new ArrayList<>();
        slaves = new ArrayList<>();
        masters.add(all.get(0));
        masters.add(all.get(1));
        slaves.add(all.get(2));
        slaves.add(all.get(3));

        // 仅给 master 分配槽位（slave 必须无槽位才能 REPLICATE）
        assignSlotsToMasters();

        // 等待 Gossip 拓扑收敛：所有节点视图中其它节点均已握手完成
        waitForTopologyConverged(10000);
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop();
        }
    }

    /**
     * 将 16384 槽位均分给两个 master
     */
    private void assignSlotsToMasters() {
        int total = SlotUtils.CLUSTER_SLOTS;
        int per = total / masters.size();
        for (int i = 0; i < masters.size(); i++) {
            int start = i * per;
            int end = (i == masters.size() - 1) ? total - 1 : (start + per - 1);
            EmbeddedNode node = masters.get(i);
            for (int slot = start; slot <= end; slot++) {
                node.getClusterConfig().setSlotOwner(slot, node.getNodeId());
                node.getSlotManager().addSlots(slot);
            }
        }
    }

    @Test
    @DisplayName("CLUSTER REPLICATE 后从节点角色经 Gossip 传播到主节点视图")
    void testReplicateRolePropagatesViaGossip() throws Exception {
        // 前置：slave0 REPLICATE master0，slave1 REPLICATE master1
        sendClusterReplicate(slaves.get(0), masters.get(0).getNodeId());
        sendClusterReplicate(slaves.get(1), masters.get(1).getNodeId());

        // 等待 Gossip 传播发送方角色
        awaitSlaveRoleKnown(masters.get(0), slaves.get(0).getNodeId(), 10000);
        awaitSlaveRoleKnown(masters.get(1), slaves.get(1).getNodeId(), 10000);

        // 断言 master0 视图中 slave0 为 SLAVE 且 masterNodeId 指向 master0
        ClusterConfig master0Config = masters.get(0).getClusterConfig();
        ClusterNode slave0View = master0Config.getNode(slaves.get(0).getNodeId());
        assertNotNull(slave0View, "master0 应能看到 slave0");
        assertTrue(slave0View.isSlave(), "master0 视图中 slave0 应为 SLAVE");
        assertFalse(slave0View.isMaster(), "master0 视图中 slave0 不应为 MASTER");
        assertEquals(masters.get(0).getNodeId(), slave0View.getMasterNodeId(),
                "slave0 的 masterNodeId 应指向 master0");

        // 断言 master1 视图中 slave1 为 SLAVE 且 masterNodeId 指向 master1
        ClusterConfig master1Config = masters.get(1).getClusterConfig();
        ClusterNode slave1View = master1Config.getNode(slaves.get(1).getNodeId());
        assertNotNull(slave1View, "master1 应能看到 slave1");
        assertTrue(slave1View.isSlave(), "master1 视图中 slave1 应为 SLAVE");
        assertEquals(masters.get(1).getNodeId(), slave1View.getMasterNodeId(),
                "slave1 的 masterNodeId 应指向 master1");

        // 交叉验证：master1 也应通过 Gossip 知道 slave0 是 master0 的从节点
        awaitSlaveRoleKnown(masters.get(1), slaves.get(0).getNodeId(), 10000);
        ClusterNode slave0ViewFromMaster1 = master1Config.getNode(slaves.get(0).getNodeId());
        assertNotNull(slave0ViewFromMaster1, "master1 应能通过 Gossip 看到 slave0");
        assertTrue(slave0ViewFromMaster1.isSlave(), "master1 视图中 slave0 应为 SLAVE");
        assertEquals(masters.get(0).getNodeId(), slave0ViewFromMaster1.getMasterNodeId(),
                "master1 视图中 slave0 的 masterNodeId 应指向 master0");
    }

    @Test
    @DisplayName("CLUSTER NODES 输出中从节点行显示 slave 标志与主节点ID")
    void testClusterNodesShowsSlaveFlag() throws Exception {
        sendClusterReplicate(slaves.get(0), masters.get(0).getNodeId());
        awaitSlaveRoleKnown(masters.get(0), slaves.get(0).getNodeId(), 10000);

        // 从 master0 获取 CLUSTER NODES 文本
        String nodesText = sendClusterNodes(masters.get(0));
        String slave0Line = findNodeLine(nodesText, slaves.get(0).getNodeId());
        assertNotNull(slave0Line, "CLUSTER NODES 应包含 slave0 行");
        assertTrue(slave0Line.contains("slave"), "slave0 行应包含 slave 标志");
        assertTrue(slave0Line.contains(masters.get(0).getNodeId()),
                "slave0 行的 master 字段应为 master0 的 nodeId");
    }

    // ==================== 测试辅助方法 ====================

    /**
     * 等待所有节点视图中其它节点均已完成握手（无 HANDSHAKE 标志）
     */
    private void waitForTopologyConverged(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        List<EmbeddedNode> all = new ArrayList<>();
        all.addAll(masters);
        all.addAll(slaves);
        while (System.currentTimeMillis() < deadline) {
            boolean converged = true;
            for (EmbeddedNode observer : all) {
                for (EmbeddedNode target : all) {
                    if (target == observer) {
                        continue;
                    }
                    ClusterNode view = observer.getClusterConfig().getNode(target.getNodeId());
                    if (view == null || view.hasState(ClusterNodeState.HANDSHAKE)) {
                        converged = false;
                        break;
                    }
                }
                if (!converged) {
                    break;
                }
            }
            if (converged) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // 不直接 fail，交由后续断言给出更精确信息
    }

    /**
     * 通过 RESP 向节点发送 CLUSTER REPLICATE masterId
     */
    private void sendClusterReplicate(EmbeddedNode node, String masterId) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", node.getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String mid = masterId;
            String cmd = "*3\r\n$7\r\nCLUSTER\r\n$9\r\nREPLICATE\r\n$"
                    + mid.length() + "\r\n" + mid + "\r\n";
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] resp = new byte[1024];
            int n = socket.getInputStream().read(resp);
            String reply = new String(resp, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            assertTrue(reply.startsWith("+OK"), "CLUSTER REPLICATE 应返回 +OK，实际: " + reply);
        }
    }

    /**
     * 通过 RESP 获取 CLUSTER NODES 文本
     */
    private String sendClusterNodes(EmbeddedNode node) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", node.getPort())) {
            socket.setSoTimeout(5000);
            OutputStream out = socket.getOutputStream();
            String cmd = "*2\r\n$7\r\nCLUSTER\r\n$5\r\nNODES\r\n";
            out.write(cmd.getBytes(StandardCharsets.UTF_8));
            out.flush();
            byte[] resp = new byte[8192];
            int n = socket.getInputStream().read(resp);
            String reply = new String(resp, 0, Math.max(n, 0), StandardCharsets.UTF_8);
            // RESP bulk string: $<len>\r\n<text>\r\n
            int crlf = reply.indexOf("\r\n");
            if (crlf > 0 && reply.startsWith("$")) {
                return reply.substring(crlf + 2);
            }
            return reply;
        }
    }

    private String findNodeLine(String nodesText, String nodeId) {
        for (String line : nodesText.split("\n")) {
            if (line.startsWith(nodeId)) {
                return line;
            }
        }
        return null;
    }

    /**
     * 等待 observer 节点的本地视图中 slaveNodeId 被识别为 SLAVE
     */
    private void awaitSlaveRoleKnown(EmbeddedNode observer, String slaveNodeId, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            ClusterNode view = observer.getClusterConfig().getNode(slaveNodeId);
            if (view != null && view.isSlave() && view.getMasterNodeId() != null) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        ClusterNode view = observer.getClusterConfig().getNode(slaveNodeId);
        fail("等待 " + slaveNodeId + " 在 " + observer.getPort() + " 视图中变为 SLAVE 超时。"
                + " 当前 isSlave=" + (view != null && view.isSlave())
                + ", masterNodeId=" + (view != null ? view.getMasterNodeId() : "null"));
    }
}
