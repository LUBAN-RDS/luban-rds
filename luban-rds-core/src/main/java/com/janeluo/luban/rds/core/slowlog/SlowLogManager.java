package com.janeluo.luban.rds.core.slowlog;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages slow logs.
 */
public class SlowLogManager {
    private static final SlowLogManager INSTANCE = new SlowLogManager();
    private final LinkedList<SlowLogEntry> slowLogs = new LinkedList<>();
    private final AtomicLong currentId = new AtomicLong(0);
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
