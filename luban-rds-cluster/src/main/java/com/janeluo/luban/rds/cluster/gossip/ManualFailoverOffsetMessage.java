package com.janeluo.luban.rds.cluster.gossip;

/**
 * MANUAL_FAILOVER_OFFSET 消息（P1-12）。
 * <p>
 * master 收到 {@link ManualFailoverStartMessage} 后，暂停客户端写、记录当前复制偏移量，
 * 再通过本消息回传给发起 slave。slave 须等待自身复制偏移量追平到此值后才执行提升，
 * 保证手动 failover 不丢数据（对齐 Redis clusterFailover、manual failover 的 offset 追平）。
 * </p>
 * <p>
 * 消息体格式：
 * - master 复制偏移量（8 字节，大端序）
 * </p>
 */
public class ManualFailoverOffsetMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * master 暂停写时的复制偏移量
     */
    private long masterOffset;

    /**
     * 默认构造方法
     */
    public ManualFailoverOffsetMessage() {
        this.type = GossipMessageType.MANUAL_FAILOVER_OFFSET;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId  发送者节点ID（master）
     * @param masterOffset  master 暂停写时的复制偏移量
     */
    public ManualFailoverOffsetMessage(String senderNodeId, long masterOffset) {
        super(senderNodeId, GossipMessageType.MANUAL_FAILOVER_OFFSET);
        this.masterOffset = masterOffset;
    }

    public long getMasterOffset() {
        return masterOffset;
    }

    public void setMasterOffset(long masterOffset) {
        this.masterOffset = masterOffset;
    }

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[8];
        data[0] = (byte) (masterOffset >> 56);
        data[1] = (byte) (masterOffset >> 48);
        data[2] = (byte) (masterOffset >> 40);
        data[3] = (byte) (masterOffset >> 32);
        data[4] = (byte) (masterOffset >> 24);
        data[5] = (byte) (masterOffset >> 16);
        data[6] = (byte) (masterOffset >> 8);
        data[7] = (byte) masterOffset;
        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 8) {
            throw new IllegalArgumentException(
                    "MANUAL_FAILOVER_OFFSET 消息体长度不足: 需要 8 字节，实际 "
                            + (body == null ? 0 : body.length));
        }
        masterOffset = ((long) (body[0] & 0xFF) << 56) |
                ((long) (body[1] & 0xFF) << 48) |
                ((long) (body[2] & 0xFF) << 40) |
                ((long) (body[3] & 0xFF) << 32) |
                ((long) (body[4] & 0xFF) << 24) |
                ((long) (body[5] & 0xFF) << 16) |
                ((long) (body[6] & 0xFF) << 8) |
                (body[7] & 0xFF);
    }

    @Override
    public String toString() {
        return "ManualFailoverOffsetMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", masterOffset=" + masterOffset +
                '}';
    }
}
