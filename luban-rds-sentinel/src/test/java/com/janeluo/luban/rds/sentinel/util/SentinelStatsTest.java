package com.janeluo.luban.rds.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelStats 测试类
 */
class SentinelStatsTest {
    
    @Test
    void testInitialStats() {
        SentinelStats stats = new SentinelStats();
        
        assertEquals(0, stats.getMastersMonitored());
        assertEquals(0, stats.getSDownEvents());
        assertEquals(0, stats.getODownEvents());
        assertEquals(0, stats.getFailoverEvents());
        assertEquals(0, stats.getPingSent());
        assertEquals(0, stats.getPongReceived());
    }
    
    @Test
    void testIncrementStats() {
        SentinelStats stats = new SentinelStats();
        
        stats.incrementMastersMonitored();
        assertEquals(1, stats.getMastersMonitored());
        
        stats.incrementSDownEvents();
        assertEquals(1, stats.getSDownEvents());
        
        stats.incrementODownEvents();
        assertEquals(1, stats.getODownEvents());
        
        stats.incrementFailoverEvents();
        assertEquals(1, stats.getFailoverEvents());
        
        stats.incrementPingSent();
        assertEquals(1, stats.getPingSent());
        
        stats.incrementPongReceived();
        assertEquals(1, stats.getPongReceived());
    }
    
    @Test
    void testDecrementMastersMonitored() {
        SentinelStats stats = new SentinelStats();
        
        stats.incrementMastersMonitored();
        stats.incrementMastersMonitored();
        assertEquals(2, stats.getMastersMonitored());
        
        stats.decrementMastersMonitored();
        assertEquals(1, stats.getMastersMonitored());
    }
    
    @Test
    void testUptime() {
        SentinelStats stats = new SentinelStats();
        
        long uptime = stats.getUptime();
        assertTrue(uptime >= 0);
    }
    
    @Test
    void testReset() {
        SentinelStats stats = new SentinelStats();
        
        stats.incrementMastersMonitored();
        stats.incrementSDownEvents();
        stats.incrementFailoverEvents();
        
        stats.reset();
        
        assertEquals(0, stats.getMastersMonitored());
        assertEquals(0, stats.getSDownEvents());
        assertEquals(0, stats.getFailoverEvents());
    }
    
    @Test
    void testGetStatsString() {
        SentinelStats stats = new SentinelStats();
        stats.incrementMastersMonitored();
        stats.incrementPingSent();
        
        String statsString = stats.getStatsString();
        
        assertNotNull(statsString);
        assertTrue(statsString.contains("masters_monitored:1"));
        assertTrue(statsString.contains("ping_sent:1"));
    }
}
