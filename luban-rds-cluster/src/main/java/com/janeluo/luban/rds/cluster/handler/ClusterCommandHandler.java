package com.janeluo.luban.rds.cluster.handler;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterConfigPersister;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.config.ClusterStats;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.node.ClusterLink;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
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
     * 构造方法
     *
     * @param clusterConfig           集群配置
     * @param slotManager             槽位管理器
     * @param stateManager            集群状态管理器
     * @param gossipProtocol          Gossip 协议
     * @param clusterConfigFilePath   集群配置文件路径（用于 CLUSTER SAVECONFIG 持久化，可为 null）
     */
    public ClusterCommandHandler(ClusterConfig clusterConfig, SlotManager slotManager,
                                  ClusterStateManager stateManager, GossipProtocol gossipProtocol,
                                  String clusterConfigFilePath) {
        this.clusterConfig = clusterConfig;
        this.slotManager = slotManager;
        this.stateManager = stateManager;
        this.gossipProtocol = gossipProtocol;
        this.clusterConfigFilePath = clusterConfigFilePath;
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
                case "SAVECONFIG":
                    return clusterSaveconfig();
                case "REPLICAS":
                    return clusterSlaves(args);
                default:
                    return "-ERR Unknown subcommand or wrong number of arguments for '" 
                            + subcommand + "'\r\n";
            }
        } catch (IllegalArgumentException e) {
            logger.warn("CLUSTER {} 命令参数错误: {}", subcommand, e.getMessage());
            return "-ERR " + e.getMessage() + "\r\n";
        } catch (Exception e) {
            logger.error("CLUSTER {} 命令执行失败", subcommand, e);
            return "-ERR " + e.getMessage() + "\r\n";
        }
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

        // 消息统计
        sb.append("cluster_stats_messages_sent:").append(stats.getMessagesSent()).append("\r\n");
        sb.append("cluster_stats_messages_received:").append(stats.getMessagesReceived()).append("\r\n");

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

        for (ClusterNode node : clusterConfig.getAllNodes()) {
            if (node.hasState(ClusterNodeState.HANDSHAKE) || node.hasState(ClusterNodeState.NOADDR)) {
                continue;
            }

            // 节点ID
            sb.append(node.getNodeId());

            // 地址信息 ip:port@cport
            sb.append(" ");
            sb.append(node.getFullAddress());

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
    private String buildNodeFlags(ClusterNode node) {
        StringBuilder flags = new StringBuilder();

        if (node.isMyself()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("myself");
        }

        if (node.isMaster()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("master");
        }

        if (node.isSlave()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("slave");
        }

        if (node.isFail()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("fail");
        }

        if (node.isPfail()) {
            if (flags.length() > 0) {
                flags.append(",");
            }
            flags.append("fail?");
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
            logger.info("CLUSTER FORGET: removed slave node {}", nodeId);
            notifyTopologyChanged();
            return "+OK\r\n";
        }

        // 如果是主节点，检查是否还有槽位
        if (node.getSlotCount() > 0) {
            return "-ERR Node " + nodeId + " is not empty! Reshard data away and try again.\r\n";
        }

        // 添加到延迟移除列表
        forgetNodes.put(nodeId, System.currentTimeMillis() + FORGET_DELAY_MS);
        clusterConfig.removeNode(nodeId);

        logger.info("CLUSTER FORGET: removed master node {} (with 60s delay)", nodeId);
        notifyTopologyChanged();
        return "+OK\r\n";
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

        // 解析槽位参数
        int[] slots = new int[args.length - 1];
        for (int i = 1; i < args.length; i++) {
            try {
                int slot = Integer.parseInt(args[i]);
                SlotUtils.validateSlot(slot);
                slots[i - 1] = slot;
            } catch (NumberFormatException e) {
                return "-ERR Invalid slot number: " + args[i] + "\r\n";
            } catch (IllegalArgumentException e) {
                return "-ERR " + e.getMessage() + "\r\n";
            }
        }

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

            // 增加配置纪元
            clusterConfig.incrementEpoch();

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
                return "-ERR Invalid slot number: " + args[i] + "\r\n";
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
        try {
            slotManager.delSlots(slots);

            // 更新当前节点的槽位信息
            ClusterNode myNode = clusterConfig.getMyNode();
            if (myNode != null) {
                for (int slot : slots) {
                    myNode.removeSlot(slot);
                }
            }

            // 增加配置纪元
            clusterConfig.incrementEpoch();

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
            return "-ERR Invalid slot number: " + args[1] + "\r\n";
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
                logger.info("CLUSTER SETSLOT: slot {} set to MIGRATING to {}", slot, targetNodeId);
                return "+OK\r\n";

            case "STABLE":
                // 清除槽位迁移状态
                slotMigrationState.remove(slot);
                slotMigrationTarget.remove(slot);
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

                // 清除迁移状态
                slotMigrationState.remove(slot);
                slotMigrationTarget.remove(slot);

                // 增加配置纪元
                clusterConfig.incrementEpoch();

                logger.info("CLUSTER SETSLOT: slot {} assigned to node {}", slot, nodeId);
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
            return "-ERR Invalid slot number: " + args[1] + "\r\n";
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

        // TODO: 实际实现需要访问数据存储获取指定槽位的键
        // 这里返回空列表
        logger.debug("CLUSTER GETKEYSINSLOT: slot={}, count={}", slot, count);
        return "*0\r\n";
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
            return "-ERR Invalid slot number: " + args[1] + "\r\n";
        } catch (IllegalArgumentException e) {
            return "-ERR " + e.getMessage() + "\r\n";
        }

        // TODO: 实际实现需要访问数据存储统计指定槽位的键数量
        // 这里返回0
        logger.debug("CLUSTER COUNTKEYSINSLOT: slot={}", slot);
        return ":0\r\n";
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

        // 检查是否有 FORCE 或 TAKEOVER 选项
        boolean force = false;
        boolean takeover = false;

        if (args.length > 1) {
            String option = args[1].toUpperCase();
            if ("FORCE".equals(option)) {
                force = true;
            } else if ("TAKEOVER".equals(option)) {
                takeover = true;
            }
        }

        // 执行故障转移
        try {
            if (takeover) {
                // TAKEOVER 模式：直接接管，不需要授权
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER TAKEOVER: slave {} promoted to master",
                        myNode.getNodeId());
            } else if (force) {
                // FORCE 模式：强制故障转移，不需要主节点同意
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER FORCE: slave {} promoted to master",
                        myNode.getNodeId());
            } else {
                // 正常模式：需要主节点同意
                // TODO: 实现正常的故障转移流程，需要向主节点请求授权
                // 这里简化处理，直接执行
                performManualFailover(myNode, masterNode);
                logger.info("CLUSTER FAILOVER: slave {} promoted to master",
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
        // 清空槽位管理器中的槽位
        slotManager.clearMySlots();

        // 清空当前节点的槽位信息
        ClusterNode myNode = clusterConfig.getMyNode();
        if (myNode != null) {
            myNode.clearSlots();
        }

        logger.info("CLUSTER FLUSHSLOTS: all slots cleared");
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
            persister.save(clusterConfig, clusterConfigFilePath);
            clusterConfig.clearDirty();
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
     * 检查节点是否在延迟移除列表中
     *
     * @param nodeId 节点ID
     * @return 是否在延迟移除列表中
     */
    public boolean isNodeInForgetList(String nodeId) {
        Long expireTime = forgetNodes.get(nodeId);
        if (expireTime == null) {
            return false;
        }

        // 检查是否过期
        if (System.currentTimeMillis() > expireTime) {
            forgetNodes.remove(nodeId);
            return false;
        }

        return true;
    }

    /**
     * 清理过期的延迟移除节点
     */
    public void cleanupForgetNodes() {
        long now = System.currentTimeMillis();
        forgetNodes.entrySet().removeIf(entry -> now > entry.getValue());
    }
}
