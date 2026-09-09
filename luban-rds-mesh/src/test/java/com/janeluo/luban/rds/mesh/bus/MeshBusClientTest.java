package com.janeluo.luban.rds.mesh.bus;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
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

            // 等第一次连接失败触发退避调度（attempts≥1，延迟 2s；同容忍 loopback connect 挂满 5s 超时）
            awaitCondition("首次连接失败并调度退避", () -> {
                Long a = attempts(client).get("node-b");
                return a != null && a >= 1L;
            }, 8500);

            // 收到在线信号：退避从 2s 重新起步（清零后立即重新计数 1，并绕过 64s 去重窗口重新调度）
            long t0 = System.currentTimeMillis();
            client.notifyPeerAlive("node-b");
            assertTrue(scheduled(client).containsKey("node-b"), "在线信号应重新调度重连");

            // 重置后应尽快执行一次重连（再次失败 → attempts ≥2）。去重回归的硬护栏是上一行
            // "重新调度"断言；本段容忍 Windows 满载下 loopback connect 挂到 5s 连接超时：
            // 最迟可见 ≈ 2s(退避) + 5s(connect 超时) + 轮询余量 ≈ 8s（基线全量同测曾 7.6s 超时，既有 flake）。
            awaitCondition("重置后执行重连", () -> {
                Long a = attempts(client).get("node-b");
                return a != null && a >= 2L;
            }, 8500);
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed >= 1000 && elapsed < 8200,
                    "重连应在重置后约 2s 起步（非旧退避 4s+），实际 " + elapsed + "ms");
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

    // ==================== 写失败：立即关闭连接（9/3 事故 D1） ====================

    @Test
    void sendFailure_closesChannel() throws Exception {
        MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
        try {
            // EmbeddedChannel 模拟"channel 活跃但 writeAndFlush 失败"（编码 OOM/连接半死）：
            // pipeline 中放入一个必失败出站 handler
            io.netty.channel.embedded.EmbeddedChannel ch =
                    new io.netty.channel.embedded.EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter() {
                        @Override
                        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg,
                                          io.netty.channel.ChannelPromise promise) {
                            promise.setFailure(new RuntimeException("simulated direct memory OOM"));
                        }
                    });
            channels(client).put("node-b", ch);
            endpoints(client).put("node-b", new MeshBusClient.PeerEndpoint("127.0.0.1", 1));

            client.send("node-b", new MeshFrame("node-a", MessageType.REQUEST_VOTE.getCode(), new byte[0]));

            // EmbeddedChannel 的 promise listener 在调用线程同步执行，close 应已生效；轮询兜底
            long deadline = System.currentTimeMillis() + 1000;
            while (ch.isOpen() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(ch.isOpen(), "写失败后 channel 应被关闭");
        } finally {
            client.close();
        }
    }

    // ==================== 发往自身 nodeId：短路丢弃（9/9 事故 node-1936 WARN 风暴） ====================

    @Test
    void sendToSelf_isNoOp() {
        MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
        try {
            // 目标==自身 id（mis-config 下 raft 层会拿自身 id 当 leader 回包目标）：
            // 不进 channel 表、不建连、不抛异常
            client.send("node-a", new MeshFrame("node-a", MessageType.APPEND_ENTRIES.getCode(), new byte[0]));

            assertTrue(channels(client).isEmpty(), "发往自身不应产生任何连接表项");
            assertTrue(endpoints(client).isEmpty(), "发往自身不应触发重连端点注册");
        } finally {
            client.close();
        }
    }

    // ==================== isWritable：出站高水位探测 ====================

    @Test
    void isWritable_unknownOrUnconnectedPeer_returnsTrue() {
        MeshBusClient client = new MeshBusClient("node-a", new MeshBusHandler());
        try {
            assertTrue(client.isWritable("node-b"), "无 channel 记录时不因拥塞误判（留给 send 的未连接分支处理）");

            io.netty.channel.embedded.EmbeddedChannel unwritable =
                    new io.netty.channel.embedded.EmbeddedChannel(
                            new io.netty.channel.ChannelOutboundHandlerAdapter());
            unwritable.config().setWriteBufferWaterMark(
                    new io.netty.channel.WriteBufferWaterMark(1, 2));
            // 只 write 不 flush：ByteBuf 计入 pendingSize，越过 2B 高水位
            unwritable.write(io.netty.buffer.Unpooled.wrappedBuffer(new byte[100]));
            channels(client).put("node-b", unwritable);

            assertFalse(client.isWritable("node-b"), "channel 越过写高水位应判不可写");
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

    @SuppressWarnings("unchecked")
    private static Map<String, io.netty.channel.Channel> channels(MeshBusClient client) {
        try {
            java.lang.reflect.Field f = MeshBusClient.class.getDeclaredField("nodeChannels");
            f.setAccessible(true);
            return (Map<String, io.netty.channel.Channel>) f.get(client);
        } catch (Exception e) {
            throw new RuntimeException("反射读取字段失败: nodeChannels", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MeshBusClient.PeerEndpoint> endpoints(MeshBusClient client) {
        try {
            java.lang.reflect.Field f = MeshBusClient.class.getDeclaredField("nodeEndpoints");
            f.setAccessible(true);
            return (Map<String, MeshBusClient.PeerEndpoint>) f.get(client);
        } catch (Exception e) {
            throw new RuntimeException("反射读取字段失败: nodeEndpoints", e);
        }
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
