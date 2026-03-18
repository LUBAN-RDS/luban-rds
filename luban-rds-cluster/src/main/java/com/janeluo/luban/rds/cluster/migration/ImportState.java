package com.janeluo.luban.rds.cluster.migration;

import java.io.Serializable;

/**
 * 导入状态
 * <p>
 * 表示槽位正在从其他节点导入到本节点的状态信息
 * </p>
 */
public class ImportState implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 槽位号
     */
    private final int slot;

    /**
     * 源节点ID
     */
    private final String sourceNodeId;

    /**
     * 导入开始时间（毫秒时间戳）
     */
    private final long startTime;

    /**
     * 已导入的键数量
     */
    private volatile int importedCount;

    /**
     * 导入状态：running, paused, completed, failed
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
     * @param sourceNodeId 源节点ID
     */
    public ImportState(int slot, String sourceNodeId) {
        this.slot = slot;
        this.sourceNodeId = sourceNodeId;
        this.startTime = System.currentTimeMillis();
        this.importedCount = 0;
        this.status = "running";
    }

    // ==================== Getter 方法 ====================

    public int getSlot() {
        return slot;
    }

    public String getSourceNodeId() {
        return sourceNodeId;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getImportedCount() {
        return importedCount;
    }

    public String getStatus() {
        return status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    // ==================== Setter 方法 ====================

    public void setImportedCount(int importedCount) {
        this.importedCount = importedCount;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    // ==================== 业务方法 ====================

    /**
     * 增加已导入键数量
     */
    public synchronized void incrementImportedCount() {
        this.importedCount++;
    }

    /**
     * 增加已导入键数量
     *
     * @param count 增加的数量
     */
    public synchronized void incrementImportedCount(int count) {
        this.importedCount += count;
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
     * 检查导入是否完成
     *
     * @return 是否完成
     */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /**
     * 检查导入是否失败
     *
     * @return 是否失败
     */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /**
     * 检查导入是否正在运行
     *
     * @return 是否正在运行
     */
    public boolean isRunning() {
        return "running".equals(status);
    }

    /**
     * 标记导入完成
     */
    public void markCompleted() {
        this.status = "completed";
    }

    /**
     * 标记导入失败
     *
     * @param error 错误信息
     */
    public void markFailed(String error) {
        this.status = "failed";
        this.errorMessage = error;
    }

    @Override
    public String toString() {
        return "ImportState{" +
                "slot=" + slot +
                ", sourceNodeId='" + sourceNodeId + '\'' +
                ", startTime=" + startTime +
                ", importedCount=" + importedCount +
                ", status='" + status + '\'' +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
