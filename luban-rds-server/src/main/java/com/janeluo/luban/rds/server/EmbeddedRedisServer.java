package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmbeddedRedisServer implements RedisServer {
    private static final Logger logger = LoggerFactory.getLogger(EmbeddedRedisServer.class);
    
    private final NettyRedisServer nettyServer;
    private final int port;
    
    public EmbeddedRedisServer() {
        this(0); // 使用随机端口
    }
    
    public EmbeddedRedisServer(int port) {
        this.port = port;
        this.nettyServer = new NettyRedisServer(port);
    }
    
    @Override
    public void start() {
        logger.info("Starting embedded LbRDS server on port {}", port == 0 ? "random" : port);
        nettyServer.start();
    }
    
    @Override
    public void stop() {
        logger.info("Stopping embedded LbRDS server");
        nettyServer.stop();
    }
    
    @Override
    public boolean isRunning() {
        return nettyServer.isRunning();
    }
    
    @Override
    public int getPort() {
        return nettyServer.getPort();
    }
    
    public MemoryStore getMemoryStore() {
        return nettyServer.getMemoryStore();
    }
    
    public void setMemoryStore(MemoryStore memoryStore) {
        // 注意：这里需要修改NettyRedisServer以支持自定义MemoryStore
        // 目前使用默认实现
    }
}
