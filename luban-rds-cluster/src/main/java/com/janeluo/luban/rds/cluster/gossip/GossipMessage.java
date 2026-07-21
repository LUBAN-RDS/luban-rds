package com.janeluo.luban.rds.cluster.gossip;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * Gossip 消息基类
 * <p>
 * 定义集群节点间通信的消息格式，所有具体的消息类型都继承此类
 * </p>
 * <p>
 * 消息格式：
 * - 发送者节点 ID（40 字节）
 * - 消息类型（1 字节）
 * - 消息长度（4 字节，大端序）
 * - 消息体（变长）
 * </p>
 */
public abstract class GossipMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息头固定长度：40（节点ID）+ 1（消息类型）+ 4（消息长度）= 45 字节
     */
    public static final int HEADER_LENGTH = 45;

    /**
     * 节点ID长度
     */
    public static final int NODE_ID_LENGTH = 40;

    /**
     * 发送者节点ID（40字符十六进制字符串）
     */
    protected String senderNodeId;

    /**
     * 消息类型
     */
    protected GossipMessageType type;

    /**
     * 默认构造方法
     */
    public GossipMessage() {
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param type         消息类型
     */
    public GossipMessage(String senderNodeId, GossipMessageType type) {
        setSenderNodeId(senderNodeId);
        this.type = type;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getSenderNodeId() {
        return senderNodeId;
    }

    /**
     * 设置发送者节点ID
     *
     * @param senderNodeId 发送者节点ID（必须是40字符的十六进制字符串）
     * @throws IllegalArgumentException 如果节点ID格式不正确
     */
    public void setSenderNodeId(String senderNodeId) {
        if (senderNodeId != null && senderNodeId.length() != NODE_ID_LENGTH) {
            throw new IllegalArgumentException(
                    "节点ID长度必须为" + NODE_ID_LENGTH + "字符，当前长度: " + senderNodeId.length());
        }
        if (senderNodeId != null && !senderNodeId.matches("[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("节点ID必须为十六进制字符串");
        }
        this.senderNodeId = senderNodeId;
    }

    public GossipMessageType getType() {
        return type;
    }

    public void setType(GossipMessageType type) {
        this.type = type;
    }

    // ==================== 编解码方法 ====================

    /**
     * 将消息编码为字节数组
     * <p>
     * 编码格式：
     * - 发送者节点ID（40字节）
     * - 消息类型（1字节）
     * - 消息长度（4字节，大端序）
     * - 消息体（变长）
     * </p>
     *
     * @return 编码后的字节数组
     */
    public byte[] encode() {
        // 编码消息体
        byte[] body = encodeBody();
        int bodyLength = body != null ? body.length : 0;

        // 计算总长度
        int totalLength = HEADER_LENGTH + bodyLength;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入发送者节点ID
        if (senderNodeId != null) {
            byte[] nodeIdBytes = senderNodeId.getBytes(StandardCharsets.UTF_8);
            System.arraycopy(nodeIdBytes, 0, data, offset, NODE_ID_LENGTH);
        }
        offset += NODE_ID_LENGTH;

        // 写入消息类型
        data[offset++] = type.getCode();

        // 写入消息长度（大端序）
        int messageLength = bodyLength;
        data[offset++] = (byte) (messageLength >> 24);
        data[offset++] = (byte) (messageLength >> 16);
        data[offset++] = (byte) (messageLength >> 8);
        data[offset++] = (byte) messageLength;

        // 写入消息体
        if (bodyLength > 0) {
            System.arraycopy(body, 0, data, offset, bodyLength);
        }

        return data;
    }

    /**
     * 从字节数组解码消息
     *
     * @param data 字节数组
     */
    public void decode(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("消息数据长度不足");
        }

        int offset = 0;

        // 读取发送者节点ID
        byte[] nodeIdBytes = new byte[NODE_ID_LENGTH];
        System.arraycopy(data, offset, nodeIdBytes, 0, NODE_ID_LENGTH);
        this.senderNodeId = new String(nodeIdBytes, StandardCharsets.UTF_8);
        offset += NODE_ID_LENGTH;

        // 读取消息类型
        byte typeCode = data[offset++];
        this.type = GossipMessageType.fromCode(typeCode);
        if (this.type == null) {
            throw new IllegalArgumentException("未知的消息类型编码: " + typeCode);
        }

        // 读取消息长度（大端序）
        int messageLength = ((data[offset++] & 0xFF) << 24) |
                ((data[offset++] & 0xFF) << 16) |
                ((data[offset++] & 0xFF) << 8) |
                (data[offset++] & 0xFF);

        // 解码消息体
        if (messageLength > 0) {
            byte[] body = new byte[messageLength];
            System.arraycopy(data, offset, body, 0, messageLength);
            decodeBody(body);
        }
    }

    /**
     * 编码消息体（子类实现）
     *
     * @return 消息体字节数组
     */
    protected abstract byte[] encodeBody();

    /**
     * 解码消息体（子类实现）
     *
     * @param body 消息体字节数组
     */
    protected abstract void decodeBody(byte[] body);

    /**
     * 计算消息总长度
     *
     * @return 消息总长度
     */
    public int getMessageLength() {
        byte[] body = encodeBody();
        return HEADER_LENGTH + (body != null ? body.length : 0);
    }

    // ==================== 工具方法 ====================

    /**
     * 创建指定类型的消息实例
     *
     * @param type 消息类型
     * @return 消息实例
     */
    public static GossipMessage createMessage(GossipMessageType type) {
        if (type == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }

        switch (type) {
            case PING:
                return new PingMessage();
            case PONG:
                return new PongMessage();
            case MEET:
                return new MeetMessage();
            case FAIL:
                return new FailMessage();
            case PUBLISH:
                return new PublishMessage();
            case FAILOVER_AUTH_REQUEST:
                return new FailoverAuthRequestMessage();
            case FAILOVER_AUTH_ACK:
                return new FailoverAuthAckMessage();
            case UPDATE:
                return new UpdateMessage();
            case FAILOVER_RESULT:
                return new FailoverResultMessage();
            default:
                throw new IllegalArgumentException("不支持的消息类型: " + type);
        }
    }

    /**
     * 从字节数组解析消息
     *
     * @param data 字节数组
     * @return 解析后的消息对象
     */
    public static GossipMessage parseMessage(byte[] data) {
        if (data == null || data.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("消息数据长度不足");
        }

        // 读取消息类型
        byte typeCode = data[NODE_ID_LENGTH];
        GossipMessageType type = GossipMessageType.fromCode(typeCode);
        if (type == null) {
            throw new IllegalArgumentException("未知的消息类型编码: " + typeCode);
        }

        // 创建消息实例并解码
        GossipMessage message = createMessage(type);
        message.decode(data);
        return message;
    }

    @Override
    public String toString() {
        return "GossipMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                '}';
    }
}
