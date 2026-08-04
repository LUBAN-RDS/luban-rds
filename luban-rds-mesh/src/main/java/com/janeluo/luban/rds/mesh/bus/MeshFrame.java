package com.janeluo.luban.rds.mesh.bus;

import java.nio.charset.StandardCharsets;

/**
 * Mesh 总线传输帧（POJO）。
 * <p>
 * 作为 {@link MeshBusCodec} 的编码输入与解码输出对象。它只承载帧级三字段
 * （senderNodeId / type / body），不感知 term 等语义——term 由 RPC 层在 body 内
 * 自行序列化（见 DESIGN.md §4.2「无 term 字段」）。
 * </p>
 * <p>
 * 阶段 2 的 RPC 消息（如 {@code AppendEntriesMessage}）序列化为 byte[] 后包成
 * MeshFrame 走总线；接收端 MeshBusHandler 拿到 MeshFrame 后按 type 反序列化 body。
 * </p>
 *
 * <h3>帧格式（与 cluster GossipMessage 同构，45B 帧头，大端 length）</h3>
 * <pre>
 * ┌────────────────────────────────────────────┐
 * │ 40B senderNodeId │ 1B type │ 4B len(BE)    │ = 45B 帧头，无 term
 * ├────────────────────────────────────────────┤
 * │ body...（≤ 16MB）                           │
 * └────────────────────────────────────────────┘
 * </pre>
 */
public class MeshFrame {

    /** 帧头固定长度：40（nodeId）+ 1（type）+ 4（length）= 45 字节 */
    public static final int HEADER_LENGTH = 45;

    /** nodeId 长度：40 字符 hex（SHA-1，160bit） */
    public static final int NODE_ID_LENGTH = 40;

    /** 单帧 body 上限：16MB（与 cluster MAX_BODY_LENGTH 对齐） */
    public static final int MAX_BODY_LENGTH = 16 * 1024 * 1024;

    /** 40 字符 hex 发送者 nodeId（ASCII 字节写入帧头） */
    private String senderNodeId;

    /** 消息类型码（帧头第 41 字节）。保留为 byte，编解码不强制校验范围，由 Decoder 解析时校验 */
    private byte type;

    /** 消息体（可为空，长度 ≤ {@link #MAX_BODY_LENGTH}） */
    private byte[] body;

    public MeshFrame() {
    }

    /**
     * @param senderNodeId 40 字符 hex 发送者 nodeId
     * @param type         消息类型码（见 {@link MessageType#getCode()}）
     * @param body         消息体；null 视为 0 长度
     */
    public MeshFrame(String senderNodeId, byte type, byte[] body) {
        this.senderNodeId = senderNodeId;
        this.type = type;
        this.body = body;
    }

    public String getSenderNodeId() {
        return senderNodeId;
    }

    public void setSenderNodeId(String senderNodeId) {
        this.senderNodeId = senderNodeId;
    }

    public byte getType() {
        return type;
    }

    public void setType(byte type) {
        this.type = type;
    }

    /**
     * 取消息体；保证返回非 null（空帧返回长度 0 的数组），便于上层无空判使用。
     *
     * @return 消息体字节数组（非 null）
     */
    public byte[] getBody() {
        return body == null ? new byte[0] : body;
    }

    public void setBody(byte[] body) {
        this.body = body;
    }

    /** body 长度（body 为 null 时返回 0） */
    public int getBodyLength() {
        return body == null ? 0 : body.length;
    }

    @Override
    public String toString() {
        String id = senderNodeId == null ? "?"
                : (senderNodeId.length() > 8 ? senderNodeId.substring(0, 8) : senderNodeId);
        return "MeshFrame{type=0x" + Integer.toHexString(type & 0xFF)
                + ", from=" + id + ", bodyLen=" + getBodyLength() + '}';
    }
}
