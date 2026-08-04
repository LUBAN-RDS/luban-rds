package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/**
 * Mesh RPC 消息抽象基类（DESIGN.md §4.3）。
 * <p>
 * 所有 RPC 消息继承本类。Raft 要求<strong>每个 RPC 都携带 term</strong>（任期校验由上层完成），
 * 故 term 作为公共字段放在消息体内（帧头无 term，见 DESIGN §4.2）。
 * </p>
 *
 * <h3>编解码模型</h3>
 * <ul>
 *   <li>基类负责 {@code term}（long）的编解码：{@link #encodeHeader(DataOutputStream)} 写 term，
 *       {@link #encode()} 串接 term + body</li>
 *   <li>子类实现 {@link #encodeBody(DataOutputStream)} / 由各自 decode 读 body（各自字段）</li>
 *   <li>每类提供 {@link #encode()} 返回 byte[]（= {@code MeshFrame.body}）</li>
 *   <li>静态 {@link #decode(MessageType, byte[])} 总入口：按 type switch 到对应子类的 decode</li>
 * </ul>
 *
 * <h3>编码约定（与 {@link LogEntry} 一致）</h3>
 * <ul>
 *   <li>String 用 UTF-8 + length-prefix（int 长度 + 字节）</li>
 *   <li>byte[] 同样 length-prefix（{@code null} 写 -1）</li>
 *   <li>long 8 字节、int 4 字节、boolean 1 字节（均由 {@link DataOutputStream} 大端写入）</li>
 *   <li>{@code List<LogEntry>}：先写 int size，再逐个 {@link LogEntry#encode()}</li>
 * </ul>
 */
public abstract class MeshRpcMessage {

    /** Raft 要求每个 RPC 携带 term（发送方当前任期） */
    protected final long term;

    protected MeshRpcMessage(long term) {
        this.term = term;
    }

    /** 取本消息的 term（用于接收方任期裁决：若 RPC.term &gt; currentTerm 则更新自身 term）。 */
    public long getTerm() {
        return term;
    }

    /**
     * 子类写各自 body 字段（不含 term；term 由基类统一写入）。
     */
    protected abstract void encodeBody(DataOutputStream out) throws Exception;

    /**
     * 序列化为 byte[]：先写 term（8 字节），再写 body。
     *
     * @return 序列化字节（作为 {@code MeshFrame.body}）
     */
    public byte[] encode() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(term);
            encodeBody(out);
            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(getClass().getSimpleName() + " encode 失败", e);
        }
    }

    /**
     * 总入口：按 {@link MessageType} 分发到对应子类的 decode。
     * <p>
     * {@code MeshBusHandler} 收到 {@code MeshFrame} 后，调
     * {@code MeshRpcMessage.decode(MessageType.fromCode(frame.getType()), frame.getBody())}
     * 反序列化为具体 RPC 类。
     * </p>
     *
     * @param type 消息类型
     * @param body 消息体（= {@code MeshFrame.body}）
     * @return 对应子类实例
     * @throws IllegalArgumentException 未知类型
     */
    public static MeshRpcMessage decode(MessageType type, byte[] body) {
        switch (type) {
            case APPEND_ENTRIES:
                return AppendEntriesMessage.decode(body);
            case APPEND_ENTRIES_RESP:
                return AppendEntriesResponse.decode(body);
            case REQUEST_VOTE:
                return RequestVoteMessage.decode(body);
            case REQUEST_VOTE_RESP:
                return RequestVoteResponse.decode(body);
            case INSTALL_SNAPSHOT:
                return InstallSnapshotMessage.decode(body);
            default:
                throw new IllegalArgumentException("未知的 mesh RPC 消息类型: " + type);
        }
    }
}
