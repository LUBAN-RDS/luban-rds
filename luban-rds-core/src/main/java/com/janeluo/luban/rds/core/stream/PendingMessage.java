package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.Objects;

/**
 * 表示待处理消息（Pending Entry List Entry）
 * 
 * <p>待处理消息是指已被传递给消费者但尚未被确认（ACK）的消息。
 * 
 * <p>包含以下信息：
 * <ul>
 *   <li>消息 ID</li>
 *   <li>消费者名称</li>
 *   <li>传递时间</li>
 *   <li>传递次数</li>
 * </ul>
 */
public class PendingMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息 ID
     */
    private final StreamId id;

    /**
     * 消费者名称
     */
    private String consumerName;

    /**
     * 传递时间（毫秒时间戳）
     */
    private long deliveryTime;

    /**
     * 传递次数
     */
    private int deliveryCount;

    /**
     * 构造函数
     *
     * @param id 消息 ID
     * @param consumerName 消费者名称
     * @param deliveryTime 传递时间（毫秒）
     */
    public PendingMessage(StreamId id, String consumerName, long deliveryTime) {
        this.id = Objects.requireNonNull(id, "ID cannot be null");
        this.consumerName = Objects.requireNonNull(consumerName, "Consumer name cannot be null");
        this.deliveryTime = deliveryTime;
        this.deliveryCount = 1;
    }

    /**
     * 获取消息 ID
     *
     * @return 消息 ID
     */
    public StreamId getId() {
        return id;
    }

    /**
     * 获取消费者名称
     *
     * @return 消费者名称
     */
    public String getConsumerName() {
        return consumerName;
    }

    /**
     * 设置消费者名称（用于 XCLAIM 转移消息所有权）
     *
     * @param consumerName 新的消费者名称
     */
    public void setConsumerName(String consumerName) {
        this.consumerName = Objects.requireNonNull(consumerName, "Consumer name cannot be null");
    }

    /**
     * 获取传递时间
     *
     * @return 传递时间（毫秒时间戳）
     */
    public long getDeliveryTime() {
        return deliveryTime;
    }

    /**
     * 更新传递时间为当前时间
     */
    public void updateDeliveryTime() {
        this.deliveryTime = System.currentTimeMillis();
    }

    /**
     * 设置传递时间
     *
     * @param deliveryTime 传递时间（毫秒时间戳）
     */
    public void setDeliveryTime(long deliveryTime) {
        this.deliveryTime = deliveryTime;
    }

    /**
     * 获取传递次数
     *
     * @return 传递次数
     */
    public int getDeliveryCount() {
        return deliveryCount;
    }

    /**
     * 增加传递次数
     */
    public void incrementDeliveryCount() {
        this.deliveryCount++;
    }

    /**
     * 设置传递次数
     *
     * @param deliveryCount 传递次数
     */
    public void setDeliveryCount(int deliveryCount) {
        this.deliveryCount = deliveryCount;
    }

    /**
     * 获取消息的空闲时间（从上次传递到现在的时间差）
     *
     * @return 空闲时间（毫秒）
     */
    public long getIdleTime() {
        return System.currentTimeMillis() - deliveryTime;
    }

    /**
     * 更新传递信息（用于重新传递消息）
     *
     * @param consumerName 消费者名称
     */
    public void redeliver(String consumerName) {
        this.consumerName = Objects.requireNonNull(consumerName, "Consumer name cannot be null");
        this.deliveryTime = System.currentTimeMillis();
        this.deliveryCount++;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PendingMessage that = (PendingMessage) obj;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "PendingMessage{" +
                "id=" + id +
                ", consumerName='" + consumerName + '\'' +
                ", deliveryTime=" + deliveryTime +
                ", deliveryCount=" + deliveryCount +
                '}';
    }
}
