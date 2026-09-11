package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.handler.LuaCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4（2026-09-11 mesh 审计）：脚本缓存静态共享 + apply 侧 EVALSHA miss 可观测。
 * <ul>
 *   <li>任一 LuaCommandHandler 实例 SCRIPT LOAD 后，其他实例（apply 侧）立即可见；</li>
 *   <li>apply 未命中脚本的 EVALSHA 条目：跳过执行、applyScriptMissCount +1、lastApplied 语义不受影响。</li>
 * </ul>
 */
class ScriptCacheSharingTest {

    private static String sha1Hex(String script) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(script.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] respFrame(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            sb.append('$').append(a.length()).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    void scriptCacheSharedAcrossHandlerInstances() throws Exception {
        DefaultMemoryStore store = new DefaultMemoryStore();
        // server 侧（RedisServerHandler 持有的 handler 树）加载脚本
        LuaCommandHandler serverSide = new LuaCommandHandler();
        String script = "return redis.call('GET', KEYS[1])";
        Object loadResp = serverSide.handle(0,
                new String[]{"SCRIPT", "LOAD", script}, store);
        String sha = sha1Hex(script);
        assertNotNull(loadResp);

        // apply 侧（LogApplier 内部 DefaultCommandHandler new 出的另一个实例）可见
        LuaCommandHandler applySide = new LuaCommandHandler();
        assertEquals(script, applySide.getScriptBySha1(sha),
                "脚本缓存应静态共享（P1-4）");

        // 快照导出/还原
        java.util.Map<String, String> snapshot = LuaCommandHandler.snapshotScripts();
        assertTrue(snapshot.containsKey(sha));
        LuaCommandHandler.restoreScripts(snapshot);
        assertEquals(script, LuaCommandHandler.snapshotScripts().get(sha));
    }

    @Test
    void restoreReplacesTable() {
        LuaCommandHandler.restoreScripts(new java.util.HashMap<>());
        assertNull(new LuaCommandHandler().getScriptBySha1("deadbeef"));
        java.util.Map<String, String> table = new java.util.HashMap<>();
        table.put("deadbeef", "return 1");
        LuaCommandHandler.restoreScripts(table);
        assertEquals("return 1", new LuaCommandHandler().getScriptBySha1("deadbeef"));
        LuaCommandHandler.restoreScripts(new java.util.HashMap<>());
    }

    @Test
    void applyEvalshaMissIsCountedAndSkipped() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), store);
        LuaCommandHandler.restoreScripts(new java.util.HashMap<>()); // 清缓存，确保未命中

        long before = applier.getApplyScriptMissCount();
        LogEntry entry = new LogEntry(1L, 1L,
                respFrame("EVALSHA", "0123456789012345678901234567890123456789", "0"), 0, null);
        Object resp = applier.apply(entry);

        assertTrue(String.valueOf(resp).startsWith("-NOSCRIPT"),
                "未命中应返回 -NOSCRIPT 响应（客户端语义不变）");
        assertEquals(before + 1, applier.getApplyScriptMissCount(),
                "apply 侧 miss 应计数");
    }
}
