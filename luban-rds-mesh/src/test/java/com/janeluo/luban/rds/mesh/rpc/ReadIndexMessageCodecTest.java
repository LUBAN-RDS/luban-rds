package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.bus.MessageType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadIndexMessageCodecTest {

    @Test
    void request_roundTrip() {
        ReadIndexRequestMessage req = new ReadIndexRequestMessage(7L, 42L);
        ReadIndexRequestMessage decoded = (ReadIndexRequestMessage)
                MeshRpcMessage.decode(MessageType.READ_INDEX_REQ, req.encode());
        assertEquals(7L, decoded.getTerm());
        assertEquals(42L, decoded.getRequestId());
    }

    @Test
    void response_roundTrip_success() {
        ReadIndexResponseMessage resp = new ReadIndexResponseMessage(9L, 42L, 1234L, true, "n2");
        ReadIndexResponseMessage decoded = (ReadIndexResponseMessage)
                MeshRpcMessage.decode(MessageType.READ_INDEX_RESP, resp.encode());
        assertEquals(9L, decoded.getTerm());
        assertEquals(42L, decoded.getRequestId());
        assertEquals(1234L, decoded.getReadIndex());
        assertTrue(decoded.isSuccess());
        assertEquals("n2", decoded.getLeaderNodeId());
    }

    @Test
    void response_roundTrip_failureWithNullLeader() {
        ReadIndexResponseMessage resp = new ReadIndexResponseMessage(3L, 1L, 0L, false, null);
        ReadIndexResponseMessage decoded = (ReadIndexResponseMessage)
                MeshRpcMessage.decode(MessageType.READ_INDEX_RESP, resp.encode());
        assertFalse(decoded.isSuccess());
        assertEquals(null, decoded.getLeaderNodeId());
    }

    @Test
    void request_truncatedBody_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> ReadIndexRequestMessage.decode(new byte[15]));
    }

    @Test
    void response_truncatedBody_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> ReadIndexResponseMessage.decode(new byte[28]));
    }

    @Test
    void response_overLongNodeIdLengthPrefix_throwsIllegalArgumentNotError() {
        // term(8)+requestId(8)+readIndex(8)+success(1)+nodeId len(4=Integer.MAX_VALUE)
        byte[] body = new byte[29];
        ByteBuffer buf = ByteBuffer.wrap(body);
        buf.putLong(1L).putLong(1L).putLong(0L).put((byte) 1).putInt(Integer.MAX_VALUE);
        assertThrows(IllegalArgumentException.class,
                () -> ReadIndexResponseMessage.decode(body));
    }
}
