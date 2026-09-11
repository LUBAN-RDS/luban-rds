package com.janeluo.luban.rds.mesh.rpc;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-4a（2026-09-11 mesh 审计）：快照元数据尾部携带脚本表（sha1 → 脚本文本）。
 * <ul>
 *   <li>带表消息编解码往返一致；</li>
 *   <li>不带表（旧格式）解码得 null 表（向后兼容）；</li>
 *   <li>后续 chunk（offset&gt;0）不携带表。</li>
 * </ul>
 */
class InstallSnapshotScriptTableTest {

    @Test
    void roundTripWithScriptTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", "return 1");
        table.put("b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", "return redis.call('GET', KEYS[1])");
        InstallSnapshotMessage msg = new InstallSnapshotMessage(
                7L, "leaderX", 3L, 100L, 0L, new byte[]{1, 2, 3}, false, table);

        InstallSnapshotMessage back = InstallSnapshotMessage.decode(msg.encode());
        assertNotNull(back.getScriptTable());
        assertEquals(2, back.getScriptTable().size());
        assertEquals("return 1", back.getScriptTable().get("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"));
        assertEquals(100L, back.getLastIncludedIndex());
        assertTrue(back.getData().length == 3);
    }

    @Test
    void legacyFrameDecodesWithNullTable() {
        // 旧格式：不带表构造（7 参构造器 → encode → decode）
        InstallSnapshotMessage legacy = new InstallSnapshotMessage(
                7L, "leaderX", 3L, 100L, 0L, new byte[]{1}, true);
        InstallSnapshotMessage back = InstallSnapshotMessage.decode(legacy.encode());
        assertNull(back.getScriptTable(), "旧格式帧应解码为无表（向后兼容）");
        assertTrue(back.isDone());
    }

    @Test
    void laterChunkCarriesNoTable() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", "return 3");
        // 首 chunk 带表
        InstallSnapshotMessage first = new InstallSnapshotMessage(
                7L, "leaderX", 3L, 100L, 0L, new byte[1024], false, table);
        assertNotNull(InstallSnapshotMessage.decode(first.encode()).getScriptTable());
        // 后续 chunk（offset>0）即使传入表也不编码
        InstallSnapshotMessage second = new InstallSnapshotMessage(
                7L, "leaderX", 3L, 100L, 1024L, new byte[1024], true, table);
        assertNull(InstallSnapshotMessage.decode(second.encode()).getScriptTable(),
                "offset>0 的 chunk 不应携带脚本表");
    }
}
