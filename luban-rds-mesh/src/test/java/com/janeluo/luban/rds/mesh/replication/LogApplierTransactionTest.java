package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.replication.TransactionPayload.WatchEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LogApplier#apply} 的 MULTI/EXEC 事务分支单元测试（阶段 9 / DESIGN §5.8）。
 * <p>
 * 验证整事务单条 {@link LogEntry}（{@code extra} = {@link TransactionPayload} 编码）的 apply 行为：
 * <ul>
 *   <li>多命令事务（SET a 1 + INCR b）按序执行，rawStore 状态正确，返回 RESP 数组含两个响应；</li>
 *   <li>空事务（无排队命令）返回 {@code *0\r\n}（空数组）；</li>
 *   <li>WATCH 校验通过：版本匹配时正常执行；</li>
 *   <li>WATCH 校验失败：版本不符返回 {@code *-1\r\n}（RESP null multi）且命令不执行；</li>
 *   <li>单命令事务（无 WATCH）正常执行；</li>
 *   <li>事务跨 db：命令作用于 entry.dbIndex。</li>
 * </ul>
 * </p>
 */
class LogApplierTransactionTest {

    private DefaultMemoryStore rawStore;
    private DefaultCommandHandler handler;
    private LogApplier applier;

    @BeforeEach
    void setUp() {
        rawStore = new DefaultMemoryStore();
        handler = new DefaultCommandHandler();
        applier = new LogApplier(handler, rawStore);
    }

    /** 构造一个完整 RESP 命令帧的字节数组（ISO-8859-1 保持二进制安全）。 */
    private static byte[] respFrame(String... parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.ISO_8859_1);
            sb.append('$').append(b.length).append("\r\n")
              .append(p).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    /** MULTI 帧（作为事务 LogEntry 的 respPayload 标识）。 */
    private static byte[] multiFrame() {
        return respFrame("MULTI");
    }

    @Test
    void applyTransaction_multiCommands_executesInOrderAndReturnsArray() {
        // 事务：SET a 1 + INCR b（b 初始 10，INCR 后 11）
        byte[] setA = respFrame("SET", "a", "1");
        byte[] incrB = respFrame("INCR", "b");
        rawStore.set(0, "b", "10");

        byte[] extra = TransactionPayload.encodeCommands(Arrays.asList(setA, incrB));
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        // 返回 RESP 数组：*2\r\n + +OK\r\n + :11\r\n
        assertTrue(response instanceof String, "应为 RESP 数组字符串");
        String resp = (String) response;
        assertEquals("*2\r\n+OK\r\n:11\r\n", resp);

        // rawStore 状态：a=1，b=11（都生效）
        assertEquals("1", rawStore.get(0, "a"));
        assertEquals("11", rawStore.get(0, "b"));
    }

    @Test
    void applyTransaction_emptyTransaction_returnsEmptyArray() {
        // 空事务（无排队命令）→ *0\r\n（与 Redis EXEC 无命令行为一致）
        byte[] extra = TransactionPayload.encodeCommands(Collections.<byte[]>emptyList());
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        assertEquals("*0\r\n", response);
    }

    @Test
    void applyTransaction_singleCommandNoWatch_executesAndReturnsSingleElementArray() {
        // 单命令事务（无 WATCH）：SET k v → *1\r\n+OK\r\n
        byte[] set = respFrame("SET", "k", "v");
        byte[] extra = TransactionPayload.encodeSingleCommand(set);
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        assertEquals("*1\r\n+OK\r\n", response);
        assertEquals("v", rawStore.get(0, "k"));
    }

    @Test
    void applyTransaction_watchMatches_executesCommands() {
        // WATCH key=a 的版本快照；apply 时版本仍匹配 → 正常执行
        rawStore.set(0, "a", "old");
        long snapshotVersion = rawStore.getKeyVersion(0, "a");

        byte[] set = respFrame("SET", "a", "new");
        java.util.List<WatchEntry> watches =
                Collections.singletonList(new WatchEntry(0, "a", snapshotVersion));
        byte[] extra = TransactionPayload.encode(watches, Collections.singletonList(set));
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        assertEquals("*1\r\n+OK\r\n", response);
        assertEquals("new", rawStore.get(0, "a"));
    }

