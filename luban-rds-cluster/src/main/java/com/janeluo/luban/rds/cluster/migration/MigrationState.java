package com.janeluo.luban.rds.cluster.migration;

import java.io.Serializable;

/**
 * 迁移状态
 * <p>
 * 表示槽位正在从本节点迁移到其他节点的状态信息
 * </p>
 */
public class MigrationState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 槽位号
     */
    private final int slot;

    /**
     * 目标节点ID
     */
    private final String targetNodeId;

    /**
     * 迁移开始时间（毫秒时间戳）
     */
    private final long startTime;

    /**
     * 槽位中的键总数
     */
    private volatile int keysCount;

    /**
     * 已迁移的键数量
     */
    private volatile int migratedCount;

    /**
     * 迁移状态：running, paused, completed, failed
     */
    private volatile String status;

    /**
     * 错误信息（如果有）
     */
    private volatile String errorMessage;

    /**
     * 构造方法
     *
     * @param slot         槽位号
     * @param targetNodeId 目标节点ID
     */
    public MigrationState(int slot, String targetNodeId) {
        this.slot = slot;
        this.targetNodeId = targetNodeId;
        this.startTime = System.currentTimeMillis();
        this.keysCount = 0;
        this.migratedCount = 0;
        this.status = "running";
    }

    // ==================== Getter 方法 ====================

    public int getSlot() {
        return slot;
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getKeysCount() {
        return keysCount;
    }

    public int getMigratedCount() {
        return migratedCount;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // ==================== Setter 方法 ====================

    public void setKeysCount(int keysCount) {
        this.keysCount = keysCount;
    }

    public void setMigratedCount(int migratedCount) {
        this.migratedCount = migratedCount;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // ==================== 业务方法 ====================

    /**
     * 增加已迁移键数量
     */
    public synchronized void incrementMigratedCount() {
        this.migratedCount++;
    }

    /**
     * 增加已迁移键数量
     *
     * @param count 增加的数量
     */
    public synchronized void incrementMigratedCount(int count) {
        this.migratedCount += count;
    }

    /**
     * 获取迁移进度百分比
     *
     * @return 进度百分比（0-100）
     */
    public int getProgress() {
        if (keysCount == 0) {
            return 0;
        }
        return (int) ((migratedCount * 100.0) / keysCount);
    }

    /**
     * 获取已运行时间（毫秒）
     *
     * @return 已运行时间
     */
    public long getElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * 检查迁移是否完成
     *
     * @return 是否完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * 检查迁移是否失败
     *
     * @return 是否失败
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /**
     * 检查迁移是否正在运行
     *
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return "running".equals(status);
    }

    /**
     * 标记迁移完成
     */
    public void markCompleted() {
        this.status = "completed";
        this.migratedCount = this.keysCount;
    }

    /**
     * 标记迁移失败
     *
     * @param error 错误信息
     */
    public void markFailed(String error) {
        this.status = "failed";
        this.errorMessage = error;
    }

    @Override
    public String toString() {
        return "MigrationState{" +
                "slot=" + slot +
                ", targetNodeId='" + targetNodeId + '\'' +
                ", startTime=" + startTime +
                ", keysCount=" + keysCount +
                ", migratedCount=" + migratedCount +
                ", progress=" + getProgress() + "%" +
                ", status='" + status + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
