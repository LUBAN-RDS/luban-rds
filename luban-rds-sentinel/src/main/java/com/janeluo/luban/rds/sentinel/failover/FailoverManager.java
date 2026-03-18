package com.janeluo.luban.rds.sentinel.failover;

import com.janeluo.luban.rds.sentinel.core.FailoverState;
import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import com.janeluo.luban.rds.sentinel.monitor.QuorumChecker;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 故障转移管理器
 * 管理故障转移流程
 */
public class FailoverManager {
    
    private static final Logger logger = LoggerFactory.getLogger(FailoverManager.class);
    
    private final Sentinel sentinel;
    private final SlaveElection slaveElection;
    private final QuorumChecker quorumChecker;
    private final ScheduledExecutorService scheduler;
    
    public FailoverManager(Sentinel sentinel) {
        this.sentinel = sentinel;
        this.slaveElection = new SlaveElection(sentinel);
        this.quorumChecker = new QuorumChecker(sentinel);
        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }
    
    /**
     * 启动故障转移
     */
    public synchronized void startFailover(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            logger.warn("Cannot start failover: master {} not found", masterName);
            return;
        }
        
        // 检查是否可以开始故障转移
        if (!quorumChecker.canStartFailover(master)) {
            logger.info("Cannot start failover for master {}: conditions not met", masterName);
            return;
        }
        
        // 检查是否已经在进行中
        if (master.isFailoverInProgress()) {
            logger.info("Failover already in progress for master {}", masterName);
            return;
        }
        
        logger.info("Starting failover for master {}", masterName);
        
        // 增加纪元
        sentinel.incrementEpoch();
        master.setFailoverEpoch((int) sentinel.getCurrentEpoch());
        master.setFailoverStartTime(System.currentTimeMillis());
        master.setFailoverState(FailoverState.WAIT_START);
        master.addFlag(MasterState.FLAG_FAILOVER_IN_PROGRESS);
        
