package com.janeluo.luban.rds.cluster.gossip;

/**
 * 手动故障转移状态机状态（P1-12）。
 * <p>
 * 独立于自动选举的 {@link FailoverState}，用于 CLUSTER FAILOVER 普通模式的候选 slave 侧：
 * <ol>
 *   <li>{@link #NONE}：未进行手动 failover</li>
 *   <li>{@link #MF_REQUESTED}：已向 master 发送 MANUAL_FAILOVER_START，等待 master 回传暂停 offset</li>
 *   <li>{@link #MF_WAITING_OFFSET}：已收到 master 暂停 offset，等待本 slave 复制偏移量追平</li>
 *   <li>{@link #MF_READY}：已追平，执行 performManualFailover（瞬态，立即回 NONE）</li>
 * </ol>
 * </p>
 */
public enum ManualFailoverState {
    /** 未进行手动 failover */
    NONE,
    /** 已发 MFStart，等待 master 回传暂停 offset */
    MF_REQUESTED,
    /** 已收到 offset，等待本 slave 追平 */
    MF_WAITING_OFFSET,
    /** 已追平，提升（瞬态） */
    MF_READY
}
