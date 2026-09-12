package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshReadyGateTest {

    private MeshNode newNode(MeshState state) {
        MeshConfig config = MeshConfig.builder("n1").build();
        return new MeshNode(config, state, new MeshBusClient("n1", new MeshBusHandler()),
                new RaftStateMachine());
    }

    @Test
    void newBootstrapNode_isNotReady_untilMarked() {
        MeshNode node = newNode(new MeshState());
        assertFalse(node.isReady(), "装配完成但未置位前不得就绪");
        node.markReady();
        assertTrue(node.isReady());
    }

    @Test
    void markReady_isIdempotent_andDoesNotRegressOnRoleChange() {
        MeshNode node = newNode(new MeshState());
        node.markReady();
        node.markReady();                                  // 幂等
        assertTrue(node.isReady());

        node.getState().role = com.janeluo.luban.rds.mesh.core.MeshRole.FOLLOWER;
        assertTrue(node.isReady(), "角色切换不得清除就绪标志");
    }

    @Test
    void onSnapshotInstalled_signalsBarrier_andMarksReady() {
        MeshState state = new MeshState();
        state.lastApplied = 0;
        MeshNode node = newNode(state);

        boolean[] awakened = {false};
        Thread waiter = new Thread(() -> {
            try {
                awakened[0] = node.applyBarrier().awaitApplied(100, 5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        try { Thread.sleep(100); } catch (InterruptedException ignored) { }

        state.lastApplied = 100;
        node.onSnapshotInstalled();
        try { waiter.join(3_000); } catch (InterruptedException ignored) { }

        assertTrue(awakened[0], "快照安装完成必须唤醒屏障等待者");
        assertTrue(node.isReady(), "快照安装完成必须置位就绪");
    }
}
