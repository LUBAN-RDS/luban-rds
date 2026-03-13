package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * SetCommandHandler 单元测试类
 * 测试所有 Set 相关命令的处理逻辑
 */
public class SetCommandHandlerTest {
    
    private SetCommandHandler handler;
    
    @Mock
    private MemoryStore store;
    
    private static final int DATABASE = 0;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        handler = new SetCommandHandler();
    }
    
    // ==================== SADD 命令测试 ====================
    
    @Test
    public void testSAddNormal() {
        String[] args = {"SADD", "myset", "member1", "member2"};
        when(store.sadd(DATABASE, "myset", "member1", "member2")).thenReturn(2);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
        verify(store, times(1)).sadd(DATABASE, "myset", "member1", "member2");
    }
    
    @Test
    public void testSAddWrongArguments() {
        String[] args = {"SADD", "myset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'sadd' command\r\n", result);
        verify(store, never()).sadd(anyInt(), anyString(), any(String[].class));
    }
    
    // ==================== SREM 命令测试 ====================
    
    @Test
    public void testSRemNormal() {
        String[] args = {"SREM", "myset", "member1"};
        when(store.srem(DATABASE, "myset", "member1")).thenReturn(1);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":1\r\n", result);
    }
    
    @Test
    public void testSRemWrongArguments() {
        String[] args = {"SREM", "myset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'srem' command\r\n", result);
    }
    
    // ==================== SMEMBERS 命令测试 ====================
    
    @Test
    public void testSMembersNormal() {
        String[] args = {"SMEMBERS", "myset"};
        Set<String> members = new HashSet<>(Arrays.asList("member1", "member2"));
        when(store.smembers(DATABASE, "myset")).thenReturn(members);
        
        Object result = handler.handle(DATABASE, args, store);
        String resultStr = (String) result;
        assert(resultStr.startsWith("*2\r\n"));
    }
    
    @Test
    public void testSMembersEmpty() {
        String[] args = {"SMEMBERS", "myset"};
        when(store.smembers(DATABASE, "myset")).thenReturn(Collections.emptySet());
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*0\r\n", result);
    }
    
    // ==================== SISMEMBER 命令测试 ====================
    
    @Test
    public void testSIsMemberTrue() {
        String[] args = {"SISMEMBER", "myset", "member1"};
        when(store.sismember(DATABASE, "myset", "member1")).thenReturn(true);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":1\r\n", result);
    }
    
    @Test
    public void testSIsMemberFalse() {
        String[] args = {"SISMEMBER", "myset", "member1"};
        when(store.sismember(DATABASE, "myset", "member1")).thenReturn(false);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":0\r\n", result);
    }
    
    // ==================== SCARD 命令测试 ====================
    
    @Test
    public void testSCardNormal() {
        String[] args = {"SCARD", "myset"};
        when(store.scard(DATABASE, "myset")).thenReturn(5);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":5\r\n", result);
    }
    
    @Test
    public void testSCardEmpty() {
        String[] args = {"SCARD", "myset"};
        when(store.scard(DATABASE, "myset")).thenReturn(0);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":0\r\n", result);
    }
    
    // ==================== SINTER 命令测试 ====================
    
    @Test
    public void testSInterNormal() {
        String[] args = {"SINTER", "set1", "set2"};
        Set<String> intersection = new HashSet<>(Arrays.asList("common"));
        when(store.sinter(DATABASE, "set1", "set2")).thenReturn(intersection);
        
        Object result = handler.handle(DATABASE, args, store);
        String resultStr = (String) result;
        assert(resultStr.startsWith("*1\r\n"));
    }
    
    @Test
    public void testSInterWrongArguments() {
        String[] args = {"SINTER"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'sinter' command\r\n", result);
    }
    
    // ==================== SUNION 命令测试 ====================
    
    @Test
    public void testSUnionNormal() {
        String[] args = {"SUNION", "set1", "set2"};
        Set<String> union = new HashSet<>(Arrays.asList("member1", "member2"));
        when(store.sunion(DATABASE, "set1", "set2")).thenReturn(union);
        
        Object result = handler.handle(DATABASE, args, store);
        String resultStr = (String) result;
        assert(resultStr.startsWith("*2\r\n"));
    }
    
    // ==================== SDIFF 命令测试 ====================
    
    @Test
    public void testSDiffNormal() {
        String[] args = {"SDIFF", "set1", "set2"};
        Set<String> diff = new HashSet<>(Arrays.asList("unique"));
        when(store.sdiff(DATABASE, "set1", "set2")).thenReturn(diff);
        
        Object result = handler.handle(DATABASE, args, store);
        String resultStr = (String) result;
        assert(resultStr.startsWith("*1\r\n"));
    }
    
    // ==================== SSCAN 命令测试 ====================
    
    @Test
    public void testSScanNormal() {
        String[] args = {"SSCAN", "myset", "0"};
        List<Object> scanResult = Arrays.asList(0L, "member1", "member2");
        when(store.sscan(DATABASE, "myset", 0L, "*", 10)).thenReturn(scanResult);
        
        Object result = handler.handle(DATABASE, args, store);
        String expected = "*2\r\n$1\r\n0\r\n*2\r\n$7\r\nmember1\r\n$7\r\nmember2\r\n";
        assertEquals(expected, result);
    }
    
    @Test
    public void testSScanWithPattern() {
        String[] args = {"SSCAN", "myset", "0", "MATCH", "member*", "COUNT", "5"};
        List<Object> scanResult = Arrays.asList(0L, "member1");
        when(store.sscan(DATABASE, "myset", 0L, "member*", 5)).thenReturn(scanResult);
        
        Object result = handler.handle(DATABASE, args, store);
        String expected = "*2\r\n$1\r\n0\r\n*1\r\n$7\r\nmember1\r\n";
        assertEquals(expected, result);
    }
    
    @Test
    public void testSScanWrongArguments() {
        String[] args = {"SSCAN", "myset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'sscan' command\r\n", result);
    }
    
    @Test
    public void testSScanInvalidCursor() {
        String[] args = {"SSCAN", "myset", "abc"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", result);
    }
    
    @Test
    public void testSScanInvalidCount() {
        String[] args = {"SSCAN", "myset", "0", "COUNT", "abc"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", result);
    }
    
    // ==================== 支持的命令测试 ====================
    
    @Test
    public void testSupportedCommands() {
        java.util.Set<String> supportedCommands = handler.supportedCommands();
        assertEquals(9, supportedCommands.size());
        assert(supportedCommands.contains("SADD"));
        assert(supportedCommands.contains("SREM"));
        assert(supportedCommands.contains("SMEMBERS"));
        assert(supportedCommands.contains("SISMEMBER"));
        assert(supportedCommands.contains("SCARD"));
        assert(supportedCommands.contains("SINTER"));
        assert(supportedCommands.contains("SUNION"));
        assert(supportedCommands.contains("SDIFF"));
        assert(supportedCommands.contains("SSCAN"));
    }
    
    // ==================== 未知命令测试 ====================
    
    @Test
    public void testUnknownCommand() {
        String[] args = {"UNKNOWN", "myset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR unknown command\r\n", result);
    }
}
