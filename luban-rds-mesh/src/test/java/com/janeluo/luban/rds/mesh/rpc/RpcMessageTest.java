package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 5 种 RPC 消息 encode/decode 往返一致性 + {@link MeshRpcMessage#decode(MessageType, byte[])} 总入口分发测试。
 * <p>
 * 覆盖（DESIGN §4.3 全字段）：AppendEntriesMessage（含 entries 列表与空列表）、AppendEntriesResponse、
 * RequestVoteMessage、RequestVoteResponse、InstallSnapshotMessage（offset/done/data）。
 * </p>
 */
class RpcMessageTest {

    private static final String NODE_ID = "0123456789abcdef0123456789abcdef01234567";

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static LogEntry entry(long term, long index) {
        return new LogEntry(term, index, utf8("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n"), 0, null);
    }

    // ==================== AppendEntriesMessage ====================

    @Test
    void appendEntriesMessage_roundtrip_withEntries() {
        List<LogEntry> entries = Arrays.asList(
                new LogEntry(5L, 11L, utf8("*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\n1\r\n"), 0, null),
                new LogEntry(5L, 12L, utf8("*2\r\n$3\r\nDEL\r\n$1\r\nb\r\n"), 1, utf8("watchVer=3")));
        AppendEntriesMessage original = new AppendEntriesMessage(
                5L, NODE_ID, 10L, 4L, entries, 9L);

        byte[] body = original.encode();
        AppendEntriesMessage decoded = AppendEntriesMessage.decode(body);

        assertEquals(5L, decoded.getTerm());
        assertEquals(NODE_ID, decoded.getLeaderId());
        assertEquals(10L, decoded.getPrevLogIndex());
        assertEquals(4L, decoded.getPrevLogTerm());
        assertEquals(9L, decoded.getLeaderCommit());
        assertEquals(2, decoded.getEntries().size());

        // 逐条校验 entries 内容精确匹配
        assertEntryEquals(entries.get(0), decoded.getEntries().get(0));
        assertEntryEquals(entries.get(1), decoded.getEntries().get(1));
    }

    @Test
    void appendEntriesMessage_roundtrip_emptyEntries_heartbeat() {
        // 心跳：entries 为空列表
        AppendEntriesMessage original = new AppendEntriesMessage(
                7L, NODE_ID, 100L, 6L, Collections.emptyList(), 95L);

        AppendEntriesMessage decoded = AppendEntriesMessage.decode(original.encode());

        assertEquals(0, decoded.getEntries().size(), "心跳 entries 应为空");
        assertEquals(7L, decoded.getTerm());
        assertEquals(100L, decoded.getPrevLogIndex());
        assertEquals(95L, decoded.getLeaderCommit());
    }

    @Test
    void appendEntriesMessage_nullEntries_normalizedToEmpty() {
        AppendEntriesMessage original = new AppendEntriesMessage(
                1L, NODE_ID, 0L, 0L, null, 0L);

        AppendEntriesMessage decoded = AppendEntriesMessage.decode(original.encode());

        assertNotNull(decoded.getEntries());
        assertEquals(0, decoded.getEntries().size());
    }

    // ==================== AppendEntriesResponse ====================

    @Test
    void appendEntriesResponse_roundtrip_success() {
        AppendEntriesResponse original = new AppendEntriesResponse(5L, true, 42L);

        AppendEntriesResponse decoded = AppendEntriesResponse.decode(original.encode());

        assertEquals(5L, decoded.getTerm());
        assertTrue(decoded.isSuccess());
        assertEquals(42L, decoded.getMatchIndex());
    }

    @Test
    void appendEntriesResponse_roundtrip_failure() {
        AppendEntriesResponse original = new AppendEntriesResponse(9L, false, 0L);

        AppendEntriesResponse decoded = AppendEntriesResponse.decode(original.encode());

        assertEquals(9L, decoded.getTerm());
        assertFalse(decoded.isSuccess());
        assertEquals(0L, decoded.getMatchIndex());
    }

    // ==================== RequestVoteMessage ====================

    @Test
    void requestVoteMessage_roundtrip_allFieldsMatch() {
        RequestVoteMessage original = new RequestVoteMessage(6L, NODE_ID, 100L, 5L);

        RequestVoteMessage decoded = RequestVoteMessage.decode(original.encode());

        assertEquals(6L, decoded.getTerm());
        assertEquals(NODE_ID, decoded.getCandidateId());
        assertEquals(100L, decoded.getLastLogIndex());
        assertEquals(5L, decoded.getLastLogTerm());
    }

    // ==================== RequestVoteResponse ====================

    @Test
    void requestVoteResponse_roundtrip_granted() {
        RequestVoteResponse original = new RequestVoteResponse(6L, true);

        RequestVoteResponse decoded = RequestVoteResponse.decode(original.encode());

        assertEquals(6L, decoded.getTerm());
        assertTrue(decoded.isVoteGranted());
    }

    @Test
    void requestVoteResponse_roundtrip_denied() {
        RequestVoteResponse original = new RequestVoteResponse(8L, false);

        RequestVoteResponse decoded = RequestVoteResponse.decode(original.encode());

        assertEquals(8L, decoded.getTerm());
        assertFalse(decoded.isVoteGranted());
    }

    // ==================== InstallSnapshotMessage ====================

    @Test
    void installSnapshotMessage_roundtrip_chunkWithOffset() {
        byte[] data = utf8("RDB-CHUNK-PART-1-BYTES...");
        InstallSnapshotMessage original = new InstallSnapshotMessage(
                5L, NODE_ID, 4L, 100L, 0L, data, false);

        InstallSnapshotMessage decoded = InstallSnapshotMessage.decode(original.encode());

        assertEquals(5L, decoded.getTerm());
        assertEquals(NODE_ID, decoded.getLeaderId());
        assertEquals(4L, decoded.getLastIncludedTerm());
        assertEquals(100L, decoded.getLastIncludedIndex());
        assertEquals(0L, decoded.getOffset());
        assertArrayEquals(data, decoded.getData());
        assertFalse(decoded.isDone());
    }

    @Test
    void installSnapshotMessage_roundtrip_lastChunkDone() {
        byte[] data = utf8("RDB-LAST-CHUNK");
        // 非零 offset + done=true（阶段 10 chunked 传输最后一个 chunk）
        InstallSnapshotMessage original = new InstallSnapshotMessage(
                5L, NODE_ID, 4L, 100L, 4194304L, data, true);

        InstallSnapshotMessage decoded = InstallSnapshotMessage.decode(original.encode());

        assertEquals(4194304L, decoded.getOffset(), "chunked offset 应精确保留");
        assertTrue(decoded.isDone());
        assertArrayEquals(data, decoded.getData());
    }

    @Test
    void installSnapshotMessage_nullData_normalizedToEmpty() {
        InstallSnapshotMessage original = new InstallSnapshotMessage(
                1L, NODE_ID, 1L, 1L, 0L, null, true);

        InstallSnapshotMessage decoded = InstallSnapshotMessage.decode(original.encode());

        assertNotNull(decoded.getData());
        assertEquals(0, decoded.getData().length);
        assertTrue(decoded.isDone());
    }

    // ==================== MeshRpcMessage.decode 总入口分发 ====================

    @Test
    void decode_dispatch_appendEntriesRequest() {
        AppendEntriesMessage original = new AppendEntriesMessage(
                3L, NODE_ID, 5L, 2L, Collections.singletonList(entry(3L, 6L)), 4L);

        MeshRpcMessage decoded = MeshRpcMessage.decode(MessageType.APPEND_ENTRIES, original.encode());

        assertInstanceOf(AppendEntriesMessage.class, decoded);
        AppendEntriesMessage ae = (AppendEntriesMessage) decoded;
        assertEquals(3L, ae.getTerm());
        assertEquals(1, ae.getEntries().size());
        assertEquals(NODE_ID, ae.getLeaderId());
    }

    @Test
    void decode_dispatch_appendEntriesResponse() {
        AppendEntriesResponse original = new AppendEntriesResponse(4L, true, 10L);

        MeshRpcMessage decoded = MeshRpcMessage.decode(MessageType.APPEND_ENTRIES_RESP, original.encode());

        assertInstanceOf(AppendEntriesResponse.class, decoded);
        assertEquals(4L, decoded.getTerm());
        assertTrue(((AppendEntriesResponse) decoded).isSuccess());
    }

    @Test
    void decode_dispatch_requestVoteRequest() {
        RequestVoteMessage original = new RequestVoteMessage(5L, NODE_ID, 20L, 4L);

        MeshRpcMessage decoded = MeshRpcMessage.decode(MessageType.REQUEST_VOTE, original.encode());

        assertInstanceOf(RequestVoteMessage.class, decoded);
        RequestVoteMessage rv = (RequestVoteMessage) decoded;
        assertEquals(5L, rv.getTerm());
        assertEquals(20L, rv.getLastLogIndex());
    }

    @Test
    void decode_dispatch_requestVoteResponse() {
        RequestVoteResponse original = new RequestVoteResponse(5L, true);

        MeshRpcMessage decoded = MeshRpcMessage.decode(MessageType.REQUEST_VOTE_RESP, original.encode());

        assertInstanceOf(RequestVoteResponse.class, decoded);
        assertTrue(((RequestVoteResponse) decoded).isVoteGranted());
    }

    @Test
    void decode_dispatch_installSnapshot() {
        InstallSnapshotMessage original = new InstallSnapshotMessage(
                6L, NODE_ID, 5L, 200L, 0L, utf8("snap"), true);

        MeshRpcMessage decoded = MeshRpcMessage.decode(MessageType.INSTALL_SNAPSHOT, original.encode());

        assertInstanceOf(InstallSnapshotMessage.class, decoded);
        InstallSnapshotMessage is = (InstallSnapshotMessage) decoded;
        assertEquals(6L, is.getTerm());
        assertEquals(200L, is.getLastIncludedIndex());
        assertTrue(is.isDone());
    }

    @Test
    void decode_dispatch_usesByteCode_fromMessageType() {
        // 模拟 MeshBusHandler 的实际调用：MessageType.fromCode(byte) → decode
        AppendEntriesResponse original = new AppendEntriesResponse(2L, true, 7L);

        MessageType type = MessageType.fromCode((byte) 0x61);   // APPEND_ENTRIES_RESP
        MeshRpcMessage decoded = MeshRpcMessage.decode(type, original.encode());

        assertInstanceOf(AppendEntriesResponse.class, decoded);
        assertEquals(2L, decoded.getTerm());
    }

    // ==================== helpers ====================

    private static void assertEntryEquals(LogEntry expected, LogEntry actual) {
        assertEquals(expected.getTerm(), actual.getTerm());
        assertEquals(expected.getIndex(), actual.getIndex());
        assertEquals(expected.getDbIndex(), actual.getDbIndex());
        assertArrayEquals(expected.getRespPayload(), actual.getRespPayload());
        if (expected.getExtra() == null) {
            assertEquals(null, actual.getExtra());
        } else {
            assertArrayEquals(expected.getExtra(), actual.getExtra());
        }
    }
}
