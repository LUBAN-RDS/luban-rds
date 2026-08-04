package com.janeluo.luban.rds.mesh.bus;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshBusCodec} 编解码单元测试（基于 {@link EmbeddedChannel}）。
 * <p>
 * 覆盖：往返一致性、半包（部分帧头/部分 body 不产出，补齐后产出）、超长 body
 * （Encoder 丢弃 + Decoder 非法 length 关闭连接）、type 非法关闭连接、空 body 帧。
 * </p>
 */
class MeshBusCodecTest {

    /** 40 字符 hex nodeId（满足帧头 40B 要求） */
    private static final String NODE_ID = "0123456789abcdef0123456789abcdef01234567";

    private static String nodeId() {
        return NODE_ID;
    }

    // ==================== 往返一致性 ====================

    @Test
    void roundtrip_normalFrame_fieldsPreserved() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new MeshBusCodec.Encoder(), new MeshBusCodec.Decoder());

        byte[] body = randomBody(128);
        MeshFrame original = new MeshFrame(nodeId(), MessageType.APPEND_ENTRIES.getCode(), body);

        assertTrue(channel.writeOutbound(original));
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded, "Encoder 应产出 outbound 字节");

        // 喂回 inbound（解码）
        assertTrue(channel.writeInbound(encoded));
        MeshFrame decoded = channel.readInbound();

        assertNotNull(decoded, "Decoder 应产出 MeshFrame");
        assertEquals(nodeId(), decoded.getSenderNodeId());
        assertEquals(MessageType.APPEND_ENTRIES.getCode(), decoded.getType());
        assertArrayEquals(body, decoded.getBody());

        channel.finish();
    }

    @Test
    void roundtrip_emptyBody() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new MeshBusCodec.Encoder(), new MeshBusCodec.Decoder());

        MeshFrame original = new MeshFrame(nodeId(),
                MessageType.REQUEST_VOTE.getCode(), new byte[0]);

        assertTrue(channel.writeOutbound(original));
        ByteBuf encoded = channel.readOutbound();
        // 帧头 45B，body 0
        assertEquals(MeshFrame.HEADER_LENGTH, encoded.readableBytes());

        assertTrue(channel.writeInbound(encoded));
        MeshFrame decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(0, decoded.getBodyLength());
        assertEquals(MessageType.REQUEST_VOTE.getCode(), decoded.getType());

        channel.finish();
    }

    @Test
    void roundtrip_frameLength_is_45_header_plus_body() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Encoder());

        int bodyLen = 50;
        MeshFrame frame = new MeshFrame(nodeId(),
                (byte) MessageType.INSTALL_SNAPSHOT.getCode(), randomBody(bodyLen));
        channel.writeOutbound(frame);
        ByteBuf encoded = channel.readOutbound();
        assertEquals(MeshFrame.HEADER_LENGTH + bodyLen, encoded.readableBytes());

        // 验证帧头前 40B 为 nodeId ASCII
        byte[] nodeIdOut = new byte[MeshFrame.NODE_ID_LENGTH];
        encoded.getBytes(0, nodeIdOut);
        assertEquals(nodeId(), new String(nodeIdOut, StandardCharsets.US_ASCII));
        // 第 41B 为 type
        assertEquals((byte) MessageType.INSTALL_SNAPSHOT.getCode(),
                encoded.getByte(MeshFrame.NODE_ID_LENGTH));
        // 第 42-45B 为大端 length
        int len = encoded.getInt(MeshFrame.NODE_ID_LENGTH + 1);
        assertEquals(bodyLen, len);

        channel.finish();
    }

    // ==================== 半包处理 ====================

    @Test
    void halfFrame_partialHeader_producesNothing() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Decoder());

        // 仅写 30 字节（< 45B 帧头），不应产出
        ByteBuf partial = Unpooled.buffer(30);
        partial.writeBytes(new byte[30]);
        channel.writeInbound(partial);
        assertNull(channel.readInbound(), "部分帧头不应产出 MeshFrame");
        channel.finish();
    }

    @Test
    void halfFrame_headerOnly_withoutBody_producesNothing_thenProducesWhenComplete() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Decoder());

        byte[] body = randomBody(64);
        byte[] frame = buildRawFrame(nodeId(),
                MessageType.APPEND_ENTRIES_RESP.getCode(), body);

        // 只写前 45B 帧头 + 10B body（body 未齐）
        int split = MeshFrame.HEADER_LENGTH + 10;
        ByteBuf first = Unpooled.wrappedBuffer(frame, 0, split);
        channel.writeInbound(first);
        assertNull(channel.readInbound(), "body 未齐不应产出");

        // 补齐剩余 body
        ByteBuf second = Unpooled.wrappedBuffer(frame, split, frame.length - split);
        channel.writeInbound(second);
        MeshFrame decoded = channel.readInbound();
        assertNotNull(decoded, "补齐后应产出");
        assertArrayEquals(body, decoded.getBody());
        assertEquals(MessageType.APPEND_ENTRIES_RESP.getCode(), decoded.getType());

        channel.finish();
    }

    // ==================== 粘包（多帧一次到） ====================

    @Test
    void multipleFrames_inOneBuffer_allDecoded() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Decoder());

        byte[] body1 = randomBody(20);
        byte[] body2 = randomBody(30);
        byte[] f1 = buildRawFrame(nodeId(), MessageType.REQUEST_VOTE.getCode(), body1);
        byte[] f2 = buildRawFrame(nodeId(), MessageType.REQUEST_VOTE_RESP.getCode(), body2);

        byte[] combined = new byte[f1.length + f2.length];
        System.arraycopy(f1, 0, combined, 0, f1.length);
        System.arraycopy(f2, 0, combined, f1.length, f2.length);

        channel.writeInbound(Unpooled.wrappedBuffer(combined));
        MeshFrame d1 = channel.readInbound();
        MeshFrame d2 = channel.readInbound();
        assertNotNull(d1);
        assertNotNull(d2);
        assertArrayEquals(body1, d1.getBody());
        assertArrayEquals(body2, d2.getBody());
        assertNull(channel.readInbound(), "不应有第三帧");
        channel.finish();
    }

    // ==================== 超长 body ====================

    @Test
    void encoder_dropsOverlongBody_noReadableBytes() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Encoder());

        // body 超过 16MB：Encoder 预检后不写帧，MessageToByteEncoder 发出 EMPTY_BUFFER
        byte[] overlong = new byte[MeshFrame.MAX_BODY_LENGTH + 1];
        MeshFrame frame = new MeshFrame(nodeId(),
                MessageType.APPEND_ENTRIES.getCode(), overlong);

        channel.writeOutbound(frame);
        ByteBuf out = channel.readOutbound();
        // 编码预检丢弃：out 要么为 null，要么为无可读字节的空 buffer（EMPTY_BUFFER）
        assertTrue(out == null || out.readableBytes() == 0,
                "超限帧应被 Encoder 丢弃，无可读 outbound 字节");
        channel.finish();
    }

    @Test
    void decoder_illegalLength_closesConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Decoder());

        // 手工构造一个 length = Integer.MAX_VALUE 的帧头（非法，> 16MB）
        ByteBuf bad = Unpooled.buffer(MeshFrame.HEADER_LENGTH);
        bad.writeBytes(nodeId().getBytes(StandardCharsets.US_ASCII));   // 40B
        bad.writeByte(MessageType.APPEND_ENTRIES.getCode());            // 1B
        bad.writeInt(Integer.MAX_VALUE);                                // 4B 大端非法 length

        channel.writeInbound(bad);
        // 非法 length → Decoder 调 ctx.close()，无帧产出，通道关闭
        assertNull(channel.readInbound(), "非法 length 不应产出 MeshFrame");
        assertFalse(channel.isOpen(), "非法 length 应关闭连接");
        channel.finishAndReleaseAll();
    }

    @Test
    void decoder_unknownType_closesConnection() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Decoder());

        ByteBuf bad = Unpooled.buffer(MeshFrame.HEADER_LENGTH);
        bad.writeBytes(nodeId().getBytes(StandardCharsets.US_ASCII));   // 40B
        bad.writeByte((byte) 0x99);                                     // 1B 非法 type
        bad.writeInt(0);                                                // 4B length=0

        channel.writeInbound(bad);
        assertNull(channel.readInbound(), "未知 type 不应产出 MeshFrame");
        assertFalse(channel.isOpen(), "未知 type 应关闭连接");
        channel.finishAndReleaseAll();
    }

    // ==================== 短 nodeId 补齐（修复 senderNodeId 长度非法丢弃帧） ====================

    /**
     * P0 回归：人工可读的短 nodeId（如 "node-9736"）编解码 round-trip 必须还原正确。
     * 修复前 Encoder 因长度 != 40 直接丢弃本帧，导致 PreVote/选举消息无法到达 peer，集群瘫痪。
     */
    @Test
    void roundtrip_shortNodeId_humanReadable_preserved() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new MeshBusCodec.Encoder(), new MeshBusCodec.Decoder());

        String shortNodeId = "node-9736";   // 9 字符，远短于 40
        byte[] body = randomBody(64);
        MeshFrame original = new MeshFrame(shortNodeId,
                MessageType.REQUEST_VOTE.getCode(), body);

        assertTrue(channel.writeOutbound(original));
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded, "短 nodeId 帧不应被 Encoder 丢弃");
        assertEquals(MeshFrame.HEADER_LENGTH + body.length, encoded.readableBytes());

        assertTrue(channel.writeInbound(encoded));
        MeshFrame decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(shortNodeId, decoded.getSenderNodeId(), "短 nodeId 解码后应 trim NUL 还原");
        assertEquals(MessageType.REQUEST_VOTE.getCode(), decoded.getType());
        assertArrayEquals(body, decoded.getBody());

        channel.finish();
    }

    @Test
    void encoder_shortNodeId_paddedWithNulTo40Bytes() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Encoder());

        String shortNodeId = "node-9738";   // 9 字符
        MeshFrame frame = new MeshFrame(shortNodeId,
                MessageType.APPEND_ENTRIES.getCode(), randomBody(10));

        channel.writeOutbound(frame);
        ByteBuf encoded = channel.readOutbound();
        assertNotNull(encoded);
        assertEquals(MeshFrame.HEADER_LENGTH + 10, encoded.readableBytes());

        // 前 9B 为 nodeId ASCII，后 31B 为 NUL 填充
        byte[] nodeIdField = new byte[MeshFrame.NODE_ID_LENGTH];
        encoded.getBytes(0, nodeIdField);
        byte[] expected = new byte[MeshFrame.NODE_ID_LENGTH];
        byte[] raw = shortNodeId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(raw, 0, expected, 0, raw.length);
        // 其余字节为 0x00
        assertArrayEquals(expected, nodeIdField, "短 nodeId 应右填 NUL 补齐到 40B");

        channel.finish();
    }

    @Test
    void encoder_overlongNodeId_dropped() {
        EmbeddedChannel channel = new EmbeddedChannel(new MeshBusCodec.Encoder());

        // 41 字符 nodeId（超过 40B 上限）应被丢弃
        String overlongId = "0123456789abcdef0123456789abcdef012345678";  // 41 chars
        assertEquals(41, overlongId.length());
        MeshFrame frame = new MeshFrame(overlongId,
                MessageType.APPEND_ENTRIES.getCode(), randomBody(10));

        channel.writeOutbound(frame);
        ByteBuf out = channel.readOutbound();
        assertTrue(out == null || out.readableBytes() == 0,
                "超长 nodeId 帧应被 Encoder 丢弃");
        channel.finish();
    }

    @Test
    void roundtrip_exactly40CharNodeId_preserved() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new MeshBusCodec.Encoder(), new MeshBusCodec.Decoder());

        // 恰好 40 字符（边界值，无填充）
        MeshFrame original = new MeshFrame(NODE_ID,
                MessageType.APPEND_ENTRIES.getCode(), randomBody(32));

        assertTrue(channel.writeOutbound(original));
        ByteBuf encoded = channel.readOutbound();
        assertTrue(channel.writeInbound(encoded));
        MeshFrame decoded = channel.readInbound();
        assertNotNull(decoded);
        assertEquals(NODE_ID, decoded.getSenderNodeId());
        channel.finish();
    }

    // ==================== MessageType ====================

    @Test
    void messageType_fromCode_validAndInvalid() {
        assertEquals(MessageType.APPEND_ENTRIES, MessageType.fromCode((byte) 0x60));
        assertEquals(MessageType.INSTALL_SNAPSHOT, MessageType.fromCode((byte) 0x64));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MessageType.fromCode((byte) 0x40));   // cluster 码段
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MessageType.fromCode((byte) 0x99));
    }

    // ==================== helpers ====================

    private static byte[] randomBody(int len) {
        byte[] b = new byte[len];
        new Random(len).nextBytes(b);
        return b;
    }

    /** 手工构造完整帧字节（与 Encoder 产物同构），用于半包/粘包测试。 */
    private static byte[] buildRawFrame(String senderNodeId, byte type, byte[] body) {
        byte[] data = new byte[MeshFrame.HEADER_LENGTH + body.length];
        int off = 0;
        byte[] idBytes = senderNodeId.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(idBytes, 0, data, off, MeshFrame.NODE_ID_LENGTH);
        off += MeshFrame.NODE_ID_LENGTH;
        data[off++] = type;
        int len = body.length;
        data[off++] = (byte) (len >>> 24);
        data[off++] = (byte) (len >>> 16);
        data[off++] = (byte) (len >>> 8);
        data[off++] = (byte) len;
        System.arraycopy(body, 0, data, off, body.length);
        return data;
    }
}
