package com.janeluo.luban.rds.mesh.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link LogEntry} encode/decode 往返一致性单元测试。
 * <p>
 * 覆盖：普通写（extra 为 null）、事务写（extra 非 null）、大 dbIndex、边界 term/index、
 * 空 payload、全字段精确匹配。
 * </p>
 */
class LogEntryTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void roundtrip_normalWrite_extraNull_allFieldsMatch() {
        byte[] payload = utf8("*3\r\n$3\r\nSET\r\n$3\r\nfoo\r\n$3\r\nbar\r\n");
        LogEntry original = new LogEntry(5L, 42L, payload, 0, null);

        byte[] encoded = original.encode();
        LogEntry decoded = LogEntry.decode(encoded);

        assertEquals(5L, decoded.getTerm());
        assertEquals(42L, decoded.getIndex());
        assertArrayEquals(payload, decoded.getRespPayload());
        assertEquals(0, decoded.getDbIndex());
        assertNull(decoded.getExtra(), "普通写 extra 应为 null");
    }

    @Test
    void roundtrip_transactionWrite_extraNonNull_allFieldsMatch() {
        byte[] payload = utf8("*1\r\n$4\r\nMULTI\r\n");
        byte[] extra = utf8("[[\"SET\",\"k\",\"1\"],[\"INCR\",\"c\"]]|watchVer=7");
        LogEntry original = new LogEntry(7L, 100L, payload, 2, extra);

        byte[] encoded = original.encode();
        LogEntry decoded = LogEntry.decode(encoded);

        assertEquals(7L, decoded.getTerm());
        assertEquals(100L, decoded.getIndex());
        assertArrayEquals(payload, decoded.getRespPayload());
        assertEquals(2, decoded.getDbIndex());
        assertNotNull(decoded.getExtra(), "事务写 extra 不应为 null");
        assertArrayEquals(extra, decoded.getExtra());
    }

    @Test
    void roundtrip_emptyPayload_extraNull() {
        LogEntry original = new LogEntry(1L, 1L, new byte[0], 0, null);

        LogEntry decoded = LogEntry.decode(original.encode());

        assertEquals(0, decoded.getRespPayload().length);
        assertNull(decoded.getExtra());
        assertEquals(1L, decoded.getTerm());
        assertEquals(1L, decoded.getIndex());
    }

    @Test
    void roundtrip_nullPayload_returnsEmptyArray() {
        // 构造时 payload 为 null，getter 应返回空数组；encode/decode 后仍空数组
        LogEntry original = new LogEntry(9L, 9L, null, 3, null);

        LogEntry decoded = LogEntry.decode(original.encode());

        assertEquals(0, decoded.getRespPayload().length, "null payload 经 getter 应为空数组");
        assertEquals(0, original.getRespPayload().length);
        assertEquals(3, decoded.getDbIndex());
    }

    @Test
    void roundtrip_largeTermIndex_dbIndexMaxValue() {
        byte[] payload = utf8("*3\r\n$4\r\nHSET\r\n$1\r\nh\r\n$1\r\nf\r\n");
        LogEntry original = new LogEntry(Long.MAX_VALUE, Long.MAX_VALUE, payload, Integer.MAX_VALUE, null);

        LogEntry decoded = LogEntry.decode(original.encode());

        assertEquals(Long.MAX_VALUE, decoded.getTerm());
        assertEquals(Long.MAX_VALUE, decoded.getIndex());
        assertEquals(Integer.MAX_VALUE, decoded.getDbIndex());
        assertArrayEquals(payload, decoded.getRespPayload());
    }

    @Test
    void roundtrip_zeroTermIndex_boundary() {
        byte[] extra = utf8("snapshot-watch");
        LogEntry original = new LogEntry(0L, 0L, utf8("ping"), 0, extra);

        LogEntry decoded = LogEntry.decode(original.encode());

        assertEquals(0L, decoded.getTerm());
        assertEquals(0L, decoded.getIndex());
        assertArrayEquals(extra, decoded.getExtra());
    }

    @Test
    void roundtrip_utf8Payload_preservedExactly() {
        // 含多字节 UTF-8 的 RESP 帧（确保 length-prefix 按字节而非字符）
        byte[] payload = utf8("*2\r\n$3\r\nSET\r\n$7\r\n你好世界\r\n");
        LogEntry original = new LogEntry(3L, 15L, payload, 1, null);

        LogEntry decoded = LogEntry.decode(original.encode());

        assertArrayEquals(payload, decoded.getRespPayload());
        assertEquals(payload.length, decoded.getRespPayload().length);
    }
}
