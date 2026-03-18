package com.janeluo.luban.rds.sentinel.failover;

import com.janeluo.luban.rds.sentinel.core.FailoverState;
import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 故障转移过程
 * 跟踪单个故障转移的状态和进度
 */
public class FailoverProcess {
    
    private static final Logger logger = LoggerFactory.getLogger(FailoverProcess.class);
    
    private final Sentinel sentinel;
    private final String masterName;
    private final long startTime;
    
    private volatile FailoverState state = FailoverState.NONE;
    private volatile String selectedSlave;
    private volatile String promotedSlave;
    private volatile int epoch;
    
    private final AtomicLong lastStateChangeTime = new AtomicLong(System.currentTimeMillis());
    private volatile int reconfiguredSlaves = 0;
    private volatile boolean completed = false;
    private volatile boolean cancelled = false;
    
    public FailoverProcess(Sentinel sentinel, String masterName) {
        this.sentinel = sentinel;
        this.masterName = masterName;
        this.startTime = System.currentTimeMillis();
        this.epoch = (int) sentinel.getCurrentEpoch();
    }
    
    /**
     * 获取故障转移状态
     */
    public FailoverState getState() {
        return state;
    }
    
    /**
     * 设置故障转移状态
     */
    public void setState(FailoverState newState) {
        FailoverState oldState = this.state;
        this.state = newState;
        this.lastStateChangeTime.set(System.currentTimeMillis());
        
        logger.info("Failover for master {} state changed: {} -> {}", 
                   masterName, oldState.getName(), newState.getName());
    }
    
    /**
     * 获取运行时间（毫秒）
     */
    public long getRunTime() {
        return System.currentTimeMillis() - startTime;
    }
    
    /**
     * 检查是否超时
     */
    public boolean isTimeout() {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return true;
        }
        
        return getRunTime() > master.getFailoverTimeout();
    }
    
    /**
     * 获取上次状态变更时间
     */
    public long getLastStateChangeTime() {
        return lastStateChangeTime.get();
    }
    
    /**
     * 获取在当前状态的持续时间
     */
    public long getStateDuration() {
        return System.currentTimeMillis() - lastStateChangeTime.get();
    }
    
    /**
     * 选择从节点
     */
    public void selectSlave(String slaveId) {
        this.selectedSlave = slaveId;
        setState(FailoverState.SELECT_SLAVE);
        
        logger.info("Selected slave {} for failover of master {}", slaveId, masterName);
    }
    
    /**
     * 提升从节点
     */
    public void promoteSlave(String slaveId) {
        this.promotedSlave = slaveId;
        setState(FailoverState.PROMOTE_SLAVE);
        
        logger.info("Promoting slave {} for master {}", slaveId, masterName);
    }
    
    /**
     * 开始重新配置从节点
     */
    public void startReconfSlaves() {
        setState(FailoverState.RECONF_SLAVES);
        this.reconfiguredSlaves = 0;
    }
    
    /**
     * 增加已重新配置的从节点计数
     */
    public void incrementReconfiguredSlaves() {
        this.reconfiguredSlaves++;
    }
    
    /**
     * 完成故障转移
     */
    public void complete() {
        this.completed = true;
        setState(FailoverState.FAILOVER_DONE);
        
        logger.info("Failover completed for master {}, promoted slave: {}", 
                   masterName, promotedSlave);
    }
    
    /**
     * 取消故障转移
     */
    public void cancel() {
        this.cancelled = true;
        setState(FailoverState.NONE);
        
        logger.warn("Failover cancelled for master {}", masterName);
    }
    
    /**
     * 检查是否完成
     */
    public boolean isCompleted() {
        return completed;
    }
    
    /**
     * 检查是否取消
     */
    public boolean isCancelled() {
        return cancelled;
    }
    
    /**
     * 获取进度百分比
     */
    public int getProgress() {
        switch (state) {
            case NONE:
                return 0;
            case WAIT_START:
                return 10;
            case SELECT_SLAVE:
                return 30;
            case PROMOTE_SLAVE:
                return 50;
            case RECONF_SLAVES:
                return 70 + (reconfiguredSlaves * 5);
            case FAILOVER_DONE:
                return 100;
            default:
                return 0;
        }
    }
    
    /**
     * 获取故障转移状态信息
     */
    public String getStatusInfo() {
        StringBuilder info = new StringBuilder();
        
        info.append("master_name:").append(masterName).append("\r\n");
        info.append("failover_state:").append(state.getName()).append("\r\n");
        info.append("failover_epoch:").append(epoch).append("\r\n");
        info.append("failover_start_time:").append(startTime).append("\r\n");
        info.append("failover_run_time:").append(getRunTime()).append("\r\n");
        info.append("failover_progress:").append(getProgress()).append("%\r\n");
        
        if (selectedSlave != null) {
            info.append("selected_slave:").append(selectedSlave).append("\r\n");
        }
        if (promotedSlave != null) {
            info.append("promoted_slave:").append(promotedSlave).append("\r\n");
        }
        
        info.append("reconfigured_slaves:").append(reconfiguredSlaves).append("\r\n");
        info.append("completed:").append(completed).append("\r\n");
        info.append("cancelled:").append(cancelled).append("\r\n");
        
        return info.toString();
    }
    
    // Getters
    
    public String getMasterName() { return masterName; }
    public long getStartTime() { return startTime; }
    public String getSelectedSlave() { return selectedSlave; }
    public String getPromotedSlave() { return promotedSlave; }
    public int getEpoch() { return epoch; }
    public int getReconfiguredSlaves() { return reconfiguredSlaves; }
}
