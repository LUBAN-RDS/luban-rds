package com.janeluo.luban.rds.replication;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 传输进度跟踪器
 * 
 * 用于记录和查询 RDB 数据传输和加载进度
 */
public class TransferProgressTracker implements TransferProgressMonitor, LoadProgressMonitor {
    
    /**
     * 传输状态
     */
    public enum TransferState {
        IDLE,           // 空闲
        GENERATING,     // 生成快照中
        TRANSFERRING,   // 传输中
        LOADING,        // 加载中
        COMPLETED,      // 已完成
        ERROR           // 错误
    }
    
    private final AtomicReference<TransferState> state = new AtomicReference<>(TransferState.IDLE);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong transferredBytes = new AtomicLong(0);
    private final AtomicLong chunkCount = new AtomicLong(0);
    private final AtomicLong keysLoaded = new AtomicLong(0);
    private final AtomicLong startTime = new AtomicLong(0);
    private final AtomicLong endTime = new AtomicLong(0);
    private final AtomicReference<String> errorMessage = new AtomicReference<>(null);
    
    // 速度计算
    private final AtomicLong lastUpdateTime = new AtomicLong(0);
    private final AtomicLong lastTransferredBytes = new AtomicLong(0);
    private final AtomicReference<Double> currentSpeed = new AtomicReference<>(0.0);
    
    // ==================== TransferProgressMonitor 实现 ====================
    
    @Override
    public void onProgress(long transferredBytes, long totalBytes, int chunkCount) {
        this.totalBytes.set(totalBytes);
        this.transferredBytes.set(transferredBytes);
        this.chunkCount.set(chunkCount);
        this.state.set(TransferState.TRANSFERRING);
        
        updateSpeed(transferredBytes);
    }
    
    @Override
    public void onComplete(long transferredBytes, long totalBytes) {
        this.transferredBytes.set(transferredBytes);
        this.totalBytes.set(totalBytes);
        this.endTime.set(System.currentTimeMillis());
        this.state.set(TransferState.COMPLETED);
    }
    
    @Override
    public void onError(String error) {
        this.errorMessage.set(error);
        this.endTime.set(System.currentTimeMillis());
        this.state.set(TransferState.ERROR);
    }
    
    // ==================== LoadProgressMonitor 实现 ====================
    
    @Override
    public void onStart() {
        this.startTime.set(System.currentTimeMillis());
        this.state.set(TransferState.LOADING);
    }
    
    @Override
    public void onDataReceived(long receivedBytes, long totalReceivedBytes) {
        this.transferredBytes.set(totalReceivedBytes);
        updateSpeed(totalReceivedBytes);
    }
    
    // ==================== 公共方法 ====================
    
    /**
     * 开始生成快照
     */
    public void startGenerating() {
        this.startTime.set(System.currentTimeMillis());
        this.state.set(TransferState.GENERATING);
        reset();
    }
    
    /**
     * 重置状态
     */
    public void reset() {
        totalBytes.set(0);
        transferredBytes.set(0);
        chunkCount.set(0);
        keysLoaded.set(0);
        endTime.set(0);
        errorMessage.set(null);
        lastUpdateTime.set(0);
        lastTransferredBytes.set(0);
        currentSpeed.set(0.0);
    }
    
    /**
     * 更新速度计算
     */
    private void updateSpeed(long currentTransferredBytes) {
        long now = System.currentTimeMillis();
        long lastTime = lastUpdateTime.get();
        
        if (lastTime > 0 && now > lastTime) {
            long bytesDiff = currentTransferredBytes - lastTransferredBytes.get();
            long timeDiff = now - lastTime;
            
            if (timeDiff > 0) {
                // 计算速度（KB/s）
                double speed = (bytesDiff / 1024.0) / (timeDiff / 1000.0);
                currentSpeed.set(speed);
            }
        }
        
        lastUpdateTime.set(now);
        lastTransferredBytes.set(currentTransferredBytes);
    }
    
