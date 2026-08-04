package com.janeluo.luban.rds.mesh.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Raft 节点持久化与运行时状态（DESIGN.md §3.1）。
 * <p>
 * 持久化字段（重启恢复）：{@link #currentTerm}、{@link #votedFor}、{@link #log}（仅含快照截断后的 tail）、
 * {@link #lastIncludedIndex}、{@link #lastIncludedTerm}。运行时字段：{@link #commitIndex}、
 * {@link #lastApplied}（重启后由快照 + 重放重建）、{@link #leaderId}、{@link #role}。
 * </p>
 *
 * <h3>并发保护</h3>
 * <p>
 * 用 {@link ReentrantReadWriteLock} 保护：log 的并发访问（append / 截断 / 读 lastLogIndex/Term）需安全。
 * 单个 volatile 标量的读写无需加锁即可保证可见性；但<strong>复合读</strong>（如 getLastLogIndex 涉及
 * lastIncludedIndex + log.size() 两项一致性）与<strong>写 log</strong>（add / 截断）必须在锁内。
 * </p>
 *
 * <h3>快照偏移语义</h3>
 * <p>
 * {@code log} 仅含 tail（快照截断后）。绝对索引与 list 索引的换算：
 * {@code listIndex = (int)(absoluteIndex - lastIncludedIndex - 1)}。
 * {@link #getLastLogIndex()} = {@code lastIncludedIndex + log.size()}（log 为空时返回 lastIncludedIndex）。
 * </p>
 */
public class MeshState {

    /** 当前任期号，单调递增（持久化） */
    public volatile long currentTerm;

    /** 当前任期投票给的候选者 nodeId；null 表示尚未投票（持久化） */
    public volatile String votedFor;

    /**
     * 日志条目数组（仅含快照截断后的 tail）。
     * <p><strong>final 引用但内容可变</strong>：list 内容在锁内 append / 截断 / 替换，引用本身不变。</p>
     */
    public final List<LogEntry> log;

    /** 最近一次快照包含的最后日志索引（快照截断的边界，持久化） */
    public volatile long lastIncludedIndex;

    /** lastIncludedIndex 对应的任期（持久化） */
    public volatile long lastIncludedTerm;

    /** 已提交的日志索引（重启后由快照 + 重放重建，运行时） */
    public volatile long commitIndex;

    /** 已应用到状态机的索引（重启后由快照 + 重放重建，运行时） */
    public volatile long lastApplied;

    /** 当前已知 Leader 的 nodeId（运行时） */
    public volatile String leaderId;

    /** 当前角色（运行时） */
    public volatile MeshRole role;

    /** 保护 log 与复合读写的读写锁 */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** 默认构造器：FOLLOWER、term=0、空日志、所有索引 0。 */
    public MeshState() {
        this.currentTerm = 0;
        this.votedFor = null;
        this.log = new ArrayList<>();
        this.lastIncludedIndex = 0;
        this.lastIncludedTerm = 0;
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.leaderId = null;
        this.role = MeshRole.FOLLOWER;
    }

    // ==================== 锁 ====================

    /** 取读锁（供上层在复合读时显式持锁）。 */
    public ReentrantReadWriteLock.ReadLock readLock() {
        return lock.readLock();
    }

    /** 取写锁（供上层在 append / 截断 / 批量改时显式持锁）。 */
    public ReentrantReadWriteLock.WriteLock writeLock() {
        return lock.writeLock();
    }

    // ==================== 日志索引辅助方法（选举 / 复制关键） ====================

    /**
     * 最后一条日志的绝对索引（= {@code lastIncludedIndex + log.size()}）。
     * <p>log 为空时返回 {@code lastIncludedIndex}（快照边界即最后索引）。</p>
     */
    public long getLastLogIndex() {
        lock.readLock().lock();
        try {
            return lastIncludedIndex + log.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 最后一条日志的任期。
     * <p>log 为空时返回 {@code lastIncludedTerm}（快照边界任期），否则返回 log 最后一条的 term。</p>
     */
    public long getLastLogTerm() {
        lock.readLock().lock();
        try {
            if (log.isEmpty()) {
                return lastIncludedTerm;
            }
            return log.get(log.size() - 1).getTerm();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 给定绝对 index 返回其对应的任期。
     * <ul>
     *   <li>{@code index == lastIncludedIndex} → 返回 {@link #lastIncludedTerm}</li>
     *   <li>{@code index > lastIncludedIndex} → 返回 {@code log[(int)(index - lastIncludedIndex - 1)].term}</li>
     *   <li>{@code index < lastIncludedIndex}（已被快照截断，不可查）→ 返回 -1</li>
     *   <li>{@code index > lastLogIndex}（越界）→ 返回 -1</li>
     * </ul>
     *
     * @param index 绝对索引（1-based）
     * @return 对应任期；不可查或越界时返回 -1
     */
    public long getLogTerm(long index) {
        lock.readLock().lock();
        try {
            if (index == lastIncludedIndex) {
                return lastIncludedTerm;
            }
            if (index < lastIncludedIndex) {
                return -1;
            }
            if (index > lastIncludedIndex + log.size()) {
                return -1;
            }
            int listIndex = (int) (index - lastIncludedIndex - 1);
            return log.get(listIndex).getTerm();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 给定绝对 index 返回对应 {@link LogEntry}（不含快照内已被截断的条目）。
     *
     * @param index 绝对索引（1-based）
     * @return LogEntry；index ≤ lastIncludedIndex 或越界时返回 {@code null}
     */
    public LogEntry getEntry(long index) {
        lock.readLock().lock();
        try {
            if (index <= lastIncludedIndex) {
                return null;
            }
            if (index > lastIncludedIndex + log.size()) {
                return null;
            }
            int listIndex = (int) (index - lastIncludedIndex - 1);
            return log.get(listIndex);
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 追加一条日志（写锁内）。
     *
     * @param entry 待追加条目
     */
    public void appendEntry(LogEntry entry) {
        lock.writeLock().lock();
        try {
            log.add(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 截断 index 之后的所有条目（写锁内）。用于 Follower 日志一致性回滚。
     *
     * @param fromAbsoluteIndex 保留 ≤ 该索引的条目；大于该索引的全部移除（1-based 绝对索引）
     */
    public void truncateAfter(long fromAbsoluteIndex) {
        lock.writeLock().lock();
        try {
            int keep = (int) Math.min(Math.max(fromAbsoluteIndex - lastIncludedIndex, 0), log.size());
            if (keep < log.size()) {
                log.subList(keep, log.size()).clear();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        readLock().lock();
        try {
            return "MeshState{term=" + currentTerm + ", role=" + role
                    + ", leader=" + leaderId + ", votedFor=" + votedFor
                    + ", lastIncluded=" + lastIncludedIndex + "/" + lastIncludedTerm
                    + ", logSize=" + log.size()
                    + ", lastLog=" + getLastLogIndex() + "/" + getLastLogTerm()
                    + ", commit=" + commitIndex + ", applied=" + lastApplied + '}';
        } finally {
            readLock().unlock();
        }
    }
}
