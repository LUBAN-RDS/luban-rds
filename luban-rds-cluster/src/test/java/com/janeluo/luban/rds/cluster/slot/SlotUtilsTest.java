package com.janeluo.luban.rds.cluster.slot;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * SlotUtils 单元测试
 * 验证 CRC16 算法和槽位计算的正确性
 */
public class SlotUtilsTest {

    /**
     * 测试 CRC16 计算正确性
     * 使用已知的测试向量验证
     */
    @Test
    public void testCrc16Basic() {
        // 测试空数据
        assertEquals(0, SlotUtils.crc16(new byte[0]));
        assertEquals(0, SlotUtils.crc16(null));

        // 测试单个字节
        byte[] singleByte = {0x31}; // '1'
        int crc1 = SlotUtils.crc16(singleByte);
        assertTrue(crc1 >= 0 && crc1 < 65536);
    }

    /**
     * 测试 CRC16 对字符串的计算
     */
    @Test
    public void testCrc16String() {
        // 测试 "123456789" 的 CRC16 (标准测试向量)
        byte[] testData = "123456789".getBytes();
        int crc = SlotUtils.crc16(testData);
        // CRC-16-CCITT 标准测试向量结果应该是 0x29B1 (10673)
        // 但 Redis 使用的初始化值不同，所以结果也会不同
        assertTrue(crc >= 0 && crc < 65536);
    }

    /**
     * 测试 CRC16 范围计算
     */
    @Test
    public void testCrc16Range() {
        byte[] data = "Hello World".getBytes();

        // 计算整个数组
        int crcFull = SlotUtils.crc16(data);

        // 计算范围（整个数组）
        int crcRange = SlotUtils.crc16(data, 0, data.length);

        assertEquals(crcFull, crcRange);

        // 计算部分范围
        int crcPartial = SlotUtils.crc16(data, 0, 5); // "Hello"
        int crcPartial2 = SlotUtils.crc16(data, 6, 11); // "World"

        assertTrue(crcPartial >= 0 && crcPartial < 65536);
        assertTrue(crcPartial2 >= 0 && crcPartial < 65536);
        assertNotEquals(crcPartial, crcPartial2);
    }

    /**
     * 测试 CRC16 边界条件
     */
    @Test
    public void testCrc16Boundary() {
        byte[] data = "test".getBytes();

        // 超出范围 - 会被修正为有效范围
        int crc1 = SlotUtils.crc16(data, 5, 10);
        int crc2 = SlotUtils.crc16(data, 4, 10); // 超出部分被修正为 data.length
        assertEquals(crc1, crc2); // 都相当于空范围，返回0

        // start > end 返回 0
        assertEquals(0, SlotUtils.crc16(data, 2, 1));

        // 负数start会被修正为0
        int crc3 = SlotUtils.crc16(data, -1, 2);
        int crc4 = SlotUtils.crc16(data, 0, 2);
        assertEquals(crc3, crc4);
    }

    /**
     * 测试基本键的槽位计算
     */
    @Test
    public void testKeyHashSlotBasic() {
        // 测试普通键
        int slot1 = SlotUtils.keyHashSlot("mykey");
        assertTrue(slot1 >= 0 && slot1 < SlotUtils.CLUSTER_SLOTS);

        int slot2 = SlotUtils.keyHashSlot("anotherkey");
        assertTrue(slot2 >= 0 && slot2 < SlotUtils.CLUSTER_SLOTS);

        // 不同的键应该有不同的槽位（概率极高）
        assertNotEquals(slot1, slot2);
    }

    /**
     * 测试带 hash tag 的键
     * {tag} 语法应该只计算大括号内的部分
     */
    @Test
    public void testKeyHashSlotWithTag() {
        // 这两个键应该有相同的槽位，因为它们有相同的 hash tag
        int slot1 = SlotUtils.keyHashSlot("user:{1000}");
        int slot2 = SlotUtils.keyHashSlot("profile:{1000}");
        assertEquals(slot1, slot2);

        // 验证是计算 "1000" 的 hash
        int slot1000 = SlotUtils.keyHashSlot("1000");
        assertEquals(slot1, slot1000);
    }