    /**
     * 设置已加载键数量
     */
    public void setKeysLoaded(long keysLoaded) {
        this.keysLoaded.set(keysLoaded);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 获取当前状态
     */
    public TransferState getState() {
        return state.get();
    }
    
    /**
     * 是否正在传输
     */
    public boolean isTransferring() {
        TransferState s = state.get();
        return s == TransferState.GENERATING || 
               s == TransferState.TRANSFERRING || 
               s == TransferState.LOADING;
    }
    
    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return state.get() == TransferState.COMPLETED;
    }
    
    /**
     * 是否出错
     */
    public boolean hasError() {
        return state.get() == TransferState.ERROR;
    }
    
    /**
     * 获取总字节数
     */
    public long getTotalBytes() {
        return totalBytes.get();
    }
    
    /**
     * 获取已传输字节数
     */
    public long getTransferredBytes() {
        return transferredBytes.get();
    }
    
    /**
     * 获取传输进度百分比
     */
    public double getProgressPercent() {
        long total = totalBytes.get();
        if (total <= 0) {
            return 0;
        }
        return (transferredBytes.get() * 100.0) / total;
    }
    
    /**
     * 获取已传输块数
     */
    public long getChunkCount() {
        return chunkCount.get();
    }
    
    /**
     * 获取已加载键数量
     */
    public long getKeysLoaded() {
        return keysLoaded.get();
    }
    
    /**
     * 获取当前速度（KB/s）
     */
    public double getCurrentSpeed() {
        return currentSpeed.get();
    }
    
    /**
     * 获取已用时间（毫秒）
     */
    public long getElapsedTime() {
        long start = startTime.get();
        if (start <= 0) {
            return 0;
        }
        
        long end = endTime.get();
        if (end > 0) {
            return end - start;
        }
        
        return System.currentTimeMillis() - start;
    }
    
    /**
     * 获取预计剩余时间（毫秒）
     */
    public long getEstimatedRemainingTime() {
        long total = totalBytes.get();
        long transferred = transferredBytes.get();
        double speed = currentSpeed.get();
        
        if (total <= 0 || transferred <= 0 || speed <= 0) {
            return -1;
        }
        
        long remainingBytes = total - transferred;
        // 速度单位是 KB/s，转换为字节/毫秒
        double bytesPerMs = speed * 1024 / 1000;
        
        if (bytesPerMs <= 0) {
            return -1;
        }
        
        return (long) (remainingBytes / bytesPerMs);
    }
    
    /**
     * 获取错误信息
     */
    public String getErrorMessage() {
        return errorMessage.get();
    }
    
    /**
     * 获取进度信息字符串
     */
    public String getProgressInfo() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("state:").append(state.get().name().toLowerCase()).append("\r\n");
        sb.append("total_bytes:").append(totalBytes.get()).append("\r\n");
        sb.append("transferred_bytes:").append(transferredBytes.get()).append("\r\n");
        sb.append("progress:").append(String.format("%.2f%%", getProgressPercent())).append("\r\n");
        sb.append("chunks:").append(chunkCount.get()).append("\r\n");
        sb.append("keys_loaded:").append(keysLoaded.get()).append("\r\n");
        sb.append("speed:").append(String.format("%.2f KB/s", currentSpeed.get())).append("\r\n");
        sb.append("elapsed_time:").append(getElapsedTime()).append(" ms\r\n");
        
        long remaining = getEstimatedRemainingTime();
        if (remaining > 0) {
            sb.append("estimated_remaining:").append(remaining).append(" ms\r\n");
        }
        
        if (errorMessage.get() != null) {
            sb.append("error:").append(errorMessage.get()).append("\r\n");
        }
        
        return sb.toString();
    }
    
    @Override
    public String toString() {
        return String.format("TransferProgressTracker{state=%s, transferred=%d/%d bytes (%.1f%%), speed=%.1f KB/s}",
                           state.get(), transferredBytes.get(), totalBytes.get(), 
                           getProgressPercent(), currentSpeed.get());
    }
}
