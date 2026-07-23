package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

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
     * 检查集群是否健康
     * <p>
     * 集群健康的条件：
     * 1. 所有16384个槽位都已分配
     * 2. 所有负责槽位的主节点都可用（未下线）
     * </p>
     *
     * @return 集群是否健康
     */
    public boolean isClusterOk() {
        // 检查所有槽位是否都已分配
        if (!config.areAllSlotsAssigned()) {
            return false;
        }

        // 检查所有负责槽位的主节点是否可用。
        // 注意：Redis 语义中 slot 的 owner 只能是 master；slave 接管 slot 前必须先提权为 master。
        // PFAIL 不计入 fail（宽容期，避免网络抖动误判集群下线），仅 FAIL master 持有的 slot 使集群 fail。
        // 此处有意简化：未实现 Redis 的"多数 master 不可达才 fail"quorum 机制。
        for (int slot = 0; slot < ClusterNode.CLUSTER_SLOTS; slot++) {
            ClusterNode node = config.getSlotOwnerNode(slot);
            if (node == null) {
                return false;
            }
            if (node.isFail()) {
                return false;
            }
        }

        return true;
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

        // 设置纪元信息
        stats.setCurrentEpoch(config.getCurrentEpoch());
        stats.setMyEpoch(config.getConfigEpoch());

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
     * 增加已接收消息计数
     *
     * @param count 增加的数量
     */
    public void incrementMessagesReceived(long count) {
        this.messagesReceived.add(count);
    }

    /**
     * 重置消息计数
     */
    public void resetMessageCounters() {
        this.messagesSent.reset();
        this.messagesReceived.reset();
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
