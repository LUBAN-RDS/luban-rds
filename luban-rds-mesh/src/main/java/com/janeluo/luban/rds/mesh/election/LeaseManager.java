package com.janeluo.luban.rds.mesh.election;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Leader 心跳租约管理（DESIGN.md §5.7 / §7.5 / 决策 13）。
 * <p>
 * Leader 每轮心跳（{@code heartbeatIntervalMs}，默认 100ms）广播 AppendEntries，收到多数派
 * {@code success=true} 即续租：{@code leaseExpireAt = now + leaseDuration}。
 * 读路径仅在租约有效期内本地执行，过期则 {@link #awaitValid(long)} 阻塞至下一轮续租。
 * </p>
 *
 * <h3>leaseDuration</h3>
 * <p>
 * {@code leaseDuration = 2 × electionTimeout}（默认 electionTimeout 上限 600ms → leaseDuration 1200ms，可配置）。
 * 前提：节点间时钟偏差 &lt; leaseDuration/2（部署要求 NTP 对齐）。
 * </p>
 *
 * <h3>awaitValid 语义（区别于 read-index）</h3>
 * <p>
 * {@link #awaitValid(long)} 是<strong>被动等下一轮心跳续租</strong>，不主动发额外心跳
 * （心跳由 {@code MeshNode.startHeartbeat} 的定时器周期触发）。read-index 则是<strong>主动发心跳
 * + 校验 commitIndex</strong>，二者是不同机制（DESIGN §5.7）。本类只实现 lease 机制，
 * read-index 由阶段 8 读路径补全。
 * </p>
 *
 * <h3>线程模型</h3>
 * <p>
 * {@link #refreshOnMajorityAck(long)} / {@link #invalidate()} 在 Leader 心跳线程调用；
 * {@link #awaitValid(long)} 在多个读线程调用。用 {@link ReentrantLock} + {@link Condition} 保证
 * {@code leaseExpireAt} 的可见性与 await/notifyAll 语义。
 * </p>
 */
public class LeaseManager {

    private static final Logger logger = LoggerFactory.getLogger(LeaseManager.class);

    /** 默认租约时长（ms）= 2 × electionTimeout 上限 600ms。 */
    public static final long DEFAULT_LEASE_DURATION_MS = 1200;

    private final long leaseDurationMs;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition renewed = lock.newCondition();

    /** 租约截止时刻（本地时钟 System.currentTimeMillis）；0 表示从未续租（视为失效）。 */
    private volatile long leaseExpireAt;
    /** 是否已 invalidate（失去 Leader 身份后永久失效，直到下次 refresh 才恢复）。 */
    private volatile boolean invalidated;

    public LeaseManager() {
        this(DEFAULT_LEASE_DURATION_MS);
    }

    /**
     * @param leaseDurationMs 租约时长（ms，>0）；应 = 2 × electionTimeout
     */
    public LeaseManager(long leaseDurationMs) {
        if (leaseDurationMs <= 0) {
            throw new IllegalArgumentException("leaseDurationMs 必须 > 0: " + leaseDurationMs);
        }
        this.leaseDurationMs = leaseDurationMs;
        this.leaseExpireAt = 0L;
        this.invalidated = false;
    }

    /**
     * 心跳多数派 ACK 续租：{@code leaseExpireAt = now + leaseDurationMs}，并唤醒所有 awaitValid 等待者。
     * <p>失去 Leader 身份（invalidate）后的首次 refresh 同时清除 invalidated 标志。</p>
     *
     * @param now 当前时刻（System.currentTimeMillis）
     */
    public void refreshOnMajorityAck(long now) {
        long newExpire = now + leaseDurationMs;
        lock.lock();
        try {
            this.leaseExpireAt = newExpire;
            this.invalidated = false;
            renewed.signalAll();
        } finally {
            lock.unlock();
        }
        logger.trace("租约续租: expireAt={}, duration={}ms", newExpire, leaseDurationMs);
    }

    /**
     * 当前时刻租约是否有效。
     *
     * @param now 当前时刻
     * @return true=有效（仍是真 Leader，可本地读）
     */
    public boolean isValid(long now) {
        long expire = leaseExpireAt;
        if (expire <= 0 || invalidated) {
            return false;
        }
        return now < expire;
    }

    /**
     * 失效时阻塞至下一轮续租（供读路径用），或超时返回 false。
     * <p>
     * 若进入时已有效，立即返回 true。等待期间被 {@link #refreshOnMajorityAck} 唤醒后再次校验有效性。
     * 与 read-index 的区别：本方法不主动发心跳，仅被动等待 Leader 心跳定时器的下一轮续租。
     * </p>
     *
     * @param timeoutMs 最大等待时长（ms，<=0 表示不等待，仅查一次）
     * @return true=等待期间租约变为有效；false=超时仍无效
     * @throws InterruptedException 等待被中断
     */
    public boolean awaitValid(long timeoutMs) throws InterruptedException {
        long now = System.currentTimeMillis();
        if (isValid(now)) {
            return true;
        }
        if (timeoutMs <= 0) {
            return false;
        }
        lock.lock();
        try {
            long deadline = System.currentTimeMillis() + timeoutMs;
            long remaining = timeoutMs;
            while (true) {
                // 持锁后再次校验（可能在进入锁前刚被续租）
                if (isValid(System.currentTimeMillis())) {
                    return true;
                }
                if (remaining <= 0) {
                    return false;
                }
                try {
                    renewed.await(remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    renewed.signalAll();
                    throw e;
                }
                long now2 = System.currentTimeMillis();
                if (isValid(now2)) {
                    return true;
                }
                remaining = deadline - now2;
                if (remaining <= 0) {
                    return false;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 主动失效：失去 Leader 身份时调用。立即让 {@link #isValid(long)} 返回 false，
     * 并唤醒所有 awaitValid 等待者（它们将因 invalidated 继续阻塞或超时返回 false）。
     */
    public void invalidate() {
        lock.lock();
        try {
            this.invalidated = true;
            this.leaseExpireAt = 0L;
            renewed.signalAll();
        } finally {
            lock.unlock();
        }
        logger.debug("租约失效（失去 Leader 身份）");
    }

    public long getLeaseDurationMs() {
        return leaseDurationMs;
    }

    /** 当前租约截止时刻（测试与监控用）；0 表示从未续租或已失效。 */
    public long getLeaseExpireAt() {
        return leaseExpireAt;
    }
}