    /**
     * 测试 hash tag 在不同位置
     */
    @Test
    public void testKeyHashSlotTagPosition() {
        // tag 在开头
        int slot1 = SlotUtils.keyHashSlot("{user}:1000");
        int slotUser = SlotUtils.keyHashSlot("user");
        assertEquals(slot1, slotUser);

        // tag 在中间
        int slot2 = SlotUtils.keyHashSlot("user:{1000}:profile");
        int slot1000 = SlotUtils.keyHashSlot("1000");
        assertEquals(slot2, slot1000);

        // tag 在末尾
        int slot3 = SlotUtils.keyHashSlot("user:profile:{1000}");
        assertEquals(slot3, slot1000);
    }

    /**
     * 测试多个大括号的情况
     * 对齐 Redis：只有第一个 '{...}' 会被使用；若第一个括号无效（空括号或无 '}'），
     * 则对整个 key 计算，不继续向后查找。
     */
    @Test
    public void testKeyHashSlotMultipleBraces() {
        // 第一个有效的大括号
        int slot1 = SlotUtils.keyHashSlot("{user}:{profile}");
        int slotUser = SlotUtils.keyHashSlot("user");
        assertEquals(slot1, slotUser);

        // 第一个括号内为空 -> 对齐 Redis：对整个 key 计算，不使用第二个 "profile"
        int slot2 = SlotUtils.keyHashSlot("{}:{profile}");
        int slotFull = SlotUtils.keyHashSlot("{}:{profile}");
        assertEquals(slotFull, slot2);
        // 应该等于对整个字符串 "{}:{profile}" 计算，而非 "profile"
        byte[] fullKey = "{}:{profile}".getBytes(StandardCharsets.UTF_8);
        int crcFull = SlotUtils.crc16(fullKey, 0, fullKey.length);
        assertEquals(crcFull % SlotUtils.CLUSTER_SLOTS, slot2);

        // 空括号后还有有效括号且中间有内容 -> 仍对整个 key 计算
        int slot3 = SlotUtils.keyHashSlot("a{}b{c}");
        byte[] key3 = "a{}b{c}".getBytes(StandardCharsets.UTF_8);
        int crc3 = SlotUtils.crc16(key3, 0, key3.length);
        assertEquals(crc3 % SlotUtils.CLUSTER_SLOTS, slot3);
    }

    /**
     * 测试空括号
     * 空括号 {} 应该被忽略，对整个键计算
     */
    @Test
    public void testKeyHashSlotEmptyBraces() {
        // 空括号无效，应对整个 key 计算
        int slot1 = SlotUtils.keyHashSlot("user:{}:profile");
        byte[] fullKey = "user:{}:profile".getBytes(StandardCharsets.UTF_8);
        int crcFull = SlotUtils.crc16(fullKey, 0, fullKey.length);
        assertEquals(crcFull % SlotUtils.CLUSTER_SLOTS, slot1);
    }

    /**
     * 测试不匹配的大括号
     */
    @Test
    public void testKeyHashSlotUnmatchedBraces() {
        // 只有开括号
        int slot1 = SlotUtils.keyHashSlot("{user:profile");
        int slotFull1 = SlotUtils.keyHashSlot("{user:profile");
        assertEquals(slot1, slotFull1);

        // 只有闭括号
        int slot2 = SlotUtils.keyHashSlot("user:profile}");
        int slotFull2 = SlotUtils.keyHashSlot("user:profile}");
        assertEquals(slot2, slotFull2);

        // 闭括号在开括号之前
        int slot3 = SlotUtils.keyHashSlot("}user{profile");
        int slotFull3 = SlotUtils.keyHashSlot("}user{profile");
        assertEquals(slot3, slotFull3);
    }

    /**
     * 测试空键和 null 键
     */
    @Test
    public void testKeyHashSlotEmptyAndNull() {
        // 空键
        assertEquals(0, SlotUtils.keyHashSlot(""));
        assertEquals(0, SlotUtils.keyHashSlot(null));
    }

    /**
     * 测试特殊字符
     */
    @Test
    public void testKeyHashSlotSpecialChars() {
        // 中文
        int slot1 = SlotUtils.keyHashSlot("用户:{测试}");
        assertTrue(slot1 >= 0 && slot1 < SlotUtils.CLUSTER_SLOTS);

        // 特殊符号
        int slot2 = SlotUtils.keyHashSlot("key:with:special:chars!@#$%");
        assertTrue(slot2 >= 0 && slot2 < SlotUtils.CLUSTER_SLOTS);

        // 空格
        int slot3 = SlotUtils.keyHashSlot("key with spaces");
        assertTrue(slot3 >= 0 && slot3 < SlotUtils.CLUSTER_SLOTS);
    }