        // 开始故障转移流程
        executeFailover(master);
    }
    
    /**
     * 执行故障转移流程
     */
    private void executeFailover(MasterState master) {
        scheduler.submit(() -> {
            try {
                // 阶段 1: 等待并尝试成为领导者
                if (!tryBecomeLeader(master)) {
                    logger.info("Failed to become leader for failover of master {}", master.getName());
                    resetFailoverState(master);
                    return;
                }
                
                // 阶段 2: 选择新的主节点
                SlaveState newMaster = selectNewMaster(master);
                if (newMaster == null) {
                    logger.error("No suitable slave found for failover of master {}", master.getName());
                    resetFailoverState(master);
                    return;
                }
                
                master.setFailoverSlave(newMaster.getSlaveId());
                master.setFailoverState(FailoverState.SELECT_SLAVE);
                
                // 阶段 3: 提升从节点为主节点
                if (!promoteSlave(master, newMaster)) {
                    logger.error("Failed to promote slave {} for master {}", 
                               newMaster.getSlaveId(), master.getName());
                    resetFailoverState(master);
                    return;
                }
                
                master.setFailoverState(FailoverState.PROMOTE_SLAVE);
                
                // 阶段 4: 重新配置其他从节点
                reconfigureSlaves(master, newMaster);
                
                master.setFailoverState(FailoverState.RECONF_SLAVES);
                
                // 阶段 5: 更新主节点信息
                updateMasterInfo(master, newMaster);
                
                master.setFailoverState(FailoverState.FAILOVER_DONE);
                master.removeFlag(MasterState.FLAG_FAILOVER_IN_PROGRESS);
                master.addFlag(MasterState.FLAG_PROMOTED);
                
                logger.info("Failover completed for master {}: new master is {}:{}", 
                           master.getName(), newMaster.getHost(), newMaster.getPort());
                
                sentinel.getStats().incrementFailoverEvents();
                
            } catch (Exception e) {
                logger.error("Error during failover for master {}", master.getName(), e);
                resetFailoverState(master);
            }
        });
    }
    
    /**
     * 尝试成为故障转移的领导者
     */
    private boolean tryBecomeLeader(MasterState master) {
        // 简化实现：直接成为领导者
        // 实际实现需要通过投票机制
        master.setLeader(sentinel.getSentinelId());
        master.setLeaderEpoch(sentinel.getCurrentEpoch());
        
        logger.info("Sentinel {} became leader for failover of master {}", 
                   sentinel.getSentinelId(), master.getName());
        
        return true;
    }
    
    /**
     * 选择新的主节点
     */
    private SlaveState selectNewMaster(MasterState master) {
        List<SlaveState> candidates = slaveElection.getCandidateSlaves(master);
        
        if (candidates.isEmpty()) {
            logger.warn("No candidate slaves available for master {}", master.getName());
            return null;
        }
        
        SlaveState selected = slaveElection.electBestSlave(candidates);
        
        if (selected != null) {
            logger.info("Selected slave {} as new master for {}", 
                       selected.getSlaveId(), master.getName());
        }
        
        return selected;
    }
    
    /**
     * 提升从节点为主节点
     */
    private boolean promoteSlave(MasterState master, SlaveState slave) {
        Channel channel = sentinel.getNodeMonitor().getNodeChannel(slave.getHost(), slave.getPort());
        
        if (channel == null || !channel.isActive()) {
            logger.error("No active connection to slave {}", slave.getSlaveId());
            return false;
        }
        
        try {
            // 发送 SLAVEOF NO ONE 命令
            String slaveofCmd = "*3\r\n$6\r\nSLAVEOF\r\n$2\r\nNO\r\n$3\r\nONE\r\n";
            channel.writeAndFlush(Unpooled.copiedBuffer(slaveofCmd, CharsetUtil.UTF_8));
            
            logger.info("Sent SLAVEOF NO ONE to slave {}", slave.getSlaveId());
            
            // 等待响应
            Thread.sleep(1000);
            
            return true;
        } catch (Exception e) {
            logger.error("Failed to promote slave {}", slave.getSlaveId(), e);
            return false;
        }
    }
    
    /**
     * 重新配置其他从节点
     */
    private void reconfigureSlaves(MasterState master, SlaveState newMaster) {
        int parallelSyncs = master.getParallelSyncs();
        int synced = 0;
        
        for (SlaveState slave : master.getSlaves().values()) {
            if (slave.getSlaveId().equals(newMaster.getSlaveId())) {
                continue;
            }
            
            // 控制并行同步数量
            if (synced >= parallelSyncs) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                synced = 0;
            }
            
            Channel channel = sentinel.getNodeMonitor().getNodeChannel(slave.getHost(), slave.getPort());
            
            if (channel != null && channel.isActive()) {
                try {
                    // 发送 SLAVEOF 命令
                    String slaveofCmd = String.format("*4\r\n$6\r\nSLAVEOF\r\n$%d\r\n%s\r\n$%d\r\n%d\r\n",
                            newMaster.getHost().length(), newMaster.getHost(),
                            String.valueOf(newMaster.getPort()).length(), newMaster.getPort());
                    
                    channel.writeAndFlush(Unpooled.copiedBuffer(slaveofCmd, CharsetUtil.UTF_8));
                    
                    logger.info("Sent SLAVEOF {} {} to slave {}", 
                               newMaster.getHost(), newMaster.getPort(), slave.getSlaveId());
                    
                    synced++;
                } catch (Exception e) {
                    logger.error("Failed to reconfigure slave {}", slave.getSlaveId(), e);
                }
            }
        }
    }
    
    /**
     * 更新主节点信息
     */
    private void updateMasterInfo(MasterState master, SlaveState newMaster) {
        master.setHost(newMaster.getHost());
        master.setPort(newMaster.getPort());
        master.setReplId(newMaster.getReplId());
        master.setReplOffset(newMaster.getReplOffset());
        
        // 移除新主节点从从节点列表
        master.removeSlave(newMaster.getSlaveId());
        
        // 重置状态
        master.setState(null);
        master.removeFlag(MasterState.FLAG_S_DOWN);
        master.removeFlag(MasterState.FLAG_O_DOWN);
        
        logger.info("Updated master {} to {}:{}", master.getName(), master.getHost(), master.getPort());
    }
    
    /**
     * 重置故障转移状态
     */
    private void resetFailoverState(MasterState master) {
        master.setFailoverState(FailoverState.NONE);
        master.setFailoverSlave(null);
        master.removeFlag(MasterState.FLAG_FAILOVER_IN_PROGRESS);
    }
    
    /**
     * 手动触发故障转移
     */
    public void forceFailover(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            logger.warn("Cannot force failover: master {} not found", masterName);
            return;
        }
        
        logger.info("Force failover requested for master {}", masterName);
        startFailover(masterName);
    }
    
    /**
     * 取消故障转移
     */
    public void cancelFailover(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return;
        }
        
        if (master.isFailoverInProgress()) {
            resetFailoverState(master);
            logger.info("Failover cancelled for master {}", masterName);
        }
    }
    
    /**
     * 关闭
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
