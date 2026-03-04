package com.janeluo.luban.rds.common.constant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RdsDataTypeConstantTest {

    @Test
    public void testDataTypeConstants() {
        assertEquals("string", RdsDataTypeConstant.STRING);
        assertEquals("hash", RdsDataTypeConstant.HASH);
        assertEquals("list", RdsDataTypeConstant.LIST);
        assertEquals("set", RdsDataTypeConstant.SET);
        assertEquals("zset", RdsDataTypeConstant.ZSET);
        assertEquals("none", RdsDataTypeConstant.NONE);
    }
}
