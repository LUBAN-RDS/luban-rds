package com.janeluo.luban.rds.mesh.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 集群感知客户端引导命令响应生成器（DESIGN §5.6 场景 6 / 阶段 8）。
 * <p>
 * mesh 模式无分片（16384 个 slot 全量数据由 Leader 持有），但仍需响应
 * {@code CLUSTER SLOTS / NODES / INFO}，使 JedisCluster / lettuce cluster 等
 * 集群感知客户端能通过标准引导流程连上 Leader——这是「集群感知客户端零侵入」的成立前提。
 * </p>
 *
 * <h3>响应语义（DESIGN §5.6）</h3>
 * <ul>
 *   <li><b>CLUSTER SLOTS</b>：{@code [[0, 16383, [leaderIp, leaderPort, leaderNodeId], []]]}
 *       —— 全 16384 个 slot 指向当前 Leader（mesh 无分片，单 master）。
 *       第 4 元素为 replicas 空数组（对齐 Redis 7.0 格式，缺省会挂死严格解析器）。
 *       无 Leader 时返回空数组 {@code *0\r\n}（客户端会重试引导）。</li>
 *   <li><b>CLUSTER NODES</b>：3 节点一行一个，复用 Redis master/slave 语义：
 *       Leader 行 {@code myself,master} 持 {@code 0-16383}；2 个 Follower 行
 *       {@code slave <leaderNodeId>}。行尾用裸 {@code \n}（对齐 Redis
 *       clusterGenNodesDescription，避免 Redisson split("\n") 残留 \r）。</li>
 *   <li><b>CLUSTER INFO</b>：多行 {@code key:value}（{@code \r\n} 分隔），
 *       关键字段 {@code cluster_state:ok}、{@code cluster_known_nodes:3}、
 *       {@code cluster_slots_ok:16384}。无 Leader 时 {@code cluster_state:fail}。</li>
 * </ul>
 *
 * <h3>Leader 动态感知</h3>
 * <p>
 * Leader 在 Raft 选举中会变更。本类通过 {@link Supplier} 动态获取当前 Leader 的
 * nodeId 与 service 地址（{@code "ip:port"}），SLOTS/NODES/INFO 实时反映新 Leader。
 * Leader 变更后客户端收到 MOVED → 再次调 CLUSTER SLOTS 刷新拓扑 → 重连新 Leader。
 * </p>
 *
 * <h3>RESP 格式参考</h3>
 * <p>
 * 对齐 {@code luban-rds-cluster} 的 {@code ClusterCommandHandler} 既有格式：
 * SLOTS 的 master endpoint 为 {@code [bulk(ip), integer(port), bulk(nodeId)]}；
 * NODES/INFO 整体作为 bulk string 返回（{@code $<len>\r\n<payload>\r\n}）。
 * </p>
 *
 * <h3>线程安全</h3>
 * {@code allNodes} 构造后只读；{@link Supplier} 由调用方保证线程安全。
 * 本类无状态，可在多线程 Netty handler 间共享。
 *
 * @author janeluo
 * @since 阶段 8
 */
public class MeshClusterCommands {

    /** Redis Cluster 总 slot 数（CRC16 哈希空间）。 */
    private static final int TOTAL_SLOTS = 16384;

    /** 末 slot（闭区间右端，0-based）。 */
    private static final int LAST_SLOT = TOTAL_SLOTS - 1;

    /** 当前 Leader 的 nodeId 提供者；可能返回 {@code null}（选举中/无 Leader）。 */
    private final Supplier<String> leaderNodeIdSupplier;

    /** 当前 Leader 的 service 地址提供者（{@code "ip:port"}）；可能返回 {@code null}。 */
    private final Supplier<String> leaderAddrSupplier;

    /** 3 节点信息映射（nodeId → {@link NodeInfo}）；构造后只读。 */
    private final Map<String, NodeInfo> allNodes;

    /** 本节点 nodeId（用于 CLUSTER NODES 的 {@code myself} 标记）。 */
    private final String selfNodeId;

