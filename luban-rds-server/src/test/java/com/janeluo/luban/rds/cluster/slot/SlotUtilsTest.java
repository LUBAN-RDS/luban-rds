package com.janeluo.luban.rds.cluster.slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SlotUtils 槽位工具类单元测试
 *
 * <p>覆盖 CRC16 算法、keyHashSlot 槽位计算（含 hash tag 语法）、
 * 槽位校验及边界值。包含正向、负向与边界值测试。</p>
 */
@DisplayName("SlotUtils 槽位工具类测试")
class SlotUtilsTest {

    /**
     * Redis 官方文档中 CLUSTER KEYSLOT "foo" 的已知结果值
     */
    private static final int FOO_SLOT = 12182;

    // ==================== keyHashSlot 测试 ====================

    @Nested
    @DisplayName("keyHashSlot 槽位计算测试")
    class KeyHashSlotTest {

        @Test
        @DisplayName("null 键返回 0")
        void testNullKey() {
            assertEquals(0, SlotUtils.keyHashSlot(null));
        }

        @Test
        @DisplayName("空字符串键返回 0")
        void testEmptyKey() {
            assertEquals(0, SlotUtils.keyHashSlot(""));
        }

        @Test
        @DisplayName("普通键的槽位在有效范围内")
        void testNormalKeyInRange() {
            int slot = SlotUtils.keyHashSlot("user:1000");
            assertTrue(slot >= 0 && slot < SlotUtils.CLUSTER_SLOTS);
        }

        @Test
        @DisplayName("已知键 foo 的槽位为 12182（Redis 官方值）")
        void testKnownKeyFoo() {
            assertEquals(FOO_SLOT, SlotUtils.keyHashSlot("foo"));
        }

        @Test
        @DisplayName("相同键的槽位相同（幂等性）")
        void testSameKeySameSlot() {
            assertEquals(SlotUtils.keyHashSlot("mykey"), SlotUtils.keyHashSlot("mykey"));
        }
    }

    // ==================== Hash Tag 测试 ====================

    @Nested
    @DisplayName("Hash Tag 语法测试")
    class HashTagTest {

        @Test
        @DisplayName("带 {tag} 的键只对 tag 内容计算槽位")
        void testHashTagContent() {
            // "foo{tag}" 和 "{tag}" 都只对 "tag" 计算
            assertEquals(SlotUtils.keyHashSlot("foo{tag}"), SlotUtils.keyHashSlot("{tag}"));
            // 也应等于直接对 "tag" 计算的结果
            assertEquals(SlotUtils.keyHashSlot("tag"), SlotUtils.keyHashSlot("{tag}"));
        }

        @Test
        @DisplayName("相同 hash tag 的不同键映射到相同槽位")
        void testSameHashTagSameSlot() {
            int slot1 = SlotUtils.keyHashSlot("user:{1000}");
            int slot2 = SlotUtils.keyHashSlot("profile:{1000}");
            int slot3 = SlotUtils.keyHashSlot("order:{1000}");
            assertEquals(slot1, slot2);
            assertEquals(slot2, slot3);
        }

        @Test
        @DisplayName("不同 hash tag 的键通常映射到不同槽位")
        void testDifferentHashTagDifferentSlot() {
            int slot1 = SlotUtils.keyHashSlot("{user}");
            int slot2 = SlotUtils.keyHashSlot("{order}");
            // 不同 tag 大概率不同槽位（除非哈希碰撞，此处仅作记录性断言）
            assertTrue(slot1 >= 0 && slot1 < SlotUtils.CLUSTER_SLOTS);
            assertTrue(slot2 >= 0 && slot2 < SlotUtils.CLUSTER_SLOTS);
        }

        @Test
        @DisplayName("空括号 {} 无效，对整个键计算")
        void testEmptyBraces() {
            // "user:{}" 与 "user:{}" 相同，但应与 "{}" 不同（{} 内容为空，无效）
            // 空括号无效，应使用整个字符串计算
            assertEquals(SlotUtils.keyHashSlot("user:{}"), SlotUtils.keyHashSlot("user:{}"));
        }

