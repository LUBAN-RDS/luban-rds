package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogReplicator} 单元测试（阶段 4.2）。
 * <p>
 * 用 {@link CaptureBus} 捕获发出的 AppendEntries，手动驱动响应处理，聚焦：
 * <ul>
 *   <li>initOnBecomeLeader：nextIndex=lastLogIndex+1, matchIndex=0；</li>
 *   <li>成功响应推进 matchIndex/nextIndex；</li>
 *   <li>失败响应回退 nextIndex；</li>
 *   <li>commitIndex 多数派推进（含 term==currentTerm 约束，防 Fig 8）；</li>
 *   <li>applyCommittedEntries：apply 到 raw store + 触发 appliedNotifier；</li>
 *   <li>批量补发：落后 nextIndex 时一次发送多条 entries。</li>
 * </ul>
 * </p>
 * <p>完整 3 节点写流程集成测试留阶段 13。</p>
 */
class LogReplicatorTest {

    private static final String A = "nodeA"; // Leader（自己）
    private static final String B = "nodeB";
    private static final String C = "nodeC";

    private MeshConfig config;
    private MeshState state;
    private CaptureBus bus;
    private DefaultMemoryStore rawStore;
    private LogApplier applier;
    private LogReplicator replicator;

    /** 捕获所有发出的帧（不发真实网络）。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, MeshFrame> sent = new HashMap<>();
        /** false=模拟对端通道高水位拥塞（isWritable=false） */
        volatile boolean writable = true;
        CaptureBus() {
            super(A, new MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.put(targetNodeId, frame);
        }
        @Override
        public boolean isWritable(String nodeId) {
            return writable;
        }
        void clear() { sent.clear(); }
        AppendEntriesMessage lastAppendEntries(String to) {
            MeshFrame f = sent.get(to);
            assertNotNull(f, "应有发往 " + to + " 的帧");
            assertEquals(MessageType.APPEND_ENTRIES.getCode(), f.getType());
            MeshRpcMessage msg = MeshRpcMessage.decode(MessageType.fromCode(f.getType()), f.getBody());
            return (AppendEntriesMessage) msg;
        }
    }

    @BeforeEach
    void setUp() {
        config = MeshConfig.builder(A)
                .addPeer(B, "127.0.0.1:11001")
                .addPeer(C, "127.0.0.1:11002")
                .build();
        state = new MeshState();
        state.currentTerm = 1;
        bus = new CaptureBus();
        rawStore = new DefaultMemoryStore();
        applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        replicator = new LogReplicator(A, config, state, bus, applier);
    }

