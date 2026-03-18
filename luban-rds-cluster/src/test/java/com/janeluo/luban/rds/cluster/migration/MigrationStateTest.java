package com.janeluo.luban.rds.cluster.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MigrationState 测试类
 */
class MigrationStateTest {

    private MigrationState migrationState;

    @BeforeEach
    void setUp() {
        migrationState = new MigrationState(1000, "target-node-id");
    }

    @Test
    @DisplayName("测试构造方法")
    void testConstructor() {
        assertEquals(1000, migrationState.getSlot());
        assertEquals("target-node-id", migrationState.getTargetNodeId());
        assertEquals(0, migrationState.getKeysCount());
        assertEquals(0, migrationState.getMigratedCount());
        assertEquals("running", migrationState.getStatus());
        assertNull(migrationState.getErrorMessage());
    }

    @Test
    @DisplayName("测试设置键数量")
    void testSetKeysCount() {
        migrationState.setKeysCount(100);
        assertEquals(100, migrationState.getKeysCount());
    }

    @Test
    @DisplayName("测试增加已迁移计数")
    void testIncrementMigratedCount() {
        migrationState.setKeysCount(100);
        migrationState.incrementMigratedCount();
        assertEquals(1, migrationState.getMigratedCount());
        
        migrationState.incrementMigratedCount(10);
        assertEquals(11, migrationState.getMigratedCount());
    }

    @Test
    @DisplayName("测试进度计算")
    void testGetProgress() {
        migrationState.setKeysCount(100);
        assertEquals(0, migrationState.getProgress());
        
        migrationState.setMigratedCount(50);
        assertEquals(50, migrationState.getProgress());
        
        migrationState.setMigratedCount(100);
        assertEquals(100, migrationState.getProgress());
    }

    @Test
    @DisplayName("测试进度计算 - 零键数量")
    void testGetProgressWithZeroKeys() {
        assertEquals(0, migrationState.getProgress());
    }

    @Test
    @DisplayName("测试状态检查")
    void testStatusCheck() {
        assertTrue(migrationState.isRunning());
        assertFalse(migrationState.isCompleted());
        assertFalse(migrationState.isFailed());
        
        migrationState.markCompleted();
        assertTrue(migrationState.isCompleted());
        assertFalse(migrationState.isRunning());
        
        MigrationState failedState = new MigrationState(2000, "target-node-id");
        failedState.markFailed("Test error");
        assertTrue(failedState.isFailed());
        assertEquals("Test error", failedState.getErrorMessage());
    }

    @Test
    @DisplayName("测试已运行时间")
    void testGetElapsedTime() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        Thread.sleep(100);
        long elapsed = migrationState.getElapsedTime();
        assertTrue(elapsed >= 100);
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        migrationState.setKeysCount(100);
        migrationState.setMigratedCount(50);
        String str = migrationState.toString();
        
        assertTrue(str.contains("slot=1000"));
        assertTrue(str.contains("targetNodeId='target-node-id'"));
        assertTrue(str.contains("keysCount=100"));
        assertTrue(str.contains("migratedCount=50"));
        assertTrue(str.contains("progress=50%"));
    }
}
