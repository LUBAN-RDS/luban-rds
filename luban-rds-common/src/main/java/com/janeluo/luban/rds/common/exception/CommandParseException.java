package com.janeluo.luban.rds.common.exception;

/**
 * 命令解析异常
 * 当 Redis 协议命令解析失败时抛出此异常
 */
public class CommandParseException extends RdsException {

    private static final long serialVersionUID = 1L;

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
