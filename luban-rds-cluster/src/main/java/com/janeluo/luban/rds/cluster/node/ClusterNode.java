package com.janeluo.luban.rds.cluster.node;

import java.io.Serializable;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 集群节点数据模型
 * <p>
 * 表示Redis集群中的一个节点，包含节点的所有状态信息和配置
 * 使用 EnumSet 存储节点状态，比 HashSet 更高效
 * </p>
 */
public class ClusterNode implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Redis集群槽位总数
     */
    public static final int CLUSTER_SLOTS = 16384;

    /**
     * 节点ID长度（40字符十六进制）
     */
    public static final int NODE_ID_LENGTH = 40;

    /**
     * 节点ID（40字符十六进制字符串）
     */
    private String nodeId;

    /**
     * 节点IP地址
     */
    private String ip;

    /**
     * 节点端口
     */
    private int port;

    /**
     * 集群总线端口（用于节点间通信）
     */
    private int busPort;

    /**
     * 节点状态标志集合（使用 EnumSet 提高性能）
     */
    private Set<ClusterNodeState> state;

    /**
     * 主节点ID（仅从节点使用，存储其主节点的ID）
     */
    private String masterNodeId;

    /**
     * 分配的槽位（16384位，每位代表一个槽位）
     */
    private BitSet slots;

    /**
     * 配置纪元（用于集群配置版本控制）
     */
    private long configEpoch;

    /**
     * 最后一次发送PING的时间（毫秒时间戳）
     */
    private long lastPingTime;

    /**
     * 最后一次收到PONG的时间（毫秒时间戳）
     */
    private long lastPongTime;

    /**
     * 连接信息
     */
    private ClusterLink link;

    /**
     * 默认构造方法
     */
    public ClusterNode() {
        this.state = EnumSet.noneOf(ClusterNodeState.class);
        this.slots = new BitSet(CLUSTER_SLOTS);
        this.configEpoch = 0;
        this.lastPingTime = 0;
        this.lastPongTime = System.currentTimeMillis();
        this.link = new ClusterLink();
    }

    /**
     * 带节点ID的构造方法
     *
     * @param nodeId 节点ID（40字符十六进制）
     */
    public ClusterNode(String nodeId) {
        this();
        setNodeId(nodeId);
    }

    /**
     * 完整构造方法
     *
     * @param nodeId       节点ID
     * @param ip           IP地址
     * @param port         端口
     * @param busPort      集群总线端口
     */
    public ClusterNode(String nodeId, String ip, int port, int busPort) {
        this(nodeId);
        this.ip = ip;
        this.port = port;
        this.busPort = busPort;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getNodeId() {
        return nodeId;
    }

    /**
     * 设置节点ID
     *
     * @param nodeId 节点ID（必须是40字符的十六进制字符串）
     * @throws IllegalArgumentException 如果节点ID格式不正确
     */
    public void setNodeId(String nodeId) {
        if (nodeId != null && nodeId.length() != NODE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "节点ID长度必须为" + NODE_ID_LENGTH + "字符，当前长度: " + nodeId.length());
        }
        if (nodeId != null && !nodeId.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("节点ID必须为十六进制字符串");
        }
        this.nodeId = nodeId;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("端口号必须在0-65535范围内");
        }
        this.port = port;
    }

    public int getBusPort() {
        return busPort;
    }

    public void setBusPort(int busPort) {
        if (busPort < 0 || busPort > 65535) {
            throw new IllegalArgumentException("集群总线端口必须在0-65535范围内");
        }
        this.busPort = busPort;
    }

    public Set<ClusterNodeState> getState() {
        return state;
    }

    public void setState(Set<ClusterNodeState> state) {
        if (state == null) {
            this.state = EnumSet.noneOf(ClusterNodeState.class);
        } else if (state instanceof EnumSet) {
            this.state = EnumSet.copyOf(state);
        } else {
            this.state = EnumSet.copyOf(state);
        }
    }

    public String getMasterNodeId() {
        return masterNodeId;
    }

    public void setMasterNodeId(String masterNodeId) {
        this.masterNodeId = masterNodeId;
    }

    public BitSet getSlots() {
        return slots;
    }

    public void setSlots(BitSet slots) {
        this.slots = slots != null ? slots : new BitSet(CLUSTER_SLOTS);
    }

    public long getConfigEpoch() {
        return configEpoch;
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
    }

    public long getLastPingTime() {
        return lastPingTime;
    }

    public void setLastPingTime(long lastPingTime) {
        this.lastPingTime = lastPingTime;
    }

    public long getLastPongTime() {
        return lastPongTime;
    }

    public void setLastPongTime(long lastPongTime) {
        this.lastPongTime = lastPongTime;
    }

    public ClusterLink getLink() {
        return link;
    }

    public void setLink(ClusterLink link) {
        this.link = link != null ? link : new ClusterLink();
    }

    // ==================== 状态管理方法 ====================

    /**
     * 添加节点状态
     *
     * @param state 要添加的状态
     */
    public void addState(ClusterNodeState state) {
        this.state.add(state);
    }

    /**
     * 移除节点状态
     *
     * @param state 要移除的状态
     */
    public void removeState(ClusterNodeState state) {
        this.state.remove(state);
    }

    /**
     * 检查是否具有指定状态
     *
     * @param state 要检查的状态
     * @return 是否具有该状态
     */
    public boolean hasState(ClusterNodeState state) {
        return this.state.contains(state);
    }

    /**
     * 判断是否为主节点
     *
     * @return 是否为主节点
     */
    public boolean isMaster() {
        return hasState(ClusterNodeState.MASTER);
    }

    /**
     * 判断是否为从节点
     *
     * @return 是否为从节点
     */
    public boolean isSlave() {
        return hasState(ClusterNodeState.SLAVE);
    }

    /**
     * 判断是否为本节点
     *
     * @return 是否为本节点
     */
    public boolean isMyself() {
        return hasState(ClusterNodeState.MYSELF);
    }

    /**
     * 判断是否已下线
     *
     * @return 是否已下线
     */
    public boolean isFail() {
        return hasState(ClusterNodeState.FAIL);
    }

    /**
     * 判断是否可能下线
     *
     * @return 是否可能下线
     */
    public boolean isPfail() {
        return hasState(ClusterNodeState.PFAIL);
    }

    /**
     * 判断节点是否可用（未下线且未可能下线）
     *
     * @return 节点是否可用
     */
    public boolean isAvailable() {
        return !isFail() && !isPfail();
    }

    // ==================== 槽位管理方法 ====================

    /**
     * 分配槽位给此节点
     *
     * @param slot 槽位号（0-16383）
     */
    public void addSlot(int slot) {
        validateSlot(slot);
        slots.set(slot);
    }

    /**
     * 分配槽位范围给此节点
     *
     * @param start 起始槽位（包含）
     * @param end   结束槽位（包含）
     */
    public void addSlotRange(int start, int end) {
        validateSlot(start);
        validateSlot(end);
        if (start > end) {
            throw new IllegalArgumentException("起始槽位不能大于结束槽位");
        }
        slots.set(start, end + 1);
    }

    /**
     * 移除槽位
     *
     * @param slot 槽位号（0-16383）
     */
    public void removeSlot(int slot) {
        validateSlot(slot);
        slots.clear(slot);
    }

    /**
     * 检查槽位是否由此节点负责
     *
     * @param slot 槽位号（0-16383）
     * @return 是否由此节点负责
     */
    public boolean hasSlot(int slot) {
        validateSlot(slot);
        return slots.get(slot);
    }

    /**
     * 获取此节点负责的槽位数量
     *
     * @return 槽位数量
     */
    public int getSlotCount() {
        return slots.cardinality();
    }

    /**
     * 清空所有槽位
     */
    public void clearSlots() {
        slots.clear();
    }

    /**
     * 验证槽位号是否有效
     *
     * @param slot 槽位号
     * @throws IllegalArgumentException 如果槽位号无效
     */
    private void validateSlot(int slot) {
        if (slot < 0 || slot >= CLUSTER_SLOTS) {
            throw new IllegalArgumentException(
                    "槽位号必须在0-" + (CLUSTER_SLOTS - 1) + "范围内，当前值: " + slot);
        }
    }

    // ==================== 时间相关方法 ====================

    /**
     * 更新最后PING时间为当前时间
     */
    public void updateLastPingTime() {
        this.lastPingTime = System.currentTimeMillis();
    }

    /**
     * 更新最后PONG时间为当前时间
     */
    public void updateLastPongTime() {
        this.lastPongTime = System.currentTimeMillis();
    }

    /**
     * 获取距离上次PONG的时间间隔（毫秒）
     *
     * @return 时间间隔（毫秒）
     */
    public long getTimeSinceLastPong() {
        return System.currentTimeMillis() - lastPongTime;
    }

    /**
     * 获取距离上次PING的时间间隔（毫秒）
     *
     * @return 时间间隔（毫秒）
     */
    public long getTimeSinceLastPing() {
        return lastPingTime > 0 ? System.currentTimeMillis() - lastPingTime : 0;
    }

    // ==================== 配置纪元方法 ====================

    /**
     * 增加配置纪元
     *
     * @return 新的配置纪元值
     */
    public long incrementConfigEpoch() {
        return ++this.configEpoch;
    }

    /**
     * 设置配置纪元（仅当新值更大时才更新）
     *
     * @param newEpoch 新的配置纪元值
     * @return 是否更新成功
     */
    public boolean setConfigEpochIfGreater(long newEpoch) {
        if (newEpoch > this.configEpoch) {
            this.configEpoch = newEpoch;
            return true;
        }
        return false;
    }

    // ==================== 工具方法 ====================

    /**
     * 获取节点地址字符串（ip:port格式）
     *
     * @return 地址字符串
     */
    public String getAddress() {
        return ip + ":" + port;
    }

    /**
     * 获取节点完整地址字符串（ip:port@busPort格式）
     *
     * @return 完整地址字符串
     */
    public String getFullAddress() {
        return ip + ":" + port + "@" + busPort;
    }

    /**
     * 重置节点状态（保留ID和地址信息）
     */
    public void reset() {
        this.state.clear();
        this.masterNodeId = null;
        this.slots.clear();
        this.configEpoch = 0;
        this.lastPingTime = 0;
        this.lastPongTime = System.currentTimeMillis();
        if (this.link != null) {
            this.link.reset();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClusterNode that = (ClusterNode) o;
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "ClusterNode{" +
                "nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", port=" + port +
                ", busPort=" + busPort +
                ", state=" + state +
                ", masterNodeId='" + masterNodeId + '\'' +
                ", slotCount=" + getSlotCount() +
                ", configEpoch=" + configEpoch +
                ", lastPingTime=" + lastPingTime +
                ", lastPongTime=" + lastPongTime +
                ", link=" + link +
                '}';
    }
}
