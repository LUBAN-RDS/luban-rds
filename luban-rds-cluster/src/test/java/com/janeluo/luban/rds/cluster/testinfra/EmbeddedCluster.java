package com.janeluo.luban.rds.cluster.testinfra;

import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量级嵌入式集群，用于集成测试。
 * <p>
 * 仅依赖 cluster + core 模块，不依赖 server 模块。
 * 通过 {@link EmbeddedNode} 启动内嵌的 RESP 服务器和集群总线。
 * </p>
 */
public class EmbeddedCluster implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedCluster.class);

    private final Map<String, EmbeddedNode> nodes = new ConcurrentHashMap<>();
    private final int basePort;
    private final int nodeCount;

    private EmbeddedCluster(int basePort, int nodeCount) {
        this.basePort = basePort;
        this.nodeCount = nodeCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public void start() {
        for (EmbeddedNode node : nodes.values()) {
            node.start();
        }
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        meetAllNodes();
    }

    public void stop() {
        for (EmbeddedNode node : nodes.values()) {
            node.stop();
        }
    }

    private void meetAllNodes() {
        List<EmbeddedNode> nodeList = new ArrayList<>(nodes.values());
        for (int i = 0; i < nodeList.size(); i++) {
            for (int j = i + 1; j < nodeList.size(); j++) {
                EmbeddedNode a = nodeList.get(i);
                EmbeddedNode b = nodeList.get(j);
                // 通过 RESP 协议发送 CLUSTER MEET 命令
                try (Socket socket = new Socket("127.0.0.1", a.getPort())) {
                    socket.setSoTimeout(3000);
                    OutputStream out = socket.getOutputStream();
                    // RESP: *3\r\n$7\r\nCLUSTER\r\n$4\r\nMEET\r\n$9\r\n127.0.0.1\r\n$5\r\n<port>\r\n
                    String port = String.valueOf(b.getPort());
                    String cmd = "*4\r\n$7\r\nCLUSTER\r\n$4\r\nMEET\r\n$9\r\n127.0.0.1\r\n"
                            + "$" + port.length() + "\r\n" + port + "\r\n";
                    out.write(cmd.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    // 读取响应（忽略结果）
                    socket.getInputStream().read(new byte[1024]);
                } catch (Exception e) {
                    log.warn("MEET failed: {} -> {} : {}", a.getNodeId(), b.getNodeId(), e.getMessage());
                }
            }
        }
    }

    public void assignSlotsEvenly() {
        List<EmbeddedNode> masters = new ArrayList<>(nodes.values());
        int total = SlotUtils.CLUSTER_SLOTS;
        int perNode = total / masters.size();
        for (int i = 0; i < masters.size(); i++) {
            int start = i * perNode;
            int end = (i == masters.size() - 1) ? total - 1 : (start + perNode - 1);
            EmbeddedNode node = masters.get(i);
            for (int slot = start; slot <= end; slot++) {
                node.getClusterConfig().setSlotOwner(slot, node.getNodeId());
                node.getSlotManager().addSlots(slot);
            }
            log.info("节点 {} 分配槽位 {}-{}", node.getNodeId(), start, end);
        }
    }

    public EmbeddedNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    public Collection<EmbeddedNode> getNodes() {
        return nodes.values();
    }

    public int getNodeCount() {
        return nodes.size();
    }

    public void waitForClusterOnline(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            boolean allOk = true;
            for (EmbeddedNode node : nodes.values()) {
                if (!node.getClusterConfig().isClusterOk()) {
                    allOk = false;
                    break;
                }
            }
            if (allOk && !nodes.isEmpty()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("等待集群 online 超时 ({}ms)", timeoutMs);
    }

    @Override
    public void close() {
        stop();
    }

    public static class Builder {
        private int nodeCount = 3;
        private int basePort = 7000;

        public Builder nodes(int count) {
            this.nodeCount = count;
            return this;
        }

        public Builder basePort(int port) {
            this.basePort = port;
            return this;
        }

        public EmbeddedCluster build() {
            EmbeddedCluster cluster = new EmbeddedCluster(basePort, nodeCount);
            for (int i = 0; i < nodeCount; i++) {
                EmbeddedNode node = new EmbeddedNode(basePort + i);
                cluster.nodes.put(node.getNodeId(), node);
            }
            return cluster;
        }
    }
}
