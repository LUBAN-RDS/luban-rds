package com.janeluo.luban.rds.cluster.gossip;

import java.util.HashMap;
import java.util.Map;

/**
 * Gossip 消息类型枚举
 * <p>
 * 定义集群节点间通信的消息类型
 * </p>
 */
public enum GossipMessageType {
    /**
     * 心跳请求 - 用于检测节点存活状态和交换集群信息
     */
    PING((byte) 0x00),

    /**
     * 心跳响应 - 响应 PING 消息
     */
    PONG((byte) 0x01),

    /**
     * 加入集群请求 - 新节点请求加入集群
     */
    MEET((byte) 0x02),

    /**
     * 节点下线通知 - 广播节点下线状态
     */
    FAIL((byte) 0x03),

    /**
     * Pub/Sub 消息传播 - 在集群间传播发布订阅消息
     */
    PUBLISH((byte) 0x04),

    /**
     * 故障转移授权请求 - 从节点请求投票成为主节点
     */
    FAILOVER_AUTH_REQUEST((byte) 0x05),

    /**
     * 故障转移授权确认 - 主节点投票响应
     */
    FAILOVER_AUTH_ACK((byte) 0x06),

    /**
     * 配置更新通知 - 通知配置变更
     */
    UPDATE((byte) 0x07);

    /**
     * 消息类型编码
     */
    private final byte code;

    /**
     * 编码到枚举的映射（用于快速查找）
     */
    private static final Map<Byte, GossipMessageType> CODE_MAP = new HashMap<>();

    static {
        for (GossipMessageType type : values()) {
            CODE_MAP.put(type.code, type);
        }
    }

    GossipMessageType(byte code) {
        this.code = code;
    }

    /**
     * 获取消息类型编码
     *
     * @return 消息类型编码
     */
    public byte getCode() {
        return code;
    }

    /**
     * 根据编码获取消息类型
     *
     * @param code 消息类型编码
     * @return 对应的消息类型，如果不存在则返回 null
     */
    public static GossipMessageType fromCode(byte code) {
        return CODE_MAP.get(code);
    }

    /**
     * 检查编码是否有效
     *
     * @param code 消息类型编码
     * @return 是否为有效的消息类型编码
     */
    public static boolean isValidCode(byte code) {
        return CODE_MAP.containsKey(code);
    }
}
