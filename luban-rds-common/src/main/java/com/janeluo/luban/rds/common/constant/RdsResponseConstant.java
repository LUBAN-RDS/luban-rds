package com.janeluo.luban.rds.common.constant;

/**
 * Redis 响应常量类
 * 
 * 预定义常用的 RESP 协议响应字符串，避免重复创建对象，提高性能
 */
public final class RdsResponseConstant {
    
    private RdsResponseConstant() {
        // 私有构造函数，防止实例化
    }
    
    // ==================== 简单字符串响应 ====================
    
    /** OK 响应 */
    public static final String OK = "+OK\r\n";
    
    /** PONG 响应 */
    public static final String PONG = "+PONG\r\n";
    
    /** QUEUED 响应（事务队列） */
    public static final String QUEUED = "+QUEUED\r\n";
    
    // ==================== 整数响应 ====================
    
    /** 整数 0 响应 */
    public static final String ZERO = ":0\r\n";
    
    /** 整数 1 响应 */
    public static final String ONE = ":1\r\n";
    
    /** 整数 -1 响应 */
    public static final String MINUS_ONE = ":-1\r\n";
    
    /** 整数 -2 响应 */
    public static final String MINUS_TWO = ":-2\r\n";
    
    // ==================== 空值响应 ====================
    
    /** 空 Bulk String 响应 */
    public static final String NULL_BULK = "$-1\r\n";
    
    /** 空数组响应 */
    public static final String EMPTY_ARRAY = "*0\r\n";
    
    /** 空 Bulk String（长度为0） */
    public static final String EMPTY_BULK = "$0\r\n\r\n";
    
    // ==================== 常用错误响应 ====================
    
    /** 值不是整数错误 */
    public static final String ERR_NOT_INTEGER = "-ERR value is not an integer or out of range\r\n";
    
    /** 值不是浮点数错误 */
    public static final String ERR_NOT_FLOAT = "-ERR value is not a valid float\r\n";
    
    /** 语法错误 */
    public static final String ERR_SYNTAX = "-ERR syntax error\r\n";
    
    /** 未知命令错误前缀 */
    public static final String ERR_UNKNOWN_COMMAND_PREFIX = "-ERR unknown command '";
    
    /** 参数数量错误前缀 */
    public static final String ERR_WRONG_ARGS_PREFIX = "-ERR wrong number of arguments for '";
    
    /** 类型错误 */
    public static final String ERR_WRONG_TYPE = "-WRONGTYPE Operation against a key holding the wrong kind of value\r\n";
    
    // ==================== 缓存的整数响应（0-100） ====================
    
    /** 缓存的整数响应数组 */
    private static final String[] INT_CACHE = new String[101];
    
    static {
        for (int i = 0; i <= 100; i++) {
            INT_CACHE[i] = ":" + i + "\r\n";
        }
    }
    
    /**
     * 获取整数响应字符串
     * 对于 0-100 的整数使用缓存，其他整数动态生成
     * 
     * @param value 整数值
     * @return RESP 格式的整数响应
     */
    public static String intResponse(long value) {
        if (value >= 0 && value <= 100) {
            return INT_CACHE[(int) value];
        }
        return ":" + value + "\r\n";
    }
    
    /**
     * 生成 Bulk String 响应
     * 
     * @param value 字符串值
     * @return RESP 格式的 Bulk String 响应
     */
    public static String bulkString(String value) {
        if (value == null) {
            return NULL_BULK;
        }
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + value + "\r\n";
    }
    
    /**
     * 生成错误响应
     * 
     * @param message 错误消息
     * @return RESP 格式的错误响应
     */
    public static String error(String message) {
        return "-ERR " + message + "\r\n";
    }
    
    /**
     * 生成参数数量错误响应
     * 
     * @param command 命令名称
     * @return RESP 格式的错误响应
     */
    public static String wrongArgsError(String command) {
        return ERR_WRONG_ARGS_PREFIX + command + "' command\r\n";
    }
}
