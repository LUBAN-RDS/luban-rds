package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SlaveState 测试类
 */
class SlaveStateTest {
    
    @Test
    void testSlaveState() {
        SlaveState slave = new SlaveState("127.0.0.1:6380", "127.0.0.1", 6380);
        
        assertEquals("127.0.0.1:6380", slave.getSlaveId());
        assertEquals("127.0.0.1", slave.getHost());
        assertEquals(6380, slave.getPort());
        assertEquals(NodeState.NORMAL, slave.getState());
        assertEquals(100, slave.getPriority());
        assertTrue(slave.isOnline());
    }
    
    @Test
    void testSlaveStateProperties() {
        SlaveState slave = new SlaveState("127.0.0.1:6380", "127.0.0.1", 6380);
        
        slave.setReplOffset(12345);
        assertEquals(12345, slave.getReplOffset());
        
        slave.setPriority(50);
        assertEquals(50, slave.getPriority());
        
        slave.setMasterHost("127.0.0.1");
        assertEquals("127.0.0.1", slave.getMasterHost());
        
        slave.setMasterPort(6379);
        assertEquals(6379, slave.getMasterPort());
        
        slave.setLag(5);
        assertEquals(5, slave.getLag());
        
        slave.setOnline(false);
        assertFalse(slave.isOnline());
        
        slave.setState(NodeState.S_DOWN);
        assertEquals(NodeState.S_DOWN, slave.getState());
        assertTrue(slave.isSDown());
    }
}
