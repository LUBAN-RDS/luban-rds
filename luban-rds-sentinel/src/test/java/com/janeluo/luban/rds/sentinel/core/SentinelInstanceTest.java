package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelInstance 测试类
 */
class SentinelInstanceTest {
    
    @Test
    void testSentinelInstance() {
        SentinelInstance si = new SentinelInstance("sentinel1", "127.0.0.1", 26379);
        
        assertEquals("sentinel1", si.getSentinelId());
        assertEquals("127.0.0.1", si.getHost());
        assertEquals(26379, si.getPort());
        assertFalse(si.isVotedMasterDown());
    }
    
    @Test
    void testSentinelInstanceVoting() {
        SentinelInstance si = new SentinelInstance("sentinel1", "127.0.0.1", 26379);
        
        si.setVotedMasterDown(true);
        assertTrue(si.isVotedMasterDown());
        
        si.setVotedLeader("leader-sentinel");
        assertEquals("leader-sentinel", si.getVotedLeader());
        
        si.setVotedLeaderEpoch(100);
        assertEquals(100, si.getVotedLeaderEpoch());
    }
}
