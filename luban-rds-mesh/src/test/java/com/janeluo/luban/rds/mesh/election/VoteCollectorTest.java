package com.janeluo.luban.rds.mesh.election;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteMessage;
import com.janeluo.luban.rds.mesh.rpc.RequestVoteResponse;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoteCollector} 单元测试：多数派判定、PreVote 模式、去重、cancel。
 * <p>用假 MeshBusClient（捕获发送）模拟网络。</p>
 */
class VoteCollectorTest {

    private static final String SELF = "nodeA";

    /** 轻量假 bus：记录每个 peer 收到的 frame。 */
    private static class FakeBus extends MeshBusClient {
        final Map<String, MeshFrame> sent = Collections.synchronizedMap(new HashMap<>());

        FakeBus() {
            super(SELF, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.put(targetNodeId, frame);
        }
    }

    private RequestVoteMessage msg(long term, boolean preVote) {
        return new RequestVoteMessage(term, SELF, 0L, 0L, preVote);
    }

    @Test
    void majority_isHalfPlusOne() {
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> { });
        assertEquals(2, vc.getMajority());

        VoteCollector vc5 = new VoteCollector(SELF, 5, false, (w, t, g, tot) -> { });
        assertEquals(3, vc5.getMajority());
    }

    @Test
    void selfVoteCounted_startDoesNotImmediatelyWinFor3Nodes() {
        // 3 节点：自己 1 票，未达多数派 2
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        FakeBus bus = new FakeBus();

        vc.start(Arrays.asList("nodeB", "nodeC"), msg(1L, false), bus, 1L);

        assertEquals(0, wins.get(), "单票不应立即赢");
        assertEquals(2, bus.sent.size(), "应向 B、C 各发一票");
        assertFalse(vc.isCompleted());
    }

    @Test
    void winsOnMajority_singleExtraGrant() throws Exception {
        AtomicInteger wins = new AtomicInteger();
        AtomicReference<Long> winTerm = new AtomicReference<>();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> {
            if (w) {
                wins.incrementAndGet();
                winTerm.set(t);
            }
        });
        vc.start(Arrays.asList("nodeB", "nodeC"), msg(5L, false), new FakeBus(), 5L);

        // B 同意 → 自己 + B = 2 = 多数派
        vc.onVoteReceived("nodeB", new RequestVoteResponse(5L, true), 5L);

        assertEquals(1, wins.get(), "达多数派应触发回调一次");
        assertEquals(5L, winTerm.get());
        assertTrue(vc.isCompleted());
    }

    @Test
    void callbackFiresOnlyOnce_onMultipleGrants() {
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        vc.start(Arrays.asList("nodeB", "nodeC"), msg(5L, false), new FakeBus(), 5L);

        vc.onVoteReceived("nodeB", new RequestVoteResponse(5L, true), 5L);
        vc.onVoteReceived("nodeC", new RequestVoteResponse(5L, true), 5L); // 已完成

        assertEquals(1, wins.get(), "回调只应触发一次");
    }

    @Test
    void duplicateVoteFromSameNode_countedOnce() {
        // 用 5 节点集群：majority=3，自己=1，B 重复响应只计 1 次 → granted=2 < 3，不赢
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 5, false, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        vc.start(Arrays.asList("nodeB", "nodeC", "nodeD", "nodeE"), msg(5L, false), new FakeBus(), 5L);

        // B 重复响应 100 次，应只计一次
        for (int i = 0; i < 100; i++) {
            vc.onVoteReceived("nodeB", new RequestVoteResponse(5L, true), 5L);
        }
        assertEquals(0, wins.get(), "同节点重复只计一次，自己+B=2 < majority(3)，不应赢");
        assertEquals(2, vc.getGranted(), "granted 应只含自己+B=2");
        assertFalse(vc.isCompleted());
    }

    @Test
    void deniedVote_doesNotCount() {
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        vc.start(Arrays.asList("nodeB", "nodeC"), msg(5L, false), new FakeBus(), 5L);

        vc.onVoteReceived("nodeB", new RequestVoteResponse(5L, false), 5L);
        vc.onVoteReceived("nodeC", new RequestVoteResponse(5L, false), 5L);

        assertEquals(0, wins.get(), "全拒不应赢");
        assertEquals(1, vc.getGranted(), "只有自己一票");
    }

    @Test
    void preVoteMode_majorityTriggersCallbackButDoesntChangeStructure() {
        // PreVote 模式：行为与正式相同，仅 outcome 由调用方区分（这里只是预投）
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 3, true, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        assertTrue(vc.isPreVote());
        vc.start(Arrays.asList("nodeB", "nodeC"), msg(5L, true), new FakeBus(), 5L);
        vc.onVoteReceived("nodeB", new RequestVoteResponse(5L, true), 5L);

        assertEquals(1, wins.get(), "PreVote 多数派同样触发回调");
    }

    @Test
    void cancel_triggersLostResultOnce() {
        AtomicInteger results = new AtomicInteger();
        AtomicInteger lost = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> {
            results.incrementAndGet();
            if (!w) lost.incrementAndGet();
        });
        vc.start(Arrays.asList("nodeB", "nodeC"), msg(5L, false), new FakeBus(), 5L);
        assertFalse(vc.isCompleted());

        vc.cancel(5L);

        assertTrue(vc.isCompleted());
        assertEquals(1, results.get());
        assertEquals(1, lost.get());

        // 再次 cancel 不应触发
        vc.cancel(5L);
        assertEquals(1, results.get());
    }

    @Test
    void singleNodeCluster_winsImmediately() {
        // totalNodes=1，自己即多数派
        AtomicInteger wins = new AtomicInteger();
        VoteCollector vc = new VoteCollector(SELF, 1, false, (w, t, g, tot) -> {
            if (w) wins.incrementAndGet();
        });
        vc.start(Collections.emptyList(), msg(5L, false), new FakeBus(), 5L);
        assertEquals(1, wins.get());
        assertTrue(vc.isCompleted());
    }

    @Test
    void start_filtersSelfNodeId() {
        FakeBus bus = new FakeBus();
        VoteCollector vc = new VoteCollector(SELF, 3, false, (w, t, g, tot) -> { });
        vc.start(Arrays.asList(SELF, "nodeB", "nodeC"), msg(5L, false), bus, 5L);
        assertFalse(bus.sent.containsKey(SELF), "不应向自己发 RequestVote");
        assertTrue(bus.sent.containsKey("nodeB"));
        assertTrue(bus.sent.containsKey("nodeC"));
    }
}
