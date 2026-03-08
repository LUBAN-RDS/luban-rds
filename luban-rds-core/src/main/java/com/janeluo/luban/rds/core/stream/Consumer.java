package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表示消费者组中的消费者
 * 
 * <p>消费者是消费者组中的一个成员，负责处理分配给它的消息。
 * 
 * <p>每个消费者维护：
 * <ul>
 *   <li>名称 - 在消费者组内唯一标识</li>
 *   <li>最后活跃时间 - 用于检测不活跃的消费者</li>
 *   <li>待处理消息列表（PEL）- 已传递但未确认的消息</li>
 * </ul>
 */
public class Consumer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消费者名称
     */
    private final String name;

    /**
     * 最后活跃时间（毫秒时间戳）
     */
    private final AtomicLong seenTime;

    /**
     * 待处理消息数量（用于快速查询）
     */
    private final AtomicInteger pendingCount;

    /**
     * 待处理消息列表（Pending Entry List）
     * Key: 消息 ID, Value: 待处理消息信息
     */
    private final ConcurrentHashMap<StreamId, PendingMessage> pendingMessages;

    /**
     * 构造函数
     *
     * @param name 消费者名称
     */
    public Consumer(String name) {
        this.name = Objects.requireNonNull(name, "Consumer name cannot be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Consumer name cannot be empty");
        }
        this.seenTime = new AtomicLong(System.currentTimeMillis());
        this.pendingCount = new AtomicInteger(0);
        this.pendingMessages = new ConcurrentHashMap<>();
    }

    /**
     * 获取消费者名称
     *
     * @return 消费者名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取最后活跃时间
     *
     * @return 最后活跃时间（毫秒时间戳）
     */
    public long getSeenTime() {
        return seenTime.get();
    }

    /**
     * 更新最后活跃时间为当前时间
     */
    public void updateSeenTime() {
        seenTime.set(System.currentTimeMillis());
    }

    /**
     * 获取待处理消息数量
     *
     * @return 待处理消息数量
     */
    public int getPendingCount() {
        return pendingCount.get();
    }

    /**
     * 获取待处理消息列表
     *
     * @return 待处理消息列表的副本
     */
    public List<PendingMessage> getPendingMessages() {
        return new ArrayList<>(pendingMessages.values());
    }

    /**
     * 添加待处理消息
     *
     * @param pendingMessage 待处理消息
     * @return 如果添加成功返回 true，如果消息已存在返回 false
     */
    public boolean addPendingMessage(PendingMessage pendingMessage) {
        Objects.requireNonNull(pendingMessage, "Pending message cannot be null");
        PendingMessage existing = pendingMessages.putIfAbsent(pendingMessage.getId(), pendingMessage);
        if (existing == null) {
            pendingCount.incrementAndGet();
            return true;
        }
        return false;
    }

    /**
     * 移除待处理消息（确认消息）
     *
     * @param id 消息 ID
     * @return 被移除的待处理消息，如果不存在返回 null
     */
    public PendingMessage removePendingMessage(StreamId id) {
        PendingMessage removed = pendingMessages.remove(id);
        if (removed != null) {
            pendingCount.decrementAndGet();
        }
        return removed;
    }

    /**
     * 获取指定的待处理消息
     *
     * @param id 消息 ID
     * @return 待处理消息，如果不存在返回 null
     */
    public PendingMessage getPendingMessage(StreamId id) {
        return pendingMessages.get(id);
    }

    /**
     * 检查是否有指定的待处理消息
     *
     * @param id 消息 ID
     * @return 如果存在返回 true
     */
    public boolean hasPendingMessage(StreamId id) {
        return pendingMessages.containsKey(id);
    }

    /**
     * 获取空闲时间（从最后活跃到现在的时间差）
     *
     * @return 空闲时间（毫秒）
     */
    public long getIdleTime() {
        return System.currentTimeMillis() - seenTime.get();
    }

    /**
     * 清空所有待处理消息
     * 
     * <p>用于删除消费者时清理资源
     */
    public void clearPendingMessages() {
        int size = pendingMessages.size();
        pendingMessages.clear();
        pendingCount.addAndGet(-size);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        Consumer consumer = (Consumer) obj;
        return Objects.equals(name, consumer.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "Consumer{" +
                "name='" + name + '\'' +
                ", seenTime=" + seenTime.get() +
                ", pendingCount=" + pendingCount.get() +
                '}';
    }
}
