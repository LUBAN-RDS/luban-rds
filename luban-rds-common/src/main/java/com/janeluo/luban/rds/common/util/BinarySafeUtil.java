package com.janeluo.luban.rds.common.util;

import java.nio.charset.StandardCharsets;

/**
 * 二进制安全工具类
 * 
 * 用于处理 RESP 协议中的二进制安全数据。
 * 
 * 设计原则：
 * 1. ISO-8859-1 是单字节编码，每个字节（0-255）对应一个字符
 * 2. 任意 byte[] → String (ISO-8859-1) → byte[] 转换是无损的
 * 3. 服务器存储使用 String，但内容是原始字节的 ISO-8859-1 表示
 * 4. 服务器不介入客户端的编解码逻辑，仅作为字节流的透明传输者
 */
public final class BinarySafeUtil {
    
    private BinarySafeUtil() {
    }
    
    /**
     * 将原始字节转换为二进制安全的字符串
     * 使用 ISO-8859-1 编码，确保无损转换
     * 
     * @param bytes 原始字节
     * @return 二进制安全的字符串
     */
    public static String bytesToString(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
    
    /**
     * 将二进制安全的字符串转换回原始字节
     * 使用 ISO-8859-1 编码，确保无损转换
     * 
     * @param str 二进制安全的字符串
     * @return 原始字节
     */
    public static byte[] stringToBytes(String str) {
        if (str == null) {
            return null;
        }
        return str.getBytes(StandardCharsets.ISO_8859_1);
    }
    
    /**
     * 计算字符串的字节长度
     * 使用 ISO-8859-1 编码，每个字符对应一个字节
     * 
     * @param str 字符串
     * @return 字节长度
     */
    public static int byteLength(String str) {
        if (str == null) {
            return 0;
        }
        return str.length();
    }
    
    /**
     * 构建 RESP 批量字符串响应
     * 格式: $<length>\r\n<bytes>\r\n
     * 
     * @param str 二进制安全的字符串
     * @return RESP 格式的响应字符串
     */
    public static String buildBulkStringResponse(String str) {
        if (str == null) {
            return "$-1\r\n";
        }
        return "$" + str.length() + "\r\n" + str + "\r\n";
    }
    
    /**
     * 构建 RESP 批量字符串响应（从字节数组）
     * 格式: $<length>\r\n<bytes>\r\n
     * 
     * @param bytes 原始字节
     * @return RESP 格式的响应字符串
     */
    public static String buildBulkStringResponse(byte[] bytes) {
        if (bytes == null) {
            return "$-1\r\n";
        }
        String str = new String(bytes, StandardCharsets.ISO_8859_1);
        return "$" + bytes.length + "\r\n" + str + "\r\n";
    }
    
    /**
     * 构建 RESP 整数响应
     * 格式: :<number>\r\n
     * 
     * @param value 整数值
     * @return RESP 格式的响应字符串
     */
    public static String buildIntegerResponse(long value) {
        return ":" + value + "\r\n";
    }
    
    /**
     * 构建 RESP 简单字符串响应
     * 格式: +<string>\r\n
     * 用于状态回复（如 OK、QUEUED），使用 UTF-8 编码
     * 
     * @param str 简单字符串
     * @return RESP 格式的响应字符串
     */
    public static String buildSimpleStringResponse(String str) {
        return "+" + str + "\r\n";
    }
    
    /**
     * 构建 RESP 错误响应
     * 格式: -<error>\r\n
     * 使用 UTF-8 编码
     * 
     * @param error 错误消息
     * @return RESP 格式的响应字符串
     */
    public static String buildErrorResponse(String error) {
        return "-" + error + "\r\n";
    }
}