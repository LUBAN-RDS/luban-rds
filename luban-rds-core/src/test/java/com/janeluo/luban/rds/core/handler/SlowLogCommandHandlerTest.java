package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import com.janeluo.luban.rds.common.constant.RdsResponseConstant;
import com.janeluo.luban.rds.core.slowlog.SlowLogEntry;
import com.janeluo.luban.rds.core.slowlog.SlowLogManager;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class SlowLogCommandHandlerTest {

    private SlowLogCommandHandler handler;
    private MemoryStore store;
    private SlowLogManager manager;

    @Before
    public void setUp() {
        handler = new SlowLogCommandHandler();
        store = new DefaultMemoryStore();
        manager = SlowLogManager.getInstance();
        manager.reset();
        RuntimeConfig.setSlowlogLogSlowerThan(10000); // Default
        RuntimeConfig.setSlowlogMaxLen(128); // Default
    }

    @Test
    public void testSlowLogLenInitiallyZero() {
        Object response = handler.handle(0, new String[]{"SLOWLOG", "LEN"}, store);
        assertEquals(":0\r\n", response);
    }

    @Test
    public void testSlowLogGetInitiallyEmpty() {
        Object response = handler.handle(0, new String[]{"SLOWLOG", "GET"}, store);
        assertEquals("*0\r\n", response);
    }

    @Test
    public void testSlowLogPushAndGet() {
        // Simulate a slow command
        manager.push(15000, Arrays.asList("GET", "key"), "127.0.0.1:9736", "client1");

        // Verify LEN
        Object lenResponse = handler.handle(0, new String[]{"SLOWLOG", "LEN"}, store);
        assertEquals(":1\r\n", lenResponse);

        // Verify GET
        Object getResponse = handler.handle(0, new String[]{"SLOWLOG", "GET"}, store);
        assertTrue(getResponse instanceof String);
        String respStr = (String) getResponse;
        assertTrue(respStr.startsWith("*1\r\n"));
        assertTrue(respStr.contains("GET"));
        assertTrue(respStr.contains("key"));
        assertTrue(respStr.contains("127.0.0.1:9736"));
        assertTrue(respStr.contains("client1"));
    }

    @Test
    public void testSlowLogReset() {
        manager.push(15000, Arrays.asList("GET", "key"), "127.0.0.1:9736", "client1");
        assertEquals(1, manager.len());

        Object resetResponse = handler.handle(0, new String[]{"SLOWLOG", "RESET"}, store);
        assertEquals(RdsResponseConstant.OK, resetResponse);

        assertEquals(0, manager.len());
    }

    @Test
    public void testSlowLogMaxLen() {
        RuntimeConfig.setSlowlogMaxLen(2);
        
        manager.push(20000, Arrays.asList("CMD1"), "ip1", "c1");
        manager.push(20000, Arrays.asList("CMD2"), "ip1", "c1");
        manager.push(20000, Arrays.asList("CMD3"), "ip1", "c1");

        assertEquals(2, manager.len());

        List<SlowLogEntry> entries = manager.get(10);
        assertEquals(2, entries.size());
        // Should be CMD3 (newest) and CMD2
        // LinkedList addFirst, so CMD3 is first, CMD2 is second. CMD1 removed.
        assertEquals("CMD3", entries.get(0).getArgs().get(0));
        assertEquals("CMD2", entries.get(1).getArgs().get(0));
    }

    @Test
    public void testSlowLogThreshold() {
        RuntimeConfig.setSlowlogLogSlowerThan(10000);
        
        // Fast command (should not be logged)
        manager.push(5000, Arrays.asList("FAST"), "ip", "c");
        assertEquals(0, manager.len());

        // Slow command (should be logged)
        manager.push(15000, Arrays.asList("SLOW"), "ip", "c");
        assertEquals(1, manager.len());
    }
    
    @Test
    public void testSlowLogDisabled() {
        RuntimeConfig.setSlowlogLogSlowerThan(-1);
        
        manager.push(1000000, Arrays.asList("VERY_SLOW"), "ip", "c");
        assertEquals(0, manager.len());
    }
}
