package com.janeluo.luban.rds.cluster.migration;

import java.io.Serializable;

/**
 * 导出结果
 * <p>
 * 表示单个键导出的结果信息
 * </p>
 */
public class ExportResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导出是否成功
     */
    private final boolean success;

    /**
     * 键值数据（序列化后的字节数组）
     */
    private final byte[] value;

    /**
     * 过期时间（毫秒时间戳，0表示无过期）
     */
    private final long ttl;

    /**
     * 错误信息（如果失败）
     */
    private final String error;

    /**
     * 键名
     */
    private final String key;

    /**
     * 键类型
     */
    private final String type;

    /**
     * 私有构造方法（成功）
     */
    private ExportResult(String key, byte[] value, long ttl, String type) {
        this.success = true;
        this.key = key;
        this.value = value;
        this.ttl = ttl;
        this.type = type;
        this.error = null;
    }

    /**
     * 私有构造方法（失败）
     */
    private ExportResult(String key, String error) {
        this.success = false;
        this.key = key;
        this.value = null;
        this.ttl = 0;
        this.type = null;
        this.error = error;
    }

    /**
     * 创建成功的导出结果
     *
     * @param key   键名
     * @param value 键值数据
     * @param ttl   过期时间（毫秒）
     * @param type  键类型
     * @return 导出结果
     */
    public static ExportResult success(String key, byte[] value, long ttl, String type) {
        return new ExportResult(key, value, ttl, type);
    }

    /**
     * 创建失败的导出结果
     *
     * @param key   键名
     * @param error 错误信息
     * @return 导出结果
     */
    public static ExportResult failure(String key, String error) {
        return new ExportResult(key, error);
    }

    /**
     * 创建键不存在的导出结果
     *
     * @param key 键名
     * @return 导出结果
     */
    public static ExportResult notFound(String key) {
        return new ExportResult(key, "Key not found");
    }

    // ==================== Getter 方法 ====================

    public boolean isSuccess() {
        return success;
    }

    public byte[] getValue() {
        return value;
    }

    public long getTtl() {
        return ttl;
    }

    public String getError() {
        return error;
    }

    public String getKey() {
        return key;
    }

    public String getType() {
        return type;
    }

    /**
     * 检查键是否存在
     *
     * @return 键是否存在
     */
    public boolean isKeyExists() {
        return success && value != null;
    }

    /**
     * 检查是否有过期时间
     *
     * @return 是否有过期时间
     */
    public boolean hasTtl() {
        return ttl > 0;
    }

    @Override
    public String toString() {
        if (success) {
            return "ExportResult{" +
                    "success=true" +
                    ", key='" + key + '\'' +
                    ", type='" + type + '\'' +
                    ", valueSize=" + (value != null ? value.length : 0) +
                    ", ttl=" + ttl +
                    '}';
        } else {
            return "ExportResult{" +
                    "success=false" +
                    ", key='" + key + '\'' +
                    ", error='" + error + '\'' +
                    '}';
        }
    }
}
