package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;

/**
 * PUBLISH 消息
 * <p>
 * 用于在集群间传播发布订阅消息
 * </p>
 * <p>
 * 消息体格式：
 * - 频道名长度（2 字节，大端序）+ 频道名（变长）
 * - 消息长度（4 字节，大端序）+ 消息内容（变长）
 * </p>
 */
public class PublishMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 频道名
     */
    private String channel;

    /**
     * 消息内容
     */
    private byte[] message;

    /**
     * 默认构造方法
     */
    public PublishMessage() {
        this.type = GossipMessageType.PUBLISH;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param channel      频道名
     * @param message      消息内容
     */
    public PublishMessage(String senderNodeId, String channel, byte[] message) {
        super(senderNodeId, GossipMessageType.PUBLISH);
        this.channel = channel;
        this.message = message;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public byte[] getMessage() {
        return message;
    }

    public void setMessage(byte[] message) {
        this.message = message;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] channelBytes = channel != null ? channel.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int channelLength = channelBytes.length;
        int messageLength = message != null ? message.length : 0;

        int totalLength = 2 + channelLength + 4 + messageLength;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入频道名长度和频道名
        data[offset++] = (byte) (channelLength >> 8);
        data[offset++] = (byte) channelLength;
        if (channelLength > 0) {
            System.arraycopy(channelBytes, 0, data, offset, channelLength);
            offset += channelLength;
        }

        // 写入消息长度和消息内容
        data[offset++] = (byte) (messageLength >> 24);
        data[offset++] = (byte) (messageLength >> 16);
        data[offset++] = (byte) (messageLength >> 8);
        data[offset++] = (byte) messageLength;
        if (messageLength > 0) {
            System.arraycopy(message, 0, data, offset, messageLength);
        }

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 6) {
            throw new IllegalArgumentException("PUBLISH 消息体长度不足: 至少需要 6 字节，实际 "
                    + (body == null ? 0 : body.length));
        }

        int offset = 0;

        // 读取频道名长度和频道名
        int channelLength = ((body[offset++] & 0xFF) << 8) | (body[offset++] & 0xFF);
        if (offset + channelLength + 4 > body.length) {
            throw new IllegalArgumentException("PUBLISH 消息频道段数据不足: channelLength=" + channelLength);
        }
        if (channelLength > 0) {
            byte[] channelBytes = new byte[channelLength];
            System.arraycopy(body, offset, channelBytes, 0, channelLength);
            this.channel = new String(channelBytes, StandardCharsets.UTF_8);
            offset += channelLength;
        }

        // 读取消息长度和消息内容
        int messageLength = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
        if (messageLength < 0 || offset + messageLength > body.length) {
            throw new IllegalArgumentException("PUBLISH 消息内容段数据不足: messageLength=" + messageLength);
        }
        if (messageLength > 0) {
            this.message = new byte[messageLength];
            System.arraycopy(body, offset, this.message, 0, messageLength);
        }
    }

    @Override
    public String toString() {
        return "PublishMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", channel='" + channel + '\'' +
                ", messageLength=" + (message != null ? message.length : 0) +
                '}';
    }
}