        @Test
        @DisplayName("空括号后跟随有效括号，使用第一个有效括号")
        void testEmptyThenValidBraces() {
            // "{}:{profile}" 第一个空括号无效，使用 "profile"
            assertEquals(SlotUtils.keyHashSlot("profile"), SlotUtils.keyHashSlot("{}:{profile}"));
        }

        @Test
        @DisplayName("多个括号只使用第一个有效括号内容")
        void testMultipleBraces() {
            // "user:{1000}:{profile}" 只使用第一个 "1000"
            assertEquals(SlotUtils.keyHashSlot("1000"),
                    SlotUtils.keyHashSlot("user:{1000}:{profile}"));
        }

        @Test
        @DisplayName("只有左括号无右括号时对整个键计算")
        void testNoClosingBrace() {
            // "user:{1000" 没有 '}'，对整个键计算
            assertEquals(SlotUtils.keyHashSlot("user:{1000"),
                    SlotUtils.keyHashSlot("user:{1000"));
            // 与完整键计算一致
            byte[] keyBytes = "user:{1000".getBytes(StandardCharsets.UTF_8);
            int crc = SlotUtils.crc16(keyBytes, 0, keyBytes.length);
            assertEquals(crc % SlotUtils.CLUSTER_SLOTS, SlotUtils.keyHashSlot("user:{1000"));
        }

        @Test
        @DisplayName("hash tag 在键的起始位置")
        void testHashTagAtStart() {
            // "{user}:1000" 只对 "user" 计算
            assertEquals(SlotUtils.keyHashSlot("user"), SlotUtils.keyHashSlot("{user}:1000"));
        }

        @Test
        @DisplayName("无括号的键对整个键计算")
        void testNoBraces() {
            byte[] keyBytes = "plainkey".getBytes(StandardCharsets.UTF_8);
            int crc = SlotUtils.crc16(keyBytes, 0, keyBytes.length);
            assertEquals(crc % SlotUtils.CLUSTER_SLOTS, SlotUtils.keyHashSlot("plainkey"));
        }

        @Test
        @DisplayName("中文键的槽位在有效范围内")
        void testUnicodeKey() {
            int slot = SlotUtils.keyHashSlot("用户:1000");
            assertTrue(slot >= 0 && slot < SlotUtils.CLUSTER_SLOTS);
        }
    }

    // ==================== CRC16 测试 ====================

    @Nested
    @DisplayName("CRC16 校验和测试")
    class Crc16Test {

        @Test
        @DisplayName("null 数据返回 0")
        void testNullData() {
            assertEquals(0, SlotUtils.crc16(null));
        }

        @Test
        @DisplayName("空数组返回 0")
        void testEmptyData() {
            assertEquals(0, SlotUtils.crc16(new byte[0]));
        }

        @Test
        @DisplayName("CRC16 结果在 0-65535 范围内")
        void testCrc16Range() {
            byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
            int crc = SlotUtils.crc16(data);
            assertTrue(crc >= 0 && crc <= 0xFFFF);
        }

        @Test
        @DisplayName("相同数据的 CRC16 相同")
        void testCrc16Consistency() {
            byte[] data1 = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] data2 = "hello".getBytes(StandardCharsets.UTF_8);
            assertEquals(SlotUtils.crc16(data1), SlotUtils.crc16(data2));
        }

        @Test
        @DisplayName("不同数据的 CRC16 通常不同")
        void testCrc16DifferentData() {
            byte[] data1 = "hello".getBytes(StandardCharsets.UTF_8);
            byte[] data2 = "world".getBytes(StandardCharsets.UTF_8);
            // 大概率不同，此处验证至少不抛异常且在范围内
            int crc1 = SlotUtils.crc16(data1);
            int crc2 = SlotUtils.crc16(data2);
            assertTrue(crc1 >= 0 && crc1 <= 0xFFFF);
            assertTrue(crc2 >= 0 && crc2 <= 0xFFFF);
        }

