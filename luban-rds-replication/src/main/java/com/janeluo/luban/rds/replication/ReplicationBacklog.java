package com.janeluo.luban.rds.replication;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 复制积压缓冲区
 * 
 * 参考 Redis repl_backlog 实现：
 * - 环形缓冲区，用于存储最近的写命令
 * - 支持部分重同步时获取增量数据
 * - 线程安全，使用读写锁优化并发性能
 * - 支持统计信息收集
 * 
 * 优化点：
 * 1. 使用读写锁替代 synchronized，提高并发性能
 * 2. 使用 ByteBuffer 优化内存访问
 * 3. 添加统计信息（写入/读取次数、命中率等）
 * 4. 优化内存使用，避免不必要的数组复制
 */
public class ReplicationBacklog {
    
    private static final Logger logger = LoggerFactory.getLogger(ReplicationBacklog.class);
    
    private final int capacity;
    private final byte[] buffer;
    private volatile int writePos = 0;
    private final AtomicLong masterReplOffset = new AtomicLong(0);
    private String replId;
    private String replId2;
    private long replId2Offset = 0;
    private final long createTime;
    private volatile long lastWriteTime;
    
    // 读写锁，优化并发性能
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock readLock = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    
    // 统计信息
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong readCount = new AtomicLong(0);
    private final AtomicLong partialSyncHitCount = new AtomicLong(0);
    private final AtomicLong partialSyncMissCount = new AtomicLong(0);
    private final AtomicLong totalBytesWritten = new AtomicLong(0);
    private final AtomicLong totalBytesRead = new AtomicLong(0);
    
    public ReplicationBacklog(int capacity) {
        this.capacity = capacity;
        this.buffer = new byte[capacity];
        this.createTime = System.currentTimeMillis();
        this.lastWriteTime = createTime;
        this.replId = generateReplId();
        logger.info("Replication backlog created, capacity: {} bytes, replid: {}", capacity, replId);
    }
    
    /**
     * 追加数据到缓冲区
     * 
     * @param data 要追加的数据
     * @return 新的主节点偏移量
     */
    public long append(byte[] data) {
        if (data == null || data.length == 0) {
            return masterReplOffset.get();
        }
        
        writeLock.lock();
        try {
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
            
            // 更新统计
            writeCount.incrementAndGet();
            totalBytesWritten.addAndGet(data.length);
            
            logger.debug("Appended {} bytes to backlog, new offset: {}", data.length, newOffset);
            return newOffset;
            
        } finally {
            writeLock.unlock();
        }
    }
    
    public long appendString(String data) {
        return append(data.getBytes(StandardCharsets.UTF_8));
    }
    
