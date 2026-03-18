package com.janeluo.luban.rds.replication;

import io.netty.channel.Channel;
import io.netty.channel.DefaultChannelPromise;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MasterReplicationManagerTest {

    @Mock
    private Channel channel;

    private MasterReplicationManager manager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));
        
        MasterReplicationManager.initialize(1024 * 1024);
        manager = MasterReplicationManager.getInstance();
        
        for (SlaveInfo slave : manager.getSlaves()) {
            manager.removeSlave(slave.getChannel());
        }
    }

    @Test
    @DisplayName("测试获取实例")
    void testGetInstance() {
        assertNotNull(manager);
        assertSame(manager, MasterReplicationManager.getInstance());
    }

    @Test
    @DisplayName("测试初始化指定大小")
    void testInitializeWithSize() {
        MasterReplicationManager.initialize(2048 * 1024);
        assertNotNull(MasterReplicationManager.getInstance());
    }

    @Test
    @DisplayName("测试设置密码")
    void testSetRequirepass() {
        manager.setRequirepass("test-password");
        assertNotNull(manager);
    }

    @Test
    @DisplayName("测试添加从节点")
    void testAddSlave() {
        int initialCount = manager.getConnectedSlaves();
        
        SlaveInfo slave = manager.addSlave(channel);
        
        assertNotNull(slave);
        assertEquals(initialCount + 1, manager.getConnectedSlaves());
        assertNotNull(manager.getSlave(channel));
    }

    @Test
    @DisplayName("测试移除从节点")
    void testRemoveSlave() {
        manager.addSlave(channel);
        int count = manager.getConnectedSlaves();
        
        manager.removeSlave(channel);
        
        assertEquals(count - 1, manager.getConnectedSlaves());
        assertNull(manager.getSlave(channel));
    }

    @Test
    @DisplayName("测试移除不存在的从节点")
    void testRemoveNonExistentSlave() {
        int count = manager.getConnectedSlaves();
        
        manager.removeSlave(channel);
        
        assertEquals(count, manager.getConnectedSlaves());
    }

    @Test
    @DisplayName("测试处理REPLCONF listening-port")
    void testHandleReplconfListeningPort() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "listening-port", "9736"});
        
        assertEquals("+OK\r\n", result);
        assertEquals(9736, manager.getSlave(channel).getPort());
    }

    @Test
    @DisplayName("测试处理REPLCONF listening-port - 无效端口")
    void testHandleReplconfListeningPortInvalid() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "listening-port", "invalid"});
        
        assertEquals("-ERR invalid port number\r\n", result);
    }

    @Test
    @DisplayName("测试处理REPLCONF ip-address")
    void testHandleReplconfIpAddress() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "ip-address", "10.0.0.1"});
        
        assertEquals("+OK\r\n", result);
        assertEquals("10.0.0.1", manager.getSlave(channel).getIp());
    }

    @Test
    @DisplayName("测试处理REPLCONF capa")
    void testHandleReplconfCapa() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "capa", "psync2", "eof"});
        
        assertEquals("+OK\r\n", result);
        assertTrue(manager.getSlave(channel).hasCapability("psync2"));
        assertTrue(manager.getSlave(channel).hasCapability("eof"));
    }

    @Test
    @DisplayName("测试处理REPLCONF ack")
    void testHandleReplconfAck() {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.FULL_SYNC);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "1000"});
        
        assertNull(result);
        assertEquals(1000, slave.getOffset());
        assertEquals(ReplicationState.ONLINE, slave.getState());
        assertTrue(slave.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE));
    }

    @Test
    @DisplayName("测试处理REPLCONF ack - 无效偏移量")
    void testHandleReplconfAckInvalidOffset() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "ack", "invalid"});
        
        assertEquals("-ERR invalid offset\r\n", result);
    }

    @Test
    @DisplayName("测试处理REPLCONF 未知子命令")
    void testHandleReplconfUnknown() {
        manager.addSlave(channel);
        
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF", "unknown"});
        
        assertEquals("-ERR unknown subcommand: unknown\r\n", result);
    }

    @Test
    @DisplayName("测试处理REPLCONF 参数不足")
    void testHandleReplconfWrongArgs() {
        String result = manager.handleReplconf(channel, new String[]{"REPLCONF"});
        
        assertEquals("-ERR wrong number of arguments for 'replconf' command\r\n", result);
    }

    @Test
    @DisplayName("测试处理PSYNC 全量同步")
    void testHandlePsyncFullSync() {
        MasterReplicationManager.PsyncResponse response = manager.handlePsync(channel, new String[]{"PSYNC", "?", "-1"});
        
        assertNotNull(response);
        assertNotNull(response.getResponse());
    }

    @Test
    @DisplayName("测试处理PSYNC 部分重同步")
    void testHandlePsyncPartialSync() {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setAuthenticated(true);
        manager.setRequirepass(null);
        
        String replId = manager.getBacklog().getReplId();
        byte[] data = "test data".getBytes();
        manager.getBacklog().append(data);
        
        MasterReplicationManager.PsyncResponse response = manager.handlePsync(channel, new String[]{"PSYNC", replId, "0"});
        
        assertNotNull(response);
        assertTrue(response.getResponse().startsWith("+CONTINUE"));
        assertNotNull(response.getBacklogData());
    }

    @Test
    @DisplayName("测试处理PSYNC 参数不足")
    void testHandlePsyncWrongArgs() {
        MasterReplicationManager.PsyncResponse response = manager.handlePsync(channel, new String[]{"PSYNC", "?"});
        
        assertNotNull(response);
        assertTrue(response.getResponse().contains("-ERR"));
    }

    @Test
    @DisplayName("测试处理PSYNC 无效偏移量")
    void testHandlePsyncInvalidOffset() {
        MasterReplicationManager.PsyncResponse response = manager.handlePsync(channel, new String[]{"PSYNC", "?", "invalid"});
        
        assertNotNull(response);
        assertEquals("-ERR invalid offset\r\n", response.getResponse());
    }

    @Test
    @DisplayName("测试传播命令")
    void testPropagateCommand() {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.ONLINE);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        
        long initialOffset = manager.getBacklog().getMasterReplOffset();
        
        manager.propagateCommand("SET key value");
        
        assertTrue(manager.getBacklog().getMasterReplOffset() > initialOffset);
    }

    @Test
    @DisplayName("测试传播命令 - 无从节点")
    void testPropagateCommandNoSlaves() {
        manager.propagateCommand("SET key value");
        assertNotNull(manager);
    }

    @Test
    @DisplayName("测试传播命令字节数组")
    void testPropagateCommandBytes() {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.ONLINE);
        slave.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        
        long initialOffset = manager.getBacklog().getMasterReplOffset();
        
        manager.propagateCommand("SET key value".getBytes());
        
        assertTrue(manager.getBacklog().getMasterReplOffset() > initialOffset);
    }

    @Test
    @DisplayName("测试发送PING到从节点")
    void testSendPingToSlaves() {
        SlaveInfo slave = manager.addSlave(channel);
        slave.setState(ReplicationState.ONLINE);
        
        manager.sendPingToSlaves();
        
        verify(channel, times(1)).writeAndFlush(any());
    }

    @Test
    @DisplayName("测试发送PING到从节点 - 无从节点")
    void testSendPingToSlavesNoSlaves() {
        manager.sendPingToSlaves();
        assertNotNull(manager);
    }

    @Test
    @DisplayName("测试标记从节点已认证")
    void testMarkSlaveAuthenticated() {
        SlaveInfo slave = manager.addSlave(channel);
        assertFalse(slave.isAuthenticated());
        
        manager.markSlaveAuthenticated(channel);
        
        assertTrue(slave.isAuthenticated());
    }

    @Test
    @DisplayName("测试获取积压缓冲区")
    void testGetBacklog() {
        assertNotNull(manager.getBacklog());
    }

    @Test
    @DisplayName("测试获取从节点列表")
    void testGetSlaves() {
        manager.addSlave(channel);
        
        assertEquals(1, manager.getSlaves().size());
    }

    @Test
    @DisplayName("测试获取同步统计")
    void testSyncStats() {
        long fullSync = manager.getSyncFull();
        long partialOk = manager.getSyncPartialOk();
        long partialErr = manager.getSyncPartialErr();
        
        assertTrue(fullSync >= 0);
        assertTrue(partialOk >= 0);
        assertTrue(partialErr >= 0);
    }

    @Test
    @DisplayName("测试获取复制信息")
    void testGetReplicationInfo() {
        String info = manager.getReplicationInfo();
        
        assertTrue(info.contains("# Replication"));
        assertTrue(info.contains("role:master"));
        assertTrue(info.contains("connected_slaves:"));
        assertTrue(info.contains("master_replid:"));
        assertTrue(info.contains("master_repl_offset:"));
    }

    @Test
    @DisplayName("测试PsyncResponse构造函数")
    void testPsyncResponseConstructor() {
        MasterReplicationManager.PsyncResponse response1 = new MasterReplicationManager.PsyncResponse("+OK\r\n", null);
        assertEquals("+OK\r\n", response1.getResponse());
        assertNull(response1.getBacklogData());
        assertFalse(response1.isNeedRdb());
        
        MasterReplicationManager.PsyncResponse response2 = new MasterReplicationManager.PsyncResponse("+FULLRESYNC\r\n", null, true);
        assertTrue(response2.isNeedRdb());
    }
}