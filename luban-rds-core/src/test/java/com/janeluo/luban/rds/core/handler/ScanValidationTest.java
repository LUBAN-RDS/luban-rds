package com.janeluo.luban.rds.core.handler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * SCAN/HSCAN/SSCAN/ZSCAN 游标与 COUNT 校验：断言与本地 Redis 7.0.12 实测行为一致。
 */
class ScanValidationTest {

    @Test
    void cursorAcceptsRedisStrToulForms() {
        assertTrue(CommonCommandHandler.isValidCursor("0"));
        assertTrue(CommonCommandHandler.isValidCursor(""));
        assertTrue(CommonCommandHandler.isValidCursor("+5"));   // 实测 Redis 接受
        assertTrue(CommonCommandHandler.isValidCursor("-1"));   // 实测 Redis 接受（回绕）
        assertTrue(CommonCommandHandler.isValidCursor("00"));
        assertTrue(CommonCommandHandler.isValidCursor("c:abc123")); // 本实现内部游标
    }

    @Test
    void cursorRejectsInvalidForms() {
        assertFalse(CommonCommandHandler.isValidCursor("abc"));
        assertFalse(CommonCommandHandler.isValidCursor("0x10"));      // 实测 Redis 拒绝（base 10 明确）
        assertFalse(CommonCommandHandler.isValidCursor(" 5"));
        assertFalse(CommonCommandHandler.isValidCursor("5 "));
        assertFalse(CommonCommandHandler.isValidCursor("18446744073709551616")); // > 2^64-1 → ERANGE
        assertFalse(CommonCommandHandler.isValidCursor("-18446744073709551617")); // < -2^64 → ERANGE
        assertFalse(CommonCommandHandler.isValidCursor("1a"));
    }

    @Test
    void countFollowsString2llSemantics() {
        assertEquals(5L, CommonCommandHandler.parseRedisLong("5"));
        assertEquals(0L, CommonCommandHandler.parseRedisLong("0"));
        assertEquals(-1L, CommonCommandHandler.parseRedisLong("-1"));
        assertEquals(2147483648L, CommonCommandHandler.parseRedisLong("2147483648"));
        assertNull(CommonCommandHandler.parseRedisLong("+5")); // 实测 Redis 拒绝 '+'
        assertNull(CommonCommandHandler.parseRedisLong("5.0"));
        assertNull(CommonCommandHandler.parseRedisLong("abc"));
        assertNull(CommonCommandHandler.parseRedisLong(""));
        assertNull(CommonCommandHandler.parseRedisLong(" 5"));
        assertNull(CommonCommandHandler.parseRedisLong("9223372036854775808")); // 溢出 long → ERANGE
    }
}