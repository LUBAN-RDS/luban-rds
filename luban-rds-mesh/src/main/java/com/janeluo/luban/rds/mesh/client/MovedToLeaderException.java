package com.janeluo.luban.rds.mesh.client;

/**
 * 当前节点非 Leader，写请求需要重定向到 Leader（DESIGN.md §5.3 / 阶段 6 完善地址）。
 * <p>
 * 由 {@code MeshNode.propose} 在 {@code role != LEADER} 时抛出；{@code RedisServerHandler}
 * 的专用 catch（阶段 6 新增）捕获后生成 {@code -MOVED}/{@code -MESHDOWN} 响应。
 * </p>
 *
 * <h3>阶段 4 占位</h3>
 * <p>
 * 阶段 4 只需表达「不是 Leader」语义；阶段 6 完善 leader 的 service 地址（ip:port）
 * 与 key 真实 CRC16 slot，供 {@code MeshClientRedirector} 构造精确的 MOVED 字符串。
 * 当前 leaderAddr 可为 {@code null}（无 Leader / 未知）。
 * </p>
 */
public class MovedToLeaderException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Leader 的 service 地址（{@code host:port}）；未知/无 Leader 时为 {@code null}。 */
    private final String leaderAddr;

    /**
     * @param leaderAddr Leader 的 service 地址（{@code host:port}）；无 Leader 时传 {@code null}
     */
    public MovedToLeaderException(String leaderAddr) {
        super("not leader; redirect to leader "
                + (leaderAddr != null ? leaderAddr : "<unknown>"));
        this.leaderAddr = leaderAddr;
    }

    public MovedToLeaderException(String leaderAddr, String message) {
        super(message);
        this.leaderAddr = leaderAddr;
    }

    /** Leader 的 service 地址；未知/无 Leader 时返回 {@code null}。 */
    public String getLeaderAddr() {
        return leaderAddr;
    }
}
