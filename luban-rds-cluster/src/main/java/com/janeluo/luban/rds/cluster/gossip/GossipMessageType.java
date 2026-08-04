package com.janeluo.luban.rds.cluster.gossip;

import java.util.HashMap;
import java.util.Map;

/**
 * Gossip 消息类型枚举
 * <p>
 * 定义集群节点间通信的消息类型
 * </p>
 * <p>
 * 消息码从 0x40 起编号（N-8）：Redis 7 集群总线消息类型码占用 0x00-0x21
 * （PING=0x00 ... MFSTART=0x08、MODULE=0x09、PUBLISHSHARD=0x0A、
 * SHARD_MIGRATE=0x0B、SHARD_ACK=0x0C ...），本实现是独立实现、不与
 * 真实 Redis 混布，但消息码撞段会在混布排查时造成误导（例如本实现的
 * FAILOVER_RESULT=0x08 恰好是 Redis 的 MFSTART）。从 0x40 起编号
 * 彻底避开 Redis 已占用区间，保持各消息相对顺序。
 * </p>
 */
public enum GossipMessageType {
    /**
     * 心跳请求 - 用于检测节点存活状态和交换集群信息
     */
    PING((byte) 0x40),

    /**
     * 心跳响应 - 响应 PING 消息
     */
    PONG((byte) 0x41),

    /**
     * 加入集群请求 - 新节点请求加入集群
     */
    MEET((byte) 0x42),

    /**
     * 节点下线通知 - 广播节点下线状态
     */
    FAIL((byte) 0x43),

    /**
     * Pub/Sub 消息传播 - 在集群间传播发布订阅消息
     */
    PUBLISH((byte) 0x44),

    /**
     * 故障转移授权请求 - 从节点请求投票成为主节点
     */
    FAILOVER_AUTH_REQUEST((byte) 0x45),

    /**
     * 故障转移授权确认 - 主节点投票响应
     */
    FAILOVER_AUTH_ACK((byte) 0x46),

    /**
     * 配置更新通知 - 通知配置变更
     */
    UPDATE((byte) 0x47),

    /**
     * 故障转移结果通知 - 胜选 slave 广播自己已提升为新 master
     */
    FAILOVER_RESULT((byte) 0x48),

    /**
     * 键迁移请求 - MIGRATE 命令通过总线传输单个键到目标节点
     */
    MIGRATE_KEY((byte) 0x49),

    /**
     * 键迁移确认 - 目标节点收到键后回复源节点
     */
    MIGRATE_KEY_ACK((byte) 0x4A),

    /**
     * 手动故障转移启动（P1-12）- slave→master，请求 master 暂停写并回传当前 offset。
     * 对齐 Redis CLUSTERMSG_TYPE_MFSTART 的语义（消息码从 0x4B 起，避开 Redis 码段）。
     */
    MANUAL_FAILOVER_START((byte) 0x4B),

    /**
     * 手动故障转移 offset 回传（P1-12）- master→slave，携带 master 暂停写时的复制偏移量。
     * slave 须追平到此 offset 后才执行提升，保证手动 failover 不丢数据。
     */
    MANUAL_FAILOVER_OFFSET((byte) 0x4C);

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

    /**
     * CLUSTER INFO per-type 消息计数用的展示名（小写，对齐 Redis clusterGetMessageTypeString）。
     * <p>
     * Redis 名：ping/pong/meet/fail/publish/auth-req/auth-ack/update/mfstart；
     * 本实现独有消息（Redis 无对应类型）采用小写连字符命名，监控解析友好。
     * </p>
     *
     * @return 展示名
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * 展示名（N-26 输出补全）
     */
    private final String displayName;

    GossipMessageType(byte code) {
        this.code = code;
        this.displayName = defaultDisplayName(name());
    }

    private static String defaultDisplayName(String enumName) {
        switch (enumName) {
            case "FAILOVER_AUTH_REQUEST":
                return "auth-req";
            case "FAILOVER_AUTH_ACK":
                return "auth-ack";
            case "MANUAL_FAILOVER_START":
                // 对齐 Redis clusterGetMessageTypeString 的 "mfstart"
                return "mfstart";
            case "MANUAL_FAILOVER_OFFSET":
                return "mf-offset";
            case "FAILOVER_RESULT":
                return "failover-result";
            case "MIGRATE_KEY":
                return "migrate-key";
            case "MIGRATE_KEY_ACK":
                return "migrate-key-ack";
            default:
                // PING/PONG/MEET/FAIL/PUBLISH/UPDATE → 小写原名
                return enumName.toLowerCase();
        }
    }
}
