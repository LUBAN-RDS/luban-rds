package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MeshReadGateEntryTest {

    private MeshWriteGate gate(MeshNode node) {
        return new MeshWriteGate(node, new DefaultMemoryStore(), new DefaultCommandHandler(""),
                MeshConfig.builder("n1").build(), Collections.emptyMap(), null);
    }

    @Test
    void applyHalted_readRejectedWithRetryable() {
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(true);
        assertThrows(RetryableMeshException.class,
                () -> gate(node).read(0, new String[]{"GET", "k"}));
        // apply halt 与就绪门是两个独立计数：halt 不应推高 not_ready_rejected
        verify(node, never()).incFollowerReadNotReadyRejected();
    }

    @Test
    void notReady_readRejectedWithRetryable() {
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(false);
        when(node.isApplyHalted()).thenReturn(false);
        assertThrows(RetryableMeshException.class,
                () -> gate(node).read(0, new String[]{"GET", "k"}));
        // Item 4：mesh_follower_read_not_ready_rejected 必须有真实计数源（不再是恒 0 占位）
        verify(node).incFollowerReadNotReadyRejected();
    }
}
