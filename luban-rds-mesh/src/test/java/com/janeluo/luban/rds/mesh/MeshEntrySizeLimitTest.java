package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.gateway.RequestTooLargeException;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q7（2026-09-11 mesh 审计 P2）：propose 前条目大小预检——超限 fail-fast。
 * <p>此前超限条目追加进 log 后被总线 Encoder（16MB 上限）静默丢弃，Leader 陷入
 * 100ms 重发-被丢死循环、future 永久悬挂。现在直接异常完成且不污染日志。</p>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class MeshEntrySizeLimitTest {

    /** 测试用总线：只记录发送，不真正建连（与 MeshNodePersistAsyncTest 同模式）。 */
    private static class CaptureBus extends com.janeluo.luban.rds.mesh.bus.MeshBusClient {
        CaptureBus(String selfId) {
            super(selfId, new com.janeluo.luban.rds.mesh.bus.MeshBusHandler());
        }
        @Override
        public void send(String targetNodeId, com.janeluo.luban.rds.mesh.bus.MeshFrame frame) { /* no-op */ }
    }

    private static MeshConfig threeNodeConfig() {
        // 单节点配置：单节点即多数派，propose 后可独立 commit（CaptureBus 无真实 peer）
        return MeshConfig.builder("a")
                .electionTimeout(5000, 10000)
                .heartbeatIntervalMs(100)
                .build();
    }

    private static MeshNode newLeaderNode() {
        MeshConfig config = threeNodeConfig();
        MeshState state = new MeshState();
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        CaptureBus bus = new CaptureBus("a");
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine(), applier, rawStore);
        node.start();
        // 反射调 onWinElection 置为 Leader（与 MeshNodePersistAsyncTest 同模式）
        try {
            java.lang.reflect.Method m = MeshNode.class.getDeclaredMethod("onWinElection");
            m.setAccessible(true);
            m.invoke(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return node;
    }

    private static byte[] setPayload(int valueSize) {
        String head = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$" + valueSize + "\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        byte[] payload = new byte[headBytes.length + valueSize + 2];
        System.arraycopy(headBytes, 0, payload, 0, headBytes.length);
        Arrays.fill(payload, headBytes.length, headBytes.length + valueSize, (byte) 'x');
        payload[payload.length - 2] = '\r';
        payload[payload.length - 1] = '\n';
        return payload;
    }

    @Test
    void oversizeEntryFailsFastWithoutPollutingLog() throws Exception {
        MeshNode node = newLeaderNode();
        try {
            long lastLogBefore = node.getState().getLastLogIndex();
            int valueSize = (int) MeshNode.MAX_ENTRY_BYTES;   // 28B 固定字段 + 12MB payload 必超限

            CompletableFuture<byte[]> f = node.propose(setPayload(valueSize), 0, null);
            ExecutionException ee = assertThrows(ExecutionException.class,
                    () -> f.get(2, TimeUnit.SECONDS));
            assertTrue(ee.getCause() instanceof RequestTooLargeException,
                    "超限条目应抛 RequestTooLargeException，实际: " + ee.getCause());
            assertEquals(lastLogBefore, node.getState().getLastLogIndex(),
                    "超限条目不得追加进 log");
        } finally {
            node.stop();
        }
    }

    @Test
    void normalSizeEntryPassesPrecheck() throws Exception {
        MeshNode node = newLeaderNode();
        try {
            CompletableFuture<byte[]> f = node.propose(
                    "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n".getBytes(StandardCharsets.US_ASCII),
                    0, null);
            f.get(2, TimeUnit.SECONDS);
            // index 1 = onWinElection 追加的 no-op（P1-3），propose 条目为 index 2
            assertEquals(2, node.getState().getLastLogIndex(), "正常条目应正常 propose 并 commit");
        } finally {
            node.stop();
        }
    }
}
