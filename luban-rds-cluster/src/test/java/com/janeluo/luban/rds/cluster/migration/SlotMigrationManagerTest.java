package com.janeluo.luban.rds.cluster.migration;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotUtils;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SlotMigrationManager 测试类
 */
class SlotMigrationManagerTest {

    @Mock
    private ClusterConfig clusterConfig;

    @Mock
    private SlotManager slotManager;

    @Mock
    private MemoryStore memoryStore;

    private SlotMigrationManager migrationManager;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        migrationManager = new SlotMigrationManager(clusterConfig, slotManager, memoryStore);
    }

    @Test
    @DisplayName("测试设置导入状态")
    void testSetImporting() {
        int slot = 1000;
        String sourceNodeId = "source-node-id";

        migrationManager.setImporting(slot, sourceNodeId);

        verify(slotManager).setSlotImporting(slot, sourceNodeId);
        assertTrue(migrationManager.isImporting(slot));
        assertNotNull(migrationManager.getImportState(slot));
        assertEquals(sourceNodeId, migrationManager.getImportState(slot).getSourceNodeId());
    }

    @Test
    @DisplayName("测试设置迁移状态")
    void testSetMigrating() {
        int slot = 1000;
        String targetNodeId = "target-node-id";

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(100);

        migrationManager.setMigrating(slot, targetNodeId);

        verify(slotManager).setSlotMigrating(slot, targetNodeId);
        assertTrue(migrationManager.isMigrating(slot));
        assertNotNull(migrationManager.getMigrationState(slot));
        assertEquals(targetNodeId, migrationManager.getMigrationState(slot).getTargetNodeId());
        assertEquals(100, migrationManager.getMigrationState(slot).getKeysCount());
    }

    @Test
    @DisplayName("测试设置迁移状态 - 槽位不属于当前节点")
    void testSetMigratingNotLocalSlot() {
        int slot = 1000;
        String targetNodeId = "target-node-id";

        when(slotManager.isSlotLocal(slot)).thenReturn(false);

        assertThrows(IllegalStateException.class, () -> {
            migrationManager.setMigrating(slot, targetNodeId);
        });
    }

    @Test
    @DisplayName("测试清除迁移状态")
    void testClearMigrationState() {
        int slot = 1000;

        // 先设置导入状态
        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(0);
        migrationManager.setMigrating(slot, "target-node-id");

        // 清除状态
        migrationManager.clearMigrationState(slot);

        verify(slotManager).setSlotMigrating(slot, null);
        verify(slotManager).setSlotImporting(slot, null);
        assertFalse(migrationManager.isMigrating(slot));
        assertFalse(migrationManager.isImporting(slot));
    }

    @Test
    @DisplayName("测试键访问控制 - 导入状态")
    void testCanAccessKeyImporting() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        migrationManager.setImporting(slot, "source-node-id");

        // 不带 ASKING 标志，无法访问
        assertFalse(migrationManager.canAccessKey(key, false));

        // 带 ASKING 标志，可以访问
        assertTrue(migrationManager.canAccessKey(key, true));
    }

    @Test
    @DisplayName("测试键访问控制 - 迁移状态且键存在")
    void testCanAccessKeyMigratingKeyExists() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(1);
        when(memoryStore.exists(0, key)).thenReturn(true);

        migrationManager.setMigrating(slot, "target-node-id");

        // 键存在，可以访问
        assertTrue(migrationManager.canAccessKey(key, false));
    }

    @Test
    @DisplayName("测试键访问控制 - 迁移状态且键不存在")
    void testCanAccessKeyMigratingKeyNotExists() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(1);
        when(memoryStore.exists(0, key)).thenReturn(false);

        migrationManager.setMigrating(slot, "target-node-id");

        // 键不存在，无法访问
        assertFalse(migrationManager.canAccessKey(key, false));
    }

    @Test
    @DisplayName("测试获取重定向信息 - 导入状态")
    void testGetRedirectInfoImporting() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        migrationManager.setImporting(slot, "source-node-id");

        String[] redirect = migrationManager.getRedirectInfo(key);
        assertNotNull(redirect);
        assertEquals("ASK", redirect[0]);
        assertEquals("source-node-id", redirect[1]);
        assertEquals(String.valueOf(slot), redirect[2]);
    }

    @Test
    @DisplayName("测试获取重定向信息 - 迁移状态且键不存在")
    void testGetRedirectInfoMigratingKeyNotExists() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(1);
        when(memoryStore.exists(0, key)).thenReturn(false);

        migrationManager.setMigrating(slot, "target-node-id");

        String[] redirect = migrationManager.getRedirectInfo(key);
        assertNotNull(redirect);
        // 迁移中键不存在应返回 ASK（临时重定向），对齐 Redis 语义
        assertEquals("ASK", redirect[0]);
        assertEquals("target-node-id", redirect[1]);
        assertEquals(String.valueOf(slot), redirect[2]);
    }

    @Test
    @DisplayName("测试导出键")
    void testExportKey() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(1);
        when(memoryStore.exists(0, key)).thenReturn(true);
        when(memoryStore.get(0, key)).thenReturn("test-value");
        when(memoryStore.pttl(0, key)).thenReturn(1000L);
        when(memoryStore.type(0, key)).thenReturn("string");

        migrationManager.setMigrating(slot, "target-node-id");

        ExportResult result = migrationManager.exportKey(key);

        assertTrue(result.isSuccess());
        assertEquals(key, result.getKey());
        assertEquals("string", result.getType());
        assertEquals(1000L, result.getTtl());
    }

    @Test
    @DisplayName("测试导出键 - 键不存在")
    void testExportKeyNotFound() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(0);

        migrationManager.setMigrating(slot, "target-node-id");

        ExportResult result = migrationManager.exportKey(key);

        assertFalse(result.isSuccess());
        assertEquals("Key not found", result.getError());
    }

    @Test
    @DisplayName("测试导入键")
    void testImportKey() throws IOException {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);
        
        // 创建正确序列化的数据
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject("test-value");
        }
        byte[] value = baos.toByteArray();

        migrationManager.setImporting(slot, "source-node-id");

        boolean success = migrationManager.importKey(key, value, 1000L);

        assertTrue(success);
        verify(memoryStore).setWithExpireMs(eq(0), eq(key), any(), eq(1000L));
        assertEquals(1, migrationManager.getImportState(slot).getImportedCount());
    }

    @Test
    @DisplayName("测试导入键 - 槽位未处于导入状态")
    void testImportKeyNotImporting() {
        String key = "test-key";
        byte[] value = "test-value".getBytes();

        boolean success = migrationManager.importKey(key, value, 1000L);

        assertFalse(success);
        verify(memoryStore, never()).setWithExpireMs(anyInt(), anyString(), any(), anyLong());
    }

    @Test
    @DisplayName("测试获取槽位中的键")
    void testGetKeysInSlot() {
        int slot = 1000;
        List<String> keys = Arrays.asList("key1", "key2", "key3");

        when(memoryStore.getKeysInSlot(0, slot, 10)).thenReturn(keys);

        List<String> result = migrationManager.getKeysInSlot(slot, 10);

        assertEquals(keys, result);
    }

    @Test
    @DisplayName("测试删除已迁移的键")
    void testDeleteMigratedKey() {
        String key = "test-key";
        int slot = SlotUtils.keyHashSlot(key);

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(1);
        when(memoryStore.del(0, key)).thenReturn(true);

        migrationManager.setMigrating(slot, "target-node-id");

        boolean deleted = migrationManager.deleteMigratedKey(key);

        assertTrue(deleted);
        verify(memoryStore).del(0, key);
    }

    @Test
    @DisplayName("测试完成迁移")
    void testFinishMigration() {
        int slot = 1000;
        String newOwnerId = "new-owner-id";

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(0);

        migrationManager.setMigrating(slot, "target-node-id");
        migrationManager.finishMigration(slot, newOwnerId);

        assertFalse(migrationManager.isMigrating(slot));
        verify(slotManager).setSlotOwner(slot, newOwnerId);
    }

    @Test
    @DisplayName("测试取消迁移")
    void testCancelMigration() {
        int slot = 1000;

        when(slotManager.isSlotLocal(slot)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, slot)).thenReturn(0);

        migrationManager.setMigrating(slot, "target-node-id");
        migrationManager.cancelMigration(slot);

        assertFalse(migrationManager.isMigrating(slot));
        assertTrue(migrationManager.getMigrationState(slot) == null 
                || migrationManager.getMigrationState(slot).isFailed());
    }

    @Test
    @DisplayName("测试统计信息")
    void testStatistics() {
        when(slotManager.isSlotLocal(1000)).thenReturn(true);
        when(slotManager.isSlotLocal(2000)).thenReturn(true);
        when(memoryStore.countKeysInSlot(0, 1000)).thenReturn(10);
        when(memoryStore.countKeysInSlot(0, 2000)).thenReturn(20);

        migrationManager.setMigrating(1000, "target-1");
        migrationManager.setImporting(3000, "source-1");

        assertEquals(1, migrationManager.getMigratingSlotCount());
        assertEquals(1, migrationManager.getImportingSlotCount());

        String summary = migrationManager.getMigrationSummary();
        assertTrue(summary.contains("Migrating slots"));
        assertTrue(summary.contains("Importing slots"));
    }

    @Test
    @DisplayName("测试无效槽位号")
    void testInvalidSlot() {
        assertThrows(IllegalArgumentException.class, () -> {
            migrationManager.setImporting(-1, "source-node-id");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            migrationManager.setMigrating(20000, "target-node-id");
        });
    }
}
