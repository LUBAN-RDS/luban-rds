package com.janeluo.luban.rds.cluster.handler;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterConfigPersister;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.config.ClusterStats;
import com.janeluo.luban.rds.cluster.gossip.FailoverManager;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.node.ClusterLink;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CLUSTER 命令处理器
 * <p>
 * 处理所有 CLUSTER 相关命令，包括集群状态查询、节点管理、槽位管理等
 * </p>
 */
public class ClusterCommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(ClusterCommandHandler.class);

    /**
     * 集群配置
     */
    private final ClusterConfig clusterConfig;

    /**
     * 槽位管理器
     */
    private final SlotManager slotManager;

    /**
     * 集群状态管理器
     */
    private final ClusterStateManager stateManager;

    /**
     * Gossip 协议
     */
    private final GossipProtocol gossipProtocol;

    /**
     * 集群配置文件路径（用于 CLUSTER SAVECONFIG 持久化）
     */
    private final String clusterConfigFilePath;

    /**
     * 内存存储（用于 GETKEYSINSLOT / COUNTKEYSINSLOT 访问实际键）
     */
    private final MemoryStore memoryStore;

    /**
     * 槽位迁移状态
     * key: 槽位号
     * value: 迁移状态（IMPORTING/MIGRATING/NODE_ID）
     */
    private final ConcurrentHashMap<Integer, String> slotMigrationState;

    /**
     * 槽位迁移目标节点
     * key: 槽位号
     * value: 目标节点ID
     */
    private final ConcurrentHashMap<Integer, String> slotMigrationTarget;

    /**
     * FORGET 命令的延迟移除节点列表
     * key: 节点ID
     * value: 过期时间戳
     */
    private final ConcurrentHashMap<String, Long> forgetNodes;

    /**
     * FORGET 延迟时间（毫秒）
     */
    private static final long FORGET_DELAY_MS = 60000;

    /**
     * 拓扑变更回调（由 NettyRedisServer 注入，用于自动触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑发生变更时调用此回调，触发 nodes.conf 持久化。
     * </p>
     */
    private Runnable onTopologyChanged;

    /**
     * 复制生命周期监听器（由 NettyRedisServer 注入，用于在角色变更时启停复制连接）。
     * <p>
     * 默认使用 NoOp 实现，保证未注入时（如单元测试）不触发复制逻辑。
     * </p>
     */
    private volatile ReplicationLifecycleListener replicationLifecycleListener =
            new NoOpReplicationLifecycleListener();

    /**
     * 构造方法
     *
     * @param clusterConfig           集群配置
     * @param slotManager             槽位管理器
     * @param stateManager            集群状态管理器
     * @param gossipProtocol          Gossip 协议
     * @param clusterConfigFilePath   集群配置文件路径（用于 CLUSTER SAVECONFIG 持久化，可为 null）
     * @param memoryStore             内存存储（用于 GETKEYSINSLOT / COUNTKEYSINSLOT，可为 null）
     */
    public ClusterCommandHandler(ClusterConfig clusterConfig, SlotManager slotManager,
                                  ClusterStateManager stateManager, GossipProtocol gossipProtocol,
                                  String clusterConfigFilePath, MemoryStore memoryStore) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.stateManager = stateManager;
        this.gossipProtocol = gossipProtocol;
        this.clusterConfigFilePath = clusterConfigFilePath;
        this.memoryStore = memoryStore;
        this.slotMigrationState = new ConcurrentHashMap<>();
        this.slotMigrationTarget = new ConcurrentHashMap<>();
        this.forgetNodes = new ConcurrentHashMap<>();
    }

    /**
     * 设置拓扑变更回调（用于自动触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑发生变更（槽位重分配、节点角色变更等）时调用此回调。
     * </p>
     *
     * @param onTopologyChanged 拓扑变更回调
     */
    public void setOnTopologyChanged(Runnable onTopologyChanged) {
        this.onTopologyChanged = onTopologyChanged;
    }

    /**
     * 设置复制生命周期监听器（由 NettyRedisServer 在装配时注入）。
     *
     * @param listener 复制生命周期监听器，null 时回退为 NoOp 实现
     */
    public void setReplicationLifecycleListener(ReplicationLifecycleListener listener) {
        this.replicationLifecycleListener =
                listener != null ? listener : new NoOpReplicationLifecycleListener();
    }

    /**
     * 通知拓扑变更（触发 nodes.conf 持久化）
     * <p>
     * 通过 ClusterConfig.markDirty() 标记脏状态，并立即触发回调执行持久化。
     * </p>
     */
    private void notifyTopologyChanged() {
        clusterConfig.markDirty();
        if (onTopologyChanged != null) {
            onTopologyChanged.run();
        }
    }

    /**
     * 处理 CLUSTER 命令
     *
     * @param args 命令参数，args[0] 是子命令
     * @return RESP 格式的响应
     */
    public String handle(String[] args) {
        if (args == null || args.length == 0) {
            return "-ERR wrong number of arguments for 'cluster' command\r\n";
        }

        String subcommand = args[0].toUpperCase();

        try {
            switch (subcommand) {
                case "INFO":
                    return clusterInfo();
                case "NODES":
                    return clusterNodes();
                case "SLOTS":
                    return clusterSlots();
                case "MEET":
                    return clusterMeet(args);
                case "FORGET":
                    return clusterForget(args);
                case "REPLICATE":
                    return clusterReplicate(args);
                case "ADDSLOTS":
                    return clusterAddslots(args);
                case "DELSLOTS":
                    return clusterDelslots(args);
                case "SETSLOT":
                    return clusterSetslot(args);
                case "KEYSLOT":
                    return clusterKeyslot(args);
                case "GETKEYSINSLOT":
                    return clusterGetkeysinslot(args);
                case "COUNTKEYSINSLOT":
                    return clusterCountkeysinslot(args);
                case "SLAVES":
                    return clusterSlaves(args);
                case "FAILOVER":
                    return clusterFailover(args);
                case "MYID":
                    return clusterMyid();
                case "FLUSHSLOTS":
                    return clusterFlushslots();
                case "BUMPEPOCH":
                    return clusterBumpepoch();
                case "SET-CONFIG-EPOCH":
                    return clusterSetConfigEpoch(args);
                case "SAVECONFIG":
                    return clusterSaveconfig();
                case "REPLICAS":
                    return clusterSlaves(args);
                case "SHARDS":
                    return clusterShards();
                case "LINKS":
                    // 本实现无跨节点连接统计（总线连接不维护于此处），返回空数组（合法响应）
                    return "*0\r\n";
                case "RESET":
                    return clusterReset(args);
                case "COUNT-FAILURE-REPORTS":
                    return clusterCountFailureReports(args);
                case "ADDSLOTSRANGE":
                    return clusterAddslotsRange(args);
                case "DELSLOTSRANGE":
                    return clusterDelslotsRange(args);
                case "REFRESH":
                    return clusterRefresh();
                case "HELP":
                    return clusterHelp();
                default:
                    return "-ERR Unknown subcommand or wrong number of arguments for '" 
                            + subcommand + "'\r\n";
            }
        } catch (IllegalArgumentException e) {
            logger.warn("CLUSTER {} 命令参数错误: {}", subcommand, e.getMessage());
            return "-ERR " + safeMessage(e) + "\r\n";
        } catch (Exception e) {
            logger.error("CLUSTER {} 命令执行失败", subcommand, e);
            return "-ERR " + safeMessage(e) + "\r\n";
        }
    }

    /**
     * 安全获取异常消息，避免 null 导致 RESP 输出 "-ERR null"。
     *
     * @param e 异常
     * @return 非空的消息字符串
     */
    private static String safeMessage(Exception e) {
        String msg = e.getMessage();
        return msg != null ? msg : e.getClass().getSimpleName();
    }

    /**
     * CLUSTER INFO 命令
     * 返回集群状态信息
     *
     * @return 集群状态信息
     */
    private String clusterInfo() {
        ClusterStats stats = stateManager.getStats();

        StringBuilder sb = new StringBuilder();

        // 集群启用标志（ClusterCommandHandler 仅在集群模式下被装配，故恒为 1）
        sb.append("cluster_enabled:1").append("\r\n");

        // 集群状态
        sb.append("cluster_state:").append(stats.getState()).append("\r\n");

        // 槽位信息
        sb.append("cluster_slots_assigned:").append(stats.getSlotsAssigned()).append("\r\n");
        sb.append("cluster_slots_ok:").append(stats.getSlotsOk()).append("\r\n");
        sb.append("cluster_slots_pfail:").append(stats.getSlotsPfail()).append("\r\n");
        sb.append("cluster_slots_fail:").append(stats.getSlotsFail()).append("\r\n");

        // 节点信息
        sb.append("cluster_known_nodes:").append(stats.getKnownNodes()).append("\r\n");
        sb.append("cluster_size:").append(stats.getSize()).append("\r\n");

        // 纪元信息
        sb.append("cluster_current_epoch:").append(stats.getCurrentEpoch()).append("\r\n");
        sb.append("cluster_my_epoch:").append(stats.getMyEpoch()).append("\r\n");

        // 消息统计：先按类型输出（仅非零类型，对齐 Redis clusterInfoCommand），再输出汇总。
        // N-26：per-type 计数在总线层（ClusterBusHandler/ClusterBusClient）按消息类型记录。
        for (Map.Entry<String, Long> e : stats.getMessagesSentByType().entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                sb.append("cluster_stats_messages_").append(e.getKey()).append("_sent:")
                        .append(e.getValue()).append("\r\n");
            }
        }
        sb.append("cluster_stats_messages_sent:").append(stats.getMessagesSent()).append("\r\n");
        for (Map.Entry<String, Long> e : stats.getMessagesReceivedByType().entrySet()) {
            if (e.getValue() != null && e.getValue() > 0) {
                sb.append("cluster_stats_messages_").append(e.getKey()).append("_received:")
                        .append(e.getValue()).append("\r\n");
            }
        }
        sb.append("cluster_stats_messages_received:").append(stats.getMessagesReceived()).append("\r\n");

        // N-26：对齐 Redis 7.2 的 total_cluster_links_buffer_limit_exceeded 字段
        //（本实现总线发送缓冲暂不限流，恒为 0）。
        sb.append("total_cluster_links_buffer_limit_exceeded:0").append("\r\n");

        return sb.toString();
    }

    /**
     * CLUSTER NODES 命令
     * 返回节点列表和槽位分配
     * 格式：
     * <nodeid> <ip:port@cport> <flags> <master> <ping-sent> <pong-recv> <config-epoch> <link-state> <slot>
     *
     * @return 节点列表信息
     */
    private String clusterNodes() {
        StringBuilder sb = new StringBuilder();

        // 按 nodeId 字典序排序，保证 CLUSTER NODES 输出稳定（对齐 Redis clusterGenNodesDescription）
        List<ClusterNode> sortedNodes = new ArrayList<>(clusterConfig.getAllNodes());
        sortedNodes.sort((a, b) -> {
            String idA = a.getNodeId() != null ? a.getNodeId() : "";
            String idB = b.getNodeId() != null ? b.getNodeId() : "";
            return idA.compareTo(idB);
        });

        for (ClusterNode node : sortedNodes) {
            // N-26：仅跳过 HANDSHAKE 临时节点；NOADDR 节点不再跳过——
            // 对齐 Redis clusterGenNodesDescription（filter=0 输出全部已知节点），
            // NOADDR 节点以 :0@0 地址展示。
            if (node.hasState(ClusterNodeState.HANDSHAKE)) {
                continue;
            }

            // 节点ID
            sb.append(node.getNodeId());

            // 地址信息 ip:port@cport（NOADDR 节点无地址，对齐 Redis 输出 :0@0）
            sb.append(" ");
            if (node.hasState(ClusterNodeState.NOADDR)
                    || node.getIp() == null || node.getIp().isEmpty()) {
                sb.append(":0@0");
            } else {
                sb.append(node.getFullAddress());
            }

            // 状态标志
            sb.append(" ");
            sb.append(buildNodeFlags(node));

            // 主节点ID（从节点显示主节点ID，主节点显示"-"）
            sb.append(" ");
            if (node.isSlave() && node.getMasterNodeId() != null) {
                sb.append(node.getMasterNodeId());
            } else {
                sb.append("-");
            }

            // ping 发送时间（毫秒时间戳，0表示未发送）
            sb.append(" ");
            sb.append(node.getLastPingTime());

            // pong 接收时间（毫秒时间戳）
            sb.append(" ");
            sb.append(node.getLastPongTime());

            // 配置纪元（N-26：从节点输出其 master 的 configEpoch，对齐 Redis
            // clusterGenNodeDescription 的 nodeEpoch = slaveof->configEpoch）
            sb.append(" ");
            sb.append(resolveNodeEpoch(node));

            // 连接状态
            sb.append(" ");
            ClusterLink link = node.getLink();
            if (link != null && link.isConnected()) {
                sb.append("connected");
            } else {
                sb.append("disconnected");
            }

            // 槽位分配（仅主节点显示）
            if (node.isMaster() && node.getSlotCount() > 0) {
                sb.append(" ");
                sb.append(formatSlots(node.getSlots()));
            }

            // 行尾使用裸 \n，对齐真实 Redis CLUSTER NODES bulk payload 行为
            // （clusterGenNodesDescription 中 sdscatlen(ni,"\n",1)）。
            // 若用 \r\n，客户端（如 Redisson ClusterNodesDecoder 用 split("\n") 切行）
            // 会在每行末尾残留 \r，导致末尾 slot 字段解析为 "0-5460\r" 抛 NumberFormatException。
            sb.append("\n");
        }

        return sb.toString();
    }

    /**
     * CLUSTER SLOTS 命令
     * 返回槽位分配信息，格式为嵌套数组：
     * [startSlot, endSlot, [ip, port, nodeId], [replica-ip, replica-port, replica-nodeId], ...]
     *
     * @return RESP 格式的槽位信息
     */
    private String clusterSlots() {
        List<SlotRange> ranges = buildSlotRanges();

        if (ranges.isEmpty()) {
            return "*0\r\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(ranges.size()).append("\r\n");
        for (SlotRange range : ranges) {
            ClusterNode master = clusterConfig.getNode(range.ownerId);
            if (master == null) {
                continue;
            }

            List<ClusterNode> replicas = new ArrayList<>();
            for (ClusterNode node : clusterConfig.getAllNodes()) {
                if (node.isSlave() && range.ownerId.equals(node.getMasterNodeId())
                        && !node.hasState(ClusterNodeState.HANDSHAKE)
                        && !node.isFail() && !node.isPfail()) {
                    replicas.add(node);
                }
            }

            int entryLen = 3 + replicas.size();
            sb.append("*").append(entryLen).append("\r\n");
            sb.append(":").append(range.start).append("\r\n");
            sb.append(":").append(range.end).append("\r\n");
            appendNodeEndpoint(sb, master);
            for (ClusterNode replica : replicas) {
                appendNodeEndpoint(sb, replica);
            }
        }

        return sb.toString();
    }

    private List<SlotRange> buildSlotRanges() {
        List<SlotRange> ranges = new ArrayList<>();
        String currentOwner = null;
        int rangeStart = -1;

        for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
            String owner = clusterConfig.getSlotOwner(i);
            if (owner == null) {
                if (currentOwner != null) {
                    ranges.add(new SlotRange(rangeStart, i - 1, currentOwner));
                    currentOwner = null;
                    rangeStart = -1;
                }
            } else if (currentOwner == null) {
                currentOwner = owner;
                rangeStart = i;
            } else if (!owner.equals(currentOwner)) {
                ranges.add(new SlotRange(rangeStart, i - 1, currentOwner));
                currentOwner = owner;
                rangeStart = i;
            }
        }
        if (currentOwner != null) {
            ranges.add(new SlotRange(rangeStart, SlotUtils.CLUSTER_SLOTS - 1, currentOwner));
        }
        return ranges;
    }

    private static class SlotRange {
        final int start;
        final int end;
        final String ownerId;

        SlotRange(int start, int end, String ownerId) {
            this.start = start;
            this.end = end;
            this.ownerId = ownerId;
        }
    }

    private void appendNodeEndpoint(StringBuilder sb, ClusterNode node) {
        String ip = node.getIp();
        int port = node.getPort();
        String nodeId = node.getNodeId();
        sb.append("*3\r\n");
        sb.append("$").append(ip.length()).append("\r\n").append(ip).append("\r\n");
        sb.append(":").append(port).append("\r\n");
        sb.append("$").append(nodeId.length()).append("\r\n").append(nodeId).append("\r\n");
    }

    /**
     * 构建节点状态标志字符串
     *
     * @param node 节点
     * @return 状态标志字符串
     */
    /**
     * N-26：解析节点的展示配置纪元——从节点显示其 master 的 configEpoch
     * （对齐 Redis clusterGenNodeDescription：nodeEpoch = slaveof->configEpoch）。
     *
     * @param node 目标节点
     * @return 展示纪元
     */
    private long resolveNodeEpoch(ClusterNode node) {
        if (node.isSlave() && node.getMasterNodeId() != null) {
            ClusterNode master = clusterConfig.getNode(node.getMasterNodeId());
            if (master != null) {
                return master.getConfigEpoch();
            }
        }
        return node.getConfigEpoch();
    }

    private String buildNodeFlags(ClusterNode node) {
        StringBuilder flags = new StringBuilder();

        // N-26：角色互斥——角色切换（performFailover/applySelfDemotion）先改一个状态
        // 再改另一个，并发读取 CLUSTER NODES 可能看到过渡态"master,slave"并存。
        // 过渡态按 masterNodeId 判定最终角色：降级路径先设置 masterNodeId、提升路径先清除。
        boolean master = node.isMaster();
        boolean slave = node.isSlave();
        if (master && slave) {
            if (node.getMasterNodeId() != null) {
                master = false;
            } else {
                slave = false;
            }
        }

        if (node.isMyself()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("myself");
        }

        if (master) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("master");
        }

        if (slave) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("slave");
        }

        // N-26：对齐 Redis redisNodeFlagsTable 顺序（myself, master, slave, fail?, fail,
        // handshake, noaddr）——旧实现 fail 先于 fail?，解析器按序匹配会误读。
        if (node.isPfail()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("fail?");
        }

        if (node.isFail()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("fail");
        }

        if (node.hasState(ClusterNodeState.HANDSHAKE)) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("handshake");
        }

        if (node.hasState(ClusterNodeState.NOADDR)) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("noaddr");
        }

        return flags.length() > 0 ? flags.toString() : "noflags";
    }

    /**
     * 格式化槽位范围
     *
     * @param slots 槽位 BitSet
     * @return 槽位范围字符串
     */
    private String formatSlots(BitSet slots) {
        StringBuilder sb = new StringBuilder();
        int start = -1;
        int end = -1;

        for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
            if (slots.get(i)) {
                if (start < 0) {
                    start = i;
                }
                end = i;
            } else {
                if (start >= 0) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    if (start == end) {
                        sb.append(start);
                    } else {
                        sb.append(start).append("-").append(end);
                    }
                    start = -1;
                    end = -1;
                }
            }
        }

        // 处理最后一个范围
        if (start >= 0) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            if (start == end) {
                sb.append(start);
            } else {
                sb.append(start).append("-").append(end);
            }
        }

        return sb.toString();
    }

    /**
     * CLUSTER MEET ip port 命令
     * 添加节点到集群，发送 MEET 消息
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterMeet(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'cluster|meet' command\r\n";
        }

        String ip = args[1];
        int port;

        try {
            port = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            return "-ERR Invalid port specified\r\n";
        }

        // 验证端口范围
        if (port < 0 || port > 65535) {
            return "-ERR Invalid port specified\r\n";
        }

        // 发送 MEET 消息
        if (gossipProtocol != null) {
            gossipProtocol.sendMeet(ip, port);
            logger.info("CLUSTER MEET: ip={}, port={}", ip, port);
            return "+OK\r\n";
        } else {
            return "-ERR Cluster not initialized\r\n";
        }
    }

    /**
     * CLUSTER FORGET nodeid 命令
     * 从集群移除节点，60秒延迟机制
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterForget(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|forget' command\r\n";
        }

        String nodeId = args[1];

        // 检查节点是否存在
        ClusterNode node = clusterConfig.getNode(nodeId);
        if (node == null) {
            return "-ERR No such node with node-id " + nodeId + "\r\n";
        }

        // 不能移除自己
        if (node.isMyself()) {
            return "-ERR I can't forget about myself!\r\n";
        }

        // 如果是从节点，可以直接移除
        if (node.isSlave()) {
            clusterConfig.removeNode(nodeId);
            clearSlotManagerForNode(nodeId);
            // P1-3：从节点也必须加入黑名单，否则 gossip 会立即重新引入它。
            clusterConfig.blacklistNode(nodeId);
            // N-39：FORGET 后断开总线连接并清除重连端点——否则断线监听器会持续重连
            // 已删除节点（僵尸重连循环，节点永远"杀不死"）。
            disconnectBusForNode(nodeId);
            logger.info("CLUSTER FORGET: removed slave node {} (blacklisted)", nodeId);
            notifyTopologyChanged();
            return "+OK\r\n";
        }

        // 如果是主节点，检查是否还有槽位
        if (node.getSlotCount() > 0) {
            return "-ERR Node " + nodeId + " is not empty! Reshard data away and try again.\r\n";
        }

        // 立即移除节点，并加入黑名单（60s 内阻止其他节点通过 gossip 重新引入该节点）。
        // P1-3：黑名单上移至 ClusterConfig（共享对象），使 Gossip 路径可查询；
        // 原 ClusterCommandHandler.forgetNodes 表保留作兼容，但权威黑名单在 clusterConfig。
        clusterConfig.blacklistNode(nodeId);
        clusterConfig.removeNode(nodeId);
        clearSlotManagerForNode(nodeId);
        // N-39：断开总线连接（同从节点分支，防僵尸重连）
        disconnectBusForNode(nodeId);

        logger.info("CLUSTER FORGET: removed master node {} (blacklisted for 60000ms)", nodeId);
        notifyTopologyChanged();
        return "+OK\r\n";
    }

    /**
     * N-39：断开被 FORGET 节点的总线连接并清除重连状态。
     * <p>
     * ClusterBusClient.disconnect 会先移除重连端点再关通道，使断线监听器
     * （仅当端点仍在时才调度重连）不会把已删除节点复活。
     * </p>
     *
     * @param nodeId 被移除的节点ID
     */
    private void disconnectBusForNode(String nodeId) {
        if (gossipProtocol != null && gossipProtocol.getBusClient() != null) {
            gossipProtocol.getBusClient().disconnect(nodeId);
        }
    }

    /**
     * CLUSTER REPLICATE master-nodeid 命令
     * 配置当前节点为从节点
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterReplicate(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|replicate' command\r\n";
        }

        String masterNodeId = args[1];

        // 检查主节点是否存在
        ClusterNode masterNode = clusterConfig.getNode(masterNodeId);
        if (masterNode == null) {
            return "-ERR Unknown node " + masterNodeId + "\r\n";
        }

        // 检查目标节点是否是主节点
        if (!masterNode.isMaster()) {
            return "-ERR Destination node is not a master\r\n";
        }

        // 获取当前节点
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            return "-ERR Current node not found in cluster\r\n";
        }

        // 检查当前节点是否有槽位
        if (myNode.getSlotCount() > 0) {
            return "-ERR Can't replicate a master that is already holding data\r\n";
        }

        // 设置当前节点为从节点
        myNode.removeState(ClusterNodeState.MASTER);
        myNode.addState(ClusterNodeState.SLAVE);
        myNode.setMasterNodeId(masterNodeId);

        // 增加配置纪元
        clusterConfig.incrementEpoch();
        myNode.setConfigEpoch(clusterConfig.getCurrentEpoch());

        logger.info("CLUSTER REPLICATE: current node is now slave of {}", masterNodeId);
        notifyTopologyChanged();
        // 通知复制生命周期：本节点成为 slave，应停止旧连接并向新 master 发起 PSYNC。
        // 放在 notifyTopologyChanged 之后、返回响应之前，确保拓扑已持久化后再启动复制。
        replicationLifecycleListener.replicateTo(masterNode);
        return "+OK\r\n";
    }

    /**
     * CLUSTER ADDSLOTS slot [slot ...] 命令
     * 分配槽位给当前节点
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterAddslots(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|addslots' command\r\n";
        }

        // 解析槽位参数（使用 Set 去重，避免重复参数导致 addSlots 异常或重复分配）
        java.util.Set<Integer> slotSet = new java.util.LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) {
            try {
                int slot = Integer.parseInt(args[i]);
                SlotUtils.validateSlot(slot);
                slotSet.add(slot);
            } catch (NumberFormatException e) {
                return "-ERR Invalid or out of range slot\r\n";
            } catch (IllegalArgumentException e) {
                return "-ERR " + e.getMessage() + "\r\n";
            }
        }

        return assignSlots(slotSet);
    }

    /**
     * CLUSTER ADDSLOTSRANGE start end [start end ...] 命令（N-19）
     * 按闭区间批量分配槽位，语义与 ADDSLOTS 相同。
     *
     * @param args 命令参数，成对的 start/end
     * @return 响应
     */
    private String clusterAddslotsRange(String[] args) {
        // 参数至少为 start end 一对（args[0]=ADDSLOTSRANGE），即 args.length >= 3 且为奇数
        if (args.length < 3 || (args.length - 1) % 2 != 0) {
            return "-ERR wrong number of arguments for 'cluster|addslotsrange' command\r\n";
        }

        java.util.Set<Integer> slotSet = new java.util.LinkedHashSet<>();
        for (int i = 1; i < args.length; i += 2) {
            int start;
            int end;
            try {
                start = Integer.parseInt(args[i]);
                end = Integer.parseInt(args[i + 1]);
                SlotUtils.validateSlot(start);
                SlotUtils.validateSlot(end);
            } catch (NumberFormatException e) {
                return "-ERR Invalid or out of range slot\r\n";
            } catch (IllegalArgumentException e) {
                return "-ERR " + e.getMessage() + "\r\n";
            }
            if (start > end) {
                return "-ERR Invalid or out of range slot\r\n";
            }
            for (int slot = start; slot <= end; slot++) {
                slotSet.add(slot);
            }
        }

        return assignSlots(slotSet);
    }

    /**
     * 槽位分配公共逻辑（ADDSLOTS / ADDSLOTSRANGE 共用）。
     *
     * @param slotSet 去重后的槽位集合
     * @return 响应
     */
    private String assignSlots(java.util.Set<Integer> slotSet) {
        int[] slots = slotSet.stream().mapToInt(Integer::intValue).toArray();

        // 检查槽位是否已分配
        for (int slot : slots) {
            String owner = slotManager.getSlotOwner(slot);
            if (owner != null) {
                return "-ERR Slot " + slot + " is already busy\r\n";
            }
        }

        // 分配槽位
        try {
            slotManager.addSlots(slots);

            // 更新当前节点的槽位信息
            ClusterNode myNode = clusterConfig.getMyNode();
            if (myNode != null) {
                for (int slot : slots) {
                    myNode.addSlot(slot);
                    // 同步更新 ClusterConfig 的槽位分配表
                    clusterConfig.setSlotOwner(slot, myNode.getNodeId());
                }
                // 设置为主节点
                myNode.removeState(ClusterNodeState.SLAVE);
                myNode.addState(ClusterNodeState.MASTER);
            }

            // 增加配置纪元，并同步设置当前节点的配置纪元，
            // 与 clusterReplicate / 故障转移路径保持一致，
            // 确保基于纪元的槽位/角色冲突裁决可靠（ADDSLOTS 后 configEpoch 不应为 0）
            clusterConfig.incrementEpoch();
            myNode.setConfigEpoch(clusterConfig.getCurrentEpoch());

            // 更新集群状态（槽位分配可能使集群变为健康）
            stateManager.updateClusterState();

            logger.info("CLUSTER ADDSLOTS: added {} slots", slots.length);
            notifyTopologyChanged();
            return "+OK\r\n";
        } catch (Exception e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    /**
     * CLUSTER DELSLOTS slot [slot ...] 命令
     * 移除槽位
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterDelslots(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|delslots' command\r\n";
        }

        // 解析槽位参数
        int[] slots = new int[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            try {
                int slot = Integer.parseInt(args[i]);
                SlotUtils.validateSlot(slot);
                slots[i - 1] = slot;
            } catch (NumberFormatException e) {
                return "-ERR Invalid or out of range slot\r\n";
            } catch (IllegalArgumentException e) {
                return "-ERR " + e.getMessage() + "\r\n";
            }
        }

        // 检查槽位是否由当前节点负责
        for (int slot : slots) {
            if (!slotManager.isSlotLocal(slot)) {
                return "-ERR Slot " + slot + " is not my slot\r\n";
            }
        }

        // 移除槽位
        return removeSlots(slots);
    }

    /**
     * CLUSTER DELSLOTSRANGE start end [start end ...] 命令（N-19）
     * 按闭区间批量移除槽位，语义与 DELSLOTS 相同。
     *
     * @param args 命令参数，成对的 start/end
     * @return 响应
     */
    private String clusterDelslotsRange(String[] args) {
        // 参数至少为 start end 一对（args[0]=DELSLOTSRANGE），即 args.length >= 3 且为奇数
        if (args.length < 3 || (args.length - 1) % 2 != 0) {
            return "-ERR wrong number of arguments for 'cluster|delslotsrange' command\r\n";
        }

        java.util.Set<Integer> slotSet = new java.util.LinkedHashSet<>();
        for (int i = 1; i < args.length; i += 2) {
            int start;
            int end;
            try {
                start = Integer.parseInt(args[i]);
                end = Integer.parseInt(args[i + 1]);
                SlotUtils.validateSlot(start);
                SlotUtils.validateSlot(end);
            } catch (NumberFormatException e) {
                return "-ERR Invalid or out of range slot\r\n";
            } catch (IllegalArgumentException e) {
                return "-ERR " + e.getMessage() + "\r\n";
            }
            if (start > end) {
                return "-ERR Invalid or out of range slot\r\n";
            }
            for (int slot = start; slot <= end; slot++) {
                slotSet.add(slot);
            }
        }

        int[] slots = slotSet.stream().mapToInt(Integer::intValue).toArray();
        return removeSlots(slots);
    }

    /**
     * 槽位移除公共逻辑（DELSLOTS / DELSLOTSRANGE 共用）。
     *
     * @param slots 去重后的槽位数组
     * @return 响应
     */
    private String removeSlots(int[] slots) {
        // 检查槽位是否由当前节点负责
        for (int slot : slots) {
            if (!slotManager.isSlotLocal(slot)) {
                return "-ERR Slot " + slot + " is not my slot\r\n";
            }
        }

        // 移除槽位
        try {
            slotManager.delSlots(slots);

            // 更新当前节点的槽位信息
            ClusterNode myNode = clusterConfig.getMyNode();
            if (myNode != null) {
                for (int slot : slots) {
                    myNode.removeSlot(slot);
                    // 同步更新 ClusterConfig 的槽位分配表
                    clusterConfig.clearSlot(slot);
                }
            }

            // 增加配置纪元
            clusterConfig.incrementEpoch();

            // 更新集群状态（移除槽位可能使集群状态变化）
            stateManager.updateClusterState();

            logger.info("CLUSTER DELSLOTS: removed {} slots", slots.length);
            notifyTopologyChanged();
            return "+OK\r\n";
        } catch (Exception e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    /**
     * CLUSTER SETSLOT slot IMPORTING|MIGRATING|STABLE|NODE nodeid 命令
     * 设置槽位状态
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterSetslot(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'cluster|setslot' command\r\n";
        }

        // 解析槽位
        int slot;
        try {
            slot = Integer.parseInt(args[1]);
            SlotUtils.validateSlot(slot);
        } catch (NumberFormatException e) {
            return "-ERR Invalid or out of range slot\r\n";
        } catch (IllegalArgumentException e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }

        String subcommand = args[2].toUpperCase();

        switch (subcommand) {
            case "IMPORTING":
                // 设置槽位为导入状态
                if (args.length < 4) {
                    return "-ERR wrong number of arguments for 'cluster|setslot|importing' command\r\n";
                }
                String sourceNodeId = args[3];
                if (clusterConfig.getNode(sourceNodeId) == null) {
                    return "-ERR Unknown node " + sourceNodeId + "\r\n";
                }
                slotMigrationState.put(slot, "IMPORTING");
                slotMigrationTarget.put(slot, sourceNodeId);
                // 同步更新 SlotManager 的导入状态
                slotManager.setSlotImporting(slot, sourceNodeId);
                // N-29：同步到 ClusterConfig，使 nodes.conf 持久化迁移方括号
                clusterConfig.setSlotImporting(slot, sourceNodeId);
                logger.info("CLUSTER SETSLOT: slot {} set to IMPORTING from {}", slot, sourceNodeId);
                return "+OK\r\n";

            case "MIGRATING":
                // 设置槽位为迁移状态
                if (args.length < 4) {
                    return "-ERR wrong number of arguments for 'cluster|setslot|migrating' command\r\n";
                }
                String targetNodeId = args[3];
                if (clusterConfig.getNode(targetNodeId) == null) {
                    return "-ERR Unknown node " + targetNodeId + "\r\n";
                }
                // 检查槽位是否由当前节点负责
                if (!slotManager.isSlotLocal(slot)) {
                    return "-ERR I'm not the owner of hash slot " + slot + "\r\n";
                }
                slotMigrationState.put(slot, "MIGRATING");
                slotMigrationTarget.put(slot, targetNodeId);
                // 同步更新 SlotManager 的迁移状态
                slotManager.setSlotMigrating(slot, targetNodeId);
                // N-29：同步到 ClusterConfig，使 nodes.conf 持久化迁移方括号
                clusterConfig.setSlotMigrating(slot, targetNodeId);
                logger.info("CLUSTER SETSLOT: slot {} set to MIGRATING to {}", slot, targetNodeId);
                return "+OK\r\n";

            case "STABLE":
                // 清除槽位迁移状态
                slotMigrationState.remove(slot);
                slotMigrationTarget.remove(slot);
                // 同步清除 SlotManager 的迁移/导入状态
                slotManager.setSlotImporting(slot, null);
                slotManager.setSlotMigrating(slot, null);
                // N-29：同步清除 ClusterConfig 迁移状态（nodes.conf 方括号不再输出）
                clusterConfig.setSlotImporting(slot, null);
                clusterConfig.setSlotMigrating(slot, null);
                logger.info("CLUSTER SETSLOT: slot {} set to STABLE", slot);
                return "+OK\r\n";

            case "NODE":
                // 设置槽位所属节点
                if (args.length < 4) {
                    return "-ERR wrong number of arguments for 'cluster|setslot|node' command\r\n";
                }
                String nodeId = args[3];
                if (clusterConfig.getNode(nodeId) == null) {
                    return "-ERR Unknown node " + nodeId + "\r\n";
                }

                // 更新槽位分配
                slotManager.setSlotOwner(slot, nodeId);

                // 更新节点的槽位信息
                ClusterNode targetNode = clusterConfig.getNode(nodeId);
                if (targetNode != null) {
                    targetNode.addSlot(slot);
                }

                // 同步更新 ClusterConfig 的槽位分配表
                clusterConfig.setSlotOwner(slot, nodeId);

                // 清除迁移状态
                slotMigrationState.remove(slot);
                slotMigrationTarget.remove(slot);

                // 清除迁移/导入状态（对齐 Redis clusterCommand SETSLOT NODE：接管槽位时
                // 清除该槽位的 migrating/importing 状态）。否则迁移完成 SETSLOT NODE 后
                // 状态残留：目标节点 importing 残留 → 该槽位请求无 ASKING 时 ASK 回源，
                // 源节点 migrating 残留 → ASK 回目标，形成迁移完成后的永久互指循环。
                slotManager.setSlotImporting(slot, null);
                slotManager.setSlotMigrating(slot, null);
                // N-29：同步清除 ClusterConfig 迁移状态
                clusterConfig.setSlotImporting(slot, null);
                clusterConfig.setSlotMigrating(slot, null);

                // 增加配置纪元，并提升新 owner 的 per-node configEpoch（P1-2A）。
                // 不提升则 Gossip 经 syncSlotsFromNode 的 epoch 仲裁会因新 owner 纪元偏低
                // 而拒绝槽位变更，第三节点槽位归属永不收敛 → 客户端双跳 MOVED。
                // 对齐 clusterAddslots/clusterReplicate/performFailoverLocally 的既有模式。
                clusterConfig.incrementEpoch();
                if (targetNode != null) {
                    targetNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
                }

                logger.info("CLUSTER SETSLOT: slot {} assigned to node {} (configEpoch={})",
                        slot, nodeId, clusterConfig.getCurrentEpoch());
                notifyTopologyChanged();
                return "+OK\r\n";

            default:
                return "-ERR Unknown subcommand for SETSLOT: " + subcommand + "\r\n";
        }
    }

    /**
     * CLUSTER KEYSLOT key 命令
     * 计算键的槽位
     *
     * @param args 命令参数
     * @return 槽位号
     */
    private String clusterKeyslot(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|keyslot' command\r\n";
        }

        String key = args[1];
        int slot = SlotUtils.keyHashSlot(key);

        return ":" + slot + "\r\n";
    }

    /**
     * CLUSTER GETKEYSINSLOT slot count 命令
     * 获取槽位中的键
     * 注意：此方法需要访问实际的数据存储，这里返回空列表
     *
     * @param args 命令参数
     * @return 键列表
     */
    private String clusterGetkeysinslot(String[] args) {
        if (args.length < 3) {
            return "-ERR wrong number of arguments for 'cluster|getkeysinslot' command\r\n";
        }

        int slot;
        int count;

        try {
            slot = Integer.parseInt(args[1]);
            SlotUtils.validateSlot(slot);
        } catch (NumberFormatException e) {
            return "-ERR Invalid or out of range slot\r\n";
        } catch (IllegalArgumentException e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }

        try {
            count = Integer.parseInt(args[2]);
            if (count < 0) {
                return "-ERR Count must be positive\r\n";
            }
        } catch (NumberFormatException e) {
            return "-ERR Invalid count: " + args[2] + "\r\n";
        }

        // 仅当本节点负责该槽位时才返回键，否则返回空数组（对齐 Redis 行为）
        List<String> keys = Collections.emptyList();
        if (memoryStore != null && slotManager.isSlotLocal(slot)) {
            List<String> result = memoryStore.getKeysInSlot(0, slot, count);
            if (result != null) {
                keys = result;
            }
        }

        logger.debug("CLUSTER GETKEYSINSLOT: slot={}, count={}, returned={}", slot, count, keys.size());
        return formatBulkArray(keys);
    }

    /**
     * CLUSTER COUNTKEYSINSLOT slot 命令
     * 统计槽位中的键数量
     * 注意：此方法需要访问实际的数据存储，这里返回0
     *
     * @param args 命令参数
     * @return 键数量
     */
    private String clusterCountkeysinslot(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|countkeysinslot' command\r\n";
        }

        int slot;
        try {
            slot = Integer.parseInt(args[1]);
            SlotUtils.validateSlot(slot);
        } catch (NumberFormatException e) {
            return "-ERR Invalid or out of range slot\r\n";
        } catch (IllegalArgumentException e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }

        // 仅当本节点负责该槽位时才统计，否则返回 0（对齐 Redis 行为）
        int count = 0;
        if (memoryStore != null && slotManager.isSlotLocal(slot)) {
            count = memoryStore.countKeysInSlot(0, slot);
        }

        logger.debug("CLUSTER COUNTKEYSINSLOT: slot={}, count={}", slot, count);
        return ":" + count + "\r\n";
    }

    /**
     * 将字符串列表格式化为 RESP bulk array 响应
     *
     * @param keys 键列表
     * @return RESP 格式的 bulk array 字符串
     */
    private String formatBulkArray(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "*0\r\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(keys.size()).append("\r\n");
        for (String key : keys) {
            if (key == null) {
                sb.append("$-1\r\n");
            } else {
                byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
                sb.append("$").append(keyBytes.length).append("\r\n");
                sb.append(key).append("\r\n");
            }
        }
        return sb.toString();
    }

    /**
     * CLUSTER SLAVES nodeid 命令
     * 获取主节点的从节点列表
     *
     * @param args 命令参数
     * @return 从节点列表
     */
    private String clusterSlaves(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|slaves' command\r\n";
        }

        String masterNodeId = args[1];

        // 检查主节点是否存在
        ClusterNode masterNode = clusterConfig.getNode(masterNodeId);
        if (masterNode == null) {
            return "-ERR Unknown node " + masterNodeId + "\r\n";
        }

        // 检查是否是主节点
        if (!masterNode.isMaster()) {
            return "-ERR The specified node is not a master\r\n";
        }

        // 查找所有从节点
        List<ClusterNode> slaves = new ArrayList<>();
        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.isSlave() && masterNodeId.equals(node.getMasterNodeId())) {
                slaves.add(node);
            }
        }

        // 构建响应
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(slaves.size()).append("\r\n");

        for (ClusterNode slave : slaves) {
            String nodeInfo = formatNodeInfo(slave);
            sb.append("$").append(nodeInfo.length()).append("\r\n");
            sb.append(nodeInfo).append("\r\n");
        }

        return sb.toString();
    }

    /**
     * 格式化节点信息
     *
     * @param node 节点
     * @return 节点信息字符串
     */
    private String formatNodeInfo(ClusterNode node) {
        StringBuilder sb = new StringBuilder();

        // 节点ID
        sb.append(node.getNodeId());

        // 地址信息
        sb.append(" ");
        sb.append(node.getFullAddress());

        // 状态标志
        sb.append(" ");
        sb.append(buildNodeFlags(node));

        // 主节点ID
        sb.append(" ");
        if (node.getMasterNodeId() != null) {
            sb.append(node.getMasterNodeId());
        } else {
            sb.append("-");
        }

        // ping 发送时间
        sb.append(" ");
        sb.append(node.getLastPingTime());

        // pong 接收时间
        sb.append(" ");
        sb.append(node.getLastPongTime());

        // 配置纪元
        sb.append(" ");
        sb.append(node.getConfigEpoch());

        // 连接状态
        sb.append(" ");
        ClusterLink link = node.getLink();
        if (link != null && link.isConnected()) {
            sb.append("connected");
        } else {
            sb.append("disconnected");
        }

        return sb.toString();
    }

    /**
     * CLUSTER FAILOVER [FORCE|TAKEOVER] 命令
     * 手动故障转移
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterFailover(String[] args) {
        // 获取当前节点
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            return "-ERR Current node not found in cluster\r\n";
        }

        // 检查当前节点是否是从节点
        if (!myNode.isSlave()) {
            return "-ERR You should send CLUSTER FAILOVER to a slave\r\n";
        }

        // 获取主节点
        String masterNodeId = myNode.getMasterNodeId();
        if (masterNodeId == null) {
            return "-ERR I'm a slave but my master is unknown to me\r\n";
        }

        ClusterNode masterNode = clusterConfig.getNode(masterNodeId);
        if (masterNode == null) {
            return "-ERR Master node not found\r\n";
        }

        // 解析选项：FORCE / TAKEOVER（对齐 Redis clusterCommand failover，P1-12）
        boolean force = false;
        boolean takeover = false;

        if (args.length > 1) {
            String option = args[1].toUpperCase();
            if ("FORCE".equals(option)) {
                force = true;
            } else if ("TAKEOVER".equals(option)) {
                takeover = true;
            } else if ("TO".equals(option) && args.length > 2) {
                // 兼容 "FAILOVER TO <nodeId> <port>"（Redis 子命令，本项目简化为忽略目标）
                // 不在此实现完整 TO 语义，仅吞掉参数避免 syntax error
            } else {
                return "-ERR syntax error\r\n";
            }
        }

        // 执行故障转移
        try {
            FailoverManager failoverManager =
                    gossipProtocol != null ? gossipProtocol.getFailoverManager() : null;

            if (takeover) {
                // TAKEOVER 模式：不询问任何人、不需 master 在线、不追平 offset，直接接管。
                // 仅自增 epoch + 广播 FailoverResult 使全网收敛（对齐 Redis CLUSTER FAILOVER TAKEOVER）。
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER TAKEOVER: slave {} promoted to master (no consensus)",
                        myNode.getNodeId());
                return "+OK\r\n";
            }

            if (force) {
                // FORCE 模式：跳过 master 健康检查，但 master 必须已知。
                // 对齐 Redis CLUSTER FAILOVER FORCE：不发 MFSTART 握手，直接提升
                // （允许 master 不可达但尚未 FAIL 时强制接管）。
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER FORCE: slave {} promoted to master (skip health check)",
                        myNode.getNodeId());
                return "+OK\r\n";
            }

            // 普通模式（P1-12 完整实现）：要求 master 在线且健康，
            // 经 MFSTART 握手让 master 暂停写、回传 offset，slave 追平后提升（异步）。
            if (masterNode.isFail() || masterNode.isPfail()) {
                // master 已 FAIL/PFAIL 时普通模式不可用，提示用 FORCE/TAKEOVER
                return "-MASTERDOWN Master is down, use FAILOVER FORCE or TAKEOVER to proceed\r\n";
            }

            if (failoverManager != null) {
                // 启动异步状态机（MF_REQUESTED → WAITING_OFFSET → READY），立即返回 +OK
                failoverManager.startManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER: slave {} initiated (normal mode, waiting offset catchup)",
                        myNode.getNodeId());
            } else {
                // FailoverManager 未注入（单测降级）：直接同步提升，保持向后兼容
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER: slave {} promoted to master (degraded, no FailoverManager)",
                        myNode.getNodeId());
            }
            return "+OK\r\n";
        } catch (Exception e) {
            logger.error("CLUSTER FAILOVER failed", e);
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    /**
     * 执行手动故障转移，委托给 FailoverManager（performFailover 已抽取到那里）。
     * 若 FailoverManager 未注入（如单元测试场景传入 null gossipProtocol），
     * 降级为本地执行以保持向后兼容。
     *
     * @param slaveNode  当前 slave 节点
     * @param masterNode 原 master 节点
     */
    private void performManualFailover(ClusterNode slaveNode, ClusterNode masterNode) {
        if (gossipProtocol != null && gossipProtocol.getFailoverManager() != null) {
            gossipProtocol.getFailoverManager().performManualFailover(slaveNode, masterNode);
            return;
        }
        // 降级：本地执行（保留原 performFailover 行为，含 epoch 自增）
        performFailoverLocally(slaveNode, masterNode);
        notifyTopologyChanged();
    }

    /**
     * 本地执行故障转移（FailoverManager 未注入时的降级路径，保留原 performFailover 行为）。
     */
    private void performFailoverLocally(ClusterNode slaveNode, ClusterNode masterNode) {
        slaveNode.removeState(ClusterNodeState.SLAVE);
        slaveNode.addState(ClusterNodeState.MASTER);
        slaveNode.setMasterNodeId(null);

        BitSet masterSlots = masterNode.getSlots();
        for (int i = masterSlots.nextSetBit(0); i >= 0; i = masterSlots.nextSetBit(i + 1)) {
            slaveNode.addSlot(i);
            slotManager.setSlotOwner(i, slaveNode.getNodeId());
            clusterConfig.setSlotOwner(i, slaveNode.getNodeId());
        }

        masterNode.clearSlots();
        masterNode.removeState(ClusterNodeState.MASTER);
        masterNode.addState(ClusterNodeState.SLAVE);
        masterNode.setMasterNodeId(slaveNode.getNodeId());

        clusterConfig.incrementEpoch();
        slaveNode.setConfigEpoch(clusterConfig.getCurrentEpoch());
        stateManager.updateClusterState();
    }

    /**
     * CLUSTER MYID 命令
     * 返回当前节点ID
     *
     * @return 节点ID
     */
    private String clusterMyid() {
        String myNodeId = clusterConfig.getMyNodeId();
        if (myNodeId == null) {
            return "-ERR Node ID not set\r\n";
        }
        return "$" + myNodeId.length() + "\r\n" + myNodeId + "\r\n";
    }

    /**
     * CLUSTER FLUSHSLOTS 命令
     * 清空当前节点的所有槽位
     *
     * @return 响应
     */
    private String clusterFlushslots() {
        // 先获取当前节点的槽位副本，用于同步清理 ClusterConfig
        ClusterNode myNode = clusterConfig.getMyNode();
        BitSet mySlots = (myNode != null) ? (BitSet) myNode.getSlots().clone() : new BitSet();

        // 清空槽位管理器中的槽位
        slotManager.clearMySlots();

        // 清空当前节点的槽位信息
        if (myNode != null) {
            myNode.clearSlots();
        }

        // 同步更新 ClusterConfig 的槽位分配表
        String myNodeId = clusterConfig.getMyNodeId();
        for (int i = mySlots.nextSetBit(0); i >= 0; i = mySlots.nextSetBit(i + 1)) {
            clusterConfig.clearSlot(i);
        }

        // 更新集群状态（清空槽位可能使集群状态变化）
        stateManager.updateClusterState();

        logger.info("CLUSTER FLUSHSLOTS: all slots cleared");
        notifyTopologyChanged();
        return "+OK\r\n";
    }

    /**
     * CLUSTER SET-CONFIG-EPOCH <epoch> 命令
     * <p>
     * 设置当前节点的配置纪元，并把集群 currentEpoch 提升到至少该值。
     * 用于 redis-cli --cluster create 在节点加入集群前逐节点建立初始配置纪元。
     * </p>
     *
     * @param args 命令参数，args[1] 为目标纪元
     * @return 响应
     */
    private String clusterSetConfigEpoch(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|set-config-epoch' command\r\n";
        }

        long epoch;
        try {
            epoch = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            return "-ERR Invalid config epoch\r\n";
        }
        if (epoch < 0) {
            return "-ERR Invalid config epoch\r\n";
        }

        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode == null) {
            return "-ERR Current node not found in cluster\r\n";
        }

        myNode.setConfigEpoch(epoch);
        clusterConfig.setEpochIfGreater(epoch);

        logger.info("CLUSTER SET-CONFIG-EPOCH: epoch={}", epoch);
        notifyTopologyChanged();
        return "+OK\r\n";
    }

    /**
     * CLUSTER BUMPEPOCH 命令
     * 增加配置纪元
     *
     * @return 新的配置纪元
     */
    private String clusterBumpepoch() {
        long newEpoch = clusterConfig.incrementEpoch();

        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode != null) {
            myNode.setConfigEpoch(newEpoch);
        }

        logger.info("CLUSTER BUMPEPOCH: new epoch = {}", newEpoch);
        notifyTopologyChanged();
        return ":" + newEpoch + "\r\n";
    }

    /**
     * CLUSTER SHARDS 命令（N-19）
     * 返回槽位分片信息（Redis 7 引入，redis-cli --cluster 与现代客户端使用）。
     * RESP2 格式：每个 shard 条目为 [slots, nodes]，
     * slots 为 [start, end] 整数对，nodes 为节点 map 列表
     * （id/port/ip/endpoint/role/replication-offset/health），master 在前。
     *
     * @return RESP 格式的分片信息
     */
    private String clusterShards() {
        List<SlotRange> ranges = buildSlotRanges();

        if (ranges.isEmpty()) {
            return "*0\r\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("*").append(ranges.size()).append("\r\n");
        for (SlotRange range : ranges) {
            ClusterNode master = clusterConfig.getNode(range.ownerId);
            if (master == null) {
                continue;
            }

            List<ClusterNode> replicas = new ArrayList<>();
            for (ClusterNode node : clusterConfig.getAllNodes()) {
                if (node.isSlave() && range.ownerId.equals(node.getMasterNodeId())
                        && !node.hasState(ClusterNodeState.HANDSHAKE)
                        && !node.isFail() && !node.isPfail()) {
                    replicas.add(node);
                }
            }

            // 每个 shard 条目：[slots, nodes]
            sb.append("*2\r\n");
            // slots: [start, end]
            sb.append("*2\r\n");
            sb.append(":").append(range.start).append("\r\n");
            sb.append(":").append(range.end).append("\r\n");
            // nodes: master 在前，replicas 在后
            sb.append("*").append(1 + replicas.size()).append("\r\n");
            appendShardNode(sb, master, "master");
            for (ClusterNode replica : replicas) {
                appendShardNode(sb, replica, "slave");
            }
        }

        return sb.toString();
    }

    /**
     * 追加 SHARDS 节点条目（RESP2 中 map 展开为 7 组 key-value 共 14 个元素）。
     */
    private void appendShardNode(StringBuilder sb, ClusterNode node, String role) {
        sb.append("*14\r\n");
        appendRespPair(sb, "id", node.getNodeId());
        appendRespPair(sb, "port", String.valueOf(node.getPort()));
        appendRespPair(sb, "ip", node.getIp());
        appendRespPair(sb, "endpoint", node.getIp());
        appendRespPair(sb, "role", role);
        appendRespPair(sb, "replication-offset", "0");
        String health = node.isFail() ? "fail" : (node.isPfail() ? "fail?" : "ok");
        appendRespPair(sb, "health", health);
    }

    /**
     * 追加一组 RESP bulk string 键值对。
     */
    private void appendRespPair(StringBuilder sb, String key, String value) {
        sb.append("$").append(key.length()).append("\r\n").append(key).append("\r\n");
        sb.append("$").append(value.length()).append("\r\n").append(value).append("\r\n");
    }

    /**
     * CLUSTER RESET [HARD|SOFT] 命令（N-19）
     * 重置节点为未配置状态，对齐 Redis clusterCommand reset / clusterReset：
     * <ul>
     * <li>SOFT（默认）：清空全部槽位与迁移状态，本节点变为无槽 master，epoch 归零</li>
     * <li>HARD：在 SOFT 基础上移除所有其他节点，并生成新的节点 ID</li>
     * </ul>
     * 与 Redis 一致：master 且持有槽位时拒绝执行。
     *
     * @param args 命令参数
     * @return 响应
     */
    private String clusterReset(String[] args) {
        boolean hard;
        if (args.length == 1) {
            hard = false;
        } else if (args.length == 2 && "HARD".equalsIgnoreCase(args[1])) {
            hard = true;
        } else if (args.length == 2 && "SOFT".equalsIgnoreCase(args[1])) {
            hard = false;
        } else {
            return "-ERR CLUSTER RESET [HARD|SOFT]\r\n";
        }

        ClusterNode myNode = clusterConfig.getMyNode();
        // 对齐 Redis：master 且持有槽位（即可能含键）时拒绝
        if (myNode != null && myNode.isMaster() && myNode.getSlotCount() > 0) {
            return "-ERR CLUSTER RESET can't be called with master nodes containing keys\r\n";
        }

        // 清空全部槽位（slotManager + clusterConfig + 本节点）
        for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
            clusterConfig.clearSlot(i);
            slotManager.setSlotOwner(i, null);
            slotManager.setSlotImporting(i, null);
            slotManager.setSlotMigrating(i, null);
            clusterConfig.setSlotImporting(i, null);
            clusterConfig.setSlotMigrating(i, null);
        }
        slotManager.clearMySlots();
        slotMigrationState.clear();
        slotMigrationTarget.clear();
        if (myNode != null) {
            myNode.clearSlots();
        }

        if (hard) {
            // 移除所有其他节点
            for (ClusterNode node : new ArrayList<>(clusterConfig.getAllNodes())) {
                if (myNode != null && node.getNodeId().equals(myNode.getNodeId())) {
                    continue;
                }
                clusterConfig.removeNode(node.getNodeId());
            }
            // 生成新节点 ID 并重建 myself 节点（保留监听地址）
            String newId = ClusterConfigPersister.generateNodeId();
            if (myNode != null) {
                clusterConfig.removeNode(myNode.getNodeId());
            }
            ClusterNode newNode = new ClusterNode(newId);
            if (myNode != null) {
                newNode.setIp(myNode.getIp());
                newNode.setPort(myNode.getPort());
                newNode.setBusPort(myNode.getBusPort());
            }
            newNode.addState(ClusterNodeState.MYSELF);
            newNode.addState(ClusterNodeState.MASTER);
            clusterConfig.addNode(newNode);
            clusterConfig.setMyNodeId(newId);
            myNode = newNode;
        }

        // 重置本节点为无槽 master、纪元归零（对齐 clusterReset：configEpoch/currentEpoch/lastVoteEpoch 全清零）
        if (myNode != null) {
            myNode.removeState(ClusterNodeState.SLAVE);
            myNode.addState(ClusterNodeState.MASTER);
            myNode.setMasterNodeId(null);
            myNode.setConfigEpoch(0);
        }
        clusterConfig.setCurrentEpoch(0);
        clusterConfig.setLastVoteEpoch(0);

        stateManager.updateClusterState();
        logger.info("CLUSTER RESET: {} reset completed, new nodeId={}", hard ? "HARD" : "SOFT",
                clusterConfig.getMyNodeId());
        notifyTopologyChanged();
        return "+OK\r\n";
    }

    /**
     * CLUSTER COUNT-FAILURE-REPORTS nodeid 命令（N-19）
     * 返回针对指定节点的 PFAIL 投票（failure report）数量。
     *
     * @param args 命令参数
     * @return 失败报告数量
     */
    private String clusterCountFailureReports(String[] args) {
        if (args.length < 2) {
            return "-ERR wrong number of arguments for 'cluster|count-failure-reports' command\r\n";
        }

        String nodeId = args[1];
        if (clusterConfig.getNode(nodeId) == null) {
            return "-ERR Unknown node " + nodeId + "\r\n";
        }

        int count = 0;
        if (gossipProtocol != null && gossipProtocol.getFailureDetector() != null) {
            count = gossipProtocol.getFailureDetector().getPfailVoteCount(nodeId);
        }
        return ":" + count + "\r\n";
    }

    /**
     * CLUSTER REFRESH 命令（N-19）
     * 从 nodes.conf 重新加载集群配置（对齐 Redis 7 CLUSTER REFRESH：
     * 重新读取磁盘上的集群配置文件并应用）。
     * <p>
     * 磁盘配置为权威：槽位归属、currentEpoch/lastVoteEpoch 以磁盘为准，
     * 磁盘中新增的节点并入节点列表；运行时状态（FAIL/PFAIL、连接等）不受影响。
     * </p>
     *
     * @return 响应
     */
    private String clusterRefresh() {
        if (clusterConfigFilePath == null || clusterConfigFilePath.isEmpty()) {
            return "-ERR cluster-config-file not configured\r\n";
        }
        try {
            ClusterConfigPersister persister = new ClusterConfigPersister();
            ClusterConfig diskConfig = persister.load(clusterConfigFilePath);
            if (diskConfig.getMyNodeId() == null) {
                return "-ERR Invalid cluster config file\r\n";
            }

            // 应用纪元（只升不降，避免重载陈旧文件回退运行时已抬高的 epoch）
            clusterConfig.setEpochIfGreater(diskConfig.getCurrentEpoch());
            if (diskConfig.getLastVoteEpoch() > clusterConfig.getLastVoteEpoch()) {
                clusterConfig.setLastVoteEpoch(diskConfig.getLastVoteEpoch());
            }

            // 槽位归属以磁盘为权威重建（含槽位清空）
            for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
                String owner = diskConfig.getSlotOwner(i);
                if (owner != null) {
                    clusterConfig.setSlotOwner(i, owner);
                    ClusterNode node = clusterConfig.getNode(owner);
                    if (node != null) {
                        node.addSlot(i);
                    }
                } else {
                    clusterConfig.clearSlot(i);
                }
            }

            // 并入磁盘有而内存无的节点
            for (ClusterNode diskNode : diskConfig.getAllNodes()) {
                if (clusterConfig.getNode(diskNode.getNodeId()) == null) {
                    clusterConfig.addNode(diskNode);
                }
            }

            // 同步 slotManager 本节点槽位
            slotManager.clearMySlots();
            ClusterNode myNode = clusterConfig.getMyNode();
            if (myNode != null) {
                String myId = myNode.getNodeId();
                for (int i = myNode.getSlots().nextSetBit(0); i >= 0; i = myNode.getSlots().nextSetBit(i + 1)) {
                    slotManager.setSlotOwner(i, myId);
                }
            }

            stateManager.updateClusterState();
            logger.info("CLUSTER REFRESH: reloaded cluster config from {}", clusterConfigFilePath);
            return "+OK\r\n";
        } catch (IOException e) {
            logger.error("CLUSTER REFRESH: failed to reload config", e);
            return "-ERR " + e.getMessage() + "\r\n";
        }
    }

    /**
     * CLUSTER HELP 命令（N-19）
     * 返回支持的全部子命令用法说明（对齐 Redis CLUSTER HELP 的数组响应格式）。
     *
     * @return RESP 格式的帮助信息
     */
    private String clusterHelp() {
        String[] helpLines = {
                "CLUSTER <subcommand> [<arg> [value] [opt] ...]. Subcommands are:",
                "ADDSLOTS <slot> [<slot> ...]",
                "ADDSLOTSRANGE <start> <end> [<start> <end> ...]",
                "BUMPEPOCH",
                "COUNT-FAILURE-REPORTS <node-id>",
                "COUNTKEYSINSLOT <slot>",
                "DELSLOTS <slot> [<slot> ...]",
                "DELSLOTSRANGE <start> <end> [<start> <end> ...]",
                "FAILOVER [FORCE|TAKEOVER]",
                "FLUSHSLOTS",
                "FORGET <node-id>",
                "GETKEYSINSLOT <slot> <count>",
                "HELP",
                "INFO",
                "KEYSLOT <key>",
                "LINKS",
                "MEET <ip> <port>",
                "MYID",
                "NODES",
                "REFRESH",
                "REPLICAS <node-id>",
                "REPLICATE <node-id>",
                "RESET [HARD|SOFT]",
                "SAVECONFIG",
                "SET-CONFIG-EPOCH <epoch>",
                "SETSLOT <slot> (IMPORTING <node-id> | MIGRATING <node-id> | NODE <node-id> | STABLE)",
                "SHARDS",
                "SLAVES <node-id>",
                "SLOTS"
        };
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(helpLines.length).append("\r\n");
        for (String line : helpLines) {
            sb.append("$").append(line.length()).append("\r\n").append(line).append("\r\n");
        }
        return sb.toString();
    }

    /**
     * CLUSTER SAVECONFIG 命令
     * 保存集群配置到 nodes.conf 文件
     *
     * @return 响应
     */
    private String clusterSaveconfig() {
        if (clusterConfigFilePath == null || clusterConfigFilePath.isEmpty()) {
            logger.warn("CLUSTER SAVECONFIG: cluster-config-file not configured, cannot persist");
            return "-ERR cluster-config-file not configured\r\n";
        }
        try {
            ClusterConfigPersister persister = new ClusterConfigPersister();
            // 确保父目录存在
            File configFile = new File(clusterConfigFilePath);
            File parentDir = configFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }
            // N-27：保存前快照脏版本号，保存完成后仅当期间无新变更才清脏
            //（与 NettyRedisServer.saveClusterConfig 同一竞态修复）。
            long dirtyVersion = clusterConfig.getDirtyVersion();
            persister.save(clusterConfig, clusterConfigFilePath);
            clusterConfig.clearDirtyIfUnchanged(dirtyVersion);
            logger.info("CLUSTER SAVECONFIG: configuration saved to {}", clusterConfigFilePath);
            return "+OK\r\n";
        } catch (IOException e) {
            logger.error("CLUSTER SAVECONFIG: failed to save configuration", e);
            return "-ERR failed to save configuration: " + e.getMessage() + "\r\n";
        }
    }

    /**
     * 获取槽位迁移状态
     *
     * @param slot 槽位号
     * @return 迁移状态（IMPORTING/MIGRATING/null）
     */
    public String getSlotMigrationState(int slot) {
        return slotMigrationState.get(slot);
    }

    /**
     * 获取槽位迁移目标节点
     *
     * @param slot 槽位号
     * @return 目标节点ID
     */
    public String getSlotMigrationTarget(int slot) {
        return slotMigrationTarget.get(slot);
    }

    /**
     * 检查节点是否在 FORGET 黑名单中。
     * <p>
     * P1-3：黑名单已上移至 ClusterConfig（共享对象），此处委托查询以保持 API 兼容。
     * </p>
     *
     * @param nodeId 节点ID
     * @return 是否在黑名单有效期内
     */
    public boolean isNodeInForgetList(String nodeId) {
        return clusterConfig.isBlacklisted(nodeId);
    }

    /**
     * 清理 FORGET 黑名单中已过期的条目。委托 ClusterConfig。
     */
    public void cleanupForgetNodes() {
        clusterConfig.cleanupBlacklist();
    }

    /**
     * 清理指定节点在 SlotManager 中的槽位记录。
     * <p>
     * 在 CLUSTER FORGET 时调用，确保 slotManager.slotOwners[] 与
     * clusterConfig.slotAssignment[] 保持一致。
     * </p>
     *
     * @param nodeId 被移除的节点ID
     */
    private void clearSlotManagerForNode(String nodeId) {
        for (int i = 0; i < SlotUtils.CLUSTER_SLOTS; i++) {
            if (nodeId.equals(slotManager.getSlotOwner(i))) {
                slotManager.setSlotOwner(i, null);
            }
        }
    }
}
