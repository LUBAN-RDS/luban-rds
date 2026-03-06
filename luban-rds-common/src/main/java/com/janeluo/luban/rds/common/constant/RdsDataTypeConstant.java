package com.janeluo.luban.rds.common.constant;

/**
 * Redis 数据类型常量类
 * 定义 Redis 支持的数据类型名称常量
 */
public final class RdsDataTypeConstant {

    private RdsDataTypeConstant() {
    }

    /** 字符串类型 */
    public static final String STRING = "string";

    /** 哈希类型 */
    public static final String HASH = "hash";

    /** 列表类型 */
    public static final String LIST = "list";

    /** 集合类型 */
    public static final String SET = "set";

    /** 有序集合类型 */
    public static final String ZSET = "zset";

    /** 无类型（键不存在） */
    public static final String NONE = "none";
}
