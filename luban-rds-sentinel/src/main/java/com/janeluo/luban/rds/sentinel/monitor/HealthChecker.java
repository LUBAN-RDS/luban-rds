package com.janeluo.luban.rds.sentinel.monitor;

import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.NodeState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SentinelInstance;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 健康检查器
 * 检查节点健康状态，判断主观下线和客观下线
 */
public class HealthChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(HealthChecker.class);
    
    private final Sentinel sentinel;
    
    public HealthChecker(Sentinel sentinel) {
        this.sentinel = sentinel;
    }
    
    /**
     * 检查所有节点健康状态
     */
    public void checkAllNodes() {
        for (MasterState master : sentinel.getMasters().values()) {
            checkMasterHealth(master);
            checkSlavesHealth(master);
            checkSentinelsHealth(master);
        }
    }
    
    /**
     * 检查主节点健康状态
     */
    private void checkMasterHealth(MasterState master) {
        long now = System.currentTimeMillis();
        long lastPong = master.getLastPongTime();
        long downAfter = master.getDownAfterMilliseconds();
        
        boolean wasDown = master.isSDown();
        boolean isDown = (now - lastPong) > downAfter;
        
        if (isDown && !wasDown) {
            // 主观下线
            markMasterSDown(master);
        } else if (!isDown && wasDown) {
            // 恢复正常
            markMasterNormal(master);
        }
        
        // 如果主观下线，检查客观下线
        if (master.isSDown()) {
            checkObjectiveDown(master);
        }
    }
    
    /**
     * 标记主节点主观下线
     */
    private void markMasterSDown(MasterState master) {
        master.setState(NodeState.S_DOWN);
        master.addFlag(MasterState.FLAG_S_DOWN);
        
        logger.warn("Sentinel {} detected master {} is subjectively down (s_down)", 
                   sentinel.getSentinelId(), master.getName());
        
        sentinel.getStats().incrementSDownEvents();
    }
    
    /**
     * 标记主节点正常
     */
    private void markMasterNormal(MasterState master) {
        master.setState(NodeState.NORMAL);
        master.removeFlag(MasterState.FLAG_S_DOWN);
        master.removeFlag(MasterState.FLAG_O_DOWN);
        
        logger.info("Sentinel {} detected master {} is back to normal", 
                   sentinel.getSentinelId(), master.getName());
    }
    
    /**
     * 检查客观下线
     */
    private void checkObjectiveDown(MasterState master) {
        // 统计认为主节点下线的哨兵数量
        int downVotes = 1; // 自己的投票
        
        for (SentinelInstance si : master.getSentinels().values()) {
            if (si.isVotedMasterDown()) {
                downVotes++;
            }
        }
        
        boolean wasODown = master.isODown();
        boolean isODown = downVotes >= master.getQuorum();
        
        if (isODown && !wasODown) {
            // 客观下线
            markMasterODown(master, downVotes);
        } else if (!isODown && wasODown) {
            // 恢复正常
            markMasterNormal(master);
        }
    }
    
    /**
     * 标记主节点客观下线
     */
    private void markMasterODown(MasterState master, int downVotes) {
        master.setState(NodeState.O_DOWN);
        master.addFlag(MasterState.FLAG_O_DOWN);
        
        logger.warn("Sentinel {} detected master {} is objectively down (o_down), " +
                   "votes: {}/{}", 
                   sentinel.getSentinelId(), master.getName(), 
                   downVotes, master.getQuorum());
        
        sentinel.getStats().incrementODownEvents();
        
        // 尝试启动故障转移
        if (!master.isFailoverInProgress()) {
            sentinel.getFailoverManager().startFailover(master.getName());
        }
    }
    
    /**
     * 检查从节点健康状态
     */
    private void checkSlavesHealth(MasterState master) {
        long now = System.currentTimeMillis();
        long downAfter = master.getDownAfterMilliseconds();
        
        for (SlaveState slave : master.getSlaves().values()) {
            long lastPong = slave.getLastPongTime();
            boolean wasDown = slave.isSDown();
            boolean isDown = (now - lastPong) > downAfter;
            
            if (isDown && !wasDown) {
                slave.setState(NodeState.S_DOWN);
                slave.setOnline(false);
                logger.warn("Sentinel {} detected slave {} is subjectively down", 
                           sentinel.getSentinelId(), slave.getSlaveId());
            } else if (!isDown && wasDown) {
                slave.setState(NodeState.NORMAL);
                slave.setOnline(true);
                logger.info("Sentinel {} detected slave {} is back to normal", 
                           sentinel.getSentinelId(), slave.getSlaveId());
            }
        }
    }
    
    /**
     * 检查其他哨兵健康状态
     */
    private void checkSentinelsHealth(MasterState master) {
        long now = System.currentTimeMillis();
        long timeout = sentinel.getConfig().getDownAfterMilliseconds() * 2;
        
        // 移除长时间无响应的哨兵
        master.getSentinels().entrySet().removeIf(entry -> {
            SentinelInstance si = entry.getValue();
            if ((now - si.getLastHelloTime()) > timeout) {
                logger.info("Sentinel {} removed dead sentinel {} for master {}", 
                           sentinel.getSentinelId(), si.getSentinelId(), master.getName());
                return true;
            }
            return false;
        });
    }
    
    /**
     * 检查特定主节点是否可达
     */
    public boolean isMasterReachable(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        return (now - master.getLastPongTime()) <= master.getDownAfterMilliseconds();
    }
    
    /**
     * 获取主节点延迟
     */
    public long getMasterLatency(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return -1;
        }
        
        return System.currentTimeMillis() - master.getLastPongTime();
    }
    
    /**
     * 获取主节点统计信息
     */
    public String getMasterStats(String masterName) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return null;
        }
        
        StringBuilder stats = new StringBuilder();
        stats.append("name:").append(master.getName()).append("\r\n");
        stats.append("ip:").append(master.getHost()).append("\r\n");
        stats.append("port:").append(master.getPort()).append("\r\n");
        stats.append("runid:").append(master.getReplId() != null ? master.getReplId() : "?").append("\r\n");
        stats.append("flags:").append(getFlagsString(master)).append("\r\n");
        stats.append("link-pending-commands:0\r\n");
        stats.append("link-refcount:1\r\n");
        stats.append("last-ping-sent:").append(
            (System.currentTimeMillis() - master.getLastPingTime()) / 1000).append("\r\n");
        stats.append("last-ok-ping-reply:").append(
            (System.currentTimeMillis() - master.getLastOkPingReply()) / 1000).append("\r\n");
        stats.append("last-ping-reply:").append(
            (System.currentTimeMillis() - master.getLastPingReply()) / 1000).append("\r\n");
        stats.append("down-after-milliseconds:").append(master.getDownAfterMilliseconds()).append("\r\n");
        stats.append("info-refresh:").append(
            (System.currentTimeMillis() - master.getLastPongTime()) / 1000).append("\r\n");
        stats.append("role-reported:master\r\n");
        stats.append("role-reported-time:").append(System.currentTimeMillis()).append("\r\n");
        stats.append("config-epoch:0\r\n");
        stats.append("num-slaves:").append(master.getSlaves().size()).append("\r\n");
        stats.append("num-other-sentinels:").append(master.getSentinels().size()).append("\r\n");
        stats.append("quorum:").append(master.getQuorum()).append("\r\n");
        stats.append("failover-timeout:").append(master.getFailoverTimeout()).append("\r\n");
        stats.append("parallel-syncs:").append(master.getParallelSyncs()).append("\r\n");
        
        if (master.isFailoverInProgress()) {
            stats.append("failover-state:").append(master.getFailoverState().getName()).append("\r\n");
            stats.append("failover-epoch:").append(master.getFailoverEpoch()).append("\r\n");
            stats.append("failover-start-time:").append(master.getFailoverStartTime()).append("\r\n");
        }
        
        return stats.toString();
    }
    
    /**
     * 获取标志字符串
     */
    private String getFlagsString(MasterState master) {
        StringBuilder flags = new StringBuilder();
        
        if (master.isODown()) {
            if (flags.length() > 0) flags.append(",");
            flags.append("o_down");
        }
        if (master.isSDown()) {
            if (flags.length() > 0) flags.append(",");
            flags.append("s_down");
        }
        if (master.isFailoverInProgress()) {
            if (flags.length() > 0) flags.append(",");
            flags.append("failover_in_progress");
        }
        
        return flags.length() > 0 ? flags.toString() : "master";
    }
}
