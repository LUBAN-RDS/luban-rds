package com.janeluo.luban.rds.cluster.lifecycle;

/**
 * 集群内部数据写操作的复制/AOF 传播回调（P0-新3）。
 * <p>
 * MIGRATE 的两条写路径都绕过普通命令传播链：
 * <ul>
 *   <li>源端删除：{@code MigrateCommandHandler} 直接 {@code memoryStore.del}，不经
 *       RedisServerHandler 的传播段（MIGRATE 分发后提前 return）；</li>
 *   <li>目标端导入：经总线 {@code MIGRATE_KEY} 直接写存储，天然无传播。</li>
 * </ul>
 * 若不补传播，源 master 删除已迁移键后其 slave 仍保留该键（幽灵键），目标 master 导入的键
 * slave 缺失——任意一次 failover 后副本数据分叉。对齐 Redis 7：MIGRATE 成功（非 COPY）向
 * 副本/AOF 传播 DEL，目标端以 RESTORE 进入正常传播流。
 * </p>
 * <p>
 * 由 server 模块（NettyRedisServer）注入实现：将 RESP 帧写入复制 backlog、在线从节点与 AOF。
 * 实现必须容忍任意线程调用（客户端事件循环/总线事件循环），且异常不得向上抛出。
 * </p>
 */
@FunctionalInterface
public interface ClusterWritePropagator {

    /**
     * 将一条 RESP 命令帧写入复制 backlog、在线从节点与 AOF。
     *
     * @param respFrame RESP 编码的命令帧（DEL / RESTORE）
     */
    void propagate(byte[] respFrame);
}