    /**
     * 从缓冲区读取数据
     * 
     * @param startOffset 起始偏移量
     * @param length 要读取的长度
     * @return 读取的数据，如果偏移量无效则返回 null
     */
    public byte[] read(long startOffset, int length) {
        readLock.lock();
        try {
            long currentOffset = masterReplOffset.get();
            long oldestOffset = Math.max(0, currentOffset - capacity);
            
            if (startOffset < oldestOffset || startOffset >= currentOffset) {
                logger.warn("Invalid read offset: {}, valid range: [{}, {})", 
                           startOffset, oldestOffset, currentOffset);
                partialSyncMissCount.incrementAndGet();
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
            
            // 更新统计
            readCount.incrementAndGet();
            totalBytesRead.addAndGet(readableLength);
            
            return result;
            
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * 检查是否可以进行部分同步
     * 
     * @param requestReplId 请求的复制 ID
     * @param requestOffset 请求的偏移量
     * @return 是否可以进行部分同步
     */
    public boolean canPartialSync(String requestReplId, long requestOffset) {
        if (requestReplId == null || requestReplId.isEmpty()) {
            partialSyncMissCount.incrementAndGet();
            return false;
        }
        
        readLock.lock();
        try {
            if (!requestReplId.equals(replId) && !requestReplId.equals(replId2)) {
                logger.debug("Replid mismatch: request={}, current={}, second={}", 
                            requestReplId, replId, replId2);
                partialSyncMissCount.incrementAndGet();
                return false;
            }
            
            long currentOffset = masterReplOffset.get();
            long oldestOffset = Math.max(0, currentOffset - capacity);
            
            if (requestOffset < oldestOffset || requestOffset > currentOffset) {
                logger.debug("Offset out of range: request={}, valid range: [{}, {}]", 
                            requestOffset, oldestOffset, currentOffset);
                partialSyncMissCount.incrementAndGet();
                return false;
            }
            
            partialSyncHitCount.incrementAndGet();
            return true;
            
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * 获取积压缓冲区数据
     * 
     * @param startOffset 起始偏移量
     * @return 数据字节数组
     */
    public byte[] getBacklogData(long startOffset) {
        readLock.lock();
        try {
            long currentOffset = masterReplOffset.get();
            int length = (int) (currentOffset - startOffset);
            if (length <= 0) {
                return new byte[0];
            }
            return read(startOffset, length);
        } finally {
            readLock.unlock();
        }
    }
    
    /**
     * 重置复制 ID
     */
    public void resetReplId() {
        writeLock.lock();
        try {
            this.replId2 = this.replId;
            this.replId2Offset = masterReplOffset.get();
            this.replId = generateReplId();
            logger.info("Replid reset: new={}, old={}, oldOffset={}", replId, replId2, replId2Offset);
        } finally {
            writeLock.unlock();
        }
    }
    
    /**
     * 生成复制 ID
     */
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
    
    // ==================== 基本信息 ====================
    
    public String getReplId() { 
        readLock.lock();
        try {
            return replId; 
        } finally {
            readLock.unlock();
        }
    }
    
    public String getReplId2() { 
        readLock.lock();
        try {
            return replId2; 
        } finally {
            readLock.unlock();
        }
    }
    
    public long getReplId2Offset() { 
        readLock.lock();
        try {
            return replId2Offset; 
        } finally {
            readLock.unlock();
        }
    }
    
    public long getMasterReplOffset() { 
        return masterReplOffset.get(); 
    }
    
    public int getCapacity() { 
        return capacity; 
    }
    
    public long getCreateTime() { 
        return createTime; 
    }
    
    public long getLastWriteTime() { 
        return lastWriteTime; 
    }
    
    public long getBacklogSize() {
        return Math.min(masterReplOffset.get(), capacity);
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取写入次数
     */
    public long getWriteCount() {
        return writeCount.get();
    }
    
    /**
     * 获取读取次数
     */
    public long getReadCount() {
        return readCount.get();
    }
    
    /**
     * 获取部分同步命中次数
     */
    public long getPartialSyncHitCount() {
        return partialSyncHitCount.get();
    }
    
    /**
     * 获取部分同步未命中次数
     */
    public long getPartialSyncMissCount() {
        return partialSyncMissCount.get();
    }
    
    /**
     * 获取部分同步命中率
     */
    public double getPartialSyncHitRate() {
        long hits = partialSyncHitCount.get();
        long misses = partialSyncMissCount.get();
        long total = hits + misses;
        if (total == 0) {
            return 0;
        }
        return (double) hits / total * 100;
    }
    
    /**
     * 获取总写入字节数
     */
    public long getTotalBytesWritten() {
        return totalBytesWritten.get();
    }
    
    /**
     * 获取总读取字节数
     */
    public long getTotalBytesRead() {
        return totalBytesRead.get();
    }
    
    /**
     * 获取缓冲区使用率
     */
    public double getUsageRate() {
        long offset = masterReplOffset.get();
        if (offset == 0) {
            return 0;
        }
        return (double) getBacklogSize() / capacity * 100;
    }
    
    /**
     * 获取统计信息字符串
     */
    public String getStatsInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("backlog_write_count:").append(writeCount.get()).append("\r\n");
        sb.append("backlog_read_count:").append(readCount.get()).append("\r\n");
        sb.append("backlog_partial_sync_hits:").append(partialSyncHitCount.get()).append("\r\n");
        sb.append("backlog_partial_sync_misses:").append(partialSyncMissCount.get()).append("\r\n");
        sb.append("backlog_partial_sync_hit_rate:").append(String.format("%.2f%%", getPartialSyncHitRate())).append("\r\n");
        sb.append("backlog_total_bytes_written:").append(totalBytesWritten.get()).append("\r\n");
        sb.append("backlog_total_bytes_read:").append(totalBytesRead.get()).append("\r\n");
        sb.append("backlog_usage_rate:").append(String.format("%.2f%%", getUsageRate())).append("\r\n");
        return sb.toString();
    }
    
    /**
     * 重置统计信息
     */
    public void resetStats() {
        writeCount.set(0);
        readCount.set(0);
        partialSyncHitCount.set(0);
        partialSyncMissCount.set(0);
        totalBytesWritten.set(0);
        totalBytesRead.set(0);
    }
    
    /**
     * 获取 INFO 输出
     */
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
    
    /**
     * 清空缓冲区
     */
    public void clear() {
        writeLock.lock();
        try {
            writePos = 0;
            masterReplOffset.set(0);
            // 不清空 replId，保持复制 ID 不变
            logger.info("Backlog cleared");
        } finally {
            writeLock.unlock();
        }
    }
}