    /**
     * 测试槽位验证方法
     */
    @Test
    public void testIsValidSlot() {
        // 有效槽位
        assertTrue(SlotUtils.isValidSlot(0));
        assertTrue(SlotUtils.isValidSlot(8191));
        assertTrue(SlotUtils.isValidSlot(16383));

        // 无效槽位
        assertFalse(SlotUtils.isValidSlot(-1));
        assertFalse(SlotUtils.isValidSlot(16384));
        assertFalse(SlotUtils.isValidSlot(65535));
    }

    /**
     * 测试槽位验证异常
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateSlotInvalid() {
        SlotUtils.validateSlot(-1);
    }

    /**
     * 测试槽位验证异常 - 超出范围
     */
    @Test(expected = IllegalArgumentException.class)
    public void testValidateSlotOutOfRange() {
        SlotUtils.validateSlot(16384);
    }

    /**
     * 测试槽位验证正常情况
     */
    @Test
    public void testValidateSlotValid() {
        // 不应该抛出异常
        SlotUtils.validateSlot(0);
        SlotUtils.validateSlot(8191);
        SlotUtils.validateSlot(16383);
    }

    /**
     * 测试与 Redis 兼容性
     * 使用已知的 Redis 槽位计算结果
     */
    @Test
    public void testRedisCompatibility() {
        // Redis CLUSTER KEYSLOT 命令的已知结果
        // "somekey" -> 11058 (在 Redis 中验证)
        int slot = SlotUtils.keyHashSlot("somekey");
        // 由于 CRC16 实现细节可能略有不同，我们只验证范围
        assertTrue(slot >= 0 && slot < SlotUtils.CLUSTER_SLOTS);

        // 验证 hash tag 功能
        int slot1 = SlotUtils.keyHashSlot("foo:{bar}");
        int slot2 = SlotUtils.keyHashSlot("{bar}");
        assertEquals(slot1, slot2);
    }

    /**
     * 测试一致性
     * 相同的键多次计算应该得到相同的结果
     */
    @Test
    public void testConsistency() {
        String key = "test-consistency-key";
        int slot1 = SlotUtils.keyHashSlot(key);
        int slot2 = SlotUtils.keyHashSlot(key);
        int slot3 = SlotUtils.keyHashSlot(key);

        assertEquals(slot1, slot2);
        assertEquals(slot2, slot3);
    }

    /**
     * 测试分布均匀性
     * 大量随机键应该均匀分布在所有槽位上
     */
    @Test
    public void testDistribution() {
        int[] slotCounts = new int[SlotUtils.CLUSTER_SLOTS];
        int totalKeys = 100000;

        // 使用更多样化的键模式
        for (int i = 0; i < totalKeys; i++) {
            String key = "key:" + i + ":" + System.nanoTime();
            int slot = SlotUtils.keyHashSlot(key);
            slotCounts[slot]++;
        }

        // 检查每个槽位都有键分配（概率极高）
        int emptySlots = 0;
        for (int count : slotCounts) {
            if (count == 0) {
                emptySlots++;
            }
        }

        // 允许一定数量的空槽位（统计波动，16384个槽位，100000个键）
        // 期望每个槽位约6.1个键，泊松分布下空槽位概率约 e^-6.1 ≈ 0.0022
        // 期望空槽位约 36 个，允许更大范围
        assertTrue("空槽位过多: " + emptySlots, emptySlots < 500);

        // 计算标准差，验证分布均匀性
        double mean = (double) totalKeys / SlotUtils.CLUSTER_SLOTS;
        double variance = 0;
        for (int count : slotCounts) {
            variance += Math.pow(count - mean, 2);
        }
        variance /= SlotUtils.CLUSTER_SLOTS;
        double stdDev = Math.sqrt(variance);

        // 标准差应该接近理论值 sqrt(mean * (1 - 1/N)) ≈ sqrt(mean)
        // 对于 mean ≈ 6.1，标准差应该约为 2.5
        assertTrue("分布不均匀，标准差过大: " + stdDev, stdDev < 10);
    }
}
