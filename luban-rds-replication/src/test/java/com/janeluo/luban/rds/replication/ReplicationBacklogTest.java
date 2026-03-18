package com.janeluo.luban.rds.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 复制积压缓冲区测试
 */
@DisplayName("ReplicationBacklog Tests")
class ReplicationBacklogTest {
    
    private ReplicationBacklog backlog;
    
    @BeforeEach
    void setUp() {
        backlog = new ReplicationBacklog(1024);
    }
    
    @Test
    @DisplayName("测试初始化")
    void testInitialization() {
        assertNotNull(backlog.getReplId());
        assertEquals(40, backlog.getReplId().length());
        assertEquals(0, backlog.getMasterReplOffset());
        assertEquals(1024, backlog.getCapacity());
    }
    
    @Test
    @DisplayName("测试追加数据")
    void testAppend() {
        byte[] data = "test data".getBytes(StandardCharsets.UTF_8);
        long offset = backlog.append(data);
        
        assertEquals(data.length, offset);
        assertEquals(data.length, backlog.getMasterReplOffset());
        assertEquals(1, backlog.getWriteCount());
        assertEquals(data.length, backlog.getTotalBytesWritten());
    }
    
    @Test
    @DisplayName("测试追加字符串")
    void testAppendString() {
        String data = "test string";
        long offset = backlog.appendString(data);
        
        assertEquals(data.length(), offset);
        assertEquals(data.length(), backlog.getMasterReplOffset());
    }
    
    @Test
    @DisplayName("测试读取数据")
    void testRead() {
        byte[] data = "test data for read".getBytes(StandardCharsets.UTF_8);
        backlog.append(data);
        
        byte[] readData = backlog.read(0, data.length);
        
        assertNotNull(readData);
        assertEquals(data.length, readData.length);
        assertArrayEquals(data, readData);
        assertEquals(1, backlog.getReadCount());
    }
    
    @Test
    @DisplayName("测试读取超出范围的数据")
    void testReadOutOfRange() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        backlog.append(data);
        
        // 读取超出范围的偏移量
        byte[] readData = backlog.read(100, 10);
        assertNull(readData);
        
