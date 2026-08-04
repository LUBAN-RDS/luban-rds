package com.janeluo.luban.rds.mesh.client;

/**
 * 当前节点非 Leader，写/读请求需要重定向到 Leader（DESIGN.md §5.3 / 阶段 6 完善地址 + key）。
 * <p>
 * 由 {@code MeshNode.propose} 在 {@code role != LEADER} 时抛出（或读路径在非 Leader 时由
 * {@code MeshWriteGate.read} 抛出）；{@code RedisServerHandler} 的专用 catch（阶段 6 新增）
 * 捕获后交由 {@link com.janeluo.luban.rds.mesh.client.MeshClientRedirector} 生成
 * {@code -MOVED}/{@code -MESHDOWN} 响应。
 * </p>
 *
 * <h3>字段语义（DESIGN §5.3 / §11 决策 12）</h3>
 * <ul>
 *   <li>{@code leaderNodeId}：Leader 的 nodeId；未知/无 Leader 时为 {@code null}。</li>
 *   <li>{@code leaderServiceAddr}：Leader 的 service 地址（{@code "host:port"}，<b>service 端口，
 *       非 bus 端口</b>）；未知/无 Leader 时为 {@code null} → 触发 {@code -MESHDOWN}。</li>
 *   <li>{@code key}：触发重定向的命令 key（用于算真实 CRC16 slot）；可能为 {@code null}
 *       （slot=0）。部分集群感知客户端依赖 MOVED 中的 slot 更新本地路由缓存，
 *       故必须用真实 CRC16（{@link com.janeluo.luban.rds.common.util.SlotUtils#getSlot}），而非占位值。</li>
 * </ul>
 *
 * <h3>已知 vs 未知 Leader</h3>
 * <ul>
 *   <li>已知 Leader：{@code leaderServiceAddr = "ip:port"} → {@code -MOVED <slot> <ip:port>}；</li>
 *   <li>未知/无 Leader：{@code leaderServiceAddr = null} → {@code -MESHDOWN The mesh cluster has no leader}。</li>
 * </ul>
 *
 * <h3>兼容性</h3>
 * <p>
 * 保留旧的 {@link #MovedToLeaderException(String)} 单参构造器与 {@link #getLeaderAddr()}，
 * 供阶段 4/5 已有调用方（{@code MeshNode.propose} / {@code MeshWriteGate.read}，传入 nodeId 占位）
 * 与既有测试（{@code MeshWriteGateTest} 断言 {@code getLeaderAddr() == 构造参数}）继续工作。
 * 新的 leaderServiceAddr 与 leaderNodeId 为同值时由单参构造器一并填充。
 * </p>
 */
public class MovedToLeaderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Leader 的 nodeId；未知/无 Leader 时为 {@code null}。 */
    private final String leaderNodeId;
    /**
     * Leader 的 service 地址（{@code host:port}，service 端口）；未知/无 Leader 时为 {@code null}。
     * <p>等价于历史字段 {@code leaderAddr}（保留别名以兼容 {@link #getLeaderAddr()}）。</p>
     */
    private final String leaderServiceAddr;
    /** 触发重定向的 key（用于算真实 CRC16 slot）；可能为 {@code null}（slot=0）。 */
    private final String key;

    // ==================== 兼容阶段 4/5 的构造器（保留旧 API） ====================

    /**
     * 兼容构造器：仅传入 leader 地址（阶段 4/5 的 {@code MeshNode.propose}/{@code MeshWriteGate.read}
     * 用 nodeId 占位时调用）。
     * <p>
     * 此构造器把 {@code leaderAddr} 同时赋给 {@code leaderServiceAddr} 与 {@code leaderNodeId=null}、
     * {@code key=null}，保证既有 {@code getLeaderAddr()} 调用方语义不变。
     * 新代码应优先使用 {@link #MovedToLeaderException(String, String, String)}。
     * </p>
     *
     * @param leaderAddr Leader 的 service 地址（{@code host:port}）；无 Leader 时传 {@code null}
     */
    public MovedToLeaderException(String leaderAddr) {
        super("not leader; redirect to leader "
                + (leaderAddr != null ? leaderAddr : "<unknown>"));
        this.leaderNodeId = null;
        this.leaderServiceAddr = leaderAddr;
        this.key = null;
    }

    /**
     * 兼容构造器：自定义 message。
     *
     * @param leaderAddr Leader 的 service 地址；无 Leader 时传 {@code null}
     * @param message    自定义异常消息
     */
    public MovedToLeaderException(String leaderAddr, String message) {
        super(message);
        this.leaderNodeId = null;
        this.leaderServiceAddr = leaderAddr;
        this.key = null;
    }

    // ==================== 阶段 6 新增构造器 ====================

    /**
     * 阶段 6 完整构造器：携带 leader nodeId / serviceAddr / 触发重定向的 key。
     *
     * @param leaderNodeId      Leader 的 nodeId；未知/无 Leader 时传 {@code null}
     * @param leaderServiceAddr Leader 的 service 地址（{@code "host:port"}，service 端口）；
     *                          无 Leader 时传 {@code null}（将触发 {@code -MESHDOWN}）
     * @param key               触发重定向的 key（用于算真实 CRC16 slot）；未知时传 {@code null}（slot=0）
     */
    public MovedToLeaderException(String leaderNodeId, String leaderServiceAddr, String key) {
        super("not leader; redirect to leader "
                + (leaderServiceAddr != null ? leaderServiceAddr : "<unknown>")
                + (leaderNodeId != null ? " (" + leaderNodeId + ")" : "")
                + (key != null ? " key=" + key : ""));
        this.leaderNodeId = leaderNodeId;
        this.leaderServiceAddr = leaderServiceAddr;
        this.key = key;
    }

    // ==================== getter ====================

    /** Leader 的 service 地址（{@code host:port}）；未知/无 Leader 时返回 {@code null}。 */
    public String getLeaderServiceAddr() {
        return leaderServiceAddr;
    }

    /** Leader 的 nodeId；未知/无 Leader 时返回 {@code null}。 */
    public String getLeaderNodeId() {
        return leaderNodeId;
    }

    /** 触发重定向的 key（用于算 MOVED slot）；未知时返回 {@code null}（slot=0）。 */
    public String getKey() {
        return key;
    }

    /**
     * 兼容 getter：返回 {@link #getLeaderServiceAddr()}。
     * <p>历史字段名，保留以兼容阶段 4/5 既有调用方与 {@code MeshWriteGateTest} 断言。</p>
     *
     * @return Leader 的 service 地址；未知/无 Leader 时返回 {@code null}
     */
    public String getLeaderAddr() {
        return leaderServiceAddr;
    }
}
