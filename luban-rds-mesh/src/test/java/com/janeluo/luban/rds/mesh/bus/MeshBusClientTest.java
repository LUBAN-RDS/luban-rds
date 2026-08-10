package com.janeluo.luban.rds.mesh.bus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@link MeshBusClient} 退避重置单元测试。
 * <p>
 * 覆盖 {@link #notifyPeerAlive(String)} 的三个分支：出站断开退避中收到在线信号
 * （退避计数清零 + 重新调度立即重连）、出站连接正常（零副作用）、未知 nodeId（不处理）。
 * </p>
 */
class MeshBusClientTest {

    /** 本机几乎必未监听的端口：连接立即 ECONNREFUSED，用于构造"出站断开 + 退避调度"场景 */
    private static final int DEAD_PORT = 1;

    // ==================== 出站断开 + 退避中收到在线信号 ====================

    @Test
    void peerAlive_whileOutboundDisconnected_resetsBackoffAndReconnects() throws Exception {
        MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
        try {
            client.start(Map.of("node-b", new MeshBusClient.PeerEndpoint("127.0.0.1", DEAD_PORT)));

            // 等第一次连接失败触发退避调度（attempts=1，延迟 2s）
            awaitCondition("首次连接失败并调度退避", () -> Long.valueOf(1L).equals(attempts(client).get("node-b")), 3000);

            // 收到在线信号：退避从 2s 重新起步（清零后立即重新计数 1，并绕过 64s 去重窗口重新调度）
            long t0 = System.currentTimeMillis();
            client.notifyPeerAlive("node-b");
            assertTrue(scheduled(client).containsKey("node-b"), "在线信号应重新调度重连");

            // 重置后 ~2s 内应执行一次重连（再次失败 → attempts 变 2）；
            // 若未重置（旧行为），下一次重连要等 4s 或被 64s 去重窗口挡住，本窗口内不会出现 attempts=2
            awaitCondition("重置后 2s 内执行重连", () -> Long.valueOf(2L).equals(attempts(client).get("node-b")), 3500);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed >= 1000 && elapsed < 3500,
                    "重连应在重置后约 2s 执行（而非旧退避 4s+），实际 " + elapsed + "ms");
        } finally {
            client.close();
        }
    }

    // ==================== 出站连接正常：零副作用 ====================

    @Test
    void peerAlive_whileOutboundConnected_noSideEffect() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try {
                    server.accept();
                } catch (IOException ignored) {
                    // 测试结束关闭连接属预期
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
            try {
                client.start(Map.of("node-b", new MeshBusClient.PeerEndpoint("127.0.0.1", server.getLocalPort())));
                awaitCondition("出站连接建立", () -> client.isConnected("node-b"), 3000);

                client.notifyPeerAlive("node-b");
                // 出站正常：不产生任何退避/调度记录
                assertNull(attempts(client).get("node-b"));
                assertNull(scheduled(client).get("node-b"));
            } finally {
                client.close();
            }
        }
    }

    // ==================== 未知 nodeId：不处理 ====================

    @Test
    void peerAlive_unknownNodeId_noOp() throws Exception {
        MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
        try {
            client.start(Map.of("node-b", new MeshBusClient.PeerEndpoint("127.0.0.1", DEAD_PORT)));
            client.notifyPeerAlive("unknown-node");
            assertNull(attempts(client).get("unknown-node"));
            assertNull(scheduled(client).get("unknown-node"));
        } finally {
            client.close();
        }
    }

    // ==================== 辅助 ====================

    @SuppressWarnings("unchecked")
    private static Map<String, Long> attempts(MeshBusClient client) {
        return fieldMap(client, "reconnectAttempts");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> scheduled(MeshBusClient client) {
        return fieldMap(client, "reconnectScheduled");
    }

    private static Map<String, Long> fieldMap(MeshBusClient client, String name) {
        try {
            java.lang.reflect.Field f = MeshBusClient.class.getDeclaredField(name);
            f.setAccessible(true);
            return (Map<String, Long>) f.get(client);
        } catch (Exception e) {
            throw new RuntimeException("反射读取字段失败: " + name, e);
        }
    }

    private static void awaitCondition(String desc, BooleanSupplier cond, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        fail("等待超时: " + desc);
    }
}
