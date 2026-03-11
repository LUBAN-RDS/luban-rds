package com.janeluo.luban.rds.core.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Stream 核心数据结构
 * 
 * <p>实现 Redis Stream 的核心功能，提供：
 * <ul>
 *   <li>消息存储：使用 ConcurrentSkipListMap 保证线程安全和有序性</li>
 *   <li>ID 自动生成：支持时间戳递增和序号递增</li>
 *   <li>范围查询：支持开区间和闭区间查询</li>
 *   <li>裁剪策略：支持 MAXLEN 和 MINID 两种裁剪方式</li>
 * </ul>
 * 
 * <p>线程安全保证：
 * <ul>
 *   <li>使用 ConcurrentSkipListMap 保证并发读写安全</li>
 *   <li>使用 ReentrantReadWriteLock 保护 ID 生成逻辑</li>
 *   <li>使用 AtomicLong 记录统计信息</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class Stream {

    private static final Logger logger = LoggerFactory.getLogger(Stream.class);

    /**
     * 消息存储（线程安全、有序）
     */
    private final ConcurrentSkipListMap<StreamId, StreamEntry> entries;

    /**
     * 最后生成的毫秒时间戳
     */
    private final AtomicLong lastMillisecondsTime;

    /**
     * 最后生成的序号
     */
    private final AtomicLong lastSequenceNumber;

    /**
     * ID 生成锁（保护 ID 生成逻辑的原子性）
     */
    private final ReentrantReadWriteLock idGenerationLock;

    /**
     * 消息总数
     */
    private final AtomicLong entryCount;

    /**
     * 最后添加的消息 ID
     */
    private volatile StreamId lastGeneratedId;

    /**
     * 最大消息数量限制（0 表示无限制）
     */
    private volatile long maxLen;

    /**
     * 消费者组管理器
     */
    private volatile StreamConsumerGroupManager consumerGroupManager;

    /**
     * 阻塞等待队列（用于 XREAD/XREADGROUP 阻塞模式）
     */
    private final List<StreamWaiter> waiters;

    /**
     * 等待队列锁（独立的锁，避免与 ID 生成锁冲突）
     */
    private final ReentrantLock waiterLock;
    
    /**
     * 等待条件（用于唤醒所有等待者）
     */
    private final Condition waiterCondition;

    /**
     * 构造函数
     */
    public Stream() {
        this.entries = new ConcurrentSkipListMap<>();
        this.lastMillisecondsTime = new AtomicLong(0);
        this.lastSequenceNumber = new AtomicLong(0);
        this.idGenerationLock = new ReentrantReadWriteLock();
        this.entryCount = new AtomicLong(0);
        this.lastGeneratedId = null;
        this.maxLen = 0;
        this.waiters = new ArrayList<>();
        this.waiterLock = new ReentrantLock();
        this.waiterCondition = waiterLock.newCondition();
    }

    /**
     * 构造函数（指定最大消息数量）
     *
     * @param maxLen 最大消息数量限制
     */
    public Stream(long maxLen) {
        this();
        this.maxLen = maxLen;
    }

/**
     * 添加消息
     * 
     * 如果 ID 为 null，则自动生成 ID。
     * 
     * 如果指定了 ID，则验证 ID 必须大于最后生成的 ID。
     *
     * @param id     消息 ID（null 表示自动生成）
     * @param fields 字段值对（Redis 7.0+ 支持空字段）
     * @return 添加的消息 ID
     * @throws IllegalArgumentException 如果 ID 无效
     */
    public StreamId addEntry(StreamId id, Map<String, String> fields) {
        // Redis 7.0+ 支持空 fields
        if (fields == null) {
            fields = Collections.emptyMap();
        }

        StreamId actualId;
        
        idGenerationLock.writeLock().lock();
        try {
            if (id == null) {
                // 自动生成 ID
                actualId = generateIdInternal();
            } else {
                // 验证 ID 必须大于最后生成的 ID
                if (lastGeneratedId != null && id.compareTo(lastGeneratedId) <= 0) {
                    throw new IllegalArgumentException(
                        "ID must be greater than the last generated ID: " + lastGeneratedId);
                }
                actualId = id;
                
                // 更新最后生成的时间和序号
                lastMillisecondsTime.set(actualId.getMillisecondsTime());
                lastSequenceNumber.set(actualId.getSequenceNumber());
            }

            // 创建消息条目
            StreamEntry entry = new StreamEntry(actualId, fields);
            
            // 添加到存储
            entries.put(actualId, entry);
            entryCount.incrementAndGet();
            lastGeneratedId = actualId;

            // 检查是否需要裁剪
            if (maxLen > 0 && entryCount.get() > maxLen) {
                trimInternal((int) maxLen);
            }
        } finally {
            idGenerationLock.writeLock().unlock();
        }

        // 唤醒等待者（在锁外执行，避免死锁）
        notifyWaiters();

        logger.debug("Added entry: id={}, fields={}", actualId, fields.keySet());
        return actualId;
    }

    /**
     * 自动生成 ID
     * 
     * <p>ID 生成算法：
     * <ol>
     *   <li>获取当前毫秒时间戳</li>
     *   <li>如果时间戳等于上次时间戳，序号递增</li>
     *   <li>如果时间戳大于上次时间戳，序号从 0 开始</li>
     *   <li>如果时间戳小于上次时间戳（时间回退），使用上次时间戳并序号递增</li>
     * </ol>
     *
     * @return 新生成的 ID
     */
    public StreamId generateId() {
        idGenerationLock.writeLock().lock();
        try {
            return generateIdInternal();
        } finally {
            idGenerationLock.writeLock().unlock();
        }
    }

    /**
     * 内部 ID 生成方法（调用者需持有写锁）
     */
    private StreamId generateIdInternal() {
        long currentTime = System.currentTimeMillis();
        long lastMs = lastMillisecondsTime.get();
        long lastSeq = lastSequenceNumber.get();

        long newMs;
        long newSeq;

        if (currentTime > lastMs) {
            // 时间戳更大，从 0 开始
            newMs = currentTime;
            newSeq = 0;
        } else if (currentTime == lastMs) {
            // 时间戳相同，序号递增
            newMs = currentTime;
            newSeq = lastSeq + 1;
        } else {
            // 时间回退，使用上次时间戳并序号递增
            newMs = lastMs;
            newSeq = lastSeq + 1;
        }

        // 更新状态
        lastMillisecondsTime.set(newMs);
        lastSequenceNumber.set(newSeq);

        return new StreamId(newMs, newSeq);
    }

    /**
     * 获取消息
     *
     * @param id 消息 ID
     * @return 消息条目，如果不存在返回 null
     */
    public StreamEntry getEntry(StreamId id) {
        if (id == null) {
            return null;
        }
        return entries.get(id);
    }

    /**
     * 删除消息
     *
     * @param id 消息 ID
     * @return 如果删除成功返回 true
     */
    public boolean deleteEntry(StreamId id) {
        if (id == null) {
            return false;
        }
        
        StreamEntry removed = entries.remove(id);
        if (removed != null) {
            entryCount.decrementAndGet();
            logger.debug("Deleted entry: id={}", id);
            return true;
        }
        return false;
    }

    /**
     * 范围查询
     *
     * @param start          起始 ID
     * @param end            结束 ID
     * @param exclusiveStart 是否排除起始 ID（开区间）
     * @param exclusiveEnd   是否排除结束 ID（开区间）
     * @param count          最大返回数量（<= 0 表示无限制）
     * @return 消息列表
     */
    public List<StreamEntry> getRange(StreamId start, StreamId end, 
                                       boolean exclusiveStart, boolean exclusiveEnd, int count) {
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        List<StreamEntry> result = new ArrayList<>();
        int maxCount = count > 0 ? count : Integer.MAX_VALUE;

        // 使用 NavigableMap 的子映射功能
        NavigableMap<StreamId, StreamEntry> subMap;
        
        if (exclusiveStart && exclusiveEnd) {
            // (start, end)
            subMap = entries.subMap(start, false, end, false);
        } else if (exclusiveStart) {
            // (start, end]
            subMap = entries.subMap(start, false, end, true);
        } else if (exclusiveEnd) {
            // [start, end)
            subMap = entries.subMap(start, true, end, false);
        } else {
            // [start, end]
            subMap = entries.subMap(start, true, end, true);
        }

        for (Map.Entry<StreamId, StreamEntry> entry : subMap.entrySet()) {
            result.add(entry.getValue());
            if (result.size() >= maxCount) {
                break;
            }
        }

        return result;
    }

    /**
     * 从指定 ID 开始查询（正向）
     *
     * @param start     起始 ID
     * @param exclusive 是否排除起始 ID
     * @param count     最大返回数量
     * @return 消息列表
     */
    public List<StreamEntry> getRangeFrom(StreamId start, boolean exclusive, int count) {
        if (start == null || count <= 0) {
            return Collections.emptyList();
        }

        List<StreamEntry> result = new ArrayList<>(Math.min(count, 100));
        NavigableMap<StreamId, StreamEntry> tailMap = entries.tailMap(start, !exclusive);

        for (Map.Entry<StreamId, StreamEntry> entry : tailMap.entrySet()) {
            result.add(entry.getValue());
            if (result.size() >= count) {
                break;
            }
        }

        return result;
    }

    /**
     * 从指定 ID 开始反向查询
     *
     * @param start     起始 ID
     * @param exclusive 是否排除起始 ID
     * @param count     最大返回数量
     * @return 消息列表（按 ID 降序）
     */
    public List<StreamEntry> getRangeFromReverse(StreamId start, boolean exclusive, int count) {
        if (start == null || count <= 0) {
            return Collections.emptyList();
        }

        List<StreamEntry> result = new ArrayList<>(count);
        NavigableMap<StreamId, StreamEntry> headMap = entries.headMap(start, !exclusive);

        // 反向遍历
        for (Map.Entry<StreamId, StreamEntry> entry : headMap.descendingMap().entrySet()) {
            result.add(entry.getValue());
            if (result.size() >= count) {
                break;
            }
        }

        return result;
    }

    /**
     * 范围查询（逆序）- 用于 XREVRANGE
     *
     * @param start          起始 ID（小值）
     * @param end            结束 ID（大值）
     * @param exclusiveStart 是否排除起始 ID（开区间）
     * @param exclusiveEnd   是否排除结束 ID（开区间）
     * @param count          最大返回数量（<= 0 表示无限制）
     * @return 消息列表（按 ID 降序）
     */
    public List<StreamEntry> getRangeReverse(StreamId start, StreamId end,
                                               boolean exclusiveStart, boolean exclusiveEnd, int count) {
        if (start == null || end == null) {
            return Collections.emptyList();
        }

        List<StreamEntry> result = new ArrayList<>();
        int maxCount = count > 0 ? count : Integer.MAX_VALUE;

        // 使用 NavigableMap 的子映射功能
        NavigableMap<StreamId, StreamEntry> subMap;

        if (exclusiveStart && exclusiveEnd) {
            subMap = entries.subMap(start, false, end, false);
        } else if (exclusiveStart) {
            subMap = entries.subMap(start, false, end, true);
        } else if (exclusiveEnd) {
            subMap = entries.subMap(start, true, end, false);
        } else {
            subMap = entries.subMap(start, true, end, true);
        }

        logger.debug("getRangeReverse: start={}, end={}, subMap.size={}, entries={}", 
                     start, end, subMap.size(), entries.keySet());

        // 反向遍历（从大到小）- 使用 descendingMap 确保正确的降序遍历
        NavigableMap<StreamId, StreamEntry> descendingMap = subMap.descendingMap();
        for (Map.Entry<StreamId, StreamEntry> entry : descendingMap.entrySet()) {
            logger.debug("getRangeReverse iterating: id={}", entry.getKey());
            result.add(entry.getValue());
            if (result.size() >= maxCount) {
                break;
            }
        }

        logger.debug("getRangeReverse result: first={}, count={}", 
                     result.isEmpty() ? null : result.get(0).getId(), result.size());

        return result;
    }

    /**
     * 获取消息数量
     *
     * @return 消息数量
     */
    public long getLength() {
        return entryCount.get();
    }

    /**
     * 按 MAXLEN 裁剪
     * 
     * <p>保留最新的 maxLen 条消息，删除旧消息。
     *
     * @param maxLen 最大保留数量
     * @return 删除的消息数量
     */
    public int trim(int maxLen) {
        if (maxLen <= 0) {
            return 0;
        }

        idGenerationLock.writeLock().lock();
        try {
            return trimInternal(maxLen);
        } finally {
            idGenerationLock.writeLock().unlock();
        }
    }

    /**
     * 内部裁剪方法（调用者需持有写锁）
     */
    private int trimInternal(int maxLen) {
        long currentSize = entryCount.get();
        if (currentSize <= maxLen) {
            return 0;
        }

        int toRemove = (int) (currentSize - maxLen);
        int removed = 0;

        // 从最旧的消息开始删除
        while (removed < toRemove && !entries.isEmpty()) {
            Map.Entry<StreamId, StreamEntry> first = entries.pollFirstEntry();
            if (first != null) {
                entryCount.decrementAndGet();
                removed++;
            } else {
                break;
            }
        }

        logger.debug("Trimmed {} entries, remaining: {}", removed, entryCount.get());
        return removed;
    }

    /**
     * 按 MINID 裁剪
     * 
     * <p>删除 ID 小于 minId 的所有消息。
     *
     * @param minId 最小保留 ID
     * @return 删除的消息数量
     */
    public int trim(StreamId minId) {
        if (minId == null) {
            return 0;
        }

        idGenerationLock.writeLock().lock();
        try {
            int removed = 0;
            
            // 获取所有小于 minId 的消息
            NavigableMap<StreamId, StreamEntry> toRemove = entries.headMap(minId, false);
            
            // 删除这些消息
            for (StreamId id : new ArrayList<>(toRemove.keySet())) {
                if (entries.remove(id) != null) {
                    entryCount.decrementAndGet();
                    removed++;
                }
            }

            logger.debug("Trimmed {} entries by MINID, remaining: {}", removed, entryCount.get());
            return removed;
        } finally {
            idGenerationLock.writeLock().unlock();
        }
    }

    /**
     * 获取第一条消息
     *
     * @return 第一条消息，如果不存在返回 null
     */
    public StreamEntry getFirstEntry() {
        Map.Entry<StreamId, StreamEntry> first = entries.firstEntry();
        return first != null ? first.getValue() : null;
    }

    /**
     * 获取最后一条消息
     *
     * @return 最后一条消息，如果不存在返回 null
     */
    public StreamEntry getLastEntry() {
        Map.Entry<StreamId, StreamEntry> last = entries.lastEntry();
        return last != null ? last.getValue() : null;
    }

    /**
     * 获取最后生成的 ID
     *
     * @return 最后生成的 ID，如果没有消息返回 null
     */
    public StreamId getLastGeneratedId() {
        return lastGeneratedId;
    }

    /**
     * 获取最大消息数量限制
     *
     * @return 最大消息数量限制（0 表示无限制）
     */
    public long getMaxLen() {
        return maxLen;
    }

    /**
     * 设置最大消息数量限制
     *
     * @param maxLen 最大消息数量限制
     */
    public void setMaxLen(long maxLen) {
        this.maxLen = Math.max(0, maxLen);
    }

    /**
     * 判断 Stream 是否为空
     *
     * @return 如果为空返回 true
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 清空 Stream
     */
    public void clear() {
        idGenerationLock.writeLock().lock();
        try {
            entries.clear();
            entryCount.set(0);
            lastGeneratedId = null;
            lastMillisecondsTime.set(0);
            lastSequenceNumber.set(0);
            logger.debug("Stream cleared");
        } finally {
            idGenerationLock.writeLock().unlock();
        }
    }

    /**
     * 获取消费者组管理器
     *
     * @return 消费者组管理器，如果不存在返回 null
     */
    public StreamConsumerGroupManager getConsumerGroupManager() {
        return consumerGroupManager;
    }

    /**
     * 设置消费者组管理器
     *
     * @param manager 消费者组管理器
     */
    public void setConsumerGroupManager(StreamConsumerGroupManager manager) {
        this.consumerGroupManager = manager;
    }

    /**
     * 估算内存占用大小
     *
     * @return 估算的内存占用大小（字节）
     */
    public long estimateMemorySize() {
        long size = 64; // Stream 对象基础开销
        
        // ConcurrentSkipListMap 开销
        size += 128;
        
        // 所有消息条目
        for (StreamEntry entry : entries.values()) {
            size += entry.estimateMemorySize();
            size += 32; // Map 条目开销
        }
        
        // 消费者组管理器开销
        if (consumerGroupManager != null) {
            size += 64;
        }
        
        return size;
    }

    @Override
    public String toString() {
        return "Stream{" +
                "length=" + entryCount.get() +
                ", lastGeneratedId=" + lastGeneratedId +
                ", maxLen=" + maxLen +
                '}';
    }

    // ==================== 阻塞等待机制 ====================

    /**
     * 流等待者
     * 
     * <p>用于 XREAD/XREADGROUP 阻塞模式，封装等待条件。
     */
    public static class StreamWaiter {
        private final StreamId waitAfterId;
        private volatile boolean notified;
        private final Condition condition;

        public StreamWaiter(StreamId waitAfterId, Condition condition) {
            this.waitAfterId = waitAfterId;
            this.condition = condition;
            this.notified = false;
        }

        public StreamWaiter(StreamId waitAfterId) {
            this(waitAfterId, null);
        }

        public StreamId getWaitAfterId() {
            return waitAfterId;
        }

        public boolean isNotified() {
            return notified;
        }

        public void setNotified(boolean notified) {
            this.notified = notified;
        }

        public Condition getCondition() {
            return condition;
        }
    }

    /**
     * 巻加等待者
     * 
     * <p>当 XREAD/XREADGROUP 需要阻塞等待新消息时调用。
     *
     * @param waiter 等待者对象
     */
    public void addWaiter(StreamWaiter waiter) {
        waiterLock.lock();
        try {
            waiters.add(waiter);
        } finally {
            waiterLock.unlock();
        }
        logger.debug("Added waiter for stream, waitAfterId={}, total waiters={}", 
            waiter.getWaitAfterId(), waiters.size());
    }

    /**
     * 移除等待者
     *
     * @param waiter 等待者对象
     */
    public void removeWaiter(StreamWaiter waiter) {
        waiterLock.lock();
        try {
            waiters.remove(waiter);
        } finally {
            waiterLock.unlock();
        }
        logger.debug("Removed waiter from stream, remaining waiters={}", waiters.size());
    }

    /**
     * 巻加消息并唤醒所有等待者
     * 
     * <p>当 XADD 添加新消息时调用，通知所有等待的客户端。
     */
    public void notifyWaiters() {
        waiterLock.lock();
        try {
            if (waiters.isEmpty()) {
                return;
            }
            
            logger.debug("Notifying {} waiters for stream", waiters.size());
            
            for (StreamWaiter waiter : waiters) {
                waiter.setNotified(true);
            }
            
            // 唤醒所有等待 waiterCondition 的线程
            waiterCondition.signalAll();
            
            waiters.clear();
        } finally {
            waiterLock.unlock();
        }
    }

    /**
     * 获取等待者数量
     *
     * @return 等待者数量
     */
    public int getWaiterCount() {
        waiterLock.lock();
        try {
            return waiters.size();
        } finally {
            waiterLock.unlock();
        }
    }

    /**
     * 获取等待者锁的条件
     * 
     * <p>用于阻塞等待时创建 Condition 对象。
     *
     * @return Condition 对象
     */
    public Condition newCondition() {
        return waiterLock.newCondition();
    }
    
    /**
     * 获取等待者锁的共享 Condition
     * 
     * <p>用于所有等待者共享同一个 Condition， 以便 signalAll() 能唤醒所有等待者。
     *
     * @return 共享的 Condition 对象
     */
    public Condition getWaiterCondition() {
        return waiterCondition;
    }

    /**
     * 获取等待者锁
     * 
     * <p>用于阻塞等待时获取锁。
     */
    public void lockForWait() {
        waiterLock.lock();
    }

    /**
     * 释放等待者锁
     * 
     * <p>用于阻塞等待完成后释放锁。
     */
    public void unlockAfterWait() {
        waiterLock.unlock();
    }
}
