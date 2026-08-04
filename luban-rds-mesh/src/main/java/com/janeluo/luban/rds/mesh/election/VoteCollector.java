package com.janeluo.luban.rds.mesh.election;

import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 投票收集器（DESIGN.md §5.2）：并行发 RequestVote 给其他节点，统计多数派。
 * <p>
 * 一次选举（含 PreVote 探测与正式选举）各用一个独立的 {@code VoteCollector} 实例，选举结束即弃。
 * 支持 PreVote 模式：多数派回调不同（PreVote 多数派 → 触发正式选举；正式多数派 → becomeLeader），
 * 由构造时传入的 {@link VoteOutcome} 区分，避免耦合 MeshNode。
 * </p>
 *
 * <h3>多数派判定</h3>
 * <p>
 * {@code majority = totalNodes / 2 + 1}（3 节点 → 2）。投票总数含自己一票
 * （Candidate 在 becomeCandidate 时已投自己，由 {@code selfVotes} 初始值传入）。
 * </p>
 *
 * <h3>并发</h3>
 * <p>
 * {@link AtomicInteger} 计票 + {@link ConcurrentHashMap} 跟踪已计票节点防重复；
 * {@link AtomicBoolean} 保证多数派回调只触发一次（防"超额触发"）。
 * </p>
 */
public class VoteCollector {

    private static final Logger logger = LoggerFactory.getLogger(VoteCollector.class);

    private final String selfNodeId;
    private final int totalNodes;
    private final int majority;
    private final boolean preVote;
    private final VoteOutcome outcome;

    private final AtomicInteger granted;
    private final Set<String> countedNodes;
    private final AtomicBoolean completed;

    /**
     * 投票结果回调。PreVote 多数派与正式多数派由调用方注入不同语义的回调，
     * 使本类不直接依赖 MeshNode 的角色转换逻辑。
     */
    @FunctionalInterface
    public interface VoteOutcome {
        /**
         * @param won       是否赢得选举（达多数派）
         * @param term      本轮选举的 term（PreVote 时为当前 term；正式时为自增后的 term）
         * @param granted   同意票数（含自己）
         * @param total     总节点数
         */
        void onResult(boolean won, long term, int granted, int total);
    }

    /**
     * @param selfNodeId  本节点 nodeId
     * @param totalNodes  集群总节点数（含自己）
     * @param preVote     是否为 PreVote 探测
     * @param outcome     结果回调（达多数派 won=true；未达 won=false）
     */
    public VoteCollector(String selfNodeId, int totalNodes, boolean preVote, VoteOutcome outcome) {
        if (selfNodeId == null) {
            throw new IllegalArgumentException("selfNodeId 不能为 null");
        }
        if (totalNodes < 1) {
            throw new IllegalArgumentException("totalNodes 必须 >= 1: " + totalNodes);
        }
        if (outcome == null) {
            throw new IllegalArgumentException("outcome 不能为 null");
        }
        this.selfNodeId = selfNodeId;
        this.totalNodes = totalNodes;
        this.majority = totalNodes / 2 + 1;
        this.preVote = preVote;
        this.outcome = outcome;
        // 自己一票：Candidate 已投自己（PreVote 也假设自己会同意自己的探测，否则无意义）
        this.granted = new AtomicInteger(1);
        this.countedNodes = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.countedNodes.add(selfNodeId);
        this.completed = new AtomicBoolean(false);
    }

    /**
     * 启动：异步向 peers 广播 RequestVote（过滤自身）。
     *
     * @param peerNodeIds 目标节点 id 集合（可含自身，会被过滤）
     * @param msg         RequestVote 消息（term/lastLogIndex/lastLogTerm/preVote 已由调用方填充）
     * @param bus         总线客户端
     * @param currentTerm 本轮 term（用于回调）
     */
    public void start(Collection<String> peerNodeIds, RequestVoteMessage msg, MeshBusClient bus, long currentTerm) {
        if (peerNodeIds == null || peerNodeIds.isEmpty()) {
            // 单节点集群（totalNodes=1）：自己即多数派，直接判赢
            evaluateOnce(currentTerm);
            return;
        }
        byte[] body = msg.encode();
        for (String peer : peerNodeIds) {
            if (peer.equals(selfNodeId)) {
                continue;
            }
            MeshFrame frame = new MeshFrame(selfNodeId, MessageType.REQUEST_VOTE.getCode(), body);
            try {
                bus.send(peer, frame);
            } catch (Exception e) {
                logger.warn("发送 RequestVote 到 {} 失败 (preVote={}, term={})",
                        peer, preVote, msg.getTerm(), e);
            }
        }
        // 单节点场景兜底：广播后立即评估一次（自己已是多数派）
        evaluateOnce(currentTerm);
    }

    /**
     * 收到投票响应：计票并评估多数派。重复响应与拒绝票都被安全忽略。
     *
     * @param fromNodeId 响应来源 nodeId
     * @param resp       响应体
     */
    public void onVoteReceived(String fromNodeId, RequestVoteResponse resp, long currentTerm) {
        if (completed.get()) {
            return;
        }
        // 节点去重：同一节点多次响应只计一次
        if (!countedNodes.add(fromNodeId)) {
            return;
        }
        if (resp.isVoteGranted()) {
            granted.incrementAndGet();
        }
        evaluateOnce(currentTerm);
    }

    /** 多数派判定（线程安全，回调仅触发一次）。 */
    private void evaluateOnce(long term) {
        if (completed.get()) {
            return;
        }
        int g = granted.get();
        if (g >= majority) {
            if (completed.compareAndSet(false, true)) {
                try {
                    outcome.onResult(true, term, g, totalNodes);
                } catch (Exception e) {
                    logger.error("VoteOutcome 回调异常 (won, preVote={})", preVote, e);
                }
            }
        }
    }

    /**
     * 主动结束本次收集（用于选举被中断：收到更高任期 AppendEntries 等）。
     * 触发一次 won=false 的结果回调（仅一次）。
     */
    public void cancel(long term) {
        if (completed.compareAndSet(false, true)) {
            try {
                outcome.onResult(false, term, granted.get(), totalNodes);
            } catch (Exception e) {
                logger.error("VoteOutcome 回调异常 (cancel, preVote={})", preVote, e);
            }
        }
    }

    public boolean isPreVote() {
        return preVote;
    }

    public int getMajority() {
        return majority;
    }

    public int getGranted() {
        return granted.get();
    }

    public boolean isCompleted() {
        return completed.get();
    }
}
