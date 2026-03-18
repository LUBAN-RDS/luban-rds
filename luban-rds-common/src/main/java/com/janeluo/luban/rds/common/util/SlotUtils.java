package com.janeluo.luban.rds.common.util;

/**
 * 槽位计算工具类
 * 用于 Redis Cluster 兼容的槽位计算
 * 
 * Redis Cluster 使用 CRC16 算法计算键的槽位
 * 槽位范围：0 - 16383 (共 16384 个槽位)
 * 
 * @author janeluo
 * @since 1.0.0
 */
public final class SlotUtils {
    
    /**
     * Redis Cluster 槽位总数
     */
    public static final int SLOT_COUNT = 16384;
    
    /**
     * CRC16 查找表
     * 使用多项式 x^16 + x^12 + x^5 + 1 (0x1021)
     */
    private static final int[] CRC16_TABLE = new int[256];
    
    static {
        // 初始化 CRC16 查找表
        for (int i = 0; i < 256; i++) {
            int crc = i << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
            }
            CRC16_TABLE[i] = crc & 0xFFFF;
        }
    }
    
    private SlotUtils() {
        // 私有构造函数，防止实例化
    }
    
    /**
     * 计算键的槽位号
     * 使用 CRC16 算法，结果对 16384 取模
     * 
     * @param key 键名
     * @return 槽位号 (0 - 16383)
     */
    public static int getSlot(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        
        // 处理哈希标签 {tag}
        // 如果键中包含 {}，则只计算 {} 内部分
        String hashKey = extractHashTag(key);
        
        return crc16(hashKey) % SLOT_COUNT;
    }
    
    /**
     * 计算字节数组的槽位号
     * 
     * @param key 键的字节数组
     * @return 槽位号 (0 - 16383)
     */
    public static int getSlot(byte[] key) {
        if (key == null || key.length == 0) {
            return 0;
        }
        
        // 处理哈希标签
        String keyStr = new String(key);
        String hashKey = extractHashTag(keyStr);
        
        return crc16(hashKey.getBytes()) % SLOT_COUNT;
    }
    
    /**
     * 提取哈希标签
     * Redis 使用 {} 标记哈希标签，只有 {} 内的内容参与槽位计算
     * 例如：{user}:1 和 {user}:2 会被分配到同一个槽位
     * 
     * @param key 键名
     * @return 用于计算槽位的键部分
     */
    public static String extractHashTag(String key) {
        if (key == null) {
            return "";
        }
        
        int start = key.indexOf('{');
        if (start >= 0) {
            int end = key.indexOf('}', start + 1);
            if (end > start + 1) {
                // 找到有效的哈希标签
                return key.substring(start + 1, end);
            }
        }
        
        // 没有哈希标签，使用整个键
        return key;
    }
    
    /**
     * 计算 CRC16 校验值
     * 
     * @param data 输入字符串
     * @return CRC16 校验值
     */
    public static int crc16(String data) {
        if (data == null) {
            return 0;
        }
        return crc16(data.getBytes());
    }
    
    /**
     * 计算 CRC16 校验值
     * 
     * @param data 输入字节数组
     * @return CRC16 校验值
     */
    public static int crc16(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        
        int crc = 0;
        for (byte b : data) {
            // 将 byte 转换为无符号值 (0-255)
            int index = ((crc >> 8) ^ (b & 0xFF)) & 0xFF;
            crc = ((crc << 8) ^ CRC16_TABLE[index]) & 0xFFFF;
        }
        
        return crc;
    }
    
    /**
     * 检查槽位号是否有效
     * 
     * @param slot 槽位号
     * @return 是否有效
     */
    public static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < SLOT_COUNT;
    }
}
