package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class StringCommandHandlerTest {
    private StringCommandHandler stringCommandHandler;
    private MemoryStore memoryStore;
    
    @Before
    public void setUp() {
        stringCommandHandler = new StringCommandHandler();
        memoryStore = new DefaultMemoryStore();
    }
    
    @Test
    public void testSetCommand() {
        String[] args = {"SET", "testKey", "testValue"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals("+OK\r\n", response);
        assertEquals("testValue", memoryStore.get(database, "testKey"));
    }
    
    @Test
    public void testGetCommand() {
        // 先设置值
        int database = 0;
        memoryStore.set(database, "testKey", "testValue");
        
        // 测试获取
        String[] args = {"GET", "testKey"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals("$9\r\ntestValue\r\n", response);
    }
    
    @Test
    public void testGetNonExistentKey() {
        String[] args = {"GET", "nonExistentKey"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals("$-1\r\n", response);
    }

    @Test
    public void testKeyspaceHitsMissesInfo() {
        int database = 0;
        // ensure one miss
        stringCommandHandler.handle(database, new String[]{"GET","missingKey"}, memoryStore);
        // ensure one hit
        memoryStore.set(database, "presentKey", "v");
        stringCommandHandler.handle(database, new String[]{"GET","presentKey"}, memoryStore);
        // exists hit/miss
        CommonCommandHandler common = new CommonCommandHandler();
        common.handle(database, new String[]{"EXISTS","presentKey","missingKey"}, memoryStore);
        Object info = common.handle(database, new String[]{"INFO"}, memoryStore);
        String s = info.toString();
        assertTrue(s.contains("keyspace_hits:"));
        assertTrue(s.contains("keyspace_misses:"));
        assertTrue(!s.contains("keyspace_hits:0"));
        assertTrue(!s.contains("keyspace_misses:0"));
    }
    
    @Test
    public void testIncrCommand() {
        String[] args = {"INCR", "counter"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":1\r\n", response);
        assertEquals("1", memoryStore.get(database, "counter"));
        
        // 再次递增
        response = stringCommandHandler.handle(database, args, memoryStore);
        assertEquals(":2\r\n", response);
        assertEquals("2", memoryStore.get(database, "counter"));
    }
    
    @Test
    public void testDecrCommand() {
        String[] args = {"DECR", "counter"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":-1\r\n", response);
        assertEquals("-1", memoryStore.get(database, "counter"));
        
        // 再次递减
        response = stringCommandHandler.handle(database, args, memoryStore);
        assertEquals(":-2\r\n", response);
        assertEquals("-2", memoryStore.get(database, "counter"));
    }
    
    @Test
    public void testIncrByCommand() {
        String[] args = {"INCRBY", "counter", "5"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":5\r\n", response);
        assertEquals("5", memoryStore.get(database, "counter"));
    }
    
    @Test
    public void testDecrByCommand() {
        String[] args = {"DECRBY", "counter", "3"};
        int database = 0;
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":-3\r\n", response);
        assertEquals("-3", memoryStore.get(database, "counter"));
    }
    
    @Test
    public void testAppendCommand() {
        // 先设置初始值
        int database = 0;
        memoryStore.set(database, "testKey", "Hello");
        
        String[] args = {"APPEND", "testKey", " World"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":11\r\n", response);
        assertEquals("Hello World", memoryStore.get(database, "testKey"));
    }
    
    @Test
    public void testStrLenCommand() {
        // 设置值
        int database = 0;
        memoryStore.set(database, "testKey", "Hello World");
        
        String[] args = {"STRLEN", "testKey"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        assertEquals(":11\r\n", response);
    }
}
