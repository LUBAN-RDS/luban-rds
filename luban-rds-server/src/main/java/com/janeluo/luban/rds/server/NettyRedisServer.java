package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.ConfigLoader;
import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.PersistService;
import com.janeluo.luban.rds.persistence.PersistServiceFactory;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import io.netty.util.ResourceLeakDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 基于Netty的Redis服务器实现
 * 
 * <p>提供完整的Redis协议兼容服务器实现，支持：
 * <ul>
 *   <li>RESP协议解析和响应</li>
 *   <li>多种数据类型操作（String、Hash、List、Set、ZSet）</li>
 *   <li>Pub/Sub消息订阅发布</li>
 *   <li>事务支持（MULTI/EXEC/DISCARD/WATCH）</li>
 *   <li>Lua脚本执行</li>
 *   <li>持久化（RDB/AOF）</li>
 *   <li>慢日志记录</li>
 *   <li>命令监控（MONITOR）</li>
 * </ul>
 * 
 * @author janeluo
 * @since 1.0.0
 */
public class NettyRedisServer implements RedisServer {
    
    private static final Logger logger = LoggerFactory.getLogger(NettyRedisServer.class);
    
    private final int port;
    private final RdsConfig config;
    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    private final PersistService persistService;
    private final ExecutorService persistExecutor;
    
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup businessGroup;
    private ChannelFuture channelFuture;
    private boolean running;
    
    /**
     * 使用默认配置创建服务器
     */
    public NettyRedisServer() {
        this(new RdsConfig());
    }
    
    /**
     * 使用指定端口创建服务器
     */
    public NettyRedisServer(int port) {
        this(port, "rdb", "./data", 60, 1);
    }
    
    /**
     * 使用详细参数创建服务器（兼容旧版本）
     */
    public NettyRedisServer(int port, String persistMode, String dataDir, int rdbSaveInterval, int aofFsyncInterval) {
        RdsConfig config = new RdsConfig();
        config.setPort(port);
        config.setPersistMode(persistMode);
        config.setDir(dataDir);
        config.setRdbSaveInterval(rdbSaveInterval);
        config.setAofFsyncInterval(aofFsyncInterval);
        
        this.config = config;
        this.port = config.getPort();
        this.memoryStore = new DefaultMemoryStore();
        this.commandHandler = new DefaultCommandHandler();
        this.protocolParser = new RedisProtocolParser();
        this.persistService = PersistServiceFactory.createPersistService(
                config.getPersistMode(), 
                config.getDir(), 
                config.getRdbSaveInterval(), 
                config.getAofFsyncInterval());
        this.persistExecutor = Executors.newSingleThreadExecutor();
        
        // 加载持久化数据
        this.persistService.load(memoryStore);
        
        // 初始化运行时配置
        RuntimeConfig.setSlowlogLogSlowerThan(config.getSlowlogLogSlowerThan());
        RuntimeConfig.setSlowlogMaxLen(config.getSlowlogMaxLen());
        RuntimeConfig.setMonitorMaxClients(config.getMonitorMaxClients());
    }
    
    /**
     * 使用配置对象创建服务器
     * 
     * @param config Redis配置对象，可通过 ConfigLoader 加载
     */
    public NettyRedisServer(RdsConfig config) {
        if (config == null) {
            config = new RdsConfig();
        }
        this.config = config;
        this.port = config.getPort();
        
        // 使用配置创建内存存储，应用数据库数量、最大内存、淘汰策略等配置
        this.memoryStore = new DefaultMemoryStore(
                config.getDatabases(),
                config.getMaxmemory(),
                config.getMaxmemoryPolicy());
        
        // 创建命令处理器，传入密码配置用于AUTH命令验证
        this.commandHandler = new DefaultCommandHandler(config.getRequirepass());
        this.protocolParser = new RedisProtocolParser();
        this.persistService = PersistServiceFactory.createPersistService(
                config.getPersistMode(), 
                config.getDir(), 
                config.getRdbSaveInterval(), 
                config.getAofFsyncInterval());
        this.persistExecutor = Executors.newSingleThreadExecutor();
        
        logger.info("使用配置初始化服务器: {}", config);
        
        // 加载持久化数据
        this.persistService.load(memoryStore);

        // 初始化运行时配置
        RuntimeConfig.setSlowlogLogSlowerThan(config.getSlowlogLogSlowerThan());
        RuntimeConfig.setSlowlogMaxLen(config.getSlowlogMaxLen());
        RuntimeConfig.setMonitorMaxClients(config.getMonitorMaxClients());
    }
    
    /**
     * 从配置文件路径创建服务器
     */
    public static NettyRedisServer fromConfigFile(String configPath) {
        RdsConfig config = ConfigLoader.load(configPath);
        return new NettyRedisServer(config);
    }
    
    /**
     * 从类路径配置文件创建服务器
     */
    public static NettyRedisServer fromClasspathConfig(String resourceName) {
        RdsConfig config = ConfigLoader.loadFromClasspath(resourceName);
        return new NettyRedisServer(config);
    }
    
