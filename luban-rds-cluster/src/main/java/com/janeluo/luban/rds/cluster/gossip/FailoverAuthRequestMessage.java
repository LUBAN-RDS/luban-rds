package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNode;

import java.util.BitSet;

/**
 * FAILOVER_AUTH_REQUEST 消息
 * <p>
 * 故障转移授权请求，从节点请求投票成为主节点
 * </p>
 * <p>
 * 消息体格式：
 * - 配置纪元（8 字节，大端序）
 * - 当前纪元（8 字节，大端序）
 * - 复制偏移量（8 字节，大端序）
 * - 声明槽位 BitSet（2048 字节 = 16384 位，N-15 追加，向后兼容：旧消息无此字段时解码为空）
 * </p>
 */
public class FailoverAuthRequestMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 16384 位槽位图占用的字节数
     */
    private static final int SLOTS_BYTES = ClusterNode.CLUSTER_SLOTS / 8;

    /**
     * 配置纪元
     */
    private long configEpoch;

    /**
     * 当前纪元
     */
    private long currentEpoch;

    /**
     * 复制偏移量
     */
    private long replicationOffset;

    /**
     * 声明槽位集合（N-15）。
     * <p>
     * 对齐 Redis clusterBuildMessageHdr：slave 广播时声明其 master 的槽位位图
     * （"If this node is a slave we send the master's information instead"），
     * 供投票方与槽位当前 owner 的 configEpoch 比较裁决陈旧候选。
     * </p>
     */
    private BitSet claimedSlots;

    /**
     * 默认构造方法
     */
    public FailoverAuthRequestMessage() {
        this.type = GossipMessageType.FAILOVER_AUTH_REQUEST;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId      发送者节点ID
     * @param configEpoch       配置纪元
     * @param currentEpoch      当前纪元
     * @param replicationOffset 复制偏移量
     */
    public FailoverAuthRequestMessage(String senderNodeId, long configEpoch,
                                      long currentEpoch, long replicationOffset) {
        super(senderNodeId, GossipMessageType.FAILOVER_AUTH_REQUEST);
        this.configEpoch = configEpoch;
        this.currentEpoch = currentEpoch;
        this.replicationOffset = replicationOffset;
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

    public long getReplicationOffset() {
        return replicationOffset;
    }

    public void setReplicationOffset(long replicationOffset) {
        this.replicationOffset = replicationOffset;
    }

    /**
     * 获取声明槽位集合（N-15）。
     *
     * @return 声明槽位 BitSet，旧版本消息（无此字段）返回空 BitSet
     */
    public BitSet getClaimedSlots() {
        return claimedSlots;
    }

    /**
     * 设置声明槽位集合（N-15）。
     *
     * @param claimedSlots 声明槽位 BitSet
     */
    public void setClaimedSlots(BitSet claimedSlots) {
        this.claimedSlots = claimedSlots != null ? (BitSet) claimedSlots.clone() : null;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[24 + SLOTS_BYTES];
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

        // 写入复制偏移量（大端序）
        data[offset++] = (byte) (replicationOffset >> 56);
        data[offset++] = (byte) (replicationOffset >> 48);
        data[offset++] = (byte) (replicationOffset >> 40);
        data[offset++] = (byte) (replicationOffset >> 32);
        data[offset++] = (byte) (replicationOffset >> 24);
        data[offset++] = (byte) (replicationOffset >> 16);
        data[offset++] = (byte) (replicationOffset >> 8);
        data[offset++] = (byte) replicationOffset;

        // 写入声明槽位（2048 字节位图，BitSet.toByteArray 只含最高 set bit 之前的字节）
        if (claimedSlots != null) {
            byte[] slotBytes = claimedSlots.toByteArray();
            System.arraycopy(slotBytes, 0, data, offset, Math.min(slotBytes.length, SLOTS_BYTES));
        }

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 24) {
            throw new IllegalArgumentException("FAILOVER_AUTH_REQUEST 消息体长度不足: 需要 24 字节，实际 "
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

        // 读取当前纪元（大端序）
        currentEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取复制偏移量（大端序）
        replicationOffset = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取声明槽位（N-15）：尾部追加，兼容旧消息（不足 2048 字节时剩余位补 0）
        int slotLen = Math.min(SLOTS_BYTES, body.length - offset);
        if (slotLen > 0) {
            byte[] slotBytes = new byte[SLOTS_BYTES];
            System.arraycopy(body, offset, slotBytes, 0, slotLen);
            this.claimedSlots = BitSet.valueOf(slotBytes);
        }
    }

    @Override
    public String toString() {
        return "FailoverAuthRequestMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", configEpoch=" + configEpoch +
                ", currentEpoch=" + currentEpoch +
                ", replicationOffset=" + replicationOffset +
                ", claimedSlotCount=" + (claimedSlots != null ? claimedSlots.cardinality() : 0) +
                '}';
    }
}
