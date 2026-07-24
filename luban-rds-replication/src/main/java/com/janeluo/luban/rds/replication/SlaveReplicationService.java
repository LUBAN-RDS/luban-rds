package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 从节点复制服务
 * 
 * 管理从节点侧的复制逻辑，包括：
 * - 连接主节点
 * - 全量同步和部分同步
 * - RDB 数据加载
 * - 命令传播
 * - 只读模式管理
 */
public class SlaveReplicationService implements ReplicationCallback {
    
    private static final Logger logger = LoggerFactory.getLogger(SlaveReplicationService.class);
    
    private final RdsConfig config;
    private final SlaveReplicationClient client;
    private final AtomicReference<ReplicationState> state = new AtomicReference<>(ReplicationState.DISCONNECTED);
    
    // 复制统计
    private final AtomicLong masterReplOffset = new AtomicLong(0);
    private final AtomicLong slaveReplOffset = new AtomicLong(0);
    
    // 心跳调度器
    private ScheduledExecutorService heartbeatScheduler;
    
    // 主节点信息
    private volatile String masterHost;
    private volatile int masterPort;
    private volatile String masterReplId;
    private volatile long secondReplOffset = -1;
    
    // RDB 数据加载器
    private RdbDataLoader rdbDataLoader;
    private MemoryStore memoryStore;

    // 复制流应用器：解析并重放主节点传播的 RESP 命令流。
    // volatile：由 setMemoryStore（启动线程）写入，由 onCommandPropagation（Netty 事件循环）读取，
    // 需保证跨线程可见性。
    private volatile ReplicationStreamApplier streamApplier;
    
    // 只读模式管理器
    private final ReadOnlyModeManager readOnlyModeManager;
    
    // 传输进度跟踪
    private final TransferProgressTracker progressTracker;
    
    /**
     * 创建从节点复制服务
     *
     * @param config 配置
     */
    public SlaveReplicationService(RdsConfig config) {
        this.config = config;
        this.client = new SlaveReplicationClient(config, this);
        this.readOnlyModeManager = new ReadOnlyModeManager();
        this.progressTracker = new TransferProgressTracker();
        
        // 设置为从节点模式
        readOnlyModeManager.setSlave(true);
    }
    
    /**
     * 设置 RDB 持久化服务
     */
    public void setRdbPersistService(RdbPersistService rdbPersistService) {
        this.rdbDataLoader = new RdbDataLoader(rdbPersistService, 
            rdbPersistService.getDataDir());
        logger.info("RDB data loader initialized");
    }
    
