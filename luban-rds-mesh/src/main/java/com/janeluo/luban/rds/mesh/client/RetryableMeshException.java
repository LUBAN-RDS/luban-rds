package com.janeluo.luban.rds.mesh.client;

/**
 * mesh 层"瞬时不可用、稍后可恢复"错误。
 * <p>
 * 由 {@code MeshNode.failPendingProposalsOnLeadershipLoss}（Leader 刚降级、新 Leader 未知）
 * 和 {@code MeshWriteGate.write}（propose 超时）抛出。{@code RedisServerHandler} 的专用 catch
 * 捕获后返回 Redis 标准 {@code -TRYAGAIN}，让集群感知客户端（Redisson/Jedis/Lettuce）自动重试。
 * </p>
 * <p>
 * 与 {@link MovedToLeaderException} 的区别：MOVED 表示"已知新 Leader，去那里"；
 * RetryableMeshException 表示"新 Leader 还没选出，稍后重试"。
 * </p>
 */
public class RetryableMeshException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RetryableMeshException(String message) {
        super(message);
    }

    public RetryableMeshException(String message, Throwable cause) {
        super(message, cause);
    }
}
