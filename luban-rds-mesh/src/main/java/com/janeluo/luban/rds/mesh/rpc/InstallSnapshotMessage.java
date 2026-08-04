package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * InstallSnapshot RPC 请求（DESIGN.md §4.3 / §5.4，消息类型 {@code 0x64}）。
 * <p>
 * Leader → Follower：快照传输。v1.2 采用 <strong>chunked</strong> 传输（默认 chunk 4MB）：
 * 单帧 body ≤ 16MB，几百 MB 的快照无法单帧传输，故按 {@link #offset} 切片发多个 INSTALL_SNAPSHOT，
 * Follower 累积拼装，{@link #done}=true 时整体加载（DESIGN §5.4）。
 * </p>
 *
 * <pre>
 * long term;                 // Leader 任期
 * String leaderId;           // Leader nodeId
 * long lastIncludedTerm;     // 快照对应的最后任期
 * long lastIncludedIndex;    // 快照对应的最后索引
 * long offset;               // chunked 传输偏移（v1 固定 0，阶段 10 启用）
 * byte[] data;               // RDB 字节（chunk）
 * boolean done;              // 是否最后一个 chunk
 * </pre>
 */
public class InstallSnapshotMessage extends MeshRpcMessage {

    private final String leaderId;
    private final long lastIncludedTerm;
    private final long lastIncludedIndex;
    private final long offset;
    private final byte[] data;
    private final boolean done;

    /**
     * @param term             Leader 任期
     * @param leaderId         Leader nodeId
     * @param lastIncludedTerm 快照对应的最后任期
     * @param lastIncludedIndex 快照对应的最后索引
     * @param offset           chunked 传输偏移（v1 固定 0；阶段 10 起按 chunk 偏移）
     * @param data             RDB 字节（chunk；null 归一化为空数组）
     * @param done             是否最后一个 chunk
     */
    public InstallSnapshotMessage(long term, String leaderId, long lastIncludedTerm, long lastIncludedIndex,
                                  long offset, byte[] data, boolean done) {
        super(term);
        this.leaderId = leaderId;
        this.lastIncludedTerm = lastIncludedTerm;
        this.lastIncludedIndex = lastIncludedIndex;
        this.offset = offset;
        this.data = data == null ? new byte[0] : data;
        this.done = done;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public long getLastIncludedTerm() {
        return lastIncludedTerm;
    }

    public long getLastIncludedIndex() {
        return lastIncludedIndex;
    }

    /** chunked 传输偏移（v1 固定 0，阶段 10 启用）。 */
    public long getOffset() {
        return offset;
    }

    /** RDB 字节 chunk（保证非 null）。 */
    public byte[] getData() {
        return data;
    }

    /** 是否最后一个 chunk。 */
    public boolean isDone() {
        return done;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        LogEntry.writeUtf8(out, leaderId);
        out.writeLong(lastIncludedTerm);
        out.writeLong(lastIncludedIndex);
        out.writeLong(offset);
        LogEntry.writeBytes(out, data);
        out.writeBoolean(done);
    }

    /**
     * 反序列化 byte[] 为 {@link InstallSnapshotMessage}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code INSTALL_SNAPSHOT} 分支调用。</p>
     */
    public static InstallSnapshotMessage decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            String leaderId = LogEntry.readUtf8(in);
            long lastIncludedTerm = in.readLong();
            long lastIncludedIndex = in.readLong();
            long offset = in.readLong();
            byte[] data = LogEntry.readBytes(in);
            boolean done = in.readBoolean();
            return new InstallSnapshotMessage(term, leaderId, lastIncludedTerm, lastIncludedIndex,
                    offset, data, done);
        } catch (Exception e) {
            throw new RuntimeException("InstallSnapshotMessage decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "InstallSnapshotMessage{term=" + term + ", leaderId=" + leaderId
                + ", lastIncluded=" + lastIncludedIndex + "/" + lastIncludedTerm
                + ", offset=" + offset + ", dataLen=" + data.length + ", done=" + done + '}';
    }
}
