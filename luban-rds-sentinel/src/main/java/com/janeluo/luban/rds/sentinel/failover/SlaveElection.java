package com.janeluo.luban.rds.sentinel.failover;

import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.NodeState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SlaveState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 从节点选举
 * 选举新的主节点
 */
public class SlaveElection {
    
    private static final Logger logger = LoggerFactory.getLogger(SlaveElection.class);
    
    /**
     * 默认从节点优先级
     */
    private static final int DEFAULT_PRIORITY = 100;
    
    /**
     * 最低优先级阈值（超过此值的从节点不会被选中）
     */
    private static final int MIN_PRIORITY_THRESHOLD = 0;
    
    private final Sentinel sentinel;
    
    public SlaveElection(Sentinel sentinel) {
        this.sentinel = sentinel;
    }
    
    /**
     * 获取候选从节点列表
     */
    public List<SlaveState> getCandidateSlaves(MasterState master) {
        List<SlaveState> candidates = new ArrayList<>();
        
        for (SlaveState slave : master.getSlaves().values()) {
            if (isCandidateSlave(slave)) {
                candidates.add(slave);
            }
        }
        
        logger.debug("Found {} candidate slaves for master {}", candidates.size(), master.getName());
        
        return candidates;
    }
    
    /**
     * 检查从节点是否可以作为候选
     */
    private boolean isCandidateSlave(SlaveState slave) {
        // 1. 从节点必须在线
        if (!slave.isOnline()) {
            logger.debug("Slave {} is not online, skipping", slave.getSlaveId());
            return false;
        }
        
        // 2. 从节点不能是主观下线状态
        if (slave.getState() == NodeState.S_DOWN) {
            logger.debug("Slave {} is s_down, skipping", slave.getSlaveId());
            return false;
        }
        
        // 3. 从节点优先级必须有效
        if (slave.getPriority() <= MIN_PRIORITY_THRESHOLD) {
            logger.debug("Slave {} has invalid priority {}, skipping", 
                        slave.getSlaveId(), slave.getPriority());
            return false;
        }
        
        // 4. 从节点必须有有效的复制偏移量
        if (slave.getReplOffset() <= 0) {
            logger.debug("Slave {} has invalid repl offset {}, skipping", 
                        slave.getSlaveId(), slave.getReplOffset());
            return false;
        }
        
        return true;
    }
    
    /**
     * 选举最佳从节点
     */
    public SlaveState electBestSlave(List<SlaveState> candidates) {
        if (candidates.isEmpty()) {
            logger.warn("No candidates available for election");
            return null;
        }
        
        // 按照选举规则排序
        candidates.sort(new SlaveComparator());
        
        SlaveState selected = candidates.get(0);
        
        logger.info("Elected slave {} as best candidate: priority={}, offset={}, lag={}", 
                   selected.getSlaveId(), selected.getPriority(), 
                   selected.getReplOffset(), selected.getLag());
        
        return selected;
    }
    
    /**
     * 从节点比较器
     * 排序规则：
     * 1. 优先级越小越好（但必须 > 0）
     * 2. 复制偏移量越大越好
     * 3. 运行 ID 越小越好（作为最后的决胜条件）
     */
    private static class SlaveComparator implements Comparator<SlaveState> {
        
        @Override
        public int compare(SlaveState s1, SlaveState s2) {
            // 1. 比较优先级（越小越好）
            int priorityCompare = Integer.compare(s1.getPriority(), s2.getPriority());
            if (priorityCompare != 0) {
                return priorityCompare;
            }
            
            // 2. 比较复制偏移量（越大越好）
            int offsetCompare = Long.compare(s2.getReplOffset(), s1.getReplOffset());
            if (offsetCompare != 0) {
                return offsetCompare;
            }
            
            // 3. 比较运行 ID（越小越好，作为决胜条件）
            return s1.getSlaveId().compareTo(s2.getSlaveId());
        }
    }
    
    /**
     * 计算从节点得分
     * 用于调试和日志
     */
    public String calculateSlaveScore(SlaveState slave) {
        return String.format("priority=%d, offset=%d, lag=%d, score=%d",
                slave.getPriority(),
                slave.getReplOffset(),
                slave.getLag(),
                calculateScore(slave));
    }
    
    /**
     * 计算从节点得分
     */
    private long calculateScore(SlaveState slave) {
        // 简化的得分计算
        // 优先级权重：10000
        // 偏移量权重：1
        // 延迟权重：-100
        long score = slave.getPriority() * 10000L;
        score -= slave.getReplOffset();
        score += slave.getLag() * 100L;
        
        return score;
    }
    
    /**
     * 获取选举排名
     */
    public List<String> getElectionRanking(MasterState master) {
        List<SlaveState> candidates = getCandidateSlaves(master);
        candidates.sort(new SlaveComparator());
        
        List<String> ranking = new ArrayList<>();
        int rank = 1;
        
        for (SlaveState slave : candidates) {
            ranking.add(String.format("%d. %s - %s", 
                    rank++, slave.getSlaveId(), calculateSlaveScore(slave)));
        }
        
        return ranking;
    }
    
    /**
     * 检查从节点是否适合提升
     */
    public boolean isSuitableForPromotion(SlaveState slave) {
        if (!isCandidateSlave(slave)) {
            return false;
        }
        
        // 检查从节点是否与主节点保持同步
        // 这里简化处理，实际应该比较复制偏移量
        long maxLag = sentinel.getConfig().getFailoverTimeout() / 1000;
        
        return slave.getLag() <= maxLag;
    }
}
