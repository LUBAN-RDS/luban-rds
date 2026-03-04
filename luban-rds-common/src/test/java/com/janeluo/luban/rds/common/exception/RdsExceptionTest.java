package com.janeluo.luban.rds.common.exception;

import org.junit.Test;
import static org.junit.Assert.*;

public class RdsExceptionTest {

    @Test
    public void testDefaultConstructor() {
        RdsException exception = new RdsException();
        assertNotNull(exception);
    }

    @Test
    public void testMessageConstructor() {
        String message = "Test exception message";
        RdsException exception = new RdsException(message);
        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
    }

    @Test
    public void testMessageAndCauseConstructor() {
        String message = "Test exception message";
        Throwable cause = new RuntimeException("Test cause");
        RdsException exception = new RdsException(message, cause);
        assertNotNull(exception);
        assertEquals(message, exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    @Test
    public void testCauseConstructor() {
        Throwable cause = new RuntimeException("Test cause");
        RdsException exception = new RdsException(cause);
        assertNotNull(exception);
        assertEquals(cause, exception.getCause());
    }
}
