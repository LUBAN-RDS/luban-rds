package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogApplier} 单元测试（阶段 4.3）。
 * <p>
 * 用真实 {@link DefaultCommandHandler} + {@link DefaultMemoryStore} 验证：
 * <ul>
 *   <li>SET LogEntry apply 后 rawStore 有 foo=bar，返回值是 {@code "+OK\r\n"} 类响应；</li>
 *   <li>GET LogEntry apply 返回值是 bar（bulk string）；</li>
 *   <li>DEL LogEntry apply 返回值是 1（delete 存在 key）/ 0（不存在）；</li>
 *   <li>RESP 解析失败的 LogEntry 返回 {@code "-ERR ..."}（不抛异常）；</li>
 *   <li>事务（extra != null）抛 {@link UnsupportedOperationException}（阶段 9 完善）；</li>
 *   <li>applyAndSerialize 返回正确的 RESP 字节。</li>
 * </ul>
 * </p>
 */
class LogApplierTest {

    private DefaultMemoryStore rawStore;
    private DefaultCommandHandler handler;
    private LogApplier applier;

    @BeforeEach
    void setUp() {
        rawStore = new DefaultMemoryStore();
        handler = new DefaultCommandHandler();
        applier = new LogApplier(handler, rawStore);
    }

    /** 构造一个完整 RESP 命令帧的字节数组。 */
    private static byte[] respFrame(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.ISO_8859_1);
            sb.append('$').append(b.length).append("\r\n")
              .append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void apply_set_writesToRawStoreAndReturnsOk() {
        byte[] frame = respFrame("SET", "foo", "bar");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        Object response = applier.apply(entry);

        // 返回值是客户端响应对象（+OK\r\n）
        assertEquals("+OK\r\n", response);

        // raw store 确实写入 foo=bar（apply 唯一目标）
        assertEquals("bar", rawStore.get(0, "foo"));
    }

    @Test
    void apply_get_returnsBulkStringOfValue() {
        // 先写入
        rawStore.set(0, "foo", "bar");

        byte[] frame = respFrame("GET", "foo");
        LogEntry entry = new LogEntry(1L, 2L, frame, 0, null);

        Object response = applier.apply(entry);

        // GET 返回 bulk string: $3\r\nbar\r\n
        assertEquals("$3\r\nbar\r\n", response);
    }

    @Test
    void apply_getMissingKey_returnsNullBulk() {
        byte[] frame = respFrame("GET", "nope");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        Object response = applier.apply(entry);

        assertEquals("$-1\r\n", response);
    }

    @Test
    void apply_del_existingKey_returnsOne() {
        rawStore.set(0, "foo", "bar");

        byte[] frame = respFrame("DEL", "foo");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        Object response = applier.apply(entry);

        // DEL 存在 key 返回 :1\r\n
        assertEquals(":1\r\n", response);
        // 已被删除
        assertNull(rawStore.get(0, "foo"));
    }

    @Test
    void apply_del_missingKey_returnsZero() {
        byte[] frame = respFrame("DEL", "nope");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        Object response = applier.apply(entry);

        assertEquals(":0\r\n", response);
    }

    @Test
    void apply_incr_incrementsValue() {
        rawStore.set(0, "counter", "41");

        byte[] frame = respFrame("INCR", "counter");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        Object response = applier.apply(entry);

        // INCR 返回整数 :42\r\n
        assertEquals(":42\r\n", response);
        assertEquals("42", rawStore.get(0, "counter"));
    }

    @Test
    void apply_dbIndex_isolatesDatabases() {
        // db 0 写入 foo=db0
        byte[] f0 = respFrame("SET", "foo", "db0");
        applier.apply(new LogEntry(1L, 1L, f0, 0, null));
        // db 1 写入 foo=db1
        byte[] f1 = respFrame("SET", "foo", "db1");
        applier.apply(new LogEntry(1L, 2L, f1, 1, null));

        assertEquals("db0", rawStore.get(0, "foo"));
        assertEquals("db1", rawStore.get(1, "foo"));
    }

