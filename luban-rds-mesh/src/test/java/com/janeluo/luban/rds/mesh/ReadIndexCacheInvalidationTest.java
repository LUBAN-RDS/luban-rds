package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.gateway.ReadIndexCache;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesMessage;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexResponseMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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

        // 同 term（5）收到 Leader n2 的 AppendEntries → TO_FOLLOWER 且 term 不变
        node.handleAppendEntries("n2", new AppendEntriesMessage(5L, "n2", 0L, 0L, null, 0L));

        assertNull(cache.get(10_000L, 100L, () -> null, 5L),
                "同 term 角色切换后缓存必须已被显式失效（term 未变，不可能靠 term 失配兜底）");
    }

    /** 更高 term 的 readIndex 应答：收敛降级 + 缓存失效。 */
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

        assertTrue(cache.invalidations() > invalidationsBefore,
                "更高 term 收敛必须立即失效读点缓存");
    }

    /** 更高 term 帧（AppendEntries）→ 降级 + 缓存失效。 */
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

        assertTrue(cache.invalidations() > invalidationsBefore,
                "更高 term 帧必须立即失效读点缓存");
        assertNull(cache.get(10_000L, 100L, () -> null, 9L),
                "更高 term 帧后缓存读点必须失效，需重新取点");
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