    @Override
    public void start() {
        if (running) {
            logger.warn("Server is already running");
            return;
        }
        
        // Configure memory leak detection level
        configureLeakDetection();
        
        // Calculate thread pool sizes based on configuration
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int ioThreads = config.getIoThreads() > 0 
                ? config.getIoThreads() 
                : Math.max(1, availableProcessors);
        int workerThreads = config.getWorkerThreads() > 0 
                ? config.getWorkerThreads() 
                : Math.max(1, availableProcessors * 2);
        int businessThreads = config.getBusinessThreads() > 0 
                ? config.getBusinessThreads() 
                : Math.max(1, availableProcessors);
        
        logger.info("Thread pool configuration: ioThreads={}, workerThreads={}, businessThreads={}", 
                ioThreads, workerThreads, businessThreads);
        
        // Boss group: accepts incoming connections (usually 1 thread is enough)
        bossGroup = new NioEventLoopGroup(1);
        // Worker group: handles I/O read/write operations
        workerGroup = new NioEventLoopGroup(workerThreads);
        // Business group: handles business logic (command processing)
        businessGroup = new DefaultEventExecutorGroup(businessThreads);
        
        try {
            // Initialize global server context
            ServerContext.setInfoProvider(new LubanInfoProvider(this));
            
            // Configure ByteBuf allocator based on config
            ByteBufAllocator allocator = config.isUsePool() 
                    ? PooledByteBufAllocator.DEFAULT 
                    : UnpooledByteBufAllocator.DEFAULT;
            
            logger.info("Using {} ByteBuf allocator", config.isUsePool() ? "pooled" : "unpooled");
            
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
             // Configure memory pool allocator
             .option(ChannelOption.ALLOCATOR, allocator)
             // Use configured tcp-backlog
             .option(ChannelOption.SO_BACKLOG, config.getTcpBacklog())
             // Configure child channel allocator
             .childOption(ChannelOption.ALLOCATOR, allocator)
             // Use configured tcp-keepalive
             .childOption(ChannelOption.SO_KEEPALIVE, config.getTcpKeepalive() > 0)
             .childHandler(new ChannelInitializer<SocketChannel>() {
                 @Override
                 public void initChannel(SocketChannel ch) throws Exception {
                     ChannelPipeline pipeline = ch.pipeline();
                     // Pass timeout config
                     // Use businessGroup for command processing to avoid blocking I/O threads
                     pipeline.addLast(businessGroup, "handler", 
                             new RedisServerHandler(memoryStore, commandHandler, protocolParser, config.getTimeout()));
                 }
             });
            
            channelFuture = b.bind(port).sync();
            running = true;
            logger.info("LbRDS server started on port {}", port);
            
            // Start periodic persistence task
            startPeriodicPersistTask();
            
            // Wait for server to close
            channelFuture.channel().closeFuture().addListener(future -> {
                running = false;
                logger.info("LbRDS server stopped");
            });
        } catch (Exception e) {
            logger.error("Failed to start LbRDS server", e);
            stop();
        }
    }
    
    /**
     * Configure memory leak detection level based on config
     */
    private void configureLeakDetection() {
        String level = config.getLeakDetection();
        if (level == null || level.isEmpty()) {
            level = "simple";
        }
        
        ResourceLeakDetector.Level leakLevel;
        switch (level.toLowerCase()) {
            case "disabled":
                leakLevel = ResourceLeakDetector.Level.DISABLED;
                break;
            case "simple":
                leakLevel = ResourceLeakDetector.Level.SIMPLE;
                break;
            case "advanced":
                leakLevel = ResourceLeakDetector.Level.ADVANCED;
                break;
            case "paranoid":
                leakLevel = ResourceLeakDetector.Level.PARANOID;
                break;
            default:
                leakLevel = ResourceLeakDetector.Level.SIMPLE;
                logger.warn("Unknown leak detection level '{}', using 'simple'", level);
        }
        
        ResourceLeakDetector.setLevel(leakLevel);
        logger.info("Memory leak detection level set to: {}", leakLevel);
    }
    
    @Override
    public void stop() {
        if (!running) {
            return;
        }
        
        try {
            // 停止定期持久化任务
            persistExecutor.shutdown();
            if (!persistExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                persistExecutor.shutdownNow();
            }
            
            // 持久化数据
            persistService.persist(memoryStore);
            
            // 关闭持久化服务
            persistService.close();
            
            // 关闭服务器
            if (channelFuture != null) {
                channelFuture.channel().close().sync();
            }
        } catch (Exception e) {
            logger.error("Error stopping LbRDS server", e);
        } finally {
            if (businessGroup != null) {
                businessGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            running = false;
            logger.info("LbRDS server stopped");
        }
    }
    
    private void startPeriodicPersistTask() {
        // 使用配置的 RDB 保存间隔
        final int saveIntervalMs = config.getRdbSaveInterval() * 1000;
        
        persistExecutor.submit(() -> {
            while (running) {
                try {
                    Thread.sleep(saveIntervalMs);
                    persistService.persist(memoryStore);
                    logger.debug("定期持久化完成");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    @Override
    public boolean isRunning() {
        return running;
    }
    
    @Override
    public int getPort() {
        return port;
    }
    
    public MemoryStore getMemoryStore() {
        return memoryStore;
    }
    
    public PersistService getPersistService() {
        return persistService;
    }
    
    /**
     * 获取当前配置
     */
    public RdsConfig getConfig() {
        return config;
    }
}
