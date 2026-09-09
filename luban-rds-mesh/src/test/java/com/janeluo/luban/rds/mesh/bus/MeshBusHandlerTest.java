package com.janeluo.luban.rds.mesh.bus;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link MeshBusHandler} 入站分发单元测试。
 * <p>
 * 覆盖 9/9 生产事故防线：收到 {@code senderNodeId == 本节点 id} 的帧 = mesh 身份冲突
 * （两台机器配置了同一个 mesh-node-id），必须丢弃并告警，绝不转发给 Raft 层——
 * 否则冲突双方的投票/复制响应互相错记，matchIndex 永不推进，leader 积压补发打爆直接内存。
 * </p>
 */
class MeshBusHandlerTest {

    private static final byte AE = MessageType.APPEND_ENTRIES.getCode();

    private static MeshFrame frame(String sender) {
        return new MeshFrame(sender, AE, "ping".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void inboundFrame_withSelfSenderId_isDropped() {
        MeshBusHandler handler = new MeshBusHandler();
        handler.setSelfNodeId("nodeA");
        AtomicReference<String> received = new AtomicReference<>();
        handler.setMessageConsumer((from, f) -> received.set(from));

        EmbeddedChannel ch = new EmbeddedChannel(handler);
        ch.writeInbound(frame("nodeA"));

        assertNull(received.get(), "sender==自身 id 的帧不得转发给消费者（身份冲突帧）");
        ch.finish();
    }

    @Test
    void inboundFrame_withOtherSender_isDelivered() {
        MeshBusHandler handler = new MeshBusHandler();
        handler.setSelfNodeId("nodeA");
        AtomicReference<MeshFrame> received = new AtomicReference<>();
        handler.setMessageConsumer((from, f) -> received.set(f));

        EmbeddedChannel ch = new EmbeddedChannel(handler);
        MeshFrame f = frame("nodeB");
        ch.writeInbound(f);

        assertNotNull(received.get(), "正常 peer 的帧必须转发");
        assertEquals("nodeB", received.get().getSenderNodeId());
        ch.finish();
    }

    @Test
    void inboundFrame_withoutSelfNodeId_deliversAsBefore() {
        // 未接线 selfNodeId（测试/旧构造路径）：保持既有行为，不误伤
        MeshBusHandler handler = new MeshBusHandler();
        AtomicReference<String> received = new AtomicReference<>();
        handler.setMessageConsumer((from, f) -> received.set(from));

        EmbeddedChannel ch = new EmbeddedChannel(handler);
        ch.writeInbound(frame("nodeA"));

        assertEquals("nodeA", received.get());
        ch.finish();
    }
}
