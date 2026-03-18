package com.janeluo.luban.rds.sentinel.util;

import java.security.SecureRandom;

/**
 * 哨兵工具类
 */
public class SentinelUtils {
    
    private static final SecureRandom RANDOM = new SecureRandom();
    
    /**
     * 生成随机 ID
     */
    public static String generateId(int length) {
        byte[] bytes = new byte[length / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString().substring(0, length);
    }
    
    /**
     * 格式化地址
     */
    public static String formatAddress(String host, int port) {
        return host + ":" + port;
    }
    
    /**
     * 解析地址
     */
    public static String[] parseAddress(String address) {
        if (address == null || address.isEmpty()) {
            return null;
        }
        
        int colonIndex = address.lastIndexOf(':');
        if (colonIndex < 0) {
            return null;
        }
        
        String host = address.substring(0, colonIndex);
        String portStr = address.substring(colonIndex + 1);
        
        try {
            return new String[]{host, portStr};
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 检查是否是有效的端口号
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }
    
    /**
     * 检查是否是有效的 IP 地址
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        // 简单验证
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        
        for (String part : parts) {
            try {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * 计算时间差（毫秒）
     */
    public static long timeDiff(long startTime, long endTime) {
        return endTime - startTime;
    }
    
    /**
     * 检查是否超时
     */
    public static boolean isTimeout(long startTime, long timeoutMs) {
        return System.currentTimeMillis() - startTime > timeoutMs;
    }
    
    /**
     * 格式化持续时间
     */
    public static String formatDuration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        } else if (durationMs < 60000) {
            return (durationMs / 1000) + "s";
        } else if (durationMs < 3600000) {
            long minutes = durationMs / 60000;
            long seconds = (durationMs % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        } else {
            long hours = durationMs / 3600000;
            long minutes = (durationMs % 3600000) / 60000;
            return hours + "h " + minutes + "m";
        }
    }
    
    /**
     * 匹配模式
     */
    public static boolean matchPattern(String text, String pattern) {
        if (pattern.equals("*")) {
            return true;
        }
        
        String regex = pattern.replace("*", ".*");
        return text.matches(regex);
    }
    
    private SentinelUtils() {
        // 私有构造函数
    }
}
