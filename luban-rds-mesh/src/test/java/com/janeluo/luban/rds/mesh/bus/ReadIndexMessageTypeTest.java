package com.janeluo.luban.rds.mesh.bus;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReadIndexMessageTypeTest {

    @Test
    void readIndexCodes_roundTrip() {
        assertEquals(MessageType.READ_INDEX_REQ, MessageType.fromCode((byte) 0x66));
        assertEquals(MessageType.READ_INDEX_RESP, MessageType.fromCode((byte) 0x67));
        assertEquals((byte) 0x66, MessageType.READ_INDEX_REQ.getCode());
        assertEquals((byte) 0x67, MessageType.READ_INDEX_RESP.getCode());
    }

    @Test
    void existingCodes_unaffected() {
        assertEquals(MessageType.APPEND_ENTRIES, MessageType.fromCode((byte) 0x60));
        assertEquals(MessageType.BUS_HELLO, MessageType.fromCode((byte) 0x65));
    }

    @Test
    void unknownCode_throws() {
        assertThrows(IllegalArgumentException.class, () -> MessageType.fromCode((byte) 0x7F));
    }
}
