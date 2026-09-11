package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-8（2026-09-11 mesh 审计）：LeaseInvalidException 补专用 catch 转 -TRYAGAIN。
 * <p>
 * 租约失效等待超时/read-index 确认失败此前落通用 catch 变 {@code -ERR Error handling command}
 * ——Redisson 对 ERR 不重试，租约抖动直接变业务失败（与 8/7 修过的 ERR→TRYAGAIN 同类，当时漏了此异常）。
 * 用 gate 抛 LeaseInvalidException 的可控 stub 驱动 handler catch 链。
 * </p>
 */
class LeaseInvalidTryagainTest {

    private MeshNode node;
    private EmbeddedChannel channel;

    /** 读恒抛 LeaseInvalidException 的 gate stub（写路径不可达——GET 走读）。 */
    private static class LeaseInvalidGate extends MeshWriteGate {
        LeaseInvalidGate(MeshNode node, MemoryStore store, DefaultCommandHandler handler) {
            super(node, store, handler, (MeshConfig) null);
        }
        @Override
        public byte[] read(int dbIndex, String[] args) {
            throw new LeaseInvalidException("mesh leader lease expired (lease mode), please retry");
        }
    }

    @BeforeEach
    void setUp() {
        // MeshNode 仅用于 gate 构造（read 被覆写，leader/lease 判定不触达）
        MeshBusClient bus = new MeshBusClient("nodeA", new MeshBusHandler()) {
        };
        node = new MeshNode(MeshConfig.builder("nodeA").addPeer("nodeB", "127.0.0.1:11001").build(), new com.janeluo.luban.rds.mesh.core.MeshState(), bus);

        MemoryStore store = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();
        RedisServerHandler serverHandler = new RedisServerHandler(store, handler, new RedisProtocolParser(), 0);
        serverHandler.setMeshEnabled(true);
        serverHandler.setMeshWriteGate(new LeaseInvalidGate(node, store, handler));
        channel = new EmbeddedChannel(serverHandler);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
        if (node != null) {
            node.stop();
        }
    }

    @Test
    void leaseInvalidRead_returnsTryagain() {
        String resp = sendCommand("GET", "k1");
        assertTrue(resp != null && resp.startsWith("-TRYAGAIN"),
                "租约失效读应返回 -TRYAGAIN（客户端可自动重试），实际: " + resp);
        assertTrue(resp.contains("lease expired"), "错误消息应含原因: " + resp);
    }

    private String sendCommand(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            sb.append("$").append(part.length()).append("\r\n").append(part).append("\r\n");
        }
        ByteBuf input = Unpooled.copiedBuffer(sb.toString(), StandardCharsets.UTF_8);
        channel.writeInbound(input);
        channel.flush();
        ByteBuf response = channel.readOutbound();
        if (response != null) {
            String s = response.toString(StandardCharsets.UTF_8);
            response.release();
            return s;
        }
        return null;
    }
}
