package com.janeluo.luban.rds.cluster.migration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImportState 测试类
 */
class ImportStateTest {

    private ImportState importState;

    @BeforeEach
    void setUp() {
        importState = new ImportState(1000, "source-node-id");
    }

    @Test
    @DisplayName("测试构造方法")
    void testConstructor() {
        assertEquals(1000, importState.getSlot());
        assertEquals("source-node-id", importState.getSourceNodeId());
        assertEquals(0, importState.getImportedCount());
        assertEquals("running", importState.getStatus());
        assertNull(importState.getErrorMessage());
    }

    @Test
    @DisplayName("测试增加已导入计数")
    void testIncrementImportedCount() {
        importState.incrementImportedCount();
        assertEquals(1, importState.getImportedCount());
        
        importState.incrementImportedCount(10);
        assertEquals(11, importState.getImportedCount());
    }

    @Test
    @DisplayName("测试状态检查")
    void testStatusCheck() {
        assertTrue(importState.isRunning());
        assertFalse(importState.isCompleted());
        assertFalse(importState.isFailed());
        
        importState.markCompleted();
        assertTrue(importState.isCompleted());
        assertFalse(importState.isRunning());
        
        ImportState failedState = new ImportState(2000, "source-node-id");
        failedState.markFailed("Test error");
        assertTrue(failedState.isFailed());
        assertEquals("Test error", failedState.getErrorMessage());
    }

    @Test
    @DisplayName("测试已运行时间")
    void testGetElapsedTime() throws InterruptedException {
        Thread.sleep(100);
        long elapsed = importState.getElapsedTime();
        assertTrue(elapsed >= 100);
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        importState.incrementImportedCount(50);
        String str = importState.toString();
        
        assertTrue(str.contains("slot=1000"));
        assertTrue(str.contains("sourceNodeId='source-node-id'"));
        assertTrue(str.contains("importedCount=50"));
    }
}
