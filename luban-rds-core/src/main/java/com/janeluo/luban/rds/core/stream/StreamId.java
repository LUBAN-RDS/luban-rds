package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.Objects;

/**
 * Stream 消息 ID
 * 
 * <p>表示 Redis Stream 中消息的唯一标识符，格式为 {@code <millisecondsTime>-<sequenceNumber>}。
 * 
 * <p>ID 由两部分组成：
 * <ul>
 *   <li>毫秒时间戳：Unix 时间戳（毫秒）</li>
 *   <li>序号：同一毫秒内的序号，用于保证唯一性</li>
 * </ul>
 * 
 * <p>支持特殊 ID：
 * <ul>
 *   <li>{@code 0-0}：最小 ID，用于范围查询的起始点</li>
 *   <li>{@code +}：最大 ID，用于范围查询的结束点</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class StreamId implements Comparable<StreamId>, Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 最小 ID：0-0
     */
    public static final StreamId MIN_ID = new StreamId(0, 0);

    /**
     * 最大 ID：+（表示无限大）
     */
    public static final StreamId MAX_ID = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    /**
     * 最后一条消息的 ID：$（用于 XREAD 命令）
     */
    public static final StreamId LAST_ID = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    /**
     * 未接收的 ID：>（用于 XREADGROUP 命令）
     */
    public static final StreamId UNRECEIVED_ID = new StreamId(Long.MAX_VALUE, Long.MAX_VALUE);

    /**
     * 毫秒时间戳
     */
    private final long millisecondsTime;

    /**
     * 序号
     */
    private final long sequenceNumber;

    /**
     * 构造函数
     *
     * @param millisecondsTime 毫秒时间戳
     * @param sequenceNumber   序号
     */
    public StreamId(long millisecondsTime, long sequenceNumber) {
        if (millisecondsTime < 0) {
            throw new IllegalArgumentException("millisecondsTime must be non-negative");
        }
        if (sequenceNumber < 0) {
            throw new IllegalArgumentException("sequenceNumber must be non-negative");
        }
        this.millisecondsTime = millisecondsTime;
        this.sequenceNumber = sequenceNumber;
    }

    /**
     * 解析字符串形式的 ID
     * 
     * <p>支持格式：
     * <ul>
     *   <li>{@code <millisecondsTime>-<sequenceNumber>}：完整 ID</li>
     *   <li>{@code <millisecondsTime>}：仅时间戳，序号默认为 0</li>
     *   <li>{@code +}：最大 ID</li>
     *   <li>{@code -}：最小 ID（等同于 0-0）</li>
     * </ul>
     *
     * @param id 字符串形式的 ID
     * @return StreamId 对象
     * @throws IllegalArgumentException 如果 ID 格式无效
     */
    public static StreamId parse(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("ID cannot be null or empty");
        }

        // 处理特殊 ID
        if ("+".equals(id)) {
            return MAX_ID;
        }
        if ("-".equals(id)) {
            return MIN_ID;
        }

        // 解析普通 ID
        int dashIndex = id.indexOf('-');
        if (dashIndex == -1) {
            // 只有时间戳，序号默认为 0
            try {
                long ms = Long.parseLong(id);
                return new StreamId(ms, 0);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid stream ID format: " + id);
            }
        }

        try {
            long ms = Long.parseLong(id.substring(0, dashIndex));
            long seq = Long.parseLong(id.substring(dashIndex + 1));
            return new StreamId(ms, seq);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid stream ID format: " + id);
        }
    }

    /**
     * 获取毫秒时间戳
     *
     * @return 毫秒时间戳
     */
    public long getMillisecondsTime() {
        return millisecondsTime;
    }

    /**
     * 获取序号
     *
     * @return 序号
     */
    public long getSequenceNumber() {
        return sequenceNumber;
    }

    /**
     * 比较两个 StreamId
     * 
     * <p>比较规则：
     * <ol>
     *   <li>先比较毫秒时间戳</li>
     *   <li>时间戳相同时比较序号</li>
     * </ol>
     *
     * @param other 另一个 StreamId
     * @return 比较结果：负数表示小于，0 表示等于，正数表示大于
     */
    @Override
    public int compareTo(StreamId other) {
        if (other == null) {
            return 1;
        }
        int cmp = Long.compare(this.millisecondsTime, other.millisecondsTime);
        if (cmp != 0) {
            return cmp;
        }
        return Long.compare(this.sequenceNumber, other.sequenceNumber);
    }

    /**
     * 判断当前 ID 是否大于指定 ID
     *
     * @param other 另一个 StreamId
     * @return 如果大于返回 true
     */
    public boolean isGreaterThan(StreamId other) {
        return this.compareTo(other) > 0;
    }

    /**
     * 判断当前 ID 是否小于指定 ID
     *
     * @param other 另一个 StreamId
     * @return 如果小于返回 true
     */
    public boolean isLessThan(StreamId other) {
        return this.compareTo(other) < 0;
    }

    /**
     * 判断当前 ID 是否在指定范围内
     *
     * @param start          起始 ID
     * @param end            结束 ID
     * @param exclusiveStart 是否排除起始 ID（开区间）
     * @param exclusiveEnd   是否排除结束 ID（开区间）
     * @return 如果在范围内返回 true
     */
    public boolean isInRange(StreamId start, StreamId end, boolean exclusiveStart, boolean exclusiveEnd) {
        boolean afterStart = exclusiveStart ? this.isGreaterThan(start) : this.compareTo(start) >= 0;
        boolean beforeEnd = exclusiveEnd ? this.isLessThan(end) : this.compareTo(end) <= 0;
        return afterStart && beforeEnd;
    }

    /**
     * 转换为字符串形式
     *
     * @return 字符串形式的 ID，格式为 {@code <millisecondsTime>-<sequenceNumber>}
     */
    @Override
    public String toString() {
        return millisecondsTime + "-" + sequenceNumber;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        StreamId streamId = (StreamId) obj;
        return millisecondsTime == streamId.millisecondsTime 
            && sequenceNumber == streamId.sequenceNumber;
    }

    @Override
    public int hashCode() {
        return Objects.hash(millisecondsTime, sequenceNumber);
    }
}
