package com.janeluo.luban.rds.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 复制积压缓冲区
 * 
 * 参考 Redis repl_backlog 实现：
 * - 环形缓冲区，用于存储最近的写命令
 * - 支持部分重同步时获取增量数据
 * - 线程安全
 */
public class ReplicationBacklog {
    
    private static final Logger logger = LoggerFactory.getLogger(ReplicationBacklog.class);
    
    private final int capacity;
    private final byte[] buffer;
    private int writePos = 0;
    private final AtomicLong masterReplOffset = new AtomicLong(0);
    private String replId;
    private String replId2;
    private long replId2Offset = 0;
    private final long createTime;
    private volatile long lastWriteTime;
    
    public ReplicationBacklog(int capacity) {
        this.capacity = capacity;
        this.buffer = new byte[capacity];
        this.createTime = System.currentTimeMillis();
        this.lastWriteTime = createTime;
        this.replId = generateReplId();
        logger.info("Replication backlog created, capacity: {} bytes, replid: {}", capacity, replId);
    }
    
    public synchronized long append(byte[] data) {
        if (data == null || data.length == 0) {
            return masterReplOffset.get();
        }
        
        int remaining = data.length;
        int offset = 0;
        
        while (remaining > 0) {
            int toWrite = Math.min(remaining, capacity - writePos);
            System.arraycopy(data, offset, buffer, writePos, toWrite);
            writePos = (writePos + toWrite) % capacity;
            offset += toWrite;
            remaining -= toWrite;
        }
        
        long newOffset = masterReplOffset.addAndGet(data.length);
        lastWriteTime = System.currentTimeMillis();
        logger.debug("Appended {} bytes to backlog, new offset: {}", data.length, newOffset);
        return newOffset;
    }
    
    public long appendString(String data) {
        return append(data.getBytes(StandardCharsets.UTF_8));
    }
    
    public synchronized byte[] read(long startOffset, int length) {
        long currentOffset = masterReplOffset.get();
        long oldestOffset = Math.max(0, currentOffset - capacity);
        
        if (startOffset < oldestOffset || startOffset >= currentOffset) {
            logger.warn("Invalid read offset: {}, valid range: [{}, {})", startOffset, oldestOffset, currentOffset);
            return null;
        }
        
        if (length <= 0) {
            return new byte[0];
        }
        
        int readableLength = (int) Math.min(length, currentOffset - startOffset);
        byte[] result = new byte[readableLength];
        
        int bufferOffset = (int) ((startOffset % capacity + capacity) % capacity);
        int remaining = readableLength;
        int resultPos = 0;
        
        while (remaining > 0) {
            int toRead = Math.min(remaining, capacity - bufferOffset);
            System.arraycopy(buffer, bufferOffset, result, resultPos, toRead);
            bufferOffset = (bufferOffset + toRead) % capacity;
            resultPos += toRead;
            remaining -= toRead;
        }
        
        return result;
    }
    
    public boolean canPartialSync(String requestReplId, long requestOffset) {
        if (requestReplId == null || requestReplId.isEmpty()) {
            return false;
        }
        
        if (!requestReplId.equals(replId) && !requestReplId.equals(replId2)) {
            logger.debug("Replid mismatch: request={}, current={}, second={}", requestReplId, replId, replId2);
            return false;
        }
        
        long currentOffset = masterReplOffset.get();
        long oldestOffset = Math.max(0, currentOffset - capacity);
        
        if (requestOffset < oldestOffset || requestOffset > currentOffset) {
            logger.debug("Offset out of range: request={}, valid range: [{}, {}]", requestOffset, oldestOffset, currentOffset);
            return false;
        }
        
        return true;
    }
    
    public byte[] getBacklogData(long startOffset) {
        long currentOffset = masterReplOffset.get();
        int length = (int) (currentOffset - startOffset);
        if (length <= 0) {
            return new byte[0];
        }
        return read(startOffset, length);
    }
    
    public synchronized void resetReplId() {
        this.replId2 = this.replId;
        this.replId2Offset = masterReplOffset.get();
        this.replId = generateReplId();
        logger.info("Replid reset: new={}, old={}, oldOffset={}", replId, replId2, replId2Offset);
    }
    
    private String generateReplId() {
        long timestamp = System.currentTimeMillis();
        long random = (long) (Math.random() * Long.MAX_VALUE);
        ByteBuffer buffer = ByteBuffer.allocate(16);
        buffer.putLong(timestamp);
        buffer.putLong(random);
        
        StringBuilder sb = new StringBuilder(40);
        for (byte b : buffer.array()) {
            sb.append(String.format("%02x", b));
        }
        while (sb.length() < 40) {
            sb.append('0');
        }
        return sb.substring(0, 40);
    }
    
    public String getReplId() { return replId; }
    public String getReplId2() { return replId2; }
    public long getReplId2Offset() { return replId2Offset; }
    public long getMasterReplOffset() { return masterReplOffset.get(); }
    public int getCapacity() { return capacity; }
    public long getCreateTime() { return createTime; }
    public long getLastWriteTime() { return lastWriteTime; }
    
    public long getBacklogSize() {
        return Math.min(masterReplOffset.get(), capacity);
    }
    
    public String getInfo() {
        return String.format(
            "repl_backlog_active:1\r\n" +
            "repl_backlog_size:%d\r\n" +
            "repl_backlog_first_byte_offset:%d\r\n" +
            "repl_backlog_histlen:%d\r\n",
            capacity,
            Math.max(1, masterReplOffset.get() - getBacklogSize() + 1),
            getBacklogSize()
        );
    }
}
