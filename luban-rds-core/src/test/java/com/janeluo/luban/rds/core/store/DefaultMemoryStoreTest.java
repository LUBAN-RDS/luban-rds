package com.janeluo.luban.rds.core.store;

import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class DefaultMemoryStoreTest {
    private MemoryStore memoryStore;
    
    @Before
    public void setUp() {
        memoryStore = new DefaultMemoryStore();
    }
    
    @Test
    public void testSetAndGet() {
        String key = "testKey";
        String value = "testValue";
        int database = 0;
        
        memoryStore.set(database, key, value);
        Object retrievedValue = memoryStore.get(database, key);
        
        assertNotNull(retrievedValue);
        assertEquals(value, retrievedValue);
    }
    
    @Test
    public void testDel() {
        String key = "testKey";
        String value = "testValue";
        int database = 0;
        
        memoryStore.set(database, key, value);
        assertTrue(memoryStore.exists(database, key));
        
        boolean deleted = memoryStore.del(database, key);
        assertTrue(deleted);
        assertFalse(memoryStore.exists(database, key));
        assertNull(memoryStore.get(database, key));
    }
    
    @Test
    public void testExists() {
        String key = "testKey";
        String value = "testValue";
        int database = 0;
        
        assertFalse(memoryStore.exists(database, key));
        memoryStore.set(database, key, value);
        assertTrue(memoryStore.exists(database, key));
    }
    
    @Test
    public void testExpire() {
        String key = "testKey";
        String value = "testValue";
        int database = 0;
        
        memoryStore.set(database, key, value);
        assertTrue(memoryStore.exists(database, key));
        
        boolean expired = memoryStore.expire(database, key, 1);
        assertTrue(expired);
        
        // 等待过期
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        
        assertFalse(memoryStore.exists(database, key));
        assertNull(memoryStore.get(database, key));
    }
    
    @Test
    public void testTtl() {
        String key = "testKey";
        String value = "testValue";
        int database = 0;
        
        memoryStore.set(database, key, value);
        assertEquals(-1, memoryStore.ttl(database, key));
        
        memoryStore.expire(database, key, 10);
        long ttl = memoryStore.ttl(database, key);
        assertTrue(ttl > 0 && ttl <= 10);
    }
    
    @Test
    public void testFlushAll() {
        String key1 = "testKey1";
        String value1 = "testValue1";
        String key2 = "testKey2";
        String value2 = "testValue2";
        int database = 0;
        
        memoryStore.set(database, key1, value1);
        memoryStore.set(database, key2, value2);
        assertTrue(memoryStore.exists(database, key1));
        assertTrue(memoryStore.exists(database, key2));
        
        memoryStore.flushAll();
        assertFalse(memoryStore.exists(database, key1));
        assertFalse(memoryStore.exists(database, key2));
    }
    
    @Test
    public void testType() {
        String stringKey = "stringKey";
        String hashKey = "hashKey";
        String listKey = "listKey";
        String setKey = "setKey";
        int database = 0;
        
        memoryStore.set(database, stringKey, "stringValue");
        memoryStore.set(database, hashKey, java.util.Collections.singletonMap("field", "value"));
        memoryStore.set(database, listKey, java.util.Collections.singletonList("item"));
        memoryStore.set(database, setKey, java.util.Collections.singleton("member"));
        
        assertEquals("string", memoryStore.type(database, stringKey));
        assertEquals("hash", memoryStore.type(database, hashKey));
        assertEquals("list", memoryStore.type(database, listKey));
        assertEquals("set", memoryStore.type(database, setKey));
        assertEquals("none", memoryStore.type(database, "nonExistentKey"));
    }
}
