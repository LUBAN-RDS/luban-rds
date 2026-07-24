package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Gossip 消息二进制编解码往返测试
 * <p>
 * 验证改用 ClusterBusCodec 后，各消息的 senderSlots / inheritedSlots 等关键字段
 * 经 encode -> parseMessage 往返不丢失。
 * </p>
 */
class GossipMessageCodecTest {

    private static final String SENDER_ID = "aabbccddeeff00112233445566778899aabbccdd";

    @Test
    @DisplayName("PingMessage encode/parseMessage 往返 senderSlots 不丢失")
    void testPingMessageRoundTrip() {
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        BitSet slots = new BitSet(16384);
        slots.set(0);
        slots.set(5460);
        slots.set(16383);
        ping.setSenderSlots(slots);

        byte[] encoded = ping.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        assertEquals(GossipMessageType.PING, decoded.getType());
        assertEquals(SENDER_ID, decoded.getSenderNodeId());
        PingMessage decodedPing = (PingMessage) decoded;
        assertEquals(ping.getPingTime(), decodedPing.getPingTime());
        assertNotNull(decodedPing.getSenderSlots());
        assertEquals(slots, decodedPing.getSenderSlots());
        assertEquals(3, decodedPing.getSenderSlots().cardinality());
    }

    @Test
    @DisplayName("PingMessage senderSlots 为 null 时往返不报错且解码为 null")
    void testPingMessageNullSlotsRoundTrip() {
        PingMessage ping = new PingMessage(SENDER_ID, 100L);
        // senderSlots 不设置（null）

        byte[] encoded = ping.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        PingMessage decodedPing = (PingMessage) decoded;
        assertEquals(SENDER_ID, decodedPing.getSenderNodeId());
        assertNull(decodedPing.getSenderSlots());
    }

    @Test
    @DisplayName("PongMessage encode/parseMessage 往返 senderSlots 不丢失")
    void testPongMessageRoundTrip() {
        PongMessage pong = new PongMessage(SENDER_ID, 200L);
        BitSet slots = new BitSet(16384);
        slots.set(1);
        slots.set(8192);
        pong.setSenderSlots(slots);

        byte[] encoded = pong.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        assertEquals(GossipMessageType.PONG, decoded.getType());
        PongMessage decodedPong = (PongMessage) decoded;
        assertEquals(pong.getPongTime(), decodedPong.getPongTime());
        assertNotNull(decodedPong.getSenderSlots());
        assertEquals(slots, decodedPong.getSenderSlots());
    }

    @Test
    @DisplayName("MeetMessage encode/parseMessage 往返 senderSlots 不丢失")
    void testMeetMessageRoundTrip() {
        MeetMessage meet = new MeetMessage(SENDER_ID, "127.0.0.1", 7000, 17000, 7L, 10L);
        BitSet slots = new BitSet(16384);
        slots.set(100);
        slots.set(200);
        meet.setSenderSlots(slots);

        byte[] encoded = meet.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        assertEquals(GossipMessageType.MEET, decoded.getType());
        MeetMessage decodedMeet = (MeetMessage) decoded;
        assertEquals(SENDER_ID, decodedMeet.getSenderNodeId());
        assertEquals("127.0.0.1", decodedMeet.getSenderIp());
        assertEquals(7000, decodedMeet.getSenderPort());
        assertEquals(17000, decodedMeet.getSenderBusPort());
        assertEquals(7L, decodedMeet.getSenderConfigEpoch());
        assertEquals(10L, decodedMeet.getCurrentEpoch());
        assertNotNull(decodedMeet.getSenderSlots());
        assertEquals(slots, decodedMeet.getSenderSlots());
    }

