package com.janeluo.luban.rds.cluster.bus;

import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.gossip.GossipMessage;
import com.janeluo.luban.rds.cluster.gossip.MigrateKeyAckMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * ClusterBusClient 请求 ID 严格匹配测试（P1-20）。
 * <p>
 * 验证 sendAndWait 的 requestId 分配与 completeResponse 的严格匹配行为，
 * 确保并发 MIGRATE 到同一节点时 ACK 不会串线（A 的 ACK 错误完成 B 的 future）。
 * </p>
 */
class ClusterBusClientRequestIdTest {

    @Mock
    private ClusterConfig clusterConfig;

    private ClusterBusClient client;
    private AutoCloseable mocks;

    /** 合法的 40 字符节点 ID（用于构造消息） */
    private static final String SENDER_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        // null gossipProtocol：构造允许，sendAndWait 在 channel 不活跃时早返回
        client = new ClusterBusClient(clusterConfig, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        mocks.close();
    }

    /**
     * 直接验证 completeResponse 按 requestId 严格匹配：
     * 用不存在的 requestId 调用应安全无副作用（不完成任何 future）。
     */
    @Test
    @DisplayName("completeResponse 对未知 requestId 安全无副作用")
    void testCompleteResponseUnknownRequestIdIsNoOp() throws Exception {
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(SENDER_ID, "key", true, null);
        // requestId=999 不存在，不应抛异常
        client.completeResponse(999L, ack);
        // 无 future 被完成（pendingResponses 为空）
        assertTrue(getPendingResponses().isEmpty());
    }

    /**
     * requestId=0（旧格式/未携带）应被忽略，不完成任何 future（防止旧 ACK 误匹配）。
     */
    @Test
    @DisplayName("completeResponse 对 requestId=0（旧格式）忽略不匹配")
    void testCompleteResponseZeroRequestIdIgnored() throws Exception {
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(SENDER_ID, "key", true, null);
        client.completeResponse(0L, ack);
        assertTrue(getPendingResponses().isEmpty());
    }

    /**
     * 手动注册一个 future（模拟 sendAndWait 注册），验证正确 requestId 能完成它，
     * 错误 requestId 不能完成它（核心防串线保证）。
     */
    @Test
    @DisplayName("正确 requestId 完成 future，错误 requestId 不串线")
    void testStrictRequestIdMatchingPreventsCrossWiring() throws Exception {
        Map<Long, CompletableFuture<GossipMessage>> pending = getPendingResponses();

        // 模拟两个并发请求注册到同一"节点"（旧实现按 nodeId 单槽位会覆盖）
        CompletableFuture<GossipMessage> futureA = new CompletableFuture<>();
        CompletableFuture<GossipMessage> futureB = new CompletableFuture<>();
        pending.put(1L, futureA);
        pending.put(2L, futureB);

        // A 的 ACK（requestId=1）到达：只应完成 futureA
        MigrateKeyAckMessage ackA = new MigrateKeyAckMessage(SENDER_ID, "keyA", true, null);
        ackA.setRequestId(1L);
        client.completeResponse(1L, ackA);

        assertTrue(futureA.isDone(), "futureA 应被 requestId=1 的 ACK 完成");
        assertEquals("keyA", ((MigrateKeyAckMessage) futureA.getNow(null)).getKey());
        assertFalse(futureB.isDone(), "futureB 不应被 A 的 ACK 完成（防串线）");

        // B 的 ACK（requestId=2）到达：完成 futureB
        MigrateKeyAckMessage ackB = new MigrateKeyAckMessage(SENDER_ID, "keyB", true, null);
        ackB.setRequestId(2L);
        client.completeResponse(2L, ackB);

        assertTrue(futureB.isDone(), "futureB 应被 requestId=2 的 ACK 完成");
        assertEquals("keyB", ((MigrateKeyAckMessage) futureB.getNow(null)).getKey());
    }

