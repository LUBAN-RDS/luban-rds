package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.common.config.RdsConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 主从复制集成测试
 * 
 * 注意：此测试需要完整的 Netty 环境，建议在集成测试环境中运行
 */
@Disabled("需要完整的服务器环境，在集成测试中运行")
class ReplicationIntegrationTest {
    
    private RdsConfig masterConfig;
    private RdsConfig slaveConfig;
    
    @BeforeEach
    void setUp() {
        // 主节点配置
        masterConfig = new RdsConfig();
        masterConfig.setPort(9736);
        masterConfig.setReplBacklogSize(1024L * 1024L);
        
        // 从节点配置
        slaveConfig = new RdsConfig();
        slaveConfig.setPort(9737);
        slaveConfig.setReplicaof("127.0.0.1:9736");
    }
    
    @Test
    @DisplayName("测试复制积压缓冲区")
    void testReplicationBacklog() {
        ReplicationBacklog backlog = new ReplicationBacklog(1024);
        
        // 写入数据
        byte[] data = "SET key value".getBytes();
        long offset = backlog.append(data);
        
        assertEquals(data.length, offset);
        assertEquals(data.length, backlog.getMasterReplOffset());
        
        // 验证复制 ID
        assertNotNull(backlog.getReplId());
        assertEquals(40, backlog.getReplId().length());
        
        // 验证部分重同步
        assertTrue(backlog.canPartialSync(backlog.getReplId(), 0));
    }
    
    @Test
    @DisplayName("测试主节点管理器")
    void testMasterReplicationManager() {
        MasterReplicationManager manager = MasterReplicationManager.getInstance();
        
        // 验证初始状态
        assertEquals(0, manager.getConnectedSlaves());
        assertNotNull(manager.getBacklog());
        
        // 验证复制信息
        String info = manager.getReplicationInfo();
        assertTrue(info.contains("role:master"));
    }
    
    @Test
    @DisplayName("测试从节点复制状态")
    void testSlaveReplicationState() {
        SlaveReplicationService service = new SlaveReplicationService(slaveConfig);
        
        // 初始状态
        assertEquals(ReplicationState.DISCONNECTED, service.getState());
        assertFalse(service.isOnline());
        
        // 验证只读模式
        assertTrue(service.isReadOnly());
        
        // 验证复制信息
        String info = service.getReplicationInfo();
        assertTrue(info.contains("role:slave"));
    }
    
    @Test
    @DisplayName("测试复制状态枚举")
    void testReplicationState() {
        // 测试握手状态
        assertTrue(ReplicationState.HANDSHAKE_PING.isHandshake());
        assertTrue(ReplicationState.HANDSHAKE_AUTH.isHandshake());
        assertFalse(ReplicationState.FULL_SYNC.isHandshake());
        
        // 测试同步状态
        assertTrue(ReplicationState.FULL_SYNC.isSyncing());
        assertTrue(ReplicationState.PARTIAL_SYNC.isSyncing());
        assertTrue(ReplicationState.LOADING_RDB.isSyncing());
        assertFalse(ReplicationState.ONLINE.isSyncing());
        
        // 测试在线状态
        assertTrue(ReplicationState.ONLINE.isOnline());
        assertFalse(ReplicationState.FULL_SYNC.isOnline());
        
        // 测试断开状态
        assertTrue(ReplicationState.DISCONNECTED.isDisconnected());
        assertTrue(ReplicationState.ERROR.isDisconnected());
        assertFalse(ReplicationState.ONLINE.isDisconnected());
    }
    
    @Test
    @DisplayName("测试从节点信息")
    void testSlaveInfo() {
        // 创建模拟通道（实际测试中需要真实通道）
        // SlaveInfo slave = new SlaveInfo(channel);
        
        // 验证初始状态
        // assertNotNull(slave.getSlaveId());
        // assertEquals(ReplicationState.DISCONNECTED, slave.getState());
        // assertFalse(slave.isOnline());
    }
}
