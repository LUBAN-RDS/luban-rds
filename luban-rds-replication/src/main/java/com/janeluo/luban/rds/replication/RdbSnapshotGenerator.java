package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDB 快照生成器
 * 
 * 用于主从复制时生成 RDB 快照并传输给从节点
 * 支持流式传输和进度监控
 */
public class RdbSnapshotGenerator {
    
    private static final Logger logger = LoggerFactory.getLogger(RdbSnapshotGenerator.class);
    
    /**
     * 传输块大小（64KB）
     */
    private static final int CHUNK_SIZE = 64 * 1024;
    
    /**
     * RDB 持久化服务
     */
    private final RdbPersistService rdbPersistService;
    
    /**
     * 数据目录
     */
    private final String dataDir;
    
    /**
     * 是否正在生成快照
     */
    private final AtomicBoolean isGenerating = new AtomicBoolean(false);
    
    /**
     * 传输统计
     */
    private final AtomicLong totalBytesTransferred = new AtomicLong(0);
    private final AtomicLong lastTransferTime = new AtomicLong(0);
    
    /**
     * 构造函数
     * 
     * @param rdbPersistService RDB 持久化服务
     * @param dataDir 数据目录
     */
    public RdbSnapshotGenerator(RdbPersistService rdbPersistService, String dataDir) {
        this.rdbPersistService = rdbPersistService;
        this.dataDir = dataDir != null ? dataDir : System.getProperty("java.io.tmpdir");
    }
    
    /**
     * 生成 RDB 快照并传输给从节点
     * 
     * @param memoryStore 内存存储
     * @param channel 从节点通道
     * @param progressMonitor 进度监控器（可选）
     * @return 传输的字节数
     */
    public long generateAndTransfer(MemoryStore memoryStore, Channel channel, 
                                    TransferProgressMonitor progressMonitor) {
        if (!isGenerating.compareAndSet(false, true)) {
            logger.warn("RDB snapshot generation is already in progress");
            return -1;
        }
        
        try {
            long startTime = System.currentTimeMillis();
            totalBytesTransferred.set(0);
            
            // 生成临时 RDB 文件
            File tempRdbFile = generateTempRdbFile(memoryStore);
            if (tempRdbFile == null || !tempRdbFile.exists()) {
                logger.error("Failed to generate RDB file");
                return -1;
            }
            
            long fileSize = tempRdbFile.length();
            logger.info("RDB snapshot generated, size: {} bytes, starting transfer...", fileSize);
            
            // 传输 RDB 文件
            long transferredBytes = transferRdbFile(tempRdbFile, channel, progressMonitor);
            
            // 删除临时文件
            deleteTempFile(tempRdbFile);
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("RDB transfer completed: {} bytes in {} ms, speed: {} KB/s",
                       transferredBytes, duration,
                       duration > 0 ? (transferredBytes / 1024.0 / duration * 1000) : 0);
            
            return transferredBytes;
            
        } catch (Exception e) {
            logger.error("Error during RDB snapshot generation and transfer", e);
            return -1;
        } finally {
            isGenerating.set(false);
        }
    }
    
    /**
     * 生成临时 RDB 文件
     */
    private File generateTempRdbFile(MemoryStore memoryStore) {
        try {
            // 使用 RdbPersistService 的同步持久化方法
            rdbPersistService.persistSync(memoryStore);
            
            // 获取生成的 RDB 文件
            File rdbFile = new File(dataDir, "dump.rdb");
            if (rdbFile.exists()) {
                // 创建临时副本，避免影响原文件
                File tempFile = new File(dataDir, 
                                        "temp-replication-" + System.currentTimeMillis() + ".rdb");
                copyFile(rdbFile, tempFile);
                return tempFile;
            }
            
            return null;
        } catch (Exception e) {
            logger.error("Failed to generate temp RDB file", e);
            return null;
        }
    }
    
    /**
     * 复制文件
     */
    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target);
             BufferedInputStream bis = new BufferedInputStream(fis, CHUNK_SIZE);
             BufferedOutputStream bos = new BufferedOutputStream(fos, CHUNK_SIZE)) {
            
            byte[] buffer = new byte[CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
    }
    
    /**
     * 流式传输 RDB 文件
     */
    private long transferRdbFile(File rdbFile, Channel channel, 
                                 TransferProgressMonitor progressMonitor) {
        long totalBytes = 0;
        long fileSize = rdbFile.length();
        long startTime = System.currentTimeMillis();
        
        try (FileInputStream fis = new FileInputStream(rdbFile);
             BufferedInputStream bis = new BufferedInputStream(fis, CHUNK_SIZE)) {
            
            // 发送 RDB 文件头（$<length>\r\n）
            String header = "$" + fileSize + "\r\n";
            ByteBuf headerBuf = Unpooled.copiedBuffer(header.getBytes());
            channel.writeAndFlush(headerBuf);
            
            // 分块传输 RDB 数据
            byte[] chunk = new byte[CHUNK_SIZE];
            int bytesRead;
            int chunkCount = 0;
            
            while ((bytesRead = bis.read(chunk)) != -1) {
                if (!channel.isActive()) {
                    logger.warn("Channel closed during RDB transfer");
                    break;
                }
                
                ByteBuf dataBuf = Unpooled.wrappedBuffer(chunk, 0, bytesRead);
                channel.writeAndFlush(dataBuf);
                
                totalBytes += bytesRead;
                totalBytesTransferred.set(totalBytes);
                lastTransferTime.set(System.currentTimeMillis());
                chunkCount++;
                
                // 通知进度监控器
                if (progressMonitor != null) {
                    progressMonitor.onProgress(totalBytes, fileSize, chunkCount);
                }
                
                // 每 100 个块记录一次日志
                if (chunkCount % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double speed = elapsed > 0 ? (totalBytes / 1024.0 / elapsed * 1000) : 0;
                    logger.debug("RDB transfer progress: {}/{} bytes ({}%), speed: {} KB/s",
                                totalBytes, fileSize,
                                String.format("%.1f", totalBytes * 100.0 / fileSize),
                                String.format("%.1f", speed));
                }
            }
            
            // 发送结束标记
            String endMarker = "\r\n";
            ByteBuf endBuf = Unpooled.copiedBuffer(endMarker.getBytes());
            channel.writeAndFlush(endBuf);
            
            if (progressMonitor != null) {
                progressMonitor.onComplete(totalBytes, fileSize);
            }
            
        } catch (Exception e) {
            logger.error("Error during RDB file transfer", e);
            if (progressMonitor != null) {
                progressMonitor.onError(e.getMessage());
            }
        }
        
        return totalBytes;
    }
    
    /**
     * 删除临时文件
     */
    private void deleteTempFile(File file) {
        if (file != null && file.exists()) {
            try {
                if (file.delete()) {
                    logger.debug("Deleted temp RDB file: {}", file.getAbsolutePath());
                } else {
                    logger.warn("Failed to delete temp RDB file: {}", file.getAbsolutePath());
                }
            } catch (Exception e) {
                logger.warn("Error deleting temp RDB file", e);
            }
        }
    }
    
    /**
     * 是否正在生成快照
     */
    public boolean isGenerating() {
        return isGenerating.get();
    }
    
    /**
     * 获取已传输字节数
     */
    public long getTotalBytesTransferred() {
        return totalBytesTransferred.get();
    }
    
    /**
     * 获取上次传输时间
     */
    public long getLastTransferTime() {
        return lastTransferTime.get();
    }
    
    /**
     * 获取数据目录
     */
    public String getDataDir() {
        return dataDir;
    }
}
