package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 表示消费者组
 * 
 * <p>消费者组允许多个消费者协同处理流中的消息，每条消息只会被传递给组内的一个消费者。
 * 
 * <p>消费者组维护：
 * <ul>
 *   <li>名称 - 在流内唯一标识</li>
 *   <li>最后传递的消息 ID - 用于追踪已传递的消息</li>
 *   <li>消费者列表 - 组内的所有消费者</li>
 *   <li>全局待处理消息列表（PEL）- 所有消费者的待处理消息</li>
 * </ul>
 */
public class ConsumerGroup implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消费者组名称
     */
    private final String name;

    /**
     * 最后传递的消息 ID
     * 用于追踪哪些消息已经被传递给消费者
     */
    private volatile StreamId lastDeliveredId;

    /**
     * 创建时间（毫秒时间戳）
     */
    private final long createdAt;

    /**
     * 消费者列表
     * Key: 消费者名称, Value: 消费者对象
     */
    private final ConcurrentHashMap<String, Consumer> consumers;

    /**
     * 全局待处理消息列表（Pending Entry List）
     * Key: 消息 ID, Value: 待处理消息信息
     * 使用 ConcurrentSkipListMap 保持按 ID 排序
     */
    private final ConcurrentSkipListMap<StreamId, PendingMessage> pel;

    /**
     * 待处理消息总数（用于快速查询）
     */
    private final AtomicLong pendingCount;

    /**
     * 构造函数
     *
     * @param name 消费者组名称
     * @param startId 起始消息 ID（用于确定从哪里开始传递消息）
     */
    public ConsumerGroup(String name, StreamId startId) {
        this.name = Objects.requireNonNull(name, "Group name cannot be null");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be empty");
        }
        this.lastDeliveredId = Objects.requireNonNull(startId, "Start ID cannot be null");
        this.createdAt = System.currentTimeMillis();
        this.consumers = new ConcurrentHashMap<>();
        this.pel = new ConcurrentSkipListMap<>();
        this.pendingCount = new AtomicLong(0);
    }

    /**
     * 获取消费者组名称
     *
     * @return 消费者组名称
     */
    public String getName() {
        return name;
    }

    /**
     * 获取最后传递的消息 ID
     *
     * @return 最后传递的消息 ID
     */
    public StreamId getLastDeliveredId() {
        return lastDeliveredId;
    }

    /**
     * 更新最后传递的消息 ID
     *
     * @param id 新的最后传递消息 ID
     */
    public void setLastDeliveredId(StreamId id) {
        this.lastDeliveredId = Objects.requireNonNull(id, "ID cannot be null");
    }

    /**
     * 获取创建时间
     *
     * @return 创建时间（毫秒时间戳）
     */
    public long getCreatedAt() {
        return createdAt;
    }

    /**
     * 获取消费者数量
     *
     * @return 消费者数量
     */
    public int getConsumerCount() {
        return consumers.size();
    }

    /**
     * 获取待处理消息总数
     *
     * @return 待处理消息总数
     */
    public long getPendingCount() {
        return pendingCount.get();
    }

    // ==================== 消费者管理 ====================

    /**
     * 创建消费者
     *
     * @param consumerName 消费者名称
     * @return 新创建的消费者，如果消费者已存在则返回现有消费者
     */
    public Consumer createConsumer(String consumerName) {
        Objects.requireNonNull(consumerName, "Consumer name cannot be null");
        return consumers.computeIfAbsent(consumerName, Consumer::new);
    }

    /**
     * 获取消费者
     *
     * @param consumerName 消费者名称
     * @return 消费者对象，如果不存在返回 null
     */
    public Consumer getConsumer(String consumerName) {
        return consumers.get(consumerName);
    }

    /**
     * 检查消费者是否存在
     *
     * @param consumerName 消费者名称
     * @return 如果存在返回 true
     */
    public boolean hasConsumer(String consumerName) {
        return consumers.containsKey(consumerName);
    }

    /**
     * 获取所有消费者
     *
     * @return 消费者列表
     */
    public List<Consumer> getConsumers() {
        return new ArrayList<>(consumers.values());
    }

    /**
     * 删除消费者
     * 
     * <p>删除消费者时，其待处理消息会保留在全局 PEL 中，
     * 可以被其他消费者通过 XCLAIM 命令认领。
     *
     * @param consumerName 消费者名称
     * @return 被删除的消费者，如果不存在返回 null
     */
    public Consumer deleteConsumer(String consumerName) {
        Consumer consumer = consumers.remove(consumerName);
        if (consumer != null) {
            // 清空消费者的待处理消息计数（但保留在全局 PEL 中）
            consumer.clearPendingMessages();
        }
        return consumer;
    }

    // ==================== 待处理消息管理 ====================

    /**
     * 添加待处理消息
     *
     * @param id 消息 ID
     * @param consumerName 消费者名称
     * @return 新创建的待处理消息
     */
    public PendingMessage addPendingMessage(StreamId id, String consumerName) {
        Objects.requireNonNull(id, "Message ID cannot be null");
        Objects.requireNonNull(consumerName, "Consumer name cannot be null");

        // 确保消费者存在
        Consumer consumer = createConsumer(consumerName);
        consumer.updateSeenTime();

        // 创建待处理消息
        PendingMessage pendingMessage = new PendingMessage(id, consumerName, System.currentTimeMillis());

        // 添加到全局 PEL
        PendingMessage existing = pel.putIfAbsent(id, pendingMessage);
        if (existing == null) {
            pendingCount.incrementAndGet();
        } else {
            pendingMessage = existing;
        }

        // 添加到消费者的 PEL
        consumer.addPendingMessage(pendingMessage);

        return pendingMessage;
    }

    /**
     * 确认消息（从 PEL 移除）
     *
     * @param id 消息 ID
     * @return 被确认的待处理消息，如果不存在返回 null
     */
    public PendingMessage ackMessage(StreamId id) {
        Objects.requireNonNull(id, "Message ID cannot be null");

        // 从全局 PEL 移除
        PendingMessage pendingMessage = pel.remove(id);
        if (pendingMessage != null) {
            pendingCount.decrementAndGet();

            // 从消费者的 PEL 移除
            Consumer consumer = consumers.get(pendingMessage.getConsumerName());
            if (consumer != null) {
                consumer.removePendingMessage(id);
            }
        }
        return pendingMessage;
    }

    /**
     * 转移消息所有权（XCLAIM 命令）
     *
     * @param id 消息 ID
     * @param newConsumer 新的消费者名称
     * @return 转移后的待处理消息，如果消息不存在返回 null
     */
    public PendingMessage claimMessage(StreamId id, String newConsumer) {
        Objects.requireNonNull(id, "Message ID cannot be null");
        Objects.requireNonNull(newConsumer, "New consumer name cannot be null");

        PendingMessage pendingMessage = pel.get(id);
        if (pendingMessage == null) {
            return null;
        }

        String oldConsumerName = pendingMessage.getConsumerName();

        // 从旧消费者的 PEL 移除
        Consumer oldConsumer = consumers.get(oldConsumerName);
        if (oldConsumer != null) {
            oldConsumer.removePendingMessage(id);
        }

        // 更新待处理消息信息
        pendingMessage.redeliver(newConsumer);

        // 添加到新消费者的 PEL
        Consumer newConsumerObj = createConsumer(newConsumer);
        newConsumerObj.addPendingMessage(pendingMessage);

        return pendingMessage;
    }

    /**
     * 获取指定的待处理消息
     *
     * @param id 消息 ID
     * @return 待处理消息，如果不存在返回 null
     */
    public PendingMessage getPendingMessage(StreamId id) {
        return pel.get(id);
    }

    /**
     * 检查是否有指定的待处理消息
     *
     * @param id 消息 ID
     * @return 如果存在返回 true
     */
    public boolean hasPendingMessage(StreamId id) {
        return pel.containsKey(id);
    }

    /**
     * 查询待处理消息
     *
     * @param start 起始 ID（包含）
     * @param end 结束 ID（包含）
     * @param count 最大返回数量
     * @param consumerName 消费者名称过滤（null 表示不过滤）
     * @param minIdleTime 最小空闲时间（毫秒，0 表示不过滤）
     * @return 待处理消息列表
     */
    public List<PendingMessage> getPendingMessages(StreamId start, StreamId end, int count,
                                                    String consumerName, long minIdleTime) {
        List<PendingMessage> result = new ArrayList<>();

        // 使用子映射获取范围内的消息
        Map<StreamId, PendingMessage> subMap;
        if (start != null && end != null) {
            subMap = pel.subMap(start, true, end, true);
        } else if (start != null) {
            subMap = pel.tailMap(start, true);
        } else if (end != null) {
            subMap = pel.headMap(end, true);
        } else {
            subMap = pel;
        }

        long currentTime = System.currentTimeMillis();

        for (PendingMessage pm : subMap.values()) {
            // 检查消费者过滤
            if (consumerName != null && !consumerName.equals(pm.getConsumerName())) {
                continue;
            }

            // 检查空闲时间过滤
            if (minIdleTime > 0) {
                long idleTime = currentTime - pm.getDeliveryTime();
                if (idleTime < minIdleTime) {
                    continue;
                }
            }

            result.add(pm);

            // 检查数量限制
            if (count > 0 && result.size() >= count) {
                break;
            }
        }

        return result;
    }

    /**
     * 获取所有待处理消息
     *
     * @return 所有待处理消息列表
     */
    public List<PendingMessage> getAllPendingMessages() {
        return new ArrayList<>(pel.values());
    }

    /**
     * 获取待处理消息的 ID 范围
     *
     * @return 包含最小和最大 ID 的数组，如果 PEL 为空返回 null
     */
    public StreamId[] getPendingIdRange() {
        if (pel.isEmpty()) {
            return null;
        }
        return new StreamId[]{pel.firstKey(), pel.lastKey()};
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ConsumerGroup that = (ConsumerGroup) obj;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "ConsumerGroup{" +
                "name='" + name + '\'' +
                ", lastDeliveredId=" + lastDeliveredId +
                ", createdAt=" + createdAt +
                ", consumerCount=" + consumers.size() +
                ", pendingCount=" + pendingCount.get() +
                '}';
    }
}
