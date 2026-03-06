package com.janeluo.luban.rds.core.slowlog;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 慢日志管理器
 * 
 * <p>负责记录和管理慢日志条目，采用单例模式实现。
 * 慢日志记录执行时间超过阈值的命令，用于性能分析和问题排查。
 * 
 * <p>特性：
 * <ul>
 *   <li>线程安全的日志记录</li>
 *   <li>可配置的慢日志阈值和最大条目数</li>
 *   <li>支持日志查询、计数和重置</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class SlowLogManager {
    
    /**
     * 单例实例
     */
    private static final SlowLogManager INSTANCE = new SlowLogManager();
    
    /**
     * 慢日志链表
     */
    private final LinkedList<SlowLogEntry> slowLogs = new LinkedList<>();
    
    /**
     * 当前ID计数器
     */
    private final AtomicLong currentId = new AtomicLong(0);
    
    /**
     * 读写锁，保证线程安全
     */
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private SlowLogManager() {}

    public static SlowLogManager getInstance() {
        return INSTANCE;
    }

    public void push(long duration, List<String> args, String clientIp, String clientName) {
        // If slowlog-log-slower-than is negative, we don't log.
        long threshold = RuntimeConfig.getSlowlogLogSlowerThan();
        if (threshold < 0) {
            return;
        }

        // If duration is less than threshold, don't log.
        if (duration < threshold) {
            return;
        }
        
        long maxLen = RuntimeConfig.getSlowlogMaxLen();
        if (maxLen == 0) {
             return;
        }

        long id = currentId.getAndIncrement();
        long timestamp = System.currentTimeMillis() / 1000;
        SlowLogEntry entry = new SlowLogEntry(id, timestamp, duration, args, clientIp, clientName);

        lock.writeLock().lock();
        try {
            slowLogs.addFirst(entry);
            while (slowLogs.size() > maxLen) {
                slowLogs.removeLast();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<SlowLogEntry> get(int count) {
        lock.readLock().lock();
        try {
            if (slowLogs.isEmpty()) {
                return Collections.emptyList();
            }
            if (count <= 0) {
                return new java.util.ArrayList<>(slowLogs);
            }
            int size = Math.min(count, slowLogs.size());
            return new java.util.ArrayList<>(slowLogs.subList(0, size));
        } finally {
            lock.readLock().unlock();
        }
    }

    public long len() {
        lock.readLock().lock();
        try {
            return slowLogs.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public void reset() {
        lock.writeLock().lock();
        try {
            slowLogs.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
}
