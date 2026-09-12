package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexResponseMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReadIndexLeaderHandlerTest {

    /** 捕获出站帧的假总线（不建连接）。 */
    static final class CapturingBus extends MeshBusClient {
        final List<MeshFrame> sent = new ArrayList<>();

        CapturingBus(String self) {
            super(self, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.add(frame);
        }
    }

    private MeshNode leader(MeshState state, CapturingBus bus) {
        MeshConfig config = MeshConfig.builder("n1")
                .addPeer("n2", "127.0.0.1:9737")
                .addPeer("n3", "127.0.0.1:9738")
                .totalNodes(3)
                .build();
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine());
        node.getState().role = MeshRole.LEADER;
        return node;
    }

    @Test
    void leaderWithValidLease_returnsCommitIndex() throws Exception {
        MeshState state = new MeshState();
        state.role = MeshRole.LEADER;
        state.currentTerm = 1;                       // 应答 term 取 Leader 自身 term（计划实现 state.currentTerm）
        state.commitIndex = 77;
        CapturingBus bus = new CapturingBus("n1");
        MeshNode node = leader(state, bus);
        node.lease().refreshOnMajorityAck(System.currentTimeMillis());

        bus.sent.clear();
        node.onMessage("n2", frame(new ReadIndexRequestMessage(1, 5)));

        ReadIndexResponseMessage resp = awaitResp(bus, 1);
        assertTrue(resp.isSuccess());
        assertEquals(77, resp.getReadIndex());
        assertEquals(5, resp.getRequestId());
        assertEquals(1, resp.getTerm());
    }

    @Test
    void leaderWithInvalidLease_returnsFailure() throws Exception {
        MeshState state = new MeshState();
        state.role = MeshRole.LEADER;
        state.commitIndex = 77;
        CapturingBus bus = new CapturingBus("n1");
        MeshNode node = leader(state, bus);          // 从未续租 → 租约无效

        bus.sent.clear();
        node.onMessage("n2", frame(new ReadIndexRequestMessage(1, 6)));

        ReadIndexResponseMessage resp = awaitResp(bus, 1);
        assertFalse(resp.isSuccess());
        assertEquals(6, resp.getRequestId());
    }

    @Test
    void nonLeader_returnsFailureWithLeaderNodeId() throws Exception {
        MeshState state = new MeshState();
        state.role = MeshRole.FOLLOWER;
        state.leaderId = "n3";
        CapturingBus bus = new CapturingBus("n1");
        MeshNode node = new MeshNode(MeshConfig.builder("n1")
                .addPeer("n2", "127.0.0.1:9737")
                .addPeer("n3", "127.0.0.1:9738")
                .totalNodes(3).build(), state, bus, new RaftStateMachine());

        bus.sent.clear();
        node.onMessage("n2", frame(new ReadIndexRequestMessage(1, 7)));

        ReadIndexResponseMessage resp = awaitResp(bus, 1);
        assertFalse(resp.isSuccess());
        assertEquals("n3", resp.getLeaderNodeId());
    }

    private static MeshFrame frame(com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage msg) {
        com.janeluo.luban.rds.mesh.bus.MessageType type =
                msg instanceof ReadIndexRequestMessage
                        ? com.janeluo.luban.rds.mesh.bus.MessageType.READ_INDEX_REQ
                        : com.janeluo.luban.rds.mesh.bus.MessageType.READ_INDEX_RESP;
        return new MeshFrame("n2", type.getCode(), msg.encode());
    }

    /** 从捕获的出站帧里取第 {@code index} 条 readIndex 应答（可能夹带心跳，故按类型过滤）。 */
    private static ReadIndexResponseMessage awaitResp(CapturingBus bus, int index) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            List<MeshFrame> snapshot = new ArrayList<>(bus.sent);
            int seen = 0;
            for (MeshFrame f : snapshot) {
                if (f.getType() != com.janeluo.luban.rds.mesh.bus.MessageType.READ_INDEX_RESP.getCode()) {
                    continue;
                }
                if (++seen == index) {
                    return (ReadIndexResponseMessage) com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage
                            .decode(com.janeluo.luban.rds.mesh.bus.MessageType.READ_INDEX_RESP,
                                    f.getBody());
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("未在 3s 内捕获到 READ_INDEX_RESP");
    }
}
