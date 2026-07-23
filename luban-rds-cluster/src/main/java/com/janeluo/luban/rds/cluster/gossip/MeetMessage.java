package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * MEET 消息
 * <p>
 * 用于新节点加入集群时向已知节点发送的握手消息
 * </p>
 * <p>
 * 消息体格式：
 * - 发送方IP长度（1字节）+ 发送方IP（变长）
 * - 发送方端口（4字节，大端序）
 * - 发送方总线端口（4字节，大端序）
 * - 发送方配置纪元（8字节，大端序）
 * - 当前集群纪元（8字节，大端序）
 * - Gossip节点数量（2字节，大端序）
 * - Gossip节点信息列表（变长）
 * </p>
 */
public class MeetMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 发送方IP地址
     */
    private String senderIp;

    /**
     * 发送方端口
     */
    private int senderPort;

    /**
     * 发送方集群总线端口
     */
    private int senderBusPort;

    /**
     * 发送方配置纪元
     */
    private long senderConfigEpoch;

    /**
     * 当前集群纪元
     */
    private long currentEpoch;

    /**
     * Gossip节点信息列表
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
     * 发送方（myNode）角色状态标志（MASTER/SLAVE 等）
     * <p>
     * 与 {@link #senderConfigEpoch}、{@link #senderMasterNodeId} 配合，使接收方能基于纪元裁决
     * 同步发送方角色。{@code selectGossipNodes} 排除本节点，发送方自己的角色无法经
     * gossip section 传播，因此必须在消息头显式携带。
     * </p>
     */
    private Set<ClusterNodeState> senderFlags;

    /**
     * 发送方（myNode）的主节点ID，仅从节点有效；主节点为 null
     */
    private String senderMasterNodeId;

    /**
     * 默认构造方法
     */
    public MeetMessage() {
        this.type = GossipMessageType.MEET;
        this.gossipNodes = new ArrayList<>();
        this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId      发送方节点ID
     * @param senderIp          发送方IP
     * @param senderPort        发送方端口
     * @param senderBusPort     发送方总线端口
     * @param senderConfigEpoch 发送方配置纪元
     * @param currentEpoch      当前集群纪元
     */
    public MeetMessage(String senderNodeId, String senderIp, int senderPort,
                       int senderBusPort, long senderConfigEpoch, long currentEpoch) {
        super(senderNodeId, GossipMessageType.MEET);
        this.senderIp = senderIp;
        this.senderPort = senderPort;
        this.senderBusPort = senderBusPort;
        this.senderConfigEpoch = senderConfigEpoch;
        this.currentEpoch = currentEpoch;
        this.gossipNodes = new ArrayList<>();
        this.senderFlags = EnumSet.noneOf(ClusterNodeState.class);
    }

    // ==================== Getter/Setter 方法 ====================

    public String getSenderIp() {
        return senderIp;
    }

    public void setSenderIp(String senderIp) {
        this.senderIp = senderIp;
    }

    public int getSenderPort() {
        return senderPort;
    }

    public void setSenderPort(int senderPort) {
        this.senderPort = senderPort;
    }

    public int getSenderBusPort() {
        return senderBusPort;
    }

    public void setSenderBusPort(int senderBusPort) {
        this.senderBusPort = senderBusPort;
    }

    public long getSenderConfigEpoch() {
        return senderConfigEpoch;
    }

    public void setSenderConfigEpoch(long senderConfigEpoch) {
        this.senderConfigEpoch = senderConfigEpoch;
    }

    public long getCurrentEpoch() {
        return currentEpoch;
    }

    public void setCurrentEpoch(long currentEpoch) {
        this.currentEpoch = currentEpoch;
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
     * 添加 Gossip 节点信息
     *
     * @param nodeInfo 节点信息
     */
    public void addGossipNode(GossipNodeInfo nodeInfo) {
        if (nodeInfo != null) {
            this.gossipNodes.add(nodeInfo);
        }
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] ipBytes = senderIp != null ? senderIp.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int ipLength = ipBytes.length;

        int gossipNodesLength = 0;
        for (GossipNodeInfo nodeInfo : gossipNodes) {
            gossipNodesLength += nodeInfo.getEncodedLength();
        }
        byte[] slotsBytes = senderSlots != null ? senderSlots.toByteArray() : new byte[0];

        int flagsCount = senderFlags.size();
        int masterNodeIdLength = senderMasterNodeId != null ? GossipNodeInfo.NODE_ID_LENGTH : 0;

        int totalLength = 1 + ipLength + 4 + 4 + 8 + 8 + 2 + gossipNodesLength + 4 + slotsBytes.length
                + 1 + flagsCount * 2 + 1 + masterNodeIdLength;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入发送方IP长度和IP
        data[offset++] = (byte) ipLength;
        if (ipLength > 0) {
            System.arraycopy(ipBytes, 0, data, offset, ipLength);
            offset += ipLength;
        }

        // 写入发送方端口（大端序）
        data[offset++] = (byte) (senderPort >> 24);
        data[offset++] = (byte) (senderPort >> 16);
        data[offset++] = (byte) (senderPort >> 8);
        data[offset++] = (byte) senderPort;

        // 写入发送方总线端口（大端序）
        data[offset++] = (byte) (senderBusPort >> 24);
        data[offset++] = (byte) (senderBusPort >> 16);
        data[offset++] = (byte) (senderBusPort >> 8);
        data[offset++] = (byte) senderBusPort;

        // 写入发送方配置纪元（大端序）
        data[offset++] = (byte) (senderConfigEpoch >> 56);
        data[offset++] = (byte) (senderConfigEpoch >> 48);
        data[offset++] = (byte) (senderConfigEpoch >> 40);
        data[offset++] = (byte) (senderConfigEpoch >> 32);
        data[offset++] = (byte) (senderConfigEpoch >> 24);
        data[offset++] = (byte) (senderConfigEpoch >> 16);
        data[offset++] = (byte) (senderConfigEpoch >> 8);
        data[offset++] = (byte) senderConfigEpoch;

        // 写入当前集群纪元（大端序）
        data[offset++] = (byte) (currentEpoch >> 56);
        data[offset++] = (byte) (currentEpoch >> 48);
        data[offset++] = (byte) (currentEpoch >> 40);
        data[offset++] = (byte) (currentEpoch >> 32);
        data[offset++] = (byte) (currentEpoch >> 24);
        data[offset++] = (byte) (currentEpoch >> 16);
        data[offset++] = (byte) (currentEpoch >> 8);
        data[offset++] = (byte) currentEpoch;

        // 写入 Gossip 节点数量（大端序）
        int gossipNodesCount = gossipNodes.size();
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

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 27) {
            throw new IllegalArgumentException("MEET 消息体长度不足: 至少需要 27 字节，实际 "
                    + (body == null ? 0 : body.length));
        }

        int offset = 0;

        // 读取发送方IP长度和IP
        int ipLength = body[offset++] & 0xFF;
        if (ipLength > 0) {
            byte[] ipBytes = new byte[ipLength];
            System.arraycopy(body, offset, ipBytes, 0, ipLength);
            this.senderIp = new String(ipBytes, StandardCharsets.UTF_8);
            offset += ipLength;
        }

        // 读取发送方端口（大端序）
        this.senderPort = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);

        // 读取发送方总线端口（大端序）
        this.senderBusPort = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);

        // 读取发送方配置纪元（大端序）
        this.senderConfigEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((body[offset++] & 0xFF));

        // 读取当前集群纪元（大端序）
        this.currentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((body[offset++] & 0xFF));

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
    }

    /**
     * 获取发送方地址字符串（ip:port格式）
     *
     * @return 地址字符串
     */
    public String getSenderAddress() {
        return senderIp + ":" + senderPort;
    }

    /**
     * 获取发送方完整地址字符串（ip:port@busPort格式）
     *
     * @return 完整地址字符串
     */
    public String getSenderFullAddress() {
        return senderIp + ":" + senderPort + "@" + senderBusPort;
    }

    @Override
    public String toString() {
        return "MeetMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", senderIp='" + senderIp + '\'' +
                ", senderPort=" + senderPort +
                ", senderBusPort=" + senderBusPort +
                ", senderConfigEpoch=" + senderConfigEpoch +
                ", currentEpoch=" + currentEpoch +
                ", gossipNodesCount=" + gossipNodes.size() +
                '}';
    }
}
