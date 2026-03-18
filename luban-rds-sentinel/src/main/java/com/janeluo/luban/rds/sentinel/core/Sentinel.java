package com.janeluo.luban.rds.sentinel.core;

import com.janeluo.luban.rds.sentinel.config.SentinelConfig;
import com.janeluo.luban.rds.sentinel.config.SentinelConstants;
import com.janeluo.luban.rds.sentinel.failover.FailoverManager;
import com.janeluo.luban.rds.sentinel.monitor.HealthChecker;
import com.janeluo.luban.rds.sentinel.monitor.NodeMonitor;
import com.janeluo.luban.rds.sentinel.util.SentinelStats;
import com.janeluo.luban.rds.sentinel.util.SentinelUtils;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.redis.RedisDecoder;
import io.netty.handler.codec.redis.RedisEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 哨兵核心类
 * 单个哨兵实例的核心逻辑
 */
public class Sentinel {
    
    private static final Logger logger = LoggerFactory.getLogger(Sentinel.class);
    
    /**
     * 哨兵 ID
     */
    private final String sentinelId;
    
    /**
     * 哨兵配置
     */
    private final SentinelConfig config;
    
    /**
     * 当前状态
     */
    private volatile SentinelState state = SentinelState.INIT;
    
    /**
     * 监控的主节点
     */
    private final Map<String, MasterState> masters = new ConcurrentHashMap<>();
    
    /**
     * 节点监控器
     */
    private NodeMonitor nodeMonitor;
    
    /**
     * 健康检查器
     */
    private HealthChecker healthChecker;
    
    /**
     * 故障转移管理器
     */
    private FailoverManager failoverManager;
    
    /**
     * 统计信息
     */
    private final SentinelStats stats = new SentinelStats();
    
    /**
     * 定时任务执行器
     */
    private ScheduledExecutorService scheduler;
    
    /**
     * Netty 相关
     */
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    
    /**
     * 当前纪元
     */
    private volatile long currentEpoch = 0;
    
    /**
     * 运行标志
     */
    private volatile boolean running = false;
    
    public Sentinel(SentinelConfig config) {
        this.config = config;
        this.sentinelId = config.getSentinelId() != null ? 
            config.getSentinelId() : generateSentinelId();
        config.setSentinelId(this.sentinelId);
    }
    
