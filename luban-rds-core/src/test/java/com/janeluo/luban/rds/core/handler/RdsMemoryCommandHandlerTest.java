package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class RdsMemoryCommandHandlerTest {
    private RdsMemoryCommandHandler handler;
    private MemoryStore store;
    private int db = 0;

    @Before
    public void setUp() {
        handler = new RdsMemoryCommandHandler();
        store = new DefaultMemoryStore();
    }

    @Test
    public void testMemoryUsage() {
        store.set(db, "key1", "value1");
        Object result = handler.handle(db, new String[]{"MEMORY", "USAGE", "key1"}, store);
        assertTrue(result instanceof Long);
        assertTrue((Long) result > 0);
        
        // Test non-existent key
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "key2"}, store);
        assertNull(result);
        
        // Test with SAMPLES
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "key1", "SAMPLES", "10"}, store);
        assertTrue(result instanceof Long);
        
        // Test invalid SAMPLES
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "key1", "SAMPLES", "abc"}, store);
        assertTrue(result.toString().startsWith("-ERR"));
    }
    
    @Test
    public void testMemoryStats() {
        store.set(db, "key1", "value1");
        Object result = handler.handle(db, new String[]{"MEMORY", "STATS"}, store);
        assertTrue(result instanceof List);
        List<?> stats = (List<?>) result;
        assertTrue(stats.contains("peak.allocated"));
        assertTrue(stats.contains("total.allocated"));
        assertTrue(stats.contains("keys.count"));
        
        // Verify keys.count logic
        int keysCountIndex = stats.indexOf("keys.count");
        assertTrue(keysCountIndex >= 0);
        Object count = stats.get(keysCountIndex + 1);
        assertEquals(1L, count);
    }
    
    @Test
    public void testMemoryPurge() {
        Object result = handler.handle(db, new String[]{"MEMORY", "PURGE"}, store);
        assertEquals("OK", result);
    }
    
    @Test
    public void testMemoryDoctor() {
        Object result = handler.handle(db, new String[]{"MEMORY", "DOCTOR"}, store);
        assertTrue(result instanceof String);
        String report = (String) result;
        assertTrue(report.contains("Luban-RDS Memory Doctor"));
    }
    
    @Test
    public void testMemoryMallocStats() {
        Object result = handler.handle(db, new String[]{"MEMORY", "MALLOC-STATS"}, store);
        assertTrue(result instanceof String);
        String stats = (String) result;
        assertTrue(stats.contains("JVM Memory Stats"));
    }
    
    @Test
    public void testMemoryHelp() {
        Object result = handler.handle(db, new String[]{"MEMORY", "HELP"}, store);
        assertTrue(result instanceof List);
    }
    
    @Test
    public void testInvalidSubcommand() {
        Object result = handler.handle(db, new String[]{"MEMORY", "INVALID"}, store);
        assertTrue(result.toString().startsWith("-ERR unknown subcommand"));
    }
    
    @Test
    public void testMemoryUsageDifferentTypes() {
        // List
        store.lpush(db, "list1", "a", "b", "c");
        Object result = handler.handle(db, new String[]{"MEMORY", "USAGE", "list1"}, store);
        assertTrue(result instanceof Long);
        
        // Hash
        store.hset(db, "hash1", "f1", "v1");
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "hash1"}, store);
        assertTrue(result instanceof Long);
        
        // Set
        store.sadd(db, "set1", "m1", "m2");
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "set1"}, store);
        assertTrue(result instanceof Long);
        
        // ZSet
        store.zadd(db, "zset1", 1.0, "m1");
        result = handler.handle(db, new String[]{"MEMORY", "USAGE", "zset1"}, store);
        assertTrue(result instanceof Long);
    }
    
    @Test
    public void testPeakMemoryUpdate() {
        // Initial peak should be 0 or small
        long initialPeak = store.getPeakUsedMemory();
        
        // Add large data
        byte[] largeData = new byte[1024 * 1024]; // 1MB
        // Can't put byte array directly via standard commands easily, but we can simulate large string
        StringBuilder sb = new StringBuilder();
        for(int i=0; i<1000; i++) sb.append("xxxxxxxxxx");
        String val = sb.toString();
        
        store.set(db, "largeKey", val);
        
        long newPeak = store.getPeakUsedMemory();
        assertTrue("Peak memory should increase", newPeak > initialPeak);
        
        // Remove data
        store.del(db, "largeKey");
        
        // Peak should remain high
        assertEquals(newPeak, store.getPeakUsedMemory());
        assertTrue(store.getUsedMemory() < newPeak);
    }
}
