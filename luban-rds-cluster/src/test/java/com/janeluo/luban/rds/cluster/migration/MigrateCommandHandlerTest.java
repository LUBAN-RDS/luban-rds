package com.janeluo.luban.rds.cluster.migration;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyAckMessage;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * MigrateCommandHandler 测试类
 */
class MigrateCommandHandlerTest {

    private static final String MY_NODE_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String TARGET_NODE_ID = "b1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    @Mock
    private SlotMigrationManager migrationManager;

    @Mock
    private MemoryStore memoryStore;

    @Mock
    private ClusterBusClient busClient;

    @Mock
    private ClusterConfig clusterConfig;

    private MigrateCommandHandler handler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        handler = new MigrateCommandHandler(migrationManager, memoryStore, busClient, clusterConfig);

        // 模拟本节点
        ClusterNode myNode = new ClusterNode(MY_NODE_ID);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        when(clusterConfig.getMyNode()).thenReturn(myNode);

        // 模拟目标节点 127.0.0.1:6379
        ClusterNode targetNode = new ClusterNode(TARGET_NODE_ID);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(6379);
        when(clusterConfig.getAllNodes()).thenReturn(java.util.Collections.singletonList(targetNode));
    }

    @Test
    @DisplayName("测试参数不足")
    void testInsufficientArguments() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379"};
        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR"));
    }

    @Test
    @DisplayName("测试单键迁移 - 键不存在")
    void testMigrateSingleKeyNotFound() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        when(memoryStore.exists(0, "test-key")).thenReturn(false);

        String result = handler.handle(args);
        assertEquals("$-1\r\n", result); // NOKEY
    }

    @Test
    @DisplayName("测试单键迁移 - 成功")
    void testMigrateSingleKeySuccess() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        // sendAndWait 返回成功 ACK
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);
        when(memoryStore.del(0, "test-key")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);
        // 验证键确实被发送到目标节点
        verify(busClient).sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong());
        // 非 COPY 模式应删除源键
        verify(memoryStore).del(0, "test-key");
    }

    @Test
    @DisplayName("测试单键迁移 - 发送失败时不删除源键（防丢数据）")
    void testMigrateSingleKeySendFailedNoDelete() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        // sendAndWait 返回失败 ACK
        MigrateKeyAckMessage failAck = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", false, "import failed");
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(failAck);

        String result = handler.handle(args);
        assertEquals("-IOERR error transferring key\r\n", result);
        // 发送失败时不应删除源键
        verify(memoryStore, never()).del(0, "test-key");
    }

    @Test
    @DisplayName("测试单键迁移 - COPY 选项")
    void testMigrateSingleKeyWithCopy() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000", "COPY"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);

        // COPY 模式下不应删除源键
        verify(memoryStore, never()).del(0, "test-key");
    }

    @Test
    @DisplayName("测试批量迁移 - 全部成功")
    void testMigrateMultipleKeysSuccess() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000", "KEYS", "key1", "key2", "key3"};

        when(memoryStore.exists(0, "key1")).thenReturn(true);
        when(memoryStore.exists(0, "key2")).thenReturn(true);
        when(memoryStore.exists(0, "key3")).thenReturn(true);

        when(memoryStore.get(0, "key1")).thenReturn("value1");
        when(memoryStore.get(0, "key2")).thenReturn("value2");
        when(memoryStore.get(0, "key3")).thenReturn("value3");
        when(memoryStore.pttl(0, "key1")).thenReturn(0L);
        when(memoryStore.pttl(0, "key2")).thenReturn(0L);
        when(memoryStore.pttl(0, "key3")).thenReturn(0L);

        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);

        when(memoryStore.del(0, "key1")).thenReturn(true);
        when(memoryStore.del(0, "key2")).thenReturn(true);
        when(memoryStore.del(0, "key3")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);
    }

    @Test
    @DisplayName("测试批量迁移 - 部分失败")
    void testMigrateMultipleKeysPartialFailure() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000", "KEYS", "key1", "key2"};

        when(memoryStore.exists(0, "key1")).thenReturn(true);
        when(memoryStore.exists(0, "key2")).thenReturn(false); // key2 不存在

        when(memoryStore.get(0, "key1")).thenReturn("value1");
        when(memoryStore.pttl(0, "key1")).thenReturn(0L);

        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);

        when(memoryStore.del(0, "key1")).thenReturn(true);

        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR partial migration"));
    }

    @Test
    @DisplayName("测试批量迁移 - 全部失败")
    void testMigrateMultipleKeysAllFailed() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000", "KEYS", "key1", "key2"};

        when(memoryStore.exists(0, "key1")).thenReturn(false);
        when(memoryStore.exists(0, "key2")).thenReturn(false);

        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR all keys failed"));
    }

    @Test
    @DisplayName("测试无效端口")
    void testInvalidPort() {
        String[] args = {"MIGRATE", "127.0.0.1", "invalid", "test-key", "0", "5000"};

        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR"));
    }

    @Test
    @DisplayName("测试无效数据库索引")
    void testInvalidDatabase() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "invalid", "5000"};

        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR"));
    }

    @Test
    @DisplayName("测试无效超时")
    void testInvalidTimeout() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "invalid"};

        String result = handler.handle(args);
        assertTrue(result.startsWith("-ERR"));
    }

    @Test
    @DisplayName("测试语法错误")
    void testSyntaxError() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000", "INVALID_OPTION"};

        String result = handler.handle(args);
        assertEquals("-ERR syntax error\r\n", result);
    }

    @Test
    @DisplayName("测试空键列表")
    void testEmptyKeyList() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000", "KEYS"};

        String result = handler.handle(args);
        assertEquals("-ERR no keys to migrate\r\n", result);
    }

    @Test
    @DisplayName("测试 dumpKey 方法")
    void testDumpKey() {
        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");

        byte[] result = handler.dumpKey("test-key");

        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    @DisplayName("测试 dumpKey 方法 - 键不存在")
    void testDumpKeyNotFound() {
        when(memoryStore.exists(0, "test-key")).thenReturn(false);

        byte[] result = handler.dumpKey("test-key");

        assertNull(result);
    }

    @Test
    @DisplayName("测试端口范围检查")
    void testPortOutOfRange() {
        String[] args = {"MIGRATE", "127.0.0.1", "70000", "test-key", "0", "5000"};

        String result = handler.handle(args);
        assertTrue(result.contains("port out of range"));
    }

    @Test
    @DisplayName("测试负数超时")
    void testNegativeTimeout() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "-1"};

        String result = handler.handle(args);
        assertTrue(result.contains("timeout out of range"));
    }
}
