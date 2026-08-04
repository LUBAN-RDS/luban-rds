package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.migration.SlotMigrationManager;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.core.store.ValueSerialization;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * P0-新3 目标端传播测试：MIGRATE_KEY 导入成功后以 RESTORE 帧进入复制/AOF 传播流。
 * <p>
 * 回归保护：旧实现目标端经总线直接写存储、不传播，目标 master 的 slave 缺失导入键，
 * failover 后丢键（副本数据分叉）。
 * </p>
 */
class GossipProtocolMigrationPropagationTest {

    private static final String MY_NODE_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final String SOURCE_NODE_ID = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";

    private ClusterConfig config;
    private SlotManager slotManager;
    private MemoryStore memoryStore;
    private SlotMigrationManager migrationManager;
    private GossipProtocol gossipProtocol;
    private List<byte[]> propagatedFrames;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        ClusterNode me = new ClusterNode(MY_NODE_ID);
        me.addState(ClusterNodeState.MYSELF);
        me.addState(ClusterNodeState.MASTER);
        config.addNode(me);
        config.setMyNodeId(MY_NODE_ID);

        slotManager = new DefaultSlotManager();
        memoryStore = new DefaultMemoryStore();
        migrationManager = new SlotMigrationManager(config, slotManager, memoryStore);
        gossipProtocol = new GossipProtocol(config, mock(ClusterBusClient.class), 15000L);
        gossipProtocol.setSlotMigrationManager(migrationManager);

        propagatedFrames = new ArrayList<>();
        gossipProtocol.setWritePropagator(propagatedFrames::add);
    }

    @AfterEach
    void tearDown() {
        gossipProtocol.stop();
    }

    @Test
    @DisplayName("P0-新3：导入成功后传播 RESTORE 帧（含键名/ttl/载荷）")
    void testImportSuccessPropagatesRestoreFrame() throws Exception {
        String key = "migrated-key";
        int slot = SlotUtils.keyHashSlot(key);
        // 槽位处于 IMPORTING 状态（MIGRATE 导入前置条件）
        slotManager.setSlotImporting(slot, SOURCE_NODE_ID);
        // 目标节点键不存在 → 可导入
        assertFalse(memoryStore.exists(0, key));

        byte[] payload = ValueSerialization.serialize("value-123");
        MigrateKeyMessage msg = new MigrateKeyMessage(SOURCE_NODE_ID, key, payload, 5000L, false, 0);

        MigrateKeyAckMessage ack = gossipProtocol.handleMigrateKey(msg);

        assertTrue(ack.isSuccess(), "导入应成功");
        assertTrue(memoryStore.exists(0, key), "键应已导入存储");
        assertEquals("value-123", memoryStore.get(0, key), "导入的值应正确");

        // 应传播一条 RESTORE 帧
        assertEquals(1, propagatedFrames.size(), "导入成功应传播 RESTORE 帧");
        String frame = new String(propagatedFrames.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(frame.contains("RESTORE") && frame.contains(key),
                "传播帧应为 RESTORE 命令，实际: " + frame);
        assertTrue(frame.contains("5000"), "传播帧应携带 ttl(ms)，实际: " + frame);
        assertTrue(frame.contains(new String(payload, StandardCharsets.ISO_8859_1)),
                "传播帧应携带序列化载荷，实际: " + frame);
    }

    @Test
    @DisplayName("P0-新3：导入失败（槽位未 IMPORTING）时不传播 RESTORE")
    void testImportRejectedDoesNotPropagateRestore() throws Exception {
        String key = "not-importing-key";
        // 未设置 IMPORTING 状态 → 导入被拒
        MigrateKeyMessage msg = new MigrateKeyMessage(SOURCE_NODE_ID, key,
                ValueSerialization.serialize("v"), 0L, false, 0);

        MigrateKeyAckMessage ack = gossipProtocol.handleMigrateKey(msg);

        assertFalse(ack.isSuccess(), "槽位未 IMPORTING 时导入应失败");
        assertEquals(0, propagatedFrames.size(), "导入失败不应传播 RESTORE");
    }

    @Test
    @DisplayName("P0-新3：BUSYKEY（键已存在且未带 REPLACE）不传播 RESTORE")
    void testBusykeyDoesNotPropagateRestore() throws Exception {
        String key = "existing-key";
        int slot = SlotUtils.keyHashSlot(key);
        slotManager.setSlotImporting(slot, SOURCE_NODE_ID);
        memoryStore.set(0, key, "old-value");

        MigrateKeyMessage msg = new MigrateKeyMessage(SOURCE_NODE_ID, key,
                ValueSerialization.serialize("new-value"), 0L, false, 0);

        MigrateKeyAckMessage ack = gossipProtocol.handleMigrateKey(msg);

        assertFalse(ack.isSuccess(), "键已存在且未带 REPLACE 应返回 BUSYKEY");
        assertEquals(0, propagatedFrames.size(), "BUSYKEY 失败不应传播 RESTORE");
    }

    @Test
    @DisplayName("P0-新3：REPLACE 导入成功传播 RESTORE（带 REPLACE 选项）")
    void testReplaceImportPropagatesRestoreWithReplace() throws Exception {
        String key = "replace-key";
        int slot = SlotUtils.keyHashSlot(key);
        slotManager.setSlotImporting(slot, SOURCE_NODE_ID);
        memoryStore.set(0, key, "old-value");

        MigrateKeyMessage msg = new MigrateKeyMessage(SOURCE_NODE_ID, key,
                ValueSerialization.serialize("new-value"), 0L, true, 0);

        MigrateKeyAckMessage ack = gossipProtocol.handleMigrateKey(msg);

        assertTrue(ack.isSuccess(), "带 REPLACE 应覆盖导入成功");
        assertEquals(1, propagatedFrames.size());
        String frame = new String(propagatedFrames.get(0), StandardCharsets.ISO_8859_1);
        assertTrue(frame.contains("REPLACE"), "传播帧应携带 REPLACE 选项，实际: " + frame);
    }
}
