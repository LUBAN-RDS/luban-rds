package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class SlaveReplicationClientTest {

    private RdsConfig config;
    private TestCallback callback;
    private SlaveReplicationClient client;

    @BeforeEach
    void setUp() {
        config = new RdsConfig();
        config.setPort(9737);
        config.setReplicaof("127.0.0.1:9736");
        config.setReplTimeout(60);
        config.setReplPingSlavePeriod(10);
        config.setReplReconnectInterval(5000L);
        
        callback = new TestCallback();
        client = new SlaveReplicationClient(config, callback);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.stop();
        }
    }

    @Test
    @DisplayName("测试初始化")
    void testInit() {
        assertEquals(ReplicationState.DISCONNECTED, client.getState());
        assertEquals(0, client.getReplicationOffset());
        assertFalse(client.isOnline());
    }

    @Test
    @DisplayName("测试无主节点配置")
    void testNoMasterConfig() {
        RdsConfig noMasterConfig = new RdsConfig();
        SlaveReplicationClient noMasterClient = new SlaveReplicationClient(noMasterConfig, callback);
        
        noMasterClient.start();
        assertEquals(ReplicationState.DISCONNECTED, noMasterClient.getState());
        
        noMasterClient.stop();
    }

    @Test
    @DisplayName("测试null回调")
    void testNullCallback() {
        SlaveReplicationClient nullCallbackClient = new SlaveReplicationClient(config, null);
        assertNotNull(nullCallbackClient);
        nullCallbackClient.stop();
    }

    @Test
    @DisplayName("测试空主节点地址")
    void testEmptyMasterAddress() {
        RdsConfig emptyConfig = new RdsConfig();
        emptyConfig.setReplicaof("");
        
        SlaveReplicationClient emptyClient = new SlaveReplicationClient(emptyConfig, callback);
        assertEquals(ReplicationState.DISCONNECTED, emptyClient.getState());
        
        emptyClient.stop();
    }

    @Test
    @DisplayName("测试停止")
    void testStop() {
        client.stop();
        assertEquals(ReplicationState.DISCONNECTED, client.getState());
    }

    @Test
    @DisplayName("测试重复停止")
    void testDoubleStop() {
        client.stop();
        client.stop();
        assertEquals(ReplicationState.DISCONNECTED, client.getState());
    }

    @Test
    @DisplayName("测试获取状态")
    void testGetState() {
        assertEquals(ReplicationState.DISCONNECTED, client.getState());
    }

    @Test
    @DisplayName("测试获取复制偏移量")
    void testGetReplicationOffset() {
        assertEquals(0, client.getReplicationOffset());
    }

    @Test
    @DisplayName("测试是否在线")
    void testIsOnline() {
        assertFalse(client.isOnline());
    }

    @Test
    @DisplayName("测试发送ACK - 非在线状态")
    void testSendAckNotOnline() {
        client.sendAck();
        assertFalse(client.isOnline());
    }

    @Test
    @DisplayName("测试解析主节点地址")
    void testParseMasterAddress() {
        RdsConfig testConfig = new RdsConfig();
        testConfig.setReplicaof("192.168.1.100:6379");
        
        SlaveReplicationClient testClient = new SlaveReplicationClient(testConfig, callback);
        assertNotNull(testClient);
        
        testClient.stop();
    }

    @Test
    @DisplayName("测试解析主节点地址 - 默认端口")
    void testParseMasterAddressDefaultPort() {
        RdsConfig testConfig = new RdsConfig();
        testConfig.setReplicaof("192.168.1.100");
        
        SlaveReplicationClient testClient = new SlaveReplicationClient(testConfig, callback);
        assertNotNull(testClient);
        
        testClient.stop();
    }

    @Test
    @DisplayName("测试解析主节点地址 - 带空格")
    void testParseMasterAddressWithSpaces() {
        RdsConfig testConfig = new RdsConfig();
        testConfig.setReplicaof("  192.168.1.100 : 6380  ");
        
        SlaveReplicationClient testClient = new SlaveReplicationClient(testConfig, callback);
        assertNotNull(testClient);
        
        testClient.stop();
    }

    @Test
    @DisplayName("测试重复启动")
    void testDoubleStart() {
        client.start();
        client.start();
        
        client.stop();
    }

    @Test
    @DisplayName("测试回调接口")
    void testCallback() {
        callback.onConnectionFailed(new RuntimeException("test"));
        assertTrue(callback.connectionFailedCalled);
        
        callback.onHandshakeFailed("test error");
        assertTrue(callback.handshakeFailedCalled);
        
        callback.onDisconnected();
        assertTrue(callback.disconnectedCalled);
        
        callback.onFullSync("repl-id", 100L);
        assertTrue(callback.fullSyncCalled);
        assertEquals("repl-id", callback.lastReplId);
        
        callback.onPartialSync("repl-id2", 200L);
        assertTrue(callback.partialSyncCalled);
        
        ByteBuf data = Unpooled.copiedBuffer("test", StandardCharsets.UTF_8);
        callback.onRdbData(data);
        assertTrue(callback.rdbDataCalled);
        
        callback.onOnline();
        assertTrue(callback.onlineCalled);
        
        ByteBuf cmdData = Unpooled.copiedBuffer("SET key value", StandardCharsets.UTF_8);
        callback.onCommandPropagation(cmdData);
        assertTrue(callback.commandPropagationCalled);
        
        assertEquals("repl-id2", callback.getReplId());
        assertEquals(200L, callback.getReplOffset());
    }

    @Test
    @DisplayName("测试配置获取")
    void testConfigMethods() {
        assertEquals(9737, config.getPort());
        assertEquals("127.0.0.1:9736", config.getReplicaof());
        assertEquals(60, config.getReplTimeout());
    }

    @Test
    @DisplayName("测试启动后停止")
    void testStartThenStop() {
        client.start();
        client.stop();
        assertEquals(ReplicationState.DISCONNECTED, client.getState());
    }

    private static class TestCallback implements ReplicationCallback {
        boolean connectionFailedCalled = false;
        boolean handshakeFailedCalled = false;
        boolean disconnectedCalled = false;
        boolean fullSyncCalled = false;
        boolean partialSyncCalled = false;
        boolean rdbDataCalled = false;
        boolean onlineCalled = false;
        boolean commandPropagationCalled = false;
        String lastReplId = null;
        long lastOffset = 0;

        @Override
        public void onConnectionFailed(Throwable cause) {
            connectionFailedCalled = true;
        }

        @Override
        public void onHandshakeFailed(String error) {
            handshakeFailedCalled = true;
        }

        @Override
        public void onDisconnected() {
            disconnectedCalled = true;
        }

        @Override
        public void onFullSync(String replId, long offset) {
            fullSyncCalled = true;
            lastReplId = replId;
            lastOffset = offset;
        }

        @Override
        public void onPartialSync(String replId, long offset) {
            partialSyncCalled = true;
            lastReplId = replId;
            lastOffset = offset;
        }

        @Override
        public void onRdbData(ByteBuf data) {
            rdbDataCalled = true;
            data.release();
        }

        @Override
        public void onOnline() {
            onlineCalled = true;
        }

        @Override
        public void onCommandPropagation(ByteBuf data) {
            commandPropagationCalled = true;
            data.release();
        }

        @Override
        public String getReplId() {
            return lastReplId;
        }

        @Override
        public long getReplOffset() {
            return lastOffset;
        }
    }
}