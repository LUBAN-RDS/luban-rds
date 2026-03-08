package com.janeluo.luban.rds.core.stream;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Stream 单元测试
 * 
 * <p>测试 Stream 的消息添加、删除、范围查询、裁剪等功能
 */
public class StreamTest {

    private Stream stream;

    @Before
    public void setUp() {
        stream = new Stream();
    }

    // ==================== 消息添加测试（自动生成 ID） ====================

    @Test
    public void testAddEntryAutoGenerateId() {
        Map<String, String> fields = createFields("field1", "value1");
        StreamId id = stream.addEntry(null, fields);

        assertNotNull(id);
        assertTrue(id.getMillisecondsTime() > 0);
        assertEquals(1L, stream.getLength());
    }

    @Test
    public void testAddEntryAutoGenerateIdMultiple() {
        Map<String, String> fields1 = createFields("field1", "value1");
        Map<String, String> fields2 = createFields("field2", "value2");

        StreamId id1 = stream.addEntry(null, fields1);
        StreamId id2 = stream.addEntry(null, fields2);

        assertNotNull(id1);
        assertNotNull(id2);
        assertTrue(id2.isGreaterThan(id1));
        assertEquals(2L, stream.getLength());
    }

    @Test
    public void testAddEntrySameTimestamp() throws InterruptedException {
        // 快速添加多条消息，可能产生相同时间戳
        for (int i = 0; i < 10; i++) {
            Map<String, String> fields = createFields("field" + i, "value" + i);
            stream.addEntry(null, fields);
        }

        assertEquals(10L, stream.getLength());

        // 验证所有 ID 都是唯一的
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, -1);
        assertEquals(10, entries.size());

