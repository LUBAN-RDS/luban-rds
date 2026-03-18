package com.janeluo.luban.rds.cluster.config;

import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.io.Serializable;
import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

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
     * 当前集群配置纪元
     */
    private long currentEpoch;

    /**
     * 当前节点的配置纪元
     */
    private long configEpoch;

    /**
     * 所有节点列表（节点ID -> 节点对象）
     */
    private Map<String, ClusterNode> nodes;

    /**
     * 槽位分配表（槽位 -> 节点ID）
     * 数组长度为16384，每个元素存储负责该槽位的节点ID
     */
    private String[] slotAssignment;

    /**
     * 已分配槽位的 BitSet（用于快速判断槽位是否已分配）
     */
    private transient BitSet assignedSlotsBitSet;

    /**
     * 已分配槽位数量缓存（原子操作保证线程安全）
     */
    private transient AtomicInteger assignedSlotCount;

    /**
     * 集群状态：ok/fail
     */
    private String state;

    /**
     * 默认构造方法
     */
    public ClusterConfig() {
        this.nodes = new ConcurrentHashMap<>();
        this.slotAssignment = new String[ClusterNode.CLUSTER_SLOTS];
        this.assignedSlotsBitSet = new BitSet(ClusterNode.CLUSTER_SLOTS);
        this.assignedSlotCount = new AtomicInteger(0);
        this.state = "fail";
        this.currentEpoch = 0;
        this.configEpoch = 0;
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
        return currentEpoch;
    }

    public void setCurrentEpoch(long currentEpoch) {
        this.currentEpoch = currentEpoch;
    }

    public long getConfigEpoch() {
        return configEpoch;
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
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

    public void setSlotAssignment(String[] slotAssignment) {
        if (slotAssignment != null && slotAssignment.length != ClusterNode.CLUSTER_SLOTS) {
            throw new IllegalArgumentException(
                    "槽位分配表长度必须为" + ClusterNode.CLUSTER_SLOTS);
        }
        this.slotAssignment = slotAssignment != null 
                ? slotAssignment.clone() 
                : new String[ClusterNode.CLUSTER_SLOTS];
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
    public void removeNode(String nodeId) {
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
    public void setSlotOwner(int slot, String nodeId) {
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
    public void clearSlot(int slot) {
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
        return ++this.currentEpoch;
    }

    /**
     * 设置配置纪元（仅当新值更大时才更新）
     *
     * @param newEpoch 新的配置纪元值
     * @return 是否更新成功
     */
    public boolean setEpochIfGreater(long newEpoch) {
        if (newEpoch > this.currentEpoch) {
            this.currentEpoch = newEpoch;
            return true;
        }
        return false;
    }

    // ==================== 状态管理方法 ====================

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
     * 重置集群配置
     */
    public void reset() {
        this.nodes.clear();
        this.slotAssignment = new String[ClusterNode.CLUSTER_SLOTS];
        this.assignedSlotsBitSet = new BitSet(ClusterNode.CLUSTER_SLOTS);
        this.assignedSlotCount = new AtomicInteger(0);
        this.state = "fail";
        this.currentEpoch = 0;
        this.configEpoch = 0;
    }

    @Override
    public String toString() {
        return "ClusterConfig{" +
                "myNodeId='" + myNodeId + '\'' +
                ", currentEpoch=" + currentEpoch +
                ", configEpoch=" + configEpoch +
                ", state='" + state + '\'' +
                ", nodeCount=" + nodes.size() +
                ", assignedSlots=" + getAssignedSlotCount() +
                ", masterCount=" + getMasterCount() +
                '}';
    }
}
