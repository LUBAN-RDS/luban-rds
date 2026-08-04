package com.janeluo.luban.rds.mesh.rpc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * AppendEntries RPC 响应（DESIGN.md §4.3，消息类型 {@code 0x61}）。
 * <p>
 * Follower → Leader：复制结果。{@code success=true} 同时作为租约确认（DESIGN §5.7）。
 * Follower 必须<strong>在日志落盘（fsync）完成后</strong>才返回 {@code success=true}（DESIGN §5.1）。
 * </p>
 *
 * <pre>
 * long term;          // Follower 当前任期（用于 Leader 更新自己）
 * boolean success;    // 是否接受（任期/日志一致性校验 + 已落盘）
 * long matchIndex;    // Follower 已确认的最高索引
 * </pre>
 */
public class AppendEntriesResponse extends MeshRpcMessage {

    private final boolean success;
    private final long matchIndex;

    /**
     * @param term       Follower 当前任期
     * @param success    是否接受
     * @param matchIndex Follower 已确认的最高索引
     */
    public AppendEntriesResponse(long term, boolean success, long matchIndex) {
        super(term);
        this.success = success;
        this.matchIndex = matchIndex;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getMatchIndex() {
        return matchIndex;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        out.writeBoolean(success);
        out.writeLong(matchIndex);
    }

    /**
     * 反序列化 byte[] 为 {@link AppendEntriesResponse}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code APPEND_ENTRIES_RESP} 分支调用。</p>
     */
    public static AppendEntriesResponse decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            boolean success = in.readBoolean();
            long matchIndex = in.readLong();
            return new AppendEntriesResponse(term, success, matchIndex);
        } catch (Exception e) {
            throw new RuntimeException("AppendEntriesResponse decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "AppendEntriesResponse{term=" + term + ", success=" + success
                + ", matchIndex=" + matchIndex + '}';
    }
}