    /**
     * 验证 requestIdSeq 从随机初值（≥1）起递增（保证 ≥1 且不可预测，与 requestId=0
     * 旧格式不冲突；随机化使伪造 ACK 命中在途请求的难度增加，N-7）。
     */
    @Test
    @DisplayName("requestIdSeq 从随机初值（≥1）起递增")
    void testRequestIdSequenceStartsFromRandom() throws Exception {
        AtomicLong seq = getRequestIdSeq();
        long first = seq.get();
        assertTrue(first >= 1L, "初始值应 ≥1（与 requestId=0 旧格式不冲突），实际: " + first);
        assertEquals(first, seq.getAndIncrement());
        assertEquals(first + 1L, seq.getAndIncrement());
    }

    /**
     * N-7：ACK 发送方与请求目标节点不一致时应被忽略（防伪造 ACK 命中在途请求）。
     */
    @Test
    @DisplayName("N-7：ACK 来源与请求目标不一致时忽略（防伪造 ACK）")
    void testCompleteResponseRejectsWrongSender() throws Exception {
        Map<Long, CompletableFuture<GossipMessage>> pending = getPendingResponses();
        Map<Long, String> targets = getPendingResponseTargets();

        CompletableFuture<GossipMessage> future = new CompletableFuture<>();
        pending.put(1L, future);
        // 请求目标是节点 A（40 字符 ID）
        String targetA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
        String attackerB = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
        targets.put(1L, targetA);
        // 伪造 ACK：发送方是节点 B（≠目标 A），即使 requestId 匹配也不应完成 future
        MigrateKeyAckMessage forgedAck = new MigrateKeyAckMessage(attackerB, "key", true, null);
        forgedAck.setRequestId(1L);
        client.completeResponse(1L, forgedAck, attackerB);

        assertFalse(future.isDone(), "来源不一致的 ACK 不应完成 future");
        assertFalse(future.isCompletedExceptionally(), "被拒 ACK 不应让 future 异常完成");

        // 合法 ACK：发送方 == 目标节点 A → 完成
        MigrateKeyAckMessage legitAck = new MigrateKeyAckMessage(targetA, "key", true, null);
        legitAck.setRequestId(1L);
        client.completeResponse(1L, legitAck, targetA);
        assertTrue(future.isDone(), "来源一致的 ACK 应完成 future");
    }

    /**
     * N-7：无目标记录的陈旧/伪造 ACK（requestId 已超时清理）应被忽略。
     */
    @Test
    @DisplayName("N-7：无目标记录的 ACK 忽略（陈旧/伪造）")
    void testCompleteResponseWithoutTargetRecordIgnored() throws Exception {
        MigrateKeyAckMessage ack = new MigrateKeyAckMessage(SENDER_ID, "key", true, null);
        ack.setRequestId(42L);
        // 未注册目标记录（模拟超时清理后迟到的 ACK）
        client.completeResponse(42L, ack, SENDER_ID);
        assertTrue(getPendingResponses().isEmpty());
    }

    // ==================== 反射辅助（访问私有字段验证内部状态） ====================

    @SuppressWarnings("unchecked")
    private Map<Long, CompletableFuture<GossipMessage>> getPendingResponses() throws Exception {
        Field f = ClusterBusClient.class.getDeclaredField("pendingResponses");
        f.setAccessible(true);
        return (Map<Long, CompletableFuture<GossipMessage>>) f.get(client);
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String> getPendingResponseTargets() throws Exception {
        Field f = ClusterBusClient.class.getDeclaredField("pendingResponseTargets");
        f.setAccessible(true);
        return (Map<Long, String>) f.get(client);
    }

    private AtomicLong getRequestIdSeq() throws Exception {
        Field f = ClusterBusClient.class.getDeclaredField("requestIdSeq");
        f.setAccessible(true);
        return (AtomicLong) f.get(client);
    }
}