    @Test
    void apply_malformedResp_returnsErrorObjectWithoutThrowing() {
        // 非法 RESP 帧
        LogEntry entry = new LogEntry(1L, 1L, "not a resp frame".getBytes(StandardCharsets.UTF_8), 0, null);

        Object response = applier.apply(entry);

        // 解析失败返回 -ERR（不抛异常中断 apply 循环）
        assertTrue(response instanceof String, "响应应为 String 错误对象");
        String s = (String) response;
        assertTrue(s.startsWith("-ERR"), "应以 -ERR 开头: " + s);
    }

    @Test
    void apply_incompleteResp_returnsErrorObject() {
        // 不完整 RESP（只有数组头，无内容）
        LogEntry entry = new LogEntry(1L, 1L, "*3\r\n".getBytes(StandardCharsets.UTF_8), 0, null);

        Object response = applier.apply(entry);

        assertTrue(response instanceof String);
        assertTrue(((String) response).startsWith("-ERR"));
    }

    @Test
    void apply_emptyPayload_returnsErrorObject() {
        LogEntry entry = new LogEntry(1L, 1L, new byte[0], 0, null);

        Object response = applier.apply(entry);

        assertTrue(((String) response).startsWith("-ERR"));
    }

    @Test
    void apply_nullEntry_returnsErrorObject() {
        Object response = applier.apply(null);

        assertTrue(((String) response).startsWith("-ERR"));
    }

    @Test
    void apply_transactionExtra_malformed_returnsErrorObject() {
        // 阶段 9：extra != null 走事务分支；格式非法（非 TransactionPayload 编码）返回 -ERR
        LogEntry entry = new LogEntry(1L, 1L, respFrame("MULTI"), 0, new byte[]{1, 2});

        Object response = applier.apply(entry);

        assertTrue(response instanceof String, "响应应为 String 错误对象");
        String s = (String) response;
        assertTrue(s.startsWith("-ERR"), "非法 extra 应返回 -ERR: " + s);
    }

    @Test
    void applyAndSerialize_set_returnsOkBytes() {
        byte[] frame = respFrame("SET", "k", "v");
        LogEntry entry = new LogEntry(1L, 1L, frame, 0, null);

        byte[] bytes = applier.applyAndSerialize(entry);

        // 序列化后与直连 server 一致：+OK\r\n
        assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), bytes);
    }

    @Test
    void applyAndSerialize_get_returnsBulkBytes() {
        rawStore.set(0, "foo", "bar");
        byte[] frame = respFrame("GET", "foo");
        LogEntry entry = new LogEntry(1L, 2L, frame, 0, null);

        byte[] bytes = applier.applyAndSerialize(entry);

        assertArrayEquals("$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1), bytes);
    }

    @Test
    void applyAndSerialize_del_returnsIntegerBytes() {
        rawStore.set(0, "foo", "bar");
        byte[] frame = respFrame("DEL", "foo");
        LogEntry entry = new LogEntry(1L, 2L, frame, 0, null);

        byte[] bytes = applier.applyAndSerialize(entry);

        assertArrayEquals(":1\r\n".getBytes(StandardCharsets.ISO_8859_1), bytes);
    }

    @Test
    void serializeResponse_stringStartingWithRespPrefix_passesThrough() {
        // +OK\r\n 应原样序列化（不被再包装为 bulk string）
        byte[] bytes = applier.serializeResponse("+OK\r\n");
        assertArrayEquals("+OK\r\n".getBytes(StandardCharsets.ISO_8859_1), bytes);

        // :5\r\n 原样
        byte[] intBytes = applier.serializeResponse(":5\r\n");
        assertArrayEquals(":5\r\n".getBytes(StandardCharsets.ISO_8859_1), intBytes);
    }

    @Test
    void constructor_rejectsNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> new LogApplier(null, rawStore));
        assertThrows(IllegalArgumentException.class,
                () -> new LogApplier(handler, null));
    }
}
