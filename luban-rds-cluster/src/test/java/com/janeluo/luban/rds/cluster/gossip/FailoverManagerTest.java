package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FailoverManager 单元测试
 * 覆盖：状态机流转、退避广播、投票授权、胜选提升、FailoverResult 收敛
 */
class FailoverManagerTest {

    static final String NODE_ID_1 = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    static final String NODE_ID_2 = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    static final String NODE_ID_3 = "c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0";
    static final String NODE_ID_4 = "d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0";
    static final String NODE_ID_5 = "e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0";
    static final long NODE_TIMEOUT = 15000L;

    ClusterConfig config;
    SlotManager slotManager;
    ClusterStateManager stateManager;
    ClusterBusClient busClient;
    FailoverManager failoverManager;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
    }

    // ==================== 状态机基础 ====================

    @Test
    @DisplayName("初始状态为 IDLE")
    void testInitialState() {
        assertEquals(FailoverState.IDLE, failoverManager.getState());
    }

    @Test
    @DisplayName("slave 检测到 master FAIL 进入 REQUESTING 态")
    void testSlaveEntersRequestingWhenMasterFail() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // 额外 2 个可用 master 满足 quorum（canFailover 要求多数 master 可用）
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();

        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        Mockito.verifyNoInteractions(busClient);  // 退避窗口内未广播
    }

    @Test
    @DisplayName("非 slave 节点 tick 不触发选举")
    void testMasterDoesNotTriggerElection() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.tick();

        assertEquals(FailoverState.IDLE, failoverManager.getState());
        Mockito.verifyNoInteractions(busClient);
    }

    @Test
    @DisplayName("master 未 FAIL 时 slave 不触发选举")
    void testSlaveNoElectionWhenMasterAlive() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(master);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();

        assertEquals(FailoverState.IDLE, failoverManager.getState());
    }

    @Test
    @DisplayName("退避到期后广播 AUTH_REQUEST 并自增 epoch")
    void testBroadcastAfterBackoff() throws Exception {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // 额外 2 个可用 master 满足 quorum
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();  // 进入 REQUESTING
        Mockito.verifyNoInteractions(busClient);

        // 等退避窗口（gracePeriod=0 + N-11 固定 500ms 基数 + rank*1000 + jitter ≤ 500ms）+ 余量
        Thread.sleep(1100);
        failoverManager.tick();  // 退避到期，广播

        assertTrue(config.getCurrentEpoch() >= 1);
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverAuthRequestMessage.class));
    }

    // ==================== 投票授权（master 侧） ====================

    @Test
    @DisplayName("master 首次收到有效 AUTH_REQUEST 投票授权")
    void testMasterVotesForFirstRequest() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 候选 NODE_ID_2 是 slave，其 master NODE_ID_3 已 FAIL
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(
                NODE_ID_2, 5L, 10L, 0L);
        failoverManager.onAuthRequest(req);

        assertEquals(10L, config.getCurrentEpoch());
        Mockito.verify(busClient).broadcast(Mockito.argThat(
                m -> m instanceof FailoverAuthAckMessage
                        && NODE_ID_1.equals(((FailoverAuthAckMessage) m).getSenderNodeId())));
    }

    @Test
    @DisplayName("重复同纪元请求触发幂等重发 ACK")
    void testIdempotentAckResend() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        FailoverAuthRequestMessage req = new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L);
        failoverManager.onAuthRequest(req);
        failoverManager.onAuthRequest(req);  // 重复

        Mockito.verify(busClient, Mockito.times(2))
                .broadcast(Mockito.any(FailoverAuthAckMessage.class));
    }

    @Test
    @DisplayName("本纪元已投他 slave 则拒绝")
    void testRejectOtherSlaveInSameEpoch() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        // 两个候选 slave，其 master 均已 FAIL
        ClusterNode failedMaster1 = createMasterNode(NODE_ID_3, 7002);
        failedMaster1.addState(ClusterNodeState.FAIL);
        ClusterNode failedMaster2 = createMasterNode(NODE_ID_4, 7003);
        failedMaster2.addState(ClusterNodeState.FAIL);
        ClusterNode candidate2 = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidate5 = createSlaveNode(NODE_ID_5, 7004, NODE_ID_4);
        config.addNode(me);
        config.addNode(failedMaster1);
        config.addNode(failedMaster2);
        config.addNode(candidate2);
        config.addNode(candidate5);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.clearInvocations(busClient);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 10L, 0L));

        Mockito.verifyNoInteractions(busClient);
    }

    @Test
    @DisplayName("过期纪元请求被拒绝")
    void testRejectStaleEpoch() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_1);
        config.setCurrentEpoch(20L);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

        Mockito.verifyNoInteractions(busClient);
    }

    // ==================== 胜选提升（候选侧） ====================

    @Test
    @DisplayName("收到过半 master 授权后胜选提升并广播 RESULT")
    void testWinElectionAndPromote() {
        // 3 master 集群，需要 2 票（masterCount/2+1 = 2）
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        // m1 持有槽位 0-100
        for (int i = 0; i <= 100; i++) m1.addSlot(i);
        m1.addState(ClusterNodeState.FAIL);

        config.addNode(m1);
        config.addNode(m2);
        config.addNode(m3);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        // 进入候选态
        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        // 模拟已广播 AUTH_REQUEST（设置 electionEpoch=1，使 ACK 的 voteEpoch 校验通过）
        failoverManager.prepareRequestedStateForTest(1L);

        // 收到 2 个 master 授权（≥ masterCount/2+1 = 2）
        // FailoverAuthAckMessage 构造: (senderNodeId, configEpoch, currentEpoch, voteEpoch, candidateId)
        // candidateId 必须是 me(NODE_ID_2)，否则 onAuthAck 的候选绑定校验会忽略
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m2.getNodeId(), 1L, 1L, 1L, NODE_ID_2));
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m3.getNodeId(), 1L, 1L, 1L, NODE_ID_2));

        // 验证：me 已是 master、继承槽位、m1 降级 slave、广播 RESULT
        assertTrue(me.isMaster());
        assertFalse(me.isSlave());
        assertEquals(101, me.getSlotCount());
        assertFalse(m1.isMaster());
        assertTrue(m1.isSlave());
        assertEquals(me.getNodeId(), m1.getMasterNodeId());
        assertTrue(config.getCurrentEpoch() >= 1);
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverResultMessage.class));
    }

    @Test
    @DisplayName("授权票数未过半不触发胜选")
    void testNoWinWithoutMajority() {
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        m1.addState(ClusterNodeState.FAIL);

        config.addNode(m1);
        config.addNode(m2);
        config.addNode(m3);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();
        // 仅 1 票（需 2 票）
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m2.getNodeId(), 1L, 1L, 1L, NODE_ID_2));

        assertFalse(me.isMaster(), "未过半不应提升");
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
    }

    // ==================== FailoverResult 收敛 ====================

    @Test
    @DisplayName("onFailoverResult 收到结果后 winner 提权、原 master 降级、槽位转移")
    void testHandleFailoverResult() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);

        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);

        FailoverResultMessage msg = new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited);
        failoverManager.onFailoverResult(msg);

        assertTrue(winner.isMaster());
        assertFalse(winner.isFail());
        assertEquals(5L, winner.getConfigEpoch());
        assertEquals(100, winner.getSlotCount());
        assertFalse(oldMaster.isMaster());
        assertTrue(oldMaster.isSlave());
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());
        assertEquals(5L, config.getCurrentEpoch());
    }

    @Test
    @DisplayName("旧纪元 FailoverResult 被忽略")
    void testIgnoreStaleResult() {
        config.setCurrentEpoch(10L);
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(winner);

        BitSet inherited = new BitSet();
        inherited.set(0, 10);

        failoverManager.onFailoverResult(new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited));  // 5 < 10

        assertFalse(winner.isMaster());
        assertEquals(10L, config.getCurrentEpoch());
    }

    // ==================== N-9：FailoverResult 伪造防护 ====================

    @Test
    @DisplayName("N-9：sender≠winner 的 FailoverResult 被忽略（防伪造代发）")
    void testRejectForgedResultSenderNotWinner() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);
        config.addNode(winner);
        config.addNode(oldMaster);
        config.setCurrentEpoch(5L);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);
        // 第三方节点 NODE_ID_3 伪造 {winner=NODE_ID_2, epoch=6, slots=0-99}（sender≠winner）
        failoverManager.onFailoverResult(new FailoverResultMessage(NODE_ID_3, NODE_ID_2, 6L, inherited));

        assertFalse(winner.isMaster(), "伪造消息不应提升 winner");
        assertTrue(oldMaster.isMaster(), "旧 master 不应被降级");
        assertEquals(5L, config.getCurrentEpoch(), "currentEpoch 不应被抬升");
    }

    @Test
    @DisplayName("N-9：声明槽位由更高纪元节点持有时整体拒绝（槽位来源交叉校验）")
    void testRejectResultClaimingHigherEpochOwnerSlots() {
        config.setCurrentEpoch(5L);
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        // 槽位 0-99 当前由 configEpoch=7 的节点持有（比伪造声明纪元 6 更新）
        ClusterNode fresherOwner = createMasterNode(NODE_ID_3, 7002);
        fresherOwner.setConfigEpoch(7L);
        for (int i = 0; i <= 99; i++) {
            fresherOwner.addSlot(i);
            config.setSlotOwner(i, NODE_ID_3);
        }
        config.addNode(winner);
        config.addNode(fresherOwner);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);
        // 伪造 {winner=自己, epoch=6, slots=0-99}
        failoverManager.onFailoverResult(new FailoverResultMessage(NODE_ID_2, NODE_ID_2, 6L, inherited));

        assertFalse(winner.isMaster(), "槽位来源校验失败的消息不应被应用");
        assertTrue(fresherOwner.isMaster(), "更高纪元的 owner 不应被降级");
        assertEquals(NODE_ID_3, config.getSlotOwner(50), "槽位归属不应改变");
        assertEquals(5L, config.getCurrentEpoch(), "currentEpoch 不应被抬升");
    }

    // ==================== N-13：isStaleMaster 收窄 ====================

    @Test
    @DisplayName("N-13：无槽位低纪元的无关 master 不被任意 FailoverResult 降级")
    void testUnrelatedEmptyMasterNotDemotedByFailoverResult() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);
        // 无关的新建空 master（无槽位、低纪元；不手动构造以避免 createMasterNode 的默认槽位）
        ClusterNode emptyMaster = new ClusterNode(NODE_ID_3, "127.0.0.1", 7002, 17002);
        emptyMaster.addState(ClusterNodeState.MASTER);
        config.addNode(winner);
        config.addNode(oldMaster);
        config.addNode(emptyMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);

        failoverManager.onFailoverResult(new FailoverResultMessage(NODE_ID_2, NODE_ID_2, 5L, inherited));

        assertTrue(emptyMaster.isMaster(), "无关空 master 不应被降级为 winner 的 slave");
        assertFalse(emptyMaster.isSlave());
        assertNull(emptyMaster.getMasterNodeId());
        // 对照：真正与 inherited slots 有交集的旧 master 仍被正常降级
        assertTrue(oldMaster.isSlave());
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId());
    }

    // ==================== N-11：选举重试冷却 + 退避公式 ====================

    @Test
    @DisplayName("N-11：退避公式 = gracePeriod + 500 固定基数 + rank×1000 + jitter")
    void testBackoffFormulaMatchesRedis() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());

        long start = failoverManager.getElectionStartTimeForTest();
        long deadline = failoverManager.getRequestDeadlineForTest();
        // 无兄弟 slave → rank=0；gracePeriod=0；jitter = |nodeId.hashCode() % 500|
        long jitter = Math.abs(NODE_ID_2.hashCode() % 500L);
        assertEquals(start + 500L + 0L * 1000L + jitter, deadline,
                "退避到期时刻应为 start + 500（固定基数）+ rank×1000 + jitter");
    }

    @Test
    @DisplayName("N-11：选举超时后进入 4×nodeTimeout 重试冷却，冷却期内不重开选举")
    void testRetryCooldownAfterElectionTimeout() throws Exception {
        // 小 nodeTimeout（50ms）：选举超时 2×50=100ms，重试冷却 4×50=200ms
        FailoverManager fm = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, 50L, 0L);
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        master.addState(ClusterNodeState.FAIL);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        config.addNode(master);
        config.addNode(me);
        config.addNode(m2);
        config.addNode(m3);
        config.setMyNodeId(NODE_ID_2);

        // 进入 REQUESTING（退避窗口 ≥ 500ms，不会立即广播）
        fm.tick();
        assertEquals(FailoverState.REQUESTING, fm.getState());
        assertEquals(0L, fm.getRetryCooldownUntilForTest(), "初次选举无重试冷却");

        // 超过选举超时（100ms）→ 回 IDLE 并进入 4×nodeTimeout=200ms 冷却
        Thread.sleep(150);
        fm.tick();
        assertEquals(FailoverState.IDLE, fm.getState());
        long cooldownUntil = fm.getRetryCooldownUntilForTest();
        assertTrue(cooldownUntil > 0L, "选举超时后应设置重试冷却");

        // 冷却期内 tick 不重开选举（修复前下一轮 tick 立即重入 REQUESTING 形成选举风暴）
        fm.tick();
        assertEquals(FailoverState.IDLE, fm.getState(), "冷却期内不应重开选举");

        // 冷却过期后重开选举
        Thread.sleep(300);
        fm.tick();
        assertEquals(FailoverState.REQUESTING, fm.getState(), "冷却过期后应重开选举");
        assertEquals(0L, fm.getRetryCooldownUntilForTest(), "进入新选举后清除冷却标记");
    }

    // ==================== 手动故障转移 ====================

    @Test
    @DisplayName("手动 performManualFailover 不触发选举状态机、广播 RESULT（C9）")
    void testManualFailoverBypassesStateMachine() {
        ClusterNode master = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i < 100; i++) master.addSlot(i);
        ClusterNode slave = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        config.addNode(master);
        config.addNode(slave);

        failoverManager.performManualFailover(slave, master);

        assertEquals(FailoverState.IDLE, failoverManager.getState());
        assertTrue(slave.isMaster());
        assertTrue(master.isSlave());
        assertEquals(100, slave.getSlotCount());
        // C9: 手动 failover 广播 FailoverResult 使全网拓扑收敛
        Mockito.verify(busClient).broadcast(Mockito.any(FailoverResultMessage.class));
    }

    // ==================== slot 收敛：winner slots 单一来源 ====================

    @Test
    @DisplayName("onFailoverResult: winner 已持 inherited 子集时 winner.slots 精确等于 inherited（消除双写抖动）")
    void testOnFailoverResultWinnerSlotsExactMatchWhenPartiallyHeld() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        // oldMaster 持 0-99
        for (int i = 0; i <= 99; i++) oldMaster.addSlot(i);
        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 100);

        FailoverResultMessage msg = new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 5L, inherited);
        failoverManager.onFailoverResult(msg);

        // winner.slots 精确等于 inherited（0-99）
        BitSet winnerSlots = winner.getSlots();
        assertEquals(inherited, winnerSlots, "winner.slots 应精确等于 inherited");
        assertEquals(100, winner.getSlotCount());
        // slotAssignment 全部指向 winner
        for (int i = 0; i <= 99; i++) {
            assertEquals(NODE_ID_2, config.getSlotOwner(i),
                    "slot " + i + " 的 owner 应为 winner");
        }
        // oldMaster 已被降级且 slots 清空
        assertFalse(oldMaster.isMaster());
        assertEquals(0, oldMaster.getSlotCount());
    }

    @Test
    @DisplayName("onFailoverResult: winner 提权为 MASTER 时 slotAssignment 均指向 winner 且 configEpoch=newConfigEpoch")
    void testOnFailoverResultWinnerPromotionAfterSlotsAssigned() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 5462; i <= 10922; i++) oldMaster.addSlot(i);
        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(5462, 10923);

        failoverManager.onFailoverResult(new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 8L, inherited));

        assertTrue(winner.isMaster(), "winner 应为 MASTER");
        assertEquals(8L, winner.getConfigEpoch(), "winner.configEpoch 应等于 newConfigEpoch");
        // winner 声明的每个 slot 都指向 winner（不变式 D：MASTER 时 slots 归属自身）
        BitSet winnerSlots = winner.getSlots();
        for (int i = winnerSlots.nextSetBit(0); i >= 0; i = winnerSlots.nextSetBit(i + 1)) {
            assertEquals(NODE_ID_2, config.getSlotOwner(i),
                    "winner 声明的 slot " + i + " 应归属 winner");
        }
    }

    @Test
    @DisplayName("onFailoverResult: oldMaster 降级为 SLAVE、slots 清空、masterNodeId 指向 winner")
    void testOnFailoverResultOldMasterDemotedSlotsCleared() {
        ClusterNode winner = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        ClusterNode oldMaster = createMasterNode(NODE_ID_1, 7000);
        for (int i = 0; i <= 49; i++) oldMaster.addSlot(i);
        config.addNode(winner);
        config.addNode(oldMaster);

        BitSet inherited = new BitSet();
        inherited.set(0, 50);

        failoverManager.onFailoverResult(new FailoverResultMessage(
                NODE_ID_2, NODE_ID_2, 6L, inherited));

        assertTrue(oldMaster.isSlave(), "oldMaster 应降级为 SLAVE");
        assertFalse(oldMaster.isMaster());
        assertEquals(0, oldMaster.getSlotCount(), "oldMaster slots 应清空");
        assertEquals(NODE_ID_2, oldMaster.getMasterNodeId(), "oldMaster.masterNodeId 应指向 winner");
        assertEquals(6L, oldMaster.getConfigEpoch(), "oldMaster.configEpoch 应提升到 winner epoch");
    }

    // ==================== P0-4 回归：候选绑定 + lastVoteEpoch 闸门 ====================

    /**
     * P0-4 核心回归：同纪元双候选场景，投给候选 A 的 ACK 不能被候选 B 计入票数。
     * 修复前 ACK 无 candidateId，B 会误计 A 的票，两个候选各自过半 → 双 master。
     */
    @Test
    @DisplayName("P0-4：候选只计入投给自己的 ACK，投给他候选的 ACK 被忽略（候选绑定）")
    void testAckCandidateBindingPreventsDoubleCount() {
        // 3 master 集群，需 2 票胜选
        ClusterNode m1 = createMasterNode(NODE_ID_1, 7000);
        ClusterNode m2 = createMasterNode(NODE_ID_3, 7002);
        ClusterNode m3 = createMasterNode(NODE_ID_4, 7003);
        ClusterNode me = createSlaveNode(NODE_ID_2, 7001, NODE_ID_1);
        me.addState(ClusterNodeState.MYSELF);
        for (int i = 0; i <= 100; i++) m1.addSlot(i);
        m1.addState(ClusterNodeState.FAIL);

        config.addNode(m1);
        config.addNode(m2);
        config.addNode(m3);
        config.addNode(me);
        config.setMyNodeId(NODE_ID_2);

        failoverManager.tick();
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        failoverManager.prepareRequestedStateForTest(1L);

        // m2 投给"本候选 NODE_ID_2" -> 计入
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m2.getNodeId(), 1L, 1L, 1L, NODE_ID_2));
        // m3 投给"另一个候选 NODE_ID_5"（同纪元）-> 必须被忽略（候选绑定校验）
        failoverManager.onAuthAck(new FailoverAuthAckMessage(
                m3.getNodeId(), 1L, 1L, 1L, NODE_ID_5));

        // 仅 1 票有效，未过半，不应胜选
        assertFalse(me.isMaster(), "他候选的 ACK 不应计入本候选票数，未过半不应提升");
        assertEquals(FailoverState.REQUESTING, failoverManager.getState());
        Mockito.verify(busClient, Mockito.never())
                .broadcast(Mockito.any(FailoverResultMessage.class));
    }

    /**
     * P0-4：sendAuthAck 产出的 ACK 必须携带 candidateId（被投候选），
     * 这样同集群其他候选才能正确忽略。
     */
    @Test
    @DisplayName("P0-4：投票方产出的 ACK 携带正确的 candidateId 字段")
    void testVoteAckCarriesCandidateId() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));

        ArgumentCaptor<FailoverAuthAckMessage> captor =
                ArgumentCaptor.forClass(FailoverAuthAckMessage.class);
        Mockito.verify(busClient).broadcast(captor.capture());
        FailoverAuthAckMessage ack = captor.getValue();
        assertEquals(NODE_ID_2, ack.getCandidateId(),
                "ACK 必须携带被投候选 candidateId");
        assertEquals(NODE_ID_1, ack.getSenderNodeId(), "ACK senderNodeId 应为投票方");
    }

    /**
     * P0-4：lastVoteEpoch 闸门——投过票后，同纪元或更早纪元的新候选请求被拒绝。
     */
    @Test
    @DisplayName("P0-4：投票后 lastVoteEpoch 闸门拒绝同纪元他候选二次请求")
    void testLastVoteEpochGateRejectsSameEpochRevote() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMasterA = createMasterNode(NODE_ID_3, 7002);
        failedMasterA.addState(ClusterNodeState.FAIL);
        ClusterNode failedMasterB = createMasterNode(NODE_ID_4, 7003);
        failedMasterB.addState(ClusterNodeState.FAIL);
        ClusterNode candidateA = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        ClusterNode candidateB = createSlaveNode(NODE_ID_5, 7004, NODE_ID_4);
        config.addNode(me);
        config.addNode(failedMasterA);
        config.addNode(failedMasterB);
        config.addNode(candidateA);
        config.addNode(candidateB);
        config.setMyNodeId(NODE_ID_1);

        // 投给 candidateA（epoch=10）-> 授权
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        assertEquals(10L, config.getLastVoteEpoch(), "投票后 lastVoteEpoch 应更新");

        Mockito.clearInvocations(busClient);
        // 同纪元 candidateB -> 被 lastVoteEpoch 闸门拒绝
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_5, 5L, 10L, 0L));
        Mockito.verifyNoInteractions(busClient);
    }

    /**
     * P0-4：模拟重启——votesCast 清空（内存态丢失），但 lastVoteEpoch 持久化保留，
     * 仍能拒绝同纪元重投。这是 Redis 7 lastVoteEpoch 持久化的核心价值。
     */
    @Test
    @DisplayName("P0-4：重启后 votesCast 丢失但 lastVoteEpoch 保留，仍拒绝同纪元重投")
    void testRestartLastVoteEpochSurvives() {
        ClusterNode me = createMasterNode(NODE_ID_1, 7000);
        me.addState(ClusterNodeState.MYSELF);
        ClusterNode failedMaster = createMasterNode(NODE_ID_3, 7002);
        failedMaster.addState(ClusterNodeState.FAIL);
        ClusterNode candidate = createSlaveNode(NODE_ID_2, 7001, NODE_ID_3);
        config.addNode(me);
        config.addNode(failedMaster);
        config.addNode(candidate);
        config.setMyNodeId(NODE_ID_1);

        // 投票（epoch=10）
        failoverManager.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        assertEquals(10L, config.getLastVoteEpoch());

        // 模拟重启：重建 FailoverManager（votesCast 为空），仅从 nodes.conf 恢复 lastVoteEpoch
        FailoverManager restarted = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
        config.setLastVoteEpoch(10L);  // 模拟持久化恢复

        Mockito.clearInvocations(busClient);
        // 重启后同纪元同候选重投 -> 被 lastVoteEpoch 闸门拒绝（不会因 votesCast 空而放行）
        restarted.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 10L, 0L));
        Mockito.verifyNoInteractions(busClient);

        // 更高纪元（epoch=11）-> 闸门放行，可重新投票
        restarted.onAuthRequest(new FailoverAuthRequestMessage(NODE_ID_2, 5L, 11L, 0L));
        assertEquals(11L, config.getLastVoteEpoch(), "更高纪元请求应放行并更新 lastVoteEpoch");
    }

    /**
     * P0-4：ACK 线编解码往返——candidateId 必须随消息体正确序列化/反序列化，
     * 否则跨节点传输后候选绑定校验会失效。
     */
    @Test
    @DisplayName("P0-4：FailoverAuthAckMessage candidateId 线编解码往返")
    void testAckCandidateIdWireRoundTrip() {
        FailoverAuthAckMessage original = new FailoverAuthAckMessage(
                NODE_ID_1, 5L, 10L, 10L, NODE_ID_2);

        // 模拟总线编码→解码（通过 encode/decode 走全链路）
        byte[] encoded = original.encode();
        assertNotNull(encoded);

        FailoverAuthAckMessage decoded = new FailoverAuthAckMessage();
        decoded.decode(encoded);

        assertEquals(NODE_ID_1, decoded.getSenderNodeId());
        assertEquals(5L, decoded.getConfigEpoch());
        assertEquals(10L, decoded.getCurrentEpoch());
        assertEquals(10L, decoded.getVoteEpoch());
        assertEquals(NODE_ID_2, decoded.getCandidateId(),
                "candidateId 经线编解码后必须保持一致");
    }

    /**
     * P0-4：无 candidateId 的旧格式 ACK 解码兼容（candidateId 为 null，候选绑定校验会拒绝）。
     */
    @Test
    @DisplayName("P0-4：旧版无 candidateId 的 ACK 解码后 candidateId 为 null")
    void testAckLegacyDecodeCandidateIdNull() {
        // 用 4 参构造（candidateId=null）后编码
        FailoverAuthAckMessage legacy = new FailoverAuthAckMessage(NODE_ID_1, 5L, 10L, 10L);
        byte[] encoded = legacy.encode();

        FailoverAuthAckMessage decoded = new FailoverAuthAckMessage();
        decoded.decode(encoded);
        assertNull(decoded.getCandidateId(),
                "旧版 ACK 无候选字段，解码后 candidateId 应为 null（会被候选绑定校验拒绝）");
    }

    // ==================== 辅助方法 ====================

    /**
     * 创建主节点。默认分配槽位 0——N-14 起投票者必须持槽（对齐 Redis
     * "myself->numslots == 0 无投票权"），多数投票测试以 master 身份投票，需满足该前置。
     */
    private ClusterNode createMasterNode(String id, int port) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.MASTER);
        n.addSlot(0);
        return n;
    }

    private ClusterNode createSlaveNode(String id, int port, String masterId) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.SLAVE);
        n.setMasterNodeId(masterId);
        return n;
    }
}
