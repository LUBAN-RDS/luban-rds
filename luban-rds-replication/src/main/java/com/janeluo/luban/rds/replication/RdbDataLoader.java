package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RDB 数据加载器
 * 
 * 用于从节点加载主节点传输的 RDB 数据
 * 支持流式加载和进度监控
 */
public class RdbDataLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(RdbDataLoader.class);
    
    /**
     * 缓冲区大小（64KB）
     */
    private static final int BUFFER_SIZE = 64 * 1024;
    
    /**
     * RDB 持久化服务
     */
    private final RdbPersistService rdbPersistService;
    
    /**
     * 数据目录
     */
    private final String dataDir;
    
    /**
     * 是否正在加载
     */
    private final AtomicBoolean isLoading = new AtomicBoolean(false);
    
    /**
     * 加载统计
     */
    private final AtomicLong totalBytesLoaded = new AtomicLong(0);
    private final AtomicLong keysLoaded = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(0);
    
    /**
     * 临时文件路径
     */
    private volatile String tempRdbPath;
    
    /**
     * 加载进度监控器
     */
    private volatile LoadProgressMonitor progressMonitor;
    
    /**
     * 构造函数
     * 
     * @param rdbPersistService RDB 持久化服务
     * @param dataDir 数据目录
     */
    public RdbDataLoader(RdbPersistService rdbPersistService, String dataDir) {
        this.rdbPersistService = rdbPersistService;
        this.dataDir = dataDir != null ? dataDir : System.getProperty("java.io.tmpdir");
    }
    
    /**
     * 开始加载 RDB 数据
     * 
     * @param memoryStore 内存存储
     * @param progressMonitor 进度监控器（可选）
     * @return 是否成功开始加载
     */
    public boolean startLoading(MemoryStore memoryStore, LoadProgressMonitor progressMonitor) {
        if (!isLoading.compareAndSet(false, true)) {
            logger.warn("RDB loading is already in progress");
            return false;
        }
        
        try {
            startTime.set(System.currentTimeMillis());
            totalBytesLoaded.set(0);
            keysLoaded.set(0);
            this.progressMonitor = progressMonitor;
            
            // 创建临时文件用于接收 RDB 数据
            tempRdbPath = dataDir + File.separator + 
                         "temp-slave-loading-" + System.currentTimeMillis() + ".rdb";
            
            logger.info("Started RDB loading, temp file: {}", tempRdbPath);
            
            if (progressMonitor != null) {
                progressMonitor.onStart();
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to start RDB loading", e);
            isLoading.set(false);
            return false;
        }
    }
    
    /**
     * 写入 RDB 数据块
     * 
     * @param data 数据缓冲区
     * @return 是否成功写入
     */
    public boolean writeChunk(ByteBuf data) {
        if (!isLoading.get()) {
            logger.warn("RDB loading not started");
            return false;
        }
        
        try {
            // 追加写入临时文件
            try (FileOutputStream fos = new FileOutputStream(tempRdbPath, true);
                 BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE)) {
                
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);
                bos.write(bytes);
                
                totalBytesLoaded.addAndGet(bytes.length);
                
                if (progressMonitor != null) {
                    progressMonitor.onDataReceived(bytes.length, totalBytesLoaded.get());
                }
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to write RDB chunk", e);
            return false;
        }
    }
    
    /**
     * 完成加载并应用数据
     * 
     * @param memoryStore 内存存储
     * @return 加载的键数量
     */
    public long finishLoading(MemoryStore memoryStore) {
        if (!isLoading.get()) {
            logger.warn("RDB loading not started");
            return -1;
        }
        
        try {
            logger.info("RDB data transfer completed, loading into memory...");
            
            // 加载 RDB 文件
            File tempRdbFile = new File(tempRdbPath);
            if (!tempRdbFile.exists()) {
                logger.error("Temp RDB file not found: {}", tempRdbPath);
                return -1;
            }
            
            // 使用 RdbPersistService 加载数据
            long loadStartTime = System.currentTimeMillis();
            
            // 复制到标准位置并加载
            File rdbFile = new File(dataDir, "dump.rdb");
            copyFile(tempRdbFile, rdbFile);

            // 加载数据：用 loadWithKeyCount 取回实际加载的键数量，
            // 修复 keysLoaded 恒为 0 的 bug（原 load 不返回 keyCount）。
            long loadedKeys = rdbPersistService.loadWithKeyCount(memoryStore);
            keysLoaded.set(loadedKeys);

            long loadDuration = System.currentTimeMillis() - loadStartTime;
            long totalDuration = System.currentTimeMillis() - startTime.get();
            
            logger.info("RDB loading completed: {} bytes loaded in {} ms (total: {} ms)",
                       totalBytesLoaded.get(), loadDuration, totalDuration);
            
            if (progressMonitor != null) {
                progressMonitor.onComplete(totalBytesLoaded.get(), keysLoaded.get());
            }
            
            // 删除临时文件
            deleteTempFile(tempRdbFile);
            
            return keysLoaded.get();
            
        } catch (Exception e) {
            logger.error("Failed to finish RDB loading", e);
            if (progressMonitor != null) {
                progressMonitor.onError(e.getMessage());
            }
            return -1;
            
        } finally {
            isLoading.set(false);
            tempRdbPath = null;
            progressMonitor = null;
        }
    }
    
    /**
     * 取消加载
     */
    public void cancelLoading() {
        if (isLoading.get()) {
            logger.info("Cancelling RDB loading");
            isLoading.set(false);
            
            // 删除临时文件
            if (tempRdbPath != null) {
                deleteTempFile(new File(tempRdbPath));
            }
            
            tempRdbPath = null;
            progressMonitor = null;
        }
    }
    
    /**
     * 复制文件
     */
    private void copyFile(File source, File target) throws IOException {
        try (FileInputStream fis = new FileInputStream(source);
             FileOutputStream fos = new FileOutputStream(target);
             BufferedInputStream bis = new BufferedInputStream(fis, BUFFER_SIZE);
             BufferedOutputStream bos = new BufferedOutputStream(fos, BUFFER_SIZE)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, bytesRead);
            }
        }
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
     * 是否正在加载
     */
    public boolean isLoading() {
        return isLoading.get();
    }
    
    /**
     * 获取已加载字节数
     */
    public long getTotalBytesLoaded() {
        return totalBytesLoaded.get();
    }
    
    /**
     * 获取已加载键数量
     */
    public long getKeysLoaded() {
        return keysLoaded.get();
    }
    
    /**
     * 获取加载进度百分比
     */
    public double getLoadProgress() {
        // 这里无法知道总大小，返回已加载字节数
        return totalBytesLoaded.get();
    }
    
    /**
     * 获取数据目录
     */
    public String getDataDir() {
        return dataDir;
    }
}
