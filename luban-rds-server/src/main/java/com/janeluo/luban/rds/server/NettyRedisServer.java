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
import com.janeluo.luban.rds.sentinel.config.SentinelConfig;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SentinelManager;
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
    
    // ==================== 哨兵相关组件 ====================
    
    /**
     * 是否启用哨兵模式
     */
    private boolean sentinelEnabled;
    
    /**
     * 哨兵配置
     */
    private SentinelConfig sentinelConfig;
    
    /**
     * 哨兵实例
     */
    private Sentinel sentinel;
    
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
            saveClusterConfig();
        }
        
        // 初始化哨兵模式
        if (config.isSentinelEnabled()) {
            initSentinelMode();
        }
    }
    
    /**
     * 初始化哨兵模式
     */
    private void initSentinelMode() {
        logger.info("初始化哨兵模式...");
        
        this.sentinelEnabled = true;
        
        // 1. 创建哨兵配置
        this.sentinelConfig = new SentinelConfig();
        this.sentinelConfig.setPort(config.getSentinelPort());
        this.sentinelConfig.setBind(config.getBind());
        this.sentinelConfig.setDownAfterMilliseconds(config.getSentinelDownAfterMilliseconds());
        this.sentinelConfig.setFailoverTimeout(config.getSentinelFailoverTimeout());
        this.sentinelConfig.setParallelSyncs(config.getSentinelParallelSyncs());
        
        // 设置公告 IP 和端口
        if (config.getSentinelAnnounceIp() != null && !config.getSentinelAnnounceIp().isEmpty()) {
            this.sentinelConfig.setBind(config.getSentinelAnnounceIp());
        }
        if (config.getSentinelAnnouncePort() > 0) {
            this.sentinelConfig.setPort(config.getSentinelAnnouncePort());
        }
        
        // 2. 解析并添加主节点监控配置
        String sentinelMonitor = config.getSentinelMonitor();
        if (sentinelMonitor != null && !sentinelMonitor.isEmpty()) {
            String[] parts = sentinelMonitor.split("\\s+");
            if (parts.length >= 4) {
                String name = parts[0];
                String host = parts[1];
                int port = Integer.parseInt(parts[2]);
                int quorum = Integer.parseInt(parts[3]);
                this.sentinelConfig.addMasterConfig(name, host, port, quorum);
                logger.info("哨兵监控主节点: name={}, host={}, port={}, quorum={}", 
                           name, host, port, quorum);
            }
        }
        
        // 3. 创建哨兵实例
        this.sentinel = SentinelManager.getInstance().createSentinel(this.sentinelConfig);
        
        logger.info("哨兵模式初始化完成: port={}", config.getSentinelPort());
    }
    
    /**
     * 初始化集群模式
     */
    private void initClusterMode() {
        logger.info("初始化集群模式...");
        
        this.clusterEnabled = true;
        
        // 1. 尝试从 nodes.conf 加载已有集群配置
        ClusterConfig loadedConfig = loadClusterConfigFromFile();
        
        // 2. 初始化 ClusterConfig（优先使用已有节点ID，否则生成新ID）
        String nodeId;
        if (loadedConfig != null && loadedConfig.getMyNodeId() != null) {
            nodeId = loadedConfig.getMyNodeId();
        } else {
            nodeId = ClusterConfigPersister.generateNodeId();
        }
        this.clusterConfig = new ClusterConfig(nodeId);
        
        // 3. 从加载的配置恢复集群状态（节点、槽位、纪元等）
        if (loadedConfig != null) {
            restoreClusterFromConfig(loadedConfig);
        }
        
        // 4. 初始化/更新当前节点信息（使用当前网络地址）
        initCurrentNode(nodeId);
        
        // 5. 初始化 SlotManager
        this.slotManager = new DefaultSlotManager(nodeId);
        
        // 6. 初始化 ClusterStateManager
        this.clusterStateManager = new ClusterStateManager(clusterConfig);
        
        // 7. 初始化 ClusterBusClient（需要在 GossipProtocol 之前）
        this.clusterBusClient = new ClusterBusClient(clusterConfig, null);
        
        // 8. 初始化 GossipProtocol
        this.gossipProtocol = new GossipProtocol(
                clusterConfig, 
                clusterBusClient, 
                config.getClusterNodeTimeout());
        
        // 解决构造函数顺序依赖：将 gossipProtocol 注入到 ClusterBusClient，
        // 确保 ClusterBusClient 创建的 ClusterBusHandler 能正确处理 PONG 等握手响应
        this.clusterBusClient.setGossipProtocol(gossipProtocol);
        
        // 将 clusterStateManager 注入到 GossipProtocol，用于消息计数统计
        this.gossipProtocol.setClusterStateManager(clusterStateManager);
        
        // 9. 初始化 ClusterCommandHandler
        String clusterConfigFilePath = new File(config.getDir(), config.getClusterConfigFile()).getAbsolutePath();
        this.clusterCommandHandler = new ClusterCommandHandler(
                clusterConfig, 
                slotManager, 
                clusterStateManager, 
                gossipProtocol,
                clusterConfigFilePath);
        
        // 10. 初始化 ClusterBusServer
        this.clusterBusServer = new ClusterBusServer(port, clusterConfig, gossipProtocol);
        
        logger.info("集群模式初始化完成: nodeId={}, port={}, busPort={}", 
                nodeId, port, clusterBusServer.getPort());
    }
    
    /**
     * 从 nodes.conf 加载集群配置
     * <p>
     * 在集群模式启动时尝试加载已有的集群配置文件，
     * 恢复节点列表、槽位分配和配置纪元等信息。
     * </p>
     *
     * @return 加载的集群配置，如果文件不存在或加载失败则返回 null
     */
    private ClusterConfig loadClusterConfigFromFile() {
        String configFile = config.getClusterConfigFile();
        
        if (configFile != null && !configFile.isEmpty()) {
            File file = new File(config.getDir(), configFile);
            if (file.exists()) {
                try {
                    ClusterConfigPersister persister = new ClusterConfigPersister();
                    ClusterConfig loadedConfig = persister.load(file.getAbsolutePath());
                    if (loadedConfig.getMyNodeId() != null && !loadedConfig.getMyNodeId().isEmpty()) {
                        logger.info("从配置文件加载集群配置: nodeId={}, 节点数={}, 槽位数={}",
                                loadedConfig.getMyNodeId(), loadedConfig.getNodeCount(),
                                loadedConfig.getAssignedSlotCount());
                        return loadedConfig;
                    }
                } catch (IOException e) {
                    logger.warn("加载集群配置文件失败: {}", e.getMessage());
                }
            }
        }
        return null;
    }

    /**
     * 从加载的集群配置恢复集群状态
     * <p>
     * 将 nodes.conf 中保存的节点列表、槽位分配、配置纪元等恢复到当前 ClusterConfig 中。
     * MYSELF 节点的网络地址（IP/端口）将在后续 initCurrentNode() 中更新为当前值。
     * </p>
     *
     * @param loaded 从文件加载的集群配置
     */
    private void restoreClusterFromConfig(ClusterConfig loaded) {
        // 恢复配置纪元
        clusterConfig.setCurrentEpoch(loaded.getCurrentEpoch());
        clusterConfig.setConfigEpoch(loaded.getConfigEpoch());
        
        // 恢复所有节点（MYSELF 节点的网络地址将在 initCurrentNode 中更新）
        for (ClusterNode node : loaded.getAllNodes()) {
            clusterConfig.addNode(node);
        }
        
        // 恢复槽位分配
        int restoredSlots = 0;
        for (int i = 0; i < ClusterNode.CLUSTER_SLOTS; i++) {
            String ownerId = loaded.getSlotOwner(i);
            if (ownerId != null) {
                clusterConfig.setSlotOwner(i, ownerId);
                restoredSlots++;
            }
        }
        
        logger.info("从配置文件恢复集群状态: 节点数={}, 槽位数={}, currentEpoch={}, configEpoch={}",
                loaded.getNodeCount(), restoredSlots, loaded.getCurrentEpoch(), loaded.getConfigEpoch());
    }

    /**
     * 加载或创建节点ID
     * 
     * @return 节点ID（40字符十六进制）
     */
    private String loadOrCreateNodeId() {
        ClusterConfig loadedConfig = loadClusterConfigFromFile();
        if (loadedConfig != null && loadedConfig.getMyNodeId() != null) {
            logger.info("从配置文件加载节点ID: {}", loadedConfig.getMyNodeId());
            return loadedConfig.getMyNodeId();
        }
        
        String newNodeId = ClusterConfigPersister.generateNodeId();
        logger.info("生成新的节点ID: {}", newNodeId);
        return newNodeId;
    }
    
    /**
     * 初始化当前节点信息
     * <p>
     * 如果节点已从 nodes.conf 恢复（具有相同 nodeId 且 MYSELF 状态的节点），
     * 则仅更新其网络地址（IP/端口可能在重启后变化），保留其状态、槽位、配置纪元等。
     * 如果节点不存在，则创建新的 MYSELF 节点（首次启动）。
     * </p>
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
        
        // 检查是否已有 MYSELF 节点（从 nodes.conf 恢复）
        ClusterNode myNode = clusterConfig.getNode(nodeId);
        if (myNode != null) {
            // 从配置文件恢复的节点，更新网络地址（IP/端口可能在重启后变化）
            logger.info("从配置文件恢复当前节点: nodeId={}, oldAddress={}", nodeId, myNode.getFullAddress());
            myNode.setIp(ip);
            myNode.setPort(announcePort);
            myNode.setBusPort(busPort);
            myNode.getLink().setConnected(true);
        } else {
            // 创建新的当前节点（首次启动，无配置文件）
            myNode = new ClusterNode(nodeId, ip, announcePort, busPort);
            myNode.addState(ClusterNodeState.MYSELF);
            myNode.addState(ClusterNodeState.MASTER);  // 默认为主节点
            myNode.getLink().setConnected(true);
            
            // 添加到集群配置
            clusterConfig.addNode(myNode);
        }
        
        clusterConfig.setMyNodeId(nodeId);
        
        logger.info("当前节点初始化完成: nodeId={}, address={}", 
                nodeId, myNode.getFullAddress());
    }

    /**
     * 保存集群配置到 nodes.conf 文件
     * <p>
     * 在启动时、CLUSTER SAVECONFIG 命令调用时、以及优雅关闭时写入。
     * </p>
     */
    private void saveClusterConfig() {
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
                     // 集群模式：通过完整构造方法注入 clusterConfig/slotManager，并注入 clusterCommandHandler，
                     // 使 CLUSTER 命令（如 CLUSTER MEET）能正确路由到 ClusterCommandHandler
                     RedisServerHandler handler = new RedisServerHandler(
                             memoryStore, commandHandler, protocolParser, config.getTimeout(),
                             clusterEnabled, clusterConfig, slotManager);
                     if (clusterEnabled && clusterCommandHandler != null) {
                         handler.setClusterCommandHandler(clusterCommandHandler);
                     }
                     pipeline.addLast(businessGroup, "handler", handler);
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
            saveClusterConfig();
            
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
