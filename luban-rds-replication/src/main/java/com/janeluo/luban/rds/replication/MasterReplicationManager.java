package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 主节点复制管理器
 * 
 * 管理主节点的复制功能，包括：
 * - 从节点连接管理
 * - 全量同步和部分同步
 * - RDB 快照生成和传输
 * - 命令传播
 * - 复制延迟监控
 */
public class MasterReplicationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterReplicationManager.class);
    private static volatile MasterReplicationManager instance;
    
    private final List<SlaveInfo> slaves;
    private final Map<Channel, SlaveInfo> slaveChannelMap;
    private final ReplicationBacklog backlog;
    private String requirepass;
    
    private final AtomicInteger connectedSlaves = new AtomicInteger(0);
    private final AtomicLong syncFull = new AtomicLong(0);
    private final AtomicLong syncPartialOk = new AtomicLong(0);
    private final AtomicLong syncPartialErr = new AtomicLong(0);
    
    // RDB 快照生成器
    private RdbSnapshotGenerator snapshotGenerator;
    private MemoryStore memoryStore;
    
    // 传输进度跟踪
    private final Map<String, TransferProgressTracker> transferTrackers = new ConcurrentHashMap<>();
    
    // 异步执行器
    private final ExecutorService asyncExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "master-replication-async");
        t.setDaemon(true);
        return t;
    });
    
    private MasterReplicationManager(int backlogSize) {
        this.slaves = new CopyOnWriteArrayList<>();
        this.slaveChannelMap = new ConcurrentHashMap<>();
        this.backlog = new ReplicationBacklog(backlogSize);
    }
    
    public static MasterReplicationManager getInstance() {
        if (instance == null) {
            synchronized (MasterReplicationManager.class) {
                if (instance == null) {
                    instance = new MasterReplicationManager(ReplicationConstants.DEFAULT_BACKLOG_SIZE);
                }
            }
        }
        return instance;
    }
    
    public static synchronized void initialize(int backlogSize) {
        if (instance == null) {
            instance = new MasterReplicationManager(backlogSize);
        }
    }
    
    /**
     * 设置 RDB 持久化服务
     */
    public void setRdbPersistService(RdbPersistService rdbPersistService) {
        this.snapshotGenerator = new RdbSnapshotGenerator(rdbPersistService, 
            rdbPersistService.getDataDir());
        logger.info("RDB snapshot generator initialized");
    }
    
    /**
     * 设置 RDB 快照生成器（用于测试注入）
     */
    void setSnapshotGenerator(RdbSnapshotGenerator snapshotGenerator) {
        this.snapshotGenerator = snapshotGenerator;
    }
    
    /**
     * 设置内存存储
     */
    public void setMemoryStore(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }
    
    public void setRequirepass(String requirepass) { this.requirepass = requirepass; }
    
    public SlaveInfo addSlave(Channel channel) {
        SlaveInfo slave = new SlaveInfo(channel);
        slaves.add(slave);
        slaveChannelMap.put(channel, slave);
        connectedSlaves.incrementAndGet();
        logger.info("Slave connected: {}, total slaves: {}", slave.getSlaveId(), connectedSlaves.get());
        return slave;
    }
    
    public void removeSlave(Channel channel) {
        SlaveInfo slave = slaveChannelMap.remove(channel);
        if (slave != null) {
            slaves.remove(slave);
            connectedSlaves.decrementAndGet();
            
            // 移除传输进度跟踪器
            transferTrackers.remove(slave.getSlaveId());
            
            logger.info("Slave disconnected: {}, remaining slaves: {}", slave.getSlaveId(), connectedSlaves.get());
        }
    }
    
    public SlaveInfo getSlave(Channel channel) {
        return slaveChannelMap.get(channel);
    }
    
    public String handleReplconf(Channel channel, String[] args) {
        if (args.length < 2) return "-ERR wrong number of arguments for 'replconf' command\r\n";
        
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave == null) slave = addSlave(channel);
        
        String subcommand = args[1].toLowerCase();
        
        switch (subcommand) {
            case "listening-port":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf listening-port' command\r\n";
                try {
                    int port = Integer.parseInt(args[2]);
                    slave.setListeningPort(port);
                    logger.debug("Slave {} listening-port: {}", slave.getSlaveId(), port);
                    return "+OK\r\n";
                } catch (NumberFormatException e) {
                    return "-ERR invalid port number\r\n";
                }
                
            case "ip-address":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf ip-address' command\r\n";
                slave.setIp(args[2]);
                logger.debug("Slave {} ip-address: {}", slave.getSlaveId(), args[2]);
                return "+OK\r\n";
                
            case "capa":
                for (int i = 2; i < args.length; i++) slave.addCapability(args[i]);
                logger.debug("Slave {} capabilities: {}", slave.getSlaveId(), slave.getCapabilities());
                return "+OK\r\n";
                
            case "ack":
                if (args.length < 3) return "-ERR wrong number of arguments for 'replconf ack' command\r\n";
                try {
                    long offset = Long.parseLong(args[2]);
                    slave.updateOffset(offset);
                    slave.setState(ReplicationState.ONLINE);
                    slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
                    slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
                    
                    // 更新延迟统计
                    slave.updateReplicationLag(backlog.getMasterReplOffset());
                    
                    logger.trace("Slave {} ACK offset: {}", slave.getSlaveId(), offset);
                    return null;
                } catch (NumberFormatException e) {
                    return "-ERR invalid offset\r\n";
                }
                
            default:
                return "-ERR unknown subcommand: " + subcommand + "\r\n";
        }
    }
    
    public PsyncResponse handlePsync(Channel channel, String[] args) {
        if (args.length < 3) return new PsyncResponse("-ERR wrong number of arguments for 'psync' command\r\n", null);
        
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave == null) slave = addSlave(channel);
        
        String replId = args[1];
        long offset;
        try {
            offset = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            return new PsyncResponse("-ERR invalid offset\r\n", null);
        }
        
        if (requirepass != null && !requirepass.isEmpty() && !slave.isAuthenticated()) {
            return new PsyncResponse("-NOAUTH Authentication required\r\n", null);
        }
        
        if (!"?".equals(replId) && backlog.canPartialSync(replId, offset)) {
            slave.setReplId(replId);
            slave.updateOffset(offset);
            slave.setState(ReplicationState.ONLINE);
            slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE | SlaveInfo.SLAVE_FLAG_PARTIAL_SYNC);
            slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
            
            syncPartialOk.incrementAndGet();
            
            byte[] backlogData = backlog.getBacklogData(offset);
            String response = String.format("+CONTINUE %s\r\n", backlog.getReplId());
            
            logger.info("Partial sync accepted for slave {}, offset: {}, data length: {}", 
                       slave.getSlaveId(), offset, backlogData != null ? backlogData.length : 0);
            
            return new PsyncResponse(response, backlogData);
            
        } else {
            slave.setReplId(backlog.getReplId());
            slave.updateOffset(0);
            slave.setState(ReplicationState.FULL_SYNC);
            slave.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING | SlaveInfo.SLAVE_FLAG_FULL_SYNC);
            slave.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
            
            syncFull.incrementAndGet();
            
            String response = String.format("+FULLRESYNC %s %d\r\n", 
                                           backlog.getReplId(), backlog.getMasterReplOffset());
            
            logger.info("Full sync requested for slave {}, replid: {}, offset: {}", 
                       slave.getSlaveId(), backlog.getReplId(), backlog.getMasterReplOffset());
            
            return new PsyncResponse(response, null, true);
        }
    }
    
    /**
     * 执行全量同步
     * 
     * <p>RDB 传输完成后，在将从节点标记为 ONLINE 之前，会从 backlog 重放
     * 快照偏移量之后到当前偏移量之间的窗口期命令，避免该期间写入对从节点永久丢失。
     * 重放期间从节点保持 SYNCING 状态，{@link #propagateCommand} 的
     * {@code slave.isOnline()} 检查会跳过该从节点，不会并发直发导致乱序。
     * 
     * @param channel 从节点通道
     * @return 是否成功开始同步
     */
    public boolean performFullSync(Channel channel) {
        if (snapshotGenerator == null || memoryStore == null) {
            logger.error("RDB snapshot generator or memory store not initialized");
            return false;
        }
        
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave == null) {
            logger.error("Slave not found for channel: {}", channel);
            return false;
        }
        
        // 创建传输进度跟踪器
        TransferProgressTracker tracker = new TransferProgressTracker();
        transferTrackers.put(slave.getSlaveId(), tracker);
        tracker.startGenerating();
        
        // 异步执行 RDB 生成和传输
        asyncExecutor.submit(() -> {
            try {
                RdbSnapshotGenerator.SnapshotResult result = snapshotGenerator.generateAndTransfer(
                    memoryStore, channel, tracker, backlog);
                
                if (!result.isSuccess()) {
                    logger.error("Full sync failed for slave {}", slave.getSlaveId());
                    tracker.onError("RDB transfer failed");
                    return;
                }
                
                logger.info("Full sync RDB transfer completed for slave {}, transferred {} bytes, snapshotOffset: {}",
                           slave.getSlaveId(), result.getTransferredBytes(), result.getSnapshotOffset());
                
                // 重放窗口期命令：从 RDB 落盘时刻的 snapshotOffset 到当前 master offset
                if (!replayWindowCommands(slave, result.getSnapshotOffset(), channel)) {
                    // 窗口期数据已被 backlog 覆盖，无法重放 -> 从节点需重新发起全量同步
                    logger.warn("Window replay failed for slave {}, marking for re-sync", slave.getSlaveId());
                    slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
                    slave.setState(ReplicationState.FULL_SYNC);
                    tracker.onError("Window replay failed, need full re-sync");
                    return;
                }
                
                // 重放完成后才标记从节点为在线
                slave.setState(ReplicationState.ONLINE);
                slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
                slave.removeFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
                slave.updateReplicationLag(backlog.getMasterReplOffset());
                
            } catch (Exception e) {
                logger.error("Error during full sync for slave {}", slave.getSlaveId(), e);
                tracker.onError(e.getMessage());
            }
        });
        
        return true;
    }
    
    /**
     * 重放全量同步窗口期命令
     * 
     * <p>从 {@code snapshotOffset} 到当前 backlog 偏移量之间的命令会被重放给从节点。
     * 重放期间从节点保持 SYNCING 状态（调用方负责），避免 {@link #propagateCommand}
     * 并发直发导致命令乱序。
     * 
     * @param slave 从节点信息
     * @param snapshotOffset RDB 落盘时刻的 backlog 偏移量（-1 表示无快照偏移量，跳过重放）
     * @param channel 从节点通道
     * @return 是否重放成功（窗口期数据未被 backlog 覆盖）
     */
    private boolean replayWindowCommands(SlaveInfo slave, long snapshotOffset, Channel channel) {
        if (snapshotOffset < 0) {
            logger.debug("No snapshot offset, skipping window replay for slave {}", slave.getSlaveId());
            // 仍将 slave offset 对齐到当前 master offset
            slave.updateOffset(backlog.getMasterReplOffset());
            return true;
        }
        
        long currentOffset = backlog.getMasterReplOffset();
        if (currentOffset <= snapshotOffset) {
            logger.debug("No window commands to replay for slave {}, snapshotOffset: {}, currentOffset: {}",
                        slave.getSlaveId(), snapshotOffset, currentOffset);
            // 无窗口期命令，slave offset 对齐到 snapshotOffset（即当前 master offset）
            slave.updateOffset(currentOffset);
            return true;
        }
        
        byte[] windowData = backlog.getBacklogData(snapshotOffset);
        if (windowData == null) {
            // 窗口期数据已被 backlog 覆盖，无法重放
            logger.warn("Window data out of backlog range for slave {}, snapshotOffset: {}, currentOffset: {}, " +
                       "cannot replay - slave should re-initiate full sync",
                       slave.getSlaveId(), snapshotOffset, currentOffset);
            return false;
        }
        
        if (windowData.length == 0) {
            logger.debug("Empty window data for slave {}, snapshotOffset: {}", slave.getSlaveId(), snapshotOffset);
            slave.updateOffset(currentOffset);
            return true;
        }
        
        if (!channel.isActive()) {
            logger.warn("Channel inactive during window replay for slave {}", slave.getSlaveId());
            return false;
        }
        
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(windowData);
            channel.writeAndFlush(buf);
            // 更新从节点偏移量到重放结束位置
            slave.updateOffset(currentOffset);
            logger.info("Window replay completed for slave {}, replayed {} bytes, offset: {} -> {}",
                       slave.getSlaveId(), windowData.length, snapshotOffset, currentOffset);
            return true;
        } catch (Exception e) {
            logger.error("Failed to replay window commands for slave {}", slave.getSlaveId(), e);
            return false;
        }
    }
    
    /**
     * 获取传输进度
     */
    public TransferProgressTracker getTransferProgress(String slaveId) {
        return transferTrackers.get(slaveId);
    }
    
    public void propagateCommand(byte[] command) {
        if (slaves.isEmpty()) return;
        
        backlog.append(command);
        
        for (SlaveInfo slave : slaves) {
            if (slave.isOnline() && slave.getChannel().isActive()) {
                try {
                    ByteBuf buf = Unpooled.wrappedBuffer(command);
                    slave.getChannel().writeAndFlush(buf);
                    slave.incrementOffset(command.length);
                } catch (Exception e) {
                    logger.error("Failed to propagate command to slave: {}", slave.getSlaveId(), e);
                }
            }
        }
    }
    
    public void propagateCommand(String command) {
        propagateCommand(command.getBytes(CharsetUtil.UTF_8));
    }
    
    public void sendPingToSlaves() {
        if (slaves.isEmpty()) return;
        
        byte[] ping = "*1\r\n$4\r\nPING\r\n".getBytes(CharsetUtil.UTF_8);
        
        for (SlaveInfo slave : slaves) {
            if (slave.getChannel().isActive()) {
                try {
                    ByteBuf buf = Unpooled.wrappedBuffer(ping);
                    slave.getChannel().writeAndFlush(buf);
                } catch (Exception e) {
                    logger.error("Failed to send PING to slave: {}", slave.getSlaveId(), e);
                }
            }
        }
    }
    
    public void checkSlaveTimeout(long timeout) {
        long now = System.currentTimeMillis();
        
        Iterator<SlaveInfo> iterator = slaves.iterator();
        while (iterator.hasNext()) {
            SlaveInfo slave = iterator.next();
            
            if (now - slave.getLastInteractionTime() > timeout) {
                logger.warn("Slave {} timed out, last interaction: {} ms ago", 
                           slave.getSlaveId(), now - slave.getLastInteractionTime());
                
                if (slave.getChannel().isActive()) {
                    slave.getChannel().close();
                }
            }
        }
    }
    
    public void markSlaveAuthenticated(Channel channel) {
        SlaveInfo slave = slaveChannelMap.get(channel);
        if (slave != null) slave.setAuthenticated(true);
    }
    
    public ReplicationBacklog getBacklog() { return backlog; }
    public int getConnectedSlaves() { return connectedSlaves.get(); }
    public List<SlaveInfo> getSlaves() { return new ArrayList<>(slaves); }
    public long getSyncFull() { return syncFull.get(); }
    public long getSyncPartialOk() { return syncPartialOk.get(); }
    public long getSyncPartialErr() { return syncPartialErr.get(); }
    
    /**
     * 获取已同步到指定偏移量的从节点数量
     */
    public int getSyncedSlavesCount(long offset) {
        int count = 0;
        for (SlaveInfo slave : slaves) {
            if (slave.isOnline() && slave.getOffset() >= offset) {
                count++;
            }
        }
        return count;
    }
    
    public String getReplicationInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("# Replication\r\n");
        info.append("role:master\r\n");
        info.append("connected_slaves:").append(connectedSlaves.get()).append("\r\n");
        
        int index = 0;
        for (SlaveInfo slave : slaves) {
            info.append("slave").append(index++).append(":")
                .append("ip=").append(slave.getIp())
                .append(",port=").append(slave.getPort())
                .append(",state=").append(slave.getState().getName())
                .append(",offset=").append(slave.getOffset())
                .append(",lag=").append(slave.getReplicationLag())
                .append("\r\n");
        }
        
        info.append("master_replid:").append(backlog.getReplId()).append("\r\n");
        info.append("master_repl_offset:").append(backlog.getMasterReplOffset()).append("\r\n");
        info.append(backlog.getInfo());
        
        info.append("sync_full:").append(syncFull.get()).append("\r\n");
        info.append("sync_partial_ok:").append(syncPartialOk.get()).append("\r\n");
        info.append("sync_partial_err:").append(syncPartialErr.get()).append("\r\n");
        
        return info.toString();
    }
    
    /**
     * 关闭管理器
     */
    public void shutdown() {
        logger.info("Shutting down master replication manager...");

        asyncExecutor.shutdown();

        for (SlaveInfo slave : slaves) {
            if (slave.getChannel().isActive()) {
                slave.getChannel().close();
            }
        }

        slaves.clear();
        slaveChannelMap.clear();
        transferTrackers.clear();

        // 重置已连接从节点计数器，避免清空列表后计数器与实际 slave 数量不一致
        // （addSlave 自增、removeSlave 自减，clear 路径必须同步归零）
        connectedSlaves.set(0);

        logger.info("Master replication manager shutdown completed");
    }
    
    public static class PsyncResponse {
        private final String response;
        private final byte[] backlogData;
        private final boolean needRdb;
        
        public PsyncResponse(String response, byte[] backlogData) {
            this(response, backlogData, false);
        }
        
        public PsyncResponse(String response, byte[] backlogData, boolean needRdb) {
            this.response = response;
            this.backlogData = backlogData;
            this.needRdb = needRdb;
        }
        
        public String getResponse() { return response; }
        public byte[] getBacklogData() { return backlogData; }
        public boolean isNeedRdb() { return needRdb; }
    }
}
