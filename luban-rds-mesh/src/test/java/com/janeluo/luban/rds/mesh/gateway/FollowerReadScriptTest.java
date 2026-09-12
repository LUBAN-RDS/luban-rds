package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.handler.LuaCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.core.ApplyBarrier;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只读脚本在 Follower 本地执行（fix-mesh-follower-read / Task 12）。
 * <ul>
 *   <li>EVALSHA 本地缓存未命中 → 回落 {@link MovedToLeaderException}（MOVED），
 *       <b>不得</b>返回 {@code -NOSCRIPT}——集群感知客户端应被重定向到 Leader 拿正确值；</li>
 *   <li>EVAL（只读脚本）→ 直接本地执行；</li>
 *   <li>EVALSHA 命中（脚本已在 P1-4 全局共享静态缓存）→ 直接本地执行。</li>
 * </ul>
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FollowerReadScriptTest {

    private static final byte[] BULK_BAR = "$3\r\nbar\r\n".getBytes(StandardCharsets.ISO_8859_1);

    private Map<String, String> originalScripts;

    @BeforeEach
    void saveScriptTable() {
        originalScripts = LuaCommandHandler.snapshotScripts();
    }

    @AfterEach
    void restoreScriptTable() {
        LuaCommandHandler.restoreScripts(originalScripts);
    }

    private MeshWriteGate gate(MeshNode node, DefaultMemoryStore store) {
        MeshConfig config = MeshConfig.builder("n1")
                .readFromFollower(MeshConfig.ReadFromFollower.READ_INDEX)
                .followerReadMaxWaitMs(500)
                .followerReadCacheMs(0)
                .build();
        return new MeshWriteGate(node, store, new DefaultCommandHandler(""),
                config, Collections.emptyMap(), null);
    }

    private MeshNode followerNode() {
        MeshState applied = new MeshState();
        applied.lastApplied = 100;
        MeshNode node = mock(MeshNode.class);
        when(node.isReady()).thenReturn(true);
        when(node.isApplyHalted()).thenReturn(false);
        when(node.isLeader()).thenReturn(false);
        when(node.getLeaderId()).thenReturn("n2");
        when(node.fetchReadIndex(anyLong())).thenReturn(10L);
        when(node.currentTerm()).thenReturn(1L);
        when(node.applyBarrier()).thenReturn(new ApplyBarrier(applied));
        when(node.readIndexCache()).thenReturn(new ReadIndexCache());
        return node;
    }

    /** 与 SCRIPT LOAD 同口径的 SHA1（走真实静态脚本缓存，而非新造生产 API）。 */
    private static String sha1Hex(String script) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(script.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void evalsha_miss_locally_fallsBackToMoved_notNoScript() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        LuaCommandHandler.restoreScripts(new HashMap<>());   // 确保未命中
        MeshNode node = followerNode();
        MeshWriteGate gate = gate(node, store);
        String[] args = {"EVALSHA", "0000000000000000000000000000000000000000", "1", "foo"};

        assertThrows(MovedToLeaderException.class, () -> gate.read(0, args),
                "脚本未命中必须回落 MOVED，不得返回 -NOSCRIPT");
        // EVALSHA 未命中是回落原因之一，必须计入计数（INFO 指标覆盖全部回落原因）
        verify(node, times(1)).incFollowerReadFallback();
    }

    @Test
    void eval_readOnly_executesLocally() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        MeshWriteGate gate = gate(followerNode(), store);
        String script = "return redis.call('GET', KEYS[1])";
        String[] args = {"EVAL", script, "1", "foo"};

        byte[] resp = gate.read(0, args);
        assertArrayEquals(BULK_BAR, resp);
    }

    @Test
    void evalsha_hit_executesLocally() throws Exception {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.set(0, "foo", "bar");
        String script = "return redis.call('GET', KEYS[1])";
        String sha = sha1Hex(script);
        // 走既有公有入口（静态共享缓存）——gate 的 DefaultCommandHandler 内部 LuaCommandHandler
        // 与快照/apply 侧共享同一实例（P1-4），故本地 resolveScriptBody 可见。
        Map<String, String> table = new HashMap<>(originalScripts);
        table.put(sha, script);
        LuaCommandHandler.restoreScripts(table);

        MeshWriteGate gate = gate(followerNode(), store);
        byte[] resp = gate.read(0, new String[]{"EVALSHA", sha, "1", "foo"});
        assertArrayEquals(BULK_BAR, resp);
    }
}
