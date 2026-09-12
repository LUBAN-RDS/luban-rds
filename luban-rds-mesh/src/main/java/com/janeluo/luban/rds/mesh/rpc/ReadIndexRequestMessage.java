package com.janeluo.luban.rds.mesh.rpc;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * readIndex 读点请求（{@code READ_INDEX_REQ}，0x66；fix-mesh-follower-read）。
 * <p>Follower → Leader：取一个经租约背书的读点。请求-响应关联靠 {@code requestId}
 * （{@code MeshBusClient.send} 是 fire-and-forget，无回调）。</p>
 * <pre>
 * long term;        // 发起方当前任期
 * long requestId;   // 单调递增，响应原样回填
 * </pre>
 */
public class ReadIndexRequestMessage extends MeshRpcMessage {

    /** body 固定长度：term(8) + requestId(8)。短于该值视为截断/畸形。 */
    private static final int MIN_BODY_LENGTH = 16;

    private final long requestId;

    public ReadIndexRequestMessage(long term, long requestId) {
        super(term);
        this.requestId = requestId;
    }

    public long getRequestId() {
        return requestId;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        out.writeLong(requestId);
    }

    public static ReadIndexRequestMessage decode(byte[] body) {
        if (body == null || body.length < MIN_BODY_LENGTH) {
            throw new IllegalArgumentException("ReadIndexRequestMessage body 过短: "
                    + (body == null ? "null" : body.length) + " < " + MIN_BODY_LENGTH);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(body))) {
            long term = in.readLong();
            long requestId = in.readLong();
            return new ReadIndexRequestMessage(term, requestId);
        } catch (Exception e) {
            throw new IllegalArgumentException("ReadIndexRequestMessage decode 失败", e);
        }
    }
}
