package com.janeluo.luban.rds.common.constant;

import org.junit.Test;
import static org.junit.Assert.*;

public class RdsResponseConstantTest {

    @Test
    public void testSimpleStringResponses() {
        assertEquals("+OK\r\n", RdsResponseConstant.OK);
        assertEquals("+PONG\r\n", RdsResponseConstant.PONG);
        assertEquals("+QUEUED\r\n", RdsResponseConstant.QUEUED);
    }

    @Test
    public void testIntegerResponses() {
        assertEquals(":0\r\n", RdsResponseConstant.ZERO);
        assertEquals(":1\r\n", RdsResponseConstant.ONE);
        assertEquals(":-1\r\n", RdsResponseConstant.MINUS_ONE);
        assertEquals(":-2\r\n", RdsResponseConstant.MINUS_TWO);
    }

    @Test
    public void testNullResponses() {
        assertEquals("$-1\r\n", RdsResponseConstant.NULL_BULK);
        assertEquals("*0\r\n", RdsResponseConstant.EMPTY_ARRAY);
        assertEquals("$0\r\n\r\n", RdsResponseConstant.EMPTY_BULK);
    }

    @Test
    public void testErrorResponses() {
        assertEquals("-ERR value is not an integer or out of range\r\n", RdsResponseConstant.ERR_NOT_INTEGER);
        assertEquals("-ERR value is not a valid float\r\n", RdsResponseConstant.ERR_NOT_FLOAT);
        assertEquals("-ERR syntax error\r\n", RdsResponseConstant.ERR_SYNTAX);
        assertEquals("-ERR unknown command '" , RdsResponseConstant.ERR_UNKNOWN_COMMAND_PREFIX);
        assertEquals("-ERR wrong number of arguments for '" , RdsResponseConstant.ERR_WRONG_ARGS_PREFIX);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", RdsResponseConstant.ERR_WRONG_TYPE);
    }

    @Test
    public void testIntResponseMethod() {
        assertEquals(":0\r\n", RdsResponseConstant.intResponse(0));
        assertEquals(":1\r\n", RdsResponseConstant.intResponse(1));
        assertEquals(":100\r\n", RdsResponseConstant.intResponse(100));
        assertEquals(":101\r\n", RdsResponseConstant.intResponse(101));
        assertEquals(":-1\r\n", RdsResponseConstant.intResponse(-1));
    }

    @Test
    public void testBulkStringMethod() {
        assertEquals("$-1\r\n", RdsResponseConstant.bulkString(null));
        assertEquals("$0\r\n\r\n", RdsResponseConstant.bulkString(""));
        assertEquals("$5\r\nhello\r\n", RdsResponseConstant.bulkString("hello"));
    }

    @Test
    public void testErrorMethod() {
        assertEquals("-ERR test error\r\n", RdsResponseConstant.error("test error"));
    }

    @Test
    public void testWrongArgsErrorMethod() {
        assertEquals("-ERR wrong number of arguments for 'GET' command\r\n", RdsResponseConstant.wrongArgsError("GET"));
    }
}
