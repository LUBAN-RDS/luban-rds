package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshConfigPersister} 单元测试（阶段 11）。
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>原子写往返：save(state) → load() → 字段一致（term/votedFor/lastIncludedIndex/Term/logTail）。</li>
 *   <li>logTail 含多条 LogEntry（含 dbIndex/extra）往返一致。</li>
 *   <li>文件不存在 → load 返回 null（首次启动）。</li>
 *   <li>文件损坏（JSON 解析失败）→ load 抛异常（不静默重置 term，DESIGN §5.5）。</li>
 *   <li>原子性：save 后无残留 tmp 文件。</li>
 *   <li>dump.rdb.index 辅助文件读写往返。</li>
 * </ul>
 */
class MeshConfigPersisterTest {

    private static final String DATA_DIR = "./target/test-data/mesh-persister-test";
    private static final String NODE_ID = "nodeABCdef1234567890";

    private MeshConfigPersister persister;

    @BeforeEach
    void setUp() throws IOException {
        cleanDir();
        Files.createDirectories(Paths.get(DATA_DIR));
        persister = new MeshConfigPersister(DATA_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        cleanDir();
    }

    private void cleanDir() throws IOException {
        Path dir = Paths.get(DATA_DIR);
        if (Files.exists(dir)) {
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
    }

    // ==================== 往返一致性 ====================

    @Test
    void saveLoadRoundTrip_basicFields_preserved() throws IOException {
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.votedFor = "xyz789";
        state.lastIncludedIndex = 100L;
        state.lastIncludedTerm = 4L;
        // 运行时字段不应被持久化（load 后应重置）
        state.commitIndex = 999L;
        state.lastApplied = 999L;

        persister.save(state, NODE_ID);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(5L, loaded.currentTerm);
        assertEquals("xyz789", loaded.votedFor);
        assertEquals(100L, loaded.lastIncludedIndex);
        assertEquals(4L, loaded.lastIncludedTerm);
        // 运行时字段：commitIndex/lastApplied 重置为 lastIncludedIndex（不持久化）
        assertEquals(100L, loaded.commitIndex);
        assertEquals(100L, loaded.lastApplied);
        assertTrue(loaded.log.isEmpty(), "空 logTail 应往返为空 list");
    }

    @Test
    void saveLoadRoundTrip_votedForNull_preserved() throws IOException {
        MeshState state = new MeshState();
        state.currentTerm = 3L;
        state.votedFor = null; // 未投票

        persister.save(state, NODE_ID);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(3L, loaded.currentTerm);
        assertNull(loaded.votedFor, "votedFor=null 应往返为 null");
    }

    @Test
    void saveLoadRoundTrip_logTailMultipleEntries_preserved() throws IOException {
        MeshState state = new MeshState();
        state.currentTerm = 7L;
        state.lastIncludedIndex = 50L;
        state.lastIncludedTerm = 6L;

        // 构造多条 LogEntry（含 dbIndex / extra）
        byte[] payload1 = respFrame("SET", "k1", "v1");
        byte[] payload2 = respFrame("INCR", "counter");
        byte[] payload3 = respFrame("DEL", "k2");
        byte[] extra = new byte[]{1, 2, 3, 4, 5}; // 事务扩展载荷

        state.appendEntry(new LogEntry(7L, 51L, payload1, 0, null));
        state.appendEntry(new LogEntry(7L, 52L, payload2, 1, extra));
        state.appendEntry(new LogEntry(7L, 53L, payload3, 0, null));

        persister.save(state, NODE_ID);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        List<LogEntry> tail = loaded.log;
        assertEquals(3, tail.size());

        // 第一条：term=7 index=51 dbIndex=0 payload 一致 extra=null
        LogEntry e1 = tail.get(0);
        assertEquals(7L, e1.getTerm());
        assertEquals(51L, e1.getIndex());
        assertEquals(0, e1.getDbIndex());
        assertArrayEquals(payload1, e1.getRespPayload());
        assertNull(e1.getExtra());

        // 第二条：含 dbIndex=1 与 extra
        LogEntry e2 = tail.get(1);
        assertEquals(52L, e2.getIndex());
        assertEquals(1, e2.getDbIndex());
        assertArrayEquals(payload2, e2.getRespPayload());
        assertArrayEquals(extra, e2.getExtra(), "extra base64 往返应一致");

        // 第三条
        LogEntry e3 = tail.get(2);
        assertEquals(53L, e3.getIndex());
        assertArrayEquals(payload3, e3.getRespPayload());
        assertNull(e3.getExtra());
    }

    @Test
    void saveLoadRoundTrip_binaryPayloadWithNonUtf8_preserved() throws IOException {
        // 二进制 payload（含 0x00 / 0xFF 等非 UTF-8 字节）→ base64 编解码后应一致
        byte[] binary = new byte[256];
        for (int i = 0; i < 256; i++) {
            binary[i] = (byte) i;
        }
        MeshState state = new MeshState();
        state.currentTerm = 1L;
        state.appendEntry(new LogEntry(1L, 1L, binary, 0, binary));

        persister.save(state, NODE_ID);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(1, loaded.log.size());
        assertArrayEquals(binary, loaded.log.get(0).getRespPayload(),
                "全字节二进制 payload 经 base64 往返应一致");
        assertArrayEquals(binary, loaded.log.get(0).getExtra(),
                "全字节二进制 extra 经 base64 往返应一致");
    }

    @Test
    void saveOverwrite_replacesPreviousContent() throws IOException {
        MeshState s1 = new MeshState();
        s1.currentTerm = 1L;
        s1.votedFor = "a";
        persister.save(s1, NODE_ID);

        MeshState s2 = new MeshState();
        s2.currentTerm = 9L;
        s2.votedFor = "b";
        persister.save(s2, NODE_ID);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(9L, loaded.currentTerm, "二次 save 应覆盖第一次");
        assertEquals("b", loaded.votedFor);
    }

    // ==================== 文件不存在 → null ====================

    @Test
    void load_fileNotExists_returnsNull() throws IOException {
        MeshState loaded = persister.load(NODE_ID);
        assertNull(loaded, "文件不存在时应返回 null（首次启动）");
    }

    // ==================== 文件损坏 → 抛异常（不静默重置 term）====================

    @Test
    void load_corruptJson_throwsParseException() throws IOException {
        // 写入损坏的 JSON
        Files.write(persister.getRaftNodesFile(),
                "{ this is not valid json".getBytes(StandardCharsets.UTF_8));

        assertThrows(MeshConfigPersister.MeshConfigParseException.class,
                () -> persister.load(NODE_ID),
                "JSON 解析失败应抛异常，不静默重置 term（DESIGN §5.5）");
    }

    @Test
    void load_missingCurrentTerm_throwsParseException() throws IOException {
        // 缺少必填字段 currentTerm
        Files.write(persister.getRaftNodesFile(),
                ("{\"nodeId\":\"" + NODE_ID + "\",\"votedFor\":\"x\"}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThrows(MeshConfigPersister.MeshConfigParseException.class,
                () -> persister.load(NODE_ID),
                "缺少 currentTerm 应抛异常");
    }

    @Test
    void load_nonObjectTopLevel_throwsParseException() throws IOException {
        Files.write(persister.getRaftNodesFile(),
                "[1,2,3]".getBytes(StandardCharsets.UTF_8));

        assertThrows(MeshConfigPersister.MeshConfigParseException.class,
                () -> persister.load(NODE_ID));
    }

    // ==================== 原子性：tmp 文件清理 ====================

    @Test
    void save_completes_noLeftoverTmpFiles() throws IOException {
        MeshState state = new MeshState();
        state.currentTerm = 2L;
        persister.save(state, NODE_ID);

        // save 成功后：raft-nodes.conf 存在，无残留 .tmp 文件
        assertTrue(Files.exists(persister.getRaftNodesFile()));
        long tmpCount = Files.list(Paths.get(DATA_DIR))
                .filter(p -> p.getFileName().toString().contains(".tmp."))
                .count();
        assertEquals(0L, tmpCount, "save 成功后不应残留 tmp 文件");
    }

    @Test
    void save_multipleTimesSameThread_reusesButCleansTmp() throws IOException {
        MeshState state = new MeshState();
        state.currentTerm = 1L;
        // 同线程多次 save（复用相同 tmp 名）→ 每次都应清理
        for (int i = 0; i < 5; i++) {
            state.currentTerm = i + 1;
            persister.save(state, NODE_ID);
        }
        long tmpCount = Files.list(Paths.get(DATA_DIR))
                .filter(p -> p.getFileName().toString().contains(".tmp."))
                .count();
        assertEquals(0L, tmpCount);

        MeshState loaded = persister.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(5L, loaded.currentTerm);
    }

    // ==================== dump.rdb.index 辅助文件 ====================

    @Test
    void dumpRdbIndex_saveLoadRoundTrip() throws IOException {
        persister.saveDumpRdbIndex(12345L);
        assertEquals(12345L, persister.loadDumpRdbIndex());
    }

    @Test
    void dumpRdbIndex_notExists_returnsMinusOne() throws IOException {
        assertEquals(-1L, persister.loadDumpRdbIndex(),
                "dump.rdb.index 不存在时返回 -1（无记录）");
    }

    @Test
    void dumpRdbIndex_corrupt_throwsParseException() throws IOException {
        Files.write(persister.dumpRdbIndexFile(),
                "not-a-number".getBytes(StandardCharsets.UTF_8));
        assertThrows(MeshConfigPersister.MeshConfigParseException.class,
                () -> persister.loadDumpRdbIndex());
    }

    @Test
    void dumpRdbIndex_overwrite_replacesPrevious() throws IOException {
        persister.saveDumpRdbIndex(100L);
        persister.saveDumpRdbIndex(200L);
        assertEquals(200L, persister.loadDumpRdbIndex());
    }

    // ==================== FileBasedPersistentStateStore 委托 ====================

    @Test
    void fileBasedStore_persistLoad_delegatesToPersister() {
        com.janeluo.luban.rds.mesh.core.FileBasedPersistentStateStore store =
                new com.janeluo.luban.rds.mesh.core.FileBasedPersistentStateStore(DATA_DIR);

        MeshState state = new MeshState();
        state.currentTerm = 8L;
        state.votedFor = "cand1";
        state.lastIncludedIndex = 20L;
        state.lastIncludedTerm = 7L;

        store.persist(state, NODE_ID);

        MeshState loaded = store.load(NODE_ID);
        assertNotNull(loaded);
        assertEquals(8L, loaded.currentTerm);
        assertEquals("cand1", loaded.votedFor);
        assertEquals(20L, loaded.lastIncludedIndex);
        assertEquals(7L, loaded.lastIncludedTerm);
    }

    @Test
    void fileBasedStore_load_fileNotExists_returnsNull() {
        com.janeluo.luban.rds.mesh.core.FileBasedPersistentStateStore store =
                new com.janeluo.luban.rds.mesh.core.FileBasedPersistentStateStore(DATA_DIR);
        assertNull(store.load(NODE_ID));
    }

    // ==================== 辅助 ====================

    /** 构造一个完整 RESP 命令帧（与 LogApplierTest 一致）。 */
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
}
