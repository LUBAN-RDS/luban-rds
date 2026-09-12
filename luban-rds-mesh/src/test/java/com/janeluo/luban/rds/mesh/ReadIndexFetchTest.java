package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.core.RaftStateMachine;
import com.janeluo.luban.rds.mesh.rpc.ReadIndexResponseMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ReadIndexFetchTest {

    /** 可编程假总线：把出站 READ_INDEX_REQ 交给测试提供的应答器。 */
    static final class ProgrammableBus extends MeshBusClient {
        volatile Consumer<MeshFrame> responder;

        ProgrammableBus(String self) {
            super(self, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            Consumer<MeshFrame> r = responder;
            if (r != null && frame.getType() == MessageType.READ_INDEX_REQ.getCode()) {
                r.accept(frame);
            }
        }
    }

    private MeshNode follower(MeshState state, ProgrammableBus bus) {
        MeshConfig config = MeshConfig.builder("n1")
                .addPeer("n2", "127.0.0.1:9737")
                .addPeer("n3", "127.0.0.1:9738")
                .totalNodes(3).build();
        MeshNode node = new MeshNode(config, state, bus, new RaftStateMachine());
        state.role = MeshRole.FOLLOWER;
        state.leaderId = "n2";
        return node;
    }

    @Test
    void fetchReadIndex_returnsReadIndex_onSuccess() throws Exception {
        MeshState state = new MeshState();
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);

        bus.responder = req -> {
            var m = com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage.decode(req.getBody());
            node.onMessage("n2", new MeshFrame("n2", MessageType.READ_INDEX_RESP.getCode(),
                    new ReadIndexResponseMessage(state.currentTerm, m.getRequestId(), 55, true, "n2").encode()));
        };

        Long readIndex = node.fetchReadIndex(2_000);
        assertEquals(55L, readIndex);
    }

    @Test
    void fetchReadIndex_returnsNull_onFailureResponse() throws Exception {
        MeshState state = new MeshState();
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);

        bus.responder = req -> {
            var m = com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage.decode(req.getBody());
            node.onMessage("n2", new MeshFrame("n2", MessageType.READ_INDEX_RESP.getCode(),
                    new ReadIndexResponseMessage(state.currentTerm, m.getRequestId(), 0, false, "n3").encode()));
        };

        assertNull(node.fetchReadIndex(2_000), "success=false 必须回落（返回 null）");
    }

    @Test
    void fetchReadIndex_returnsNull_onTimeout() {
        MeshState state = new MeshState();
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);
        bus.responder = req -> { /* 不应答 */ };
        assertNull(node.fetchReadIndex(150), "无应答必须超时回落");
    }

    @Test
    void fetchReadIndex_discardsStaleTerm_andReturnsNull() throws Exception {
        MeshState state = new MeshState();
        state.currentTerm = 5;
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);

        bus.responder = req -> {
            var m = com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage.decode(req.getBody());
            node.onMessage("n2", new MeshFrame("n2", MessageType.READ_INDEX_RESP.getCode(),
                    new ReadIndexResponseMessage(4, m.getRequestId(), 55, true, "n2").encode()));
        };

        assertNull(node.fetchReadIndex(2_000), "低于本节点 term 的响应是过期响应，必须丢弃");
    }

    @Test
    void fetchReadIndex_higherTerm_convergesThenReturnsNull() throws Exception {
        MeshState state = new MeshState();
        state.currentTerm = 5;
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);

        bus.responder = req -> {
            var m = com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage.decode(req.getBody());
            node.onMessage("n2", new MeshFrame("n2", MessageType.READ_INDEX_RESP.getCode(),
                    new ReadIndexResponseMessage(9, m.getRequestId(), 55, true, "n2").encode()));
        };

        assertNull(node.fetchReadIndex(2_000), "更高 term 时读点不可采信（本任务不返回读点）");
        assertEquals(9L, state.currentTerm, "但仍须按 Raft 规则收敛 term");
    }

    @Test
    void fetchReadIndex_nonMemberResponse_isDroppedByMemberCheck() throws Exception {
        MeshState state = new MeshState();
        ProgrammableBus bus = new ProgrammableBus("n1");
        MeshNode node = follower(state, bus);

        bus.responder = req -> {
            var m = com.janeluo.luban.rds.mesh.rpc.ReadIndexRequestMessage.decode(req.getBody());
            // 来源 nX 不在 peers 集合 → onMessage 的成员校验应直接丢弃
            node.onMessage("nX", new MeshFrame("nX", MessageType.READ_INDEX_RESP.getCode(),
                    new ReadIndexResponseMessage(state.currentTerm, m.getRequestId(), 55, true, "nX").encode()));
        };

        assertNull(node.fetchReadIndex(300), "非成员来源帧必须被丢弃，读点不可用");
        assertTrue(node.getUnknownPeerFrameCount() >= 1, "成员校验丢弃计数应增长");
    }
}
