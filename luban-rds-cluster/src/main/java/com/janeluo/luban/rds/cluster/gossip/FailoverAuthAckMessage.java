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
 * - 被投候选节点ID（40 字节 ASCII）—— P0-4 修复：将投票绑定到具体候选，
 *   防止同纪元多候选互相误计对方的 ACK 导致双 master
 * </p>
 */
public class FailoverAuthAckMessage extends GossipMessage {

    private static final long serialVersionUID = 1L;

    /**
     * 被投候选节点ID长度（ASCII，对齐 ClusterNode.NODE_ID_LENGTH）
     */
    private static final int CANDIDATE_ID_LENGTH = 40;

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
     * 被投候选节点ID（P0-4：投票必须绑定到具体候选，ACK 为广播消息，
     * 不带 candidateId 会使同纪元其他候选误计此票）
     */
    private String candidateId;

    /**
     * 默认构造方法
     */
    public FailoverAuthAckMessage() {
        this.type = GossipMessageType.FAILOVER_AUTH_ACK;
    }

    /**
     * 兼容旧调用方的构造方法（candidateId 留空，仅用于过渡/测试桩）。
     * 生产路径应使用带 candidateId 的 5 参构造。
     *
     * @param senderNodeId 发送者节点ID
     * @param configEpoch  配置纪元
     * @param currentEpoch 当前纪元
     * @param voteEpoch    投票纪元
     */
    public FailoverAuthAckMessage(String senderNodeId, long configEpoch,
                                  long currentEpoch, long voteEpoch) {
        this(senderNodeId, configEpoch, currentEpoch, voteEpoch, null);
    }

    /**
     * 带候选ID的构造方法（生产路径）。
     *
     * @param senderNodeId 发送者节点ID（投票方）
     * @param configEpoch  配置纪元
     * @param currentEpoch 当前纪元
     * @param voteEpoch    投票纪元
     * @param candidateId  被投候选节点ID
     */
    public FailoverAuthAckMessage(String senderNodeId, long configEpoch,
                                  long currentEpoch, long voteEpoch, String candidateId) {
        super(senderNodeId, GossipMessageType.FAILOVER_AUTH_ACK);
        this.configEpoch = configEpoch;
        this.currentEpoch = currentEpoch;
        this.voteEpoch = voteEpoch;
        this.candidateId = candidateId;
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

    public String getCandidateId() {
        return candidateId;
    }

    public void setCandidateId(String candidateId) {
        this.candidateId = candidateId;
    }

    // ==================== 编解码方法 ====================

    @Override
    protected byte[] encodeBody() {
        byte[] data = new byte[24 + CANDIDATE_ID_LENGTH];
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

        // 写入候选节点ID（40 字节 ASCII，不足补 0，对齐 FailoverResultMessage.winnerNodeId 编码）
        String cid = candidateId != null ? candidateId : "";
        for (int i = 0; i < CANDIDATE_ID_LENGTH; i++) {
            data[offset++] = i < cid.length() ? (byte) cid.charAt(i) : (byte) 0;
        }

        return data;
    }

    @Override
    protected void decodeBody(byte[] body) {
        if (body == null || body.length < 24) {
            throw new IllegalArgumentException("FAILOVER_AUTH_ACK 消息体长度不足: 至少 24 字节，实际 "
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

        // 读取投票纪元（大端序）
        voteEpoch = ((long) (body[offset++] & 0xFF) << 56) |
                ((long) (body[offset++] & 0xFF) << 48) |
                ((long) (body[offset++] & 0xFF) << 40) |
                ((long) (body[offset++] & 0xFF) << 32) |
                ((long) (body[offset++] & 0xFF) << 24) |
                ((long) (body[offset++] & 0xFF) << 16) |
                ((long) (body[offset++] & 0xFF) << 8) |
                ((long) (body[offset++] & 0xFF));

        // 读取候选节点ID（兼容无候选字段的旧版本：剩余字节不足 40 则保持 null）
        if (body.length - offset >= CANDIDATE_ID_LENGTH) {
            int end = offset + CANDIDATE_ID_LENGTH;
            // 去除尾部 \0 填充
            while (end > offset && body[end - 1] == 0) {
                end--;
            }
            candidateId = end > offset ? new String(body, offset, end - offset) : null;
        }
    }

    @Override
    public String toString() {
        return "FailoverAuthAckMessage{" +
                "senderNodeId='" + senderNodeId + '\'' +
                ", type=" + type +
                ", configEpoch=" + configEpoch +
                ", currentEpoch=" + currentEpoch +
                ", voteEpoch=" + voteEpoch +
                ", candidateId='" + candidateId + '\'' +
                '}';
    }
}
