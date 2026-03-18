package com.janeluo.luban.rds.cluster.config;

import java.io.Serializable;

/**
 * 集群统计信息
 * <p>
 * 用于存储和展示集群的运行状态统计信息
 * </p>
 */
public class ClusterStats implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 集群状态：ok/fail
     */
    private String state;

    /**
     * 已分配的槽位数量
     */
    private int slotsAssigned;

    /**
     * 正常状态的槽位数量
     */
    private int slotsOk;

    /**
     * 处于PFAIL状态的槽位数量
     */
    private int slotsPfail;

    /**
     * 处于FAIL状态的槽位数量
     */
    private int slotsFail;

    /**
     * 已知节点数量
     */
    private int knownNodes;

    /**
     * 主节点数量（集群规模）
     */
    private int size;

    /**
     * 当前集群配置纪元
     */
    private long currentEpoch;

    /**
     * 当前节点的配置纪元
     */
    private long myEpoch;

    /**
     * 已发送的消息数量
     */
    private long messagesSent;

    /**
     * 已接收的消息数量
     */
    private long messagesReceived;

    /**
     * 默认构造方法
     */
    public ClusterStats() {
        this.state = "fail";
        this.slotsAssigned = 0;
        this.slotsOk = 0;
        this.slotsPfail = 0;
        this.slotsFail = 0;
        this.knownNodes = 0;
        this.size = 0;
        this.currentEpoch = 0;
        this.myEpoch = 0;
        this.messagesSent = 0;
        this.messagesReceived = 0;
    }

    // ==================== Getter/Setter 方法 ====================

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public int getSlotsAssigned() {
        return slotsAssigned;
    }

    public void setSlotsAssigned(int slotsAssigned) {
        this.slotsAssigned = slotsAssigned;
    }

    public int getSlotsOk() {
        return slotsOk;
    }

    public void setSlotsOk(int slotsOk) {
        this.slotsOk = slotsOk;
    }

    public int getSlotsPfail() {
        return slotsPfail;
    }

    public void setSlotsPfail(int slotsPfail) {
        this.slotsPfail = slotsPfail;
    }

    public int getSlotsFail() {
        return slotsFail;
    }

    public void setSlotsFail(int slotsFail) {
        this.slotsFail = slotsFail;
    }

    public int getKnownNodes() {
        return knownNodes;
    }

    public void setKnownNodes(int knownNodes) {
        this.knownNodes = knownNodes;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public long getCurrentEpoch() {
        return currentEpoch;
    }

    public void setCurrentEpoch(long currentEpoch) {
        this.currentEpoch = currentEpoch;
    }

    public long getMyEpoch() {
        return myEpoch;
    }

    public void setMyEpoch(long myEpoch) {
        this.myEpoch = myEpoch;
    }

    public long getMessagesSent() {
        return messagesSent;
    }

    public void setMessagesSent(long messagesSent) {
        this.messagesSent = messagesSent;
    }

    public long getMessagesReceived() {
        return messagesReceived;
    }

    public void setMessagesReceived(long messagesReceived) {
        this.messagesReceived = messagesReceived;
    }

    // ==================== 统计更新方法 ====================

    /**
     * 增加已发送消息计数
     *
     * @param count 增加的数量
     */
    public void incrementMessagesSent(long count) {
        this.messagesSent += count;
    }

    /**
     * 增加已接收消息计数
     *
     * @param count 增加的数量
     */
    public void incrementMessagesReceived(long count) {
        this.messagesReceived += count;
    }

    /**
     * 判断集群是否健康
     *
     * @return 集群是否处于健康状态
     */
    public boolean isHealthy() {
        return "ok".equalsIgnoreCase(state);
    }

    @Override
    public String toString() {
        return "ClusterStats{" +
                "state='" + state + '\'' +
                ", slotsAssigned=" + slotsAssigned +
                ", slotsOk=" + slotsOk +
                ", slotsPfail=" + slotsPfail +
                ", slotsFail=" + slotsFail +
                ", knownNodes=" + knownNodes +
                ", size=" + size +
                ", currentEpoch=" + currentEpoch +
                ", myEpoch=" + myEpoch +
                ", messagesSent=" + messagesSent +
                ", messagesReceived=" + messagesReceived +
                '}';
    }
}
