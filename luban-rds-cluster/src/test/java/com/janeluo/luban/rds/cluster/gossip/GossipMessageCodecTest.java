package com.janeluo.luban.rds.cluster.gossip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;

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
}
