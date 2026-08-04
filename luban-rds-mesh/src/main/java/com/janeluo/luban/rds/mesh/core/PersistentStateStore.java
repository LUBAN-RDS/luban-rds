package com.janeluo.luban.rds.mesh.core;

/**
 * Mesh 持久化状态存储抽象（DESIGN §7.4）。
 *
 * <p>抽象出 {@link MeshState} 持久化字段（{@code currentTerm / votedFor / logTail /
 * lastIncludedIndex / lastIncludedTerm}）的读写，便于：</p>
 * <ul>
 *   <li>生产用 {@code FileBasedPersistentStateStore}（raft-nodes.conf 原子写）。</li>
 *   <li>测试用内存实现（无磁盘 IO，加速单测）。</li>
 *   <li>未来扩展（如 KV 存后端）。</li>
 * </ul>
 *
 * <h3>持久化时机（DESIGN §5.1，fsync 在确认路径上）</h3>
 * <ul>
 *   <li>{@code currentTerm} 变化（选举 / 收到更高任期）</li>
 *   <li>{@code votedFor} 设置（投票）</li>
 *   <li>log append：每条 fsync（follower 落盘才 ACK，leader 落盘才 complete future）</li>
 *   <li>{@code lastIncludedIndex/Term} 变化（快照后）</li>
 * </ul>
 *
 * <h3>异常语义</h3>
 * <ul>
 *   <li>{@link #persist}：落盘失败抛 {@link RuntimeException}（含 {@link java.io.IOException}），
 *       调用方据此 fail propose / 返回 success=false。</li>
 *   <li>{@link #load}：文件不存在返回 {@code null}（首次启动）；
 *       文件<b>损坏抛异常</b>（不静默重置 term，DESIGN §5.5）。</li>
 * </ul>
 */
public interface PersistentStateStore {

    /**
     * 持久化 {@link MeshState}（原子写 raft-nodes.conf）。
     *
     * @param state  待持久化状态
     * @param nodeId 本节点 nodeId
     */
    void persist(MeshState state, String nodeId);

    /**
     * 启动加载（含 lastIncludedIndex/lastIncludedTerm）。
     *
     * @param nodeId 本节点 nodeId
     * @return 恢复的 state；文件不存在返回 {@code null}（首次启动）
     */
    MeshState load(String nodeId);
}
