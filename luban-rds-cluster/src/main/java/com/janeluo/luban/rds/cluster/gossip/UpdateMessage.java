package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;

/**
 * UPDATE 消息
 * <p>
 * 配置更新通知，通知集群中其他节点配置变更
 * </p>
 * <p>
 * 消息体格式：
 * - 配置纪元（8 字节，大端序）
 * - 节点 ID（40 字节）
 * - IP 地址长度（1 字节）+ IP 地址（变长）
 * - 端口（4 字节，大端序）
 * - 总线端口（4 字节，大端序）
 * </p>
 */
public class UpdateMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 配置纪元
     */
    private long configEpoch;

    /**
     * 更新的节点ID
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
     * 集群总线端口
     */
    private int busPort;

    /**
     * 默认构造方法
     */
    public UpdateMessage() {
        this.type = GossipMessageType.UPDATE;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param configEpoch  配置纪元
     * @param nodeId       更新的节点ID
     * @param ip           节点IP
     * @param port         节点端口
     * @param busPort      集群总线端口
     */
    public UpdateMessage(String senderNodeId, long configEpoch, String nodeId,
                         String ip, int port, int busPort) {
        super(senderNodeId, GossipMessageType.UPDATE);
        this.configEpoch = configEpoch;
        this.nodeId = nodeId;
        this.ip = ip;
        this.port = port;
        this.busPort = busPort;
    }

    // ==================== Getter/Setter 方法 ====================

    public long getConfigEpoch() {
        return configEpoch;
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
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
        this.port = port;
    }

    public int getBusPort() {
        return busPort;
    }

    public void setBusPort(int busPort) {
        this.busPort = busPort;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] ipBytes = ip != null ? ip.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int ipLength = ipBytes.length;
        int totalLength = 8 + 40 + 1 + ipLength + 4 + 4;

        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入配置纪元（大端序）
        data[offset++] = (byte) (configEpoch >> 56);
        data[offset++] = (byte) (configEpoch >> 48);
        data[offset++] = (byte) (configEpoch >> 40);
        data[offset++] = (byte) (configEpoch >> 32);
        data[offset++] = (byte) (configEpoch >> 24);
        data[offset++] = (byte) (configEpoch >> 16);
        data[offset++] = (byte) (configEpoch >> 8);
        data[offset++] = (byte) configEpoch;

        // 写入节点ID（40字节）
        if (nodeId != null) {
            byte[] nodeIdBytes = nodeId.getBytes(StandardCharsets.UTF_8);
            int copyLength = Math.min(nodeIdBytes.length, 40);
            System.arraycopy(nodeIdBytes, 0, data, offset, copyLength);
        }
        offset += 40;

        // 写入IP地址长度和IP地址
        data[offset++] = (byte) ipLength;
        if (ipLength > 0) {
            System.arraycopy(ipBytes, 0, data, offset, ipLength);
            offset += ipLength;
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

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 57) {
            throw new IllegalArgumentException("UPDATE 消息体长度不足: 至少需要 57 字节，实际 "
                    + (body == null ? 0 : body.length));
        }

        int offset = 0;

        // 读取配置纪元（大端序）
        configEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取节点ID（40字节）
        byte[] nodeIdBytes = new byte[40];
        System.arraycopy(body, offset, nodeIdBytes, 0, 40);
        this.nodeId = new String(nodeIdBytes, StandardCharsets.UTF_8).trim();
        offset += 40;

        // 读取IP地址长度和IP地址
        int ipLength = body[offset++] & 0xFF;
        if (offset + ipLength + 8 > body.length) {
            throw new IllegalArgumentException("UPDATE 消息 IP/端口段数据不足: ipLength=" + ipLength);
        }
        if (ipLength > 0) {
            byte[] ipBytes = new byte[ipLength];
            System.arraycopy(body, offset, ipBytes, 0, ipLength);
            this.ip = new String(ipBytes, StandardCharsets.UTF_8);
            offset += ipLength;
        }

        // 读取端口（大端序）
        this.port = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);

        // 读取总线端口（大端序）
        this.busPort = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
    }

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
    public String toString() {
        return "UpdateMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", configEpoch=" + configEpoch +
                ", nodeId='" + nodeId + '\'' +
                ", ip='" + ip + '\'' +
                ", port=" + port +
                ", busPort=" + busPort +
                '}';
    }
}
