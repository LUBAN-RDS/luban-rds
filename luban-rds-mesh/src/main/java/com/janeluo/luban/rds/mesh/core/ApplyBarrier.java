package com.janeluo.luban.rds.mesh.core;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * apply 屏障：按绝对索引等待本地 {@code lastApplied} 追平（follower 读路径用）。
 * <p>
 * 等待发生在业务线程（Netty worker），{@link #signalApplied()} 由 raft 线程调用
 * （每成功 apply 一条 / 快照安装完成 / 角色切换）。等待期间不持有任何 raft 侧锁，
 * 与单线程 raftExecutor 模型无锁序反转。
 * </p>
 */
public class ApplyBarrier {

    private final MeshState state;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition appliedCond = lock.newCondition();

    public ApplyBarrier(MeshState state) {
        this.state = state;
    }

    /**
     * 等待 {@code lastApplied >= index}。
     *
     * @param index     目标绝对索引（readIndex 或 lastIncludedIndex）
     * @param timeoutMs 等待上限
     * @return true=已追平；false=超时（调用方回落 MOVED）
     */
    public boolean awaitApplied(long index, long timeoutMs) throws InterruptedException {
        if (state.lastApplied >= index) {
            return true;
        }
        long remainNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        lock.lock();
        try {
            while (state.lastApplied < index) {
                if (remainNanos <= 0) {
                    return false;
                }
                remainNanos = appliedCond.awaitNanos(remainNanos);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** 唤醒所有等待者（由 raft 线程调用）。 */
    public void signalApplied() {
        lock.lock();
        try {
            appliedCond.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
