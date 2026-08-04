package com.janeluo.luban.rds.cluster.lifecycle;

/**
 * 写暂停门控（P1-12）。
 * <p>
 * 在 cluster 模块定义，不依赖 server 模块，由 server 层实现。
 * 用于手动故障转移（CLUSTER FAILOVER 普通模式）：候选 slave 请求 master 在被接管前
 * 暂停客户端写、记录复制偏移量，slave 追平后提升，避免丢失 master 未暂停期间写入的数据。
 * 也供 CLIENT PAUSE 命令复用（修复 ClientCommandHandler.handleClientPause 空壳）。
 * </p>
 * <p>
 * 实现必须保证无暂停时零开销（server 写路径每条写命令都会查询 {@link #isPaused()}），
 * 推荐用 volatile boolean 或 AtomicBoolean 的读操作。
 * </p>
 */
public interface WritePauseGate {

    /**
     * 暂停客户端写。
     * <p>
     * 调用后，server 层的写命令处理应拒绝/挂起新的写请求，直至 {@link #resume()} 被调用。
     * 重复调用应幂等。
     * </p>
     */
    void pause();

    /**
     * 恢复客户端写（解除暂停）。
     * <p>
     * 调用后恢复正常的写命令处理。未暂停时调用应幂等（无副作用）。
     * </p>
     */
    void resume();

    /**
     * 当前是否处于写暂停状态。
     * <p>
     * server 写路径在执行写命令前查询此方法，暂停时拒绝写。必须高性能（每条写命令调用）。
     * </p>
     *
     * @return true 表示当前写已暂停
     */
    boolean isPaused();
}
