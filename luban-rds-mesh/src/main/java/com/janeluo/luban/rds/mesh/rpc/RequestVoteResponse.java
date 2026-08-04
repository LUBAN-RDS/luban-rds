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
 * long term;          // 投票者当前任期
 * boolean voteGranted; // 是否投票
 * </pre>
 */
public class RequestVoteResponse extends MeshRpcMessage {

    private final boolean voteGranted;

    /**
     * @param term        投票者当前任期
     * @param voteGranted 是否投票
     */
    public RequestVoteResponse(long term, boolean voteGranted) {
        super(term);
        this.voteGranted = voteGranted;
    }

    public boolean isVoteGranted() {
        return voteGranted;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        out.writeBoolean(voteGranted);
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
            return new RequestVoteResponse(term, voteGranted);
        } catch (Exception e) {
            throw new RuntimeException("RequestVoteResponse decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "RequestVoteResponse{term=" + term + ", voteGranted=" + voteGranted + '}';
    }
}
