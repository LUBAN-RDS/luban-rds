package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

/**
 * 集群状态管理器
 * <p>
 * 负责检查集群健康状态、统计信息收集等功能
 * </p>
 */
public class ClusterStateManager {

    /**
     * 集群配置引用
     */
    private final ClusterConfig config;

    /**
     * 已发送消息计数（LongAdder 保证多线程累加的原子性与高吞吐）
     */
    private final LongAdder messagesSent = new LongAdder();

    /**
     * 已接收消息计数（LongAdder 保证多线程累加的原子性与高吞吐）
     */
    private final LongAdder messagesReceived = new LongAdder();

    /**
     * 分类型已发送消息计数（类型展示名 -> 计数，N-26 输出补全）。
     * 供 CLUSTER INFO 输出 Redis 风格的 cluster_stats_messages_<type>_sent 字段。
     */
    private final Map<String, LongAdder> messagesSentByType = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 分类型已接收消息计数（类型展示名 -> 计数，N-26 输出补全）。
     */
    private final Map<String, LongAdder> messagesReceivedByType = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 构造方法
     *
     * @param config 集群配置
     */
    public ClusterStateManager(ClusterConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("集群配置不能为空");
        }
        this.config = config;
    }

    /**
     * 检查集群是否健康（N-26：全网唯一 cluster_state 公式，对齐 Redis 7.2 clusterUpdateState）。
     * <p>
     * 集群 fail 当且仅当满足以下任一条件（require_full_coverage=yes 语义）：
     * <ol>
     *   <li><b>全槽覆盖</b>：任一槽位未分配，或槽位 owner 处于 FAIL 状态
     *       （PFAIL owner 容忍——宽容期，避免网络抖动误判集群下线；对齐 Redis
     *       {@code slots[j] == NULL || slots[j]->flags & CLUSTER_NODE_FAIL}）。</li>
     *   <li><b>多数可达</b>：reachable_masters（持槽且非 FAIL/PFAIL 的 master 数）
     *       &lt; size/2+1。size = 持槽 master 总数（<b>含</b> FAIL/PFAIL，对齐 Redis
     *       getClusterSize：{@code nodeIsMaster(node) && node->numslots}）。</li>
     * </ol>
     * 注意：size 只统计"持槽"master（Redis cluster size 语义），不含无槽 master；
     * 旧实现（GossipTask）按全部 master 计数，会在空 master 加入时错误改变 quorum。
     * </p>
     *
     * @return 集群是否健康
     */
    public boolean isClusterOk() {
        // ① 全槽覆盖：所有槽位已分配且 owner 非 FAIL
        for (int slot = 0; slot < ClusterNode.CLUSTER_SLOTS; slot++) {
            ClusterNode node = config.getSlotOwnerNode(slot);
            if (node == null) {
                return false;
            }
            if (node.isFail()) {
                return false;
            }
        }

        // ② 多数可达：reachable_masters >= size/2 + 1（size 含 FAIL/PFAIL 持槽 master）
        int size = 0;
        int reachableMasters = 0;
        for (ClusterNode node : config.getAllNodes()) {
            if (node.isMaster() && node.getSlotCount() > 0) {
                size++;
                if (!node.isFail() && !node.isPfail()) {
                    reachableMasters++;
                }
            }
        }
        return reachableMasters >= size / 2 + 1;
    }

    /**
     * 获取集群统计信息
     *
     * @return 集群统计信息对象
     */
    public ClusterStats getStats() {
        ClusterStats stats = new ClusterStats();

        // 设置集群状态
        stats.setState(isClusterOk() ? "ok" : "fail");

        // 统计槽位信息
        int slotsAssigned = 0;
        int slotsOk = 0;
        int slotsPfail = 0;
        int slotsFail = 0;

        for (int slot = 0; slot < ClusterNode.CLUSTER_SLOTS; slot++) {
            String nodeId = config.getSlotOwner(slot);
            if (nodeId != null) {
                slotsAssigned++;
                ClusterNode node = config.getNode(nodeId);
                if (node != null) {
                    if (node.isFail()) {
                        slotsFail++;
                    } else if (node.isPfail()) {
                        slotsPfail++;
                    } else {
                        slotsOk++;
                    }
                }
            }
        }

        stats.setSlotsAssigned(slotsAssigned);
        stats.setSlotsOk(slotsOk);
        stats.setSlotsPfail(slotsPfail);
        stats.setSlotsFail(slotsFail);

        // 统计节点信息
        int knownNodes = config.getNodeCount();
        int masterCount = 0;

        for (ClusterNode node : config.getAllNodes()) {
            if (node.isMaster()) {
                masterCount++;
            }
        }

        stats.setKnownNodes(knownNodes);
        stats.setSize(masterCount);

        // 设置纪元信息。N-26：cluster_my_epoch 使用 MYSELF 节点的实际 configEpoch——
        // ClusterConfig 级别独立字段只在 restoreClusterFromConfig 时从 header 恢复、
        // 其余时间恒为 0（陈旧死字段），输出会误导监控（与 nodes.conf 持久化的
        // "My Config Epoch" 修复同源）。
        ClusterNode myNode = config.getMyNode();
        long myEpoch = myNode != null ? myNode.getConfigEpoch() : config.getConfigEpoch();
        stats.setCurrentEpoch(config.getCurrentEpoch());
        stats.setMyEpoch(myEpoch);

        // N-26：分类型消息计数（CLUSTER INFO per-type 字段）
        Map<String, Long> sentByType = new java.util.HashMap<>();
        for (Map.Entry<String, LongAdder> e : messagesSentByType.entrySet()) {
            sentByType.put(e.getKey(), e.getValue().sum());
        }
        Map<String, Long> receivedByType = new java.util.HashMap<>();
        for (Map.Entry<String, LongAdder> e : messagesReceivedByType.entrySet()) {
            receivedByType.put(e.getKey(), e.getValue().sum());
        }
        stats.setMessagesSentByType(sentByType);
        stats.setMessagesReceivedByType(receivedByType);

        // 设置消息计数
        stats.setMessagesSent(messagesSent.sum());
        stats.setMessagesReceived(messagesReceived.sum());

        return stats;
    }

    /**
     * 增加已发送消息计数
     *
     * @param count 增加的数量
     */
    public void incrementMessagesSent(long count) {
        this.messagesSent.add(count);
    }

    /**
     * 增加已发送消息计数（分类型，N-26）。
     *
     * @param type  消息类型展示名（GossipMessageType.getDisplayName()）
     * @param count 增加的数量
     */
    public void incrementMessagesSent(String type, long count) {
        this.messagesSent.add(count);
        if (type != null) {
            this.messagesSentByType.computeIfAbsent(type, k -> new LongAdder()).add(count);
        }
    }

    /**
     * 增加已接收消息计数
     *
     * @param count 增加的数量
     */
    public void incrementMessagesReceived(long count) {
        this.messagesReceived.add(count);
    }

    /**
     * 增加已接收消息计数（分类型，N-26）。
     *
     * @param type  消息类型展示名（GossipMessageType.getDisplayName()）
     * @param count 增加的数量
     */
    public void incrementMessagesReceived(String type, long count) {
        this.messagesReceived.add(count);
        if (type != null) {
            this.messagesReceivedByType.computeIfAbsent(type, k -> new LongAdder()).add(count);
        }
    }

    /**
     * 重置消息计数
     */
    public void resetMessageCounters() {
        this.messagesSent.reset();
        this.messagesReceived.reset();
        this.messagesSentByType.clear();
        this.messagesReceivedByType.clear();
    }

    /**
     * 检查指定槽位是否可用
     *
     * @param slot 槽位号
     * @return 槽位是否可用
     */
    public boolean isSlotAvailable(int slot) {
        if (slot < 0 || slot >= ClusterNode.CLUSTER_SLOTS) {
            return false;
        }

        ClusterNode node = config.getSlotOwnerNode(slot);
        if (node == null) {
            return false;
        }

        // slot owner 只能是 master，检查其是否可用（未 FAIL/PFAIL）
        return node.isAvailable();
    }

    /**
     * 获取不可用的槽位数量
     *
     * @return 不可用的槽位数量
     */
    public int getUnavailableSlotCount() {
        int count = 0;
        for (int slot = 0; slot < ClusterNode.CLUSTER_SLOTS; slot++) {
            if (!isSlotAvailable(slot)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检查是否有足够的节点进行故障转移
     * <p>
     * 至少需要半数以上的主节点可用
     * </p>
     *
     * @return 是否可以进行故障转移
     */
    public boolean canFailover() {
        // 前置条件：本节点必须是 slave，且其 master 已 FAIL（对齐 Redis：仅 fail master 的 slave 才能发起选举）
        ClusterNode myself = config.getMyNode();
        if (myself == null || !myself.isSlave()) {
            return false;
        }
        String myMasterId = myself.getMasterNodeId();
        if (myMasterId == null) {
            return false;
        }
        ClusterNode myMaster = config.getNode(myMasterId);
        if (myMaster == null || !myMaster.isFail()) {
            return false;
        }

        int masterCount = 0;
        int availableMasterCount = 0;

        for (ClusterNode node : config.getAllNodes()) {
            if (node.isMaster()) {
                masterCount++;
                if (node.isAvailable()) {
                    availableMasterCount++;
                }
            }
        }

        // 需要超过半数的主节点可用
        return masterCount > 0 && availableMasterCount > masterCount / 2;
    }

    /**
     * 获取处于FAIL状态的节点数量
     *
     * @return FAIL状态的节点数量
     */
    public int getFailNodeCount() {
        int count = 0;
        for (ClusterNode node : config.getAllNodes()) {
            if (node.isFail()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取处于PFAIL状态的节点数量
     *
     * @return PFAIL状态的节点数量
     */
    public int getPfailNodeCount() {
        int count = 0;
        for (ClusterNode node : config.getAllNodes()) {
            if (node.isPfail()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 更新集群状态
     */
    public void updateClusterState() {
        boolean isOk = isClusterOk();
        config.setState(isOk ? "ok" : "fail");
    }

    /**
     * 获取集群配置
     *
     * @return 集群配置
     */
    public ClusterConfig getConfig() {
        return config;
    }
}