    @Test
    void applyTransaction_watchVersionMismatch_returnsNullMultiAndDoesNotExecute() {
        // WATCH key=a 的版本快照为 999（与实际不符）→ 事务中止，返回 *-1\r\n，命令不执行
        rawStore.set(0, "a", "old");
        long mismatchVersion = 999L;  // 故意不匹配

        byte[] set = respFrame("SET", "a", "new");
        java.util.List<WatchEntry> watches =
                Collections.singletonList(new WatchEntry(0, "a", mismatchVersion));
        byte[] extra = TransactionPayload.encode(watches, Collections.singletonList(set));
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        // RESP null multi（*-1\r\n），与 Redis WATCH 失败语义一致
        assertEquals("*-1\r\n", response);
        // 命令未执行：a 仍是 old
        assertEquals("old", rawStore.get(0, "a"));
    }

    @Test
    void applyTransaction_watchOnMissingKey_returnsNullMulti() {
        // WATCH 不存在的 key（版本快照=0，但 apply 前 key 被其它命令创建）→ 不匹配 → 中止
        // 场景：WATCH 不存在的 key（snapshot=0），apply 前另一写操作创建了 key（version 变正）
        byte[] set = respFrame("SET", "watched", "created-elsewhere");
        applier.apply(new LogEntry(1L, 1L, set, 0, null));  // 现在 watched 存在，version > 0

        // WATCH 快照为 0（MULTI 时 key 不存在），但 apply 时 version 已变 → 不匹配
        byte[] txSet = respFrame("SET", "x", "1");
        java.util.List<WatchEntry> watches =
                Collections.singletonList(new WatchEntry(0, "watched", 0L));
        byte[] extra = TransactionPayload.encode(watches, Collections.singletonList(txSet));
        LogEntry entry = new LogEntry(1L, 2L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        assertEquals("*-1\r\n", response);
        // 事务内命令未执行：x 不存在
        assertNull(rawStore.get(0, "x"));
    }

    @Test
    void applyTransaction_dbIndex_isolatesDatabases() {
        // 事务 LogEntry 的 dbIndex=1 → 队列内命令作用于 db 1（与普通 apply 同语义）
        byte[] setFoo = respFrame("SET", "foo", "db1-tx");
        byte[] extra = TransactionPayload.encodeSingleCommand(setFoo);
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 1, extra);

        Object response = applier.apply(entry);

        assertEquals("*1\r\n+OK\r\n", response);
        assertEquals("db1-tx", rawStore.get(1, "foo"));
        // db 0 不受影响
        assertNull(rawStore.get(0, "foo"));
    }

    @Test
    void applyTransaction_threeCommands_mixedTypes() {
        // 混合类型命令：SET / DEL（已存在 key）/ GET
        rawStore.set(0, "delme", "value");
        rawStore.set(0, "g", "gval");

        byte[] set = respFrame("SET", "a", "1");
        byte[] del = respFrame("DEL", "delme");
        byte[] get = respFrame("GET", "g");
        byte[] extra = TransactionPayload.encodeCommands(Arrays.asList(set, del, get));
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        Object response = applier.apply(entry);

        // *3\r\n + +OK\r\n + :1\r\n + $4\r\ngval\r\n
        assertEquals("*3\r\n+OK\r\n:1\r\n$4\r\ngval\r\n", response);
        // 状态：a=1，delme 已删，g=gval
        assertEquals("1", rawStore.get(0, "a"));
        assertNull(rawStore.get(0, "delme"));
        assertEquals("gval", rawStore.get(0, "g"));
    }

    @Test
    void applyTransaction_malformedExtra_returnsError() {
        // 非法 extra（非 TransactionPayload 编码）→ -ERR，不抛异常
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, new byte[]{1, 2, 3});

        Object response = applier.apply(entry);

        assertTrue(response instanceof String);
        assertTrue(((String) response).startsWith("-ERR"));
    }

    @Test
    void applyAndSerialize_transaction_returnsArrayBytes() {
        // applyAndSerialize 对事务返回 RESP 数组字节
        byte[] set = respFrame("SET", "k", "v");
        byte[] extra = TransactionPayload.encodeSingleCommand(set);
        LogEntry entry = new LogEntry(1L, 1L, multiFrame(), 0, extra);

        byte[] bytes = applier.applyAndSerialize(entry);

        // 序列化后：*1\r\n+OK\r\n（字符串直通，serialize 对 "*1\r\n+OK\r\n" 原样输出）
        String s = new String(bytes, StandardCharsets.ISO_8859_1);
        assertEquals("*1\r\n+OK\r\n", s);
    }
}
