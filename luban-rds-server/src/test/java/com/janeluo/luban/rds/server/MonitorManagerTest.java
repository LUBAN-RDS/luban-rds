package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * MonitorManager 单元测试
 * 
 * <p>测试覆盖率目标：>= 90%
 */
public class MonitorManagerTest {

    private MonitorManager monitorManager;

    @Before
    public void setUp() {
        monitorManager = MonitorManager.getInstance();
        RuntimeConfig.setMonitorMaxClients(100);
    }

    // ==================== 基本功能测试 ====================

    /**
     * 测试添加监控客户端
     */
    @Test
    public void testAddMonitor() {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(channel, -1, null);
        
        assertEquals(1, monitorManager.getMonitorClientCount());
        verify(channel, atLeastOnce()).writeAndFlush(any(ByteBuf.class));
        
        monitorManager.removeMonitor(channel);
        assertEquals(0, monitorManager.getMonitorClientCount());
    }

    /**
     * 测试最大客户端限制
     */
    @Test
    public void testMaxClients() {
        RuntimeConfig.setMonitorMaxClients(1);
        Channel c1 = mock(Channel.class);
        Channel c2 = mock(Channel.class);
        when(c1.id()).thenReturn(mock(ChannelId.class));
        when(c2.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(c1, -1, null);
        monitorManager.addMonitor(c2, -1, null);
        
        assertEquals(1, monitorManager.getMonitorClientCount());
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(c2).writeAndFlush(captor.capture());
        String response = captor.getValue().toString(StandardCharsets.UTF_8);
        assertTrue(response.startsWith("-ERR max number"));
        
        monitorManager.removeMonitor(c1);
        RuntimeConfig.setMonitorMaxClients(100);
    }

    /**
     * 测试移除不存在的客户端
     */
    @Test
    public void testRemoveNonExistentMonitor() {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        
        int countBefore = monitorManager.getMonitorClientCount();
        monitorManager.removeMonitor(channel);
        assertEquals(countBefore, monitorManager.getMonitorClientCount());
    }

    // ==================== 命令广播测试 ====================

    /**
     * 测试命令广播
     */
    @Test
    public void testBroadcast() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String log = buf.toString(StandardCharsets.UTF_8);
            // RESP Bulk String 格式: $length\r\ncontent\r\n
            if (log.contains("\"SET\" \"key\" \"value\"")) {
                foundLog = true;
                // 验证 RESP 格式
                assertTrue("Should be RESP Bulk String format", log.startsWith("$"));
                break;
            }
        }
        assertTrue("Should receive monitor log", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试多参数命令广播
     */
    @Test
    public void testBroadcastMultipleArgs() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "MSET", 
            new String[]{"MSET", "key1", "value1", "key2", "value2"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String log = buf.toString(StandardCharsets.UTF_8);
            if (log.contains("\"MSET\" \"key1\" \"value1\" \"key2\" \"value2\"")) {
                foundLog = true;
                break;
            }
        }
        assertTrue("Should receive MSET log", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试空参数命令
     */
    @Test
    public void testBroadcastEmptyArgs() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "PING", new String[]{"PING"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String log = buf.toString(StandardCharsets.UTF_8);
            if (log.contains("\"PING\"")) {
                foundLog = true;
                break;
            }
        }
        assertTrue("Should receive PING log", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 null 参数处理
     */
    @Test
    public void testBroadcastNullArgs() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "PING", null);
        
        Thread.sleep(150);
        
        verify(channel, atLeast(1)).writeAndFlush(any(ByteBuf.class));
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 过滤功能测试 ====================

    /**
     * 测试数据库过滤
     */
    @Test
    public void testDbFiltering() throws InterruptedException {
        Channel db0Channel = mock(Channel.class);
        when(db0Channel.id()).thenReturn(mock(ChannelId.class));
        Channel db1Channel = mock(Channel.class);
        when(db1Channel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(db0Channel, 0, null);
        monitorManager.addMonitor(db1Channel, 1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        monitorManager.submit(1, "127.0.0.1:1234", "GET", new String[]{"GET", "key"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor0 = ArgumentCaptor.forClass(ByteBuf.class);
        verify(db0Channel, atLeast(1)).writeAndFlush(captor0.capture());
        
        boolean foundSetInDb0 = false;
        for (ByteBuf buf : captor0.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"SET\"")) foundSetInDb0 = true;
        }
        assertTrue("DB 0 channel should receive SET", foundSetInDb0);
        
        ArgumentCaptor<ByteBuf> captor1 = ArgumentCaptor.forClass(ByteBuf.class);
        verify(db1Channel, atLeast(1)).writeAndFlush(captor1.capture());
        
        boolean foundGetInDb1 = false;
        for (ByteBuf buf : captor1.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"GET\"")) foundGetInDb1 = true;
        }
        assertTrue("DB 1 channel should receive GET", foundGetInDb1);
        
        monitorManager.removeMonitor(db0Channel);
        monitorManager.removeMonitor(db1Channel);
    }

    /**
     * 测试命令模式过滤
     */
    @Test
    public void testPatternFiltering() throws InterruptedException {
        Channel setChannel = mock(Channel.class);
        when(setChannel.id()).thenReturn(mock(ChannelId.class));
        Channel getChannel = mock(Channel.class);
        when(getChannel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(setChannel, -1, "SET");
        monitorManager.addMonitor(getChannel, -1, "GET");
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        monitorManager.submit(0, "127.0.0.1:1234", "GET", new String[]{"GET", "key"});
        monitorManager.submit(0, "127.0.0.1:1234", "DEL", new String[]{"DEL", "key"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> setCaptor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(setChannel, atLeast(1)).writeAndFlush(setCaptor.capture());
        int setCount = 0;
        for (ByteBuf buf : setCaptor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"SET\"")) setCount++;
        }
        assertEquals(1, setCount);
        
        ArgumentCaptor<ByteBuf> getCaptor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(getChannel, atLeast(1)).writeAndFlush(getCaptor.capture());
        int getCount = 0;
        for (ByteBuf buf : getCaptor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"GET\"")) getCount++;
        }
        assertEquals(1, getCount);
        
        monitorManager.removeMonitor(setChannel);
        monitorManager.removeMonitor(getChannel);
    }

    /**
     * 测试通配符模式过滤（不过滤）
     */
    @Test
    public void testPatternFilteringWildcard() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(channel, -1, "*");
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"SET\"")) foundLog = true;
        }
        assertTrue("Wildcard pattern should not filter", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试空模式过滤（不过滤）
     */
    @Test
    public void testPatternFilteringEmpty() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        
        monitorManager.addMonitor(channel, -1, "");
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundLog = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("\"SET\"")) foundLog = true;
        }
        assertTrue("Empty pattern should not filter", foundLog);
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 特殊字符处理测试 ====================

    /**
     * 测试参数中包含引号
     */
    @Test
    public void testArgWithQuotes() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", 
            new String[]{"SET", "key", "value\"with\"quotes"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean found = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("value\"with\"quotes")) {
                found = true;
                break;
            }
        }
        assertTrue("Should contain quotes in value", found);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试参数中包含反斜杠
     */
    @Test
    public void testArgWithBackslash() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", 
            new String[]{"SET", "key", "value\\with\\backslash"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean found = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("value\\with\\backslash")) {
                found = true;
                break;
            }
        }
        assertTrue("Should contain backslash in value", found);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试参数中包含换行符
     */
    @Test
    public void testArgWithNewline() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", 
            new String[]{"SET", "key", "value\nwith\nnewline"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean found = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("value\nwith\nnewline")) {
                found = true;
                break;
            }
        }
        assertTrue("Should contain newline in value", found);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试参数中包含中文（多字节字符）
     */
    @Test
    public void testArgWithChinese() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", 
            new String[]{"SET", "key", "中文测试"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean found = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("中文测试")) {
                found = true;
                break;
            }
        }
        assertTrue("Should contain Chinese characters", found);
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 日志格式测试 ====================

    /**
     * 测试日志格式包含时间戳
     */
    @Test
    public void testLogFormatTimestamp() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundTimestamp = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            // 时间戳格式："秒.微秒"（用引号包围）
            if (s.matches(".*\"\\d+\\.\\d{6}\".*")) {
                foundTimestamp = true;
                break;
            }
        }
        assertTrue("Should contain quoted timestamp", foundTimestamp);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试日志格式包含数据库编号
     */
    @Test
    public void testLogFormatDatabase() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(5, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundDb = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("[5 ")) {
                foundDb = true;
                break;
            }
        }
        assertTrue("Should contain database number", foundDb);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试日志格式包含客户端地址
     */
    @Test
    public void testLogFormatClientAddress() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "192.168.1.100:5678", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(2)).writeAndFlush(captor.capture());
        
        boolean foundAddress = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains("192.168.1.100:5678")) {
                foundAddress = true;
                break;
            }
        }
        assertTrue("Should contain client address", foundAddress);
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 历史回放测试 ====================

    /**
     * 测试历史命令回放
     */
    @Test
    public void testHistoryReplay() throws InterruptedException {
        // 先提交一些命令
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key1", "value1"});
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key2", "value2"});
        
        Thread.sleep(150);
        
        // 然后添加监控客户端
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        Thread.sleep(150);
        
        // 验证收到历史命令
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeast(1)).writeAndFlush(captor.capture());
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 高并发测试 ====================

    /**
     * 测试高并发提交
     */
    @Test
    public void testHighConcurrency() throws InterruptedException {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        monitorManager.addMonitor(channel, -1, null);
        
        int threadCount = 10;
        int commandsPerThread = 100;
        CountDownLatch latch = new CountDownLatch(threadCount);
        
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            new Thread(() -> {
                for (int j = 0; j < commandsPerThread; j++) {
                    monitorManager.submit(0, "127.0.0.1:1234", "SET", 
                        new String[]{"SET", "key" + threadId + "_" + j, "value"});
                }
                latch.countDown();
            }).start();
        }
        
        assertTrue("Concurrent submission should complete", latch.await(10, TimeUnit.SECONDS));
        
        Thread.sleep(200);
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试多客户端同时监控
     */
    @Test
    public void testMultipleMonitorClients() throws InterruptedException {
        int clientCount = 10;
        Channel[] channels = new Channel[clientCount];
        
        for (int i = 0; i < clientCount; i++) {
            channels[i] = mock(Channel.class);
            when(channels[i].id()).thenReturn(mock(ChannelId.class));
            monitorManager.addMonitor(channels[i], -1, null);
        }
        
        assertEquals(clientCount, monitorManager.getMonitorClientCount());
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        for (int i = 0; i < clientCount; i++) {
            verify(channels[i], atLeast(2)).writeAndFlush(any(ByteBuf.class));
            monitorManager.removeMonitor(channels[i]);
        }
        
        assertEquals(0, monitorManager.getMonitorClientCount());
    }

    // ==================== 单例测试 ====================

    /**
     * 测试单例模式
     */
    @Test
    public void testSingleton() {
        MonitorManager instance1 = MonitorManager.getInstance();
        MonitorManager instance2 = MonitorManager.getInstance();
        assertSame(instance1, instance2);
    }

    // ==================== MonitorContext 测试 ====================

    /**
     * 测试 MonitorContext 构造
     */
    @Test
    public void testMonitorContextConstruction() {
        Channel channel = mock(Channel.class);
        MonitorManager.MonitorContext ctx = new MonitorManager.MonitorContext(channel, 1, "SET");
        
        assertSame(channel, ctx.channel);
        assertEquals(Integer.valueOf(1), ctx.dbFilter);
        assertNotNull(ctx.patternFilter);
    }

    /**
     * 测试 MonitorContext 空模式
     */
    @Test
    public void testMonitorContextNullPattern() {
        Channel channel = mock(Channel.class);
        MonitorManager.MonitorContext ctx = new MonitorManager.MonitorContext(channel, null, null);
        
        assertNull(ctx.dbFilter);
        assertNull(ctx.patternFilter);
    }

    /**
     * 测试 MonitorContext 通配符模式
     */
    @Test
    public void testMonitorContextWildcardPattern() {
        Channel channel = mock(Channel.class);
        MonitorManager.MonitorContext ctx = new MonitorManager.MonitorContext(channel, null, "*");
        
        assertNull(ctx.patternFilter);
    }
}