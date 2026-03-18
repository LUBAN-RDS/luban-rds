package com.janeluo.luban.rds.cluster.node;

import java.io.Serializable;

/**
 * 集群节点连接信息
 * <p>
 * 表示与集群节点的连接状态和相关统计信息
 * </p>
 */
public class ClusterLink implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否已连接
     */
    private boolean connected;

    /**
     * 最后一次交互时间（时间戳，毫秒）
     */
    private long lastInteractionTime;

    /**
     * 出站缓冲区大小（字节）
     */
    private long outboundBufferSize;

    /**
     * 默认构造方法
     */
    public ClusterLink() {
        this.connected = false;
        this.lastInteractionTime = System.currentTimeMillis();
        this.outboundBufferSize = 0;
    }

    /**
     * 带参数的构造方法
     *
     * @param connected            是否已连接
     * @param lastInteractionTime  最后一次交互时间
     * @param outboundBufferSize   出站缓冲区大小
     */
    public ClusterLink(boolean connected, long lastInteractionTime, long outboundBufferSize) {
        this.connected = connected;
        this.lastInteractionTime = lastInteractionTime;
        this.outboundBufferSize = outboundBufferSize;
    }

    /**
     * 获取连接状态
     *
     * @return 是否已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 设置连接状态
     *
     * @param connected 是否已连接
     */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * 获取最后一次交互时间
     *
     * @return 最后一次交互时间（毫秒时间戳）
     */
    public long getLastInteractionTime() {
        return lastInteractionTime;
    }

    /**
     * 设置最后一次交互时间
     *
     * @param lastInteractionTime 最后一次交互时间（毫秒时间戳）
     */
    public void setLastInteractionTime(long lastInteractionTime) {
        this.lastInteractionTime = lastInteractionTime;
    }

    /**
     * 获取出站缓冲区大小
     *
     * @return 出站缓冲区大小（字节）
     */
    public long getOutboundBufferSize() {
        return outboundBufferSize;
    }

    /**
     * 设置出站缓冲区大小
     *
     * @param outboundBufferSize 出站缓冲区大小（字节）
     */
    public void setOutboundBufferSize(long outboundBufferSize) {
        this.outboundBufferSize = outboundBufferSize;
    }

    /**
     * 更新最后一次交互时间为当前时间
     */
    public void updateInteractionTime() {
        this.lastInteractionTime = System.currentTimeMillis();
    }

    /**
     * 重置连接信息
     */
    public void reset() {
        this.connected = false;
        this.lastInteractionTime = System.currentTimeMillis();
        this.outboundBufferSize = 0;
    }

    @Override
    public String toString() {
        return "ClusterLink{" +
                "connected=" + connected +
                ", lastInteractionTime=" + lastInteractionTime +
                ", outboundBufferSize=" + outboundBufferSize +
                '}';
    }
}
