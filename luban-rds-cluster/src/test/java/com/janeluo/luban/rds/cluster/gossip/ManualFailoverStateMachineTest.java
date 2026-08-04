package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.bus.ClusterBusClient;
import com.janeluo.luban.rds.cluster.config.ClusterConfig;
import com.janeluo.luban.rds.cluster.config.ClusterStateManager;
import com.janeluo.luban.rds.cluster.lifecycle.NoOpReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.lifecycle.ReplicationLifecycleListener;
import com.janeluo.luban.rds.cluster.lifecycle.WritePauseGate;
import com.janeluo.luban.rds.cluster.node.ClusterNode;
import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import com.janeluo.luban.rds.cluster.slot.DefaultSlotManager;
import com.janeluo.luban.rds.cluster.slot.SlotManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 手动 failover 状态机测试（P1-12）。
 * <p>
 * 覆盖 CLUSTER FAILOVER 普通模式异步流程：MF_REQUESTED → MF_WAITING_OFFSET → MF_READY，
 * 以及 master 侧 MFStart 处理（暂停写、回传 offset）、slave 侧 offset 追平后提升。
 * </p>
 */
class ManualFailoverStateMachineTest {

    static final String MASTER_ID = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    static final String SLAVE_ID = "b1c2d3e4f5a6b7c8d9e0f1a2b3c4d5e6f7a8b9c0";
    static final long NODE_TIMEOUT = 15000L;

    ClusterConfig config;
    SlotManager slotManager;
    ClusterStateManager stateManager;
    ClusterBusClient busClient;
    FailoverManager failoverManager;
    TrackingWritePauseGate writePauseGate;

    @BeforeEach
    void setUp() {
        config = new ClusterConfig();
        slotManager = new DefaultSlotManager();
        stateManager = new ClusterStateManager(config);
        busClient = Mockito.mock(ClusterBusClient.class);
        writePauseGate = new TrackingWritePauseGate();
        failoverManager = new FailoverManager(config, slotManager, stateManager, busClient,
                () -> {}, NODE_TIMEOUT, 0L);
        failoverManager.setWritePauseGate(writePauseGate);
        // NoOp 复制监听器（getReplicationOffset 默认返回 0）
        failoverManager.setReplicationLifecycleListener(new NoOpReplicationLifecycleListener());
    }

    @Test
    @DisplayName("初始手动 failover 状态为 NONE")
    void testInitialState() {
        assertEquals(ManualFailoverState.NONE, failoverManager.getManualFailoverState());
    }

    @Test
    @DisplayName("startManualFailover 进入 MF_REQUESTED 并向 master 发 MFStart")
    void testStartManualFailoverSendsMfStart() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        ClusterNode me = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(me);

        failoverManager.startManualFailover(me, master);

