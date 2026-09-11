package com.janeluo.luban.rds.mesh.rpc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * RequestVote RPC 响应（DESIGN.md §4.3，消息类型 {@code 0x63}）。
 * <p>
 * All → Candidate：投票结果。
 * </p>
 *
 * <pre>
 * long term;           // 投票者当前任期
 * boolean voteGranted; // 是否投票
 * boolean preVote;     // P0-3：该票所属阶段（PreVote 探测 or 正式选举）
 * long electionTerm;   // P0-3：授予时的选举任期（用于轮次匹配）
 * </pre>
 *
 * <h3>P0-3 帧兼容（2026-09-11 mesh 审计）</h3>
 * <p>
 * {@code preVote}/{@code electionTerm} 追加于帧尾。旧版本帧（9 字节）可解码，缺省字段取
 * {@code false/0}——{@code electionTerm=0} 与任何 currentTerm 不相等，调用方按保守语义丢弃
 * （不计票）；新版本帧发给旧版本节点时多余尾部字节被忽略，双向兼容。
 * </p>
 */
public class RequestVoteResponse extends MeshRpcMessage {

    private final boolean voteGranted;
    /** P0-3：该票所属阶段（true=PreVote 探测票；false=正式选举票）。 */
    private final boolean preVote;
    /** P0-3：授予时的选举任期。候选者计票前校验其等于自身 currentTerm。 */
    private final long electionTerm;

    /**
     * 兼容构造（旧调用点/旧帧解码）：preVote=false、electionTerm=0。
     * electionTerm=0 与任何 currentTerm 不相等，将被计票方保守丢弃。
     *
     * @param term        投票者当前任期
     * @param voteGranted 是否投票
     */
    public RequestVoteResponse(long term, boolean voteGranted) {
        this(term, voteGranted, false, 0L);
    }

    /**
     * @param term         投票者当前任期
     * @param voteGranted  是否投票
     * @param preVote      该票所属阶段（对请求 {@code RequestVoteMessage.isPreVote()} 的回显）
     * @param electionTerm 授予时的选举任期（处理请求后的投票者 currentTerm）
     */
    public RequestVoteResponse(long term, boolean voteGranted, boolean preVote, long electionTerm) {
        super(term);
        this.voteGranted = voteGranted;
        this.preVote = preVote;
        this.electionTerm = electionTerm;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    /** P0-3：该票所属阶段（true=PreVote；false=正式选举）。 */
    public boolean isPreVote() {
        return preVote;
    }

    /** P0-3：授予时的选举任期。 */
    public long getElectionTerm() {
        return electionTerm;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        out.writeBoolean(voteGranted);
        // P0-3：尾部追加轮次标识，旧版本解码器忽略多余字节（双向兼容）
        out.writeBoolean(preVote);
        out.writeLong(electionTerm);
    }

    /**
     * 反序列化 byte[] 为 {@link RequestVoteResponse}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code REQUEST_VOTE_RESP} 分支调用。</p>
     */
    public static RequestVoteResponse decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            boolean voteGranted = in.readBoolean();
            // P0-3：旧版本帧（9 字节）无尾部字段 → 默认 preVote=false/electionTerm=0，
            // 与任何 currentTerm 不相等 → 调用方保守丢弃（安全方向）
            boolean preVote = false;
            long electionTerm = 0L;
            if (in.available() >= 9) {
                preVote = in.readBoolean();
                electionTerm = in.readLong();
            }
            return new RequestVoteResponse(term, voteGranted, preVote, electionTerm);
        } catch (Exception e) {
            throw new RuntimeException("RequestVoteResponse decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "RequestVoteResponse{term=" + term + ", voteGranted=" + voteGranted
                + ", preVote=" + preVote + ", electionTerm=" + electionTerm + '}';
    }
}