    /**
     * 设置内存存储
     */
    public void setMemoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        this.streamApplier = new ReplicationStreamApplier(memoryStore);
        logger.info("Replication stream applier initialized with memory store");
    }
    
    /**
     * 启动复制服务
     */
    public synchronized void start() {
        if (state.get() != ReplicationState.DISCONNECTED) {
            return;
        }
        
        logger.info("启动从节点复制服务");
        
        // 解析主节点地址
        String replicaof = config.getReplicaof();
        if (replicaof == null || replicaof.isEmpty()) {
            logger.warn("未配置主节点地址");
            return;
        }
        
        String[] parts = replicaof.split(":");
        this.masterHost = parts[0].trim();
        this.masterPort = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 6379;
        
        // 启动客户端
        client.start();
        
        // 启动心跳
        startHeartbeat();
    }
    
    /**
     * 停止复制服务
     */
    public synchronized void stop() {
        logger.info("停止从节点复制服务");

        state.set(ReplicationState.DISCONNECTED);

        if (streamApplier != null) {
            streamApplier.close();
            streamApplier = null;
        }

        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            heartbeatScheduler = null;
        }

        client.stop();

        // 取消 RDB 加载
        if (rdbDataLoader != null && rdbDataLoader.isLoading()) {
            rdbDataLoader.cancelLoading();
        }
    }
    
    /**
     * 启动心跳
     */
    private void startHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
        }
        
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                if (isOnline()) {
                    // 发送 REPLCONF ACK
                    client.sendAck();
                }
            } catch (Exception e) {
                logger.error("心跳异常", e);
            }
        }, config.getReplPingSlavePeriod(), config.getReplPingSlavePeriod(), TimeUnit.SECONDS);
    }
    
    // ==================== ReplicationCallback 实现 ====================
    
    @Override
    public void onConnectionFailed(Throwable cause) {
        logger.error("连接主节点失败", cause);
        state.set(ReplicationState.ERROR);
    }
    
    @Override
    public void onHandshakeFailed(String error) {
        logger.error("握手失败: {}", error);
        state.set(ReplicationState.ERROR);
    }
    
    @Override
    public void onDisconnected() {
        logger.warn("与主节点断开连接");
        state.set(ReplicationState.DISCONNECTED);
    }
    
    @Override
    public void onFullSync(String replId, long offset) {
        logger.info("开始全量同步, replId: {}, offset: {}", replId, offset);
        this.masterReplId = replId;
        this.masterReplOffset.set(offset);
        state.set(ReplicationState.FULL_SYNC);
        
        // 开始 RDB 加载
        if (rdbDataLoader != null && memoryStore != null) {
            rdbDataLoader.startLoading(memoryStore, progressTracker);
        }
    }
    
    @Override
    public void onPartialSync(String replId, long offset) {
        logger.info("部分重同步, replId: {}, offset: {}", replId, offset);
        this.masterReplId = replId;
        this.masterReplOffset.set(offset);
        state.set(ReplicationState.PARTIAL_SYNC);
    }
    
    @Override
    public void onRdbData(ByteBuf data) {
        try {
            logger.debug("收到 RDB 数据: {} bytes", data.readableBytes());
            
            // 写入 RDB 加载器
            if (rdbDataLoader != null && rdbDataLoader.isLoading()) {
                rdbDataLoader.writeChunk(data);
            }
            
            // 更新偏移量
            slaveReplOffset.addAndGet(data.readableBytes());
            
            // 标记为加载 RDB
            if (state.get() == ReplicationState.FULL_SYNC) {
                state.set(ReplicationState.LOADING_RDB);
            }
        } finally {
            data.release();
        }
    }
    
    @Override
    public void onOnline() {
        logger.info("复制同步完成，进入在线状态");
        state.set(ReplicationState.ONLINE);
        
        // 完成 RDB 加载
        if (rdbDataLoader != null && rdbDataLoader.isLoading() && memoryStore != null) {
            rdbDataLoader.finishLoading(memoryStore);
        }
    }
    
    @Override
    public void onCommandPropagation(ByteBuf data) {
        if (streamApplier == null) {
            logger.warn("收到命令传播但复制流应用器未初始化，丢弃数据: {} bytes", data.readableBytes());
            data.release();
            return;
        }

        try {
            logger.debug("收到命令传播: {} bytes", data.readableBytes());

            streamApplier.applyData(data);
            slaveReplOffset.set(streamApplier.getAppliedOffset());

            // 标记为在线
            if (state.get() != ReplicationState.ONLINE) {
                state.set(ReplicationState.ONLINE);
                logger.info("复制同步完成，进入在线状态");
            }
        } catch (ReplicationApplyException e) {
            logger.error("应用复制命令流失败，触发断开重连", e);
            // applyData 内部已释放 data；触发断开重连以恢复一致性
            client.reconnect();
        }
    }
    
    @Override
    public String getReplId() {
        return masterReplId;
    }
    
    @Override
    public long getReplOffset() {
        return slaveReplOffset.get();
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 获取当前状态
     */
    public ReplicationState getState() {
        return state.get();
    }
    
    /**
     * 是否在线
     */
    public boolean isOnline() {
        return state.get() == ReplicationState.ONLINE;
    }
    
    /**
     * 是否只读
     */
    public boolean isReadOnly() {
        return readOnlyModeManager.isReadOnly();
    }
    
    /**
     * 设置只读模式
     */
    public void setReadOnly(boolean readOnly) {
        readOnlyModeManager.setReadOnly(readOnly);
    }
    
    /**
     * 检查命令是否应该被拦截
     */
    public String checkReadOnlyIntercept(String command) {
        return readOnlyModeManager.interceptWriteCommand(command);
    }
    
    /**
     * 获取复制信息
     */
    public String getReplicationInfo() {
        StringBuilder sb = new StringBuilder();
        
        sb.append("# Replication\r\n");
        sb.append("role:slave\r\n");
        sb.append("master_host:").append(masterHost != null ? masterHost : "").append("\r\n");
        sb.append("master_port:").append(masterPort).append("\r\n");
        sb.append("master_link_status:").append(isOnline() ? "up" : "down").append("\r\n");
        sb.append("master_sync_in_progress:").append(
            state.get() == ReplicationState.FULL_SYNC || state.get() == ReplicationState.PARTIAL_SYNC ? 1 : 0
        ).append("\r\n");
        sb.append("slave_repl_offset:").append(slaveReplOffset.get()).append("\r\n");
        sb.append("slave_priority:100\r\n");
        sb.append("slave_read_only:").append(readOnlyModeManager.isReadOnly() ? 1 : 0).append("\r\n");
        
        // 重连统计
        sb.append(client.getReconnectInfo());
        
        return sb.toString();
    }
    
    /**
     * 获取主节点地址
     */
    public String getMasterAddress() {
        if (masterHost != null) {
            return masterHost + ":" + masterPort;
        }
        return null;
    }
    
    /**
     * 获取复制客户端
     */
    public SlaveReplicationClient getClient() {
        return client;
    }
    
    /**
     * 获取主节点复制 ID
     */
    public String getMasterReplId() {
        return masterReplId;
    }
    
    /**
     * 获取传输进度跟踪器
     */
    public TransferProgressTracker getProgressTracker() {
        return progressTracker;
    }
    
    /**
     * 获取只读模式管理器
     */
    public ReadOnlyModeManager getReadOnlyModeManager() {
        return readOnlyModeManager;
    }
    
    /**
     * 手动触发重连
     */
    public void reconnect() {
        client.reconnect();
    }
}
