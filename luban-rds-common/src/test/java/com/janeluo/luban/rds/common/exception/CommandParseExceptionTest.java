package com.janeluo.luban.rds.common.exception;

import org.junit.Test;
import static org.junit.Assert.*;

public class CommandParseExceptionTest {

    @Test
    public void testDefaultConstructor() {
        CommandParseException exception = new CommandParseException();
        assertNotNull(exception);
        assertTrue(exception instanceof RdsException);
    }

    @Test
    public void testMessageConstructor() {
        String message = "Test parse exception message";
        CommandParseException exception = new CommandParseException(message);
        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertTrue(exception instanceof RdsException);
    }

    @Test
    public void testMessageAndCauseConstructor() {
        String message = "Test parse exception message";
        Throwable cause = new RuntimeException("Test cause");
        CommandParseException exception = new CommandParseException(message, cause);
        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertTrue(exception instanceof RdsException);
    }
}
