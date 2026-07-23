package com.janeluo.luban.rds.cluster.gossip;

import com.janeluo.luban.rds.cluster.node.ClusterNodeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GossipNodeInfo 单元测试
 */
class GossipNodeInfoTest {

    @Test
    @DisplayName("slots 字段 encode/decode 往返一致")
    void testSlotsEncodeDecodeRoundTrip() {
        GossipNodeInfo info = new GossipNodeInfo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        info.setIp("127.0.0.1");
        info.setPort(7000);
        info.setBusPort(17000);
        info.setConfigEpoch(3L);
        info.setFlags(EnumSet.of(ClusterNodeState.MASTER));

        BitSet slots = new BitSet(16384);
        slots.set(0);
        slots.set(5460);
        slots.set(10922);
        slots.set(16383);
        info.setSlots(slots);
        info.setMasterNodeId("dddddddddddddddddddddddddddddddddddddddd");

        byte[] encoded = info.encode();
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);

        GossipNodeInfo decoded = new GossipNodeInfo();
        decoded.decode(encoded, 0);

        assertEquals(info.getNodeId(), decoded.getNodeId());
        assertEquals(info.getIp(), decoded.getIp());
        assertEquals(info.getPort(), decoded.getPort());
        assertEquals(info.getBusPort(), decoded.getBusPort());
        assertEquals(info.getConfigEpoch(), decoded.getConfigEpoch());
        assertNotNull(decoded.getSlots());
        assertEquals(slots, decoded.getSlots());
        assertEquals(4, decoded.getSlots().cardinality());
        assertTrue(decoded.getSlots().get(0));
        assertTrue(decoded.getSlots().get(16383));
        assertEquals(info.getMasterNodeId(), decoded.getMasterNodeId());
    }

    @Test
    @DisplayName("slots 为 null 时 encode/decode 不报错且解码后为 null")
    void testSlotsNullEncodeDecode() {
        GossipNodeInfo info = new GossipNodeInfo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        info.setIp("10.0.0.1");
        info.setPort(7001);
        info.setBusPort(17001);
        info.setConfigEpoch(0L);
        info.setFlags(EnumSet.noneOf(ClusterNodeState.class));
        // slots 为 null

        byte[] encoded = info.encode();

        GossipNodeInfo decoded = new GossipNodeInfo();
        decoded.decode(encoded, 0);

        assertEquals(info.getNodeId(), decoded.getNodeId());
        assertNull(decoded.getSlots());
    }

    @Test
    @DisplayName("getEncodedLength 与 encode 实际长度一致")
    void testGetEncodedLengthMatches() {
        GossipNodeInfo info = new GossipNodeInfo("cccccccccccccccccccccccccccccccccccccccc");
        info.setIp("127.0.0.1");
        info.setPort(7002);
        info.setBusPort(17002);
        info.setConfigEpoch(1L);
        info.setFlags(EnumSet.of(ClusterNodeState.MASTER, ClusterNodeState.FAIL));

        BitSet slots = new BitSet();
        slots.set(100);
        slots.set(200);
        info.setSlots(slots);

        assertEquals(info.encode().length, info.getEncodedLength());
    }

    @Test
    @DisplayName("masterNodeId 为 null 时 encode/decode 往返一致")
    void testMasterNodeIdNullRoundTrip() {
        GossipNodeInfo info = new GossipNodeInfo("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");
        info.setIp("192.168.1.1");
        info.setPort(7003);
        info.setBusPort(17003);
        info.setConfigEpoch(5L);
        info.setFlags(EnumSet.of(ClusterNodeState.SLAVE));
        info.setMasterNodeId(null);

        byte[] encoded = info.encode();
        GossipNodeInfo decoded = new GossipNodeInfo();
        decoded.decode(encoded, 0);

        assertEquals(info.getNodeId(), decoded.getNodeId());
        assertNull(decoded.getMasterNodeId());
        assertEquals(info.getEncodedLength(), encoded.length);
    }
}
