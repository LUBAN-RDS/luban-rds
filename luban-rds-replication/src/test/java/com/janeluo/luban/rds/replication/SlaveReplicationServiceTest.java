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

class SlaveReplicationServiceTest {

    private SlaveReplicationService service;
    private ReplicationBacklog testBacklog;

    @BeforeEach
    void setUp() {
        RdsConfig config = new RdsConfig();
        config.setPort(9737);
        config.setReplicaof("127.0.0.1:9736");
        config.setSlaveReadOnly(true);
        
        service = new SlaveReplicationService(config);
        testBacklog = new ReplicationBacklog(1024);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.stop();
        }
    }

    @Test
    @DisplayName("测试初始化")
    void testInit() {
        assertEquals(ReplicationState.DISCONNECTED, service.getState());
        assertFalse(service.isOnline());
        assertTrue(service.isReadOnly());
        assertNull(service.getMasterReplId());
        assertEquals(0, service.getReplOffset());
    }

    @Test
    @DisplayName("测试获取复制客户端")
    void testGetClient() {
        assertNotNull(service.getClient());
    }

    @Test
    @DisplayName("测试回调：连接失败")
    void testOnConnectionFailed() {
        service.onConnectionFailed(new RuntimeException("test error"));
        assertEquals(ReplicationState.ERROR, service.getState());
    }

    @Test
    @DisplayName("测试回调：握手失败")
    void testOnHandshakeFailed() {
        service.onHandshakeFailed("auth failed");
        assertEquals(ReplicationState.ERROR, service.getState());
    }

    @Test
    @DisplayName("测试回调：断开连接")
    void testOnDisconnected() {
        setServiceState(service, ReplicationState.ONLINE);
        service.onDisconnected();
        assertEquals(ReplicationState.DISCONNECTED, service.getState());
    }

    @Test
    @DisplayName("测试回调：全量同步")
    void testOnFullSync() {
        service.onFullSync("repl-id-123", 1000L);
        
        assertEquals(ReplicationState.FULL_SYNC, service.getState());
        assertEquals("repl-id-123", service.getMasterReplId());
    }

    @Test
    @DisplayName("测试回调：部分重同步")
    void testOnPartialSync() {
        service.onPartialSync("repl-id-456", 2000L);
        
        assertEquals(ReplicationState.PARTIAL_SYNC, service.getState());
        assertEquals("repl-id-456", service.getMasterReplId());
    }

    @Test
    @DisplayName("测试回调：RDB数据")
    void testOnRdbData() {
        setServiceState(service, ReplicationState.FULL_SYNC);
        long initialOffset = service.getReplOffset();
        
        ByteBuf data = Unpooled.copiedBuffer("test rdb data", StandardCharsets.UTF_8);
        service.onRdbData(data);
        
        assertEquals(ReplicationState.LOADING_RDB, service.getState());
        assertEquals(initialOffset + 13, service.getReplOffset());
    }

    @Test
    @DisplayName("测试回调：RDB数据 - 非全量同步状态")
    void testOnRdbDataNotFullSync() {
        setServiceState(service, ReplicationState.ONLINE);
        
        ByteBuf data = Unpooled.copiedBuffer("test", StandardCharsets.UTF_8);
        service.onRdbData(data);
        
        assertEquals(ReplicationState.ONLINE, service.getState());
    }

    @Test
    @DisplayName("测试回调：在线")
    void testOnOnline() {
        setServiceState(service, ReplicationState.LOADING_RDB);
        service.onOnline();
        assertEquals(ReplicationState.ONLINE, service.getState());
    }

    @Test
    @DisplayName("测试回调：命令传播")
    void testOnCommandPropagation() {
        setServiceState(service, ReplicationState.PARTIAL_SYNC);
        long initialOffset = service.getReplOffset();
        
        ByteBuf data = Unpooled.copiedBuffer("SET key value", StandardCharsets.UTF_8);
        service.onCommandPropagation(data);
        
        assertEquals(ReplicationState.ONLINE, service.getState());
        assertEquals(initialOffset + 13, service.getReplOffset());
    }

    @Test
    @DisplayName("测试回调：命令传播 - 已在线状态")
    void testOnCommandPropagationAlreadyOnline() {
        setServiceState(service, ReplicationState.ONLINE);
        long initialOffset = service.getReplOffset();
        
        ByteBuf data = Unpooled.copiedBuffer("SET key value", StandardCharsets.UTF_8);
        service.onCommandPropagation(data);
        
        assertEquals(ReplicationState.ONLINE, service.getState());
        assertEquals(initialOffset + 13, service.getReplOffset());
    }

    @Test
    @DisplayName("测试获取复制信息")
    void testGetReplicationInfo() {
        String info = service.getReplicationInfo();
        
        assertTrue(info.contains("# Replication"));
        assertTrue(info.contains("role:slave"));
        assertTrue(info.contains("master_host:"));
        assertTrue(info.contains("master_port:"));
        assertTrue(info.contains("master_link_status:"));
        assertTrue(info.contains("master_sync_in_progress:"));
        assertTrue(info.contains("slave_repl_offset:"));
        assertTrue(info.contains("slave_priority:100"));
        assertTrue(info.contains("slave_read_only:"));
    }

    @Test
    @DisplayName("测试获取主节点地址")
    void testGetMasterAddress() {
        assertNull(service.getMasterAddress());
    }

    @Test
    @DisplayName("测试只读模式")
    void testReadOnly() {
        assertTrue(service.isReadOnly());
    }

    @Test
    @DisplayName("测试启动 - 无主节点配置")
    void testStartNoMaster() {
        RdsConfig config = new RdsConfig();
        SlaveReplicationService noMasterService = new SlaveReplicationService(config);
        
        noMasterService.start();
        
        assertEquals(ReplicationState.DISCONNECTED, noMasterService.getState());
        
        noMasterService.stop();
    }

    @Test
    @DisplayName("测试启动 - 已启动")
    void testStartAlreadyStarted() {
        RdsConfig config = new RdsConfig();
        config.setReplicaof("127.0.0.1:9736");
        
        SlaveReplicationService testService = new SlaveReplicationService(config);
        setServiceState(testService, ReplicationState.CONNECTING);
        
        testService.start();
        
        testService.stop();
    }

    @Test
    @DisplayName("测试停止")
    void testStop() {
        service.stop();
        assertEquals(ReplicationState.DISCONNECTED, service.getState());
    }

    @Test
    @DisplayName("测试获取复制ID")
    void testGetReplId() {
        assertNull(service.getReplId());
        
        service.onFullSync("test-repl-id", 0);
        assertEquals("test-repl-id", service.getReplId());
    }

    @Test
    @DisplayName("测试获取复制偏移量")
    void testGetReplOffset() {
        assertEquals(0, service.getReplOffset());
        
        ByteBuf data = Unpooled.copiedBuffer("test", StandardCharsets.UTF_8);
        service.onRdbData(data);
        
        assertEquals(4, service.getReplOffset());
    }

    private void setServiceState(SlaveReplicationService svc, ReplicationState state) {
        try {
            var field = SlaveReplicationService.class.getDeclaredField("state");
            field.setAccessible(true);
            var atomicRef = (java.util.concurrent.atomic.AtomicReference<ReplicationState>) field.get(svc);
            atomicRef.set(state);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}