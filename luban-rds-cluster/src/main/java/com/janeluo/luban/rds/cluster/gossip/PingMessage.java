package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
     * 默认构造方法
     */
    public PingMessage() {
        this.type = GossipMessageType.PING;
        this.gossipNodes = new ArrayList<>();
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

    // ==================== 节点管理方法 ====================

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

        int totalLength = 8 + 2 + gossipNodesLength;
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

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 10) {
            return;
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