        // 读取负偏移量
        readData = backlog.read(-1, 10);
        assertNull(readData);
    }
    
    @Test
    @DisplayName("测试部分同步检查")
    void testCanPartialSync() {
        byte[] data = "test data for partial sync".getBytes(StandardCharsets.UTF_8);
        backlog.append(data);
        
        String replId = backlog.getReplId();
        
        // 有效的部分同步
        assertTrue(backlog.canPartialSync(replId, 0));
        assertTrue(backlog.canPartialSync(replId, data.length / 2));
        
        // 无效的复制 ID
        assertFalse(backlog.canPartialSync("invalid-repl-id", 0));
        
        // 超出范围的偏移量
        assertFalse(backlog.canPartialSync(replId, data.length + 1000));
    }
    
    @Test
    @DisplayName("测试获取积压数据")
    void testGetBacklogData() {
        byte[] data1 = "data1".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "data2".getBytes(StandardCharsets.UTF_8);
        
        backlog.append(data1);
        backlog.append(data2);
        
        byte[] backlogData = backlog.getBacklogData(data1.length);
        
        assertNotNull(backlogData);
        assertEquals(data2.length, backlogData.length);
        assertArrayEquals(data2, backlogData);
    }
    
    @Test
    @DisplayName("测试重置复制 ID")
    void testResetReplId() {
        String oldReplId = backlog.getReplId();
        
        backlog.append("test".getBytes(StandardCharsets.UTF_8));
        backlog.resetReplId();
        
        String newReplId = backlog.getReplId();
        
        assertNotEquals(oldReplId, newReplId);
        assertEquals(oldReplId, backlog.getReplId2());
    }
    
    @Test
    @DisplayName("测试环形缓冲区覆盖")
    void testCircularBuffer() {
        // 创建小容量的缓冲区
        ReplicationBacklog smallBacklog = new ReplicationBacklog(10);
        
        // 写入超过容量的数据
        byte[] data1 = "12345".getBytes(StandardCharsets.UTF_8);
        byte[] data2 = "67890".getBytes(StandardCharsets.UTF_8);
        byte[] data3 = "abcdef".getBytes(StandardCharsets.UTF_8);
        
        smallBacklog.append(data1);
        smallBacklog.append(data2);
        smallBacklog.append(data3);
        
        // 总共写入了 16 字节，但容量只有 10
        assertEquals(16, smallBacklog.getMasterReplOffset());
        assertEquals(10, smallBacklog.getBacklogSize());
    }
    
    @Test
    @DisplayName("测试统计信息")
    void testStatistics() {
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        
        // 多次写入
        for (int i = 0; i < 10; i++) {
            backlog.append(data);
        }
        
        // 多次读取
        for (int i = 0; i < 5; i++) {
            backlog.read(i * data.length, data.length);
        }
        
        assertEquals(10, backlog.getWriteCount());
        assertEquals(5, backlog.getReadCount());
        assertEquals(10 * data.length, backlog.getTotalBytesWritten());
        assertEquals(5 * data.length, backlog.getTotalBytesRead());
        
        // 部分同步统计
        String replId = backlog.getReplId();
        backlog.canPartialSync(replId, 0); // 命中
        backlog.canPartialSync("invalid", 0); // 未命中
        
        assertEquals(1, backlog.getPartialSyncHitCount());
        assertEquals(1, backlog.getPartialSyncMissCount());
        assertEquals(50.0, backlog.getPartialSyncHitRate(), 0.1);
    }
    
    @Test
    @DisplayName("测试清空缓冲区")
    void testClear() {
        backlog.append("test".getBytes(StandardCharsets.UTF_8));
        
        backlog.clear();
        
        assertEquals(0, backlog.getMasterReplOffset());
        assertEquals(0, backlog.getBacklogSize());
    }
    
    @Test
    @DisplayName("测试重置统计信息")
    void testResetStats() {
        backlog.append("test".getBytes(StandardCharsets.UTF_8));
        backlog.read(0, 4);
        
        backlog.resetStats();
        
        assertEquals(0, backlog.getWriteCount());
        assertEquals(0, backlog.getReadCount());
        assertEquals(0, backlog.getTotalBytesWritten());
        assertEquals(0, backlog.getTotalBytesRead());
    }
    
    @Test
    @DisplayName("测试 INFO 输出")
    void testGetInfo() {
        backlog.append("test".getBytes(StandardCharsets.UTF_8));
        
        String info = backlog.getInfo();
        
        assertNotNull(info);
        assertTrue(info.contains("repl_backlog_active:1"));
        assertTrue(info.contains("repl_backlog_size:1024"));
    }
    
    @Test
    @DisplayName("测试统计信息输出")
    void testGetStatsInfo() {
        backlog.append("test".getBytes(StandardCharsets.UTF_8));
        
        String stats = backlog.getStatsInfo();
        
        assertNotNull(stats);
        assertTrue(stats.contains("backlog_write_count:"));
        assertTrue(stats.contains("backlog_read_count:"));
        assertTrue(stats.contains("backlog_partial_sync_hits:"));
    }
    
    @Test
    @DisplayName("测试并发写入")
    void testConcurrentWrite() throws InterruptedException {
        int threadCount = 10;
        int writesPerThread = 100;
        
        Thread[] threads = new Thread[threadCount];
        
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < writesPerThread; j++) {
                    backlog.append("x".getBytes(StandardCharsets.UTF_8));
                }
            });
        }
        
        for (Thread thread : threads) {
            thread.start();
        }
        
        for (Thread thread : threads) {
            thread.join();
        }
        
        assertEquals(threadCount * writesPerThread, backlog.getMasterReplOffset());
        assertEquals(threadCount * writesPerThread, backlog.getWriteCount());
    }
}
