package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * PING 消息
 * <p>
 * 用于心跳检测和节点信息交换，是集群节点间最频繁的消息类型
 * </p>
 * <p>
 * 消息体格式：
 * - PING 时间戳（8 字节，大端序）
 * - Gossip 节点数量（2 字节，大端序）
 * - Gossip 节点信息列表（变长）
 * </p>
 */
public class PingMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * PING 时间戳（毫秒）
     */
    private long pingTime;

    /**
     * Gossip 节点信息列表（随机选择的节点信息，用于传播集群状态）
     */
    private List<GossipNodeInfo> gossipNodes;

    /**
     * 发送方（myNode）拥有的槽位集合
     * <p>
     * 由于 {@code selectGossipNodes} 排除本节点，发送方自己的槽位无法经 gossip section 传播，
     * 因此在消息头显式携带，使接收方能同步发送方的槽位归属。
     * </p>
     */
    private BitSet senderSlots;

    /**
     * 发送方（myNode）配置纪元
     * <p>
     * 与 {@link #senderFlags}、{@link #senderMasterNodeId} 配合，使接收方能基于纪元裁决
     * 同步发送方角色。{@code selectGossipNodes} 排除本节点，发送方自己的角色无法经
     * gossip section 传播，因此必须在消息头显式携带。
     * </p>
     */
    private long senderConfigEpoch;

    /**
     * 发送方（myNode）角色状态标志（MASTER/SLAVE 等）
     */
    private Set<ClusterNodeState> senderFlags;

    /**
     * 发送方（myNode）的主节点ID，仅从节点有效；主节点为 null
     */
    private String senderMasterNodeId;

    /**
     * 发送方（myNode）所在的集群当前纪元（currentEpoch）
     * <p>
     * 使接收方能通过心跳同步集群级 currentEpoch。重启节点本地 currentEpoch 可能滞后，
     * 导致 epoch 仲裁门控恒为 false。尾部追加字段，旧版本节点解码时忽略多余字节，
     * 新版本节点解码旧消息时字段不足则保留默认值 0（setEpochIfGreater(0) 无副作用）。
     * </p>
     */
    private long senderCurrentEpoch;

    /**
     * 发送方（myNode）的复制偏移量（P1-6）。
     * <p>
     * slave 填已同步偏移量，master 填 0 或自身偏移。供对端维护本节点的 replOffset，
     * 用于 failover rank 退避计算。尾部追加字段，向后兼容（同 senderCurrentEpoch 模式）。
     * </p>
     */
    private long senderReplicationOffset;

    /**
     * 默认构造方法
     */
    public PingMessage() {
        this.type = GossipMessageType.PING;
        this.gossipNodes = new ArrayList<>();
        this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param pingTime     PING 时间戳
     */
    public PingMessage(String senderNodeId, long pingTime) {
        super(senderNodeId, GossipMessageType.PING);
        this.pingTime = pingTime;
        this.gossipNodes = new ArrayList<>();
        this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 完整构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param pingTime     PING 时间戳
     * @param gossipNodes  Gossip 节点信息列表
     */
    public PingMessage(String senderNodeId, long pingTime, List<GossipNodeInfo> gossipNodes) {
        super(senderNodeId, GossipMessageType.PING);
        this.pingTime = pingTime;
        this.gossipNodes = gossipNodes != null ? new ArrayList<>(gossipNodes) : new ArrayList<>();
        this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
    }

    // ==================== Getter/Setter 方法 ====================

    public long getPingTime() {
        return pingTime;
    }

    public void setPingTime(long pingTime) {
        this.pingTime = pingTime;
    }

    public List<GossipNodeInfo> getGossipNodes() {
        return gossipNodes;
    }

    public void setGossipNodes(List<GossipNodeInfo> gossipNodes) {
        this.gossipNodes = gossipNodes != null ? new ArrayList<>(gossipNodes) : new ArrayList<>();
    }

    /**
     * 获取发送方拥有的槽位集合
     *
     * @return 槽位集合，可能为 null
     */
    public BitSet getSenderSlots() {
        return senderSlots;
    }

    /**
     * 设置发送方拥有的槽位集合
     *
     * @param senderSlots 槽位集合
     */
    public void setSenderSlots(BitSet senderSlots) {
        this.senderSlots = senderSlots;
    }

    /**
     * 获取发送方配置纪元
     *
     * @return 配置纪元
     */
    public long getSenderConfigEpoch() {
        return senderConfigEpoch;
    }

    /**
     * 设置发送方配置纪元
     *
     * @param senderConfigEpoch 配置纪元
     */
    public void setSenderConfigEpoch(long senderConfigEpoch) {
        this.senderConfigEpoch = senderConfigEpoch;
    }

    /**
     * 获取发送方角色状态标志
     *
     * @return 状态标志集合，不为 null
     */
    public Set<ClusterNodeState> getSenderFlags() {
        return senderFlags;
    }

    /**
     * 设置发送方角色状态标志
     *
     * @param senderFlags 状态标志集合，null 视为空集
     */
    public void setSenderFlags(Set<ClusterNodeState> senderFlags) {
        this.senderFlags = senderFlags != null ? EnumSet.copyOf(senderFlags) : EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 获取发送方主节点ID
     *
     * @return 主节点ID，主节点或未知时为 null
     */
    public String getSenderMasterNodeId() {
        return senderMasterNodeId;
    }

    /**
     * 设置发送方主节点ID
     *
     * @param senderMasterNodeId 主节点ID
     */
    public void setSenderMasterNodeId(String senderMasterNodeId) {
        this.senderMasterNodeId = senderMasterNodeId;
    }

    /**
     * 获取发送方集群当前纪元
     *
     * @return 集群当前纪元
     */
    public long getSenderCurrentEpoch() {
        return senderCurrentEpoch;
    }

    /**
     * 设置发送方集群当前纪元
     *
     * @param senderCurrentEpoch 集群当前纪元
     */
    public void setSenderCurrentEpoch(long senderCurrentEpoch) {
        this.senderCurrentEpoch = senderCurrentEpoch;
    }

    /**
     * 获取发送方复制偏移量（P1-6）。
     *
     * @return 复制偏移量
     */
    public long getSenderReplicationOffset() {
        return senderReplicationOffset;
    }

    /**
     * 设置发送方复制偏移量（P1-6）。
     *
     * @param senderReplicationOffset 复制偏移量
     */
    public void setSenderReplicationOffset(long senderReplicationOffset) {
        this.senderReplicationOffset = senderReplicationOffset;
    }

    /**
     * 添加 Gossip 节点信息
     *
     * @param nodeInfo 节点信息
     */
    public void addGossipNode(GossipNodeInfo nodeInfo) {
        if (nodeInfo != null) {
            this.gossipNodes.add(nodeInfo);
        }
    }

    /**
     * 获取 Gossip 节点数量
     *
     * @return 节点数量
     */
    public int getGossipNodeCount() {
        return gossipNodes.size();
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        // 计算总长度
        int gossipNodesCount = gossipNodes.size();
        int gossipNodesLength = 0;
        for (GossipNodeInfo nodeInfo : gossipNodes) {
            gossipNodesLength += nodeInfo.getEncodedLength();
        }
        byte[] slotsBytes = senderSlots != null ? senderSlots.toByteArray() : new byte[0];

        int flagsCount = senderFlags.size();
        int masterNodeIdLength = senderMasterNodeId != null ? GossipNodeInfo.NODE_ID_LENGTH : 0;

        int totalLength = 8 + 2 + gossipNodesLength + 4 + slotsBytes.length
                + 8 + 1 + flagsCount * 2 + 1 + masterNodeIdLength + 8
                + 8; // +8：senderReplicationOffset（P1-6，尾部追加）
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入 PING 时间戳（大端序）
        data[offset++] = (byte) (pingTime >> 56);
        data[offset++] = (byte) (pingTime >> 48);
        data[offset++] = (byte) (pingTime >> 40);
        data[offset++] = (byte) (pingTime >> 32);
        data[offset++] = (byte) (pingTime >> 24);
        data[offset++] = (byte) (pingTime >> 16);
        data[offset++] = (byte) (pingTime >> 8);
        data[offset++] = (byte) pingTime;

        // 写入 Gossip 节点数量（大端序）
        data[offset++] = (byte) (gossipNodesCount >> 8);
        data[offset++] = (byte) gossipNodesCount;

        // 写入 Gossip 节点信息
        for (GossipNodeInfo nodeInfo : gossipNodes) {
            byte[] nodeData = nodeInfo.encode();
            System.arraycopy(nodeData, 0, data, offset, nodeData.length);
            offset += nodeData.length;
        }

        // 写入发送方槽位集合（4字节长度 + 位图）
        data[offset++] = (byte) (slotsBytes.length >> 24);
        data[offset++] = (byte) (slotsBytes.length >> 16);
        data[offset++] = (byte) (slotsBytes.length >> 8);
        data[offset++] = (byte) slotsBytes.length;
        if (slotsBytes.length > 0) {
            System.arraycopy(slotsBytes, 0, data, offset, slotsBytes.length);
            offset += slotsBytes.length;
        }

        // 写入发送方配置纪元（8字节，大端序）
        data[offset++] = (byte) (senderConfigEpoch >> 56);
        data[offset++] = (byte) (senderConfigEpoch >> 48);
        data[offset++] = (byte) (senderConfigEpoch >> 40);
        data[offset++] = (byte) (senderConfigEpoch >> 32);
        data[offset++] = (byte) (senderConfigEpoch >> 24);
        data[offset++] = (byte) (senderConfigEpoch >> 16);
        data[offset++] = (byte) (senderConfigEpoch >> 8);
        data[offset++] = (byte) senderConfigEpoch;

        // 写入发送方角色状态标志（1字节数量 + 每个2字节）
        data[offset++] = (byte) flagsCount;
        for (ClusterNodeState flag : senderFlags) {
            short flagCode = (short) flag.ordinal();
            data[offset++] = (byte) (flagCode >> 8);
            data[offset++] = (byte) flagCode;
        }

        // 写入发送方 masterNodeId（1字节标志 + 可选40字节）
        if (senderMasterNodeId != null) {
            data[offset++] = 1;
            byte[] masterIdBytes = senderMasterNodeId.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(masterIdBytes, 0, data, offset, GossipNodeInfo.NODE_ID_LENGTH);
            offset += GossipNodeInfo.NODE_ID_LENGTH;
        } else {
            data[offset++] = 0;
        }

        // 写入发送方集群当前纪元（8字节，大端序）- 尾部追加，向后兼容
        data[offset++] = (byte) (senderCurrentEpoch >> 56);
        data[offset++] = (byte) (senderCurrentEpoch >> 48);
        data[offset++] = (byte) (senderCurrentEpoch >> 40);
        data[offset++] = (byte) (senderCurrentEpoch >> 32);
        data[offset++] = (byte) (senderCurrentEpoch >> 24);
        data[offset++] = (byte) (senderCurrentEpoch >> 16);
        data[offset++] = (byte) (senderCurrentEpoch >> 8);
        data[offset++] = (byte) senderCurrentEpoch;

        // 写入发送方复制偏移量（8字节，大端序，P1-6，尾部追加，向后兼容）
        data[offset++] = (byte) (senderReplicationOffset >> 56);
        data[offset++] = (byte) (senderReplicationOffset >> 48);
        data[offset++] = (byte) (senderReplicationOffset >> 40);
        data[offset++] = (byte) (senderReplicationOffset >> 32);
        data[offset++] = (byte) (senderReplicationOffset >> 24);
        data[offset++] = (byte) (senderReplicationOffset >> 16);
        data[offset++] = (byte) (senderReplicationOffset >> 8);
        data[offset++] = (byte) senderReplicationOffset;

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 10) {
            throw new IllegalArgumentException("PING 消息体长度不足: 至少需要 10 字节，实际 "
                    + (body == null ? 0 : body.length));
        }

        int offset = 0;

        // 读取 PING 时间戳（大端序）
        pingTime = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取 Gossip 节点数量（大端序）
        int gossipNodesCount = ((body[offset++] & 0xFF) << 8) | (body[offset++] & 0xFF);

        // 读取 Gossip 节点信息
        this.gossipNodes = new ArrayList<>(gossipNodesCount);
        for (int i = 0; i < gossipNodesCount; i++) {
            GossipNodeInfo nodeInfo = new GossipNodeInfo();
            offset = nodeInfo.decode(body, offset);
            this.gossipNodes.add(nodeInfo);
        }

        // 读取发送方槽位集合（4字节长度 + 位图）
        if (offset + 4 <= body.length) {
            int slotsBytesLength = ((body[offset++] & 0xFF) << 24) |
                    ((body[offset++] & 0xFF) << 16) |
                    ((body[offset++] & 0xFF) << 8) |
                    (body[offset++] & 0xFF);
            if (slotsBytesLength > 0 && offset + slotsBytesLength <= body.length) {
                byte[] slotsBytes = new byte[slotsBytesLength];
                System.arraycopy(body, offset, slotsBytes, 0, slotsBytesLength);
                this.senderSlots = BitSet.valueOf(slotsBytes);
                offset += slotsBytesLength;
            }
        }

        // 读取发送方配置纪元（8字节，大端序）
        if (offset + 8 <= body.length) {
            this.senderConfigEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                    ((long) (body[offset++] & 0xFF) << 48) |
                    ((long) (body[offset++] & 0xFF) << 40) |
                    ((long) (body[offset++] & 0xFF) << 32) |
                    ((long) (body[offset++] & 0xFF) << 24) |
                    ((long) (body[offset++] & 0xFF) << 16) |
                    ((long) (body[offset++] & 0xFF) << 8) |
                    ((long) (body[offset++] & 0xFF));
        }

        // 读取发送方角色状态标志（1字节数量 + 每个2字节）
        if (this.senderFlags == null) {
            this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
        } else {
            this.senderFlags.clear();
        }
        if (offset + 1 <= body.length) {
            int flagsCount = body[offset++] & 0xFF;
            if (offset + flagsCount * 2L <= body.length) {
                ClusterNodeState[] states = ClusterNodeState.values();
                for (int i = 0; i < flagsCount; i++) {
                    short flagCode = (short) (((body[offset++] & 0xFF) << 8) | (body[offset++] & 0xFF));
                    if (flagCode >= 0 && flagCode < states.length) {
                        this.senderFlags.add(states[flagCode]);
                    }
                }
            }
        }

        // 读取发送方 masterNodeId（1字节标志 + 可选40字节）
        if (offset + 1 <= body.length) {
            int hasMasterId = body[offset++] & 0xFF;
            if (hasMasterId == 1) {
                if (offset + GossipNodeInfo.NODE_ID_LENGTH <= body.length) {
                    byte[] masterIdBytes = new byte[GossipNodeInfo.NODE_ID_LENGTH];
                    System.arraycopy(body, offset, masterIdBytes, 0, GossipNodeInfo.NODE_ID_LENGTH);
                    String rawMasterId = new String(masterIdBytes, StandardCharsets.UTF_8);
                    int mid = rawMasterId.indexOf(0);
                    this.senderMasterNodeId = mid >= 0 ? rawMasterId.substring(0, mid) : rawMasterId;
                    if (this.senderMasterNodeId.isEmpty()) {
                        this.senderMasterNodeId = null;
                    }
                    offset += GossipNodeInfo.NODE_ID_LENGTH;
                }
            }
        }

        // 读取发送方集群当前纪元（8字节，大端序）- 向后兼容：旧消息无此字段时保留默认值 0
        if (offset + 8 <= body.length) {
            this.senderCurrentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                    ((long) (body[offset++] & 0xFF) << 48) |
                    ((long) (body[offset++] & 0xFF) << 40) |
                    ((long) (body[offset++] & 0xFF) << 32) |
                    ((long) (body[offset++] & 0xFF) << 24) |
                    ((long) (body[offset++] & 0xFF) << 16) |
                    ((long) (body[offset++] & 0xFF) << 8) |
                    ((long) (body[offset++] & 0xFF));
        }

        // 读取发送方复制偏移量（8字节，大端序，P1-6，尾部追加，向后兼容：旧消息无此字段时保留默认值 0）
        if (offset + 8 <= body.length) {
            this.senderReplicationOffset = ((long) (body[offset++] & 0xFF) << 56) |
                    ((long) (body[offset++] & 0xFF) << 48) |
                    ((long) (body[offset++] & 0xFF) << 40) |
                    ((long) (body[offset++] & 0xFF) << 32) |
                    ((long) (body[offset++] & 0xFF) << 24) |
                    ((long) (body[offset++] & 0xFF) << 16) |
                    ((long) (body[offset++] & 0xFF) << 8) |
                    ((long) (body[offset++] & 0xFF));
        }
    }

    @Override
    public String toString() {
        return "PingMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", pingTime=" + pingTime +
                ", gossipNodesCount=" + getGossipNodeCount() +
                '}';
    }
}
