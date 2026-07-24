package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.io.Serializable;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Gossip 节点信息
 * <p>
 * 用于在心跳消息中携带节点状态信息，实现集群状态的传播
 * 使用 EnumSet 存储节点状态，比 HashSet 更高效
 * </p>
 */
public class GossipNodeInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 节点ID长度（40字符十六进制）
     */
    public static final int NODE_ID_LENGTH = 40;

    /**
     * 空字节数组常量（slots 为 null 时使用）
     */
    private static final byte[] EMPTY_BYTES = new byte[0];

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
     * 配置纪元（用于集群配置版本控制）
     */
    private long configEpoch;

    /**
     * 节点状态标志集合（使用 EnumSet 提高性能）
     */
    private Set<ClusterNodeState> flags;

    /**
     * 节点拥有的槽位集合（16384 bit）
     * <p>
     * 用于在 Gossip 消息中携带槽位所有权，使各节点对全局槽位分配达成一致。
     * 可为 null 表示未知或不携带。
     * </p>
     */
    private BitSet slots;

    /**
     * 主节点ID（仅从节点使用，存储其主节点的ID）。
     * <p>
     * 在 Gossip 中传播 master-slave 关系，作为 FailoverResult 消息丢包时的
     * 后备收敛机制。null 表示未知或不适用（主节点无 masterNodeId）。
     * </p>
     */
    private String masterNodeId;

    /**
     * 默认构造方法
     */
    public GossipNodeInfo() {
        this.flags = EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 带节点ID的构造方法
     *
     * @param nodeId 节点ID（40字符十六进制）
     */
    public GossipNodeInfo(String nodeId) {
        this();
        setNodeId(nodeId);
    }

    /**
     * 完整构造方法
     *
     * @param nodeId      节点ID
     * @param ip          IP地址
     * @param port        端口
     * @param busPort     集群总线端口
     * @param configEpoch 配置纪元
     * @param flags       状态标志集合
     */
    public GossipNodeInfo(String nodeId, String ip, int port, int busPort,
                          long configEpoch, Set<ClusterNodeState> flags) {
        this(nodeId);
        this.ip = ip;
        this.port = port;
        this.busPort = busPort;
        this.configEpoch = configEpoch;
        this.flags = flags != null ? EnumSet.copyOf(flags) : EnumSet.noneOf(ClusterNodeState.class);
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

    public long getConfigEpoch() {
        return configEpoch;
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
    }

    public Set<ClusterNodeState> getFlags() {
        return flags;
    }

    public void setFlags(Set<ClusterNodeState> flags) {
        this.flags = flags != null ? EnumSet.copyOf(flags) : EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 获取节点拥有的槽位集合
     *
     * @return 槽位集合，可能为 null
     */
    public BitSet getSlots() {
        return slots;
    }

    /**
     * 设置节点拥有的槽位集合
     *
     * @param slots 槽位集合，null 表示未知
     */
    public void setSlots(BitSet slots) {
        this.slots = slots;
    }

    /**
     * 获取主节点ID（仅从节点有效）
     *
     * @return 主节点ID，未知或不适用时返回 null
     */
    public String getMasterNodeId() {
        return masterNodeId;
    }

    /**
     * 设置主节点ID
     *
     * @param masterNodeId 主节点ID，null 表示清除
     */
    public void setMasterNodeId(String masterNodeId) {
        this.masterNodeId = masterNodeId;
    }

    // ==================== 状态管理方法 ====================

    /**
     * 添加节点状态标志
     *
     * @param flag 要添加的状态标志
     */
    public void addFlag(ClusterNodeState flag) {
        this.flags.add(flag);
    }

    /**
     * 移除节点状态标志
     *
     * @param flag 要移除的状态标志
     */
    public void removeFlag(ClusterNodeState flag) {
        this.flags.remove(flag);
    }

    /**
     * 检查是否具有指定状态标志
     *
     * @param flag 要检查的状态标志
     * @return 是否具有该状态标志
     */
    public boolean hasFlag(ClusterNodeState flag) {
        return this.flags.contains(flag);
    }

    /**
     * 判断是否为主节点
     *
     * @return 是否为主节点
     */
    public boolean isMaster() {
        return hasFlag(ClusterNodeState.MASTER);
    }

    /**
     * 判断是否为从节点
     *
     * @return 是否为从节点
     */
    public boolean isSlave() {
        return hasFlag(ClusterNodeState.SLAVE);
    }

    /**
     * 判断是否已下线
     *
     * @return 是否已下线
     */
    public boolean isFail() {
        return hasFlag(ClusterNodeState.FAIL);
    }

    /**
     * 判断是否可能下线
     *
     * @return 是否可能下线
     */
    public boolean isPfail() {
        return hasFlag(ClusterNodeState.PFAIL);
    }

    // ==================== 编解码方法 ====================

    /**
     * 将节点信息编码为字节数组
     * <p>
     * 编码格式：
     * - 节点ID（40字节）
     * - IP地址长度（1字节）+ IP地址（变长）
     * - 端口（4字节，大端序）
     * - 总线端口（4字节，大端序）
     * - 配置纪元（8字节，大端序）
     * - 状态标志数量（1字节）+ 状态标志（每个2字节）
     * - 槽位字节数（4字节，大端序）+ 槽位位图（变长，BitSet.toByteArray）
     * </p>
     *
     * @return 编码后的字节数组
     */
    public byte[] encode() {
        // 校验 nodeId 长度，禁止空填充导致的解码歧义
        if (nodeId != null && nodeId.length() != NODE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "节点ID长度必须为" + NODE_ID_LENGTH + "字符，当前长度: " + nodeId.length());
        }

        // 计算总长度
        int ipBytesLength = ip != null ? ip.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        int flagsCount = flags.size();
        byte[] slotsBytes = slots != null ? slots.toByteArray() : EMPTY_BYTES;
        // masterNodeId：1 字节标志 + （有值时）40 字节 node-id
        int masterNodeIdLength = masterNodeId != null ? NODE_ID_LENGTH : 0;
        int totalLength = NODE_ID_LENGTH + 1 + ipBytesLength + 4 + 4 + 8 + 1 + flagsCount * 2
                + 4 + slotsBytes.length + 1 + masterNodeIdLength;

        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入节点ID
        if (nodeId != null) {
            byte[] nodeIdBytes = nodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(nodeIdBytes, 0, data, offset, NODE_ID_LENGTH);
        }
        offset += NODE_ID_LENGTH;

        // 写入IP地址长度和IP地址
        data[offset++] = (byte) ipBytesLength;
        if (ipBytesLength > 0) {
            byte[] ipBytes = ip.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(ipBytes, 0, data, offset, ipBytesLength);
            offset += ipBytesLength;
        }

        // 写入端口（大端序）
        data[offset++] = (byte) (port >> 24);
        data[offset++] = (byte) (port >> 16);
        data[offset++] = (byte) (port >> 8);
        data[offset++] = (byte) port;

        // 写入总线端口（大端序）
        data[offset++] = (byte) (busPort >> 24);
        data[offset++] = (byte) (busPort >> 16);
        data[offset++] = (byte) (busPort >> 8);
        data[offset++] = (byte) busPort;

        // 写入配置纪元（大端序）
        data[offset++] = (byte) (configEpoch >> 56);
        data[offset++] = (byte) (configEpoch >> 48);
        data[offset++] = (byte) (configEpoch >> 40);
        data[offset++] = (byte) (configEpoch >> 32);
        data[offset++] = (byte) (configEpoch >> 24);
        data[offset++] = (byte) (configEpoch >> 16);
        data[offset++] = (byte) (configEpoch >> 8);
        data[offset++] = (byte) configEpoch;

        // 写入状态标志数量
        data[offset++] = (byte) flagsCount;

        // 写入状态标志
        for (ClusterNodeState flag : flags) {
            short flagCode = (short) flag.ordinal();
            data[offset++] = (byte) (flagCode >> 8);
            data[offset++] = (byte) flagCode;
        }

        // 写入槽位字节数（4字节，大端序）+ 槽位位图
        data[offset++] = (byte) (slotsBytes.length >> 24);
        data[offset++] = (byte) (slotsBytes.length >> 16);
        data[offset++] = (byte) (slotsBytes.length >> 8);
        data[offset++] = (byte) slotsBytes.length;
        if (slotsBytes.length > 0) {
            System.arraycopy(slotsBytes, 0, data, offset, slotsBytes.length);
            offset += slotsBytes.length;
        }

        // 写入 masterNodeId：1 字节标志 + 可选 40 字节 node-id
        if (masterNodeId != null) {
            data[offset++] = 1;
            byte[] masterIdBytes = masterNodeId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(masterIdBytes, 0, data, offset, NODE_ID_LENGTH);
            offset += NODE_ID_LENGTH;
        } else {
            data[offset++] = 0;
        }

        return data;
    }

    /**
     * 从字节数组解码节点信息
     *
     * @param data   字节数组
     * @param offset 起始偏移量
     * @return 解码后的偏移量
     */
    public int decode(byte[] data, int offset) {
        if (data == null) {
            throw new IllegalArgumentException("解码数据为空");
        }
        // 定长部分最小长度：40(id) + 1(iplen) + 4(port) + 4(busport) + 8(epoch) + 1(flagsCount)
        final int minFixed = NODE_ID_LENGTH + 1 + 4 + 4 + 8 + 1;
        if (offset < 0 || offset > data.length - minFixed) {
            throw new IllegalArgumentException(
                    "GossipNodeInfo 定长部分数据不足: 需要 " + minFixed + " 字节");
        }

        // 读取节点ID，trim 尾部 0x00 填充后校验
        byte[] nodeIdBytes = new byte[NODE_ID_LENGTH];
        System.arraycopy(data, offset, nodeIdBytes, 0, NODE_ID_LENGTH);
        String rawNodeId = new String(nodeIdBytes, java.nio.charset.StandardCharsets.UTF_8);
        // 去除尾部填充的 0x00（兼容旧编码端可能的零填充）
        int idEnd = rawNodeId.indexOf(0);
        String trimmedNodeId = idEnd >= 0 ? rawNodeId.substring(0, idEnd) : rawNodeId;
        if (trimmedNodeId.isEmpty()) {
            this.nodeId = null;
        } else {
            // 走 setNodeId 做格式校验，非法则直接保留原始字符串避免丢失
            try {
                setNodeId(trimmedNodeId);
            } catch (IllegalArgumentException e) {
                this.nodeId = trimmedNodeId;
            }
        }
        offset += NODE_ID_LENGTH;

        // 读取IP地址长度和IP地址
        int ipLength = data[offset++] & 0xFF;
        if (offset + ipLength + 4 + 4 + 8 + 1 > data.length) {
            throw new IllegalArgumentException("GossipNodeInfo IP/端口段数据不足");
        }
        if (ipLength > 0) {
            byte[] ipBytes = new byte[ipLength];
            System.arraycopy(data, offset, ipBytes, 0, ipLength);
            this.ip = new String(ipBytes, java.nio.charset.StandardCharsets.UTF_8);
            offset += ipLength;
        }

        // 读取端口（大端序）
        this.port = ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);

        // 读取总线端口（大端序）
        this.busPort = ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);

        // 读取配置纪元（大端序）
        this.configEpoch = ((long) (data[offset++] & 0xFF) << 56) |
                ((long) (data[offset++] & 0xFF) << 48) |
                ((long) (data[offset++] & 0xFF) << 40) |
                ((long) (data[offset++] & 0xFF) << 32) |
                ((long) (data[offset++] & 0xFF) << 24) |
                ((long) (data[offset++] & 0xFF) << 16) |
                ((long) (data[offset++] & 0xFF) << 8) |
                ((data[offset++] & 0xFF));

        // 读取状态标志数量
        int flagsCount = data[offset++] & 0xFF;
        if (offset + flagsCount * 2L > data.length) {
            throw new IllegalArgumentException("GossipNodeInfo 状态标志段数据不足: flagsCount=" + flagsCount);
        }

        // 读取状态标志
        this.flags.clear();
        ClusterNodeState[] states = ClusterNodeState.values();
        for (int i = 0; i < flagsCount; i++) {
            short flagCode = (short) (((data[offset++] & 0xFF) << 8) | (data[offset++] & 0xFF));
            if (flagCode >= 0 && flagCode < states.length) {
                this.flags.add(states[flagCode]);
            }
        }

        // 读取槽位字节数（4字节，大端序）+ 槽位位图
        if (offset + 4 > data.length) {
            throw new IllegalArgumentException("GossipNodeInfo 槽位长度字段数据不足");
        }
        int slotsBytesLength = ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);
        if (slotsBytesLength < 0 || offset + slotsBytesLength > data.length) {
            throw new IllegalArgumentException(
                    "GossipNodeInfo 槽位位图数据不足: slotsBytesLength=" + slotsBytesLength);
        }
        if (slotsBytesLength > 0) {
            byte[] slotsBytes = new byte[slotsBytesLength];
            System.arraycopy(data, offset, slotsBytes, 0, slotsBytesLength);
            this.slots = BitSet.valueOf(slotsBytes);
            offset += slotsBytesLength;
        } else {
            this.slots = null;
        }

        // 读取 masterNodeId：1 字节标志 + 可选 40 字节 node-id
        if (offset + 1 <= data.length) {
            int hasMasterId = data[offset++] & 0xFF;
            if (hasMasterId == 1) {
                if (offset + NODE_ID_LENGTH > data.length) {
                    throw new IllegalArgumentException("GossipNodeInfo masterNodeId 数据不足");
                }
                byte[] masterIdBytes = new byte[NODE_ID_LENGTH];
                System.arraycopy(data, offset, masterIdBytes, 0, NODE_ID_LENGTH);
                String rawMasterId = new String(masterIdBytes, java.nio.charset.StandardCharsets.UTF_8);
                int mid = rawMasterId.indexOf(0);
                this.masterNodeId = mid >= 0 ? rawMasterId.substring(0, mid) : rawMasterId;
                if (this.masterNodeId.isEmpty()) {
                    this.masterNodeId = null;
                }
                offset += NODE_ID_LENGTH;
            } else if (hasMasterId != 0) {
                throw new IllegalArgumentException("GossipNodeInfo masterNodeId 标志非法: " + hasMasterId);
            } else {
                this.masterNodeId = null;
            }
        }

        return offset;
    }

    /**
     * 计算编码后的字节长度
     *
     * @return 字节长度
     */
    public int getEncodedLength() {
        int ipBytesLength = ip != null ? ip.getBytes(java.nio.charset.StandardCharsets.UTF_8).length : 0;
        int flagsCount = flags.size();
        int slotsBytesLength = slots != null ? slots.toByteArray().length : 0;
        int masterNodeIdLength = masterNodeId != null ? NODE_ID_LENGTH : 0;
        return NODE_ID_LENGTH + 1 + ipBytesLength + 4 + 4 + 8 + 1 + flagsCount * 2
                + 4 + slotsBytesLength + 1 + masterNodeIdLength;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        GossipNodeInfo that = (GossipNodeInfo) o;
        return Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId);
    }

    @Override
    public String toString() {
        return "GossipNodeInfo{" +
                "nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", port=" + port +
                ", busPort=" + busPort +
                ", configEpoch=" + configEpoch +
                ", flags=" + flags +
                ", masterNodeId='" + masterNodeId + '\'' +
                ", slotsCount=" + (slots != null ? slots.cardinality() : 0) +
                '}';
    }
}
