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
 * <pre>
 * long term;           // Candidate 任期
 * String candidateId;  // Candidate nodeId
 * long lastLogIndex;   // Candidate 日志最后索引
 * long lastLogTerm;    // 最后索引对应的任期
 * </pre>
 */
public class RequestVoteMessage extends MeshRpcMessage {

    private final String candidateId;
    private final long lastLogIndex;
    private final long lastLogTerm;

    /**
     * @param term         Candidate 任期
     * @param candidateId  Candidate nodeId
     * @param lastLogIndex Candidate 日志最后索引
     * @param lastLogTerm  最后索引对应的任期
     */
    public RequestVoteMessage(long term, String candidateId, long lastLogIndex, long lastLogTerm) {
        super(term);
        this.candidateId = candidateId;
        this.lastLogIndex = lastLogIndex;
        this.lastLogTerm = lastLogTerm;
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

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        LogEntry.writeUtf8(out, candidateId);
        out.writeLong(lastLogIndex);
        out.writeLong(lastLogTerm);
    }

    /**
     * 反序列化 byte[] 为 {@link RequestVoteMessage}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code REQUEST_VOTE} 分支调用。</p>
     */
    public static RequestVoteMessage decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            String candidateId = LogEntry.readUtf8(in);
            long lastLogIndex = in.readLong();
            long lastLogTerm = in.readLong();
            return new RequestVoteMessage(term, candidateId, lastLogIndex, lastLogTerm);
        } catch (Exception e) {
            throw new RuntimeException("RequestVoteMessage decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "RequestVoteMessage{term=" + term + ", candidateId=" + candidateId
                + ", lastLogIndex=" + lastLogIndex + ", lastLogTerm=" + lastLogTerm + '}';
    }
}
