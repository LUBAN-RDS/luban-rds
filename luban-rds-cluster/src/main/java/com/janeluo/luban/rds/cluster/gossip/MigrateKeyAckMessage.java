package com.janeluo.luban.rds.cluster.gossip;

import java.nio.charset.StandardCharsets;

/**
 * 键迁移确认消息
 * <p>
 * 目标节点收到 {@link MigrateKeyMessage} 并完成 importKey 后，
 * 回复此消息给源节点，告知迁移是否成功。
 * </p>
 * <p>
 * 消息体格式：
 * - 键名长度（4 字节，大端序）+ 键名（UTF-8）
 * - 成功标志（1 字节，0=失败，1=成功）
 * - 错误信息长度（4 字节，大端序）+ 错误信息（UTF-8，失败时填写）
 * </p>
 */
public class MigrateKeyAckMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 迁移的键名
     */
    private String key;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 失败时的错误信息
     */
    private String errorMessage;

    /**
     * 默认构造方法
     */
    public MigrateKeyAckMessage() {
        this.type = GossipMessageType.MIGRATE_KEY_ACK;
    }

    /**
     * 完整构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param key          键名
     * @param success      是否成功
     * @param errorMessage 失败时的错误信息（成功时为 null）
     */
    public MigrateKeyAckMessage(String senderNodeId, String key, boolean success, String errorMessage) {
        super(senderNodeId, GossipMessageType.MIGRATE_KEY_ACK);
        this.key = key;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    @Override
    protected byte[] encodeBody() {
        byte[] keyBytes = key != null ? key.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] errorBytes = errorMessage != null ? errorMessage.getBytes(StandardCharsets.UTF_8) : new byte[0];
        int totalLength = 4 + keyBytes.length + 1 + 4 + errorBytes.length;
        byte[] data = new byte[totalLength];
        int offset = 0;

        // 写入键名长度和键名
        data[offset++] = (byte) (keyBytes.length >> 24);
        data[offset++] = (byte) (keyBytes.length >> 16);
        data[offset++] = (byte) (keyBytes.length >> 8);
        data[offset++] = (byte) keyBytes.length;
        if (keyBytes.length > 0) {
            System.arraycopy(keyBytes, 0, data, offset, keyBytes.length);
            offset += keyBytes.length;
        }

        // 写入成功标志
        data[offset++] = (byte) (success ? 1 : 0);

        // 写入错误信息长度和错误信息
        data[offset++] = (byte) (errorBytes.length >> 24);
        data[offset++] = (byte) (errorBytes.length >> 16);
        data[offset++] = (byte) (errorBytes.length >> 8);
        data[offset++] = (byte) errorBytes.length;
        if (errorBytes.length > 0) {
            System.arraycopy(errorBytes, 0, data, offset, errorBytes.length);
        }

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 4) {
            throw new IllegalArgumentException("MIGRATE_KEY_ACK 消息体长度不足: 至少需要 4 字节，实际 "
                    + (body == null ? 0 : body.length));
        }
        int offset = 0;

        // 读取键名长度和键名
        int keyLen = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
        if (keyLen < 0 || offset + keyLen > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY_ACK 消息键名段数据不足: keyLen=" + keyLen);
        }
        if (keyLen > 0) {
            byte[] keyBytes = new byte[keyLen];
            System.arraycopy(body, offset, keyBytes, 0, keyLen);
            this.key = new String(keyBytes, StandardCharsets.UTF_8);
            offset += keyLen;
        }

        // 读取成功标志
        if (offset >= body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY_ACK 消息成功标志字段数据不足");
        }
        this.success = (body[offset++] & 0xFF) == 1;

        // 读取错误信息长度和错误信息
        if (offset + 4 > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY_ACK 消息错误信息长度字段数据不足");
        }
        int errorLen = ((body[offset++] & 0xFF) << 24) |
                ((body[offset++] & 0xFF) << 16) |
                ((body[offset++] & 0xFF) << 8) |
                (body[offset++] & 0xFF);
        if (errorLen < 0 || offset + errorLen > body.length) {
            throw new IllegalArgumentException("MIGRATE_KEY_ACK 消息错误信息段数据不足: errorLen=" + errorLen);
        }
        if (errorLen > 0) {
            byte[] errorBytes = new byte[errorLen];
            System.arraycopy(body, offset, errorBytes, 0, errorLen);
            this.errorMessage = new String(errorBytes, StandardCharsets.UTF_8);
        }
    }

    @Override
    public String toString() {
        return "MigrateKeyAckMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", key='" + key + '\'' +
                ", success=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
