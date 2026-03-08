package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 管理流的所有消费者组
 * 
 * <p>每个流都有一个 StreamConsumerGroupManager 实例来管理其消费者组。
 * 
 * <p>提供以下功能：
 * <ul>
 *   <li>创建和销毁消费者组</li>
 *   <li>获取消费者组</li>
 *   <li>查询消费者组数量和统计信息</li>
 * </ul>
 */
public class StreamConsumerGroupManager implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 所属流的 Key
     */
    private final String streamKey;

    /**
     * 消费者组列表
     * Key: 消费者组名称, Value: 消费者组对象
     */
    private final ConcurrentHashMap<String, ConsumerGroup> groups;

    /**
     * 构造函数
     *
     * @param streamKey 所属流的 Key
     */
    public StreamConsumerGroupManager(String streamKey) {
        this.streamKey = Objects.requireNonNull(streamKey, "Stream key cannot be null");
        this.groups = new ConcurrentHashMap<>();
    }

    /**
     * 获取流的 Key
     *
     * @return 流的 Key
     */
    public String getStreamKey() {
        return streamKey;
    }

    /**
     * 获取消费者组数量
     *
     * @return 消费者组数量
     */
    public int getGroupCount() {
        return groups.size();
    }

    /**
     * 创建消费者组
     *
     * @param groupName 消费者组名称
     * @param startId 起始消息 ID（新消息从这个 ID 之后开始传递）
     * @return 新创建的消费者组
     * @throws IllegalStateException 如果消费者组已存在
     */
    public ConsumerGroup createGroup(String groupName, StreamId startId) {
        Objects.requireNonNull(groupName, "Group name cannot be null");
        Objects.requireNonNull(startId, "Start ID cannot be null");

        ConsumerGroup newGroup = new ConsumerGroup(groupName, startId);
        ConsumerGroup existing = groups.putIfAbsent(groupName, newGroup);

        if (existing != null) {
            throw new IllegalStateException("Consumer group '" + groupName + "' already exists");
        }

        return newGroup;
    }

    /**
     * 创建消费者组（使用 "$" 表示从最后一条消息开始）
     *
     * @param groupName 消费者组名称
     * @param startId 起始消息 ID
     * @param lastEntryId 流中最后一条消息的 ID（用于处理 "$" 特殊 ID）
     * @return 新创建的消费者组
     * @throws IllegalStateException 如果消费者组已存在
     */
    public ConsumerGroup createGroup(String groupName, StreamId startId, StreamId lastEntryId) {
        Objects.requireNonNull(groupName, "Group name cannot be null");

        // 如果 startId 为 null，使用最后一条消息的 ID（相当于 "$"）
        StreamId actualStartId = startId != null ? startId : lastEntryId;
        if (actualStartId == null) {
            actualStartId = StreamId.MIN_ID;
        }

        return createGroup(groupName, actualStartId);
    }

    /**
     * 销毁消费者组
     *
     * @param groupName 消费者组名称
     * @return 如果销毁成功返回 true，如果消费者组不存在返回 false
     */
    public boolean destroyGroup(String groupName) {
        Objects.requireNonNull(groupName, "Group name cannot be null");
        return groups.remove(groupName) != null;
    }

    /**
     * 获取消费者组
     *
     * @param groupName 消费者组名称
     * @return 消费者组对象，如果不存在返回 null
     */
    public ConsumerGroup getGroup(String groupName) {
        return groups.get(groupName);
    }

    /**
     * 检查消费者组是否存在
     *
     * @param groupName 消费者组名称
     * @return 如果存在返回 true
     */
    public boolean hasGroup(String groupName) {
        return groups.containsKey(groupName);
    }

    /**
     * 获取所有消费者组
     *
     * @return 消费者组列表
     */
    public List<ConsumerGroup> getGroups() {
        return new ArrayList<>(groups.values());
    }

    /**
     * 获取所有消费者组名称
     *
     * @return 消费者组名称列表
     */
    public List<String> getGroupNames() {
        return new ArrayList<>(groups.keySet());
    }

    /**
     * 获取所有消费者组的总消费者数量
     *
     * @return 总消费者数量
     */
    public int getTotalConsumerCount() {
        return groups.values().stream()
                .mapToInt(ConsumerGroup::getConsumerCount)
                .sum();
    }

    /**
     * 获取所有消费者组的总待处理消息数量
     *
     * @return 总待处理消息数量
     */
    public long getTotalPendingCount() {
        return groups.values().stream()
                .mapToLong(ConsumerGroup::getPendingCount)
                .sum();
    }

    /**
     * 清空所有消费者组
     */
    public void clear() {
        groups.clear();
    }

    /**
     * 检查是否为空（没有任何消费者组）
     *
     * @return 如果没有任何消费者组返回 true
     */
    public boolean isEmpty() {
        return groups.isEmpty();
    }

    @Override
    public String toString() {
        return "StreamConsumerGroupManager{" +
                "streamKey='" + streamKey + '\'' +
                ", groupCount=" + groups.size() +
                '}';
    }
}
