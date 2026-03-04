package com.janeluo.luban.rds.common.exception;

public class RdsException extends RuntimeException {
    public RdsException() {
        super();
    }
    
    public RdsException(String message) {
        super(message);
    }
    
    public RdsException(String message, Throwable cause) {
        super(message, cause);
    }
    
    public RdsException(Throwable cause) {
        super(cause);
    }
}
