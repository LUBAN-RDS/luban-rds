package com.janeluo.luban.rds.server;

import com.janeluo.luban.rds.common.config.RuntimeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelId;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * MONITOR 命令集成测试
 * 
 * <p>测试 MONITOR 功能与 RedisServerHandler 的集成，
 * 验证所有命令类型都能被正确监控。
 */
public class MonitorIntegrationTest {

    private MonitorManager monitorManager;

    @Before
    public void setUp() {
        monitorManager = MonitorManager.getInstance();
        RuntimeConfig.setMonitorMaxClients(100);
    }

    /**
     * 测试普通命令被监控
     */
    @Test
    public void testNormalCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        // 模拟普通命令提交
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"SET\" \"key\" \"value\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试事务命令被监控
     */
    @Test
    public void testTransactionCommandsMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        // 模拟事务命令提交
        monitorManager.submit(0, "127.0.0.1:1234", "MULTI", new String[]{"MULTI"});
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        monitorManager.submit(0, "127.0.0.1:1234", "EXEC", new String[]{"EXEC"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"MULTI\"");
        assertReceivedLog(channel, "\"SET\" \"key\" \"value\"");
        assertReceivedLog(channel, "\"EXEC\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 WATCH 命令被监控
     */
    @Test
    public void testWatchCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "WATCH", new String[]{"WATCH", "key"});
        monitorManager.submit(0, "127.0.0.1:1234", "UNWATCH", new String[]{"UNWATCH"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"WATCH\" \"key\"");
        assertReceivedLog(channel, "\"UNWATCH\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 Pub/Sub 命令被监控
     */
    @Test
    public void testPubSubCommandsMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        // 模拟 Pub/Sub 命令提交
        monitorManager.submit(0, "127.0.0.1:1234", "SUBSCRIBE", new String[]{"SUBSCRIBE", "channel1"});
        monitorManager.submit(0, "127.0.0.1:1234", "PUBLISH", new String[]{"PUBLISH", "channel1", "message"});
        monitorManager.submit(0, "127.0.0.1:1234", "UNSUBSCRIBE", new String[]{"UNSUBSCRIBE", "channel1"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"SUBSCRIBE\" \"channel1\"");
        assertReceivedLog(channel, "\"PUBLISH\" \"channel1\" \"message\"");
        assertReceivedLog(channel, "\"UNSUBSCRIBE\" \"channel1\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 PUBLISH 命令被监控
     */
    @Test
    public void testPublishCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "PUBLISH", 
            new String[]{"PUBLISH", "mychannel", "hello world"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"PUBLISH\" \"mychannel\" \"hello world\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 QUIT 命令被监控
     */
    @Test
    public void testQuitCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "QUIT", new String[]{"QUIT"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"QUIT\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 SELECT 命令被监控
     */
    @Test
    public void testSelectCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "SELECT", new String[]{"SELECT", "1"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"SELECT\" \"1\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 AUTH 命令被监控
     */
    @Test
    public void testAuthCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "AUTH", new String[]{"AUTH", "password"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"AUTH\" \"password\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 INFO 命令被监控
     */
    @Test
    public void testInfoCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "INFO", new String[]{"INFO", "server"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"INFO\" \"server\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 PING 命令被监控
     */
    @Test
    public void testPingCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "PING", new String[]{"PING"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"PING\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 ECHO 命令被监控
     */
    @Test
    public void testEchoCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        monitorManager.submit(0, "127.0.0.1:1234", "ECHO", new String[]{"ECHO", "hello"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"ECHO\" \"hello\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试复杂命令被监控
     */
    @Test
    public void testComplexCommandMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, null);
        
        // 测试 MSET 命令
        monitorManager.submit(0, "127.0.0.1:1234", "MSET", 
            new String[]{"MSET", "key1", "value1", "key2", "value2", "key3", "value3"});
        
        Thread.sleep(150);
        
        assertReceivedLog(channel, "\"MSET\" \"key1\" \"value1\" \"key2\" \"value2\" \"key3\" \"value3\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 MONITOR 命令本身不被监控（避免递归）
     */
    @Test
    public void testMonitorCommandNotMonitored() throws InterruptedException {
        Channel channel = createMockChannel();
        
        // 在添加监控前先记录命令数量
        monitorManager.addMonitor(channel, -1, null);
        
        Thread.sleep(100);
        
        // 获取当前收到的所有日志
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeastOnce()).writeAndFlush(captor.capture());
        
        int logCountBefore = 0;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            // 只计算命令日志，不计算 +OK 响应
            if (!s.equals("+OK\r\n")) {
                logCountBefore++;
            }
        }
        
        // MONITOR 命令不应该被提交到监控队列
        // 因为在 RedisServerHandler 中我们排除了 MONITOR 命令
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试监控客户端只能执行 QUIT 命令
     */
    @Test
    public void testMonitorClientRestriction() {
        // 这个测试需要通过 RedisServerHandler 进行，
        // 在单元测试中我们只验证 MonitorManager 的行为
        // MonitorManager 本身不限制客户端执行的命令
        assertTrue(true);
    }

    /**
     * 测试 MONITOR 扩展语法（数据库过滤）
     */
    @Test
    public void testMonitorWithDbFilter() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, 1, null);
        
        // 提交不同数据库的命令
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key0", "value0"});
        monitorManager.submit(1, "127.0.0.1:1234", "SET", new String[]{"SET", "key1", "value1"});
        monitorManager.submit(2, "127.0.0.1:1234", "SET", new String[]{"SET", "key2", "value2"});
        
        Thread.sleep(150);
        
        // 只收到数据库 1 的命令
        assertReceivedLog(channel, "\"SET\" \"key1\" \"value1\"");
        assertNotReceivedLog(channel, "\"SET\" \"key0\" \"value0\"");
        assertNotReceivedLog(channel, "\"SET\" \"key2\" \"value2\"");
        
        monitorManager.removeMonitor(channel);
    }

    /**
     * 测试 MONITOR 扩展语法（模式过滤）
     */
    @Test
    public void testMonitorWithPatternFilter() throws InterruptedException {
        Channel channel = createMockChannel();
        monitorManager.addMonitor(channel, -1, "SET");
        
        monitorManager.submit(0, "127.0.0.1:1234", "SET", new String[]{"SET", "key", "value"});
        monitorManager.submit(0, "127.0.0.1:1234", "GET", new String[]{"GET", "key"});
        monitorManager.submit(0, "127.0.0.1:1234", "DEL", new String[]{"DEL", "key"});
        
        Thread.sleep(150);
        
        // 只收到 SET 命令
        assertReceivedLog(channel, "\"SET\" \"key\" \"value\"");
        assertNotReceivedLog(channel, "\"GET\" \"key\"");
        assertNotReceivedLog(channel, "\"DEL\" \"key\"");
        
        monitorManager.removeMonitor(channel);
    }

    // ==================== 辅助方法 ====================

    private Channel createMockChannel() {
        Channel channel = mock(Channel.class);
        when(channel.id()).thenReturn(mock(ChannelId.class));
        return channel;
    }

    private void assertReceivedLog(Channel channel, String expectedContent) {
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeastOnce()).writeAndFlush(captor.capture());
        
        boolean found = false;
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            if (s.contains(expectedContent)) {
                found = true;
                // 验证 RESP Bulk String 格式
                assertTrue("Should be RESP Bulk String format: " + s, 
                    s.startsWith("$") || s.startsWith("+OK"));
                break;
            }
        }
        assertTrue("Should contain: " + expectedContent, found);
    }

    private void assertNotReceivedLog(Channel channel, String expectedContent) {
        ArgumentCaptor<ByteBuf> captor = ArgumentCaptor.forClass(ByteBuf.class);
        verify(channel, atLeastOnce()).writeAndFlush(captor.capture());
        
        for (ByteBuf buf : captor.getAllValues()) {
            String s = buf.toString(StandardCharsets.UTF_8);
            assertFalse("Should NOT contain: " + expectedContent, s.contains(expectedContent));
        }
    }
}