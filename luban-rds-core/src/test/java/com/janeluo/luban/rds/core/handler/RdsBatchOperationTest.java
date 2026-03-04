package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class RdsBatchOperationTest {
    private StringCommandHandler stringCommandHandler;
    private HashCommandHandler hashCommandHandler;
    private MemoryStore memoryStore;
    private final int database = 0;

    @Before
    public void setUp() {
        stringCommandHandler = new StringCommandHandler();
        hashCommandHandler = new HashCommandHandler();
        memoryStore = new DefaultMemoryStore();
    }

    @Test
    public void testMSet() {
        String[] args = {"MSET", "key1", "val1", "key2", "val2"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        assertEquals("+OK\r\n", response);
        assertEquals("val1", memoryStore.get(database, "key1"));
        assertEquals("val2", memoryStore.get(database, "key2"));
    }
    
    @Test
    public void testMSetInvalidArgs() {
        String[] args = {"MSET", "key1", "val1", "key2"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        assertTrue(response.toString().startsWith("-ERR"));
    }

    @Test
    public void testMGet() {
        memoryStore.set(database, "key1", "val1");
        memoryStore.set(database, "key2", "val2");
        
        String[] args = {"MGET", "key1", "key2", "key3"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        // *3\r\n$4\r\nval1\r\n$4\r\nval2\r\n$-1\r\n
        String expected = "*3\r\n$4\r\nval1\r\n$4\r\nval2\r\n$-1\r\n";
        assertEquals(expected, response);
    }

    @Test
    public void testHMSet() {
        String[] args = {"HMSET", "hash1", "f1", "v1", "f2", "v2"};
        Object response = hashCommandHandler.handle(database, args, memoryStore);
        assertEquals("+OK\r\n", response);
        
        assertEquals("v1", memoryStore.hget(database, "hash1", "f1"));
        assertEquals("v2", memoryStore.hget(database, "hash1", "f2"));
    }

    @Test
    public void testHMSetInvalidArgs() {
        String[] args = {"HMSET", "hash1", "f1"};
        Object response = hashCommandHandler.handle(database, args, memoryStore);
        assertTrue(response.toString().startsWith("-ERR"));
    }

    @Test
    public void testHMGet() {
        memoryStore.hset(database, "hash1", "f1", "v1");
        memoryStore.hset(database, "hash1", "f2", "v2");
        
        String[] args = {"HMGET", "hash1", "f1", "f2", "f3"};
        Object response = hashCommandHandler.handle(database, args, memoryStore);
        
        // *3\r\n$2\r\nv1\r\n$2\r\nv2\r\n$-1\r\n
        String expected = "*3\r\n$2\r\nv1\r\n$2\r\nv2\r\n$-1\r\n";
        assertEquals(expected, response);
    }
    
    @Test
    public void testMGetWithWrongType() {
        // Set a hash
        memoryStore.hset(database, "hashKey", "f", "v");
        memoryStore.set(database, "strKey", "strVal");
        
        String[] args = {"MGET", "strKey", "hashKey"};
        Object response = stringCommandHandler.handle(database, args, memoryStore);
        
        // hashKey should be nil ($-1) because it's not a string
        // *2\r\n$6\r\nstrVal\r\n$-1\r\n
        String expected = "*2\r\n$6\r\nstrVal\r\n$-1\r\n";
        assertEquals(expected, response);
    }
    
    @Test
    public void testMSetOverwrite() {
        memoryStore.set(database, "key1", "oldVal");
        String[] args = {"MSET", "key1", "newVal"};
        stringCommandHandler.handle(database, args, memoryStore);
        assertEquals("newVal", memoryStore.get(database, "key1"));
    }
}
