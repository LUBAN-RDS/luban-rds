package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class ListCommandHandlerTest {
    
    private ListCommandHandler handler;
    @Mock
    private MemoryStore store;
    private static final int DATABASE = 0;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        handler = new ListCommandHandler();
    }
    
    // LPUSH 命令测试
    @Test
    public void testLPushNormal() {
        String[] args = {"LPUSH", "list1", "value1", "value2"};
        when(store.lpush(DATABASE, "list1", new String[]{"value1", "value2"})).thenReturn(2);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
        verify(store, times(1)).lpush(DATABASE, "list1", new String[]{"value1", "value2"});
    }
    
    @Test
    public void testLPushWrongArguments() {
        String[] args = {"LPUSH", "list1"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'lpush' command\r\n", result);
        verify(store, never()).lpush(anyInt(), anyString(), any(String[].class));
    }
    
    @Test
    public void testLPushOOM() {
        String[] args = {"LPUSH", "list1", "value1"};
        when(store.lpush(DATABASE, "list1", new String[]{"value1"})).thenThrow(
            new RuntimeException("OOM command not allowed when used memory > 'maxmemory'")
        );
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", result);
    }
    
    // RPUSH 命令测试
    @Test
    public void testRPushNormal() {
        String[] args = {"RPUSH", "list1", "value1", "value2"};
        when(store.rpush(DATABASE, "list1", new String[]{"value1", "value2"})).thenReturn(2);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
        verify(store, times(1)).rpush(DATABASE, "list1", new String[]{"value1", "value2"});
    }
    
    @Test
    public void testRPushWrongArguments() {
        String[] args = {"RPUSH", "list1"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'rpush' command\r\n", result);
        verify(store, never()).rpush(anyInt(), anyString(), any(String[].class));
    }
    
    @Test
    public void testRPushOOM() {
        String[] args = {"RPUSH", "list1", "value1"};
        when(store.rpush(DATABASE, "list1", new String[]{"value1"})).thenThrow(
            new RuntimeException("OOM command not allowed when used memory > 'maxmemory'")
        );
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", result);
    }
    
    // LPOP 命令测试
    @Test
    public void testLPopNormal() {
        String[] args = {"LPOP", "list1"};
        when(store.lpop(DATABASE, "list1")).thenReturn("value1");
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$6\r\nvalue1\r\n", result);
        verify(store, times(1)).lpop(DATABASE, "list1");
    }
    
    @Test
    public void testLPopEmptyList() {
        String[] args = {"LPOP", "list1"};
        when(store.lpop(DATABASE, "list1")).thenReturn(null);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$-1\r\n", result);
    }
    
    @Test
    public void testLPopWrongArguments() {
        String[] args = {"LPOP"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'lpop' command\r\n", result);
        verify(store, never()).lpop(anyInt(), anyString());
    }
    
    // RPOP 命令测试
    @Test
    public void testRPopNormal() {
        String[] args = {"RPOP", "list1"};
        when(store.rpop(DATABASE, "list1")).thenReturn("value1");
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$6\r\nvalue1\r\n", result);
        verify(store, times(1)).rpop(DATABASE, "list1");
    }
    
    @Test
    public void testRPopEmptyList() {
        String[] args = {"RPOP", "list1"};
        when(store.rpop(DATABASE, "list1")).thenReturn(null);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$-1\r\n", result);
    }
    
    @Test
    public void testRPopWrongArguments() {
        String[] args = {"RPOP"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'rpop' command\r\n", result);
        verify(store, never()).rpop(anyInt(), anyString());
    }
    
    // LLEN 命令测试
    @Test
    public void testLLenNormal() {
        String[] args = {"LLEN", "list1"};
        when(store.llen(DATABASE, "list1")).thenReturn(5);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":5\r\n", result);
        verify(store, times(1)).llen(DATABASE, "list1");
    }
    
    @Test
    public void testLLenEmptyList() {
        String[] args = {"LLEN", "list1"};
        when(store.llen(DATABASE, "list1")).thenReturn(0);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":0\r\n", result);
    }
    
    @Test
    public void testLLenWrongArguments() {
        String[] args = {"LLEN"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'llen' command\r\n", result);
        verify(store, never()).llen(anyInt(), anyString());
    }
    
    // LRANGE 命令测试
    @Test
    public void testLRangeNormal() {
        String[] args = {"LRANGE", "list1", "0", "2"};
        when(store.lrange(DATABASE, "list1", 0, 2)).thenReturn(java.util.Arrays.asList("value1", "value2", "value3"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*3\r\n$6\r\nvalue1\r\n$6\r\nvalue2\r\n$6\r\nvalue3\r\n", result);
        verify(store, times(1)).lrange(DATABASE, "list1", 0, 2);
    }
    
    @Test
    public void testLRangeEmptyList() {
        String[] args = {"LRANGE", "list1", "0", "2"};
        when(store.lrange(DATABASE, "list1", 0, 2)).thenReturn(java.util.Collections.emptyList());
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*0\r\n", result);
    }
    
    @Test
    public void testLRangeWrongArguments() {
        String[] args = {"LRANGE", "list1", "0"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'lrange' command\r\n", result);
        verify(store, never()).lrange(anyInt(), anyString(), anyLong(), anyLong());
    }
    
    @Test
    public void testLRangeInvalidRange() {
        String[] args = {"LRANGE", "list1", "a", "2"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", result);
        verify(store, never()).lrange(anyInt(), anyString(), anyLong(), anyLong());
    }
    
    // LREM 命令测试
    @Test
    public void testLRemNormal() {
        String[] args = {"LREM", "list1", "2", "value"};
        when(store.lrem(DATABASE, "list1", 2, "value")).thenReturn(2);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
        verify(store, times(1)).lrem(DATABASE, "list1", 2, "value");
    }
    
    @Test
    public void testLRemWrongArguments() {
        String[] args = {"LREM", "list1", "2"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'lrem' command\r\n", result);
        verify(store, never()).lrem(anyInt(), anyString(), anyInt(), anyString());
    }
    
    @Test
    public void testLRemInvalidCount() {
        String[] args = {"LREM", "list1", "a", "value"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", result);
        verify(store, never()).lrem(anyInt(), anyString(), anyInt(), anyString());
    }
    
    // LINDEX 命令测试
    @Test
    public void testLIndexNormal() {
        String[] args = {"LINDEX", "list1", "0"};
        when(store.lindex(DATABASE, "list1", 0)).thenReturn("value1");
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$6\r\nvalue1\r\n", result);
        verify(store, times(1)).lindex(DATABASE, "list1", 0);
    }
    
    @Test
    public void testLIndexOutOfRange() {
        String[] args = {"LINDEX", "list1", "10"};
        when(store.lindex(DATABASE, "list1", 10)).thenReturn(null);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$-1\r\n", result);
    }
    
    @Test
    public void testLIndexWrongType() {
        String[] args = {"LINDEX", "list1", "0"};
        when(store.lindex(DATABASE, "list1", 0)).thenThrow(
            new RuntimeException("WRONGTYPE Operation against a key holding the wrong kind of value")
        );
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", result);
    }
    
    @Test
    public void testLIndexWrongArguments() {
        String[] args = {"LINDEX", "list1"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'lindex' command\r\n", result);
        verify(store, never()).lindex(anyInt(), anyString(), anyInt());
    }
    
    @Test
    public void testLIndexInvalidIndex() {
        String[] args = {"LINDEX", "list1", "a"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", result);
        verify(store, never()).lindex(anyInt(), anyString(), anyInt());
    }
    
    // 未知命令测试
    @Test
    public void testUnknownCommand() {
        String[] args = {"UNKNOWN", "list1"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR unknown command\r\n", result);
    }
    
    // 支持的命令测试
    @Test
    public void testSupportedCommands() {
        java.util.Set<String> supportedCommands = handler.supportedCommands();
        assertEquals(8, supportedCommands.size());
        assert(supportedCommands.contains("LPUSH"));
        assert(supportedCommands.contains("RPUSH"));
        assert(supportedCommands.contains("LPOP"));
        assert(supportedCommands.contains("RPOP"));
        assert(supportedCommands.contains("LLEN"));
        assert(supportedCommands.contains("LRANGE"));
        assert(supportedCommands.contains("LREM"));
        assert(supportedCommands.contains("LINDEX"));
    }
}
