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

class SlaveInfoTest {

    @Mock
    private Channel channel;

    private SlaveInfo slaveInfo;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        
        when(channel.remoteAddress()).thenReturn(new InetSocketAddress("192.168.1.100", 6379));
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(new DefaultChannelPromise(channel));
        
        slaveInfo = new SlaveInfo(channel);
    }

    @Test
    @DisplayName("测试初始化")
    void testInit() {
        assertNotNull(slaveInfo.getSlaveId());
        assertEquals("192.168.1.100", slaveInfo.getIp());
        assertEquals(6379, slaveInfo.getPort());
        assertEquals(ReplicationState.DISCONNECTED, slaveInfo.getState());
        assertFalse(slaveInfo.isOnline());
        assertFalse(slaveInfo.isAuthenticated());
        assertEquals(0, slaveInfo.getOffset());
        assertTrue(slaveInfo.getConnectTime() > 0);
        assertTrue(slaveInfo.getLastInteractionTime() > 0);
    }

    @Test
    @DisplayName("测试设置监听端口")
    void testSetListeningPort() {
        slaveInfo.setListeningPort(9736);
        assertEquals(9736, slaveInfo.getPort());
    }

    @Test
    @DisplayName("测试设置IP")
    void testSetIp() {
        slaveInfo.setIp("10.0.0.1");
        assertEquals("10.0.0.1", slaveInfo.getIp());
    }

    @Test
    @DisplayName("测试能力管理")
    void testCapabilities() {
        slaveInfo.addCapability("psync2");
        slaveInfo.addCapability("eof");
        
        assertTrue(slaveInfo.hasCapability("psync2"));
        assertTrue(slaveInfo.hasCapability("eof"));
        assertFalse(slaveInfo.hasCapability("unknown"));
        assertEquals("psync2,eof", slaveInfo.getCapabilities());
    }

    @Test
    @DisplayName("测试复制ID")
    void testReplId() {
        slaveInfo.setReplId("abc123");
        assertEquals("abc123", slaveInfo.getReplId());
    }

    @Test
    @DisplayName("测试偏移量更新")
    void testOffsetUpdate() {
        slaveInfo.updateOffset(100);
        assertEquals(100, slaveInfo.getOffset());
        
        slaveInfo.incrementOffset(50);
        assertEquals(150, slaveInfo.getOffset());
    }

    @Test
    @DisplayName("测试偏移量更新时间")
    void testOffsetUpdateTime() throws InterruptedException {
        long initialTime = slaveInfo.getLastInteractionTime();
        Thread.sleep(10);
        
        slaveInfo.updateOffset(100);
        
        assertTrue(slaveInfo.getLastInteractionTime() > initialTime);
    }

    @Test
    @DisplayName("测试状态管理")
    void testState() {
        slaveInfo.setState(ReplicationState.FULL_SYNC);
        assertEquals(ReplicationState.FULL_SYNC, slaveInfo.getState());
        
        slaveInfo.setState(ReplicationState.ONLINE);
        assertEquals(ReplicationState.ONLINE, slaveInfo.getState());
    }

    @Test
    @DisplayName("测试数据库索引")
    void testCurrentDb() {
        slaveInfo.setCurrentDb(3);
        assertEquals(3, slaveInfo.getCurrentDb());
    }

    @Test
    @DisplayName("测试认证状态")
    void testAuthenticated() {
        slaveInfo.setAuthenticated(true);
        assertTrue(slaveInfo.isAuthenticated());
        
        slaveInfo.setAuthenticated(false);
        assertFalse(slaveInfo.isAuthenticated());
    }

    @Test
    @DisplayName("测试标志位操作")
    void testFlags() {
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE));
        assertFalse(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_SYNCING));
        
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE));
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_SYNCING));
        
        slaveInfo.removeFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        assertFalse(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE));
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_SYNCING));
    }

    @Test
    @DisplayName("测试设置多个标志位")
    void testSetFlags() {
        slaveInfo.setFlags(SlaveInfo.SLAVE_FLAG_ONLINE | SlaveInfo.SLAVE_FLAG_READONLY);
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_ONLINE));
        assertTrue(slaveInfo.hasFlag(SlaveInfo.SLAVE_FLAG_READONLY));
    }

    @Test
    @DisplayName("测试在线判断")
    void testIsOnline() {
        assertFalse(slaveInfo.isOnline());
        
        slaveInfo.setState(ReplicationState.ONLINE);
        assertFalse(slaveInfo.isOnline());
        
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_ONLINE);
        assertTrue(slaveInfo.isOnline());
    }

    @Test
    @DisplayName("测试同步中判断")
    void testIsSyncing() {
        assertFalse(slaveInfo.isSyncing());
        
        slaveInfo.addFlag(SlaveInfo.SLAVE_FLAG_SYNCING);
        assertTrue(slaveInfo.isSyncing());
    }

    @Test
    @DisplayName("测试获取通道")
    void testGetChannel() {
        assertSame(channel, slaveInfo.getChannel());
    }

    @Test
    @DisplayName("测试信息字符串")
    void testGetInfoString() {
        slaveInfo.setState(ReplicationState.ONLINE);
        slaveInfo.updateOffset(100);
        
        String info = slaveInfo.getInfoString();
        assertTrue(info.contains("ip=192.168.1.100"));
        assertTrue(info.contains("port=6379"));
        assertTrue(info.contains("state=online"));
        assertTrue(info.contains("offset=100"));
    }

    @Test
    @DisplayName("测试toString")
    void testToString() {
        String str = slaveInfo.toString();
        assertNotNull(str);
        assertTrue(str.length() > 0);
    }

    @Test
    @DisplayName("测试标志位常量")
    void testFlagConstants() {
        assertEquals(0, SlaveInfo.SLAVE_FLAG_NONE);
        assertEquals(1, SlaveInfo.SLAVE_FLAG_ONLINE);
        assertEquals(2, SlaveInfo.SLAVE_FLAG_SYNCING);
        assertEquals(4, SlaveInfo.SLAVE_FLAG_READONLY);
        assertEquals(8, SlaveInfo.SLAVE_FLAG_FULL_SYNC);
        assertEquals(16, SlaveInfo.SLAVE_FLAG_PARTIAL_SYNC);
    }
}