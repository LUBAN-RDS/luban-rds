package com.janeluo.luban.rds.mesh.core;

/**
 * Raft 节点角色枚举（DESIGN.md §2.2）。
 * <ul>
 *   <li>{@link #FOLLOWER} 默认状态；被动接收 AppendEntries；选举超时后转为 CANDIDATE</li>
 *   <li>{@link #CANDIDATE} 选举中；发起 RequestVote；获得多数票转 LEADER</li>
 *   <li>{@link #LEADER} 处理所有客户端写入；向 Followers 复制日志；维持心跳与租约</li>
 * </ul>
 */
public enum MeshRole {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
