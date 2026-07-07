package com.janeluo.luban.rds.client.cli;

/**
 * 集群搭建过程中的统一异常
 * <p>
 * 任何编排步骤失败均抛出此异常，由 {@link RedisCliMain} 捕获后
 * 打印错误信息并以非零状态码退出。
 * </p>
 *
 * @author janeluo
 * @since 1.0.0
 */
public class ClusterSetupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClusterSetupException(String message) {
        super(message);
    }

    public ClusterSetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
