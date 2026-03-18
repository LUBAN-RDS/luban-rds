package com.janeluo.luban.rds.cluster.gossip;

/**
 * FAILOVER_AUTH_ACK 消息
 * <p>
 * 故障转移授权确认，主节点投票响应
 * </p>
 * <p>
 * 消息体格式：
 * - 配置纪元（8 字节，大端序）
 * - 当前纪元（8 字节，大端序）
 * - 投票纪元（8 字节，大端序）
 * </p>
 */
public class FailoverAuthAckMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 配置纪元
     */
    private long configEpoch;

    /**
     * 当前纪元
     */
    private long currentEpoch;

    /**
     * 投票纪元
     */
    private long voteEpoch;

    /**
     * 默认构造方法
     */
    public FailoverAuthAckMessage() {
        this.type = GossipMessageType.FAILOVER_AUTH_ACK;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId 发送者节点ID
     * @param configEpoch  配置纪元
     * @param currentEpoch 当前纪元
     * @param voteEpoch    投票纪元
     */
    public FailoverAuthAckMessage(String senderNodeId, long configEpoch,
                                  long currentEpoch, long voteEpoch) {
        super(senderNodeId, GossipMessageType.FAILOVER_AUTH_ACK);
        this.configEpoch = configEpoch;
        this.currentEpoch = currentEpoch;
        this.voteEpoch = voteEpoch;
    }

    // ==================== Getter/Setter 方法 ====================

    public long getConfigEpoch() {
        return configEpoch;
    }

    public void setConfigEpoch(long configEpoch) {
        this.configEpoch = configEpoch;
    }

    public long getCurrentEpoch() {
        return currentEpoch;
    }

    public void setCurrentEpoch(long currentEpoch) {
        this.currentEpoch = currentEpoch;
    }

    public long getVoteEpoch() {
        return voteEpoch;
    }

    public void setVoteEpoch(long voteEpoch) {
        this.voteEpoch = voteEpoch;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[24];
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

        // 写入当前纪元（大端序）
        data[offset++] = (byte) (currentEpoch >> 56);
        data[offset++] = (byte) (currentEpoch >> 48);
        data[offset++] = (byte) (currentEpoch >> 40);
        data[offset++] = (byte) (currentEpoch >> 32);
        data[offset++] = (byte) (currentEpoch >> 24);
        data[offset++] = (byte) (currentEpoch >> 16);
        data[offset++] = (byte) (currentEpoch >> 8);
        data[offset++] = (byte) currentEpoch;

        // 写入投票纪元（大端序）
        data[offset++] = (byte) (voteEpoch >> 56);
        data[offset++] = (byte) (voteEpoch >> 48);
        data[offset++] = (byte) (voteEpoch >> 40);
        data[offset++] = (byte) (voteEpoch >> 32);
        data[offset++] = (byte) (voteEpoch >> 24);
        data[offset++] = (byte) (voteEpoch >> 16);
        data[offset++] = (byte) (voteEpoch >> 8);
        data[offset++] = (byte) voteEpoch;

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 24) {
            return;
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

        // 读取当前纪元（大端序）
        currentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取投票纪元（大端序）
        voteEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));
    }

    @Override
    public String toString() {
        return "FailoverAuthAckMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", configEpoch=" + configEpoch +
                ", currentEpoch=" + currentEpoch +
                ", voteEpoch=" + voteEpoch +
                '}';
    }
}
