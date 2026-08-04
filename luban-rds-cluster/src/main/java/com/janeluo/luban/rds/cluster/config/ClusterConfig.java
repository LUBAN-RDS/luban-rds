package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 集群配置
 * <p>
 * 管理整个集群的配置信息，包括节点列表、槽位分配、配置纪元等
 * </p>
 */
public class ClusterConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前节点ID
     */
    private String myNodeId;

    /**
     * 当前集群配置纪元（AtomicLong 保证跨线程自增/比较的原子性）
     */
    private final AtomicLong currentEpoch;

    /**
     * 当前节点的配置纪元（AtomicLong 保证跨线程自增/比较的原子性）
     */
    private final AtomicLong configEpoch;

    /**
     * 本节点最后一次投票的选举纪元（对齐 Redis 7 server.cluster->lastVoteEpoch）。
     * <p>
     * P0-4 修复：master 每次授权投票后更新此值，并在收到 AUTH_REQUEST 时拒绝
     * {@code reqEpoch <= lastVoteEpoch} 的请求。此值持久化到 nodes.conf，
     * 节点重启后不会遗忘已投过的纪元，避免同一纪元二次投票导致双 master。
     * </p>
     */
    private final AtomicLong lastVoteEpoch;

    /**
     * 所有节点列表（节点ID -> 节点对象）
     */
    private Map<String, ClusterNode> nodes;

    /**
     * 槽位分配表（槽位 -> 节点ID）
     * 数组长度为16384，每个元素存储负责该槽位的节点ID
     * <p>
     * volatile 保证引用可见性；数组内容的变更由 synchronized 方法保护。
     * </p>
     */
    private volatile String[] slotAssignment;

    /**
     * 已分配槽位的 BitSet（用于快速判断槽位是否已分配）
     * <p>
     * volatile 保证引用可见性；内容变更由 synchronized 方法保护。
     * </p>
     */
    private transient BitSet assignedSlotsBitSet;

    /**
     * 已分配槽位数量缓存（原子操作保证线程安全）
     */
    private transient AtomicInteger assignedSlotCount;

    /**
     * 集群状态：ok/fail
     */
    private volatile String state;

    /**
     * 集群配置脏标记（用于自动触发 nodes.conf 持久化）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑发生变更（节点增删、状态变更、槽位重分配、纪元变化）时置为 true，
     * 由后台定时任务检查并触发持久化，完成后清除。
     * </p>
     */
    private volatile boolean dirty;

    /**
     * FORGET 黑名单：被 CLUSTER FORGET 移除的节点在 60s 内禁止经 Gossip 重新引入。
     * <p>
     * P1-3 修复：原黑名单仅存在于 ClusterCommandHandler 且无任何调用方，导致被 FORGET 的
     * master 经 Gossip 立即"复活"。此处上移到共享的 ClusterConfig，使 Gossip 路径
     * （processGossipNodes / handleMeet）能在重新引入节点前查询此表。
     * key = 节点ID，value = 过期时间戳（System.currentTimeMillis() + 延迟）。
     * </p>
     */
    private final Map<String, Long> forgetBlacklist = new ConcurrentHashMap<>();

    /**
     * FORGET 黑名单默认延迟（毫秒），对齐 Redis CLUSTER_BLACKLIST_TTL。
     */
    private static final long FORGET_BLACKLIST_TTL_MS = 60_000L;

    /**
     * 默认构造方法
     */
    public ClusterConfig() {
        this.nodes = new ConcurrentHashMap<>();
        this.slotAssignment = new String[ClusterNode.CLUSTER_SLOTS];
        this.assignedSlotsBitSet = new BitSet(ClusterNode.CLUSTER_SLOTS);
        this.assignedSlotCount = new AtomicInteger(0);
        this.state = "fail";
        this.currentEpoch = new AtomicLong(0);
        this.configEpoch = new AtomicLong(0);
        this.lastVoteEpoch = new AtomicLong(0);
    }

    /**
     * 带当前节点ID的构造方法
     *
     * @param myNodeId 当前节点ID
     */
    public ClusterConfig(String myNodeId) {
        this();
        this.myNodeId = myNodeId;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getMyNodeId() {
        return myNodeId;
    }

    public void setMyNodeId(String myNodeId) {
        this.myNodeId = myNodeId;
    }

    public long getCurrentEpoch() {
        return currentEpoch.get();
    }

    public void setCurrentEpoch(long currentEpoch) {
        this.currentEpoch.set(currentEpoch);
    }

    public long getConfigEpoch() {
        return configEpoch.get();
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch.set(configEpoch);
    }

    public Map<String, ClusterNode> getNodes() {
        return nodes;
    }

    public void setNodes(Map<String, ClusterNode> nodes) {
        this.nodes = nodes != null ? new ConcurrentHashMap<>(nodes) : new ConcurrentHashMap<>();
    }

    public String[] getSlotAssignment() {
        return slotAssignment;
    }

    public synchronized void setSlotAssignment(String[] slotAssignment) {
        if (slotAssignment != null && slotAssignment.length != ClusterNode.CLUSTER_SLOTS) {
            throw new IllegalArgumentException(
                    "槽位分配表长度必须为" + ClusterNode.CLUSTER_SLOTS);
        }
        this.slotAssignment = slotAssignment != null
                ? slotAssignment.clone()
                : new String[ClusterNode.CLUSTER_SLOTS];
        // 重建 BitSet 和计数器，避免二者与新数组不一致
        this.assignedSlotsBitSet.clear();
        int count = 0;
        for (int i = 0; i < this.slotAssignment.length; i++) {
            if (this.slotAssignment[i] != null) {
                this.assignedSlotsBitSet.set(i);
                count++;
            }
        }
        this.assignedSlotCount.set(count);
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    // ==================== 节点管理方法 ====================

    /**
     * 添加节点
     *
     * @param node 要添加的节点
     */
    public void addNode(ClusterNode node) {
        if (node == null || node.getNodeId() == null) {
            throw new IllegalArgumentException("节点或节点ID不能为空");
        }
        nodes.put(node.getNodeId(), node);
    }

    /**
     * 移除节点
     *
     * @param nodeId 要移除的节点ID
     */
    public synchronized void removeNode(String nodeId) {
        if (nodeId == null) {
            return;
        }
        ClusterNode removed = nodes.remove(nodeId);
        if (removed != null) {
            // 清除该节点负责的所有槽位
            int clearedCount = 0;
            for (int i = 0; i < slotAssignment.length; i++) {
                if (nodeId.equals(slotAssignment[i])) {
                    slotAssignment[i] = null;
                    assignedSlotsBitSet.clear(i);
                    clearedCount++;
                }
            }
            // 更新已分配槽位计数
            if (clearedCount > 0) {
                assignedSlotCount.addAndGet(-clearedCount);
            }
        }
    }

    /**
     * 获取节点
     *
     * @param nodeId 节点ID
     * @return 节点对象，如果不存在则返回null
     */
    public ClusterNode getNode(String nodeId) {
        return nodes.get(nodeId);
    }

    /**
     * 获取所有节点
     *
     * @return 所有节点的集合
     */
    public Collection<ClusterNode> getAllNodes() {
        return nodes.values();
    }

    /**
     * 获取当前节点
     *
     * @return 当前节点对象，如果不存在则返回null
     */
    public ClusterNode getMyNode() {
        return myNodeId != null ? nodes.get(myNodeId) : null;
    }

    /**
     * 获取节点数量
     *
     * @return 节点数量
     */
    public int getNodeCount() {
        return nodes.size();
    }

    /**
     * 检查节点是否存在
     *
     * @param nodeId 节点ID
     * @return 节点是否存在
     */
    public boolean hasNode(String nodeId) {
        return nodes.containsKey(nodeId);
    }

    // ==================== 槽位管理方法 ====================

    /**
     * 设置槽位的负责节点
     *
     * @param slot   槽位号（0-16383）
     * @param nodeId 节点ID
     */
    public synchronized void setSlotOwner(int slot, String nodeId) {
        validateSlot(slot);
        String oldNodeId = slotAssignment[slot];
        slotAssignment[slot] = nodeId;

        // 更新 BitSet 和计数器
        if (nodeId != null && oldNodeId == null) {
            // 新分配
            assignedSlotsBitSet.set(slot);
            assignedSlotCount.incrementAndGet();
        } else if (nodeId == null && oldNodeId != null) {
            // 取消分配
            assignedSlotsBitSet.clear(slot);
            assignedSlotCount.decrementAndGet();
        }
        
        // 同时更新节点的槽位信息
        if (nodeId != null) {
            ClusterNode node = nodes.get(nodeId);
            if (node != null) {
                node.addSlot(slot);
            }
        }
        // 所有权转移时，清理旧节点的槽位记录，避免残留
        if (oldNodeId != null && !oldNodeId.equals(nodeId)) {
            ClusterNode oldNode = nodes.get(oldNodeId);
            if (oldNode != null) {
                oldNode.removeSlot(slot);
            }
        }
    }

    /**
     * 基于配置纪元比较，批量同步某节点的槽位归属
     * <p>
     * 用于 Gossip 收到对端/第三方节点槽位信息时的同步。仅当本地 slot 无 owner，
     * 或本地 owner 的配置纪元严格小于提供方的配置纪元时才覆盖，避免循环抢占。
     * 相等纪元不覆盖，保证 ADDSLOTS/REPLICATE 后 incrementEpoch 的新配置优先。
     * </p>
     *
     * @param nodeId      节点ID
     * @param slots       该节点拥有的槽位集合
     * @param configEpoch 该节点的配置纪元（用于冲突裁决）
     */
    public synchronized void syncSlotsFromNode(String nodeId, BitSet slots, long configEpoch) {
        if (nodeId == null || slots == null) {
            return;
        }
        ClusterNode node = nodes.get(nodeId);
        if (node == null) {
            return;
        }
        // 先捕获本节点当前记录该 owner 拥有的槽位快照（getSlots 返回防御性拷贝），
        // 用于后续移除"本地仍记为该 owner、但 advertised 位图已不含"的槽位。
        // 必须先捕获，否则下面的 add 循环会修改 node.slots，污染移除判定。
        BitSet prevOwnedByNode = node.getSlots();

        for (int s = slots.nextSetBit(0); s >= 0; s = slots.nextSetBit(s + 1)) {
            String curOwner = slotAssignment[s];
            if (curOwner == null) {
                setSlotOwner(s, nodeId);
            } else if (curOwner.equals(nodeId)) {
                // 已归属该节点，确保 ClusterNode.slots 一致
                node.addSlot(s);
            } else {
                ClusterNode curOwnerNode = nodes.get(curOwner);
                long curEpoch = curOwnerNode != null ? curOwnerNode.getConfigEpoch() : 0;
                if (configEpoch > curEpoch) {
                    // 抢占前先清理旧 owner 的 slot 记录，避免残留
                    if (curOwnerNode != null) {
                        curOwnerNode.removeSlot(s);
                    }
                    setSlotOwner(s, nodeId);
                }
            }
            if (s == Integer.MAX_VALUE) {
                break;
            }
        }

        // P1-2B：移除"本地仍记为 nodeId 拥有、但 advertised 位图不再包含"的槽位。
        // 原实现只增不删，节点迁出槽位后本地视图永不收敛 → 第三节点槽位归属陈旧。
        // 仅当 advertised configEpoch >= 本节点上次记录的该 owner configEpoch 时才移除，
        // 防止过期的 gossip 分片回放把更新的槽位变更冲掉（陈旧快照不覆盖新状态）。
        if (configEpoch >= node.getConfigEpoch()) {
            for (int s = prevOwnedByNode.nextSetBit(0); s >= 0; s = prevOwnedByNode.nextSetBit(s + 1)) {
                if (!slots.get(s) && nodeId.equals(slotAssignment[s])) {
                    setSlotOwner(s, null);
                    node.removeSlot(s);
                }
                if (s == Integer.MAX_VALUE) {
                    break;
                }
            }
        }
    }

    /**
     * 获取槽位的负责节点ID
     *
     * @param slot 槽位号（0-16383）
     * @return 节点ID，如果未分配则返回null
     */
    public String getSlotOwner(int slot) {
        validateSlot(slot);
        return slotAssignment[slot];
    }

    /**
     * 获取槽位的负责节点对象
     *
     * @param slot 槽位号（0-16383）
     * @return 节点对象，如果未分配或节点不存在则返回null
     */
    public ClusterNode getSlotOwnerNode(int slot) {
        String nodeId = getSlotOwner(slot);
        return nodeId != null ? nodes.get(nodeId) : null;
    }

    /**
     * 清除槽位分配
     *
     * @param slot 槽位号（0-16383）
     */
    public synchronized void clearSlot(int slot) {
        validateSlot(slot);
        String oldNodeId = slotAssignment[slot];
        slotAssignment[slot] = null;

        // 更新 BitSet 和计数器
        if (oldNodeId != null) {
            assignedSlotsBitSet.clear(slot);
            assignedSlotCount.decrementAndGet();
        }
        
        // 同时更新节点的槽位信息
        if (oldNodeId != null) {
            ClusterNode node = nodes.get(oldNodeId);
            if (node != null) {
                node.removeSlot(slot);
            }
        }
    }

    /**
     * 获取已分配的槽位数量
     *
     * @return 已分配的槽位数量
     */
    public int getAssignedSlotCount() {
        return assignedSlotCount.get();
    }

    /**
     * 检查所有槽位是否都已分配
     *
     * @return 所有槽位是否都已分配
     */
    public boolean areAllSlotsAssigned() {
        return assignedSlotCount.get() == ClusterNode.CLUSTER_SLOTS;
    }

    /**
     * 验证槽位号是否有效
     *
     * @param slot 槽位号
     * @throws IllegalArgumentException 如果槽位号无效
     */
    private void validateSlot(int slot) {
        if (slot < 0 || slot >= ClusterNode.CLUSTER_SLOTS) {
            throw new IllegalArgumentException(
                    "槽位号必须在0-" + (ClusterNode.CLUSTER_SLOTS - 1) + "范围内，当前值: " + slot);
        }
    }

    // ==================== 配置纪元方法 ====================

    /**
     * 增加集群配置纪元
     *
     * @return 新的配置纪元值
     */
    public long incrementEpoch() {
        return currentEpoch.incrementAndGet();
    }

    /**
     * 设置配置纪元（仅当新值更大时才更新）
     *
     * @param newEpoch 新的配置纪元值
     * @return 是否更新成功
     */
    public boolean setEpochIfGreater(long newEpoch) {
        return currentEpoch.getAndUpdate(e -> Math.max(e, newEpoch)) < newEpoch;
    }

    /**
     * 获取最后一次投票的选举纪元（对齐 Redis 7 lastVoteEpoch）。
     *
     * @return 最后一次投票的纪元，从未投过票返回 0
     */
    public long getLastVoteEpoch() {
        return lastVoteEpoch.get();
    }

    /**
     * 设置最后一次投票的选举纪元（从 nodes.conf 恢复时使用）。
     *
     * @param epoch 最后一次投票的纪元
     */
    public void setLastVoteEpoch(long epoch) {
        lastVoteEpoch.set(epoch);
    }

    /**
     * 记录一次投票并返回是否更新成功。
     * <p>
     * 仅当 {@code epoch} 大于当前 lastVoteEpoch 时更新（防止回退），
     * 并置脏标记触发 nodes.conf 持久化，确保重启后投票记忆不丢失。
     * </p>
     *
     * @param epoch 本次投票的选举纪元
     * @return 是否更新成功（epoch 更大时为 true）
     */
    public boolean recordVoteEpoch(long epoch) {
        boolean updated = lastVoteEpoch.getAndUpdate(e -> Math.max(e, epoch)) < epoch;
        if (updated) {
            markDirty();
        }
        return updated;
    }

    // ==================== FORGET 黑名单 ====================

    /**
     * 将节点加入 FORGET 黑名单（对齐 Redis clusterBlacklistAddNode）。
     * <p>
     * 被 CLUSTER FORGET 移除的节点在 TTL 内禁止经 Gossip 重新引入，
     * 否则对端的 gossip 段会立即把它"复活"，使 FORGET 失效。
     * </p>
     *
     * @param nodeId 被移除的节点ID
     */
    public void blacklistNode(String nodeId) {
        if (nodeId != null) {
            forgetBlacklist.put(nodeId, System.currentTimeMillis() + FORGET_BLACKLIST_TTL_MS);
        }
    }

    /**
     * 查询节点是否在 FORGET 黑名单内（且未过期）。
     *
     * @param nodeId 节点ID
     * @return true 表示该节点仍在黑名单有效期内，Gossip 不应重新引入
     */
    public boolean isBlacklisted(String nodeId) {
        if (nodeId == null) {
            return false;
        }
        Long expireAt = forgetBlacklist.get(nodeId);
        if (expireAt == null) {
            return false;
        }
        if (System.currentTimeMillis() > expireAt) {
            forgetBlacklist.remove(nodeId, expireAt);
            return false;
        }
        return true;
    }

    /**
     * 清理 FORGET 黑名单中已过期的条目（对齐 Redis clusterBlacklistCleanup）。
     * 应由后台定时任务周期调用。
     */
    public void cleanupBlacklist() {
        long now = System.currentTimeMillis();
        forgetBlacklist.entrySet().removeIf(e -> now > e.getValue());
    }

    // ==================== 状态管理方法 ====================

    /**
     * 标记集群配置为脏（拓扑发生变更）
     * <p>
     * 参照 Redis 7 clusterSaveConfigIfNeeded 机制：
     * 当集群拓扑变更时调用此方法，触发后台持久化。
     * </p>
     */
    public void markDirty() {
        this.dirty = true;
    }

    /**
     * 检查集群配置是否脏（是否有未持久化的拓扑变更）
     *
     * @return true 如果有未持久化的变更
     */
    public boolean isDirty() {
        return dirty;
    }

    /**
     * 清除脏标记（持久化完成后调用）
     */
    public void clearDirty() {
        this.dirty = false;
    }

    /**
     * 检查集群是否健康
     *
     * @return 集群是否健康
     */
    public boolean isClusterOk() {
        return "ok".equalsIgnoreCase(state);
    }

    /**
     * 获取主节点数量
     *
     * @return 主节点数量
     */
    public int getMasterCount() {
        int count = 0;
        for (ClusterNode node : nodes.values()) {
            if (node.isMaster()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取从节点数量
     *
     * @return 从节点数量
     */
    public int getSlaveCount() {
        int count = 0;
        for (ClusterNode node : nodes.values()) {
            if (node.isSlave()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取指定主节点的所有从节点
     *
     * @param masterNodeId 主节点ID
     * @return 从节点列表（可能为空，不会返回 null）
     */
    public List<ClusterNode> getSlavesOfMaster(String masterNodeId) {
        List<ClusterNode> slaves = new ArrayList<>();
        if (masterNodeId == null) {
            return slaves;
        }
        for (ClusterNode node : nodes.values()) {
            if (node.isSlave() && masterNodeId.equals(node.getMasterNodeId())) {
                slaves.add(node);
            }
        }
        return slaves;
    }

    /**
     * 重置集群配置
     */
    public synchronized void reset() {
        this.nodes.clear();
        // 就地重置而非替换对象引用，避免其他线程持有的旧引用失效
        Arrays.fill(this.slotAssignment, null);
        this.assignedSlotsBitSet.clear();
        this.assignedSlotCount.set(0);
        this.state = "fail";
        this.currentEpoch.set(0);
        this.configEpoch.set(0);
    }

    @Override
    public String toString() {
        return "ClusterConfig{" +
                "myNodeId='" + myNodeId + '\'' +
                ", currentEpoch=" + currentEpoch.get() +
                ", configEpoch=" + configEpoch.get() +
                ", state='" + state + '\'' +
                ", nodeCount=" + nodes.size() +
                ", assignedSlots=" + getAssignedSlotCount() +
                ", masterCount=" + getMasterCount() +
                '}';
    }
}
