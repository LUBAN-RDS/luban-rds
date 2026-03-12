package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LuaStructTest {
    private LuaCommandHandler luaCommandHandler;
    private MemoryStore memoryStore;
    private int database;

    @Before
    public void setUp() {
        luaCommandHandler = new LuaCommandHandler();
        memoryStore = new DefaultMemoryStore();
        database = 0;
    }

    @Test
    public void testStructPackUnpack() {
        String script = 
            "local packed = struct.pack('dLc0', 1.5, 'hello') " +
            "local d, s = struct.unpack('dLc0', packed) " +
            "return {d, s}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue(resp.startsWith("*2\r\n"));
        assertTrue(resp.contains("1.5"));
        assertTrue(resp.contains("hello"));
    }

    @Test
    public void testRedissonScriptLogic() {
        String script = 
            "local key = 'mykey' " +
            "local val = 'myval' " +
            "local msg = struct.pack('Lc0Lc0', key, val) " +
            "return msg";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue(resp.startsWith("$18\r\n"));
    }

    @Test
    public void testStructUnpackWithC0() {
        // Test unpacking with c0 reading remaining bytes
        String script = 
            "local packed = struct.pack('bc0', 65, 'BC') " + // 65 = 'A'
            "local b, s = struct.unpack('bc0', packed) " +
            "return {b, s}";
            
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        assertTrue(resp.contains(":65"));
        assertTrue(resp.contains("BC"));
    }
}
