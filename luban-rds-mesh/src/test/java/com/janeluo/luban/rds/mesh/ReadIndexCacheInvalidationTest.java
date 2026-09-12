package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.gateway.ReadIndexCache;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * readIndex 缓存失效（fix-mesh-follower-read / Task 11）。
 * <p>确定性、无时间依赖：seed 缓存 → 触发角色/term 变化（走真实的
 * {@link MeshNode#handleAppendEntries} / {@link MeshNode#completePendingReadIndex}）→
 * 断言缓存已不可用。</p>
 * <p>关键用例 {@code candidateToFollower_sameTerm_invalidatesCache}：同 term 的角色切换，
 * 缓存项的 term 与 currentTerm 相同，{@link ReadIndexCache#get} 自身的 term 失配检测
 * <b>不会</b>触发——命中只可能来自显式 {@code invalidate()}，从而把显式失效与
 * "term 变化顺带失效"区分开。</p>
 */
class ReadIndexCacheInvalidationTest {

    private MeshNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
    }

    private MeshNode node(MeshState state) {
        node = new MeshNode(MeshConfig.builder("n1").build(), state, new NoOpBus(),
                new RaftStateMachine());
        return node;
    }

    /** 同 term：CANDIDATE 收到当前 Leader 的 AppendEntries → 转 FOLLOWER（term 不变）。 */
    @Test
    void candidateToFollower_sameTerm_invalidatesCache() {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.role = MeshRole.CANDIDATE;
        MeshNode node = node(state);

        ReadIndexCache cache = node.readIndexCache();
        assertNotNull(cache.get(10_000L, 100L, () -> 7L, 5L), "seed 读点应命中缓存");
        int invalidationsBefore = cache.invalidations();

        // 同 term（5）收到 Leader n2 的 AppendEntries → TO_FOLLOWER 且 term 不变
        node.handleAppendEntries("n2", new AppendEntriesMessage(5L, "n2", 0L, 0L, null, 0L));

        assertEquals(invalidationsBefore + 1, cache.invalidations(),
                "同 term 角色切换必须且只失效一次（多调用点重复失效会让 INFO 计数虚高）");
        assertNull(cache.get(10_000L, 100L, () -> null, 5L),
                "同 term 角色切换后缓存必须已被显式失效（term 未变，不可能靠 term 失配兜底）");
    }

    /** 更高 term 的 readIndex 应答：收敛降级 + 缓存失效（恰好一次）。 */
    @Test
    void higherTermReadIndexResponse_invalidatesCache() {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.role = MeshRole.LEADER;
        state.leaderId = "n1";
        MeshNode node = node(state);

        ReadIndexCache cache = node.readIndexCache();
        assertNotNull(cache.get(10_000L, 100L, () -> 7L, 5L), "seed 读点应命中缓存");
        int invalidationsBefore = cache.invalidations();

        node.completePendingReadIndex(new ReadIndexResponseMessage(9L, 1L, 0L, false, null));

        assertEquals(invalidationsBefore + 1, cache.invalidations(),
                "更高 term 收敛必须恰好失效一次读点缓存");
    }

    /** 更高 term 帧（AppendEntries）→ 降级 + 缓存失效（恰好一次）。 */
    @Test
    void higherTermAppendEntries_invalidatesCache() {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.role = MeshRole.LEADER;
        state.leaderId = "n1";
        MeshNode node = node(state);

        ReadIndexCache cache = node.readIndexCache();
        assertNotNull(cache.get(10_000L, 100L, () -> 7L, 5L), "seed 读点应命中缓存");
        int invalidationsBefore = cache.invalidations();

        node.handleAppendEntries("n2", new AppendEntriesMessage(9L, "n2", 0L, 0L, null, 0L));

        assertEquals(invalidationsBefore + 1, cache.invalidations(),
                "更高 term 帧必须恰好失效一次读点缓存");
        assertNull(cache.get(10_000L, 100L, () -> null, 9L),
                "更高 term 帧后缓存读点必须失效，需重新取点");
    }

    /**
     * Item 2（fix-mesh-follower-read 复核）：apply 进入 fail-stop 必须失效读点缓存——
     * 此前 {@code ReadIndexCache.invalidate()} 从不被任何 halt 路径调用，正确性仅靠 gate
     * 读入口先查 {@code isApplyHalted()} 侥幸成立；本用例把该失效触发钉死。
     */
    @Test
    void applyFailStop_invalidatesCache() {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.role = MeshRole.LEADER;
        // 毒条目：apply 直接抛非预期异常 → P1-5 fail-stop → 注入的 halt hook 失效缓存
        LogApplier poison = new LogApplier(new DefaultCommandHandler(), new DefaultMemoryStore()) {
            @Override
            public Object apply(LogEntry entry) {
                throw new IllegalStateException("injected poison entry");
            }
        };
        node = new MeshNode(MeshConfig.builder("n1").build(), state, new NoOpBus(),
                new RaftStateMachine(), poison, new DefaultMemoryStore());

        ReadIndexCache cache = node.readIndexCache();
        assertNotNull(cache.get(10_000L, 100L, () -> 7L, 5L), "seed 读点应命中缓存");
        int invalidationsBefore = cache.invalidations();

        state.appendEntry(new LogEntry(5L, 1L, new byte[]{1}, 0, null));
        state.commitIndex = 1;
        node.getReplicator().applyCommittedEntries();

        assertTrue(node.isApplyHalted(), "毒条目应触发 apply fail-stop");
        assertEquals(invalidationsBefore + 1, cache.invalidations(),
                "apply fail-stop 必须失效读点缓存（delta spec 四类失效触发之一）");
        assertNull(cache.get(10_000L, 100L, () -> null, 5L),
                "fail-stop 后缓存读点必须已失效");
    }

    /**
     * Item 3（fix-mesh-follower-read 复核）：feature-off 节点从未写入读点缓存，
     * 角色/term 转移触发的 {@code invalidate()} 不得让任何 follower 读计数增长
     * （规格：计数 SHALL 在开关关闭时不增长）。
     */
    @Test
    void featureOff_nodeTransitions_keepAllFollowerReadCountersZero() {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.role = MeshRole.CANDIDATE;
        MeshNode node = node(state);   // 3 参构造：readFromFollower=OFF，缓存从未被写入

        // 同 term 角色切换 + 更高 term 收敛，两次都经过 applyFollowerSideEffects 的失效点
        node.handleAppendEntries("n2", new AppendEntriesMessage(5L, "n2", 0L, 0L, null, 0L));
        node.handleAppendEntries("n2", new AppendEntriesMessage(9L, "n2", 0L, 0L, null, 0L));

        assertEquals(0L, node.readIndexCacheInvalidationCount(),
                "OFF 节点无缓存项可清，cache_invalidated 必须保持 0（不得因角色转移增长）");
        assertEquals(0L, node.readIndexFetchCount());
        assertEquals(0L, node.readIndexCacheHitCount());
        assertEquals(0L, node.readIndexCoalescedCount());
        assertEquals(0L, node.followerReadFallbackCount());
        assertEquals(0L, node.followerReadLocalCount());
        assertEquals(0L, node.followerReadRejectedCount());
        assertEquals(0L, node.followerReadNotReadyRejectedCount());
    }

    /** 无网络的 bus：测试只驱动入站处理方向，屏蔽 send 副作用。 */
    private static class NoOpBus extends MeshBusClient {
        NoOpBus() {
            super("n1", new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            // no-op
        }
    }
}