    @Test
    @DisplayName("FailoverResultMessage encode/parseMessage 往返 inheritedSlots 不丢失")
    void testFailoverResultMessageRoundTrip() {
        BitSet inherited = new BitSet(16384);
        inherited.set(0);
        inherited.set(5460);
        inherited.set(10922);

        FailoverResultMessage result = new FailoverResultMessage(
                SENDER_ID, SENDER_ID, 42L, inherited);

        byte[] encoded = result.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        assertEquals(GossipMessageType.FAILOVER_RESULT, decoded.getType());
        FailoverResultMessage decodedResult = (FailoverResultMessage) decoded;
        assertEquals(SENDER_ID, decodedResult.getWinnerNodeId());
        assertEquals(42L, decodedResult.getNewConfigEpoch());
        assertNotNull(decodedResult.getInheritedSlots());
        assertEquals(inherited, decodedResult.getInheritedSlots());
    }

    @Test
    @DisplayName("PingMessage 携带 senderCurrentEpoch 往返不丢失")
    void testPingMessageCarriesCurrentEpoch() {
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(7L);
        ping.setSenderCurrentEpoch(9L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));

        byte[] encoded = ping.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        PingMessage decodedPing = (PingMessage) decoded;
        assertEquals(9L, decodedPing.getSenderCurrentEpoch(),
                "解码后 senderCurrentEpoch 应与编码一致");
        assertEquals(7L, decodedPing.getSenderConfigEpoch(),
                "senderConfigEpoch 应保持一致");
    }

    @Test
    @DisplayName("PongMessage 携带 senderCurrentEpoch 往返不丢失")
    void testPongMessageCarriesCurrentEpoch() {
        PongMessage pong = new PongMessage(SENDER_ID, System.currentTimeMillis());
        pong.setSenderCurrentEpoch(11L);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));

        byte[] encoded = pong.encode();
        GossipMessage decoded = GossipMessage.parseMessage(encoded);

        PongMessage decodedPong = (PongMessage) decoded;
        assertEquals(11L, decodedPong.getSenderCurrentEpoch());
    }

    @Test
    @DisplayName("旧版本 PING 消息（无 senderCurrentEpoch 字段）解码向后兼容")
    void testPingMessageBackwardCompatibleWithoutCurrentEpoch() {
        // 构造完整消息后裁掉尾部 8 字节（senderCurrentEpoch 为消息体最后 8 字节），
        // 并同步修正头部的 bodyLength 字段，模拟旧版本无 senderCurrentEpoch 字段的报文。
        PingMessage ping = new PingMessage(SENDER_ID, System.currentTimeMillis());
        ping.setSenderConfigEpoch(5L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        byte[] fullEncoded = ping.encode();

        // 头部：40(id) + 1(type) + 4(length)，length 字节位于 [41,45)
        int bodyLengthOffset = GossipMessage.NODE_ID_LENGTH + 1;
        int originalBodyLength = fullEncoded.length - GossipMessage.HEADER_LENGTH;
        int truncatedBodyLength = originalBodyLength - 8;

        byte[] truncated = new byte[GossipMessage.HEADER_LENGTH + truncatedBodyLength];
        System.arraycopy(fullEncoded, 0, truncated, 0, truncated.length);
        // 用截断后的 bodyLength 覆盖头部 length 字段，使 decode() 长度校验通过
        truncated[bodyLengthOffset] = (byte) (truncatedBodyLength >> 24);
        truncated[bodyLengthOffset + 1] = (byte) (truncatedBodyLength >> 16);
        truncated[bodyLengthOffset + 2] = (byte) (truncatedBodyLength >> 8);
        truncated[bodyLengthOffset + 3] = (byte) truncatedBodyLength;

        // 不应抛异常，senderCurrentEpoch 保持默认值 0
        PingMessage decoded = new PingMessage();
        decoded.decode(truncated);

        assertEquals(0L, decoded.getSenderCurrentEpoch(),
                "旧版本消息解码后 senderCurrentEpoch 应为默认值 0");
        assertEquals(5L, decoded.getSenderConfigEpoch(),
                "其他字段应正常解码");
    }
}
