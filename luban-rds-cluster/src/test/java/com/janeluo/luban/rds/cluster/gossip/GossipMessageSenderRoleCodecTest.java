package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PING/PONG/MEET 消息头新增发送方角色字段的二进制编解码回归测试。
 * <p>
 * 历史 bug：CLUSTER REPLICATE 后从节点角色无法经 Gossip 传播，根因是消息头未携带
 * 发送方角色。修复新增 {@code senderConfigEpoch}/{@code senderFlags}/{@code senderMasterNodeId}
 * 三个字段。本测试用非默认值覆盖这三个字段的 encode→decode round-trip，防止二进制
 * 偏移/长度计算回归导致集群总线连接被 {@code ctx.close()}（解码异常即断连）。
 * </p>
 */
class GossipMessageSenderRoleCodecTest {

    private static final String SENDER_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final String MASTER_ID = "fedcba9876543210fedcba9876543210fedcba98";

    @Test
    void pingMessageRoundTripWithSenderRole() {
        PingMessage ping = new PingMessage(SENDER_ID, 1234567890L);
        BitSet slots = new BitSet();
        slots.set(0);
        slots.set(16383);
        ping.setSenderSlots(slots);
        ping.setSenderConfigEpoch(42L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        ping.setSenderMasterNodeId(MASTER_ID);

        byte[] encoded = ping.encode();
        PingMessage decoded = (PingMessage) GossipMessage.parseMessage(encoded);

        assertEquals(1234567890L, decoded.getPingTime());
        assertNotNull(decoded.getSenderSlots());
        assertEquals(2, decoded.getSenderSlots().cardinality());
        assertTrue(decoded.getSenderSlots().get(0));
        assertTrue(decoded.getSenderSlots().get(16383));
        assertEquals(42L, decoded.getSenderConfigEpoch());
        assertEquals(1, decoded.getSenderFlags().size());
        assertTrue(decoded.getSenderFlags().contains(ClusterNodeState.SLAVE));
        assertEquals(MASTER_ID, decoded.getSenderMasterNodeId());
    }

    @Test
    void pingMessageRoundTripMasterRoleNoMasterNodeId() {
        PingMessage ping = new PingMessage(SENDER_ID, 1L);
        ping.setSenderConfigEpoch(7L);
        ping.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        // 主节点 masterNodeId 为 null，验证 null 分支编解码

        byte[] encoded = ping.encode();
        PingMessage decoded = (PingMessage) GossipMessage.parseMessage(encoded);

        assertEquals(7L, decoded.getSenderConfigEpoch());
        assertTrue(decoded.getSenderFlags().contains(ClusterNodeState.MASTER));
        assertNull(decoded.getSenderMasterNodeId());
    }

    @Test
    void pongMessageRoundTripWithSenderRole() {
        PongMessage pong = new PongMessage(SENDER_ID, 9876543210L);
        BitSet slots = new BitSet();
        slots.set(100);
        pong.setSenderSlots(slots);
        pong.setSenderConfigEpoch(99L);
        pong.setSenderFlags(EnumSet.of(ClusterNodeState.MASTER));
        // 主节点无 masterNodeId

        byte[] encoded = pong.encode();
        PongMessage decoded = (PongMessage) GossipMessage.parseMessage(encoded);

        assertEquals(9876543210L, decoded.getPongTime());
        assertNotNull(decoded.getSenderSlots());
        assertTrue(decoded.getSenderSlots().get(100));
        assertEquals(99L, decoded.getSenderConfigEpoch());
        assertTrue(decoded.getSenderFlags().contains(ClusterNodeState.MASTER));
        assertNull(decoded.getSenderMasterNodeId());
    }

    @Test
    void meetMessageRoundTripWithSenderRole() {
        MeetMessage meet = new MeetMessage(SENDER_ID, "192.168.1.1", 7000, 17000, 55L, 77L);
        BitSet slots = new BitSet();
        slots.set(500);
        meet.setSenderSlots(slots);
        meet.setSenderFlags(EnumSet.of(ClusterNodeState.SLAVE));
        meet.setSenderMasterNodeId(MASTER_ID);

        byte[] encoded = meet.encode();
        MeetMessage decoded = (MeetMessage) GossipMessage.parseMessage(encoded);

        assertEquals("192.168.1.1", decoded.getSenderIp());
        assertEquals(7000, decoded.getSenderPort());
        assertEquals(17000, decoded.getSenderBusPort());
        assertEquals(55L, decoded.getSenderConfigEpoch());
        assertEquals(77L, decoded.getCurrentEpoch());
        assertNotNull(decoded.getSenderSlots());
        assertTrue(decoded.getSenderSlots().get(500));
        assertTrue(decoded.getSenderFlags().contains(ClusterNodeState.SLAVE));
        assertEquals(MASTER_ID, decoded.getSenderMasterNodeId());
    }

    @Test
    void emptySenderFlagsRoundTrip() {
        // buildSenderFlags 在节点既非 master 也非 slave 时返回空集，验证空集编解码
        PingMessage ping = new PingMessage(SENDER_ID, 1L);
        Set<ClusterNodeState> empty = EnumSet.noneOf(ClusterNodeState.class);
        ping.setSenderFlags(empty);

        byte[] encoded = ping.encode();
        PingMessage decoded = (PingMessage) GossipMessage.parseMessage(encoded);

        assertTrue(decoded.getSenderFlags().isEmpty());
    }
}
