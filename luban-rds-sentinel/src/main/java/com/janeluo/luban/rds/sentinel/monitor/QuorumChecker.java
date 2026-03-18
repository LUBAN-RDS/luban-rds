package com.janeluo.luban.rds.sentinel.monitor;

import com.janeluo.luban.rds.sentinel.core.MasterState;
import com.janeluo.luban.rds.sentinel.core.Sentinel;
import com.janeluo.luban.rds.sentinel.core.SentinelInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 仲裁检查器
 * 检查是否达到故障转移的仲裁数量
 */
public class QuorumChecker {
    
    private static final Logger logger = LoggerFactory.getLogger(QuorumChecker.class);
    
    private final Sentinel sentinel;
    
    public QuorumChecker(Sentinel sentinel) {
        this.sentinel = sentinel;
    }
    
    /**
     * 检查是否达到仲裁
     */
    public boolean checkQuorum(MasterState master) {
        int agreeCount = countVotesForDown(master);
        boolean result = agreeCount >= master.getQuorum();
        
        logger.debug("Quorum check for master {}: {}/{} votes, result: {}", 
                    master.getName(), agreeCount, master.getQuorum(), result);
        
        return result;
    }
    
    /**
     * 统计认为主节点下线的投票数
     */
    public int countVotesForDown(MasterState master) {
        int count = 1; // 自己的投票
        
        for (SentinelInstance si : master.getSentinels().values()) {
            if (si.isVotedMasterDown()) {
                count++;
            }
        }
        
        return count;
    }
    
    /**
     * 检查是否可以开始故障转移
     */
    public boolean canStartFailover(MasterState master) {
        // 1. 主节点必须客观下线
        if (!master.isODown()) {
            logger.debug("Cannot start failover: master {} is not O_DOWN", master.getName());
            return false;
        }
        
        // 2. 必须达到仲裁数量
        if (!checkQuorum(master)) {
            logger.debug("Cannot start failover: quorum not reached for master {}", master.getName());
            return false;
        }
        
        // 3. 没有其他故障转移在进行
        if (master.isFailoverInProgress()) {
            logger.debug("Cannot start failover: failover already in progress for master {}", 
                        master.getName());
            return false;
        }
        
        return true;
    }
    
    /**
     * 检查是否可以成为故障转移的领导者
     */
    public boolean canBeFailoverLeader(MasterState master) {
        // 统计投票给当前哨兵的数量
        int votesForMe = 1; // 自己投给自己
        
        for (SentinelInstance si : master.getSentinels().values()) {
            if (sentinel.getSentinelId().equals(si.getVotedLeader())) {
                votesForMe++;
            }
        }
        
        // 需要获得多数票
        int majority = (master.getSentinels().size() + 2) / 2; // +1 for self, +1 for majority
        boolean result = votesForMe > majority;
        
        logger.debug("Leadership check for master {}: {}/{} votes for me, result: {}", 
                    master.getName(), votesForMe, majority + 1, result);
        
        return result;
    }
    
    /**
     * 获取所有同意主节点下线的哨兵列表
     */
    public List<SentinelInstance> getSentinelsAgreeDown(MasterState master) {
        List<SentinelInstance> result = new ArrayList<>();
        
        for (SentinelInstance si : master.getSentinels().values()) {
            if (si.isVotedMasterDown()) {
                result.add(si);
            }
        }
        
        return result;
    }
    
    /**
     * 获取投票给指定领导者的哨兵列表
     */
    public List<SentinelInstance> getSentinelsVotedFor(MasterState master, String leaderId) {
        List<SentinelInstance> result = new ArrayList<>();
        
        for (SentinelInstance si : master.getSentinels().values()) {
            if (leaderId.equals(si.getVotedLeader())) {
                result.add(si);
            }
        }
        
        return result;
    }
    
    /**
     * 更新其他哨兵的下线投票状态
     */
    public void updateSentinelVote(String masterName, String sentinelId, boolean votedDown) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return;
        }
        
        SentinelInstance si = master.getSentinel(sentinelId);
        if (si != null) {
            si.setVotedMasterDown(votedDown);
            logger.debug("Updated sentinel {} vote for master {}: {}", 
                        sentinelId, masterName, votedDown);
        }
    }
    
    /**
     * 更新其他哨兵的领导者投票
     */
    public void updateSentinelLeaderVote(String masterName, String sentinelId, 
                                         String leaderId, long epoch) {
        MasterState master = sentinel.getMasterState(masterName);
        if (master == null) {
            return;
        }
        
        SentinelInstance si = master.getSentinel(sentinelId);
        if (si != null) {
            si.setVotedLeader(leaderId);
            si.setVotedLeaderEpoch(epoch);
            logger.debug("Updated sentinel {} leader vote for master {}: leader={}, epoch={}", 
                        sentinelId, masterName, leaderId, epoch);
        }
    }
    
    /**
     * 获取仲裁状态信息
     */
    public String getQuorumStatus(MasterState master) {
        int downVotes = countVotesForDown(master);
        int totalSentinels = master.getSentinels().size() + 1;
        int quorum = master.getQuorum();
        
        return String.format("votes=%d, sentinels=%d, quorum=%d, status=%s",
                downVotes, totalSentinels, quorum,
                downVotes >= quorum ? "QUORUM_REACHED" : "QUORUM_NOT_REACHED");
    }
}
