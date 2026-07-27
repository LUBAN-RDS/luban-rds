package com.janeluo.luban.rds.replication;

/**
 * 运行时复制控制接口。
 * <p>
 * 在 replication 模块定义，由 server 层（{@code ReplicationCoordinator}）实现，
 * 供 {@link com.janeluo.luban.rds.replication.handler.ReplicationCommandHandler}
 * 通过 setter 注入后调用。
 * </p>
 * <p>
 * 抽象出此接口是为了打破 replication 模块对 server 模块的依赖
 * （replication 不依赖 server，但 handler 需要在收到 {@code SLAVEOF} 时
 * 触发复制启停）。server 模块构造 handler 后注入自身实现。
 * </p>
 */
public interface ReplicationController {

    /**
     * 作为 slave 连接到指定 master 地址。
     * <p>
     * 实现应支持 {@code "host:port"} 与 {@code "host port"} 两种地址格式，
     * 并对相同目标的重复调用幂等。容忍未建连状态下调用。
     * </p>
     *
     * @param masterAddress master 地址，不应为 null/空
     */
    void startSlave(String masterAddress);

    /**
     * 停止从节点复制服务。
     * <p>
     * 仅停止复制连接，不修改共享配置。容忍未建连状态。
     * </p>
     */
    void stopSlave();
}
