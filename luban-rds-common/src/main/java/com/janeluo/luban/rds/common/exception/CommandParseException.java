package com.janeluo.luban.rds.common.exception;

public class CommandParseException extends RdsException {
    public CommandParseException() {
        super();
    }
    
    public CommandParseException(String message) {
        super(message);
    }
    
    public CommandParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
