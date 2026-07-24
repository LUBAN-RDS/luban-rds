package com.janeluo.luban.rds.replication;

/**
 * 复制命令应用失败时抛出，触发上层断开重连。
 *
 * <p>当从节点在应用主节点传播的命令流时发生解析或执行错误，
 * 抛出此异常以通知上层（{@link SlaveReplicationService}）断开当前连接并触发重连，
 * 从而避免主从数据不一致。
 */
public class ReplicationApplyException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * 创建复制应用异常。
     *
     * @param message 错误描述
     * @param cause   根本原因
     */
    public ReplicationApplyException(String message, Throwable cause) {
        super(message, cause);
    }
}
