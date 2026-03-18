package com.janeluo.luban.rds.replication;

/**
 * 传输进度监控接口
 * 
 * 用于监控 RDB 数据传输进度
 */
public interface TransferProgressMonitor {
    
    /**
     * 传输进度回调
     * 
     * @param transferredBytes 已传输字节数
     * @param totalBytes 总字节数
     * @param chunkCount 已传输块数
     */
    void onProgress(long transferredBytes, long totalBytes, int chunkCount);
    
    /**
     * 传输完成回调
     * 
     * @param transferredBytes 已传输字节数
     * @param totalBytes 总字节数
     */
    void onComplete(long transferredBytes, long totalBytes);
    
    /**
     * 传输错误回调
     * 
     * @param error 错误信息
     */
    void onError(String error);
}
