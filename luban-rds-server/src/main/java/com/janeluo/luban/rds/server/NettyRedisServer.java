package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.bus.ClusterBusServer;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterConfigPersister;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.gossip.GossipProtocol;
import com.janeluo.luban.rds.cluster.handler.ClusterCommandHandler;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
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

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
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
    
    // ==================== 集群相关组件 ====================
    
    /**
     * 是否启用集群模式
     */
    private boolean clusterEnabled;
    
    /**
     * 集群配置
     */
    private ClusterConfig clusterConfig;
    
    /**
     * 槽位管理器
     */
    private SlotManager slotManager;
    
    /**
     * 集群总线服务器
     */
    private ClusterBusServer clusterBusServer;
    
    /**
     * Gossip 协议
     */
    private GossipProtocol gossipProtocol;
    
    /**
     * 集群命令处理器
     */
    private ClusterCommandHandler clusterCommandHandler;
    
    /**
     * 集群状态管理器
     */
    private ClusterStateManager clusterStateManager;
    
    /**
     * 集群总线客户端
     */
    private ClusterBusClient clusterBusClient;
    
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
        
        // 初始化 Lua 配置
        RuntimeConfig.setLuaScriptTimeoutMs(config.getLuaTimeout());
        RuntimeConfig.setLuaSandboxEnabled(config.isLuaSandboxEnabled());
        RuntimeConfig.setLuaMaxScriptBytes(config.getLuaMaxScriptBytes());
        RuntimeConfig.setLuaMaxReturnBytes(config.getLuaMaxReturnBytes());
        RuntimeConfig.setLuaMaxOpsPerScript(config.getLuaMaxOpsPerScript());
        RuntimeConfig.setLuaYieldMs(config.getLuaYieldMs());
        RuntimeConfig.setLuaAllowedModules(config.getLuaAllowedModules());
        RuntimeConfig.setLuaBlockedFunctions(config.getLuaBlockedFunctions());
        
        // 初始化集群模式
        if (config.isClusterEnabled()) {
            initClusterMode();
        }
    }
    
    /**
     * 初始化集群模式
     */
    private void initClusterMode() {
        logger.info("初始化集群模式...");
        
        this.clusterEnabled = true;
        
        // 1. 初始化 ClusterConfig
        String nodeId = loadOrCreateNodeId();
        this.clusterConfig = new ClusterConfig(nodeId);
        
        // 2. 初始化当前节点信息
        initCurrentNode(nodeId);
        
        // 3. 初始化 SlotManager
        this.slotManager = new DefaultSlotManager(nodeId);
        
        // 4. 初始化 ClusterStateManager
        this.clusterStateManager = new ClusterStateManager(clusterConfig);
        
        // 5. 初始化 ClusterBusClient（需要在 GossipProtocol 之前）
        this.clusterBusClient = new ClusterBusClient(clusterConfig, null);
        
        // 6. 初始化 GossipProtocol
        this.gossipProtocol = new GossipProtocol(
                clusterConfig, 
                clusterBusClient, 
                config.getClusterNodeTimeout());
        
        // 更新 ClusterBusClient 的 GossipProtocol 引用
        // 注意：由于构造函数顺序问题，这里需要重新创建或使用 setter
        // 这里简化处理，GossipProtocol 已经持有正确的引用
        
        // 7. 初始化 ClusterCommandHandler
        this.clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, 
                slotManager, 
                clusterStateManager, 
                gossipProtocol);
        
        // 8. 初始化 ClusterBusServer
        this.clusterBusServer = new ClusterBusServer(port, clusterConfig, gossipProtocol);
        
        logger.info("集群模式初始化完成: nodeId={}, port={}, busPort={}", 
                nodeId, port, clusterBusServer.getPort());
    }
    
    /**
     * 加载或创建节点ID
     * 
     * @return 节点ID（40字符十六进制）
     */
    private String loadOrCreateNodeId() {
        String configFile = config.getClusterConfigFile();
        
        // 尝试从配置文件加载
        if (configFile != null && !configFile.isEmpty()) {
            File file = new File(config.getDir(), configFile);
            if (file.exists()) {
                try {
                    ClusterConfigPersister persister = new ClusterConfigPersister();
                    ClusterConfig loadedConfig = persister.load(file.getAbsolutePath());
                    String loadedNodeId = loadedConfig.getMyNodeId();
                    if (loadedNodeId != null && !loadedNodeId.isEmpty()) {
                        logger.info("从配置文件加载节点ID: {}", loadedNodeId);
                        return loadedNodeId;
                    }
                } catch (IOException e) {
                    logger.warn("加载集群配置文件失败，将创建新节点ID: {}", e.getMessage());
                }
            }
        }
        
        // 生成新的节点ID
        String newNodeId = ClusterConfigPersister.generateNodeId();
        logger.info("生成新的节点ID: {}", newNodeId);
        return newNodeId;
    }
    
    /**
     * 初始化当前节点信息
     * 
     * @param nodeId 节点ID
     */
    private void initCurrentNode(String nodeId) {
        // 获取本机IP
        String ip = config.getClusterAnnounceIp();
        if (ip == null || ip.isEmpty()) {
            try {
                ip = InetAddress.getLocalHost().getHostAddress();
            } catch (UnknownHostException e) {
                ip = "127.0.0.1";
                logger.warn("无法获取本机IP地址，使用默认值: {}", ip);
            }
        }
        
        // 获取端口
        int announcePort = config.getClusterAnnouncePort();
        if (announcePort <= 0) {
            announcePort = port;
        }
        
        // 获取总线端口
        int busPort = config.getClusterAnnounceBusPort();
        if (busPort <= 0) {
            busPort = announcePort + ClusterBusServer.BUS_PORT_OFFSET;
        }
        
        // 创建当前节点
        ClusterNode myNode = new ClusterNode(nodeId, ip, announcePort, busPort);
        myNode.addState(ClusterNodeState.MYSELF);
        myNode.addState(ClusterNodeState.MASTER);  // 默认为主节点
        
        // 添加到集群配置
        clusterConfig.addNode(myNode);
        clusterConfig.setMyNodeId(nodeId);
        
        logger.info("当前节点初始化完成: nodeId={}, address={}", 
                nodeId, myNode.getFullAddress());
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
            
            // 启动集群总线服务器
            if (clusterEnabled && clusterBusServer != null) {
                clusterBusServer.start();
                logger.info("集群总线服务器启动成功，端口: {}", clusterBusServer.getPort());
            }
            
            // 启动 Gossip 协议
            if (clusterEnabled && gossipProtocol != null) {
                gossipProtocol.start();
                logger.info("Gossip 协议启动成功");
            }
            
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
            // 停止 Gossip 协议
            if (gossipProtocol != null) {
                gossipProtocol.stop();
                logger.info("Gossip 协议已停止");
            }
            
            // 停止集群总线服务器
            if (clusterBusServer != null) {
                clusterBusServer.stop();
                logger.info("集群总线服务器已停止");
            }
            
            // 关闭集群总线客户端
            if (clusterBusClient != null) {
                clusterBusClient.close();
                logger.info("集群总线客户端已关闭");
            }
            
            // 保存集群配置
            if (clusterConfig != null && config.getClusterConfigFile() != null) {
                try {
                    ClusterConfigPersister persister = new ClusterConfigPersister();
                    File configFile = new File(config.getDir(), config.getClusterConfigFile());
                    // 确保目录存在
                    configFile.getParentFile().mkdirs();
                    persister.save(clusterConfig, configFile.getAbsolutePath());
                    logger.info("集群配置已保存到: {}", configFile.getAbsolutePath());
                } catch (IOException e) {
                    logger.error("保存集群配置失败", e);
                }
            }
            
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
    
    // ==================== 集群相关 Getter 方法 ====================
    
    /**
     * 获取集群配置
     * 
     * @return 集群配置对象，如果未启用集群模式则返回 null
     */
    public ClusterConfig getClusterConfig() {
        return clusterConfig;
    }
    
    /**
     * 获取槽位管理器
     * 
     * @return 槽位管理器，如果未启用集群模式则返回 null
     */
    public SlotManager getSlotManager() {
        return slotManager;
    }
    
    /**
     * 检查是否启用集群模式
     * 
     * @return 是否启用集群模式
     */
    public boolean isClusterEnabled() {
        return clusterEnabled;
    }
    
    /**
     * 获取集群命令处理器
     * 
     * @return 集群命令处理器，如果未启用集群模式则返回 null
     */
    public ClusterCommandHandler getClusterCommandHandler() {
        return clusterCommandHandler;
    }
    
    /**
     * 获取集群状态管理器
     * 
     * @return 集群状态管理器，如果未启用集群模式则返回 null
     */
    public ClusterStateManager getClusterStateManager() {
        return clusterStateManager;
    }
    
    /**
     * 获取 Gossip 协议
     * 
     * @return Gossip 协议，如果未启用集群模式则返回 null
     */
    public GossipProtocol getGossipProtocol() {
        return gossipProtocol;
    }
    
    /**
     * 获取集群总线服务器
     * 
     * @return 集群总线服务器，如果未启用集群模式则返回 null
     */
    public ClusterBusServer getClusterBusServer() {
        return clusterBusServer;
    }
    
    /**
     * 获取集群总线客户端
     * 
     * @return 集群总线客户端，如果未启用集群模式则返回 null
     */
    public ClusterBusClient getClusterBusClient() {
        return clusterBusClient;
    }
}
