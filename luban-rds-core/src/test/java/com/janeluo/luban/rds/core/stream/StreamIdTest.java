package com.janeluo.luban.rds.core.stream;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * StreamId 单元测试
 * 
 * <p>测试 StreamId 的解析、比较、边界情况等功能
 */
public class StreamIdTest {

    // ==================== ID 解析测试 ====================

    @Test
    public void testParseCompleteId() {
        StreamId id = StreamId.parse("1234567890123-456");
        assertEquals(1234567890123L, id.getMillisecondsTime());
        assertEquals(456L, id.getSequenceNumber());
        assertEquals("1234567890123-456", id.toString());
    }

    @Test
    public void testParseTimestampOnly() {
        StreamId id = StreamId.parse("1234567890123");
        assertEquals(1234567890123L, id.getMillisecondsTime());
        assertEquals(0L, id.getSequenceNumber());
        assertEquals("1234567890123-0", id.toString());
    }

    @Test
    public void testParseMaxId() {
        StreamId id = StreamId.parse("+");
        assertEquals(StreamId.MAX_ID, id);
        assertEquals(Long.MAX_VALUE, id.getMillisecondsTime());
        assertEquals(Long.MAX_VALUE, id.getSequenceNumber());
    }

    @Test
    public void testParseMinId() {
        StreamId id = StreamId.parse("-");
        assertEquals(StreamId.MIN_ID, id);
        assertEquals(0L, id.getMillisecondsTime());
        assertEquals(0L, id.getSequenceNumber());
    }

    @Test
    public void testParseZeroSequence() {
        StreamId id = StreamId.parse("1000-0");
        assertEquals(1000L, id.getMillisecondsTime());
        assertEquals(0L, id.getSequenceNumber());
    }

    @Test
    public void testParseLargeSequence() {
        StreamId id = StreamId.parse("1000-999999999");
        assertEquals(1000L, id.getMillisecondsTime());
        assertEquals(999999999L, id.getSequenceNumber());
    }

    // ==================== ID 解析错误测试 ====================

    @Test(expected = IllegalArgumentException.class)
    public void testParseNullId() {
        StreamId.parse(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseEmptyId() {
        StreamId.parse("");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidFormat() {
        StreamId.parse("invalid");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidTimestamp() {
        StreamId.parse("abc-123");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseInvalidSequence() {
        StreamId.parse("123-abc");
    }

    // ==================== 构造函数测试 ====================

    @Test
    public void testConstructorNormal() {
        StreamId id = new StreamId(1000, 500);
        assertEquals(1000L, id.getMillisecondsTime());
        assertEquals(500L, id.getSequenceNumber());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNegativeTimestamp() {
        new StreamId(-1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorNegativeSequence() {
        new StreamId(0, -1);
    }

    // ==================== ID 比较测试 ====================

    @Test
    public void testCompareToEqual() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 500);
        assertEquals(0, id1.compareTo(id2));
        assertEquals(0, id2.compareTo(id1));
    }

    @Test
    public void testCompareToDifferentTimestamp() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(2000, 500);
        assertTrue(id1.compareTo(id2) < 0);
        assertTrue(id2.compareTo(id1) > 0);
    }

    @Test
    public void testCompareToDifferentSequence() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 600);
        assertTrue(id1.compareTo(id2) < 0);
        assertTrue(id2.compareTo(id1) > 0);
    }

    @Test
    public void testCompareToTimestampPriority() {
        // 时间戳优先于序号比较
        StreamId id1 = new StreamId(1000, 999);
        StreamId id2 = new StreamId(2000, 0);
        assertTrue(id1.compareTo(id2) < 0);
    }

    @Test
    public void testCompareToNull() {
        StreamId id = new StreamId(1000, 500);
        assertTrue(id.compareTo(null) > 0);
    }

    // ==================== isGreaterThan / isLessThan 测试 ====================

    @Test
    public void testIsGreaterThan() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 400);
        StreamId id3 = new StreamId(900, 500);

        assertTrue(id1.isGreaterThan(id2));
        assertTrue(id1.isGreaterThan(id3));
        assertFalse(id2.isGreaterThan(id1));
        assertFalse(id1.isGreaterThan(id1));
    }

    @Test
    public void testIsLessThan() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 600);
        StreamId id3 = new StreamId(1100, 500);

