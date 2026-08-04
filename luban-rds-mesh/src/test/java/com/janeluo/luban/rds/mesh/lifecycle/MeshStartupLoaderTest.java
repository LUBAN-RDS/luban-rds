package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MeshStartupLoader} 单元测试（阶段 11 / DESIGN §5.5）。
 *
 * <p>覆盖三种启动场景：</p>
 * <ul>
 *   <li><b>首次启动</b>：无 raft-nodes.conf → 空 state（currentTerm=1），可信。</li>
 *   <li><b>正常恢复</b>：raft-nodes.conf + dump.rdb（索引匹配）+ logTail → 加载后状态正确。</li>
 *   <li><b>不可信衔接</b>：dump.rdb 索引 ≠ lastIncludedIndex（或 dump.rdb 丢失）→ 标记不可信，内存空。</li>
 * </ul>
 *
 * <p>用真实 {@link RdbPersistService} + {@link RdbSnapshotGenerator}（小数据集 + 临时目录），
 * 覆盖 raft-nodes.conf ↔ dump.rdb ↔ logTail 重放的完整链路。</p>
 */
class MeshStartupLoaderTest {

    private static final String DATA_DIR = "./target/test-data/mesh-startup-loader-test";
    private static final String NODE_ID = "nodeXYZ1234567890abcd";

    private RdbPersistService persistService;
    private RdbSnapshotGenerator snapshotGenerator;
    private MeshConfigPersister persister;

