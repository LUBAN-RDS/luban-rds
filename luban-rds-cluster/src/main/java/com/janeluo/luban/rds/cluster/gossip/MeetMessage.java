package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

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
     * 默认构造方法
     */
    public MeetMessage() {
        this.type = GossipMessageType.MEET;
        this.gossipNodes = new ArrayList<>();
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

        int totalLength = 1 + ipLength + 4 + 4 + 8 + 8 + 2 + gossipNodesLength;
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

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 27) {
            return;
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
                ((long) (body[offset++] & 0xFF));

        // 读取当前集群纪元（大端序）
        this.currentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
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
