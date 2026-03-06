package com.janeluo.luban.rds.protocol;

/**
 * RESP 协议类型枚举
 * 定义 RESP2 和 RESP3 协议支持的所有数据类型
 */
public enum RespType {

    /** 简单字符串（RESP2） */
    SIMPLE_STRING('+'),

    /** 错误消息（RESP2） */
    ERROR('-'),

    /** 整数（RESP2） */
    INTEGER(':'),

    /** 批量字符串（RESP2） */
    BULK_STRING('$'),

    /** 数组（RESP2） */
    ARRAY('*'),

    /** 映射（RESP3） */
    MAP('%'),

    /** 集合（RESP3） */
    SET('~'),

    /** 属性（RESP3） */
    ATTRIBUTE('|'),

    /** 空值（RESP3） */
    NULL('_'),

    /** 双精度浮点数（RESP3） */
    DOUBLE(','),

    /** 布尔值（RESP3） */
    BOOLEAN('#'),

    /** 大整数（RESP3） */
    BIG_NUMBER('(');

    private final char prefix;

    RespType(char prefix) {
        this.prefix = prefix;
    }

    public char getPrefix() {
        return prefix;
    }

    /**
     * 根据前缀字符获取对应的 RESP 类型
     *
     * @param prefix 前缀字符
     * @return 对应的 RESP 类型，未找到时返回 null
     */
    public static RespType fromPrefix(char prefix) {
        for (RespType type : values()) {
            if (type.prefix == prefix) {
                return type;
            }
        }
        return null;
    }
}
