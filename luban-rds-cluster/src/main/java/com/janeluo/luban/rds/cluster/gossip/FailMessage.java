package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;

/**
 * FAIL 消息
 * <p>
 * 用于广播节点下线状态，通知集群中其他节点某个节点已下线
 * </p>
 * <p>
 * 消息体格式：
 * - 故障节点ID（40字节）
 * - 故障节点IP长度（1字节）+ 故障节点IP（变长）
 * - 故障节点端口（4字节，大端序）
 * </p>
 */
public class FailMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 故障节点ID
     */
    private String failedNodeId;

    /**
     * 故障节点IP
     */
    private String failedNodeIp;

    /**
     * 故障节点端口
     */
    private int failedNodePort;

    /**
     * 默认构造方法
     */
    public FailMessage() {
        this.type = GossipMessageType.FAIL;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId  发送方节点ID
     * @param failedNodeId  故障节点ID
     * @param failedNodeIp  故障节点IP
     * @param failedNodePort 故障节点端口
     */
    public FailMessage(String senderNodeId, String failedNodeId, 
                       String failedNodeIp, int failedNodePort) {
        super(senderNodeId, GossipMessageType.FAIL);
        this.failedNodeId = failedNodeId;
        this.failedNodeIp = failedNodeIp;
        this.failedNodePort = failedNodePort;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getFailedNodeId() {
        return failedNodeId;
    }

    public void setFailedNodeId(String failedNodeId) {
        this.failedNodeId = failedNodeId;
    }

    public String getFailedNodeIp() {
        return failedNodeIp;
    }

    public void setFailedNodeIp(String failedNodeIp) {
        this.failedNodeIp = failedNodeIp;
    }

    public int getFailedNodePort() {
        return failedNodePort;
    }

    public void setFailedNodePort(int failedNodePort) {
        this.failedNodePort = failedNodePort;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] ipBytes = failedNodeIp != null ? failedNodeIp.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int ipLength = ipBytes.length;

        int totalLength = 40 + 1 + ipLength + 4;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入故障节点ID（40字节）
        if (failedNodeId != null) {
            byte[] nodeIdBytes = failedNodeId.getBytes(StandardCharsets.UTF_8);
            int copyLength = Math.min(nodeIdBytes.length, 40);
            System.arraycopy(nodeIdBytes, 0, data, offset, copyLength);
        }
        offset += 40;

        // 写入故障节点IP长度和IP
        data[offset++] = (byte) ipLength;
        if (ipLength > 0) {
            System.arraycopy(ipBytes, 0, data, offset, ipLength);
            offset += ipLength;
        }

        // 写入故障节点端口（大端序）
        data[offset++] = (byte) (failedNodePort >> 24);
        data[offset++] = (byte) (failedNodePort >> 16);
        data[offset++] = (byte) (failedNodePort >> 8);
        data[offset++] = (byte) failedNodePort;

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 45) {
            throw new IllegalArgumentException("FAIL 消息体长度不足: 需要 45 字节，实际 "
                    + (body == null ? 0 : body.length));
        }

        int offset = 0;

        // 读取故障节点ID（40字节）
        byte[] nodeIdBytes = new byte[40];
        System.arraycopy(body, offset, nodeIdBytes, 0, 40);
        this.failedNodeId = new String(nodeIdBytes, StandardCharsets.UTF_8).trim();
        offset += 40;

        // 读取故障节点IP长度和IP
        int ipLength = body[offset++] & 0xFF;
        if (offset + ipLength + 4 > body.length) {
            throw new IllegalArgumentException("FAIL 消息 IP/端口段数据不足");
        }
        if (ipLength > 0) {
            byte[] ipBytes = new byte[ipLength];
            System.arraycopy(body, offset, ipBytes, 0, ipLength);
            this.failedNodeIp = new String(ipBytes, StandardCharsets.UTF_8);
            offset += ipLength;
        }

        // 读取故障节点端口（大端序）
        this.failedNodePort = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
    }

    /**
     * 获取故障节点地址字符串（ip:port格式）
     *
     * @return 地址字符串
     */
    public String getFailedNodeAddress() {
        return failedNodeIp + ":" + failedNodePort;
    }

    @Override
    public String toString() {
        return "FailMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", failedNodeId='" + failedNodeId + '\'' +
                ", failedNodeIp='" + failedNodeIp + '\'' +
                ", failedNodePort=" + failedNodePort +
                '}';
    }
}
