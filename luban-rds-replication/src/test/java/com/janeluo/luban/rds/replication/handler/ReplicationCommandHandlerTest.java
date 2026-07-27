package com.janeluo.luban.rds.replication.handler;

import com.janeluo.luban.rds.common.config.RdsConfig;
import com.janeluo.luban.rds.replication.*;
import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReplicationCommandHandlerTest {

    @Mock
    private Channel channel;

    @Mock
    private ReplicationController coordinator;

    private ReplicationCommandHandler handler;
    private RdsConfig config;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));

        config = new RdsConfig();
        config.setPort(9736);

        MasterReplicationManager.initialize(1024 * 1024);

        handler = new ReplicationCommandHandler(config);
        handler.setReplicationCoordinator(coordinator);
    }

    @Test
    @DisplayName("测试支持命令集合")
    void testSupportedCommands() {
        Set<String> commands = handler.supportedCommands();
        
        assertTrue(commands.contains("SLAVEOF"));
        assertTrue(commands.contains("REPLICAOF"));
        assertTrue(commands.contains("PSYNC"));
        assertTrue(commands.contains("SYNC"));
        assertTrue(commands.contains("REPLCONF"));
        assertTrue(commands.contains("WAIT"));
    }

    @Test
    @DisplayName("测试处理SLAVEOF NO ONE")
    void testHandleSlaveofNoOne() {
        String result = handler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "NO", "ONE"});

        assertEquals("+OK\r\n", result);
        // NO ONE 应触发停止复制并清除只读标志
        verify(coordinator, times(1)).stopSlave();
        assertFalse(handler.getReadOnlyModeManager().isSlave());
        assertFalse(handler.getReadOnlyModeManager().isReadOnly());
    }

    @Test
    @DisplayName("测试处理REPLICAOF NO ONE")
    void testHandleReplicaofNoOne() {
        String result = handler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"REPLICAOF", "NO", "ONE"});

        assertEquals("+OK\r\n", result);
        verify(coordinator, times(1)).stopSlave();
    }

    @Test
    @DisplayName("测试处理SLAVEOF - 集群模式")
    void testHandleSlaveofClusterMode() {
        config.setClusterEnabled(true);
        ReplicationCommandHandler clusterHandler = new ReplicationCommandHandler(config);
        clusterHandler.setReplicationCoordinator(coordinator);

        String result = clusterHandler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "127.0.0.1", "6379"});

        assertEquals("-ERR can't set master in cluster mode, use CLUSTER REPLICATE instead\r\n", result);
        // 集群模式下不应触发 startSlave / stopSlave
        verify(coordinator, never()).startSlave(anyString());
        verify(coordinator, never()).stopSlave();
    }

    @Test
    @DisplayName("测试处理SLAVEOF - 参数不足")
    void testHandleSlaveofWrongArgs() {
        String result = handler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "127.0.0.1"});
        
        assertEquals("-ERR wrong number of arguments for 'slaveof' command\r\n", result);
    }

    @Test
    @DisplayName("测试处理REPLCONF")
    void testHandleReplconf() {
        MasterReplicationManager.getInstance().addSlave(channel);
        
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"REPLCONF", "listening-port", "9736"});
        
        assertEquals("+OK\r\n", result);
    }

    @Test
    @DisplayName("测试处理WAIT - 参数不足")
    void testHandleWaitWrongArgs() {
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"WAIT", "1"});
        
        assertEquals("-ERR wrong number of arguments for 'wait' command\r\n", result);
    }

    @Test
    @DisplayName("测试处理WAIT")
    void testHandleWait() {
        MasterReplicationManager manager = MasterReplicationManager.getInstance();
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.ONLINE);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"WAIT", "1", "1000"});
        
        assertTrue(result.startsWith(":"));
        assertTrue(result.endsWith("\r\n"));
    }

    @Test
    @DisplayName("测试处理空命令")
    void testHandleEmptyCommand() {
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[0]);
        
        assertEquals("-ERR empty command\r\n", result);
    }

    @Test
    @DisplayName("测试处理null命令")
    void testHandleNullCommand() {
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                null);
        
        assertEquals("-ERR empty command\r\n", result);
    }

    @Test
    @DisplayName("测试处理未知命令")
    void testHandleUnknownCommand() {
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"UNKNOWN"});
        
        assertEquals("-ERR unknown command: UNKNOWN\r\n", result);
    }

    @Test
    @DisplayName("测试获取复制管理器")
    void testGetReplicationManager() {
        assertNotNull(handler.getReplicationManager());
    }

    @Test
    @DisplayName("测试handle方法默认返回")
    void testHandleDefault() {
        Object result = handler.handle(0, new String[]{"TEST"}, null);
        assertNull(result);
    }

    @Test
    @DisplayName("测试处理SYNC命令")
    void testHandleSync() {
        MasterReplicationManager.getInstance().addSlave(channel);
        
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"SYNC"});
        
        assertNull(result);
    }

    @Test
    @DisplayName("测试处理PSYNC命令")
    void testHandlePsync() {
        MasterReplicationManager.getInstance().addSlave(channel);
        
        io.netty.channel.ChannelHandlerContext ctx = mock(io.netty.channel.ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        
        String result = handler.handleWithChannel(ctx,
                new String[]{"PSYNC", "?", "-1"});
        
        assertNull(result);
    }

    @Test
    @DisplayName("测试处理SLAVEOF设置主节点 - 触发startSlave")
    void testHandleSlaveofSetMaster() {
        String result = handler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "127.0.0.1", "6379"});

        assertEquals("+OK\r\n", result);
        // 应将 host port 传给 coordinator.startSlave
        verify(coordinator, times(1)).startSlave("127.0.0.1 6379");
        // 应同时设置只读从节点标志
        assertTrue(handler.getReadOnlyModeManager().isSlave());
        assertTrue(handler.getReadOnlyModeManager().isReadOnly());
    }

    @Test
    @DisplayName("测试处理REPLICAOF设置主节点 - 触发startSlave")
    void testHandleReplicaofSetMaster() {
        String result = handler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"REPLICAOF", "127.0.0.1", "6379"});

        assertEquals("+OK\r\n", result);
        verify(coordinator, times(1)).startSlave("127.0.0.1 6379");
    }

    @Test
    @DisplayName("测试处理SLAVEOF - 未注入coordinator时仍返回OK且不抛异常")
    void testHandleSlaveofWithoutCoordinator() {
        ReplicationCommandHandler bareHandler = new ReplicationCommandHandler(config);

        String result = bareHandler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "127.0.0.1", "6379"});

        assertEquals("+OK\r\n", result);
        assertTrue(bareHandler.getReadOnlyModeManager().isSlave());
    }

    @Test
    @DisplayName("测试处理SLAVEOF NO ONE - 未注入coordinator时不抛异常")
    void testHandleSlaveofNoOneWithoutCoordinator() {
        ReplicationCommandHandler bareHandler = new ReplicationCommandHandler(config);

        String result = bareHandler.handleWithChannel(mock(io.netty.channel.ChannelHandlerContext.class),
                new String[]{"SLAVEOF", "NO", "ONE"});

        assertEquals("+OK\r\n", result);
        assertFalse(bareHandler.getReadOnlyModeManager().isSlave());
    }
}