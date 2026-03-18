package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * MasterState 测试类
 */
class MasterStateTest {
    
    @Test
    void testMasterState() {
        MasterState master = new MasterState("mymaster", "127.0.0.1", 6379, 2);
        
        assertEquals("mymaster", master.getName());
        assertEquals("127.0.0.1", master.getHost());
        assertEquals(6379, master.getPort());
        assertEquals(2, master.getQuorum());
        assertEquals(NodeState.NORMAL, master.getState());
        assertEquals(FailoverState.NONE, master.getFailoverState());
    }
    
    @Test
    void testMasterStateFlags() {
        MasterState master = new MasterState("mymaster", "127.0.0.1", 6379, 2);
        
        assertFalse(master.hasFlag(MasterState.FLAG_S_DOWN));
        assertFalse(master.hasFlag(MasterState.FLAG_O_DOWN));
        
        master.addFlag(MasterState.FLAG_S_DOWN);
        assertTrue(master.hasFlag(MasterState.FLAG_S_DOWN));
        assertFalse(master.hasFlag(MasterState.FLAG_O_DOWN));
        
        master.addFlag(MasterState.FLAG_O_DOWN);
        assertTrue(master.hasFlag(MasterState.FLAG_S_DOWN));
        assertTrue(master.hasFlag(MasterState.FLAG_O_DOWN));
        
        master.removeFlag(MasterState.FLAG_S_DOWN);
        assertFalse(master.hasFlag(MasterState.FLAG_S_DOWN));
        assertTrue(master.hasFlag(MasterState.FLAG_O_DOWN));
    }
    
    @Test
    void testMasterStateSlaves() {
        MasterState master = new MasterState("mymaster", "127.0.0.1", 6379, 2);
        
        SlaveState slave1 = new SlaveState("127.0.0.1:6380", "127.0.0.1", 6380);
        SlaveState slave2 = new SlaveState("127.0.0.1:6381", "127.0.0.1", 6381);
        
        master.addSlave(slave1);
        master.addSlave(slave2);
        
        assertEquals(2, master.getSlaves().size());
        assertNotNull(master.getSlave("127.0.0.1:6380"));
        assertNotNull(master.getSlave("127.0.0.1:6381"));
        
        master.removeSlave("127.0.0.1:6380");
        assertEquals(1, master.getSlaves().size());
        assertNull(master.getSlave("127.0.0.1:6380"));
    }
    
    @Test
    void testMasterStateSentinels() {
        MasterState master = new MasterState("mymaster", "127.0.0.1", 6379, 2);
        
        SentinelInstance si1 = new SentinelInstance("sentinel1", "127.0.0.1", 26379);
        SentinelInstance si2 = new SentinelInstance("sentinel2", "127.0.0.1", 26380);
        
        master.addSentinel(si1);
        master.addSentinel(si2);
        
        assertEquals(2, master.getSentinels().size());
        assertNotNull(master.getSentinel("sentinel1"));
        assertNotNull(master.getSentinel("sentinel2"));
        
        master.removeSentinel("sentinel1");
        assertEquals(1, master.getSentinels().size());
        assertNull(master.getSentinel("sentinel1"));
    }
    
    @Test
    void testMasterStateDownStatus() {
        MasterState master = new MasterState("mymaster", "127.0.0.1", 6379, 2);
        
        assertFalse(master.isSDown());
        assertFalse(master.isODown());
        assertFalse(master.isFailoverInProgress());
        
        master.setState(NodeState.S_DOWN);
        assertTrue(master.isSDown());
        
        master.setState(NodeState.O_DOWN);
        assertTrue(master.isODown());
        
        master.setFailoverState(FailoverState.SELECT_SLAVE);
        assertTrue(master.isFailoverInProgress());
    }
}
