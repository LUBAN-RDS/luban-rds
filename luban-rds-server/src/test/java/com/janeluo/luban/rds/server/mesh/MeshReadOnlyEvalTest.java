package com.janeluo.luban.rds.server.mesh;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.gateway.MeshWriteGate;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import com.janeluo.luban.rds.server.RedisServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线图#12（2026-09-11 mesh 审计）：只读 EVAL/EVALSHA 走本地读（Leader+租约）。
 * <p>
 * 生产写放大源头——门户每请求 2 条 EVAL（1 条纯读 PTTL）恒判写全进 Raft（9/11 实测 1 小时
 * 吃掉全天 90% Raft 条目）。判定复用 cluster 从节点先例（resolveScriptBody +
 * LuaScriptAnalyzer，取不到脚本保守判写）。只读脚本 → gate.read（不产生 Raft 条目）；
 * 写脚本/未缓存 EVALSHA → 照旧 gate.write。
 * </p>
 */
class MeshReadOnlyEvalTest {

    private MeshNode node;
    private EmbeddedChannel channel;
    private RecordingGate gate;

    /** 记录 read/write 调用次数的 gate（读路径真实执行本地 Lua，写路径拒绝计数）。 */
    private static class RecordingGate extends MeshWriteGate {
        final AtomicInteger readCalls = new AtomicInteger();
        final AtomicInteger writeCalls = new AtomicInteger();

        RecordingGate(MeshNode node, MemoryStore store, DefaultCommandHandler handler) {
            super(node, store, handler, (MeshConfig) null);
        }

        @Override
        public byte[] read(int dbIndex, String[] args) {
            readCalls.incrementAndGet();
            return super.read(dbIndex, args);
        }

        @Override
        public byte[] write(byte[] rawRespFrame, int dbIndex, byte[] extra) {
            writeCalls.incrementAndGet();
            // 不真正 propose（记录路由决策即可；写脚本进 Raft 的行为由 mesh 模块测试覆盖）
            return "-ERR write path reached in test\r\n".getBytes(StandardCharsets.US_ASCII);
        }
    }

    @BeforeEach
    void setUp() {
        MeshBusClient bus = new MeshBusClient("nodeA", new MeshBusHandler()) {
        };
        node = new MeshNode(MeshConfig.builder("nodeA")
                .addPeer("nodeB", "127.0.0.1:11001").build(), new MeshState(), bus);

        MemoryStore store = new DefaultMemoryStore();
        DefaultCommandHandler handler = new DefaultCommandHandler();
        // 预置 key：PTTL 可读
        store.set(0, "sess", "v");
        store.pexpire(0, "sess", 60_000);

        RedisServerHandler serverHandler = new RedisServerHandler(
                store, handler, new RedisProtocolParser(), 0);
        serverHandler.setMeshEnabled(true);
        gate = new RecordingGate(node, store, handler);
        serverHandler.setMeshWriteGate(gate);
        channel = new EmbeddedChannel(serverHandler);
    }

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.close();
        }
        node.stop();
    }

    private String sendCommand(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append("*").append(parts.length).append("\r\n");
        for (String part : parts) {
            byte[] b = part.getBytes(StandardCharsets.UTF_8);
            sb.append("$").append(b.length).append("\r\n").append(part).append("\r\n");
        }
        ByteBuf input = Unpooled.copiedBuffer(sb.toString(), StandardCharsets.UTF_8);
        channel.writeInbound(input);
        channel.flush();
        ByteBuf response = channel.readOutbound();
        if (response != null) {
            String s = response.toString(StandardCharsets.UTF_8);
            response.release();
            return s;
        }
        return null;
    }

    @Test
    void readOnlyEval_routedToLocalRead() {
        // Leader 角色直置（gate.read 内部校验 isLeader——通过真实 MeshNode 状态不满足时会 MOVED，
        // 故这里直接以非 Leader 下的 MOVED 为路由证明：read 被调用即达成断言目标）
        String resp = sendCommand("EVAL", "return redis.call('PTTL', KEYS[1])", "1", "sess");
        assertEquals(1, gate.readCalls.get(), "只读 EVAL 应路由到本地读（gate.read）");
        assertEquals(0, gate.writeCalls.get(), "只读 EVAL 不应走 Raft 写路径");
        // gate.read 在非 Leader 下抛 MOVED → 客户端收到 -MOVED（证明读路径被真实执行）
        assertTrue(resp != null && resp.startsWith("-MOVED"),
                "非 Leader 上只读 EVAL 仍 MOVED（P1-7 架构项保持），实际: " + resp);
    }

    @Test
    void writeEval_routedToRaftWrite() {
        sendCommand("EVAL", "redis.call('HSET', KEYS[1], 'f', 'v') return 1", "1", "sess");
        assertEquals(1, gate.writeCalls.get(), "写脚本 EVAL 应照旧走 Raft（gate.write）");
        assertEquals(0, gate.readCalls.get());
    }

    @Test
    void evalshaUncached_conservativelyRoutedToWrite() {
        sendCommand("EVALSHA", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef", "1", "sess");
        assertEquals(1, gate.writeCalls.get(), "未缓存 EVALSHA 取不到脚本应保守判写（宁多复制不漏复制）");
        assertEquals(0, gate.readCalls.get());
    }
}
