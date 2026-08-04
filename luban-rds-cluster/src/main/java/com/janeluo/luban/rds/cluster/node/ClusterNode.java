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
    private volatile String masterNodeId;

    /**
     * 分配的槽位（16384位，每位代表一个槽位）
     */
    private volatile BitSet slots;

    /**
     * 配置纪元（用于集群配置版本控制）
     */
    private volatile long configEpoch;

    /**
     * 最后一次发送PING的时间（毫秒时间戳）
     */
    private volatile long lastPingTime;

    /**
     * 最后一次收到PONG的时间（毫秒时间戳）
     */
    private volatile long lastPongTime;

    /**
     * 节点被标记为 FAIL 状态的时刻（毫秒时间戳，0 表示未标记 FAIL）。
     * <p>
     * 由 {@link #addState(ClusterNodeState)} 在添加 FAIL 时自动记录，
     * 由 {@link #removeState(ClusterNodeState)} 在移除 FAIL 时清零。
     * 用于 {@link com.janeluo.luban.rds.cluster.gossip.FailureDetector} 的 FAIL 保护期判断
     * （对齐 Redis Cluster：FAIL 状态至少保持 NODE_TIMEOUT*2，防止短暂恢复导致 failover 抖动）。
     * </p>
     */
    private volatile long failTime;

    /**
     * 复制偏移量（P1-6）。
     * <p>
     * slave 的已同步偏移量（master_repl_offset），master 通常为 0 或自身偏移。
     * 由 gossip（PING/PONG/MEET 消息头与 gossip section）传播，
     * 供 {@link com.janeluo.luban.rds.cluster.gossip.FailoverManager} 计算 failover rank 退避，
     * 使 offset 更大（数据更新鲜）的 slave 优先发起选举、优先获票。
     * </p>
     */
    private volatile long replOffset;

    /**
     * 连接信息
     */
    private volatile ClusterLink link;

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

    /**
     * 获取节点状态标志集合（返回防御性副本，避免外部绕过 addState/removeState 直接修改）
     *
     * @return 状态标志集合的副本
     */
    public synchronized Set<ClusterNodeState> getState() {
        if (state.isEmpty()) {
            return EnumSet.noneOf(ClusterNodeState.class);
        }
        return EnumSet.copyOf(state);
    }

    public synchronized void setState(Set<ClusterNodeState> state) {
        if (state == null || state.isEmpty()) {
            this.state = EnumSet.noneOf(ClusterNodeState.class);
        } else {
            this.state = EnumSet.copyOf(state);
        }
    }

    public synchronized String getMasterNodeId() {
        return masterNodeId;
    }

    public synchronized void setMasterNodeId(String masterNodeId) {
        this.masterNodeId = masterNodeId;
    }

    /**
     * 获取此节点拥有的槽位集合（返回防御性副本）。
     * <p>
     * 返回 clone 而非内部引用，避免外部直接 set/clear 破坏一致性，
     * 也避免并发遍历与 addSlot/removeSlot 竞态。
     * </p>
     *
     * @return 槽位集合的副本
     */
    public synchronized BitSet getSlots() {
        return (BitSet) slots.clone();
    }

    public synchronized void setSlots(BitSet slots) {
        this.slots = slots != null ? slots : new BitSet(CLUSTER_SLOTS);
    }

    public synchronized long getConfigEpoch() {
        return configEpoch;
    }

    public synchronized void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
    }

    /**
     * 获取复制偏移量（P1-6，用于 failover rank 计算）。
     *
     * @return 复制偏移量
     */
    public synchronized long getReplOffset() {
        return replOffset;
    }

    /**
     * 设置复制偏移量（P1-6）。
     *
     * @param replOffset 复制偏移量
     */
    public synchronized void setReplOffset(long replOffset) {
        this.replOffset = replOffset;
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

    /**
     * 获取节点被标记为 FAIL 的时刻。
     *
     * @return FAIL 标记时刻（毫秒时间戳），0 表示当前未处于 FAIL 状态
     */
    public long getFailTime() {
        return failTime;
    }

    /**
     * 设置节点被标记为 FAIL 的时刻。
     * <p>
     * 供测试与恢复场景手动操作 FAIL 标记时间（如模拟保护期已过）。
     * 正常路径由 {@link #addState(ClusterNodeState)}/{@link #removeState(ClusterNodeState)} 自动维护。
     * </p>
     *
     * @param failTime FAIL 标记时刻（毫秒时间戳），0 表示未标记
     */
    public void setFailTime(long failTime) {
        this.failTime = failTime;
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
     * <p>
     * 当添加 FAIL 状态时，自动记录 {@link #failTime} 为当前时刻，
     * 供 {@link com.janeluo.luban.rds.cluster.gossip.FailureDetector} 的 FAIL 保护期判断使用。
     * </p>
     *
     * @param state 要添加的状态
     */
    public synchronized void addState(ClusterNodeState state) {
        this.state.add(state);
        if (state == ClusterNodeState.FAIL) {
            this.failTime = System.currentTimeMillis();
        }
    }

    /**
     * 移除节点状态
     * <p>
     * 当移除 FAIL 状态时，自动清零 {@link #failTime}。
     * </p>
     *
     * @param state 要移除的状态
     */
    public synchronized void removeState(ClusterNodeState state) {
        this.state.remove(state);
        if (state == ClusterNodeState.FAIL) {
            this.failTime = 0L;
        }
    }

    /**
     * 检查是否具有指定状态
     *
     * @param state 要检查的状态
     * @return 是否具有该状态
     */
    public synchronized boolean hasState(ClusterNodeState state) {
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
    public synchronized void addSlot(int slot) {
        validateSlot(slot);
        slots.set(slot);
    }

    /**
     * 分配槽位范围给此节点
     *
     * @param start 起始槽位（包含）
     * @param end   结束槽位（包含）
     */
    public synchronized void addSlotRange(int start, int end) {
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
    public synchronized void removeSlot(int slot) {
        validateSlot(slot);
        slots.clear(slot);
    }

    /**
     * 检查槽位是否由此节点负责
     *
     * @param slot 槽位号（0-16383）
     * @return 是否由此节点负责
     */
    public synchronized boolean hasSlot(int slot) {
        validateSlot(slot);
        return slots.get(slot);
    }

    /**
     * 获取此节点负责的槽位数量
     *
     * @return 槽位数量
     */
    public synchronized int getSlotCount() {
        return slots.cardinality();
    }

    /**
     * 清空所有槽位
     */
    public synchronized void clearSlots() {
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
            // 对齐 Redis 错误串（N-20），避免中文消息经 catch 泄漏到客户端 RESP 响应
            throw new IllegalArgumentException("Invalid slot specified");
        }
    }

    // ==================== 时间相关方法 ====================

    /**
     * 更新最后PING时间为当前时间
     */
    public synchronized void updateLastPingTime() {
        this.lastPingTime = System.currentTimeMillis();
    }

    /**
     * 更新最后PONG时间为当前时间
     */
    public synchronized void updateLastPongTime() {
        this.lastPongTime = System.currentTimeMillis();
    }

    /**
     * 获取距离上次PONG的时间间隔（毫秒）
     *
     * @return 时间间隔（毫秒）
     */
    public synchronized long getTimeSinceLastPong() {
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
    public synchronized long incrementConfigEpoch() {
        return ++this.configEpoch;
    }

    /**
     * 设置配置纪元（仅当新值更大时才更新）
     *
     * @param newEpoch 新的配置纪元值
     * @return 是否更新成功
     */
    public synchronized boolean setConfigEpochIfGreater(long newEpoch) {
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
    public synchronized void reset() {
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
