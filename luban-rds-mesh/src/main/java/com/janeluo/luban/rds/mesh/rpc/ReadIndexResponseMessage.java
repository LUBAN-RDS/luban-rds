package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;

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

    /** body 最小长度：term(8) + requestId(8) + readIndex(8) + success(1) + nodeId length prefix(4)。 */
    private static final int MIN_BODY_LENGTH = 29;

    /** leaderNodeId 字节长度上界：与帧头 {@link MeshFrame#NODE_ID_LENGTH} 的 nodeId 约定一致。 */
    private static final int MAX_NODE_ID_BYTES = MeshFrame.NODE_ID_LENGTH;

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
        if (body == null || body.length < MIN_BODY_LENGTH) {
            throw new IllegalArgumentException("ReadIndexResponseMessage body 过短: "
                    + (body == null ? "null" : body.length) + " < " + MIN_BODY_LENGTH);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            long term = in.readLong();
            long requestId = in.readLong();
            long readIndex = in.readLong();
            boolean success = in.readBoolean();
            String leaderNodeId = readBoundedNodeId(in);
            return new ReadIndexResponseMessage(term, requestId, readIndex, success, leaderNodeId);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("ReadIndexResponseMessage decode 失败", e);
        }
    }

    /**
     * 读 leaderNodeId：先校验 length-prefix 上界再分配数组。
     * <p>共享的 {@link LogEntry#readUtf8} 直接 {@code new byte[len]}，恶意超长 prefix 会抛
     * {@link OutOfMemoryError}（{@code Error} 绕过 {@code onMessage} 的 {@code catch (Exception)}，
     * 可打断分发路径）。此处内联做有界读取，不改动共享编解码工具。</p>
     */
    private static String readBoundedNodeId(DataInputStream in) throws Exception {
        int len = in.readInt();
        if (len < 0) {
            return null;                                // -1 = null，与 LogEntry.writeUtf8 约定一致
        }
        if (len > MAX_NODE_ID_BYTES) {
            throw new IllegalArgumentException("leaderNodeId 长度超限: " + len + " > " + MAX_NODE_ID_BYTES);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