    /**
     * 构造集群命令响应生成器。
     *
     * @param leaderNodeIdSupplier 当前 Leader nodeId 提供者（可返回 null）
     * @param leaderAddrSupplier   当前 Leader service 地址（{@code "ip:port"}）提供者（可返回 null）
     * @param allNodes             3 节点信息映射（nodeId → NodeInfo）；null 视为空
     * @param selfNodeId           本节点 nodeId（用于 NODES 的 myself 标记）；可为 null
     */
    public MeshClusterCommands(Supplier<String> leaderNodeIdSupplier,
                                Supplier<String> leaderAddrSupplier,
                                Map<String, NodeInfo> allNodes,
                                String selfNodeId) {
        this.leaderNodeIdSupplier = leaderNodeIdSupplier != null
                ? leaderNodeIdSupplier : () -> null;
        this.leaderAddrSupplier = leaderAddrSupplier != null
                ? leaderAddrSupplier : () -> null;
        this.allNodes = allNodes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(allNodes))
                : Collections.emptyMap();
        this.selfNodeId = selfNodeId;
    }

    // ==================== CLUSTER SLOTS ====================

    /**
     * 生成 {@code CLUSTER SLOTS} 响应（RESP 字节）。
     * <p>
     * mesh 无分片：全 16384 slot 指向当前 Leader，输出单个 slot range：
     * {@code [[0, 16383, [leaderIp, leaderPort, leaderNodeId], []]]}。
     * </p>
     * <p>
     * 无 Leader（leaderAddr/nodeId 为 null，或 Leader 的 NodeInfo 缺失）时返回空数组
     * {@code *0\r\n}——集群感知客户端会据此退避重试引导。
     * </p>
     *
     * @return RESP 数组字节（UTF-8/ISO-8859-1 兼容，均为 ASCII）
     */
    public byte[] clusterSlots() {
        Endpoint leader = resolveLeader();
        if (leader == null) {
            // 无 Leader：返回空数组（对齐 Redis clusterSlotsCommand 在无槽位时的 *0\r\n）
            return "*0\r\n".getBytes(StandardCharsets.ISO_8859_1);
        }

        StringBuilder sb = new StringBuilder();
        // 外层 1 个元素（1 个 slot range）
        sb.append("*1\r\n");
        // 内层 4 个元素：startSlot, endSlot, masterInfo[3], replicas
        sb.append("*4\r\n");
        sb.append(":0\r\n");             // startSlot = 0
        sb.append(":").append(LAST_SLOT).append("\r\n"); // endSlot = 16383
        // master endpoint: [ip(bulk), port(integer), nodeId(bulk)]
        appendNodeEndpoint(sb, leader);
        // replicas 数组：mesh 无从节点（全 16384 slot 仅 Leader 持有），空数组。
        // 必须补齐——*4 声明 4 元素，缺第 4 元素会让严格 RESP 解析器（redis-cli/
        // Lettuce/Jedis）永久等待，拓扑刷新挂起（对齐 Redis 7.0 clusterSlotsCommand）。
        sb.append("*0\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    // ==================== CLUSTER NODES ====================

    /**
     * 生成 {@code CLUSTER NODES} 响应（RESP bulk string 字节）。
     * <p>
     * 3 节点一行一个，对齐 Redis CLUSTER NODES 格式：
     * <pre>
     * &lt;nodeId&gt; &lt;ip:port@cport&gt; &lt;flags&gt; &lt;masterId|-&gt; &lt;pingSent&gt; &lt;pongRecv&gt; &lt;configEpoch&gt; &lt;linkState&gt; &lt;slot...&gt;
     * </pre>
     * Leader 行标记 {@code myself,master} 持 {@code 0-16383}；Follower 行 {@code slave <leaderNodeId>}。
     * 行尾用裸 {@code \n}（对齐 Redis clusterGenNodesDescription）。
     * </p>
     * <p>
     * 无 Leader 时仍输出 3 节点，但所有节点标记为无槽 master（{@code master,noflags?}）——
     * 简化处理：此时不挂 slot，避免误导客户端。{@link #clusterInfo()} 同步反映 {@code cluster_state:fail}。
     * </p>
     *
     * @return RESP bulk string 字节（{@code $<len>\r\n<payload>\r\n}）
     */
    public byte[] clusterNodes() {
        String leaderNodeId = currentLeaderNodeId();
        String payload = buildNodesPayload(leaderNodeId);
        return toBulkStringBytes(payload);
    }

    // ==================== CLUSTER INFO ====================

    /**
     * 生成 {@code CLUSTER INFO} 响应（RESP bulk string 字节）。
     * <p>
     * 返回多行 {@code key:value}（{@code \r\n} 分隔），关键字段：
     * {@code cluster_enabled:1}、{@code cluster_state:ok|fail}、
     * {@code cluster_slots_assigned:16384|0}、{@code cluster_slots_ok:16384|0}、
     * {@code cluster_known_nodes:3}、{@code cluster_size:1|0}（mesh 无分片，size=1 个 master）、
     * {@code cluster_current_epoch:1}、{@code cluster_my_epoch:1}、
     * {@code cluster_stats_messages_sent:0}、{@code cluster_stats_messages_received:0}。
     * </p>
     *
     * @return RESP bulk string 字节
     */
    public byte[] clusterInfo() {
        boolean hasLeader = resolveLeader() != null;

        StringBuilder sb = new StringBuilder();
        sb.append("cluster_enabled:1").append("\r\n");
        sb.append("cluster_state:").append(hasLeader ? "ok" : "fail").append("\r\n");
        sb.append("cluster_slots_assigned:").append(hasLeader ? TOTAL_SLOTS : 0).append("\r\n");
        sb.append("cluster_slots_ok:").append(hasLeader ? TOTAL_SLOTS : 0).append("\r\n");
        sb.append("cluster_slots_pfail:0").append("\r\n");
        sb.append("cluster_slots_fail:0").append("\r\n");
        sb.append("cluster_known_nodes:").append(allNodes.size()).append("\r\n");
        // mesh 无分片：size = master 数（有 Leader 时 1，无 Leader 时 0）
        sb.append("cluster_size:").append(hasLeader ? 1 : 0).append("\r\n");
        sb.append("cluster_current_epoch:1").append("\r\n");
        sb.append("cluster_my_epoch:1").append("\r\n");
        sb.append("cluster_stats_messages_sent:0").append("\r\n");
        sb.append("cluster_stats_messages_received:0").append("\r\n");

        return toBulkStringBytes(sb.toString());
    }

    // ==================== 内部辅助 ====================

    /**
     * 解析当前 Leader 的 endpoint（ip/port/nodeId）。
     * <p>
     * 优先用 leaderAddrSupplier 的 {@code "ip:port"} + leaderNodeIdSupplier 的 nodeId；
     * 若 addr 不可解析，回退到 allNodes 中 Leader nodeId 对应的 NodeInfo。
     * 任一关键字段缺失返回 {@code null}（表示无 Leader）。
     * </p>
     */
    private Endpoint resolveLeader() {
        String nodeId = currentLeaderNodeId();
        String addr = currentLeaderAddr();

        if (addr != null && !addr.isEmpty()) {
            String[] parts = parseHostPort(addr);
            if (parts != null && nodeId != null && !nodeId.isEmpty()) {
                return new Endpoint(parts[0], Integer.parseInt(parts[1]), nodeId);
            }
        }

        // 回退：用 allNodes 中 Leader 的 NodeInfo 补全 ip/port（或 addr）
        if (nodeId != null) {
            NodeInfo info = allNodes.get(nodeId);
            if (info != null) {
                String ip = info.getIp();
                int port = info.getPort();
                if (ip != null && !ip.isEmpty() && port > 0) {
                    return new Endpoint(ip, port, nodeId);
                }
            }
        }
        return null;
    }

    private String currentLeaderNodeId() {
        try {
            return leaderNodeIdSupplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentLeaderAddr() {
        try {
            return leaderAddrSupplier.get();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析 {@code "host:port"} 为 [host, port]；非法返回 {@code null}。
     */
    private static String[] parseHostPort(String addr) {
        int idx = addr.lastIndexOf(':');
        if (idx <= 0 || idx == addr.length() - 1) {
            return null;
        }
        try {
            int port = Integer.parseInt(addr.substring(idx + 1));
            if (port <= 0 || port > 65535) {
                return null;
            }
            return new String[]{addr.substring(0, idx), String.valueOf(port)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 追加 master endpoint 到 RESP 构建器：{@code *3\r\n $ip\r\n :port\r\n $nodeId\r\n}。
     * <p>
     * 对齐 luban-rds-cluster ClusterCommandHandler.appendNodeEndpoint：port 编码为 integer。
     * </p>
     */
    private static void appendNodeEndpoint(StringBuilder sb, Endpoint ep) {
        String ip = ep.ip;
        String nodeId = ep.nodeId;
        sb.append("*3\r\n");
        sb.append("$").append(ip.length()).append("\r\n").append(ip).append("\r\n");
        sb.append(":").append(ep.port).append("\r\n");
        sb.append("$").append(nodeId.length()).append("\r\n").append(nodeId).append("\r\n");
    }

    /**
     * 构建 CLUSTER NODES 的多行文本（无 RESP 包装，行尾 {@code \n}）。
     */
    private String buildNodesPayload(String leaderNodeId) {
        StringBuilder sb = new StringBuilder();

        // 按 nodeId 字典序输出（对齐 Redis clusterGenNodesDescription 的稳定顺序）
        List<NodeInfo> sorted = new ArrayList<>(allNodes.values());
        sorted.sort((a, b) -> {
            String idA = a.getNodeId() != null ? a.getNodeId() : "";
            String idB = b.getNodeId() != null ? b.getNodeId() : "";
            return idA.compareTo(idB);
        });

        for (NodeInfo node : sorted) {
            String nid = node.getNodeId();
            if (nid == null || nid.isEmpty()) {
                continue;
            }

            boolean isLeader = leaderNodeId != null && leaderNodeId.equals(nid);
            boolean isSelf = selfNodeId != null && selfNodeId.equals(nid);
            boolean noLeader = leaderNodeId == null;
            // 无 Leader 时所有节点按 standalone master 展示（对齐 Redis：无主从关系）
            boolean showAsMaster = noLeader || isLeader;

            // <nodeId>
            sb.append(nid);
            sb.append(" ");

            // <ip:port@cport>
            String ip = node.getIp();
            int port = node.getPort();
            int cport = node.getBusPort() > 0 ? node.getBusPort() : (port + 10000);
            if (ip == null || ip.isEmpty() || port <= 0) {
                sb.append(":0@0");
            } else {
                sb.append(ip).append(":").append(port).append("@").append(cport);
            }
            sb.append(" ");

            // <flags>：myself?, master/slave
            StringBuilder flags = new StringBuilder();
            if (isSelf) {
                flags.append("myself");
            }
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append(showAsMaster ? "master" : "slave");
            sb.append(flags);
            sb.append(" ");

            // <masterId|->：master 节点显示 "-"，slave 节点显示 leaderNodeId
            if (showAsMaster) {
                sb.append("-");
            } else {
                sb.append(leaderNodeId);
            }
            sb.append(" ");

            // <pingSent> <pongRecv>（mesh 不维护 ping/pong 时间戳，输出 0）
            sb.append("0 0 ");

            // <configEpoch>
            sb.append("1 ");

            // <linkState>
            sb.append("connected");

            // <slot...>：仅 Leader（有 Leader 时）持 0-16383
            if (isLeader && !noLeader) {
                sb.append(" 0-").append(LAST_SLOT);
            }

            // 行尾裸 \n（对齐 Redis clusterGenNodesDescription）
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * 将文本包装为 RESP bulk string 字节：{@code $<len>\r\n<text>\r\n}。
     * <p>用 ISO-8859-1 编码（ASCII 安全，与 protocolParser.serialize(String) 一致）。</p>
     */
    private static byte[] toBulkStringBytes(String text) {
        byte[] payload = text.getBytes(StandardCharsets.ISO_8859_1);
        String header = "$" + payload.length + "\r\n";
        byte[] headerBytes = header.getBytes(StandardCharsets.ISO_8859_1);
        byte[] trailing = "\r\n".getBytes(StandardCharsets.ISO_8859_1);

        byte[] result = new byte[headerBytes.length + payload.length + trailing.length];
        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        System.arraycopy(payload, 0, result, headerBytes.length, payload.length);
        System.arraycopy(trailing, 0, result, headerBytes.length + payload.length, trailing.length);
        return result;
    }

    // ==================== 内部类型 ====================

    /** Leader endpoint 解析结果（ip/port/nodeId）。 */
    private static final class Endpoint {
        final String ip;
        final int port;
        final String nodeId;

        Endpoint(String ip, int port, String nodeId) {
            this.ip = ip;
            this.port = port;
            this.nodeId = nodeId;
        }
    }

    /**
     * 节点信息（nodeId / ip / port / busPort / role）。
     * <p>
     * 用于 CLUSTER NODES 的 3 节点拓扑展示。role 仅作记录，实际 Leader/Follower 判定
     * 由运行时 {@link #leaderNodeIdSupplier} 决定（Leader 会变更）。
     * </p>
     */
    public static final class NodeInfo {
        /** Redis Cluster 标准 nodeId（40 字符十六进制）。 */
        private final String nodeId;
        private final String ip;
        private final int port;
        /** 集群总线端口（{@code @cport}）；{@code <=0} 时用 port+10000 推导。 */
        private final int busPort;
        /** 初始角色（LEADER/FOLLOWER），仅记录；运行时以 Leader supplier 为准。 */
        private final NodeRole role;

        public NodeInfo(String nodeId, String ip, int port, int busPort, NodeRole role) {
            this.nodeId = nodeId;
            this.ip = ip;
            this.port = port;
            this.busPort = busPort;
            this.role = role;
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        public int getBusPort() {
            return busPort;
        }

        public NodeRole getRole() {
            return role;
        }
    }

    /** 节点角色枚举（仅用于 NodeInfo 记录初始角色）。 */
    public enum NodeRole {
        LEADER,
        FOLLOWER
    }
}
