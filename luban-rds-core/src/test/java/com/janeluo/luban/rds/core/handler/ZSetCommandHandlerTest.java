package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

/**
 * ZSetCommandHandler 单元测试类
 * 测试所有 ZSet 相关命令的处理逻辑
 */
public class ZSetCommandHandlerTest {
    
    private ZSetCommandHandler handler;
    
    @Mock
    private MemoryStore store;
    
    private static final int DATABASE = 0;
    
    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        handler = new ZSetCommandHandler();
    }
    
    // ==================== ZADD 命令测试 ====================
    
    @Test
    public void testZAddNormal() {
        String[] args = {"ZADD", "myzset", "1.0", "member1"};
        when(store.zadd(DATABASE, "myzset", 1.0, "member1")).thenReturn(1);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":1\r\n", result);
        verify(store, times(1)).zadd(DATABASE, "myzset", 1.0, "member1");
    }
    
    @Test
    public void testZAddWrongArguments() {
        String[] args = {"ZADD", "myzset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'zadd' command\r\n", result);
        verify(store, never()).zadd(anyInt(), anyString(), anyDouble(), anyString());
    }
    
    @Test
    public void testZAddInvalidScore() {
        String[] args = {"ZADD", "myzset", "abc", "member1"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR value is not a valid float\r\n", result);
    }
    
    // ==================== ZRANGE 命令测试 ====================
    
    @Test
    public void testZRangeNormal() {
        String[] args = {"ZRANGE", "myzset", "0", "-1"};
        when(store.zrange(DATABASE, "myzset", 0, -1)).thenReturn(Arrays.asList("member1", "member2"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember1\r\n$7\r\nmember2\r\n", result);
    }
    
    @Test
    public void testZRangeEmpty() {
        String[] args = {"ZRANGE", "myzset", "0", "-1"};
        when(store.zrange(DATABASE, "myzset", 0, -1)).thenReturn(Collections.emptyList());
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*0\r\n", result);
    }
    
    // ==================== ZSCORE 命令测试 ====================
    
    @Test
    public void testZScoreNormal() {
        String[] args = {"ZSCORE", "myzset", "member1"};
        when(store.zscore(DATABASE, "myzset", "member1")).thenReturn(1.5);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$3\r\n1.5\r\n", result);
    }
    
    @Test
    public void testZScoreNotFound() {
        String[] args = {"ZSCORE", "myzset", "member1"};
        when(store.zscore(DATABASE, "myzset", "member1")).thenReturn(null);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$-1\r\n", result);
    }
    
    // ==================== ZREM 命令测试 ====================
    
    @Test
    public void testZRemNormal() {
        String[] args = {"ZREM", "myzset", "member1", "member2"};
        when(store.zrem(DATABASE, "myzset", "member1", "member2")).thenReturn(2);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
    }
    
    // ==================== ZCARD 命令测试 ====================
    
    @Test
    public void testZCardNormal() {
        String[] args = {"ZCARD", "myzset"};
        when(store.zcard(DATABASE, "myzset")).thenReturn(5);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":5\r\n", result);
    }
    
    // ==================== ZSCAN 命令测试 ====================
    
    @Test
    public void testZScanNormal() {
        String[] args = {"ZSCAN", "myzset", "0"};
        List<Object> scanResult = Arrays.asList(0L, "member1", "1.0", "member2", "2.0");
        when(store.zscan(DATABASE, "myzset", 0L, "*", 10)).thenReturn(scanResult);
        
        Object result = handler.handle(DATABASE, args, store);
        String expected = "*2\r\n$1\r\n0\r\n*4\r\n$7\r\nmember1\r\n$3\r\n1.0\r\n$7\r\nmember2\r\n$3\r\n2.0\r\n";
        assertEquals(expected, result);
    }
    
    @Test
    public void testZScanWithPattern() {
        String[] args = {"ZSCAN", "myzset", "0", "MATCH", "member*", "COUNT", "5"};
        List<Object> scanResult = Arrays.asList(0L, "member1", "1.0");
        when(store.zscan(DATABASE, "myzset", 0L, "member*", 5)).thenReturn(scanResult);
        
        Object result = handler.handle(DATABASE, args, store);
        String expected = "*2\r\n$1\r\n0\r\n*2\r\n$7\r\nmember1\r\n$3\r\n1.0\r\n";
        assertEquals(expected, result);
    }
    
    // ==================== ZREMRANGEBYSCORE 命令测试 ====================
    
    @Test
    public void testZRemRangeByScoreNormal() {
        String[] args = {"ZREMRANGEBYSCORE", "myzset", "1.0", "5.0"};
        when(store.zremrangeByScore(DATABASE, "myzset", 1.0, 5.0)).thenReturn(3);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":3\r\n", result);
    }
    
    @Test
    public void testZRemRangeByScoreWrongArguments() {
        String[] args = {"ZREMRANGEBYSCORE", "myzset", "1.0"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'zremrangebyscore' command\r\n", result);
    }
    
    // ==================== ZREMRANGEBYRANK 命令测试 ====================
    
    @Test
    public void testZRemRangeByRankNormal() {
        String[] args = {"ZREMRANGEBYRANK", "myzset", "0", "2"};
        when(store.zremrangeByRank(DATABASE, "myzset", 0L, 2L)).thenReturn(3);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":3\r\n", result);
    }
    
    // ==================== ZRANK 命令测试 ====================
    
    @Test
    public void testZRankNormal() {
        String[] args = {"ZRANK", "myzset", "member1"};
        when(store.zrank(DATABASE, "myzset", "member1")).thenReturn(5L);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":5\r\n", result);
    }
    
    @Test
    public void testZRankNotFound() {
        String[] args = {"ZRANK", "myzset", "member1"};
        when(store.zrank(DATABASE, "myzset", "member1")).thenReturn(null);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$-1\r\n", result);
    }
    
    // ==================== ZREVRANK 命令测试 ====================
    
    @Test
    public void testZRevRankNormal() {
        String[] args = {"ZREVRANK", "myzset", "member1"};
        when(store.zrevrank(DATABASE, "myzset", "member1")).thenReturn(2L);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":2\r\n", result);
    }
    
    // ==================== ZINCRBY 命令测试 ====================
    
    @Test
    public void testZIncrByNormal() {
        String[] args = {"ZINCRBY", "myzset", "2.5", "member1"};
        when(store.zincrby(DATABASE, "myzset", 2.5, "member1")).thenReturn(3.5);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("$3\r\n3.5\r\n", result);
    }
    
    @Test
    public void testZIncrByWrongArguments() {
        String[] args = {"ZINCRBY", "myzset", "2.5"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'zincrby' command\r\n", result);
    }
    
    // ==================== ZCOUNT 命令测试 ====================
    
    @Test
    public void testZCountNormal() {
        String[] args = {"ZCOUNT", "myzset", "1.0", "5.0"};
        when(store.zcount(DATABASE, "myzset", 1.0, 5.0)).thenReturn(3);
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals(":3\r\n", result);
    }
    
    // ==================== ZPOPMAX 命令测试 ====================
    
    @Test
    public void testZPopMaxNormal() {
        String[] args = {"ZPOPMAX", "myzset"};
        when(store.zpopmax(DATABASE, "myzset", 1)).thenReturn(Arrays.asList("member1", "10.0"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember1\r\n$4\r\n10.0\r\n", result);
    }
    
    @Test
    public void testZPopMaxWithCount() {
        String[] args = {"ZPOPMAX", "myzset", "2"};
        when(store.zpopmax(DATABASE, "myzset", 2)).thenReturn(Arrays.asList("member1", "10.0", "member2", "5.0"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*4\r\n$7\r\nmember1\r\n$4\r\n10.0\r\n$7\r\nmember2\r\n$3\r\n5.0\r\n", result);
    }
    
    @Test
    public void testZPopMaxEmpty() {
        String[] args = {"ZPOPMAX", "myzset"};
        when(store.zpopmax(DATABASE, "myzset", 1)).thenReturn(Collections.emptyList());
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*0\r\n", result);
    }
    
    // ==================== ZPOPMIN 命令测试 ====================
    
    @Test
    public void testZPopMinNormal() {
        String[] args = {"ZPOPMIN", "myzset"};
        when(store.zpopmin(DATABASE, "myzset", 1)).thenReturn(Arrays.asList("member1", "1.0"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember1\r\n$3\r\n1.0\r\n", result);
    }
    
    // ==================== ZREVRANGE 命令测试 ====================
    
    @Test
    public void testZRevRangeNormal() {
        String[] args = {"ZREVRANGE", "myzset", "0", "-1"};
        when(store.zrevrange(DATABASE, "myzset", 0L, -1L)).thenReturn(Arrays.asList("member2", "member1"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember2\r\n$7\r\nmember1\r\n", result);
    }
    
    @Test
    public void testZRevRangeEmpty() {
        String[] args = {"ZREVRANGE", "myzset", "0", "-1"};
        when(store.zrevrange(DATABASE, "myzset", 0L, -1L)).thenReturn(Collections.emptyList());
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*0\r\n", result);
    }
    
    // ==================== ZRANGEBYSCORE 命令测试 ====================
    
    @Test
    public void testZRangeByScoreNormal() {
        String[] args = {"ZRANGEBYSCORE", "myzset", "1.0", "5.0"};
        when(store.zrangeByScore(DATABASE, "myzset", 1.0, 5.0, 0, -1)).thenReturn(Arrays.asList("member1", "member2"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember1\r\n$7\r\nmember2\r\n", result);
    }
    
    @Test
    public void testZRangeByScoreWithLimit() {
        String[] args = {"ZRANGEBYSCORE", "myzset", "1.0", "5.0", "LIMIT", "1", "2"};
        when(store.zrangeByScore(DATABASE, "myzset", 1.0, 5.0, 1, 2)).thenReturn(Arrays.asList("member2", "member3"));
        
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("*2\r\n$7\r\nmember2\r\n$7\r\nmember3\r\n", result);
    }
    
    // ==================== 支持的命令测试 ====================
    
    @Test
    public void testSupportedCommands() {
        java.util.Set<String> supportedCommands = handler.supportedCommands();
        assertEquals(16, supportedCommands.size());
        assert(supportedCommands.contains("ZADD"));
        assert(supportedCommands.contains("ZRANGE"));
        assert(supportedCommands.contains("ZRANGEBYSCORE"));
        assert(supportedCommands.contains("ZSCORE"));
        assert(supportedCommands.contains("ZREM"));
        assert(supportedCommands.contains("ZCARD"));
        assert(supportedCommands.contains("ZSCAN"));
        assert(supportedCommands.contains("ZREMRANGEBYSCORE"));
        assert(supportedCommands.contains("ZREMRANGEBYRANK"));
        assert(supportedCommands.contains("ZRANK"));
        assert(supportedCommands.contains("ZREVRANK"));
        assert(supportedCommands.contains("ZINCRBY"));
        assert(supportedCommands.contains("ZCOUNT"));
        assert(supportedCommands.contains("ZPOPMAX"));
        assert(supportedCommands.contains("ZPOPMIN"));
        assert(supportedCommands.contains("ZREVRANGE"));
    }
    
    // ==================== 未知命令测试 ====================
    
    @Test
    public void testUnknownCommand() {
        String[] args = {"UNKNOWN", "myzset"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR unknown command\r\n", result);
    }
}
