package com.janeluo.luban.rds.cluster.gossip;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GossipMessageType 消息码单元测试（N-8）
 * <p>
 * 消息码从 0x40 起编号，避开 Redis 7 集群总线已占用的 0x00-0x21 码段，
 * 防止混布时消息误判（如本实现 FAILOVER_RESULT 曾占用 Redis 的 MFSTART 码）。
 * </p>
 */
class GossipMessageTypeTest {

    @Test
    @DisplayName("消息码从 0x40 起顺序编号，不占用 Redis 0x00-0x21 码段")
    void testCodesStartAt0x40AndSequential() {
        GossipMessageType[] types = GossipMessageType.values();
        assertEquals(13, types.length, "当前共 13 种消息类型");

        for (int i = 0; i < types.length; i++) {
            final int expected = 0x40 + i;
            final GossipMessageType type = types[i];
            assertEquals((byte) expected, type.getCode(),
                    () -> type.name() + " 应编码为 0x" + Integer.toHexString(expected));
            // 不与 Redis 7 已占用码段（0x00-0x21）重叠
            assertTrue(expected >= 0x40, type.name() + " 码值必须 >= 0x40");
        }
    }

    @Test
    @DisplayName("fromCode 与 isValidCode 往返一致")
    void testFromCodeRoundTrip() {
        for (GossipMessageType type : GossipMessageType.values()) {
            assertTrue(GossipMessageType.isValidCode(type.getCode()));
            assertSame(type, GossipMessageType.fromCode(type.getCode()));
        }

        // Redis 码段内的值不应被识别为合法消息
        assertFalse(GossipMessageType.isValidCode((byte) 0x00));
        assertFalse(GossipMessageType.isValidCode((byte) 0x08));
        assertFalse(GossipMessageType.isValidCode((byte) 0x21));
        assertNull(GossipMessageType.fromCode((byte) 0x05));
    }

    @Test
    @DisplayName("各消息码与预期十六进制值一致（防误改回归保护）")
    void testExactCodeValues() {
        assertEquals((byte) 0x40, GossipMessageType.PING.getCode());
        assertEquals((byte) 0x41, GossipMessageType.PONG.getCode());
        assertEquals((byte) 0x42, GossipMessageType.MEET.getCode());
        assertEquals((byte) 0x43, GossipMessageType.FAIL.getCode());
        assertEquals((byte) 0x44, GossipMessageType.PUBLISH.getCode());
        assertEquals((byte) 0x45, GossipMessageType.FAILOVER_AUTH_REQUEST.getCode());
        assertEquals((byte) 0x46, GossipMessageType.FAILOVER_AUTH_ACK.getCode());
        assertEquals((byte) 0x47, GossipMessageType.UPDATE.getCode());
        assertEquals((byte) 0x48, GossipMessageType.FAILOVER_RESULT.getCode());
        assertEquals((byte) 0x49, GossipMessageType.MIGRATE_KEY.getCode());
        assertEquals((byte) 0x4A, GossipMessageType.MIGRATE_KEY_ACK.getCode());
        assertEquals((byte) 0x4B, GossipMessageType.MANUAL_FAILOVER_START.getCode());
        assertEquals((byte) 0x4C, GossipMessageType.MANUAL_FAILOVER_OFFSET.getCode());
    }
}
