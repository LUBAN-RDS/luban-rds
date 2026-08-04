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
        return generateAndTransfer(memoryStore, channel, progressMonitor, null).getTransferredBytes();
    }
    
    /**
     * 生成 RDB 快照并传输给从节点
     * 
     * <p>当提供 {@code backlog} 时，会在 RDB 文件落盘（{@code persistSync} 完成）之后、
     * 传输之前，记录 {@code backlog.getMasterReplOffset()} 作为快照偏移量，
     * 供主节点在全量同步完成后重放窗口期命令使用。
     * 
     * @param memoryStore 内存存储
     * @param channel 从节点通道
     * @param progressMonitor 进度监控器（可选）
     * @param backlog 复制积压缓冲区（可选，用于记录快照偏移量）
     * @return 快照结果，包含传输字节数与快照偏移量
     */
    public SnapshotResult generateAndTransfer(MemoryStore memoryStore, Channel channel,
                                              TransferProgressMonitor progressMonitor,
                                              ReplicationBacklog backlog) {
        if (!isGenerating.compareAndSet(false, true)) {
            logger.warn("RDB snapshot generation is already in progress");
            return SnapshotResult.failure();
        }
        
        try {
            long startTime = System.currentTimeMillis();
            totalBytesTransferred.set(0);
            
            // 生成临时 RDB 文件（persistSync 完成后落盘）
            File tempRdbFile = generateTempRdbFile(memoryStore);
            if (tempRdbFile == null || !tempRdbFile.exists()) {
                logger.error("Failed to generate RDB file");
                return SnapshotResult.failure();
            }
            
            // 在 RDB 文件落盘后记录 backlog 偏移量，
            // 此后窗口期写入的命令都会进入 backlog，可被主节点重放给从节点
            long snapshotOffset = backlog != null ? backlog.getMasterReplOffset() : -1;
            
            long fileSize = tempRdbFile.length();
            logger.info("RDB snapshot generated, size: {} bytes, snapshotOffset: {}, starting transfer...",
                       fileSize, snapshotOffset);
            
            // 传输 RDB 文件
            long transferredBytes = transferRdbFile(tempRdbFile, channel, progressMonitor);
            
            // 删除临时文件
            deleteTempFile(tempRdbFile);
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("RDB transfer completed: {} bytes in {} ms, speed: {} KB/s",
                       transferredBytes, duration,
                       duration > 0 ? (transferredBytes / 1024.0 / duration * 1000) : 0);
            
            return new SnapshotResult(transferredBytes, snapshotOffset);
            
        } catch (Exception e) {
            logger.error("Error during RDB snapshot generation and transfer", e);
            return SnapshotResult.failure();
        } finally {
            isGenerating.set(false);
        }
    }
    
    /**
     * 生成临时 RDB 文件。
     *
     * <p>实现：调 {@link RdbPersistService#persistSync(MemoryStore)} 把 {@code memoryStore}
     * 落盘到 {@code dump.rdb}，再复制为带时间戳的临时副本并返回。原始 {@code dump.rdb} 保持不变
     * （复制而非移动），避免影响既有复制传输路径。</p>
     *
     * <p><b>阶段 10 可见性提升（DESIGN §5.4 / IMPLEMENTATION_PLAN 阶段 10.2）</b>：
     * 本方法原为 {@code private}（仅供 {@link #generateAndTransfer} 内部使用）。
     * chunked INSTALL_SNAPSHOT 需要"先把快照落盘为文件、再按 4MB 切片读取字节"，
     * 故把可见性提为 {@code public}，供 {@code SnapshotManager} 复用既有落盘路径，
     * 避免新增并行的 generate-to-bytes API（DESIGN Open Question 选定方案 A：复用现有落盘）。</p>
     *
     * <p>调用方职责：使用完毕后自行删除返回的临时文件（如 {@code SnapshotManager} 发完所有 chunk 后删除）。</p>
     *
     * @param memoryStore 内存存储
     * @return 临时 RDB 文件（已落盘）；生成失败或 dump.rdb 不存在时返回 {@code null}
     */
    public File generateTempRdbFile(MemoryStore memoryStore) {
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
    
    /**
     * RDB 快照传输结果
     * 
     * <p>包含本次传输的字节数以及 RDB 文件落盘时刻对应的 backlog 偏移量。
     * 主节点在全量同步完成后，可据此偏移量从 backlog 中重放窗口期命令。
     */
    public static class SnapshotResult {
        private final long transferredBytes;
        private final long snapshotOffset;
        
        public SnapshotResult(long transferredBytes, long snapshotOffset) {
            this.transferredBytes = transferredBytes;
            this.snapshotOffset = snapshotOffset;
        }
        
        /**
         * 传输的字节数，失败时为 -1
         */
        public long getTransferredBytes() {
            return transferredBytes;
        }
        
        /**
         * RDB 落盘时刻的 backlog 偏移量。
         * 未提供 backlog 时为 -1，表示不进行窗口期重放。
         */
        public long getSnapshotOffset() {
            return snapshotOffset;
        }
        
        /**
         * 是否传输成功
         */
        public boolean isSuccess() {
            return transferredBytes > 0;
        }
        
        /**
         * 是否包含可用于重放的快照偏移量
         */
        public boolean hasSnapshotOffset() {
            return snapshotOffset >= 0;
        }
        
        /**
         * 创建失败结果
         */
        public static SnapshotResult failure() {
            return new SnapshotResult(-1, -1);
        }
    }
}
