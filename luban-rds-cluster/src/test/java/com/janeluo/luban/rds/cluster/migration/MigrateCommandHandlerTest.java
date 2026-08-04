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

import java.util.List;

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
        // P1-17：对齐 Redis 7，单键不存在回复 +NOKEY（而非 bulk nil $-1）
        assertEquals("+NOKEY\r\n", result);
    }

    @Test
    @DisplayName("N-38：序列化载荷超过总线单帧上限时拒绝迁移（不发送、不删源键）")
    void testMigrateSingleKeyOversizeRejected() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "big-key", "0", "5000"};

        when(memoryStore.exists(0, "big-key")).thenReturn(true);
        // 16MB+1 的载荷：超过总线解码器单帧上限（16MB）
        when(memoryStore.get(0, "big-key")).thenReturn(new byte[16 * 1024 * 1024 + 1]);
        when(memoryStore.pttl(0, "big-key")).thenReturn(1000L);

        String result = handler.handle(args);
        assertEquals("-ERR key value too large for cluster migration\r\n", result);
        // 未发送到目标节点、不删除源键（避免连接被对端解码器拔掉 + 数据丢失）
        verify(busClient, never()).sendAndWait(anyString(), any(GossipMessage.class), anyLong());
        verify(memoryStore, never()).del(0, "big-key");
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
    @DisplayName("P0-新3：迁移成功后源键删除进入复制/AOF 流（DEL 帧传播）")
    void testMigrateSingleKeySuccessPropagatesDel() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        // 注入传播回调并捕获传播帧
        List<byte[]> propagatedFrames = new java.util.ArrayList<>();
        handler.setWritePropagator(propagatedFrames::add);

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);
        when(memoryStore.del(0, "test-key")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);

        // 应传播一条 DEL 帧，且帧内容为 RESP 编码的 "DEL test-key"
        assertEquals(1, propagatedFrames.size(), "成功迁移（非 COPY）应传播 DEL 帧");
        String frame = new String(propagatedFrames.get(0), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(frame.contains("DEL") && frame.contains("test-key"),
                "传播帧应为 DEL 命令，实际: " + frame);
    }

    @Test
    @DisplayName("P0-新3：COPY 模式不删除源键也不传播 DEL")
    void testMigrateSingleKeyCopyDoesNotPropagateDel() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000", "COPY"};

        List<byte[]> propagatedFrames = new java.util.ArrayList<>();
        handler.setWritePropagator(propagatedFrames::add);

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(ack);

        handler.handle(args);
        assertEquals(0, propagatedFrames.size(), "COPY 模式不应传播 DEL");
        verify(memoryStore, never()).del(0, "test-key");
    }

    @Test
    @DisplayName("P0-新3：迁移失败时不传播 DEL（源键未删除）")
    void testMigrateSingleKeyFailureDoesNotPropagateDel() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        List<byte[]> propagatedFrames = new java.util.ArrayList<>();
        handler.setWritePropagator(propagatedFrames::add);

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage failAck = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", false, "import failed");
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong())).thenReturn(failAck);

        handler.handle(args);
        assertEquals(0, propagatedFrames.size(), "迁移失败不应传播 DEL");
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

    // ==================== P1-17 语义补全测试 ====================

    @Test
    @DisplayName("P1-17: 单键迁移 - 目标键已存在未带 REPLACE 返回 -BUSYKEY")
    void testMigrateBusyKey() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        // 目标节点返回 BUSYKEY 错误
        MigrateKeyAckMessage busykeyAck =
                new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", false, "BUSYKEY");
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(busykeyAck);

        String result = handler.handle(args);
        assertEquals("-BUSYKEY Target key name already exists.\r\n", result);
        // BUSYKEY 时不应删除源键
        verify(memoryStore, never()).del(0, "test-key");
    }

    @Test
    @DisplayName("P1-17: AUTH 选项被正确解析（单参 password，不报 syntax error）")
    void testMigrateWithAuth() {
        // Redis MIGRATE AUTH <password>（单参）。AUTH2 用于 username+password 两参。
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000",
                "AUTH", "secret"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(ack);
        when(memoryStore.del(0, "test-key")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);
    }

    @Test
    @DisplayName("P1-17: AUTH2 选项被正确解析（不报 syntax error）")
    void testMigrateWithAuth2() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000",
                "AUTH2", "myuser", "mypass", "REPLACE"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(ack);
        when(memoryStore.del(0, "test-key")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);
    }

    @Test
    @DisplayName("P1-17: key 与 KEYS 并存报 syntax error")
    void testMigrateKeyAndKeysConflict() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000",
                "KEYS", "key1", "key2"};

        String result = handler.handle(args);
        assertEquals("-ERR syntax error\r\n", result);
    }

    @Test
    @DisplayName("P1-17: AUTH 参数不足报 syntax error")
    void testMigrateAuthMissingArgs() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "5000", "AUTH"};

        String result = handler.handle(args);
        assertEquals("-ERR syntax error\r\n", result);
    }

    @Test
    @DisplayName("P1-17: timeout=0 不报错且能成功（用内部默认超时）")
    void testMigrateTimeoutZero() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "test-key", "0", "0"};

        when(memoryStore.exists(0, "test-key")).thenReturn(true);
        when(memoryStore.get(0, "test-key")).thenReturn("test-value");
        when(memoryStore.pttl(0, "test-key")).thenReturn(1000L);
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(TARGET_NODE_ID, "test-key", true, null);
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(ack);
        when(memoryStore.del(0, "test-key")).thenReturn(true);

        String result = handler.handle(args);
        assertEquals("+OK\r\n", result);
    }
}
