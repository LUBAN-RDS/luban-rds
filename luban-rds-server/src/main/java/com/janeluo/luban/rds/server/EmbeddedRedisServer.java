package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 内嵌Redis服务器
 * 
 * <p>提供内嵌模式的Redis服务器实现，适用于测试和嵌入式场景。
 * 支持随机端口分配，方便并行测试。
 * 
 * @author janeluo
 * @since 1.0.0
 */
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