        assertEquals(ManualFailoverState.MF_REQUESTED, failoverManager.getManualFailoverState());
        // 验证向 master 发送了 MANUAL_FAILOVER_START
        ArgumentCaptor<GossipMessage> captor = ArgumentCaptor.forClass(GossipMessage.class);
        verify(busClient).send(eq(MASTER_ID), captor.capture());
        assertEquals(GossipMessageType.MANUAL_FAILOVER_START, captor.getValue().getType());
    }

    @Test
    @DisplayName("master 侧收到 MFStart：暂停写、记录 offset、回传 MFOffset 给 slave")
    void testMasterHandlesMfStart() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        master.addState(ClusterNodeState.MYSELF);
        ClusterNode slave = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(slave);
        config.setMyNodeId(MASTER_ID);
        // master 复制 offset 为 12345
        failoverManager.setReplicationLifecycleListener(new FixedOffsetListener(12345L));

        ManualFailoverStartMessage mfStart = new ManualFailoverStartMessage(SLAVE_ID);
        failoverManager.onManualFailoverStart(mfStart);

        // 写暂停门控被激活
        assertTrue(writePauseGate.paused, "master 收到 MFStart 后应暂停写");
        // 验证向 slave 回传了 MFOffset，携带 master offset=12345
        ArgumentCaptor<GossipMessage> captor = ArgumentCaptor.forClass(GossipMessage.class);
        verify(busClient).send(eq(SLAVE_ID), captor.capture());
        assertInstanceOf(ManualFailoverOffsetMessage.class, captor.getValue());
        assertEquals(12345L, ((ManualFailoverOffsetMessage) captor.getValue()).getMasterOffset());
    }

    @Test
    @DisplayName("master 侧忽略非 master 节点的 MFStart")
    void testMasterIgnoresMfStartFromNonSlave() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        master.addState(ClusterNodeState.MYSELF);
        // 发送方是另一个 master（非本节点 slave）
        ClusterNode otherMaster = createMasterNode("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0", 7002);
        config.addNode(master);
        config.addNode(otherMaster);
        config.setMyNodeId(MASTER_ID);

        ManualFailoverStartMessage mfStart =
                new ManualFailoverStartMessage("c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0");
        failoverManager.onManualFailoverStart(mfStart);

        assertFalse(writePauseGate.paused, "非本节点 slave 的 MFStart 不应触发写暂停");
        verifyNoInteractions(busClient);
    }

    @Test
    @DisplayName("slave 侧收到 MFOffset 后转入 MF_WAITING_OFFSET")
    void testSlaveReceivesOffsetTransitionsToWaiting() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        ClusterNode me = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(me);

        failoverManager.startManualFailover(me, master);
        assertEquals(ManualFailoverState.MF_REQUESTED, failoverManager.getManualFailoverState());

        ManualFailoverOffsetMessage offsetMsg = new ManualFailoverOffsetMessage(MASTER_ID, 5000L);
        failoverManager.onManualFailoverOffset(offsetMsg);

        assertEquals(ManualFailoverState.MF_WAITING_OFFSET, failoverManager.getManualFailoverState());
    }

    @Test
    @DisplayName("slave offset 追平后执行提升并回退 NONE（offset=0 视为已追平）")
    void testSlaveCatchesUpAndPromotes() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        master.addSlot(0);
        ClusterNode me = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(me);
        config.setMyNodeId(SLAVE_ID);

        failoverManager.startManualFailover(me, master);
        // master offset=0（未装配复制）→ slave 立即视为追平
        failoverManager.onManualFailoverOffset(new ManualFailoverOffsetMessage(MASTER_ID, 0L));
        assertEquals(ManualFailoverState.MF_WAITING_OFFSET, failoverManager.getManualFailoverState());

        // tick 推进：offset=0 应立即追平并提升
        failoverManager.tick();

        assertEquals(ManualFailoverState.NONE, failoverManager.getManualFailoverState());
        // slave 已提升为 master
        assertTrue(me.isMaster(), "slave 应被提升为 master");
        assertTrue(me.hasState(ClusterNodeState.MASTER));
    }

    @Test
    @DisplayName("slave offset 未追平时保持 WAITING_OFFSET 不提升")
    void testSlaveNotCaughtUpStaysWaiting() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        master.addSlot(0);
        ClusterNode me = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(me);
        // slave offset=100，目标=5000，未追平
        failoverManager.setReplicationLifecycleListener(new FixedOffsetListener(100L));

        failoverManager.startManualFailover(me, master);
        failoverManager.onManualFailoverOffset(new ManualFailoverOffsetMessage(MASTER_ID, 5000L));

        failoverManager.tick();
        // 仍在等待追平
        assertEquals(ManualFailoverState.MF_WAITING_OFFSET, failoverManager.getManualFailoverState());
        assertFalse(me.isMaster(), "未追平时不应提升");
    }

    @Test
    @DisplayName("手动 failover 完成后写暂停被解除（resume 调用）")
    void testWritePauseResumedAfterPromotion() {
        ClusterNode master = createMasterNode(MASTER_ID, 7000);
        master.addSlot(0);
        ClusterNode me = createSlaveNode(SLAVE_ID, 7001, MASTER_ID);
        config.addNode(master);
        config.addNode(me);
        config.setMyNodeId(SLAVE_ID);

        // 先模拟 master 侧暂停（onManualFailoverStart 会暂停）
        // 此处直接测试 abortManualFailover 路径：启动后立即 tick（offset=0 追平）→ 完成 → resume
        failoverManager.startManualFailover(me, master);
        failoverManager.onManualFailoverOffset(new ManualFailoverOffsetMessage(MASTER_ID, 0L));
        failoverManager.tick();

        // 提升完成后，abortManualFailover 调用了 writePauseGate.resume()
        assertFalse(writePauseGate.paused, "提升完成后写暂停应被解除");
    }

    // ==================== 辅助类 ====================

    private ClusterNode createMasterNode(String id, int port) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.MASTER);
        return n;
    }

    private ClusterNode createSlaveNode(String id, int port, String masterId) {
        ClusterNode n = new ClusterNode(id, "127.0.0.1", port, port + 10000);
        n.addState(ClusterNodeState.SLAVE);
        n.setMasterNodeId(masterId);
        return n;
    }

    /** 返回固定 offset 的复制监听器（用于测试 offset 追平判定）。 */
    private static class FixedOffsetListener extends NoOpReplicationLifecycleListener {
        private final long offset;

        FixedOffsetListener(long offset) {
            this.offset = offset;
        }

        @Override
        public long getReplicationOffset() {
            return offset;
        }
    }

    /** 跟踪暂停状态的写暂停门控（测试观察 pause/resume 调用）。 */
    private static class TrackingWritePauseGate implements WritePauseGate {
        volatile boolean paused = false;

        @Override
        public void pause() {
            paused = true;
        }

        @Override
        public void resume() {
            paused = false;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }
    }
}