        assertTrue(id1.isLessThan(id2));
        assertTrue(id1.isLessThan(id3));
        assertFalse(id2.isLessThan(id1));
        assertFalse(id1.isLessThan(id1));
    }

    // ==================== 边界情况测试 ====================

    @Test
    public void testMinId() {
        StreamId minId = StreamId.MIN_ID;
        assertEquals(0L, minId.getMillisecondsTime());
        assertEquals(0L, minId.getSequenceNumber());
        assertEquals("0-0", minId.toString());
    }

    @Test
    public void testMaxId() {
        StreamId maxId = StreamId.MAX_ID;
        assertEquals(Long.MAX_VALUE, maxId.getMillisecondsTime());
        assertEquals(Long.MAX_VALUE, maxId.getSequenceNumber());
    }

    @Test
    public void testMinIdIsLessThanAll() {
        StreamId minId = StreamId.MIN_ID;
        StreamId normalId = new StreamId(1, 0);
        StreamId largeId = new StreamId(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1);

        assertTrue(minId.isLessThan(normalId));
        assertTrue(minId.isLessThan(largeId));
        assertTrue(minId.isLessThan(StreamId.MAX_ID));
    }

    @Test
    public void testMaxIdIsGreaterThanAll() {
        StreamId maxId = StreamId.MAX_ID;
        StreamId normalId = new StreamId(1000, 500);
        StreamId minId = StreamId.MIN_ID;

        assertTrue(maxId.isGreaterThan(normalId));
        assertTrue(maxId.isGreaterThan(minId));
    }

    // ==================== 开区间判断测试 ====================

    @Test
    public void testIsInRangeClosedInterval() {
        StreamId start = new StreamId(1000, 0);
        StreamId end = new StreamId(2000, 0);
        StreamId id1 = new StreamId(1000, 0);   // 边界
        StreamId id2 = new StreamId(1500, 0);   // 中间
        StreamId id3 = new StreamId(2000, 0);   // 边界

        assertTrue(id1.isInRange(start, end, false, false));
        assertTrue(id2.isInRange(start, end, false, false));
        assertTrue(id3.isInRange(start, end, false, false));
    }

    @Test
    public void testIsInRangeOpenInterval() {
        StreamId start = new StreamId(1000, 0);
        StreamId end = new StreamId(2000, 0);
        StreamId id1 = new StreamId(1000, 0);   // 边界
        StreamId id2 = new StreamId(1500, 0);   // 中间
        StreamId id3 = new StreamId(2000, 0);   // 边界

        // 开区间 (start, end)
        assertFalse(id1.isInRange(start, end, true, true));
        assertTrue(id2.isInRange(start, end, true, true));
        assertFalse(id3.isInRange(start, end, true, true));
    }

    @Test
    public void testIsInRangeHalfOpenLeft() {
        StreamId start = new StreamId(1000, 0);
        StreamId end = new StreamId(2000, 0);
        StreamId id1 = new StreamId(1000, 0);
        StreamId id3 = new StreamId(2000, 0);

        // (start, end]
        assertFalse(id1.isInRange(start, end, true, false));
        assertTrue(id3.isInRange(start, end, true, false));
    }

    @Test
    public void testIsInRangeHalfOpenRight() {
        StreamId start = new StreamId(1000, 0);
        StreamId end = new StreamId(2000, 0);
        StreamId id1 = new StreamId(1000, 0);
        StreamId id3 = new StreamId(2000, 0);

        // [start, end)
        assertTrue(id1.isInRange(start, end, false, true));
        assertFalse(id3.isInRange(start, end, false, true));
    }

    @Test
    public void testIsInRangeWithMinAndMax() {
        StreamId id = new StreamId(1000, 500);

        // 使用 MIN_ID 和 MAX_ID 作为边界
        assertTrue(id.isInRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false));
        assertTrue(id.isInRange(StreamId.MIN_ID, StreamId.MAX_ID, true, true));
    }

    // ==================== equals 和 hashCode 测试 ====================

    @Test
    public void testEquals() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 500);
        StreamId id3 = new StreamId(1000, 600);
        StreamId id4 = new StreamId(2000, 500);

        assertEquals(id1, id2);
        assertNotEquals(id1, id3);
        assertNotEquals(id1, id4);
        assertEquals(id1, id1);
        assertNotEquals(id1, null);
        assertNotEquals(id1, "string");
    }

    @Test
    public void testHashCode() {
        StreamId id1 = new StreamId(1000, 500);
        StreamId id2 = new StreamId(1000, 500);

        assertEquals(id1.hashCode(), id2.hashCode());
    }

    @Test
    public void testHashCodeConsistency() {
        StreamId id = new StreamId(1000, 500);
        int hash1 = id.hashCode();
        int hash2 = id.hashCode();
        assertEquals(hash1, hash2);
    }

    // ==================== toString 测试 ====================

    @Test
    public void testToString() {
        StreamId id = new StreamId(1234567890123L, 456);
        assertEquals("1234567890123-456", id.toString());
    }

    @Test
    public void testToStringMinId() {
        assertEquals("0-0", StreamId.MIN_ID.toString());
    }

    @Test
    public void testToStringMaxId() {
        String maxStr = StreamId.MAX_ID.toString();
        assertTrue(maxStr.contains("-"));
    }

    // ==================== 特殊场景测试 ====================

    @Test
    public void testSameTimestampDifferentSequence() {
        StreamId id1 = new StreamId(1000, 0);
        StreamId id2 = new StreamId(1000, 1);
        StreamId id3 = new StreamId(1000, 2);

        assertTrue(id1.isLessThan(id2));
        assertTrue(id2.isLessThan(id3));
        assertTrue(id1.isLessThan(id3));
    }

    @Test
    public void testLargeValues() {
        StreamId id = new StreamId(Long.MAX_VALUE - 100, Long.MAX_VALUE - 100);
        assertEquals(Long.MAX_VALUE - 100, id.getMillisecondsTime());
        assertEquals(Long.MAX_VALUE - 100, id.getSequenceNumber());
    }

    @Test
    public void testParseAndCompare() {
        StreamId id1 = StreamId.parse("1000-500");
        StreamId id2 = StreamId.parse("1000-600");
        StreamId id3 = StreamId.parse("2000-0");

        assertTrue(id1.isLessThan(id2));
        assertTrue(id2.isLessThan(id3));
    }
}
