package com.janeluo.luban.rds.core.stream;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Stream 消息条目
 * 
 * <p>表示 Redis Stream 中的单条消息，包含：
 * <ul>
 *   <li>消息 ID：唯一标识符</li>
 *   <li>字段值对：消息内容（使用 LinkedHashMap 保持插入顺序）</li>
 *   <li>创建时间：消息创建的时间戳</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class StreamEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Java 对象头大小（64 位 JVM，压缩 oops）
     */
    private static final int OBJECT_HEADER_SIZE = 12;

    /**
     * 引用大小（压缩 oops）
     */
    private static final int REFERENCE_SIZE = 4;

    /**
     * String 对象开销
     */
    private static final int STRING_OVERHEAD = 24;

    /**
     * LinkedHashMap 条目开销
     */
    private static final int MAP_ENTRY_OVERHEAD = 32;

    /**
     * LinkedHashMap 开销
     */
    private static final int MAP_OVERHEAD = 48;

    /**
     * 消息 ID
     */
    private final StreamId id;

    /**
     * 字段值对（保持插入顺序）
     */
    private final LinkedHashMap<String, String> fields;

    /**
     * 创建时间（毫秒时间戳）
     */
    private final long createdTime;

    /**
     * 构造函数
     *
     * @param id     消息 ID
     * @param fields 字段值对
     */
    public StreamEntry(StreamId id, Map<String, String> fields) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.fields = fields != null ? new LinkedHashMap<>(fields) : new LinkedHashMap<>();
        this.createdTime = System.currentTimeMillis();
    }

    /**
     * 构造函数（指定创建时间）
     *
     * @param id          消息 ID
     * @param fields      字段值对
     * @param createdTime 创建时间
     */
    public StreamEntry(StreamId id, Map<String, String> fields, long createdTime) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.fields = fields != null ? new LinkedHashMap<>(fields) : new LinkedHashMap<>();
        this.createdTime = createdTime;
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
     * 获取字段值对
     * 
     * <p>返回的是副本，修改不会影响原始数据
     *
     * @return 字段值对的副本
     */
    public Map<String, String> getFields() {
        return new LinkedHashMap<>(fields);
    }

    /**
     * 获取原始字段值对（内部使用）
     *
     * @return 原始字段值对
     */
    public LinkedHashMap<String, String> getFieldsInternal() {
        return fields;
    }

    /**
     * 获取创建时间
     *
     * @return 创建时间（毫秒时间戳）
     */
    public long getCreatedTime() {
        return createdTime;
    }

    /**
     * 获取指定字段的值
     *
     * @param field 字段名
     * @return 字段值，如果不存在返回 null
     */
    public String getField(String field) {
        return fields.get(field);
    }

    /**
     * 获取字段数量
     *
     * @return 字段数量
     */
    public int getFieldCount() {
        return fields.size();
    }

    /**
     * 序列化为 RESP 格式
     * 
     * <p>格式：
     * <pre>
     * *2
     * $&lt;idLength&gt;
     * &lt;idString&gt;
     * *&lt;fieldCount&gt;
     * $&lt;field1Length&gt;
     * &lt;field1&gt;
     * $&lt;value1Length&gt;
     * &lt;value1&gt;
     * ...
     * </pre>
     *
     * @return RESP 格式的字节数组
     */
    public byte[] toRespBytes() {
        StringBuilder sb = new StringBuilder();
        
        // 消息条目是一个包含 2 个元素的数组：[id, field-value pairs]
        sb.append("*2\r\n");
        
        // 第一个元素：ID
        String idStr = id.toString();
        sb.append("$").append(idStr.length()).append("\r\n");
        sb.append(idStr).append("\r\n");
        
        // 第二个元素：字段值对数组
        int fieldCount = fields.size() * 2;
        sb.append("*").append(fieldCount).append("\r\n");
        
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            // 字段名
            String key = entry.getKey();
            sb.append("$").append(key.length()).append("\r\n");
            sb.append(key).append("\r\n");
            
            // 字段值
            String value = entry.getValue();
            if (value == null) {
                sb.append("$-1\r\n");
            } else {
                sb.append("$").append(value.length()).append("\r\n");
                sb.append(value).append("\r\n");
            }
        }
        
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 序列化为 RESP 格式字符串
     *
     * @return RESP 格式字符串
     */
    public String toRespString() {
        return new String(toRespBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * 计算内存占用大小
     * 
     * <p>估算对象在 JVM 堆中占用的内存大小，包括：
     * <ul>
     *   <li>对象头和字段引用</li>
     *   <li>StreamId 对象</li>
     *   <li>LinkedHashMap 及其条目</li>
     *   <li>所有字符串（字段名和值）</li>
     * </ul>
     *
     * @return 估算的内存占用大小（字节）
     */
    public long estimateMemorySize() {
        long size = OBJECT_HEADER_SIZE; // StreamEntry 对象头
        
        // 字段引用
        size += REFERENCE_SIZE; // id 引用
        size += REFERENCE_SIZE; // fields 引用
        size += 8; // createdTime (long)
        
        // StreamId 对象
        size += OBJECT_HEADER_SIZE + 8 + 8; // 对象头 + 两个 long 字段
        
        // LinkedHashMap
        size += MAP_OVERHEAD;
        size += (long) fields.size() * MAP_ENTRY_OVERHEAD;
        
        // 字段名和值的字符串
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            
            // 字段名
            if (key != null) {
                size += STRING_OVERHEAD + (long) key.length() * 2L;
            }
            
            // 字段值
            if (value != null) {
                size += STRING_OVERHEAD + (long) value.length() * 2L;
            }
        }
        
        return size;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        StreamEntry that = (StreamEntry) obj;
        return Objects.equals(id, that.id) 
            && Objects.equals(fields, that.fields);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, fields);
    }

    @Override
    public String toString() {
        return "StreamEntry{" +
                "id=" + id +
                ", fields=" + fields +
                ", createdTime=" + createdTime +
                '}';
    }
}
