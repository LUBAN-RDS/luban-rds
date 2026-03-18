package com.janeluo.luban.rds.sentinel.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * NodeState 测试类
 */
class NodeStateTest {
    
    @Test
    void testNodeStateValues() {
        NodeState[] states = NodeState.values();
        assertEquals(4, states.length);
        
        assertEquals("normal", NodeState.NORMAL.getName());
        assertEquals("s_down", NodeState.S_DOWN.getName());
        assertEquals("o_down", NodeState.O_DOWN.getName());
        assertEquals("disconnected", NodeState.DISCONNECTED.getName());
    }
}
