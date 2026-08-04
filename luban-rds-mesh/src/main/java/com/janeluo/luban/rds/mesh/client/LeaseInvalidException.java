package com.janeluo.luban.rds.mesh.client;

/**
 * Leader 租约失效且续租等待超时（或 read-index 主动确认失败），读路径拒绝服务（DESIGN.md §5.7）。
 * <p>
 * 由 {@code MeshWriteGate.read} 在以下场景抛出，供上层 {@code RedisServerHandler}（阶段 12 集成）
 * catch 后向客户端返回明确错误，让客户端重试：
 * <ul>
 *   <li><b>lease 模式</b>：租约失效，{@code LeaseManager.awaitValid} 等待续租超时仍无效。
 *       典型场景：旧 Leader 被分区隔离后租约过期（{@code invalidate} 或心跳 ACK 不足），
 *       读路径不放行陈旧读，抛本异常让客户端重试（可能经 MOVED 连上新 Leader）。</li>
 *   <li><b>read-index 模式</b>：主动确认（等当前心跳多数派 ACK 续租）超时，说明多数派未及时 ACK，
 *       当前节点可能已非真 Leader，抛本异常。</li>
 * </ul>
 * </p>
 *
 * <h3>为什么不直接放行本地读</h3>
 * <p>
 * 强一致卖点的关键：旧 Leader 被隔开后不知情，若仍本地回答读请求，客户端经 MOVED 连上它读到陈旧数据，
 * 破坏强一致。故租约失效时必须拒绝读（DESIGN §5.7 / §9 风险表「旧 Leader 分区」）。
 * </p>
 *
 * <h3>与 {@link MovedToLeaderException} 的区别</h3>
 * <ul>
 *   <li>{@link MovedToLeaderException}：当前节点明确非 Leader（角色已转换），上层生成 MOVED/MESHDOWN。</li>
 *   <li>{@link LeaseInvalidException}：当前节点角色仍是 Leader，但租约失效（可能正在分区），
 *       无法给出确定的 Leader 地址，上层应返回错误让客户端重试（而非 MOVED 到可能也失效的自己）。</li>
 * </ul>
 * </p>
 */
public class LeaseInvalidException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LeaseInvalidException(String message) {
        super(message);
    }

    public LeaseInvalidException(String message, Throwable cause) {
        super(message, cause);
    }
}