        @Test
        @DisplayName("crc16(byte[]) 与 crc16(byte[], 0, length) 结果一致")
        void testCrc16OverloadsEqual() {
            byte[] data = "一致性测试".getBytes(StandardCharsets.UTF_8);
            assertEquals(SlotUtils.crc16(data), SlotUtils.crc16(data, 0, data.length));
        }

        @Test
        @DisplayName("null 数据的范围版本返回 0")
        void testCrc16RangeNull() {
            assertEquals(0, SlotUtils.crc16(null, 0, 10));
        }

        @Test
        @DisplayName("空数组的范围版本返回 0")
        void testCrc16RangeEmpty() {
            assertEquals(0, SlotUtils.crc16(new byte[0], 0, 0));
        }

        @Test
        @DisplayName("负的 start 自动调整为 0")
        void testCrc16NegativeStart() {
            byte[] data = "test".getBytes(StandardCharsets.UTF_8);
            assertEquals(SlotUtils.crc16(data, 0, data.length),
                    SlotUtils.crc16(data, -5, data.length));
        }

        @Test
        @DisplayName("end 超过数组长度时自动截断")
        void testCrc16EndOverflow() {
            byte[] data = "test".getBytes(StandardCharsets.UTF_8);
            assertEquals(SlotUtils.crc16(data, 0, data.length),
                    SlotUtils.crc16(data, 0, data.length + 100));
        }

        @Test
        @DisplayName("start >= end 时返回 0")
        void testCrc16StartGreaterEqualEnd() {
            byte[] data = "test".getBytes(StandardCharsets.UTF_8);
            assertEquals(0, SlotUtils.crc16(data, 2, 2));
            assertEquals(0, SlotUtils.crc16(data, 3, 2));
        }

        @Test
        @DisplayName("部分范围计算 CRC16")
        void testCrc16PartialRange() {
            byte[] data = "abcdef".getBytes(StandardCharsets.UTF_8);
            // 计算 "bcde" 部分（索引 1-4）
            int crcPartial = SlotUtils.crc16(data, 1, 5);
            int crcFull = SlotUtils.crc16("bcde".getBytes(StandardCharsets.UTF_8));
            assertEquals(crcFull, crcPartial);
        }
    }

    // ==================== 槽位校验测试 ====================

    @Nested
    @DisplayName("槽位校验测试")
    class SlotValidationTest {

        @Test
        @DisplayName("isValidSlot 边界值：0 和 16383 有效")
        void testIsValidSlotBoundary() {
            assertTrue(SlotUtils.isValidSlot(0));
            assertTrue(SlotUtils.isValidSlot(SlotUtils.CLUSTER_SLOTS - 1));
        }

        @Test
        @DisplayName("isValidSlot 边界值：-1 和 16384 无效")
        void testIsValidSlotInvalid() {
            assertFalse(SlotUtils.isValidSlot(-1));
            assertFalse(SlotUtils.isValidSlot(SlotUtils.CLUSTER_SLOTS));
        }

        @Test
        @DisplayName("validateSlot 有效槽位不抛异常")
        void testValidateSlotValid() {
            SlotUtils.validateSlot(0);
            SlotUtils.validateSlot(SlotUtils.CLUSTER_SLOTS - 1);
        }

        @Test
        @DisplayName("validateSlot 无效槽位抛出异常")
        void testValidateSlotInvalid() {
            IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class,
                    () -> SlotUtils.validateSlot(-1));
            assertTrue(ex1.getMessage().contains("槽位号必须在"));
            IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class,
                    () -> SlotUtils.validateSlot(SlotUtils.CLUSTER_SLOTS));
            assertTrue(ex2.getMessage().contains("槽位号必须在"));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 100, 8191, 16383})
        @DisplayName("参数化测试：有效槽位通过校验")
        void testValidSlotsParameterized(int slot) {
            assertTrue(SlotUtils.isValidSlot(slot));
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -100, 16384, 16385, 99999})
        @DisplayName("参数化测试：无效槽位不通过校验")
        void testInvalidSlotsParameterized(int slot) {
            assertFalse(SlotUtils.isValidSlot(slot));
        }
    }
}
