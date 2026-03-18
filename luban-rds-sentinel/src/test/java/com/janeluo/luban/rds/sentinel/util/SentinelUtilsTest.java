package com.janeluo.luban.rds.sentinel.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SentinelUtils 测试类
 */
class SentinelUtilsTest {
    
    @Test
    void testGenerateId() {
        String id = SentinelUtils.generateId(40);
        
        assertNotNull(id);
        assertEquals(40, id.length());
        assertTrue(id.matches("[0-9a-f]+"));
    }
    
    @Test
    void testFormatAddress() {
        String address = SentinelUtils.formatAddress("127.0.0.1", 6379);
        
        assertEquals("127.0.0.1:6379", address);
    }
    
    @Test
    void testParseAddress() {
        String[] parts = SentinelUtils.parseAddress("127.0.0.1:6379");
        
        assertNotNull(parts);
        assertEquals(2, parts.length);
        assertEquals("127.0.0.1", parts[0]);
        assertEquals("6379", parts[1]);
    }
    
    @Test
    void testParseAddressInvalid() {
        String[] parts = SentinelUtils.parseAddress("invalid");
        assertNull(parts);
        
        parts = SentinelUtils.parseAddress("");
        assertNull(parts);
        
        parts = SentinelUtils.parseAddress(null);
        assertNull(parts);
    }
    
    @Test
    void testIsValidPort() {
        assertTrue(SentinelUtils.isValidPort(6379));
        assertTrue(SentinelUtils.isValidPort(26379));
        assertTrue(SentinelUtils.isValidPort(65535));
        
        assertFalse(SentinelUtils.isValidPort(0));
        assertFalse(SentinelUtils.isValidPort(-1));
        assertFalse(SentinelUtils.isValidPort(65536));
    }
    
    @Test
    void testIsValidIp() {
        assertTrue(SentinelUtils.isValidIp("127.0.0.1"));
        assertTrue(SentinelUtils.isValidIp("192.168.1.1"));
        assertTrue(SentinelUtils.isValidIp("0.0.0.0"));
        
        assertFalse(SentinelUtils.isValidIp("256.0.0.1"));
        assertFalse(SentinelUtils.isValidIp("127.0.0"));
        assertFalse(SentinelUtils.isValidIp(""));
        assertFalse(SentinelUtils.isValidIp(null));
    }
    
    @Test
    void testFormatDuration() {
        assertEquals("500ms", SentinelUtils.formatDuration(500));
        assertEquals("5s", SentinelUtils.formatDuration(5000));
        assertEquals("1m 30s", SentinelUtils.formatDuration(90000));
        assertEquals("1h 30m", SentinelUtils.formatDuration(5400000));
    }
    
    @Test
    void testMatchPattern() {
        assertTrue(SentinelUtils.matchPattern("mymaster", "*"));
        assertTrue(SentinelUtils.matchPattern("mymaster", "my*"));
        assertTrue(SentinelUtils.matchPattern("mymaster", "*master"));
        
        assertFalse(SentinelUtils.matchPattern("mymaster", "other*"));
    }
    
    @Test
    void testTimeDiff() {
        long start = System.currentTimeMillis();
        long end = start + 1000;
        
        assertEquals(1000, SentinelUtils.timeDiff(start, end));
    }
    
    @Test
    void testIsTimeout() {
        long startTime = System.currentTimeMillis() - 5000;
        
        assertTrue(SentinelUtils.isTimeout(startTime, 3000));
        assertFalse(SentinelUtils.isTimeout(startTime, 10000));
    }
}
