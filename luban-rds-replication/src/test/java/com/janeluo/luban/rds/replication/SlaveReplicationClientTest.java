package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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

    // ==================== PSYNC 响应路由测试 (C2) ====================

    @Test
    @DisplayName("PSYNC 响应路由到 handlePsyncResponse 而非 handleReplconfResponse - FULLRESYNC")
    void testPsyncResponseRoutedToPsyncHandler_FullResync() throws Exception {
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        String fullResync = "+FULLRESYNC abc123def456 1000\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(fullResync, StandardCharsets.UTF_8));

        // 路由正确：触发 onFullSync，进入 FULL_SYNC
        assertTrue(callback.fullSyncCalled, "FULLRESYNC 应触发 onFullSync 回调");
        assertFalse(callback.partialSyncCalled, "FULLRESYNC 不应触发 onPartialSync");
        assertEquals(ReplicationState.FULL_SYNC, client.getState());
        assertEquals("abc123def456", callback.lastReplId);
        assertEquals(1000L, callback.lastOffset);
        assertEquals("abc123def456", client.getMasterReplId());
        assertEquals(1000L, client.getReplicationOffset());
    }

    @Test
    @DisplayName("PSYNC 响应路由到 handlePsyncResponse - CONTINUE")
    void testPsyncResponseRoutedToPsyncHandler_Continue() throws Exception {
        // 模拟已有 replid/offset 的重连场景
        client.setMasterReplId("existing-replid");
        client.setReplicationOffset(500L);
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        String continueResp = "+CONTINUE newreplid789\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(continueResp, StandardCharsets.UTF_8));

        assertTrue(callback.partialSyncCalled, "CONTINUE 应触发 onPartialSync 回调");
        assertFalse(callback.fullSyncCalled, "CONTINUE 不应触发 onFullSync");
        assertEquals(ReplicationState.PARTIAL_SYNC, client.getState());
        assertEquals("newreplid789", callback.lastReplId);
        assertEquals(500L, callback.lastOffset, "CONTINUE 不携带 offset，应保留现有偏移量");
        assertEquals("newreplid789", client.getMasterReplId());
    }

    @Test
    @DisplayName("CONTINUE 响应不带 replid 时保留现有 replid")
    void testPsyncResponse_ContinueWithoutReplid() throws Exception {
        client.setMasterReplId("old-replid");
        client.setReplicationOffset(42L);
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        String continueResp = "+CONTINUE\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(continueResp, StandardCharsets.UTF_8));

        assertTrue(callback.partialSyncCalled);
        assertEquals(ReplicationState.PARTIAL_SYNC, client.getState());
        assertEquals("old-replid", client.getMasterReplId(), "无 replid 的 CONTINUE 应保留现有 replid");
        assertEquals("old-replid", callback.lastReplId);
        assertEquals(42L, callback.lastOffset);
    }

    @Test
    @DisplayName("PSYNC 异常响应回退到 DISCONNECTED")
    void testPsyncResponse_ErrorFallsBackToDisconnected() throws Exception {
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        String errResp = "-ERR PSYNC failed\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(errResp, StandardCharsets.UTF_8));

        assertFalse(callback.fullSyncCalled);
        assertFalse(callback.partialSyncCalled);
        assertEquals(ReplicationState.DISCONNECTED, client.getState(), "错误响应应回退到 DISCONNECTED");
    }

    @Test
    @DisplayName("FULLRESYNC 解析失败时不崩溃且进入 DISCONNECTED")
    void testPsyncResponse_MalformedFullResync() throws Exception {
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        // 缺少 offset，格式异常
        String malformed = "+FULLRESYNC onlyreplid\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(malformed, StandardCharsets.UTF_8));

        assertEquals(ReplicationState.DISCONNECTED, client.getState(),
                "格式异常的 FULLRESYNC 应安全回退到 DISCONNECTED");
    }

    @Test
    @DisplayName("PSYNC 响应不再被路由到 handleReplconfResponse")
    void testPsyncResponseNotRoutedToReplconfHandler() throws Exception {
        // 处于 HANDSHAKE_PSYNC 时收到 +OK（旧的错误路由会把它当 REPLCONF 响应静默忽略）
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);

        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));

        // 既非 FULLRESYNC 也非 CONTINUE，应被视为异常并回退
        assertFalse(callback.fullSyncCalled);
        assertFalse(callback.partialSyncCalled);
        assertNotEquals(ReplicationState.HANDSHAKE_PSYNC, client.getState(),
                "+OK 在 PSYNC 阶段不应被当作正常响应");
    }

    // ==================== REPLCONF 逐条等待 + 5s timeout 测试 (C2 方案 A) ====================

    @Test
    @DisplayName("REPLCONF 逐条等待：PORT +OK 后才推进到 IP，不再一次性发送三条")
    void testReplconfSequentialWait_PortOkAdvancesToIp() throws Exception {
        // 进入发送 REPLCONF PORT 的状态
        invokeSendReplConf(client);

        // 发送 PORT 后应停留在 HANDSHAKE_REPLCONF_PORT，等待 +OK
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState(),
                "发送 PORT 后状态应为 HANDSHAKE_REPLCONF_PORT");

        // 收到 +OK 后应在回调内推进到 IP（而非直接跳到 PSYNC）
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));

        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState(),
                "PORT +OK 后状态应推进到 HANDSHAKE_REPLCONF_IP，而非直接进入 PSYNC");
        assertNotEquals(ReplicationState.HANDSHAKE_PSYNC, client.getState(),
                "PORT +OK 后不应立即进入 PSYNC（旧实现的问题）");
    }

    @Test
    @DisplayName("REPLCONF 逐条等待：IP +OK 后才推进到 CAPA")
    void testReplconfSequentialWait_IpOkAdvancesToCapa() throws Exception {
        invokeSendReplConf(client);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState());

        // IP +OK 后推进到 CAPA
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));

        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_CAPA, client.getState(),
                "IP +OK 后状态应推进到 HANDSHAKE_REPLCONF_CAPA");
        assertNotEquals(ReplicationState.HANDSHAKE_PSYNC, client.getState(),
                "IP +OK 后不应立即进入 PSYNC");
    }

    @Test
    @DisplayName("REPLCONF 逐条等待：CAPA +OK 后才进入 PSYNC")
    void testReplconfSequentialWait_CapaOkAdvancesToPsync() throws Exception {
        invokeSendReplConf(client);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8)); // PORT -> IP
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8)); // IP -> CAPA
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_CAPA, client.getState());

        // CAPA +OK 后才进入 PSYNC
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));

        assertEquals(ReplicationState.HANDSHAKE_PSYNC, client.getState(),
                "CAPA +OK 后状态应推进到 HANDSHAKE_PSYNC");
    }

    @Test
    @DisplayName("REPLCONF 完整握手序列：PORT->IP->CAPA->PSYNC 逐步推进")
    void testReplconfSequentialWait_FullSequence() throws Exception {
        invokeSendReplConf(client);
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_CAPA, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
        assertEquals(ReplicationState.HANDSHAKE_PSYNC, client.getState());

        // 进入 PSYNC 后正常 FULLRESYNC 流程仍可继续
        String fullResync = "+FULLRESYNC replid123 42\r\n";
        invokeHandleResponse(client, Unpooled.copiedBuffer(fullResync, StandardCharsets.UTF_8));
        assertEquals(ReplicationState.FULL_SYNC, client.getState());
        assertTrue(callback.fullSyncCalled);
    }

    @Test
    @DisplayName("REPLCONF 错误响应（-ERR）回退到 DISCONNECTED")
    void testReplconfErrorResponseFallsBackToDisconnected() throws Exception {
        invokeSendReplConf(client);
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("-ERR unknown command\r\n",
                StandardCharsets.UTF_8));

        assertEquals(ReplicationState.DISCONNECTED, client.getState(),
                "REPLCONF -ERR 响应应回退到 DISCONNECTED");
        assertTrue(callback.disconnectedCalled, "应触发 onDisconnected 回调");
    }

    @Test
    @DisplayName("REPLCONF IP 阶段错误响应也回退到 DISCONNECTED")
    void testReplconfIpErrorResponseFallsBack() throws Exception {
        invokeSendReplConf(client);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8)); // PORT -> IP
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("-ERR bad ip\r\n",
                StandardCharsets.UTF_8));

        assertEquals(ReplicationState.DISCONNECTED, client.getState());
        assertTrue(callback.disconnectedCalled);
    }

    @Test
    @DisplayName("REPLCONF CAPA 阶段错误响应也回退到 DISCONNECTED")
    void testReplconfCapaErrorResponseFallsBack() throws Exception {
        invokeSendReplConf(client);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8)); // PORT -> IP
        invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8)); // IP -> CAPA
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_CAPA, client.getState());

        invokeHandleResponse(client, Unpooled.copiedBuffer("-ERR unsupported capa\r\n",
                StandardCharsets.UTF_8));

        assertEquals(ReplicationState.DISCONNECTED, client.getState());
        assertTrue(callback.disconnectedCalled);
    }

    @Test
    @DisplayName("REPLCONF 5s timeout 触发回退 DISCONNECTED")
    void testReplconfTimeoutFallsBackToDisconnected() throws Exception {
        // 注入快速 scheduler + 短超时，避免测试等待 5s
        ScheduledExecutorService fastScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-replconf-timeout");
            t.setDaemon(true);
            return t;
        });
        setHandshakeScheduler(client, fastScheduler);
        setReplconfTimeoutMs(client, 50L);

        try {
            invokeSendReplConf(client);
            assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState(),
                    "发送 PORT 后应处于等待 +OK 状态");

            // 不发送 +OK，等待 timeout 触发
            assertTrue(waitForState(client, ReplicationState.DISCONNECTED, 2000),
                    "超时后状态应回退到 DISCONNECTED");

            assertTrue(callback.disconnectedCalled, "超时应触发 onDisconnected 回调");
        } finally {
            fastScheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("REPLCONF 收到 +OK 时取消对应 timeout，不误触发回退")
    void testReplconfOkCancelsTimeout() throws Exception {
        ScheduledExecutorService fastScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "test-replconf-timeout-cancel");
            t.setDaemon(true);
            return t;
        });
        setHandshakeScheduler(client, fastScheduler);
        setReplconfTimeoutMs(client, 100L);

        try {
            invokeSendReplConf(client);
            assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState());

            // 在 PORT timeout 触发前发送 +OK，应取消 PORT timeout 并推进到 IP
            Thread.sleep(20);
            invokeHandleResponse(client, Unpooled.copiedBuffer("+OK\r\n", StandardCharsets.UTF_8));
            assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState());

            // 等待超过 PORT timeout 原定触发时间，确认已取消的 PORT timeout 未误触发回退
            // （IP 阶段会启动新的 timeout，此处只验证 PORT 的 timeout 已被取消）
            Thread.sleep(60);
            assertEquals(ReplicationState.HANDSHAKE_REPLCONF_IP, client.getState(),
                    "已取消的 PORT timeout 不应再触发回退");
            assertFalse(callback.disconnectedCalled, "正常 +OK 流程不应触发 onDisconnected");
        } finally {
            fastScheduler.shutdownNow();
        }
    }

    @Test
    @DisplayName("REPLCONF 发送时不存在活跃 channel 不抛异常")
    void testReplconfSendWithoutChannelIsSafe() throws Exception {
        // 未调用 start()，channel 为 null；sendReplConf 应安全不抛异常
        invokeSendReplConf(client);
        assertEquals(ReplicationState.HANDSHAKE_REPLCONF_PORT, client.getState());
    }

    // ==================== onOnline / sendAck 回调链测试 (C2) ====================

    @Test
    @DisplayName("FULL_SYNC 首个 RDB 数据块后推进到 LOADING_RDB，未触发 onOnline")
    void testFullSyncFirstRdbChunkAdvancesToLoadingRdb() throws Exception {
        setClientState(client, ReplicationState.FULL_SYNC);

        // 非 RESP 命令帧（不以 '*' 开头）视为 RDB 字节
        ByteBuf rdbChunk = Unpooled.copiedBuffer(new byte[]{0x52, 0x45, 0x44, 0x49, 0x53});
        invokeHandleResponse(client, rdbChunk);

        assertEquals(ReplicationState.LOADING_RDB, client.getState(),
                "首个 RDB 数据块后 CLIENT 状态应推进到 LOADING_RDB");
        assertTrue(callback.rdbDataCalled, "RDB 数据应通过 onRdbData 回调分发");
        assertFalse(callback.onlineCalled, "RDB 传输中不应触发 onOnline");
        assertFalse(callback.commandPropagationCalled, "RDB 传输中不应触发 onCommandPropagation");
    }

    @Test
    @DisplayName("LOADING_RDB 收到 RESP 命令帧触发 onOnline 并进入 ONLINE（C2 回调链核心）")
    void testLoadingRdbRespCommandFrameTriggersOnOnline() throws Exception {
        setClientState(client, ReplicationState.LOADING_RDB);

        // RESP 命令帧（以 '*' 开头）视为 RDB 传输结束、命令传播开始
        ByteBuf cmdFrame = Unpooled.copiedBuffer("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                StandardCharsets.UTF_8);
        invokeHandleResponse(client, cmdFrame);

        assertEquals(ReplicationState.ONLINE, client.getState(),
                "收到命令帧后 CLIENT 状态应切换到 ONLINE");
        assertTrue(callback.onlineCalled, "应触发 onOnline 回调（C2 回调链核心）");
        assertTrue(callback.commandPropagationCalled, "命令帧应通过 onCommandPropagation 分发");
        assertFalse(callback.rdbDataCalled, "命令帧不应走 onRdbData 路径");
    }

    @Test
    @DisplayName("PARTIAL_SYNC 首条命令触发 onOnline 并进入 ONLINE")
    void testPartialSyncFirstCommandTriggersOnOnline() throws Exception {
        setClientState(client, ReplicationState.PARTIAL_SYNC);

        ByteBuf cmdFrame = Unpooled.copiedBuffer("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                StandardCharsets.UTF_8);
        invokeHandleResponse(client, cmdFrame);

        assertEquals(ReplicationState.ONLINE, client.getState(),
                "部分重同步首条命令后应切换到 ONLINE");
        assertTrue(callback.onlineCalled, "应触发 onOnline 回调");
        assertTrue(callback.commandPropagationCalled);
    }

    @Test
    @DisplayName("ONLINE 状态下后续命令不再重复触发 onOnline")
    void testOnlineStateDoesNotRepeatOnOnline() throws Exception {
        setClientState(client, ReplicationState.ONLINE);

        ByteBuf cmdFrame = Unpooled.copiedBuffer("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                StandardCharsets.UTF_8);
        invokeHandleResponse(client, cmdFrame);

        assertEquals(ReplicationState.ONLINE, client.getState());
        assertFalse(callback.onlineCalled, "ONLINE 状态下不应重复触发 onOnline");
        assertTrue(callback.commandPropagationCalled, "命令应正常分发");
    }

    @Test
    @DisplayName("FULL_SYNC 收到 RESP 命令帧不立即触发 onOnline（必须先经过 LOADING_RDB）")
    void testFullSyncRespFrameDoesNotSkipLoadingRdb() throws Exception {
        setClientState(client, ReplicationState.FULL_SYNC);

        // FULL_SYNC 状态下即使收到 '*' 开头帧也走 RDB 路径（仅 LOADING_RDB 才切换）
        ByteBuf frame = Unpooled.copiedBuffer("*1\r\n$4\r\nPING\r\n", StandardCharsets.UTF_8);
        invokeHandleResponse(client, frame);

        assertEquals(ReplicationState.LOADING_RDB, client.getState(),
                "FULL_SYNC 下应先推进到 LOADING_RDB，不跳过");
        assertTrue(callback.rdbDataCalled);
        assertFalse(callback.onlineCalled);
    }

    @Test
    @DisplayName("端到端回调链：FULLRESYNC -> RDB chunk -> 命令帧 -> onOnline -> sendAck 可发")
    void testEndToEndCallbackChain_FullSyncPath() throws Exception {
        // 1. PSYNC 握手 -> FULLRESYNC -> FULL_SYNC
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+FULLRESYNC replid789 100\r\n",
                StandardCharsets.UTF_8));
        assertEquals(ReplicationState.FULL_SYNC, client.getState());
        assertTrue(callback.fullSyncCalled);
        assertEquals(100L, client.getReplicationOffset());

        // 2. RDB 数据块 -> LOADING_RDB
        ByteBuf rdbChunk = Unpooled.copiedBuffer(new byte[]{0x01, 0x02, 0x03, 0x04});
        invokeHandleResponse(client, rdbChunk);
        assertEquals(ReplicationState.LOADING_RDB, client.getState());
        assertTrue(callback.rdbDataCalled);

        // 3. 命令帧 -> ONLINE + onOnline
        ByteBuf cmdFrame = Unpooled.copiedBuffer("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                StandardCharsets.UTF_8);
        invokeHandleResponse(client, cmdFrame);
        assertEquals(ReplicationState.ONLINE, client.getState(), "回调链终点应为 ONLINE");
        assertTrue(callback.onlineCalled, "onOnline 必须被触发以激活心跳 sendAck");

        // 4. ONLINE 后 sendAck 应能发出（isOnline 为真，但因无 channel 不会真正写出）
        assertTrue(client.isOnline(), "ONLINE 后 client.isOnline() 必须为真");
        client.sendAck(); // 不抛异常即视为通过
    }

    @Test
    @DisplayName("端到端回调链：CONTINUE -> 命令帧 -> onOnline -> sendAck 可发")
    void testEndToEndCallbackChain_PartialSyncPath() throws Exception {
        // 1. PSYNC 握手 -> CONTINUE -> PARTIAL_SYNC
        client.setMasterReplId("old-replid");
        client.setReplicationOffset(500L);
        setClientState(client, ReplicationState.HANDSHAKE_PSYNC);
        invokeHandleResponse(client, Unpooled.copiedBuffer("+CONTINUE newreplid\r\n",
                StandardCharsets.UTF_8));
        assertEquals(ReplicationState.PARTIAL_SYNC, client.getState());
        assertTrue(callback.partialSyncCalled);

        // 2. 命令帧 -> ONLINE + onOnline
        ByteBuf cmdFrame = Unpooled.copiedBuffer("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n",
                StandardCharsets.UTF_8);
        invokeHandleResponse(client, cmdFrame);
        assertEquals(ReplicationState.ONLINE, client.getState());
        assertTrue(callback.onlineCalled, "部分重同步路径也必须触发 onOnline");

        // 3. sendAck 可发
        assertTrue(client.isOnline());
        client.sendAck();
    }

    @Test
    @DisplayName("心跳调度器：ONLINE 后 sendAck 发送 REPLCONF ACK <offset>")
    void testSendAckSendsReplconfAckWithOffset() throws Exception {
        // 进入 ONLINE 并设置偏移量
        setClientState(client, ReplicationState.ONLINE);
        client.setReplicationOffset(12345L);

        // sendAck 在无 channel 时静默返回（不抛异常），但 isOnline 为真
        assertTrue(client.isOnline());
        client.sendAck(); // 无 channel，不会实际写出，验证不抛异常

        // 偏移量可读
        assertEquals(12345L, client.getReplicationOffset());
    }

    @Test
    @DisplayName("非 ONLINE 状态下 sendAck 不发送（isOnline 门控）")
    void testSendAckGatedByIsOnline() throws Exception {
        setClientState(client, ReplicationState.LOADING_RDB);
        assertFalse(client.isOnline(), "LOADING_RDB 阶段 isOnline 必须为假");

        client.sendAck(); // 静默返回，不抛异常
        assertFalse(client.isOnline());
    }

    // ==================== 反射辅助方法 ====================

    @SuppressWarnings("unchecked")
    private static void setClientState(SlaveReplicationClient client, ReplicationState state) {
        try {
            var field = SlaveReplicationClient.class.getDeclaredField("state");
            field.setAccessible(true);
            AtomicReference<ReplicationState> ref =
                    (AtomicReference<ReplicationState>) field.get(client);
            ref.set(state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeHandleResponse(SlaveReplicationClient client, ByteBuf msg) {
        try {
            Method method = SlaveReplicationClient.class.getDeclaredMethod(
                    "handleResponse", ByteBuf.class);
            method.setAccessible(true);
            method.invoke(client, msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void invokeSendReplConf(SlaveReplicationClient client) {
        try {
            Method method = SlaveReplicationClient.class.getDeclaredMethod("sendReplConf");
            method.setAccessible(true);
            method.invoke(client);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void setHandshakeScheduler(SlaveReplicationClient client,
                                              ScheduledExecutorService scheduler) {
        try {
            Field field = SlaveReplicationClient.class.getDeclaredField("handshakeScheduler");
            field.setAccessible(true);
            field.set(client, scheduler);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void setReplconfTimeoutMs(SlaveReplicationClient client, long timeoutMs) {
        try {
            Field field = SlaveReplicationClient.class.getDeclaredField("replconfTimeoutMs");
            field.setAccessible(true);
            field.set(client, timeoutMs);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean waitForState(SlaveReplicationClient client, ReplicationState expected,
                                        long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (client.getState() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return client.getState() == expected;
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