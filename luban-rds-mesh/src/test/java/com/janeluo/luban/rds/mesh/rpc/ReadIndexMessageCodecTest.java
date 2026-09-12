package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.bus.MessageType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
