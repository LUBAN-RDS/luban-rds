package com.janeluo.luban.rds.mesh.bus;

import java.util.HashMap;
import java.util.Map;

/**
 * Mesh 总线消息类型枚举（0x60-0x67）。
 * <p>
 * 与 cluster 模块（{@code GossipMessageType}，0x40 起）的码段不冲突；
 * 对应 Raft 的 5 类 RPC（见 DESIGN.md §4.1），另加 follower 读用的 readIndex 一对。
 * </p>
 * <ul>
 *   <li>{@link #APPEND_ENTRIES}      0x60 Leader → Follower：心跳 + 日志复制</li>
 *   <li>{@link #APPEND_ENTRIES_RESP} 0x61 Follower → Leader：复制结果</li>
 *   <li>{@link #REQUEST_VOTE}        0x62 Candidate → All：选举投票请求</li>
 *   <li>{@link #REQUEST_VOTE_RESP}   0x63 All → Candidate：投票结果</li>
 *   <li>{@link #INSTALL_SNAPSHOT}    0x64 Leader → Follower：快照传输</li>
 *   <li>{@link #BUS_HELLO}           0x65 Client → Server：连接握手认证（P1-12，
 *       body=mesh-auth-token UTF-8 字节，senderNodeId=发起方 nodeId；token 未配置时不发送）</li>
 *   <li>{@link #READ_INDEX_REQ}      0x66 Follower → Leader：请求读点（readIndex）</li>
 *   <li>{@link #READ_INDEX_RESP}     0x67 Leader → Follower：读点应答</li>
 * </ul>
 */
public enum MessageType {

    APPEND_ENTRIES((byte) 0x60),
    APPEND_ENTRIES_RESP((byte) 0x61),
    REQUEST_VOTE((byte) 0x62),
    REQUEST_VOTE_RESP((byte) 0x63),
    INSTALL_SNAPSHOT((byte) 0x64),
    BUS_HELLO((byte) 0x65),

    /**
     * {@link #READ_INDEX_REQ}    0x66 Follower → Leader：请求读点（readIndex）
     * <p>body: long term; long requestId;</p>
     * @see #READ_INDEX_RESP
     */
    READ_INDEX_REQ((byte) 0x66),
    /**
     * {@link #READ_INDEX_RESP}   0x67 Leader → Follower：读点应答
     * <p>body: long term; long requestId; long readIndex; boolean success; String leaderNodeId;</p>
     */
    READ_INDEX_RESP((byte) 0x67);

    private final byte code;

    /** code → MessageType 反查表（启动时构建一次） */
    private static final Map<Byte, MessageType> CODE_MAP = new HashMap<>();

    static {
        for (MessageType type : values()) {
            CODE_MAP.put(type.code, type);
        }
    }

    MessageType(byte code) {
        this.code = code;
    }

    /**
     * 取消息类型码（写入帧头第 41 字节）。
     *
     * @return 类型码
     */
    public byte getCode() {
        return code;
    }

    /**
     * 由类型码反查枚举。
     *
     * @param code 类型码
     * @return 对应的 MessageType
     * @throws IllegalArgumentException 未知类型码（不在 0x60-0x67 范围内）
     */
    public static MessageType fromCode(byte code) {
        MessageType type = CODE_MAP.get(code);
        if (type == null) {
            throw new IllegalArgumentException("未知的 mesh 消息类型编码: 0x"
                    + Integer.toHexString(code & 0xFF));
        }
        return type;
    }
}