    @BeforeEach
    void setUp() throws IOException {
        cleanDir();
        Files.createDirectories(Paths.get(DATA_DIR));
        persistService = new RdbPersistService(DATA_DIR);
        snapshotGenerator = new RdbSnapshotGenerator(persistService, DATA_DIR);
        persister = new MeshConfigPersister(DATA_DIR);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (persistService != null) {
            persistService.close();
        }
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

    // ==================== 场景 1：首次启动 ====================

    @Test
    void load_firstStart_noRaftNodesConf_emptyStateTerm1() throws IOException {
        MemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertTrue(result.firstStart, "无 raft-nodes.conf → 首次启动");
        assertTrue(result.isTrusted, "首次启动视为可信空状态");
        assertNotNull(result.state);
        assertEquals(1L, result.state.currentTerm, "首次启动 currentTerm=1");
        assertEquals(0L, result.state.lastIncludedIndex);
        assertEquals(0L, result.state.lastApplied);
        assertEquals(0, result.state.log.size());
        assertEquals(0L, result.replayedCount);
    }

    // ==================== 场景 2：正常恢复（dump.rdb 衔接 + logTail 重放）====================

    @Test
    void load_normalRecovery_dumpRdbAndLogTail_stateCorrect() throws IOException {
        // 1. 生成 dump.rdb（含 k1=v1），对应 lastIncludedIndex=10
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        snapStore.set(0, "k1", "v1");
        File dump = generateDumpRdb(snapStore);
        assertTrue(dump.exists(), "dump.rdb 应已生成");
        // 记录 dump.rdb 对应的 lastIncludedIndex
        persister.saveDumpRdbIndex(10L);

        // 2. 构造 raft-nodes.conf：lastIncludedIndex=10，logTail 含 SET k2=v2 (index=11)
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.votedFor = "someCand";
        state.lastIncludedIndex = 10L;
        state.lastIncludedTerm = 4L;
        byte[] setFrame = respFrame("SET", "k2", "v2");
        state.appendEntry(new LogEntry(5L, 11L, setFrame, 0, null));
        persister.save(state, NODE_ID);

        // 3. 启动加载（raw store 初始为空）
        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        // 4. 断言：可信衔接
        assertFalse(result.firstStart);
        assertTrue(result.isTrusted, "dump.rdb 索引匹配 lastIncludedIndex → 可信");
        assertEquals(1L, result.replayedCount, "logTail 1 条应被重放");
        assertEquals(11L, result.state.lastApplied, "lastApplied 推进到 11");
        assertEquals(11L, result.state.commitIndex, "commitIndex 推进到 11");

        // 快照载入：k1=v1（来自 dump.rdb）
        assertEquals("v1", rawStore.get(0, "k1"), "dump.rdb 载入应恢复 k1=v1");
        // tail 重放：k2=v2（来自 logTail）
        assertEquals("v2", rawStore.get(0, "k2"), "logTail 重放应应用 SET k2=v2");
    }

    @Test
    void load_normalRecovery_emptyLogTail_onlySnapshotLoaded() throws IOException {
        // dump.rdb 对应 lastIncludedIndex=20，logTail 为空
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        snapStore.set(0, "a", "1");
        snapStore.set(0, "b", "2");
        generateDumpRdb(snapStore);
        persister.saveDumpRdbIndex(20L);

        MeshState state = new MeshState();
        state.currentTerm = 3L;
        state.lastIncludedIndex = 20L;
        state.lastIncludedTerm = 2L;
        // logTail 空（已全部被快照截断）
        persister.save(state, NODE_ID);

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertTrue(result.isTrusted);
        assertEquals(0L, result.replayedCount, "空 logTail 无需重放");
        assertEquals(20L, result.state.lastApplied, "lastApplied=lastIncludedIndex");
        assertEquals("1", rawStore.get(0, "a"));
        assertEquals("2", rawStore.get(0, "b"));
    }

    @Test
    void load_normalRecovery_logTailMultipleEntries_appliedInOrder() throws IOException {
        // dump.rdb 含 counter=5（lastIncludedIndex=1）
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        snapStore.set(0, "counter", "5");
        generateDumpRdb(snapStore);
        persister.saveDumpRdbIndex(1L);

        // logTail: INCR counter (index=2), INCR counter (index=3)
        // 重放后 counter 应为 7（依赖快照前置值 5，验证"快照 + tail 重放 = 完整状态"）
        MeshState state = new MeshState();
        state.currentTerm = 2L;
        state.lastIncludedIndex = 1L;
        state.lastIncludedTerm = 1L;
        byte[] incr = respFrame("INCR", "counter");
        state.appendEntry(new LogEntry(2L, 2L, incr, 0, null));
        state.appendEntry(new LogEntry(2L, 3L, incr, 0, null));
        persister.save(state, NODE_ID);

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertTrue(result.isTrusted);
        assertEquals(2L, result.replayedCount);
        assertEquals("7", rawStore.get(0, "counter"),
                "快照(5) + INCR + INCR = 7，验证 tail 重放依赖快照前置值");
    }

    // ==================== 场景 3：不可信衔接 ====================

    @Test
    void load_untrustedDumpRdbIndexMismatch_markedUntrusted() throws IOException {
        // dump.rdb 存在但 index(5) ≠ lastIncludedIndex(10)
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        snapStore.set(0, "k1", "v1");
        generateDumpRdb(snapStore);
        persister.saveDumpRdbIndex(5L); // 与 raft-nodes.conf 的 10 不匹配

        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.lastIncludedIndex = 10L;
        state.lastIncludedTerm = 4L;
        state.appendEntry(new LogEntry(5L, 11L, respFrame("SET", "k2", "v2"), 0, null));
        persister.save(state, NODE_ID);

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertFalse(result.isTrusted, "dump.rdb 索引不匹配 → 不可信");
        assertEquals(0L, result.replayedCount, "不可信时不应重放 tail");
        assertNull(rawStore.get(0, "k1"), "不可信时内存为空（不加载 dump.rdb）");
        assertNull(rawStore.get(0, "k2"), "不可信时不重放 tail（k2 不存在）");
    }

    @Test
    void load_untrustedDumpRdbMissingButSnapshotHistoryExists_markedUntrusted() throws IOException {
        // 无 dump.rdb 但 lastIncludedIndex > 0（曾快照过）→ 不可信
        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.lastIncludedIndex = 50L; // 曾快照过
        state.lastIncludedTerm = 4L;
        persister.save(state, NODE_ID);
        // 不生成 dump.rdb

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertFalse(result.isTrusted, "无 dump.rdb 但 lastIncludedIndex>0 → 不可信");
    }

    @Test
    void load_noDumpRdbNeverSnapshotted_trustedEmptyMemory() throws IOException {
        // 无 dump.rdb 且 lastIncludedIndex=0（从未快照）→ 可信（无快照历史是正常的）
        MeshState state = new MeshState();
        state.currentTerm = 2L;
        state.lastIncludedIndex = 0L; // 从未快照
        state.lastIncludedTerm = 0L;
        state.appendEntry(new LogEntry(2L, 1L, respFrame("SET", "k", "v"), 0, null));
        persister.save(state, NODE_ID);
        // 不生成 dump.rdb

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertTrue(result.isTrusted, "无 dump.rdb 且 lastIncludedIndex=0 → 可信（从未快照）");
        assertEquals(1L, result.replayedCount, "无快照时直接重放全部 log");
        assertEquals("v", rawStore.get(0, "k"), "logTail 重放应应用 SET k v");
    }

    @Test
    void load_dumpRdbExistsButNoIndexFile_markedUntrusted() throws IOException {
        // dump.rdb 存在但 dump.rdb.index 不存在（-1）≠ lastIncludedIndex(10) → 不可信
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        snapStore.set(0, "k", "v");
        generateDumpRdb(snapStore);
        // 故意不写 dump.rdb.index

        MeshState state = new MeshState();
        state.currentTerm = 5L;
        state.lastIncludedIndex = 10L;
        state.lastIncludedTerm = 4L;
        persister.save(state, NODE_ID);

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertFalse(result.isTrusted, "dump.rdb 存在但 index 文件缺失(-1) ≠ 10 → 不可信");
    }

    // ==================== raft-nodes.conf 损坏 → 抛异常（不静默重置）====================

    @Test
    void load_corruptRaftNodesConf_throws() throws IOException {
        Files.write(persister.getRaftNodesFile(),
                "{ invalid json".getBytes(StandardCharsets.UTF_8));

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);

        org.junit.jupiter.api.Assertions.assertThrows(
                MeshConfigPersister.MeshConfigParseException.class,
                () -> loader.load(NODE_ID),
                "raft-nodes.conf 损坏应抛异常（不静默重置 term，DESIGN §5.5）");
    }

    // ==================== 辅助 ====================

    /** 用 RdbSnapshotGenerator 生成 dump.rdb（落盘到 DATA_DIR/dump.rdb）。 */
    private File generateDumpRdb(MemoryStore source) {
        File temp = snapshotGenerator.generateTempRdbFile(source);
        if (temp == null || !temp.exists()) {
            throw new IllegalStateException("生成 RDB 临时文件失败");
        }
        File target = new File(DATA_DIR, "dump.rdb");
        if (target.exists() && !target.delete()) {
            target.deleteOnExit();
        }
        if (!temp.renameTo(target)) {
            // rename 失败时 copy
            try {
                copyFile(temp, target);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            temp.delete();
        }
        return target;
    }

    private void copyFile(File src, File dst) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(src);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
             java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis);
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = bis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        }
    }

    /** 构造一个完整 RESP 命令帧。 */
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
