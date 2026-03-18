package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelState 测试类
 */
class SentinelStateTest {
    
    @Test
    void testSentinelStateValues() {
        SentinelState[] states = SentinelState.values();
        assertEquals(5, states.length);
        
        assertEquals("init", SentinelState.INIT.getName());
        assertEquals("running", SentinelState.RUNNING.getName());
        assertEquals("failover_in_progress", SentinelState.FAILOVER_IN_PROGRESS.getName());
        assertEquals("shutting_down", SentinelState.SHUTTING_DOWN.getName());
        assertEquals("shutdown", SentinelState.SHUTDOWN.getName());
    }
}
