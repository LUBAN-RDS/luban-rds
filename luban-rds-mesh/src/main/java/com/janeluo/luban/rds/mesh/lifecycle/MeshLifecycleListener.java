package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.MeshClusterCommands;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mesh 角色/Leader 变更回调（DESIGN.md §6 / IMPLEMENTATION_PLAN 阶段 12）。
 * <p>
 * 实现 {@link MeshNode.RoleChangeListener}，在 becomeLeader / becomeFollower 时：
 * <ul>
 *   <li>记录角色变更日志（便于观测选举/故障转移）；</li>
 *   <li>刷新 {@link MeshClusterCommands} 的 leader 感知缓存（供 CLUSTER SLOTS/NODES/INFO
 *       实时反映新 Leader）。</li>
 * </ul>
 * </p>
 *
 * <h3>线程模型</h3>
 * <p>
 * 回调在 {@code MeshNode.raftExecutor} 单线程上执行（参见
 * {@link MeshNode#setRoleChangeListener}），故本类无需自身加锁。leaderId 与 role 通过
 * supplier 在请求时动态读取，故即便回调被跳过（异常容错），下次 CLUSTER 查询仍能取到最新值。
 * </p>
 *
 * <h3>阶段说明</h3>
 * <p>阶段 12 简单实现（日志 + 可能刷新缓存）。{@link MeshClusterCommands} 本就用
 * {@link java.util.function.Supplier} 动态获取 Leader，故「刷新」更多是观测/日志层面；
 * 真正的 leader 缓存刷新由 supplier 每次查询时完成。本类主要为后续阶段预留扩展点
 * （如 becomeLeader 时触发一次立即心跳、becomeFollower 时清连接级状态等）。</p>
 *
 * @author janeluo
 * @since 阶段 12
 */
public class MeshLifecycleListener implements MeshNode.RoleChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(MeshLifecycleListener.class);

    /** 本节点 nodeId（日志用）。 */
    private final String selfNodeId;
    /**
     * CLUSTER 命令响应生成器（可选）。非 {@code null} 时用于角色变更后的观测/日志；
     * 其 leader 供应商在每次 CLUSTER 查询时动态取值，无需此处主动刷新。
     */
    private final MeshClusterCommands clusterCommands;

    public MeshLifecycleListener(String selfNodeId, MeshClusterCommands clusterCommands) {
        this.selfNodeId = selfNodeId;
        this.clusterCommands = clusterCommands;
    }

    @Override
    public void onRoleChanged(MeshRole role, String leaderId) {
        // 观测：记录角色变更。这对故障转移调试很有价值。
        if (role == MeshRole.LEADER) {
            logger.info("MeshLifecycleListener: 本节点 {} 成为 LEADER", abbrev(selfNodeId));
        } else if (role == MeshRole.CANDIDATE) {
            logger.info("MeshLifecycleListener: 本节点 {} 转为 CANDIDATE，等待选举结果",
                    abbrev(selfNodeId));
        } else {
            // FOLLOWER
            if (leaderId != null) {
                logger.info("MeshLifecycleListener: 本节点 {} 转为 FOLLOWER，当前 Leader={}",
                        abbrev(selfNodeId), abbrev(leaderId));
            } else {
                logger.info("MeshLifecycleListener: 本节点 {} 转为 FOLLOWER，暂无 Leader",
                        abbrev(selfNodeId));
            }
        }

        // 阶段 12：MeshClusterCommands 通过 Supplier 动态感知 Leader，无需主动刷新。
        // 此处保留 clusterCommands 引用，为后续阶段（如缓存预热、连接级状态清理）预留扩展点。
        if (clusterCommands != null && role == MeshRole.LEADER) {
            logger.debug("MeshLifecycleListener: 本节点成为 Leader，CLUSTER 命令将反映新 Leader");
        }
    }

    private static String abbrev(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
