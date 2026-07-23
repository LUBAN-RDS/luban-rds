package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNode;

import java.nio.charset.StandardCharsets;
import java.util.BitSet;

/**
 * FAILOVER_RESULT 消息
 * <p>
 * 胜选 slave 广播自己已提升为新 master，触发全网拓扑收敛。
 * 收到此消息的节点按结果更新 winner 为 master、继承槽位、把原 master 降级为 winner 的 slave。
 * </p>
 * <p>
 * 消息体格式：
 * - 胜选节点ID（40 字节 ASCII）
 * - 新配置纪元（8 字节，大端序）
 * - 继承的槽位 BitSet（16384 位 = 2048 字节）
 * </p>
 */
public class FailoverResultMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 16384 位槽位图占用的字节数
     */
    private static final int SLOTS_BYTES = ClusterNode.CLUSTER_SLOTS / 8;

    /**
     * 胜选节点ID
     */
    private String winnerNodeId;

    /**
     * 新配置纪元（胜选后自增的 currentEpoch）
     */
    private long newConfigEpoch;

    /**
     * 继承的槽位集合（原 master 的槽位）
     */
    private BitSet inheritedSlots;

    /**
     * 默认构造方法
     */
    public FailoverResultMessage() {
        this.type = GossipMessageType.FAILOVER_RESULT;
    }

    /**
     * 带参数的构造方法
     *
     * @param senderNodeId   发送方节点ID（即 winner）
     * @param winnerNodeId   胜选节点ID
     * @param newConfigEpoch 新配置纪元
     * @param inheritedSlots 继承的槽位集合
     */
    public FailoverResultMessage(String senderNodeId, String winnerNodeId,
                                 long newConfigEpoch, BitSet inheritedSlots) {
        super(senderNodeId, GossipMessageType.FAILOVER_RESULT);
        this.winnerNodeId = winnerNodeId;
        this.newConfigEpoch = newConfigEpoch;
        this.inheritedSlots = inheritedSlots != null ? (BitSet) inheritedSlots.clone() : new BitSet();
    }

    public String getWinnerNodeId() {
        return winnerNodeId;
    }

    public void setWinnerNodeId(String winnerNodeId) {
        this.winnerNodeId = winnerNodeId;
    }

    public long getNewConfigEpoch() {
        return newConfigEpoch;
    }

    public void setNewConfigEpoch(long newConfigEpoch) {
        this.newConfigEpoch = newConfigEpoch;
    }

    public BitSet getInheritedSlots() {
        return inheritedSlots;
    }

    public void setInheritedSlots(BitSet inheritedSlots) {
        this.inheritedSlots = inheritedSlots;
    }

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[40 + 8 + SLOTS_BYTES];
        int offset = 0;

        // winnerNodeId（40 字节）
        if (winnerNodeId != null) {
            byte[] idBytes = winnerNodeId.getBytes(StandardCharsets.UTF_8);
            int copyLen = Math.min(idBytes.length, 40);
            System.arraycopy(idBytes, 0, data, offset, copyLen);
        }
        offset += 40;

        // newConfigEpoch（8 字节大端）
        data[offset++] = (byte) (newConfigEpoch >> 56);
        data[offset++] = (byte) (newConfigEpoch >> 48);
        data[offset++] = (byte) (newConfigEpoch >> 40);
        data[offset++] = (byte) (newConfigEpoch >> 32);
        data[offset++] = (byte) (newConfigEpoch >> 24);
        data[offset++] = (byte) (newConfigEpoch >> 16);
        data[offset++] = (byte) (newConfigEpoch >> 8);
        data[offset++] = (byte) newConfigEpoch;

        // inheritedSlots（2048 字节位图，BitSet.toByteArray 只含最高 set bit 之前的字节）
        BitSet slots = inheritedSlots != null ? inheritedSlots : new BitSet();
        byte[] slotBytes = slots.toByteArray();
        System.arraycopy(slotBytes, 0, data, offset, Math.min(slotBytes.length, SLOTS_BYTES));

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 40 + 8) {
            throw new IllegalArgumentException(
                    "FAILOVER_RESULT 消息体长度不足: 至少需要 48 字节，实际 "
                            + (body == null ? 0 : body.length));
        }
        int offset = 0;

        byte[] idBytes = new byte[40];
        System.arraycopy(body, offset, idBytes, 0, 40);
        this.winnerNodeId = new String(idBytes, StandardCharsets.UTF_8).trim();
        if (this.winnerNodeId.isEmpty()) {
            throw new IllegalArgumentException("FAILOVER_RESULT 消息 winnerNodeId 为空");
        }
        offset += 40;

        this.newConfigEpoch = 0L;
        for (int i = 0; i < 8; i++) {
            this.newConfigEpoch = (this.newConfigEpoch << 8) | (body[offset++] & 0xFFL);
        }

        // inheritedSlots：补齐到 SLOTS_BYTES 长度（不足时剩余位补 0，不抛异常以兼容正常广播）
        int slotLen = Math.min(SLOTS_BYTES, body.length - offset);
        byte[] slotBytes = new byte[SLOTS_BYTES];
        System.arraycopy(body, offset, slotBytes, 0, slotLen);
        this.inheritedSlots = BitSet.valueOf(slotBytes);
    }

    @Override
    public String toString() {
        return "FailoverResultMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", winnerNodeId='" + winnerNodeId + '\'' +
                ", newConfigEpoch=" + newConfigEpoch +
                ", inheritedSlotCount=" + (inheritedSlots != null ? inheritedSlots.cardinality() : 0) +
                '}';
    }
}
