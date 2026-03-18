package com.janeluo.luban.rds.cluster.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExportResult 测试类
 */
class ExportResultTest {

    @Test
    @DisplayName("测试成功的导出结果")
    void testSuccessResult() {
        byte[] value = "test-value".getBytes();
        ExportResult result = ExportResult.success("test-key", value, 1000L, "string");
        
        assertTrue(result.isSuccess());
        assertEquals("test-key", result.getKey());
        assertArrayEquals(value, result.getValue());
        assertEquals(1000L, result.getTtl());
        assertEquals("string", result.getType());
        assertNull(result.getError());
        assertTrue(result.isKeyExists());
        assertTrue(result.hasTtl());
    }

    @Test
    @DisplayName("测试失败的导出结果")
    void testFailureResult() {
        ExportResult result = ExportResult.failure("test-key", "Export failed");
        
        assertFalse(result.isSuccess());
        assertEquals("test-key", result.getKey());
        assertEquals("Export failed", result.getError());
        assertNull(result.getValue());
        assertEquals(0L, result.getTtl());
        assertNull(result.getType());
        assertFalse(result.isKeyExists());
        assertFalse(result.hasTtl());
    }

    @Test
    @DisplayName("测试键不存在的导出结果")
    void testNotFoundResult() {
        ExportResult result = ExportResult.notFound("test-key");
        
        assertFalse(result.isSuccess());
        assertEquals("test-key", result.getKey());
        assertEquals("Key not found", result.getError());
    }

    @Test
    @DisplayName("测试无过期时间")
    void testNoTtl() {
        byte[] value = "test-value".getBytes();
        ExportResult result = ExportResult.success("test-key", value, 0L, "string");
        
        assertFalse(result.hasTtl());
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        byte[] value = "test-value".getBytes();
        
        ExportResult successResult = ExportResult.success("test-key", value, 1000L, "string");
        String successStr = successResult.toString();
        assertTrue(successStr.contains("success=true"));
        assertTrue(successStr.contains("key='test-key'"));
        assertTrue(successStr.contains("type='string'"));
        assertTrue(successStr.contains("ttl=1000"));
        
        ExportResult failureResult = ExportResult.failure("test-key", "Error");
        String failureStr = failureResult.toString();
        assertTrue(failureStr.contains("success=false"));
        assertTrue(failureStr.contains("error='Error'"));
    }
}
