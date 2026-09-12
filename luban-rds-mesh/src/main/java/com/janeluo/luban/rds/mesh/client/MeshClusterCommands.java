package com.janeluo.luban.rds.mesh.client;

import com.janeluo.luban.rds.mesh.MeshNode;

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
 *       clusterGenNodesDescription，避免 Redisson split("\n") 残留 \r）。
 *       离线节点（bus 未连接，经 {@code onlinePredicate} 判定）linkState 标
 *       {@code disconnected}，避免集群感知客户端向死节点发起连接；
 *       断开超阈值的节点额外在 flags 列标 {@code fail}（经 {@code failPredicate} 判定），
 *       使 Redisson 等严格按 Redis 语义踢出死节点的客户端立即从拓扑移除；
 *       本节点（myself）恒标 {@code connected}（正在响应请求）。</li>
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

    /** 节点在线判定（nodeId → 是否在线）；null/恒 true 表示不启用死节点标记。 */
    private final java.util.function.Predicate<String> onlinePredicate;

    /** 节点失败判定（nodeId → 是否已断开超阈值）；null/恒 false 表示不标 fail。 */
    private final java.util.function.Predicate<String> failPredicate;

    /**
     * follower 读计数来源（CLUSTER INFO 的 {@code mesh_follower_read_*} 段用）。
     * <p>null（单测/未装配）时该段输出全 0；装配层传 {@code () -> meshNode}。</p>
     */
    private final Supplier<MeshNode> meshNodeSupplier;

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
        this(leaderNodeIdSupplier, leaderAddrSupplier, allNodes, selfNodeId, null, null);
    }

    /**
     * 重载构造器：额外接受节点在线判定。
     *
     * @param onlinePredicate nodeId → 在线；{@code null} 视为恒 true（不标记 disconnected）。
     *                        由 MeshBootstrap 装配 {@code busClient::isConnected}。
     */
    public MeshClusterCommands(Supplier<String> leaderNodeIdSupplier,
                                Supplier<String> leaderAddrSupplier,
                                Map<String, NodeInfo> allNodes,
                                String selfNodeId,
                                java.util.function.Predicate<String> onlinePredicate) {
        this(leaderNodeIdSupplier, leaderAddrSupplier, allNodes, selfNodeId, onlinePredicate, null);
    }

    /**
     * 重载构造器：额外接受节点失败判定。
     * <p>
     * 当 {@code failPredicate} 对某节点返回 true 时，CLUSTER NODES 的 flags 列追加
     * {@code fail}（对齐 Redis 7.x Gossip 标记语义），使 Redisson / JedisCluster 等
     * 集群感知客户端立即从拓扑移除该死节点，停止向死节点发起连接。
     * </p>
     *
     * @param onlinePredicate nodeId → 在线；{@code null} 视为恒 true
     * @param failPredicate   nodeId → 已失败（断开超阈值）；{@code null} 视为恒 false（不标 fail）
     */
    public MeshClusterCommands(Supplier<String> leaderNodeIdSupplier,
                                Supplier<String> leaderAddrSupplier,
                                Map<String, NodeInfo> allNodes,
                                String selfNodeId,
                                java.util.function.Predicate<String> onlinePredicate,
                                java.util.function.Predicate<String> failPredicate) {
        this(leaderNodeIdSupplier, leaderAddrSupplier, allNodes, selfNodeId,
                onlinePredicate, failPredicate, null);
    }

    /**
     * 完整构造器：额外接受 follower 读计数来源（CLUSTER INFO 的 {@code mesh_follower_read_*} 段）。
     *
     * @param meshNodeSupplier 提供实时计数的 {@link MeshNode}（通常 {@code () -> meshNode}）；
     *                         {@code null} 时该段输出全 0（单测/未启用场景）
     */
    public MeshClusterCommands(Supplier<String> leaderNodeIdSupplier,
                                Supplier<String> leaderAddrSupplier,
                                Map<String, NodeInfo> allNodes,
                                String selfNodeId,
                                java.util.function.Predicate<String> onlinePredicate,
                                java.util.function.Predicate<String> failPredicate,
                                Supplier<MeshNode> meshNodeSupplier) {
        this.leaderNodeIdSupplier = leaderNodeIdSupplier != null
                ? leaderNodeIdSupplier : () -> null;
        this.leaderAddrSupplier = leaderAddrSupplier != null
                ? leaderAddrSupplier : () -> null;
        this.allNodes = allNodes != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(allNodes))
                : Collections.emptyMap();
        this.selfNodeId = selfNodeId;
        this.onlinePredicate = onlinePredicate != null ? onlinePredicate : n -> true;
        this.failPredicate = failPredicate != null ? failPredicate : n -> false;
        this.meshNodeSupplier = meshNodeSupplier;
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
            // Q5（2026-09-11 审计 P2）：无 Leader 返回标准 -CLUSTERDOWN 而非空数组 *0——
            // 空槽映射令严格集群客户端初始化挂死（813 事故），CLUSTERDOWN 使其退避重试
            return "-CLUSTERDOWN The cluster is down\r\n".getBytes(StandardCharsets.ISO_8859_1);
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

        // fix-mesh-follower-read：追加 follower 读计数段（不改既有 cluster_* 字段与顺序）。
        // 未装配 MeshNode 时输出全 0。
        MeshNode node = meshNodeSupplier != null ? meshNodeSupplier.get() : null;
        sb.append(buildMeshInfoSection(
                node != null ? node.readIndexFetchCount() : 0L,
                node != null ? node.readIndexCacheHitCount() : 0L,
                node != null ? node.readIndexCacheInvalidationCount() : 0L,
                node != null ? node.readIndexCoalescedCount() : 0L,
                node != null ? node.followerReadFallbackCount() : 0L,
                node != null ? node.followerReadLocalCount() : 0L,
                node != null ? node.followerReadRejectedCount() : 0L,
                0L));

        return toBulkStringBytes(sb.toString());
    }

    /**
     * follower 读计数（CLUSTER INFO 段，fix-mesh-follower-read）。
     * <p>开关关闭（{@code mesh-read-from-follower=off}）时各计数保持 0——gate 不会进入
     * follower 读路径。此处保持 CLUSTER INFO 的扁平 {@code key:value} 格式，
     * 不插入 {@code #} 段头（CLUSTER INFO 无段头，且严格客户端按 {@code :} 拆分行）。</p>
     *
     * @param indexFetch       取读点总次数（每次进入 fetchReadIndex 即计，含成功与失败）
     * @param cacheHit         readIndex 短窗口缓存命中次数
     * @param cacheInvalidated 缓存失效次数（term 变化 / Leader 变更）
     * @param coalesced        在途取读点合并次数
     * @param fallbackMoved    回落 MOVED 次数（fetch 失败 + gate 回落分支）
     * @param local            follower 本地读成功次数
     * @param rejected         在途上限拒绝次数
     * @param notReadyRejected 未就绪拒绝次数；<b>当前无独立数据源，恒 0（预留）</b>
     */
    static String buildMeshInfoSection(long indexFetch, long cacheHit, long cacheInvalidated,
                                       long coalesced, long fallbackMoved, long local, long rejected,
                                       long notReadyRejected) {
        StringBuilder sb = new StringBuilder();
        sb.append("mesh_follower_read_index_fetch:").append(indexFetch).append("\r\n");
        sb.append("mesh_follower_read_cache_hit:").append(cacheHit).append("\r\n");
        sb.append("mesh_follower_read_cache_invalidated:").append(cacheInvalidated).append("\r\n");
        sb.append("mesh_follower_read_coalesced:").append(coalesced).append("\r\n");
        sb.append("mesh_follower_read_fallback_moved:").append(fallbackMoved).append("\r\n");
        sb.append("mesh_follower_read_local:").append(local).append("\r\n");
        sb.append("mesh_follower_read_rejected:").append(rejected).append("\r\n");
        sb.append("mesh_follower_read_not_ready_rejected:").append(notReadyRejected).append("\r\n");
        return sb.toString();
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

            // <flags>：myself?, master/slave, [fail]
            StringBuilder flags = new StringBuilder();
            if (isSelf) {
                flags.append("myself");
            }
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append(showAsMaster ? "master" : "slave");
            // 死节点（断开超阈值）追加 fail flag，使 Redisson 等客户端立即踢出
            // myself 恒不标 fail（本节点正在响应请求）
            if (!isSelf && failPredicate.test(nid)) {
                flags.append(",fail");
            }
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
            // 本节点正在响应请求，恒 connected；其余节点按在线判定（出站 bus 链路活跃）
            boolean online = isSelf || onlinePredicate.test(nid);
            sb.append(online ? "connected" : "disconnected");

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
