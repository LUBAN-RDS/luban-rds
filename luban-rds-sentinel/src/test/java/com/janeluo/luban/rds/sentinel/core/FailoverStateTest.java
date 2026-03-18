package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * FailoverState 测试类
 */
class FailoverStateTest {
    
    @Test
    void testFailoverStateValues() {
        FailoverState[] states = FailoverState.values();
        assertEquals(6, states.length);
        
        assertEquals("none", FailoverState.NONE.getName());
        assertEquals("wait_start", FailoverState.WAIT_START.getName());
        assertEquals("select_slave", FailoverState.SELECT_SLAVE.getName());
        assertEquals("promote_slave", FailoverState.PROMOTE_SLAVE.getName());
        assertEquals("reconf_slaves", FailoverState.RECONF_SLAVES.getName());
        assertEquals("failover_done", FailoverState.FAILOVER_DONE.getName());
    }
}
