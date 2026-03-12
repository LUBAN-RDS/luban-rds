package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class StructPackBinaryTest {

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
    public void testUnpackReturnValueCount() {
        String script = 
            "local data = string.char(0,0,0,0,0x40,0x77,0x2B,0x41,5,0,0,0) .. 'hello' " +
            "local results = {struct.unpack('dLc0', data)} " +
            "return #results";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Return value count: " + resp);
        assertEquals("dLc0 should return 2 values (d + Lc0 combined)", ":2\r\n", resp);
    }

    @Test
    public void testLc0CombinedFormat() {
        String script = 
            "local t = 123.456 " +
            "local val = string.char(0,0,0,0) .. 'hello' " +
            "local packed = struct.pack('dLc0', t, val) " +
            "local t2, val2 = struct.unpack('dLc0', packed) " +
            "return {#packed, t2, #val2, val == val2}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Lc0 combined format response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array", resp.startsWith("*4\r\n"));
        assertTrue("Packed length should be 21 (8 + 4 + 9)", resp.contains(":21\r\n"));
        assertTrue("val length should be 9", resp.contains(":9\r\n"));
    }

    @Test
    public void testCombinedFormatAssignment() {
        String script = 
            "local data = string.char(0,0,0,0,0x40,0x77,0x2B,0x41,5,0,0,0) .. 'hello' " +
            "local t, val = struct.unpack('dLc0', data) " +
            "return {type(t), type(val), #val}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Combined format assignment response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("t should be number", resp.contains("number"));
        assertTrue("val should be string", resp.contains("string"));
        assertTrue("val length should be 5", resp.contains(":5\r\n"));
    }

    @Test
    public void testLc0PackWithNullBytes() {
        String script = 
            "local val = string.char(0,0,0,0) .. 'test' " +
            "local packed = struct.pack('Lc0', val) " +
            "local val2 = struct.unpack('Lc0', packed) " +
            "return {#packed, #val2, val == val2}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Lc0 with null bytes response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should be equal", resp.contains(":1\r\n"));
        assertTrue("Packed length should be 8 (4 + 4)", resp.contains(":8\r\n"));
    }

    @Test
    public void testOldRedissonFormat() {
        String script = 
            "local key = 'mykey' " +
            "local val = 'myval' " +
            "local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val) " +
            "local k, v = struct.unpack('Lc0Lc0', msg) " +
            "return {#msg, k, v, k == key, v == val}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Old Redisson format response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array", resp.startsWith("*5\r\n"));
        assertTrue("Packed length should be 18", resp.contains(":18\r\n"));
        assertTrue("Should contain mykey", resp.contains("mykey"));
        assertTrue("Should contain myval", resp.contains("myval"));
    }

    @Test
    public void testNewRedissonFormat() {
        String script = 
            "local key = 'mykey' " +
            "local val = 'myval' " +
            "local msg = struct.pack('Lc0Lc0', key, val) " +
            "local k, v = struct.unpack('Lc0Lc0', msg) " +
            "return {#msg, k, v, k == key, v == val}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("New Redisson format response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array", resp.startsWith("*5\r\n"));
        assertTrue("Packed length should be 18", resp.contains(":18\r\n"));
        assertTrue("Should contain mykey", resp.contains("mykey"));
        assertTrue("Should contain myval", resp.contains("myval"));
    }

    @Test
    public void testFullPackUnpackCycle() {
        String script = 
            "local t = 123.456 " +
            "local val = '\\x00\\x00\\x00\\x00' " +
            "local packed = struct.pack('dLc0', t, val) " +
            "local t2, val2 = struct.unpack('dLc0', packed) " +
            "return {#packed, t2, #val2}";
        
        Object result = luaCommandHandler.handle(database, new String[]{"EVAL", script, "0"}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Full cycle response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array", resp.startsWith("*3\r\n"));
        assertTrue("Packed length should be 16", resp.contains(":16\r\n"));
    }

    @Test
    public void testHsetHgetWithBinaryData() {
        byte[] originalBytes = new byte[] {
            0x00, 0x00, 0x00, 0x00, 0x40, 0x77, 0x2B, 0x41,
            (byte)0xB3, 0x07, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        
        String originalData = new String(originalBytes, StandardCharsets.ISO_8859_1);
        
        System.out.println("Original data length: " + originalData.length());
        System.out.println("Original bytes: " + bytesToHex(originalBytes));
        
        String script = 
            "local data = ARGV[1] " +
            "redis.call('HSET', 'test:hash', 'field1', data) " +
            "local retrieved = redis.call('HGET', 'test:hash', 'field1') " +
            "return {#data, #retrieved, data == retrieved}";
        
        Object result = luaCommandHandler.handle(database, 
            new String[]{"EVAL", script, "0", originalData}, memoryStore);
        String resp = result.toString();
        
        System.out.println("HSET/HGET response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array with 3 elements", resp.startsWith("*3\r\n"));
        assertTrue("Original length should be 16", resp.contains(":16\r\n"));
        assertTrue("Retrieved length should be 16", resp.contains(":16\r\n"));
        assertTrue("Should be equal", resp.contains(":1\r\n"));
    }

    @Test
    public void testPackUnpackThenRepack() {
        byte[] originalBytes = new byte[] {
            0x00, 0x00, 0x00, 0x00, 0x40, 0x77, 0x2B, 0x41,
            0x04, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00
        };
        
        String originalData = new String(originalBytes, StandardCharsets.ISO_8859_1);
        
        String script = 
            "local data = ARGV[1] " +
            "redis.call('HSET', 'test:hash2', 'field1', data) " +
            "local retrieved = redis.call('HGET', 'test:hash2', 'field1') " +
            "local t, val = struct.unpack('dLc0', retrieved) " +
            "print('Unpacked: t=' .. tostring(t) .. ', val len=' .. #val) " +
            "local repacked = struct.pack('dLc0', t, val) " +
            "print('Repacked length: ' .. #repacked) " +
            "redis.call('HSET', 'test:hash2', 'field2', repacked) " +
            "local final = redis.call('HGET', 'test:hash2', 'field2') " +
            "return {#data, #retrieved, #repacked, #final, data == repacked}";
        
        Object result = luaCommandHandler.handle(database, 
            new String[]{"EVAL", script, "0", originalData}, memoryStore);
        String resp = result.toString();
        
        System.out.println("Repack response: " + resp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Should return array", resp.startsWith("*5\r\n"));
        assertTrue("All lengths should be 16", resp.contains(":16\r\n"));
        assertTrue("Should be equal", resp.contains(":1\r\n"));
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }
}