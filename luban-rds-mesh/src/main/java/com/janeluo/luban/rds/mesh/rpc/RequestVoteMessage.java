package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * RequestVote RPC 请求（DESIGN.md §4.3，消息类型 {@code 0x62}）。
 * <p>
 * Candidate → All：选举投票请求。接收方按 Raft 规则裁决（任期 ≥ 自己、candidate 日志 ≥ 自己日志、
 * 未投过别人）后决定是否投票（DESIGN §5.2）。
 * </p>
 *
 * <h3>PreVote 字段（DESIGN §5.2 delta spec）</h3>
 * <p>
 * 为支持 PreVote（不自增 term 先探测"能否赢"），本类新增 {@code preVote} 标志位，复用同一 RPC 类型
 * 与字段结构（避免新增 RPC 类型带来的编解码/总线类型码改动）。{@code preVote=true} 表示这是一次
 * PreVote 探测：投票者校验同正式投票（任期/日志），但<strong>不记录 votedFor</strong>；
 * Candidate 仅在 PreVote 获多数派后才自增 term 发起正式 RequestVote。
 * </p>
 * <p>
 * 向后兼容：旧版本 wire 格式不含 preVote 尾部字节。解码时若读到 EOF（剩余字节不足 1 个 boolean），
 * 视为 {@code preVote=false}（兼容历史编码）。但本模块内所有 encode 都会写出该字段，故 T2.2 的
 * 往返测试需补 preVote 字段断言。
 * </p>
 *
 * <pre>
 * long term;           // Candidate 任期（PreVote 时为当前 term，不自增）
 * String candidateId;  // Candidate nodeId
 * long lastLogIndex;   // Candidate 日志最后索引
 * long lastLogTerm;    // 最后索引对应的任期
 * boolean preVote;     // 是否为 PreVote 探测（默认 false）
 * </pre>
 */
public class RequestVoteMessage extends MeshRpcMessage {

    private final String candidateId;
    private final long lastLogIndex;
    private final long lastLogTerm;
    private final boolean preVote;

    /**
     * 全参构造器。
     *
     * @param term         Candidate 任期（PreVote 时为当前 term，不自增）
     * @param candidateId  Candidate nodeId
     * @param lastLogIndex Candidate 日志最后索引
     * @param lastLogTerm  最后索引对应的任期
     * @param preVote      是否为 PreVote 探测
     */
    public RequestVoteMessage(long term, String candidateId, long lastLogIndex, long lastLogTerm, boolean preVote) {
        super(term);
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
        this.preVote = preVote;
    }

    /**
     * 兼容构造器：等价于 {@code preVote=false}（正式投票）。
     */
    public RequestVoteMessage(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
        this(term, candidateId, lastLogIndex, lastLogTerm, false);
    }

    public String getCandidateId() {
        return candidateId;
    }

    public long getLastLogIndex() {
        return lastLogIndex;
    }

    public long getLastLogTerm() {
        return lastLogTerm;
    }

    /** 是否为 PreVote 探测（true=不记录 votedFor，仅反映"能否赢"）。 */
    public boolean isPreVote() {
        return preVote;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        LogEntry.writeUtf8(out, candidateId);
        out.writeLong(lastLogIndex);
        out.writeLong(lastLogTerm);
        out.writeBoolean(preVote);
    }

    /**
     * 反序列化 byte[] 为 {@link RequestVoteMessage}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code REQUEST_VOTE} 分支调用。</p>
     * <p>兼容性：若字节流尾部不含 preVote 字段（旧编码），按 {@code false} 处理。</p>
     */
    public static RequestVoteMessage decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            String candidateId = LogEntry.readUtf8(in);
            long lastLogIndex = in.readLong();
            long lastLogTerm = in.readLong();
            boolean preVote = in.available() >= 1 && in.readBoolean();
            return new RequestVoteMessage(term, candidateId, lastLogIndex, lastLogTerm, preVote);
        } catch (Exception e) {
            throw new RuntimeException("RequestVoteMessage decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "RequestVoteMessage{term=" + term + ", candidateId=" + candidateId
                + ", lastLogIndex=" + lastLogIndex + ", lastLogTerm=" + lastLogTerm
                + ", preVote=" + preVote + '}';
    }
}
