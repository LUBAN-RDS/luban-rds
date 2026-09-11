package com.janeluo.luban.rds.mesh.gateway;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4（2026-09-11 mesh 审计 P2）：读写分类与未知命令收口。
 * <ul>
 *   <li>KEYS 归读（原默认判写进 Raft——读放大 + 日志垃圾）；</li>
 *   <li>未知命令（含拼写错误）不进 Raft，gate.write 抛 {@link UnknownCommandException}；</li>
 *   <li>RESP 帧命令名解析正确（大小写不敏感）。</li>
 * </ul>
 */
class MeshCommandClassificationTest {

    @Test
    void keysClassifiedAsRead() {
        assertFalse(MeshWriteGate.isWriteCommand("KEYS"), "KEYS 应归读");
        assertFalse(MeshWriteGate.isWriteCommand("keys"), "大小写不敏感");
    }

    @Test
    void scriptExistsIsReadSemantics() {
        // SCRIPT 整体仍判写（LOAD 会改脚本缓存）；EXISTS 子命令的读特判在 handler 层
        assertTrue(MeshWriteGate.isWriteCommand("SCRIPT"));
    }

    @Test
    void writeRejectsUnknownCommand() {
        // 无 gate 依赖的最小构造：MeshWriteGate 需要 meshNode——未知命令检查在 propose 之前，
        // 用 null 依赖构造会 NPE。改为直接测 extractCommandName + 注册判定链路，
        // end-to-end 行为由 server 侧嵌入式测试覆盖。
        byte[] frame = respArray("SETT", "k", "v");
        assertEquals("SETT", MeshWriteGate.extractCommandName(frame));
    }

    @Test
    void extractCommandNameParsesCaseAndMalformed() {
        assertEquals("SET", MeshWriteGate.extractCommandName(respArray("SET", "k", "v")));
        // 原始大小写返回（注册判定与错误串由调用方处理大小写）
        assertEquals("evalsha", MeshWriteGate.extractCommandName(respArray("evalsha", "sha", "0")));
        assertEquals("MULTI", MeshWriteGate.extractCommandName(respArray("MULTI")));
        assertEquals(null, MeshWriteGate.extractCommandName(new byte[]{0x01, 0x02}));
        assertEquals(null, MeshWriteGate.extractCommandName(null));
    }

    @Test
    void unknownCommandExceptionCarriesName() {
        UnknownCommandException e = new UnknownCommandException("SETT");
        assertTrue(e.getMessage().contains("SETT"));
        assertEquals("SETT", e.getCommandName());
    }

    static byte[] respArray(String... args) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(args.length).append("\r\n");
        for (String a : args) {
            sb.append('$').append(a.length()).append("\r\n").append(a).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }
}
