package com.janeluo.luban.rds.replication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReplicationBacklogTest {

    private ReplicationBacklog backlog;

    @BeforeEach
    void setUp() {
        backlog = new ReplicationBacklog(1024);
    }

    @Test
    @DisplayName("测试初始化")
    void testInit() {
        assertNotNull(backlog.getReplId());
        assertEquals(40, backlog.getReplId().length());
        assertEquals(0, backlog.getMasterReplOffset());
        assertEquals(1024, backlog.getCapacity());
        assertTrue(backlog.getCreateTime() > 0);
        assertTrue(backlog.getLastWriteTime() > 0);
    }

    @Test
    @DisplayName("测试追加数据")
    void testAppend() {
        byte[] data = "SET key value".getBytes();
        long offset = backlog.append(data);
        
        assertEquals(data.length, offset);
        assertEquals(data.length, backlog.getMasterReplOffset());
    }

    @Test
    @DisplayName("测试追加空数据")
    void testAppendNull() {
        long offset1 = backlog.append(null);
        assertEquals(0, offset1);
        
        long offset2 = backlog.append(new byte[0]);
        assertEquals(0, offset2);
    }

    @Test
    @DisplayName("测试追加字符串")
    void testAppendString() {
        long offset = backlog.appendString("SET key value");
        assertEquals(13, offset);
    }

    @Test
    @DisplayName("测试环形缓冲区写入")
    void testCircularBuffer() {
        ReplicationBacklog smallBacklog = new ReplicationBacklog(100);
        
        byte[] data1 = new byte[60];
        byte[] data2 = new byte[60];
        
        smallBacklog.append(data1);
        smallBacklog.append(data2);
        
        assertEquals(120, smallBacklog.getMasterReplOffset());
        assertEquals(100, smallBacklog.getBacklogSize());
    }

    @Test
    @DisplayName("测试读取数据")
    void testRead() {
        byte[] data = "Hello World".getBytes();
        backlog.append(data);
        
        byte[] read = backlog.read(0, data.length);
        assertArrayEquals(data, read);
    }

    @Test
    @DisplayName("测试读取空数据")
    void testReadEmpty() {
        byte[] data = "test".getBytes();
        backlog.append(data);
        byte[] read = backlog.read(0, 0);
        assertEquals(0, read.length);
    }

    @Test
    @DisplayName("测试读取无效偏移量")
    void testReadInvalidOffset() {
        backlog.append("test".getBytes());
        
        assertNull(backlog.read(100, 10));
        assertNull(backlog.read(-1, 10));
    }

    @Test
    @DisplayName("测试部分重同步判断")
    void testCanPartialSync() {
        String replId = backlog.getReplId();
        
        assertTrue(backlog.canPartialSync(replId, 0));
        
        byte[] data = "test data".getBytes();
        backlog.append(data);
        
        assertTrue(backlog.canPartialSync(replId, 0));
        assertTrue(backlog.canPartialSync(replId, data.length));
    }

    @Test
    @DisplayName("测试部分重同步无效ID")
    void testCanPartialSyncInvalidId() {
        assertFalse(backlog.canPartialSync(null, 0));
        assertFalse(backlog.canPartialSync("", 0));
        assertFalse(backlog.canPartialSync("invalid-id", 0));
    }

    @Test
    @DisplayName("测试部分重同步偏移量超出范围")
    void testCanPartialSyncOffsetOutOfRange() {
        String replId = backlog.getReplId();
        
        backlog.append("test".getBytes());
        
        assertFalse(backlog.canPartialSync(replId, 1000));
        assertFalse(backlog.canPartialSync(replId, -1));
    }

    @Test
    @DisplayName("测试获取积压数据")
    void testGetBacklogData() {
        byte[] data = "test data".getBytes();
        backlog.append(data);
        
        byte[] result = backlog.getBacklogData(0);
        assertArrayEquals(data, result);
        
        byte[] empty = backlog.getBacklogData(100);
        assertEquals(0, empty.length);
    }

    @Test
    @DisplayName("测试重置复制ID")
    void testResetReplId() {
        String oldReplId = backlog.getReplId();
        
        backlog.append("test".getBytes());
        backlog.resetReplId();
        
        String newReplId = backlog.getReplId();
        assertNotEquals(oldReplId, newReplId);
        assertEquals(oldReplId, backlog.getReplId2());
        assertEquals(4, backlog.getReplId2Offset());
    }

    @Test
    @DisplayName("测试获取信息")
    void testGetInfo() {
        String info = backlog.getInfo();
        
        assertTrue(info.contains("repl_backlog_active:1"));
        assertTrue(info.contains("repl_backlog_size:"));
        assertTrue(info.contains("repl_backlog_first_byte_offset:"));
        assertTrue(info.contains("repl_backlog_histlen:"));
    }

    @Test
    @DisplayName("测试写入时间更新")
    void testLastWriteTime() throws InterruptedException {
        long initialTime = backlog.getLastWriteTime();
        Thread.sleep(10);
        
        backlog.append("test".getBytes());
        
        assertTrue(backlog.getLastWriteTime() > initialTime);
    }
}