package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.core.store.ValueSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RESTORE 命令处理器测试（P0-新3）。
 * <p>
 * 覆盖：RESTORE 还原迁移载荷（与 MIGRATE_KEY 载荷同一序列化实现）、BUSYKEY/REPLACE 语义、
 * TTL 毫秒语义、参数与选项校验。
 * </p>
 */
class RestoreCommandHandlerTest {

    private MemoryStore store;
    private RestoreCommandHandler handler;

    @BeforeEach
    void setUp() {
        store = new DefaultMemoryStore();
        handler = new RestoreCommandHandler();
    }

    @Test
    @DisplayName("RESTORE 还原序列化载荷并存储（与迁移载荷互通）")
    void testRestoreRoundTrip() throws Exception {
        byte[] payload = ValueSerialization.serialize("migrated-value");
        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);

        Object result = handler.handle(0,
                new String[]{"RESTORE", "key1", "0", payloadStr}, store);

        assertEquals("+OK\r\n", result);
        assertTrue(store.exists(0, "key1"));
        assertEquals("migrated-value", store.get(0, "key1"));
    }

    @Test
    @DisplayName("RESTORE 带 ttl 时按毫秒设置过期（与 MIGRATE TTL 语义一致）")
    void testRestoreWithTtlMs() throws Exception {
        byte[] payload = ValueSerialization.serialize("ttl-value");
        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);

        Object result = handler.handle(0,
                new String[]{"RESTORE", "key-ttl", "3000", payloadStr}, store);

        assertEquals("+OK\r\n", result);
        long ttl = store.pttl(0, "key-ttl");
        assertTrue(ttl > 0 && ttl <= 3000, "应按毫秒设置 TTL，实际: " + ttl);
    }

    @Test
    @DisplayName("RESTORE 键已存在且未带 REPLACE 返回 BUSYKEY")
    void testRestoreBusykey() throws Exception {
        store.set(0, "existing", "old");
        byte[] payload = ValueSerialization.serialize("new");
        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);

        Object result = handler.handle(0,
                new String[]{"RESTORE", "existing", "0", payloadStr}, store);

        assertTrue(result.toString().startsWith("-BUSYKEY"));
        assertEquals("old", store.get(0, "existing"), "BUSYKEY 时不应覆盖");
    }

    @Test
    @DisplayName("RESTORE 带 REPLACE 覆盖已存在键")
    void testRestoreReplace() throws Exception {
        store.set(0, "replace-key", "old");
        byte[] payload = ValueSerialization.serialize("new");
        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);

        Object result = handler.handle(0,
                new String[]{"RESTORE", "replace-key", "0", payloadStr, "REPLACE"}, store);

        assertEquals("+OK\r\n", result);
        assertEquals("new", store.get(0, "replace-key"));
    }

    @Test
    @DisplayName("RESTORE 参数不足/非法 TTL/未知选项返回错误")
    void testRestoreValidation() throws Exception {
        byte[] payload = ValueSerialization.serialize("v");
        String payloadStr = new String(payload, StandardCharsets.ISO_8859_1);

        // 参数不足
        assertTrue(handler.handle(0, new String[]{"RESTORE", "k", "0"}, store).toString().startsWith("-ERR"));
        // 非法 TTL
        assertTrue(handler.handle(0, new String[]{"RESTORE", "k", "abc", payloadStr}, store)
                .toString().startsWith("-ERR"));
        // 负 TTL
        assertTrue(handler.handle(0, new String[]{"RESTORE", "k", "-1", payloadStr}, store)
                .toString().startsWith("-ERR"));
        // 未知选项
        assertTrue(handler.handle(0, new String[]{"RESTORE", "k", "0", payloadStr, "FOO"}, store)
                .toString().startsWith("-ERR"));
    }

    @Test
    @DisplayName("RESTORE 坏载荷返回 Bad data format（不写存储）")
    void testRestoreBadPayload() {
        Object result = handler.handle(0,
                new String[]{"RESTORE", "bad", "0", "not-a-serialized-payload"}, store);

        assertTrue(result.toString().startsWith("-ERR"));
        assertFalse(store.exists(0, "bad"));
    }
}
