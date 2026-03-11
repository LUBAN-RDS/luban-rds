package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.stream.Stream;
import com.janeluo.luban.rds.core.stream.StreamId;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * StreamCommandHandler 单元测试
 * 
 * <p>测试 XADD、XLEN、XRANGE、XREVRANGE、XDEL、XTRIM、XREAD、XINFO 等命令
 */
public class StreamCommandHandlerTest {

    private StreamCommandHandler handler;

    @Mock
    private MemoryStore store;

    private static final int DATABASE = 0;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        handler = new StreamCommandHandler();
    }

    // ==================== XADD 命令测试 ====================

    @Test
    public void testXAddBasic() {
        String[] args = {"XADD", "mystream", "*", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
        verify(store).set(eq(DATABASE), eq("mystream"), any(Stream.class));
    }

    @Test
    public void testXAddWithSpecificId() {
        String[] args = {"XADD", "mystream", "1000-0", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("1000-0"));
    }

    @Test
    public void testXAddWithMultipleFields() {
        String[] args = {"XADD", "mystream", "*", "field1", "value1", "field2", "value2"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
    }

    @Test
    public void testXAddWithMaxLen() {
        String[] args = {"XADD", "mystream", "MAXLEN", "5", "*", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
    }

    @Test
    public void testXAddWithMaxLenApproximate() {
        String[] args = {"XADD", "mystream", "MAXLEN", "~", "5", "*", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
    }

    @Test
    public void testXAddWithMinId() {
        String[] args = {"XADD", "mystream", "MINID", "1000-0", "*", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
    }

    @Test
    public void testXAddNoMkStream() {
        String[] args = {"XADD", "mystream", "NOMKSTREAM", "*", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("$-1\r\n", result);
        verify(store, never()).set(anyInt(), anyString(), any());
    }

    @Test
    public void testXAddNoMkStreamExistingStream() {
        String[] args = {"XADD", "mystream", "NOMKSTREAM", "*", "field1", "value1"};
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        Object result = handler.handle(DATABASE, args, store);

        assertTrue(result.toString().contains("$"));
    }

    @Test
    public void testXAddWrongType() {
        String[] args = {"XADD", "mykey", "*", "field1", "value1"};
        when(store.get(DATABASE, "mykey")).thenReturn("not a stream");

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", result);
    }

    @Test
    public void testXAddWrongArguments() {
        String[] args = {"XADD", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xadd' command\r\n", result);
    }

    @Test
    public void testXAddOddFieldCount() {
        String[] args = {"XADD", "mystream", "*", "field1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR wrong number of arguments for 'xadd' command\r\n", result);
    }

    @Test
    public void testXAddInvalidId() {
        String[] args = {"XADD", "mystream", "invalid", "field1", "value1"};
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR Invalid stream ID specified as stream command argument\r\n", result);
    }

    @Test
    public void testXAddIdSmallerThanLast() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XADD", "mystream", "1000-0", "field1", "value1"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR The ID specified in XADD is equal or smaller than the target stream top item\r\n", result);
    }

    // ==================== XLEN 命令测试 ====================

    @Test
    public void testXLen() {
        Stream stream = new Stream();
        stream.addEntry(null, java.util.Collections.singletonMap("f", "v"));
        stream.addEntry(null, java.util.Collections.singletonMap("f", "v"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XLEN", "mystream"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":2\r\n", result);
    }

    @Test
    public void testXLenEmpty() {
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        String[] args = {"XLEN", "mystream"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":0\r\n", result);
    }

    @Test
    public void testXLenWrongType() {
        when(store.get(DATABASE, "mykey")).thenReturn("not a stream");

        String[] args = {"XLEN", "mykey"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", result);
    }

    @Test
    public void testXLenWrongArguments() {
        String[] args = {"XLEN"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xlen' command\r\n", result);
    }

    // ==================== XRANGE 命令测试 ====================

    @Test
    public void testXRange() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "-", "+"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*3")); // 3 entries
        assertTrue(resultStr.contains("1000-0"));
        assertTrue(resultStr.contains("2000-0"));
        assertTrue(resultStr.contains("3000-0"));
    }

    @Test
    public void testXRangeWithCount() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "-", "+", "COUNT", "2"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*2")); // 2 entries
    }

    @Test
    public void testXRangeWithSpecificRange() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "1000-0", "2000-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*2")); // 2 entries
        assertTrue(resultStr.contains("1000-0"));
        assertTrue(resultStr.contains("2000-0"));
        assertFalse(resultStr.contains("3000-0"));
    }

    @Test
    public void testXRangeExclusiveStart() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "(1000-0", "2000-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*1")); // 1 entry
        assertTrue(resultStr.contains("2000-0"));
        assertFalse(resultStr.contains("1000-0"));
    }

    @Test
    public void testXRangeExclusiveEnd() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "1000-0", "(2000-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*1")); // 1 entry
        assertTrue(resultStr.contains("1000-0"));
        assertFalse(resultStr.contains("2000-0"));
    }

    @Test
    public void testXRangeEmpty() {
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        String[] args = {"XRANGE", "mystream", "-", "+"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("*0\r\n", result);
    }

    @Test
    public void testXRangeWrongArguments() {
        String[] args = {"XRANGE", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xrange' command\r\n", result);
    }

    @Test
    public void testXRangeInvalidId() {
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XRANGE", "mystream", "invalid", "+"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR Invalid stream ID specified as stream command argument\r\n", result);
    }

    // ==================== XREVRANGE 命令测试 ====================

    @Test
    public void testXRevRange() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XREVRANGE", "mystream", "+", "-"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*3")); // 3 entries
        // 验证顺序是逆序的（3000-0 在前）
        int idx3000 = resultStr.indexOf("3000-0");
        int idx2000 = resultStr.indexOf("2000-0");
        int idx1000 = resultStr.indexOf("1000-0");
        assertTrue(idx3000 < idx2000);
        assertTrue(idx2000 < idx1000);
    }

    @Test
    public void testXRevRangeWithCount() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XREVRANGE", "mystream", "+", "-", "COUNT", "2"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("*2")); // 2 entries
    }

    @Test
    public void testXRevRangeWrongArguments() {
        String[] args = {"XREVRANGE", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xrevrange' command\r\n", result);
    }

    // ==================== XDEL 命令测试 ====================

    @Test
    public void testXDel() {
        Stream stream = new Stream();
        StreamId id1 = stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XDEL", "mystream", "1000-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":1\r\n", result);
        assertEquals(1L, stream.getLength());
    }

    @Test
    public void testXDelMultiple() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XDEL", "mystream", "1000-0", "2000-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":2\r\n", result);
        assertEquals(1L, stream.getLength());
    }

    @Test
    public void testXDelNonExistent() {
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XDEL", "mystream", "999-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":0\r\n", result);
    }

    @Test
    public void testXDelEmptyStream() {
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        String[] args = {"XDEL", "mystream", "1000-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":0\r\n", result);
    }

    @Test
    public void testXDelWrongArguments() {
        String[] args = {"XDEL", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xdel' command\r\n", result);
    }

    @Test
    public void testXDelInvalidId() {
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XDEL", "mystream", "invalid"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR Invalid stream ID specified as stream command argument\r\n", result);
    }

    // ==================== XTRIM 命令测试 ====================

    @Test
    public void testXTrimMaxLen() {
        Stream stream = new Stream();
        for (int i = 0; i < 10; i++) {
            stream.addEntry(new StreamId(1000 + i, 0), java.util.Collections.singletonMap("f", "v"));
        }
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XTRIM", "mystream", "MAXLEN", "5"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":5\r\n", result);
        assertEquals(5L, stream.getLength());
    }

    @Test
    public void testXTrimMaxLenApproximate() {
        Stream stream = new Stream();
        for (int i = 0; i < 10; i++) {
            stream.addEntry(new StreamId(1000 + i, 0), java.util.Collections.singletonMap("f", "v"));
        }
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XTRIM", "mystream", "MAXLEN", "~", "5"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":5\r\n", result);
    }

    @Test
    public void testXTrimMinId() {
        Stream stream = new Stream();
        for (int i = 0; i < 10; i++) {
            stream.addEntry(new StreamId(1000 + i * 100, 0), java.util.Collections.singletonMap("f", "v"));
        }
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XTRIM", "mystream", "MINID", "1300-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":3\r\n", result); // 删除 1000, 1100, 1200
    }

    @Test
    public void testXTrimEmptyStream() {
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        String[] args = {"XTRIM", "mystream", "MAXLEN", "5"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals(":0\r\n", result);
    }

    @Test
    public void testXTrimWrongArguments() {
        String[] args = {"XTRIM", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xtrim' command\r\n", result);
    }

    @Test
    public void testXTrimInvalidMaxLen() {
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XTRIM", "mystream", "MAXLEN", "abc"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR value is not an integer or out of range\r\n", result);
    }

    @Test
    public void testXTrimInvalidMinId() {
        Stream stream = new Stream();
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XTRIM", "mystream", "MINID", "invalid"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR Invalid stream ID specified as stream command argument\r\n", result);
    }

    // ==================== XREAD 命令测试 ====================

    @Test
    public void testXRead() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        // 使用 COUNT 限制返回数量，避免 Integer.MAX_VALUE 导致内存问题
        String[] args = {"XREAD", "COUNT", "10", "STREAMS", "mystream", "0-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("mystream"));
        assertTrue(resultStr.contains("1000-0"));
        assertTrue(resultStr.contains("2000-0"));
    }

    @Test
    public void testXReadWithCount() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        stream.addEntry(new StreamId(3000, 0), java.util.Collections.singletonMap("f", "v3"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XREAD", "COUNT", "2", "STREAMS", "mystream", "0-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("mystream"));
    }

    @Test
    public void testXReadWithDollar() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        // 使用 COUNT 限制返回数量
        String[] args = {"XREAD", "COUNT", "10", "STREAMS", "mystream", "$"};

        Object result = handler.handle(DATABASE, args, store);

        // $ 表示从最新消息之后开始，非阻塞模式返回 null
        assertEquals("$-1\r\n", result);
    }

    @Test
    public void testXReadMultipleStreams() {
        Stream stream1 = new Stream();
        stream1.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        Stream stream2 = new Stream();
        stream2.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));

        when(store.get(DATABASE, "stream1")).thenReturn(stream1);
        when(store.get(DATABASE, "stream2")).thenReturn(stream2);

        // 使用 COUNT 限制返回数量
        String[] args = {"XREAD", "COUNT", "10", "STREAMS", "stream1", "stream2", "0-0", "0-0"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("stream1"));
        assertTrue(resultStr.contains("stream2"));
    }

    @Test
    public void testXReadNoNewMessages() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        // 从 2000-0 之后读取，没有新消息
        String[] args = {"XREAD", "COUNT", "10", "STREAMS", "mystream", "2000-0"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("$-1\r\n", result);
    }

    @Test
    public void testXReadWrongArguments() {
        String[] args = {"XREAD"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xread' command\r\n", result);
    }

    @Test
    public void testXReadSyntaxError() {
        // STREAMS 后面需要成对的 key 和 ID
        String[] args = {"XREAD", "STREAMS", "mystream", "id1", "extra"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR syntax error\r\n", result);
    }

    // ==================== XINFO 命令测试 ====================

    @Test
    public void testXInfoStream() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        stream.addEntry(new StreamId(2000, 0), java.util.Collections.singletonMap("f", "v2"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XINFO", "STREAM", "mystream"};

        Object result = handler.handle(DATABASE, args, store);

        String resultStr = result.toString();
        assertTrue(resultStr.contains("length"));
        assertTrue(resultStr.contains("2"));
        assertTrue(resultStr.contains("last-generated-id"));
    }

    @Test
    public void testXInfoGroups() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XINFO", "GROUPS", "mystream"};

        Object result = handler.handle(DATABASE, args, store);

        // 没有消费者组时返回空列表
        assertTrue("Result should be a List", result instanceof java.util.List);
        assertTrue("Result should be empty", ((java.util.List<?>) result).isEmpty());
    }

    @Test
    public void testXInfoConsumers() {
        Stream stream = new Stream();
        stream.addEntry(new StreamId(1000, 0), java.util.Collections.singletonMap("f", "v1"));
        when(store.get(DATABASE, "mystream")).thenReturn(stream);

        String[] args = {"XINFO", "CONSUMERS", "mystream", "mygroup"};

        Object result = handler.handle(DATABASE, args, store);

        // 没有消费者组时返回空列表
        assertTrue("Result should be a List", result instanceof java.util.List);
        assertTrue("Result should be empty", ((java.util.List<?>) result).isEmpty());
    }

    @Test
    public void testXInfoNoKey() {
        when(store.get(DATABASE, "mystream")).thenReturn(null);

        String[] args = {"XINFO", "STREAM", "mystream"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-ERR no such key\r\n", result);
    }

    @Test
    public void testXInfoWrongType() {
        when(store.get(DATABASE, "mykey")).thenReturn("not a stream");

        String[] args = {"XINFO", "STREAM", "mykey"};

        Object result = handler.handle(DATABASE, args, store);

        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", result);
    }

    @Test
    public void testXInfoWrongArguments() {
        String[] args = {"XINFO"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR wrong number of arguments for 'xinfo' command\r\n", result);
    }

    // ==================== 未知命令测试 ====================

    @Test
    public void testUnknownCommand() {
        String[] args = {"UNKNOWN", "mystream"};
        Object result = handler.handle(DATABASE, args, store);
        assertEquals("-ERR unknown command\r\n", result);
    }

    // ==================== 支持的命令测试 ====================

    @Test
    public void testSupportedCommands() {
        java.util.Set<String> supportedCommands = handler.supportedCommands();

        assertEquals(8, supportedCommands.size());
        assertTrue(supportedCommands.contains("XADD"));
        assertTrue(supportedCommands.contains("XLEN"));
        assertTrue(supportedCommands.contains("XRANGE"));
        assertTrue(supportedCommands.contains("XREVRANGE"));
        assertTrue(supportedCommands.contains("XDEL"));
        assertTrue(supportedCommands.contains("XTRIM"));
        assertTrue(supportedCommands.contains("XREAD"));
        assertTrue(supportedCommands.contains("XINFO"));
    }
}
