package com.janeluo.luban.rds.server;

/**
 * Redis服务器接口
 * 
 * <p>定义Redis服务器的基本操作契约，包括启动、停止、状态检查等。
 * 
 * @author janeluo
 * @since 1.0.0
 */
public interface RedisServer {
    
    /**
     * 启动服务器
     */
    void start();
    
    /**
     * 停止服务器
     */
    void stop();
    
    /**
     * 检查服务器是否正在运行
     *
     * @return 如果服务器正在运行返回true，否则返回false
     */
    boolean isRunning();
    
    /**
     * 获取服务器监听端口
     *
     * @return 端口号
     */
    int getPort();
}
