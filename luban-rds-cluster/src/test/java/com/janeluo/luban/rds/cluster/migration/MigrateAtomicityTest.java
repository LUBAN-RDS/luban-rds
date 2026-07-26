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

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MIGRATE 批量键原子性测试（C7 / 缺陷编号 C2 修复覆盖）。
 * <p>
 * 验证 {@link MigrateCommandHandler#migrateMultipleKeys} 的两阶段行为：
 * <ul>
 *   <li>全成功 → 非 COPY 模式删除全部源键</li>
 *   <li>部分失败 → 源端不删除任何键</li>
 *   <li>COPY 模式 → 不删除源</li>
 *   <li>超限 → 拒绝传输、不发送、不删除</li>
 * </ul>
 * </p>
 */
class MigrateAtomicityTest {

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

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        ClusterNode myNode = new ClusterNode(MY_NODE_ID);
        myNode.setIp("127.0.0.1");
        myNode.setPort(7000);
        when(clusterConfig.getMyNode()).thenReturn(myNode);

        ClusterNode targetNode = new ClusterNode(TARGET_NODE_ID);
        targetNode.setIp("127.0.0.1");
        targetNode.setPort(6379);
        when(clusterConfig.getAllNodes()).thenReturn(Collections.singletonList(targetNode));
    }

    /**
     * 默认处理器（生产 64MB 阈值）
     */
    private MigrateCommandHandler newHandler() {
        return new MigrateCommandHandler(migrationManager, memoryStore, busClient, clusterConfig);
    }

    /**
     * 可注入阈值的处理器，用于触发超限分支
     */
    private MigrateCommandHandler newHandlerWithBatchLimit(long limit) {
        return new MigrateCommandHandler(migrationManager, memoryStore, busClient, clusterConfig) {
            @Override
            protected long getMaxBatchSize() {
                return limit;
            }
        };
    }

    private void stubKey(String key, Object value, long ttl) {
        when(memoryStore.exists(0, key)).thenReturn(true);
        when(memoryStore.get(0, key)).thenReturn(value);
        when(memoryStore.pttl(0, key)).thenReturn(ttl);
    }

    @Test
    @DisplayName("批量迁移全部成功 - 非 COPY 模式删除全部源键")
    void testBatchMigrateAllSuccess_DeletesAllSources() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000",
                "KEYS", "k1", "k2", "k3"};
        stubKey("k1", "v1", 0L);
        stubKey("k2", "v2", 0L);
        stubKey("k3", "v3", 0L);

        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "", true, null));

        MigrateCommandHandler handler = newHandler();
        String result = handler.handle(args);

        assertEquals("+OK\r\n", result);
        verify(memoryStore).del(0, "k1");
        verify(memoryStore).del(0, "k2");
        verify(memoryStore).del(0, "k3");
        verify(busClient, times(3)).sendAndWait(
                eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong());
    }

    @Test
    @DisplayName("批量迁移部分失败 - 源端不删除任何键")
    void testBatchMigratePartialFailure_NoDelete() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000",
                "KEYS", "k1", "k2", "k3"};
        stubKey("k1", "v1", 0L);
        stubKey("k2", "v2", 0L);
        stubKey("k3", "v3", 0L);

        // 按发送顺序依次返回：k1 成功、k2 失败、k3 成功
        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "k1", true, null))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "k2", false, "import failed"))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "k3", true, null));

        MigrateCommandHandler handler = newHandler();
        String result = handler.handle(args);

        assertTrue(result.contains("partial migration"),
                "应返回部分迁移错误，实际: " + result);
        // 修复核心断言：任一失败时源端绝不删除
        verify(memoryStore, never()).del(0, "k1");
        verify(memoryStore, never()).del(0, "k2");
        verify(memoryStore, never()).del(0, "k3");
        // 所有 dump 成功的键仍应被尝试传输
        verify(busClient, times(3)).sendAndWait(
                eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong());
    }

    @Test
    @DisplayName("COPY 模式 - 全部成功不删除源键")
    void testBatchMigrateCopyMode_NoDelete() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000",
                "COPY", "KEYS", "k1", "k2", "k3"};
        stubKey("k1", "v1", 0L);
        stubKey("k2", "v2", 0L);
        stubKey("k3", "v3", 0L);

        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "", true, null));

        MigrateCommandHandler handler = newHandler();
        String result = handler.handle(args);

        assertEquals("+OK\r\n", result);
        verify(memoryStore, never()).del(0, "k1");
        verify(memoryStore, never()).del(0, "k2");
        verify(memoryStore, never()).del(0, "k3");
    }

    @Test
    @DisplayName("批量迁移全部失败 - 源端不删除")
    void testBatchMigrateAllFailed_NoDelete() {
        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000",
                "KEYS", "k1", "k2"};
        stubKey("k1", "v1", 0L);
        stubKey("k2", "v2", 0L);

        when(busClient.sendAndWait(eq(TARGET_NODE_ID), any(GossipMessage.class), anyLong()))
                .thenReturn(new MigrateKeyAckMessage(TARGET_NODE_ID, "", false, "import failed"));

        MigrateCommandHandler handler = newHandler();
        String result = handler.handle(args);

        assertTrue(result.startsWith("-ERR all keys failed"));
        verify(memoryStore, never()).del(0, "k1");
        verify(memoryStore, never()).del(0, "k2");
    }

    @Test
    @DisplayName("批量迁移超限 - 拒绝传输且不发送不删除")
    void testBatchMigrateSizeLimitExceeded_Rejected() throws Exception {
        // 构造一个序列化后体积确定的值，并把阈值设为小于单个 dump 大小，
        // 以确保第一个键 dump 完成即触发累计超限。
        byte[] bigValue = new byte[256];
        long dumpedSize = serializedSize(bigValue);
        long threshold = dumpedSize / 2;

        String[] args = {"MIGRATE", "127.0.0.1", "6379", "", "0", "5000",
                "KEYS", "k1", "k2"};
        stubKey("k1", bigValue, 0L);
        stubKey("k2", bigValue, 0L);

        MigrateCommandHandler handler = newHandlerWithBatchLimit(threshold);
        String result = handler.handle(args);

        assertEquals("-ERR command keys batch too large\r\n", result);
        verify(busClient, never()).sendAndWait(
                anyString(), any(GossipMessage.class), anyLong());
        verify(memoryStore, never()).del(0, "k1");
        verify(memoryStore, never()).del(0, "k2");
    }

    private static long serializedSize(Object value) throws Exception {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(value);
            oos.flush();
            return baos.toByteArray().length;
        }
    }
}