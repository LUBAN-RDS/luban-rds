package com.janeluo.luban.rds.common.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class RdsUtilTest {

    @Test
    public void testGetLogger() {
        assertNotNull(RdsUtil.getLogger(RdsUtilTest.class));
    }

    @Test
    public void testToString() {
        assertNull(RdsUtil.toString(null));
        assertEquals("test", RdsUtil.toString("test"));
        assertEquals("123", RdsUtil.toString(123));
    }

    @Test
    public void testIsEmpty() {
        assertTrue(RdsUtil.isEmpty(null));
        assertTrue(RdsUtil.isEmpty(""));
        assertFalse(RdsUtil.isEmpty("test"));
    }

    @Test
    public void testIsNotEmpty() {
        assertFalse(RdsUtil.isNotEmpty(null));
        assertFalse(RdsUtil.isNotEmpty(""));
        assertTrue(RdsUtil.isNotEmpty("test"));
    }

    @Test
    public void testCurrentTimeMillis() {
        long time1 = RdsUtil.currentTimeMillis();
        long time2 = System.currentTimeMillis();
        // 两个时间戳应该接近，但可能有微小差异
        assertTrue(Math.abs(time1 - time2) < 1000);
    }

    @Test
    public void testCurrentSeconds() {
        long seconds1 = RdsUtil.currentSeconds();
        long seconds2 = System.currentTimeMillis() / 1000;
        // 两个时间戳应该接近，但可能有微小差异
        assertTrue(Math.abs(seconds1 - seconds2) < 2);
    }
}
