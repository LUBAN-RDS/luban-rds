package com.janeluo.luban.rds.cluster.gossip;

/**
 * MANUAL_FAILOVER_START 消息（P1-12）。
 * <p>
 * 候选 slave 发送给自己的 master，请求其暂停客户端写、记录当前复制偏移量、
 * 并通过 {@link ManualFailoverOffsetMessage} 回传该偏移量。
 * </p>
 * <p>
 * 对齐 Redis CLUSTERMSG_TYPE_MFSTART：普通模式手动故障转移的握手阶段，
 * 保证 master 在被接管前已停止写入，slave 追平 offset 后提升，避免丢数据。
 * 消息体为空（仅头部 senderNodeId 标识发起方）。
 * </p>
 */
public class ManualFailoverStartMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 默认构造方法
     */
    public ManualFailoverStartMessage() {
        this.type = GossipMessageType.MANUAL_FAILOVER_START;
    }

    /**
     * 带发送者的构造方法
     *
     * @param senderNodeId 发起手动故障转移的 slave 节点ID
     */
    public ManualFailoverStartMessage(String senderNodeId) {
        super(senderNodeId, GossipMessageType.MANUAL_FAILOVER_START);
    }

    @Override
    protected byte[] encodeBody() {
        return new byte[0];
    }

    @Override
    protected void decodeBody(byte[] body) {
        // 消息体为空，无需解码
    }

    @Override
    public String toString() {
        return "ManualFailoverStartMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                '}';
    }
}