        // 验证 ID 是递增的
        for (int i = 1; i < entries.size(); i++) {
            assertTrue(entries.get(i).getId().isGreaterThan(entries.get(i - 1).getId()));
        }
    }

    // ==================== 消息添加测试（指定 ID） ====================

    @Test
    public void testAddEntryWithSpecificId() {
        StreamId id = new StreamId(1000, 0);
        Map<String, String> fields = createFields("field1", "value1");

        StreamId resultId = stream.addEntry(id, fields);

        assertEquals(id, resultId);
        assertEquals(1L, stream.getLength());
    }

    @Test
    public void testAddEntryWithIncreasingIds() {
        StreamId id1 = new StreamId(1000, 0);
        StreamId id2 = new StreamId(1000, 1);
        StreamId id3 = new StreamId(2000, 0);

        stream.addEntry(id1, createFields("f1", "v1"));
        stream.addEntry(id2, createFields("f2", "v2"));
        stream.addEntry(id3, createFields("f3", "v3"));

        assertEquals(3L, stream.getLength());
        assertEquals(id3, stream.getLastGeneratedId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddEntryWithDuplicateId() {
        StreamId id = new StreamId(1000, 0);
        Map<String, String> fields = createFields("field1", "value1");

        stream.addEntry(id, fields);
        stream.addEntry(id, fields); // 应该抛出异常
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddEntryWithSmallerId() {
        StreamId id1 = new StreamId(2000, 0);
        StreamId id2 = new StreamId(1000, 0);

        stream.addEntry(id1, createFields("f1", "v1"));
        stream.addEntry(id2, createFields("f2", "v2")); // 应该抛出异常
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddEntryWithEqualId() {
        StreamId id1 = new StreamId(1000, 0);
        StreamId id2 = new StreamId(1000, 0);

        stream.addEntry(id1, createFields("f1", "v1"));
        stream.addEntry(id2, createFields("f2", "v2")); // 应该抛出异常
    }

    // ==================== 消息添加错误测试 ====================

    @Test(expected = IllegalArgumentException.class)
    public void testAddEntryWithNullFields() {
        stream.addEntry(null, null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testAddEntryWithEmptyFields() {
        stream.addEntry(null, new HashMap<>());
    }

    // ==================== 消息删除测试 ====================

    @Test
    public void testDeleteEntry() {
        StreamId id = stream.addEntry(null, createFields("field1", "value1"));
        assertEquals(1L, stream.getLength());

        boolean deleted = stream.deleteEntry(id);

        assertTrue(deleted);
        assertEquals(0L, stream.getLength());
        assertNull(stream.getEntry(id));
    }

    @Test
    public void testDeleteEntryNonExistent() {
        StreamId id = new StreamId(999, 999);
        boolean deleted = stream.deleteEntry(id);

        assertFalse(deleted);
    }

    @Test
    public void testDeleteEntryNull() {
        boolean deleted = stream.deleteEntry(null);
        assertFalse(deleted);
    }

    @Test
    public void testDeleteMultipleEntries() {
        StreamId id1 = stream.addEntry(null, createFields("f1", "v1"));
        StreamId id2 = stream.addEntry(null, createFields("f2", "v2"));
        StreamId id3 = stream.addEntry(null, createFields("f3", "v3"));

        assertEquals(3L, stream.getLength());

        stream.deleteEntry(id2);
        assertEquals(2L, stream.getLength());
        assertNull(stream.getEntry(id2));
        assertNotNull(stream.getEntry(id1));
        assertNotNull(stream.getEntry(id3));
    }

    // ==================== 范围查询测试 ====================

    @Test
    public void testGetRangeEmpty() {
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, -1);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testGetRangeAll() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, -1);

        assertEquals(3, entries.size());
        assertEquals(new StreamId(1000, 0), entries.get(0).getId());
        assertEquals(new StreamId(2000, 0), entries.get(1).getId());
        assertEquals(new StreamId(3000, 0), entries.get(2).getId());
    }

    @Test
    public void testGetRangeWithCount() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, 2);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(1000, 0), entries.get(0).getId());
        assertEquals(new StreamId(2000, 0), entries.get(1).getId());
    }

    @Test
    public void testGetRangeClosedInterval() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));
        stream.addEntry(new StreamId(4000, 0), createFields("f4", "v4"));

        List<StreamEntry> entries = stream.getRange(
            new StreamId(2000, 0), new StreamId(3000, 0), false, false, -1);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(2000, 0), entries.get(0).getId());
        assertEquals(new StreamId(3000, 0), entries.get(1).getId());
    }

    @Test
    public void testGetRangeOpenInterval() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));
        stream.addEntry(new StreamId(4000, 0), createFields("f4", "v4"));

        // 开区间 (2000-0, 3000-0)
        List<StreamEntry> entries = stream.getRange(
            new StreamId(2000, 0), new StreamId(3000, 0), true, true, -1);

        assertTrue(entries.isEmpty());
    }

    @Test
    public void testGetRangeHalfOpenLeft() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        // (1000-0, 3000-0]
        List<StreamEntry> entries = stream.getRange(
            new StreamId(1000, 0), new StreamId(3000, 0), true, false, -1);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(2000, 0), entries.get(0).getId());
        assertEquals(new StreamId(3000, 0), entries.get(1).getId());
    }

    @Test
    public void testGetRangeHalfOpenRight() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        // [1000-0, 3000-0)
        List<StreamEntry> entries = stream.getRange(
            new StreamId(1000, 0), new StreamId(3000, 0), false, true, -1);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(1000, 0), entries.get(0).getId());
        assertEquals(new StreamId(2000, 0), entries.get(1).getId());
    }

    @Test
    public void testGetRangeNullStart() {
        stream.addEntry(null, createFields("f1", "v1"));
        List<StreamEntry> entries = stream.getRange(null, StreamId.MAX_ID, false, false, -1);
        assertTrue(entries.isEmpty());
    }

    @Test
    public void testGetRangeNullEnd() {
        stream.addEntry(null, createFields("f1", "v1"));
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, null, false, false, -1);
        assertTrue(entries.isEmpty());
    }

    // ==================== getRangeFrom 测试 ====================

    @Test
    public void testGetRangeFrom() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        List<StreamEntry> entries = stream.getRangeFrom(new StreamId(1000, 0), false, 10);

        assertEquals(3, entries.size());
    }

    @Test
    public void testGetRangeFromExclusive() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        List<StreamEntry> entries = stream.getRangeFrom(new StreamId(1000, 0), true, 10);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(2000, 0), entries.get(0).getId());
    }

    @Test
    public void testGetRangeFromWithCount() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        List<StreamEntry> entries = stream.getRangeFrom(new StreamId(1000, 0), false, 2);

        assertEquals(2, entries.size());
    }

    // ==================== getRangeFromReverse 测试 ====================

    @Test
    public void testGetRangeFromReverse() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        // getRangeFromReverse 使用 headMap，获取小于等于 start 的消息
        // exclusive=false 表示包含 start，所以返回 1000-0, 2000-0, 3000-0
        // 反转后顺序是 3000-0, 2000-0, 1000-0
        List<StreamEntry> entries = stream.getRangeFromReverse(new StreamId(3000, 0), false, 10);

        assertEquals(3, entries.size());
        assertEquals(new StreamId(3000, 0), entries.get(0).getId());
        assertEquals(new StreamId(2000, 0), entries.get(1).getId());
        assertEquals(new StreamId(1000, 0), entries.get(2).getId());
    }

    @Test
    public void testGetRangeFromReverseExclusive() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));
        stream.addEntry(new StreamId(3000, 0), createFields("f3", "v3"));

        // exclusive=true 表示不包含 start，只返回小于 3000-0 的消息
        // 反转后顺序是 2000-0, 1000-0
        List<StreamEntry> entries = stream.getRangeFromReverse(new StreamId(3000, 0), true, 10);

        assertEquals(2, entries.size());
        assertEquals(new StreamId(2000, 0), entries.get(0).getId());
        assertEquals(new StreamId(1000, 0), entries.get(1).getId());
    }

    // ==================== 裁剪测试（MAXLEN） ====================

    @Test
    public void testTrimMaxLen() {
        for (int i = 0; i < 10; i++) {
            stream.addEntry(new StreamId(1000 + i, 0), createFields("f" + i, "v" + i));
        }

        assertEquals(10L, stream.getLength());

        int trimmed = stream.trim(5);

        assertEquals(5, trimmed);
        assertEquals(5L, stream.getLength());

        // 验证保留的是最新的消息
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, -1);
        assertEquals(new StreamId(1005, 0), entries.get(0).getId());
        assertEquals(new StreamId(1009, 0), entries.get(4).getId());
    }

    @Test
    public void testTrimMaxLenNoChange() {
        for (int i = 0; i < 5; i++) {
            stream.addEntry(new StreamId(1000 + i, 0), createFields("f" + i, "v" + i));
        }

        int trimmed = stream.trim(10);

        assertEquals(0, trimmed);
        assertEquals(5L, stream.getLength());
    }

    @Test
    public void testTrimMaxLenZero() {
        stream.addEntry(null, createFields("f1", "v1"));

        int trimmed = stream.trim(0);

        assertEquals(0, trimmed);
    }

    @Test
    public void testTrimMaxLenNegative() {
        stream.addEntry(null, createFields("f1", "v1"));

        int trimmed = stream.trim(-1);

        assertEquals(0, trimmed);
    }

    // ==================== 裁剪测试（MINID） ====================

    @Test
    public void testTrimMinId() {
        for (int i = 0; i < 10; i++) {
            stream.addEntry(new StreamId(1000 + i * 100, 0), createFields("f" + i, "v" + i));
        }

        int trimmed = stream.trim(new StreamId(1300, 0));

        assertEquals(3, trimmed); // 删除 1000, 1100, 1200
        assertEquals(7L, stream.getLength());

        // 验证剩余消息
        List<StreamEntry> entries = stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, -1);
        assertEquals(new StreamId(1300, 0), entries.get(0).getId());
    }

    @Test
    public void testTrimMinIdNoChange() {
        stream.addEntry(new StreamId(2000, 0), createFields("f1", "v1"));

        int trimmed = stream.trim(new StreamId(1000, 0));

        assertEquals(0, trimmed);
        assertEquals(1L, stream.getLength());
    }

    @Test
    public void testTrimMinIdNull() {
        stream.addEntry(null, createFields("f1", "v1"));

        int trimmed = stream.trim((StreamId) null);

        assertEquals(0, trimmed);
    }

    // ==================== 自动裁剪测试 ====================

    @Test
    public void testAutoTrimOnAdd() {
        Stream streamWithMaxLen = new Stream(5);

        for (int i = 0; i < 10; i++) {
            streamWithMaxLen.addEntry(new StreamId(1000 + i, 0), createFields("f" + i, "v" + i));
        }

        assertEquals(5L, streamWithMaxLen.getLength());
    }

    // ==================== 时间回退处理测试 ====================

    @Test
    public void testTimeRegression() {
        // 先添加一个具有较大时间戳的消息
        StreamId id1 = new StreamId(System.currentTimeMillis() + 10000, 0);
        stream.addEntry(id1, createFields("f1", "v1"));

        // 然后自动生成 ID（时间戳会小于 id1）
        StreamId id2 = stream.addEntry(null, createFields("f2", "v2"));

        // 验证自动生成的 ID 大于手动指定的 ID
        assertTrue(id2.isGreaterThan(id1));
    }

    @Test
    public void testTimeRegressionSequenceIncrement() {
        // 设置一个较大的时间戳
        StreamId id1 = new StreamId(10000000000000L, 0);
        stream.addEntry(id1, createFields("f1", "v1"));

        // 自动生成 ID
        StreamId id2 = stream.addEntry(null, createFields("f2", "v2"));
        StreamId id3 = stream.addEntry(null, createFields("f3", "v3"));

        // 验证序号递增
        assertEquals(id1.getMillisecondsTime(), id2.getMillisecondsTime());
        assertEquals(1L, id2.getSequenceNumber());
        assertEquals(2L, id3.getSequenceNumber());
    }

    // ==================== 并发访问测试 ====================

    @Test
    public void testConcurrentAdd() throws InterruptedException {
        int threadCount = 10;
        int messagesPerThread = 100;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < messagesPerThread; j++) {
                        Map<String, String> fields = createFields("field", "value");
                        stream.addEntry(null, fields);
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(threadCount * messagesPerThread, successCount.get());
        assertEquals((long) threadCount * messagesPerThread, stream.getLength());
    }

    @Test
    public void testConcurrentReadWrite() throws InterruptedException {
        // 先添加一些消息
        for (int i = 0; i < 100; i++) {
            stream.addEntry(new StreamId(1000 + i, 0), createFields("f" + i, "v" + i));
        }

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        // 一半线程读，一半线程写
        for (int i = 0; i < threadCount; i++) {
            final boolean isWriter = i % 2 == 0;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < 100; j++) {
                        if (isWriter) {
                            stream.addEntry(null, createFields("f", "v"));
                        } else {
                            stream.getRange(StreamId.MIN_ID, StreamId.MAX_ID, false, false, 10);
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, errorCount.get());
    }

    // ==================== 其他方法测试 ====================

    @Test
    public void testGetEntry() {
        StreamId id = stream.addEntry(null, createFields("field1", "value1"));

        StreamEntry entry = stream.getEntry(id);

        assertNotNull(entry);
        assertEquals(id, entry.getId());
        assertEquals("value1", entry.getField("field1"));
    }

    @Test
    public void testGetEntryNonExistent() {
        StreamEntry entry = stream.getEntry(new StreamId(999, 999));
        assertNull(entry);
    }

    @Test
    public void testGetEntryNull() {
        StreamEntry entry = stream.getEntry(null);
        assertNull(entry);
    }

    @Test
    public void testGetFirstEntry() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));

        StreamEntry first = stream.getFirstEntry();

        assertNotNull(first);
        assertEquals(new StreamId(1000, 0), first.getId());
    }

    @Test
    public void testGetFirstEntryEmpty() {
        StreamEntry first = stream.getFirstEntry();
        assertNull(first);
    }

    @Test
    public void testGetLastEntry() {
        stream.addEntry(new StreamId(1000, 0), createFields("f1", "v1"));
        stream.addEntry(new StreamId(2000, 0), createFields("f2", "v2"));

        StreamEntry last = stream.getLastEntry();

        assertNotNull(last);
        assertEquals(new StreamId(2000, 0), last.getId());
    }

    @Test
    public void testGetLastEntryEmpty() {
        StreamEntry last = stream.getLastEntry();
        assertNull(last);
    }

    @Test
    public void testGetLastGeneratedId() {
        StreamId id = stream.addEntry(null, createFields("f1", "v1"));

        assertEquals(id, stream.getLastGeneratedId());
    }

    @Test
    public void testGetLastGeneratedIdEmpty() {
        assertNull(stream.getLastGeneratedId());
    }

    @Test
    public void testIsEmpty() {
        assertTrue(stream.isEmpty());

        stream.addEntry(null, createFields("f1", "v1"));

        assertFalse(stream.isEmpty());
    }

    @Test
    public void testClear() {
        stream.addEntry(null, createFields("f1", "v1"));
        stream.addEntry(null, createFields("f2", "v2"));

        assertEquals(2L, stream.getLength());

        stream.clear();

        assertEquals(0L, stream.getLength());
        assertTrue(stream.isEmpty());
        assertNull(stream.getLastGeneratedId());
    }

    @Test
    public void testSetMaxLen() {
        stream.setMaxLen(100);
        assertEquals(100L, stream.getMaxLen());
    }

    @Test
    public void testSetMaxLenNegative() {
        stream.setMaxLen(-1);
        assertEquals(0L, stream.getMaxLen());
    }

    @Test
    public void testEstimateMemorySize() {
        assertTrue(stream.estimateMemorySize() > 0);

        stream.addEntry(null, createFields("field1", "value1"));

        long size = stream.estimateMemorySize();
        assertTrue(size > 0);
    }

    @Test
    public void testToString() {
        stream.addEntry(null, createFields("f1", "v1"));
        String str = stream.toString();

        assertTrue(str.contains("length=1"));
    }

    // ==================== 辅助方法 ====================

    private Map<String, String> createFields(String key, String value) {
        Map<String, String> fields = new HashMap<>();
        fields.put(key, value);
        return fields;
    }
}
