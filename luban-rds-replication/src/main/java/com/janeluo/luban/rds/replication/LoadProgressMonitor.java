package com.janeluo.luban.rds.replication;

/**
 * 加载进度监控接口
 * 
 * 用于监控 RDB 数据加载进度
 */
public interface LoadProgressMonitor {
    
    /**
     * 开始加载回调
     */
    void onStart();
    
    /**
     * 数据接收回调
     * 
     * @param receivedBytes 本次接收字节数
     * @param totalReceivedBytes 总接收字节数
     */
    void onDataReceived(long receivedBytes, long totalReceivedBytes);
    
    /**
     * 加载完成回调
     * 
     * @param totalBytes 总字节数
     * @param keysLoaded 加载的键数量
     */
    void onComplete(long totalBytes, long keysLoaded);
    
    /**
     * 加载错误回调
     * 
     * @param error 错误信息
     */
    void onError(String error);
}
