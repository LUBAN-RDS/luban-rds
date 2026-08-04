package com.janeluo.luban.rds.cluster.slot;

import java.nio.charset.StandardCharsets;

/**
 * 槽位工具类
 * 实现 CRC16 算法和槽位计算
 * <p>
 * Redis集群使用CRC16算法计算key的槽位，支持{tag}语法实现hash tag
 * </p>
 */
public class SlotUtils {

    /**
     * Redis集群槽位总数
     */
    public static final int CLUSTER_SLOTS = 16384;

    /**
     * CRC16 查找表
     * 使用 CRC-16-CCITT 多项式 0x1021
     */
    private static final int[] CRC16_TABLE = new int[256];

    static {
        // 初始化 CRC16 查找表 (CRC-16-CCITT 多项式 0x1021)
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

    /**
     * 私有构造方法，防止实例化
     */
    private SlotUtils() {
    }

    /**
     * 计算键的槽位
     * <p>
     * 支持 {tag} 语法，只计算大括号内的部分。
     * 如果key中包含 {...}，则只对大括号内的内容计算hash；
     * 否则对整个key计算hash。
     * </p>
     *
     * <pre>
     * 示例（与 Redis CLUSTER KEYSLOT 语义一致）：
     * - "user:1000" -> 对整个字符串计算
     * - "user:{1000}" -> 只对 "1000" 计算
     * - "{user}:1000" -> 只对 "user" 计算
     * - "user:{1000}:profile" -> 只对 "1000" 计算
     * - "user:{1000}:{profile}" -> 只对第一个 "1000" 计算
     * - "user:{}:profile" -> 对整个字符串计算（空括号无效）
     * - "{}:{profile}" -> 对整个字符串计算（第一个空括号无效，不继续找后续括号）
     * </pre>
     * <p>
     * 对齐 Redis keyHashSlot 规则：只查找第一个 '{' 及其后第一个 '}'，
     * 若 '}' 不存在或 '{}' 之间为空，则对整个 key 计算，不继续向后查找。
     * </p>
     *
     * @param key 键名
     * @return 槽位号 (0-16383)
     */
    public static int keyHashSlot(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        int start = 0;
        int end = keyBytes.length;

        // 对齐 Redis：只找第一个 '{'，再找其后第一个 '}'
        // 若 '}' 不存在或 '{}' 之间为空（e == s+1），则对整个 key 计算，不继续向后查找
        int s = indexOf(keyBytes, (byte) '{', 0);
        if (s >= 0) {
            int e = indexOf(keyBytes, (byte) '}', s + 1);
            if (e > s + 1) {
                start = s + 1;
                end = e;
            }
        }

        // 计算 CRC16 并取模
        int crc16 = crc16(keyBytes, start, end);
        return crc16 % CLUSTER_SLOTS;
    }

    /**
     * 计算字节数组的 CRC16 校验和
     *
     * @param data 字节数组
     * @return CRC16 校验和 (0-65535)
     */
    public static int crc16(byte[] data) {
        if (data == null || data.length == 0) {
            return 0;
        }
        return crc16(data, 0, data.length);
    }

    /**
     * 计算字节数组指定范围的 CRC16 校验和
     *
     * @param data  字节数组
     * @param start 起始位置（包含）
     * @param end   结束位置（不包含）
     * @return CRC16 校验和 (0-65535)
     */
    public static int crc16(byte[] data, int start, int end) {
        if (data == null || data.length == 0) {
            return 0;
        }
        if (start < 0) {
            start = 0;
        }
        if (end > data.length) {
            end = data.length;
        }
        if (start >= end) {
            return 0;
        }

        int crc = 0;
        for (int i = start; i < end; i++) {
            crc = ((crc << 8) ^ CRC16_TABLE[((crc >>> 8) ^ (data[i] & 0xFF)) & 0xFF]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * 在字节数组中查找指定字节的位置
     *
     * @param data     字节数组
     * @param target   目标字节
     * @param fromIndex 起始位置
     * @return 找到的位置，未找到返回 -1
     */
    private static int indexOf(byte[] data, byte target, int fromIndex) {
        for (int i = fromIndex; i < data.length; i++) {
            if (data[i] == target) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 验证槽位号是否有效
     *
     * @param slot 槽位号
     * @return 是否有效
     */
    public static boolean isValidSlot(int slot) {
        return slot >= 0 && slot < CLUSTER_SLOTS;
    }

    /**
     * 验证槽位号，无效时抛出异常
     *
     * @param slot 槽位号
     * @throws IllegalArgumentException 如果槽位号无效
     */
    public static void validateSlot(int slot) {
        if (!isValidSlot(slot)) {
            // 对齐 Redis clusterCommand 的槽位越界错误串（客户端按错误串匹配）
            throw new IllegalArgumentException("Invalid slot specified");
        }
    }
}
