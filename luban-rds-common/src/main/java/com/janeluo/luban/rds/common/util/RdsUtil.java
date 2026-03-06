package com.janeluo.luban.rds.common.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RDS 通用工具类
 * 提供日志获取、字符串处理、时间获取等通用方法
 */
public final class RdsUtil {

    private static final Logger logger = LoggerFactory.getLogger(RdsUtil.class);

    private RdsUtil() {
    }

    /**
     * 获取指定类的 Logger 实例
     *
     * @param clazz 类对象
     * @return Logger 实例
     */
    public static Logger getLogger(Class<?> clazz) {
        return LoggerFactory.getLogger(clazz);
    }

    /**
     * 将对象转换为字符串
     *
     * @param obj 对象
     * @return 字符串表示，对象为 null 时返回 null
     */
    public static String toString(Object obj) {
        if (obj == null) {
            return null;
        }
        return obj.toString();
    }

    /**
     * 判断字符串是否为空
     *
     * @param str 字符串
     * @return 为 null 或空字符串时返回 true
     */
    public static boolean isEmpty(String str) {
        return str == null || str.isEmpty();
    }

    /**
     * 判断字符串是否非空
     *
     * @param str 字符串
     * @return 不为 null 且不为空字符串时返回 true
     */
    public static boolean isNotEmpty(String str) {
        return !isEmpty(str);
    }

    /**
     * 获取当前时间的毫秒数
     *
     * @return 当前时间毫秒数
     */
    public static long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * 获取当前时间的秒数
     *
     * @return 当前时间秒数
     */
    public static long currentSeconds() {
        return currentTimeMillis() / 1000;
    }
}
