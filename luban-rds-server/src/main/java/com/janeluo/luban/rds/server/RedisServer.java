package com.janeluo.luban.rds.server;

public interface RedisServer {
    void start();
    
    void stop();
    
    boolean isRunning();
    
    int getPort();
}
