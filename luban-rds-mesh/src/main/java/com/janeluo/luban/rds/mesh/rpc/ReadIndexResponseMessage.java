package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * readIndex 读点应答（{@code READ_INDEX_RESP}，0x67；fix-mesh-follower-read）。
 * <p>Leader → Follower。{@code success=false} 时 {@code leaderNodeId} 给出已知 Leader
 * （供发起方直接回落 MOVED，不必白等超时）。</p>
 * <pre>
 * long term;            // Leader 当前任期
 * long requestId;       // 原样回填
 * long readIndex;       // success=true 时为 Leader 的 commitIndex
 * boolean success;      // 是否给出读点
 * String leaderNodeId;  // 非 Leader 应答时给出；可 null
 * </pre>
 */
public class ReadIndexResponseMessage extends MeshRpcMessage {

    private final long requestId;
    private final long readIndex;
    private final boolean success;
    private final String leaderNodeId;

    public ReadIndexResponseMessage(long term, long requestId, long readIndex,
                                    boolean success, String leaderNodeId) {
        super(term);
        this.requestId = requestId;
        this.readIndex = readIndex;
        this.success = success;
        this.leaderNodeId = leaderNodeId;
    }

    public long getRequestId() {
        return requestId;
    }

    public long getReadIndex() {
        return readIndex;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getLeaderNodeId() {
        return leaderNodeId;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        out.writeLong(requestId);
        out.writeLong(readIndex);
        out.writeBoolean(success);
        LogEntry.writeUtf8(out, leaderNodeId);
    }

    public static ReadIndexResponseMessage decode(byte[] body) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            long term = in.readLong();
            long requestId = in.readLong();
            long readIndex = in.readLong();
            boolean success = in.readBoolean();
            String leaderNodeId = LogEntry.readUtf8(in);
            return new ReadIndexResponseMessage(term, requestId, readIndex, success, leaderNodeId);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReadIndexResponseMessage decode 失败", e);
        }
    }
}
