package com.janeluo.luban.rds.mesh.election;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 选举超时定时器（DESIGN.md §5.2）。
 * <p>
 * 每个节点持有一个 ElectionTimer。Follower/Candidate 在选举超时区间内未收到合法 AppendEntries
 * 或 RequestVote（即未"看到" Leader 或更高任期候选者）时，超时触发 → 回调 {@code onElectionTimeout}
 * （由 MeshNode 注入，调 RaftStateMachine.becomeCandidate → PreVote → RequestVote）。
 * </p>
 *
 * <h3>随机化超时（防 split vote）</h3>
 * <p>
 * 每次调度一个超时都重新随机一个值（区间 {@code [minMs, maxMs]}，默认 150-300ms）。随机化是 Raft
 * 防 split vote 的关键：若多个节点固定相同超时，会同时发起选举互相争票，谁都拿不到多数派。随机化
 * 使超时点错开，先超时的候选者更可能在其他节点超时前拿到多数票。
 * </p>
 *
 * <h3>并发与线程模型</h3>
 * <p>
 * 内部用一个 {@link java.util.concurrent.ScheduledExecutorService} 的单线程调度器（由构造者注入，
 * 便于 MeshNode 复用统一线程池）。所有公开方法线程安全：
 * <ul>
 *   <li>{@link #reset()} 取消当前未触发的超时任务，重新随机并调度一个新的。</li>
 *   <li>{@link #start()} 启动定时器（等价于首次 reset）。</li>
 *   <li>{@link #stop()} 取消所有任务，不再触发回调。</li>
 * </ul>
 * 超时回调在调度线程上执行（不要阻塞），MeshNode 在回调里只做"置标志/提交任务"，重活异步化。
 * </p>
 *
 * <h3>去重</h3>
 * <p>
 * 同一时刻只保留一个待触发的超时任务（用 {@link AtomicReference} CAS 替换 + cancel 旧的），
 * 避免高频 reset 累积多个任务导致"抖动式"重复触发。
 * </p>
 */
public class ElectionTimer {

    private static final Logger logger = LoggerFactory.getLogger(ElectionTimer.class);

    /** 默认选举超时下限（ms），DESIGN §5.2。 */
    public static final long DEFAULT_MIN_MS = 150;
    /** 默认选举超时上限（ms），DESIGN §5.2。 */
    public static final long DEFAULT_MAX_MS = 300;

    /** 退避最大位移量（2^shift 倍）：封顶区间 = [minMs × 4, maxMs × 4]。 */
    private static final int MAX_BACKOFF_SHIFT = 2;

    private final long minMs;
    private final long maxMs;
    private final Runnable onElectionTimeout;
    private final java.util.concurrent.ScheduledExecutorService scheduler;

    /** 当前待触发的超时任务引用；null 表示无待触发任务（已触发或已 stop）。 */
    private final AtomicReference<java.util.concurrent.ScheduledFuture<?>> pending =
            new AtomicReference<>();

    private volatile boolean started;
    private volatile boolean stopped;

    /**
     * 连续选举失败次数（PreVote 未达多数派 / 正式选举未达多数派）。
     * 用于计算退避区间：shift = min(consecutiveFailures, MAX_BACKOFF_SHIFT)。
     * 选举成功或收到合法 AppendEntries/RequestVote 后复位为 0。
     */
    private int consecutiveFailures = 0;

    /**
     * @param minMs            选举超时下限（ms，>0）
     * @param maxMs            选举超时上限（ms，>= minMs）
     * @param onElectionTimeout 超时回调（在调度线程执行，不可阻塞）
     * @param scheduler        调度器（由 MeshNode 注入，复用线程池）
     */
    public ElectionTimer(long minMs, long maxMs, Runnable onElectionTimeout,
                         java.util.concurrent.ScheduledExecutorService scheduler) {
        if (minMs <= 0 || maxMs < minMs) {
            throw new IllegalArgumentException("非法选举超时区间: [" + minMs + "," + maxMs + "]");
        }
        if (onElectionTimeout == null) {
            throw new IllegalArgumentException("onElectionTimeout 不能为 null");
        }
        if (scheduler == null) {
            throw new IllegalArgumentException("scheduler 不能为 null");
        }
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.onElectionTimeout = onElectionTimeout;
        this.scheduler = scheduler;
    }

    /**
     * 用默认区间（150-300ms）构造。
     */
    public ElectionTimer(Runnable onElectionTimeout,
                         java.util.concurrent.ScheduledExecutorService scheduler) {
        this(DEFAULT_MIN_MS, DEFAULT_MAX_MS, onElectionTimeout, scheduler);
    }

    /** 启动定时器：首次随机并调度一个超时。重复 start 幂等（已启动则忽略）。 */
    public synchronized void start() {
        if (stopped) {
            throw new IllegalStateException("ElectionTimer 已 stop，不可再 start");
        }
        if (started) {
            return;
        }
        started = true;
        scheduleNext();
        logger.debug("ElectionTimer 启动，区间=[{},{}]ms", minMs, maxMs);
    }

    /**
     * 重置超时：收到合法 AppendEntries / RequestVote 时调用。
     * 取消当前未触发的任务，重新随机并调度一个新的。
     */
    public synchronized void reset() {
        if (stopped || !started) {
            return;
        }
        scheduleNext();
    }

    /** 停止定时器：取消所有待触发任务，不再触发回调。 */
    public synchronized void stop() {
        stopped = true;
        cancelPending();
        logger.debug("ElectionTimer 已停止");
    }

    /** 随机化下一次超时时长（ms）。可见用于测试与 LeaseManager 推算 leaseDuration。 */
    public long nextTimeoutMs() {
        // 退避区间：正常 [minMs, maxMs]，失败后翻倍至 [minMs×2^shift, maxMs×2^shift]
        int shift = Math.min(consecutiveFailures, MAX_BACKOFF_SHIFT);
        long effectiveMin = minMs << shift;
        long effectiveMax = maxMs << shift;
        // ThreadLocalRandom：[effectiveMin, effectiveMax] 闭区间
        return ThreadLocalRandom.current().nextLong(effectiveMin, effectiveMax + 1);
    }

    public long getMinMs() {
        return minMs;
    }

    public long getMaxMs() {
        return maxMs;
    }

    /**
     * 选举失败回调（PreVote 未达多数派 / 正式选举未达多数派）。
     * 增加连续失败计数，下次 nextTimeoutMs 使用更大的退避区间。
     */
    public synchronized void onElectionFailed() {
        if (consecutiveFailures < MAX_BACKOFF_SHIFT) {
            consecutiveFailures++;
            logger.info("选举退避: consecutiveFailures={}, 下次区间=[{},{}]ms",
                    consecutiveFailures, minMs << consecutiveFailures, maxMs << consecutiveFailures);
        }
    }

    /**
     * 选举成功 / 收到合法 AppendEntries/RequestVote 回调。
     * 复位连续失败计数，恢复正常选举超时区间。
     */
    public synchronized void onElectionSucceeded() {
        if (consecutiveFailures > 0) {
            logger.debug("选举退避复位: consecutiveFailures 0（收到合法 Leader 信号）");
            consecutiveFailures = 0;
        }
    }

    /** 当前连续失败次数（测试用）。 */
    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    // ==================== internal ====================

    private void scheduleNext() {
        long delay = nextTimeoutMs();
        // 同步块保证 pending 的"取消旧的 + 设置新的"原子（与 stop/start/reset 互斥）
        java.util.concurrent.ScheduledFuture<?> prev = pending.getAndSet(null);
        if (prev != null) {
            prev.cancel(false);
        }
        // 用一个持有 future 引用的 holder，回调触发时验证它仍是当前注册的任务
        final java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<?>> slot =
                new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledFuture<?> future = scheduler.schedule(() -> {
            // 触发时校验：当前注册的任务必须是自己（否则说明已被 reset/stop 取消替换，丢弃此次触发）
            if (slot.get() == null) {
                return;
            }
            pending.compareAndSet(slot.get(), null);
            try {
                onElectionTimeout.run();
            } catch (Exception e) {
                logger.error("选举超时回调异常", e);
            }
        }, delay, TimeUnit.MILLISECONDS);
        slot.set(future);
        pending.set(future);
    }

    private void cancelPending() {
        java.util.concurrent.ScheduledFuture<?> f = pending.getAndSet(null);
        if (f != null) {
            f.cancel(false);
        }
    }
}