    /**
     * 生成哨兵 ID
     */
    private String generateSentinelId() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString().substring(0, SentinelConstants.SENTINEL_ID_LENGTH);
    }
    
    /**
     * 启动哨兵
     */
    public synchronized void start() {
        if (running) {
            logger.warn("Sentinel {} is already running", sentinelId);
            return;
        }
        
        logger.info("Starting sentinel {} on port {}", sentinelId, config.getPort());
        
        try {
            // 初始化组件
            nodeMonitor = new NodeMonitor(this);
            healthChecker = new HealthChecker(this);
            failoverManager = new FailoverManager(this);
            
            // 初始化定时任务执行器
            scheduler = Executors.newScheduledThreadPool(4);
            
            // 加载主节点配置
            loadMasterConfigs();
            
            // 启动 Netty 服务器
            startNettyServer();
            
            // 启动监控任务
            startMonitorTasks();
            
            running = true;
            state = SentinelState.RUNNING;
            
            logger.info("Sentinel {} started successfully", sentinelId);
            
        } catch (Exception e) {
            logger.error("Failed to start sentinel {}", sentinelId, e);
            state = SentinelState.SHUTDOWN;
            throw new RuntimeException("Failed to start sentinel", e);
        }
    }
    
    /**
     * 加载主节点配置
     */
    private void loadMasterConfigs() {
        for (Map.Entry<String, SentinelConfig.MasterMonitorConfig> entry : 
             config.getMasterConfigs().entrySet()) {
            SentinelConfig.MasterMonitorConfig masterConfig = entry.getValue();
            monitorMaster(masterConfig.getName(), masterConfig.getHost(), 
                         masterConfig.getPort(), masterConfig.getQuorum());
        }
    }
    
    /**
     * 启动 Netty 服务器
     */
    private void startNettyServer() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                          .addLast(new RedisDecoder(true))
                          .addLast(new RedisEncoder())
                          .addLast(new SentinelServerHandler(Sentinel.this));
                    }
                });
        
        ChannelFuture future = bootstrap.bind(config.getBind(), config.getPort()).sync();
        serverChannel = future.channel();
        
        logger.info("Sentinel {} listening on {}:{}", sentinelId, config.getBind(), config.getPort());
    }
    
    /**
     * 启动监控任务
     */
    private void startMonitorTasks() {
        // PING 任务
        scheduler.scheduleAtFixedRate(() -> {
            try {
                nodeMonitor.sendPingToAllNodes();
            } catch (Exception e) {
                logger.error("Error in PING task", e);
            }
        }, 0, config.getPingInterval(), TimeUnit.MILLISECONDS);
        
        // INFO 任务
        scheduler.scheduleAtFixedRate(() -> {
            try {
                nodeMonitor.queryInfoFromAllNodes();
            } catch (Exception e) {
                logger.error("Error in INFO task", e);
            }
        }, 0, config.getInfoInterval(), TimeUnit.MILLISECONDS);
        
        // 健康检查任务
        scheduler.scheduleAtFixedRate(() -> {
            try {
                healthChecker.checkAllNodes();
            } catch (Exception e) {
                logger.error("Error in health check task", e);
            }
        }, 0, config.getMonitorInterval(), TimeUnit.MILLISECONDS);
        
        // Pub/Sub hello 消息任务
        scheduler.scheduleAtFixedRate(() -> {
            try {
                nodeMonitor.publishHelloMessage();
            } catch (Exception e) {
                logger.error("Error in Pub/Sub task", e);
            }
        }, 0, config.getPubsubInterval(), TimeUnit.MILLISECONDS);
    }
    
    /**
     * 关闭哨兵
     */
    public synchronized void shutdown() {
        if (!running) {
            return;
        }
        
        logger.info("Shutting down sentinel {}", sentinelId);
        state = SentinelState.SHUTTING_DOWN;
        running = false;
        
        try {
            // 关闭定时任务
            if (scheduler != null) {
                scheduler.shutdown();
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            }
            
            // 关闭 Netty 服务器
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully();
            }
            
            state = SentinelState.SHUTDOWN;
            logger.info("Sentinel {} shutdown completed", sentinelId);
            
        } catch (Exception e) {
            logger.error("Error during sentinel shutdown", e);
        }
    }
    
    /**
     * 监控主节点
     */
    public void monitorMaster(String name, String host, int port, int quorum) {
        MasterState master = new MasterState(name, host, port, quorum);
        master.setDownAfterMilliseconds(config.getDownAfterMilliseconds());
        master.setFailoverTimeout(config.getFailoverTimeout());
        master.setParallelSyncs(config.getParallelSyncs());
        
        masters.put(name, master);
        
        logger.info("Sentinel {} starts monitoring master {} at {}:{} with quorum {}", 
                   sentinelId, name, host, port, quorum);
        
        stats.incrementMastersMonitored();
    }
    
    /**
     * 移除主节点监控
     */
    public void removeMaster(String name) {
        MasterState master = masters.remove(name);
        if (master != null) {
            logger.info("Sentinel {} stops monitoring master {}", sentinelId, name);
            stats.decrementMastersMonitored();
        }
    }
    
    /**
     * 获取主节点状态
     */
    public MasterState getMasterState(String name) {
        return masters.get(name);
    }
    
    /**
     * 获取所有主节点
     */
    public Map<String, MasterState> getMasters() {
        return new ConcurrentHashMap<>(masters);
    }
    
    /**
     * 获取主节点地址
     */
    public String[] getMasterAddrByName(String name) {
        MasterState master = masters.get(name);
        if (master == null) {
            return null;
        }
        return new String[]{master.getHost(), String.valueOf(master.getPort())};
    }
    
    /**
     * 触发故障转移
     */
    public void startFailover(String masterName) {
        MasterState master = masters.get(masterName);
        if (master == null) {
            logger.warn("Cannot start failover: master {} not found", masterName);
            return;
        }
        
        failoverManager.startFailover(masterName);
    }
    
    /**
     * 检查仲裁
     */
    public boolean checkQuorum(String masterName) {
        MasterState master = masters.get(masterName);
        if (master == null) {
            return false;
        }
        
        int agreeCount = 1; // 自己
        for (SentinelInstance sentinel : master.getSentinels().values()) {
            if (sentinel.isVotedMasterDown()) {
                agreeCount++;
            }
        }
        
        return agreeCount >= master.getQuorum();
    }
    
    /**
     * 投票给领导者
     */
    public boolean voteForLeader(String masterName, String reqEpoch, String reqRunid) {
        MasterState master = masters.get(masterName);
        if (master == null) {
            return false;
        }
        
        long reqEpochLong = Long.parseLong(reqEpoch);
        
        // 如果请求的纪元更大，更新当前纪元
        if (reqEpochLong > currentEpoch) {
            currentEpoch = reqEpochLong;
        }
        
        // 如果已经投过票且纪元相同，检查是否投给了同一个哨兵
        if (master.getLeader() != null && master.getLeaderEpoch() == reqEpochLong) {
            return master.getLeader().equals(reqRunid);
        }
        
        // 投票
        master.setLeader(reqRunid);
        master.setLeaderEpoch(reqEpochLong);
        
        logger.info("Sentinel {} voted for {} as leader for master {} in epoch {}", 
                   sentinelId, reqRunid, masterName, reqEpochLong);
        
        return true;
    }
    
    // Getters
    
    public String getSentinelId() { return sentinelId; }
    public SentinelConfig getConfig() { return config; }
    public SentinelState getState() { return state; }
    public boolean isRunning() { return running; }
    public long getCurrentEpoch() { return currentEpoch; }
    public void incrementEpoch() { currentEpoch++; }
    public SentinelStats getStats() { return stats; }
    public NodeMonitor getNodeMonitor() { return nodeMonitor; }
    public HealthChecker getHealthChecker() { return healthChecker; }
    public FailoverManager getFailoverManager() { return failoverManager; }
}
