package com.janeluo.luban.rds.cluster.migration;

import java.io.Serializable;

/**
 * 键导入结果（P1-17）。
 * <p>
 * 表示目标节点收到 MIGRATE_KEY 后 {@link SlotMigrationManager#importKey} 的结果，
 * 区分不同的失败原因，使源端能回送精确的 Redis 错误（BUSYKEY / IOERR 等）。
 * </p>
 */
public class ImportResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 导入结果状态
     */
    public enum Status {
        /** 导入成功 */
        SUCCESS,
        /** 槽位未处于 IMPORTING 状态 */
        NOT_IMPORTING,
        /** 目标键已存在且未带 REPLACE（对齐 Redis BUSYKEY） */
        BUSYKEY,
        /** 其他导入异常（反序列化失败、存储异常等） */
        ERROR
    }

    private final Status status;

    /**
     * 失败时的错误信息（成功时为 null）
     */
    private final String error;

    private ImportResult(Status status, String error) {
        this.status = status;
        this.error = error;
    }

    /**
     * 成功结果
     */
    public static ImportResult success() {
        return new ImportResult(Status.SUCCESS, null);
    }

    /**
     * 槽位未导入结果
     */
    public static ImportResult notImporting() {
        return new ImportResult(Status.NOT_IMPORTING, "importKey 失败：槽位未处于 IMPORTING 状态");
    }

    /**
     * BUSYKEY 结果（目标键已存在且未带 REPLACE）
     */
    public static ImportResult busykey() {
        return new ImportResult(Status.BUSYKEY, "BUSYKEY");
    }

    /**
     * 错误结果
     *
     * @param error 错误信息
     */
    public static ImportResult error(String error) {
        return new ImportResult(Status.ERROR, error);
    }

    public Status getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    /**
     * 是否成功
     */
    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