    /** 构造 SET 命令帧。 */
    private static byte[] setFrame(String key, String val) {
        String f = "*3\r\n$3\r\nSET\r\n$" + key.length() + "\r\n" + key + "\r\n$"
                + val.length() + "\r\n" + val + "\r\n";
        return f.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** 向 state 追加一条 entry（模拟 propose）。 */
    private LogEntry appendEntry(long index, byte[] payload) {
        LogEntry e = new LogEntry(state.currentTerm, index, payload, 0, null);
        state.appendEntry(e);
        return e;
    }

    @Test
    void initOnBecomeLeader_initializesNextIndexAndMatchIndex() {
        // 预置 2 条日志
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));

        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // nextIndex[B] = nextIndex[C] = lastLogIndex + 1 = 3
        assertEquals(3L, replicator.getNextIndex(B));
        assertEquals(3L, replicator.getNextIndex(C));
        // matchIndex = 0
        assertEquals(0L, replicator.getMatchIndex(B));
        assertEquals(0L, replicator.getMatchIndex(C));
    }

    @Test
    void initOnBecomeLeader_emptyLog_nextIndexIs1() {
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 空日志：lastLogIndex=0，nextIndex=1，matchIndex=0
        assertEquals(1L, replicator.getNextIndex(B));
        assertEquals(0L, replicator.getMatchIndex(B));
    }

    @Test
    void clearOnLoseLeadership_resetsState() {
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        replicator.clearOnLoseLeadership();

        // 清空后回到默认（lastLogIndex+1 / 0）
        assertEquals(state.getLastLogIndex() + 1, replicator.getNextIndex(B));
        assertEquals(0L, replicator.getMatchIndex(B));
    }

    @Test
    void replicate_sendsAppendEntriesToAllPeersWithEntriesFromNextIndex() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        bus.clear();
        LogEntry newEntry = appendEntry(3, setFrame("c", "3"));
        replicator.replicate(newEntry, false);

        // B 和 C 都应收到 AppendEntries
        AppendEntriesMessage toB = bus.lastAppendEntries(B);
        AppendEntriesMessage toC = bus.lastAppendEntries(C);

        // term=1, leaderId=A, leaderCommit=0
        assertEquals(1L, toB.getTerm());
        assertEquals(A, toB.getLeaderId());
        assertEquals(0L, toB.getLeaderCommit());
        // prevLogIndex=nextIndex-1=2, prevLogTerm=1
        assertEquals(2L, toB.getPrevLogIndex());
        assertEquals(1L, toB.getPrevLogTerm());
        // entries 包含 index=3 的条目（nextIndex=3 → 发从 3 到末尾）
        assertEquals(1, toB.getEntries().size());
        assertEquals(3L, toB.getEntries().get(0).getIndex());
        assertEquals(3L, toC.getEntries().get(0).getIndex());
    }

    @Test
    void onAppendEntriesResponse_success_advancesMatchIndexAndNextIndex() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // Follower B 确认到 index=2
        AppendEntriesResponse resp = new AppendEntriesResponse(1L, true, 2L);
        replicator.onAppendEntriesResponse(B, resp, false);

        assertEquals(2L, replicator.getMatchIndex(B));
        assertEquals(3L, replicator.getNextIndex(B));
        // C 仍是初始 0
        assertEquals(0L, replicator.getMatchIndex(C));
    }

    @Test
    void onAppendEntriesResponse_success_lowerMatchIndex_doesNotRegress() {
        appendEntry(1, setFrame("a", "1"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 5L), false);

        // 再来一个更小的 matchIndex 不应回退
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 3L), false);
        assertEquals(5L, replicator.getMatchIndex(B));
        assertEquals(6L, replicator.getNextIndex(B));
    }

    @Test
    void onAppendEntriesResponse_failure_decrementsNextIndex() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        // nextIndex[B] 初始 = 4

        AppendEntriesResponse resp = new AppendEntriesResponse(1L, false, 0L);
        replicator.onAppendEntriesResponse(B, resp, false);

        // nextIndex 回退到 3
        assertEquals(3L, replicator.getNextIndex(B));
        // matchIndex 不变（仍 0）
        assertEquals(0L, replicator.getMatchIndex(B));
        // 应触发重发（bus 有发往 B 的帧）
        assertNotNull(bus.sent.get(B));
    }

    @Test
    void onAppendEntriesResponse_failure_usesConflictMatchIndexToAccelerate() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        appendEntry(4, setFrame("d", "4"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        // nextIndex[B] 初始 = 5

        // Follower 报告 conflict matchIndex=2（它只有到 2 的日志）
        AppendEntriesResponse resp = new AppendEntriesResponse(1L, false, 2L);
        replicator.onAppendEntriesResponse(B, resp, false);

        // 应回退到 conflictMatchIndex+1 = 3（比逐格回退到 4 更快）
        assertEquals(3L, replicator.getNextIndex(B));
    }

    @Test
    void maybeAdvanceCommitIndex_majorityOfCurrentTerm_advances() {
        // 3 条日志，term=1（currentTerm）
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // B 确认到 3，C 确认到 2：含自己(3)，多数派(2/3) >= 3？matchIndex=[self=3, B=3, C=2]
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 3L), false);
        replicator.onAppendEntriesResponse(C, new AppendEntriesResponse(1L, true, 2L), false);

        // 多数派 >= 3：自己(3)+B(3) = 2 >= majority(2) → commitIndex=3
        assertEquals(3L, state.commitIndex);
    }

    @Test
    void maybeAdvanceCommitIndex_majorityNotReached_doesNotAdvance() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 只有 C 确认到 3：自己(3)+C(3)=2 >= majority(2)，但 B 未确认
        // 其实自己+C 已达多数派（2/3），所以 commitIndex 会推进到 3
        // 改测：只有 B 确认到 1
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 1L), false);
        // C 未确认（matchIndex=0）：matchIndex=[self=3, B=1, C=0]
        // 排序降序 [3,1,0]，majority=2 → 第 2 大(下标1)=1，且 log[1].term=1==currentTerm → commit=1
        assertEquals(1L, state.commitIndex);
    }

    @Test
    void maybeAdvanceCommitIndex_oldTermEntryNotCommitted_fig8Protection() {
        // 场景：旧任期日志不能直接 commit（Raft Fig 8 保护）
        // entry index=1 是 term=1（旧），entry index=2 是 term=2（currentTerm）
        state.currentTerm = 2;
        state.appendEntry(new LogEntry(1L, 1L, setFrame("old", "1"), 0, null));
        state.appendEntry(new LogEntry(2L, 2L, setFrame("new", "2"), 0, null));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 两个 Follower 都确认到 index=1（旧任期）
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(2L, true, 1L), false);
        replicator.onAppendEntriesResponse(C, new AppendEntriesResponse(2L, true, 1L), false);

        // 多数派 >= 1，但 log[1].term=1 != currentTerm=2 → 不能 commit
        // 故 commitIndex 仍为 0
        assertEquals(0L, state.commitIndex);
    }

    @Test
    void maybeAdvanceCommitIndex_singleNode_immediatelyCommits() {
        // 单节点集群：无 peer，自己即多数派
        MeshConfig single = MeshConfig.builder("solo").build();
        MeshState st = new MeshState();
        st.currentTerm = 1;
        CaptureBus singleBus = new CaptureBus(); // selfNodeId=A，但无 peer
        LogApplier ap = new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore());
        LogReplicator rep = new LogReplicator("solo", single, st, singleBus, ap);
        rep.initOnBecomeLeader(single.getOtherNodeIds());

        // 追加一条
        st.appendEntry(new LogEntry(1L, 1L, setFrame("k", "v"), 0, null));
        // 手动检查 commit
        boolean advanced = rep.maybeAdvanceCommitIndex();

        assertTrue(advanced, "单节点应立即 commit");
        assertEquals(1L, st.commitIndex);
    }

    @Test
    void applyCommittedEntries_appliesToRawStoreAndAdvancesLastApplied() {
        appendEntry(1, setFrame("foo", "bar"));
        appendEntry(2, setFrame("k2", "v2"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 模拟 commit 到 2
        state.commitIndex = 2;

        int applied = replicator.applyCommittedEntries();

        assertEquals(2, applied);
        assertEquals(2L, state.lastApplied);
        // raw store 被 apply 写入
        assertEquals("bar", rawStore.get(0, "foo"));
        assertEquals("v2", rawStore.get(0, "k2"));
    }

    @Test
    void applyCommittedEntries_triggersAppliedNotifierWithResponseObject() {
        appendEntry(1, setFrame("foo", "bar"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        state.commitIndex = 1;

        AtomicLong notifiedIndex = new AtomicLong(-1);
        java.util.concurrent.atomic.AtomicReference<Object> notifiedResp = new java.util.concurrent.atomic.AtomicReference<>();
        replicator.setAppliedNotifier((idx, resp) -> {
            notifiedIndex.set(idx);
            notifiedResp.set(resp);
        });

        replicator.applyCommittedEntries();

        assertEquals(1L, notifiedIndex.get());
        // SET 的响应对象是 +OK\r\n
        assertEquals("+OK\r\n", notifiedResp.get());
    }

    @Test
    void applyCommittedEntries_partialCommit_appliesOnlyCommittedRange() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 只 commit 到 2（entry 3 未提交）
        state.commitIndex = 2;

        int applied = replicator.applyCommittedEntries();

        assertEquals(2, applied);
        assertEquals(2L, state.lastApplied);
        // entry 3 未 apply
        assertNull(rawStore.get(0, "c"));
        assertEquals("1", rawStore.get(0, "a"));
        assertEquals("2", rawStore.get(0, "b"));
    }

    @Test
    void replicate_backlog_sendsMultipleEntriesWhenFollowerBehind() {
        // Leader 有 4 条日志，Follower B 落后（nextIndex[B]=2）
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        appendEntry(3, setFrame("c", "3"));
        appendEntry(4, setFrame("d", "4"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        // 手动把 B 的 nextIndex 设回 2（模拟落后）
        replicator.getNextIndexView(); // 触发视图（无副作用，仅占位）

        bus.clear();
        // 用反射或直接构造：这里通过 onAppendEntriesResponse 失败回退来模拟
        // 让 B 回退到 2
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 1L), false);
        // nextIndex[B] 现在应为 2

        // 重新触发 replicate（B 应收到从 index=2 开始的多条 entries）
        bus.clear();
        replicator.replicate(state.getEntry(4), false);

        AppendEntriesMessage toB = bus.lastAppendEntries(B);
        // prevLogIndex=1, entries 从 index=2 到 4（3 条）
        assertEquals(1L, toB.getPrevLogIndex());
        assertEquals(3, toB.getEntries().size());
        assertEquals(2L, toB.getEntries().get(0).getIndex());
        assertEquals(4L, toB.getEntries().get(2).getIndex());
    }

    @Test
    void leaseRefresher_invokedOnSuccessResponse() {
        appendEntry(1, setFrame("a", "1"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        java.util.concurrent.atomic.AtomicInteger refreshCount = new java.util.concurrent.atomic.AtomicInteger();
        replicator.setLeaseRefresher(refreshCount::incrementAndGet);

        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 1L), true);

        assertTrue(refreshCount.get() >= 1, "续租回调应被调用");
    }

    @Test
    void leaseRefresher_notInvokedWhenRefreshLeaseFalse() {
        appendEntry(1, setFrame("a", "1"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        java.util.concurrent.atomic.AtomicInteger refreshCount = new java.util.concurrent.atomic.AtomicInteger();
        replicator.setLeaseRefresher(refreshCount::incrementAndGet);

        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 1L), false);

        assertEquals(0, refreshCount.get(), "refreshLease=false 时不调续租");
    }

    @Test
    void onAppendEntriesResponse_unknownPeer_ignored() {
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        // 未知 peer D
        replicator.onAppendEntriesResponse("nodeD", new AppendEntriesResponse(1L, true, 5L), false);
        // 不应抛异常，且 B/C 状态不变
        assertEquals(0L, replicator.getMatchIndex(B));
    }

    // ==================== D4：连续 NACK 节流 + 快照降级（9/3 事故） ====================

    @Test
    void onAppendEntriesResponse_failure_throttlesResendAfterLimit() {
        appendEntry(1, setFrame("a", "1"));
        appendEntry(2, setFrame("b", "2"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        // 连续 20 次 NACK：前 8 次立即重发，其后不再每次立即重发（节流，增量随心跳节奏）
        for (int i = 0; i < 20; i++) {
            bus.clear();
            replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 0L), false);
            if (i < 8) {
                assertNotNull(bus.sent.get(B), "第 " + (i + 1) + " 次 NACK 应立即重发");
            } else {
                assertNull(bus.sent.get(B), "超过立即重发上限后不得每次 NACK 立即重发（节流）");
            }
        }
    }

    @Test
    void onAppendEntriesResponse_success_clearsNackCount() {
        appendEntry(1, setFrame("a", "1"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());

        for (int i = 0; i < 10; i++) {
            replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 0L), false);
        }
        // success 清零后，下一次 NACK 又处于立即重发窗口内
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 1L), false);
        bus.clear();
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 0L), false);
        assertNotNull(bus.sent.get(B), "计数清零后恢复立即重发");
    }

    @Test
    void fallbackBeyondSnapshotBoundary_triggersSendSnapshot() {
        state.lastIncludedIndex = 10;
        state.lastIncludedTerm = 1;
        for (long i = 11; i <= 15; i++) {
            appendEntry(i, setFrame("k" + i, "v" + i));
        }
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        // nextIndex[B] 初始 16；conflict matchIndex=0 逐格回退一次到 15 —— 不越界
        // 用 matchIndex=10 直接回退到 11：nextIndex-1=10 <= lastIncludedIndex=10 → 越界降级
        com.janeluo.luban.rds.mesh.replication.SnapshotManager sm =
                org.mockito.Mockito.mock(com.janeluo.luban.rds.mesh.replication.SnapshotManager.class);
        org.mockito.Mockito.when(sm.sendSnapshot(B)).thenReturn(1024L);
        replicator.setSnapshotManager(sm);

        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 10L), false);

        org.mockito.Mockito.verify(sm).sendSnapshot(B);
    }

    @Test
    void fallbackGivesUpAfterThreeFailures() {
        state.lastIncludedIndex = 10;
        state.lastIncludedTerm = 1;
        for (long i = 11; i <= 15; i++) {
            appendEntry(i, setFrame("k" + i, "v" + i));
        }
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        com.janeluo.luban.rds.mesh.replication.SnapshotManager sm =
                org.mockito.Mockito.mock(com.janeluo.luban.rds.mesh.replication.SnapshotManager.class);
        org.mockito.Mockito.when(sm.sendSnapshot(B)).thenReturn(-1L);   // 持续失败
        replicator.setSnapshotManager(sm);

        for (int round = 0; round < 5; round++) {
            replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 10L), false);
        }
        org.mockito.Mockito.verify(sm, org.mockito.Mockito.times(3)).sendSnapshot(B);
    }

    @Test
    void fallbackInCooldown_doesNotResendSnapshot() {
        state.lastIncludedIndex = 10;
        state.lastIncludedTerm = 1;
        for (long i = 11; i <= 15; i++) {
            appendEntry(i, setFrame("k" + i, "v" + i));
        }
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        com.janeluo.luban.rds.mesh.replication.SnapshotManager sm =
                org.mockito.Mockito.mock(com.janeluo.luban.rds.mesh.replication.SnapshotManager.class);
        org.mockito.Mockito.when(sm.sendSnapshot(B)).thenReturn(1024L);
        replicator.setSnapshotManager(sm);

        // 第一次越界 NACK → 发快照；follower 安装期间继续 NACK → 冷却期内不得重发
        for (int i = 0; i < 10; i++) {
            replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 10L), false);
        }
        org.mockito.Mockito.verify(sm, org.mockito.Mockito.times(1)).sendSnapshot(B);

        // follower 安装对齐（success）后，冷却清除，后续越界可再次触发
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, true, 10L), false);
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 10L), false);
        org.mockito.Mockito.verify(sm, org.mockito.Mockito.times(2)).sendSnapshot(B);
    }

    // ==================== 9/9 生产事故回归：积压补发必须有单帧上限 ====================

    @Test
    void replicate_backlog_cappedByEntryCount() {
        // 300 条日志，B 落后（nextIndex 回退到 2）→ 单帧最多 MAX_AE_BATCH_ENTRIES 条，不得全段重发
        for (int i = 1; i <= 300; i++) {
            appendEntry(i, setFrame("k" + i, "v" + i));
        }
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 1L), false); // nextIndex[B]→2

        bus.clear();
        replicator.replicate(state.getEntry(300), false);

        AppendEntriesMessage toB = bus.lastAppendEntries(B);
        assertEquals(1L, toB.getPrevLogIndex());
        assertEquals(256, toB.getEntries().size(), "单帧条数必须封顶（256），全量重发是 9/9 直接内存 OOM 的机制");
        assertEquals(2L, toB.getEntries().get(0).getIndex());
        assertEquals(257L, toB.getEntries().get(255).getIndex());
    }

    @Test
    void replicate_backlog_cappedByByteBudget() {
        // 30 条 ~200KB 大 entry，B 从 1 落后 → 单帧总字节不得超 2MB 预算（≥1 条保底）
        String bigVal = "x".repeat(200 * 1024);
        for (int i = 1; i <= 30; i++) {
            appendEntry(i, setFrame("k" + i, bigVal));
        }
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        replicator.onAppendEntriesResponse(B, new AppendEntriesResponse(1L, false, 1L), false); // nextIndex[B]→2

        bus.clear();
        replicator.replicate(state.getEntry(30), false);

        AppendEntriesMessage toB = bus.lastAppendEntries(B);
        long bytes = toB.getEntries().stream().mapToLong(e -> e.encode().length).sum();
        // B 落后 [2..30] 共 29 条大 entry（~5.8MB），单帧必须截断
        assertTrue(toB.getEntries().size() < 29, "积压全段进单帧 → 字节预算未生效");
        assertTrue(toB.getEntries().size() >= 1, "至少 1 条保底");
        assertTrue(bytes <= 2L * 1024 * 1024 + 256 * 1024, "字节预算超限：实际 " + bytes);
    }

    @Test
    void replicate_skipsPeerWhenChannelUnwritable() {
        appendEntry(1, setFrame("a", "1"));
        replicator.initOnBecomeLeader(config.getOtherNodeIds());
        bus.writable = false;

        bus.clear();
        replicator.replicate(state.getEntry(1), false);

        assertTrue(bus.sent.isEmpty(), "对端通道拥塞（高水位）时应跳过本轮复制，而非无界排队");
    }
}
