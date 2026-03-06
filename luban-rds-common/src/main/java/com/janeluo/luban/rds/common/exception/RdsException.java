package com.janeluo.luban.rds.common.exception;

/**
 * RDS 运行时异常基类
 * 所有 RDS 相关的运行时异常都应继承此类
 */
public class RdsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

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
