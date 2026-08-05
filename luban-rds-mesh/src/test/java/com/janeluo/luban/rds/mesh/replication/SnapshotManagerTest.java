package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshBusHandler;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.lifecycle.MeshConfigPersister;
import com.janeluo.luban.rds.mesh.lifecycle.MeshStartupLoader;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
import com.janeluo.luban.rds.mesh.rpc.MeshRpcMessage;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import com.janeluo.luban.rds.replication.RdbDataLoader;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotManager} 单元测试（阶段 10）。
 *
 * <p>聚焦四件事：</p>
 * <ul>
 *   <li><b>Leader chunked 发送</b>：mock 真实 RdbSnapshotGenerator 落盘小数据集，
 *       sendSnapshot 按 4MB（或测试用小 chunk）切片发多个 InstallSnapshotMessage，
 *       offset 递增、最后一片 done=true。</li>
 *   <li><b>Follower 累积拼装 + 加载</b>：收多个 chunk 累积到临时文件，done 后加载进 raw store、
 *       截断 log、更新 commitIndex/lastApplied/lastIncludedIndex/Term、回 AppendEntriesResponse。</li>
 *   <li><b>周期快照</b>：log 达阈值触发 takePeriodicSnapshotIfNeeded，落盘 dump.rdb、截断 log、
 *       更新 lastIncludedIndex/Term。</li>
 *   <li><b>任期校验</b>：过期 term 的 INSTALL_SNAPSHOT 被拒绝（回 success=false）。</li>
 *   <li><b>崩溃时序（index 先落盘修复）</b>：index 新 + conf 旧边界 → loader 判不可信（杜绝双 apply）；
 *       dump.rdb.index 写失败 → 周期快照中止（dump.rdb 不落盘）/ INSTALL_SNAPSHOT 完成路径 ACK false。</li>
 * </ul>
 *
 * <p>使用真实 RdbPersistService + RdbSnapshotGenerator + RdbDataLoader（小数据集 + 临时目录），
 * 既验证 mesh 调度逻辑，也顺带覆盖 replication 模块 RDB 生成/加载的真实链路。</p>
 */
class SnapshotManagerTest {

    private static final String LEADER = "nodeLeader";
    private static final String FOLLOWER = "nodeFollower";
    private static final String NODE_ID = "nodeSnapshotCrashWindow";
    private static final String DATA_DIR = "./target/test-data/snapshot-mgr-test";

    private RdbPersistService persistService;
    private RdbSnapshotGenerator snapshotGenerator;
    private CaptureBus bus;

    @BeforeEach
    void setUp() {
        cleanDir();
        File dir = new File(DATA_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        persistService = new RdbPersistService(DATA_DIR);
        snapshotGenerator = new RdbSnapshotGenerator(persistService, DATA_DIR);
        bus = new CaptureBus();
    }

    @AfterEach
    void tearDown() {
        if (persistService != null) {
            persistService.close();
        }
        cleanDir();
    }

    private void cleanDir() {
        File dir = new File(DATA_DIR);
        if (dir.exists()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) {
                    f.delete();
                }
            }
            dir.delete();
        }
    }

    /** 捕获所有发出的帧（不发真实网络）。 */
    private static class CaptureBus extends MeshBusClient {
        final Map<String, List<MeshFrame>> sent = new HashMap<>();

        CaptureBus() {
            super(LEADER, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(frame);
        }

        List<MeshFrame> framesTo(String to) {
            return sent.getOrDefault(to, new ArrayList<>());
        }

        void clear() {
            sent.clear();
        }
    }

    /** Follower 视角捕获 bus（selfNodeId=FOLLOWER），ACK 帧会被捕获到 {@link #sent}。 */
    private static class FollowerCaptureBus extends MeshBusClient {
        final Map<String, List<MeshFrame>> sent = new HashMap<>();

        FollowerCaptureBus() {
            super(FOLLOWER, new MeshBusHandler());
        }

        @Override
        public void send(String targetNodeId, MeshFrame frame) {
            sent.computeIfAbsent(targetNodeId, k -> new ArrayList<>()).add(frame);
        }

        List<MeshFrame> framesTo(String to) {
            return sent.getOrDefault(to, new ArrayList<>());
        }

        void clear() {
            sent.clear();
        }
    }

    /** 构造一个 Follower 视角 SnapshotManager，并返回其内部 bus（可读取 ACK 帧）。 */
    private FollowerFixture newFollowerManager(MeshState state, MemoryStore rawStore, int chunkSize) {
        return newFollowerManager(state, rawStore, chunkSize, null);
    }

    /** 同上，但可注入自定义 dumpRdbIndexWriter hook（测试 index 写失败路径）。 */
    private FollowerFixture newFollowerManager(MeshState state, MemoryStore rawStore, int chunkSize,
                                               LongConsumer dumpRdbIndexWriter) {
        RdbDataLoader loader = new RdbDataLoader(persistService, DATA_DIR);
        FollowerCaptureBus fbus = new FollowerCaptureBus();
        SnapshotManager mgr = new SnapshotManager(FOLLOWER, state, fbus, rawStore,
                snapshotGenerator, loader, DATA_DIR, chunkSize,
                SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null, dumpRdbIndexWriter);
        return new FollowerFixture(mgr, fbus);
    }

    /** Follower 测试夹具：SnapshotManager + 其 bus。 */
    private static final class FollowerFixture {
        final SnapshotManager mgr;
        final FollowerCaptureBus bus;

        FollowerFixture(SnapshotManager mgr, FollowerCaptureBus bus) {
            this.mgr = mgr;
            this.bus = bus;
        }
    }

    // ==================== Leader 侧：chunked 发送 ====================

    @Test
    void sendSnapshot_chunksRdbBy4mb_lastChunkDoneTrue() {
        // 小数据集：生成 < 4MB 的 RDB，验证默认 chunk 大小下发 1 个 done=true 帧
        MemoryStore store = new DefaultMemoryStore();
        store.set(0, "k1", "v1");
        store.set(0, "k2", "v2");

        SnapshotManager leaderMgr = new SnapshotManager(LEADER, new MeshState(), bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR);

        long sent = leaderMgr.sendSnapshot(FOLLOWER);

        assertTrue(sent > 0, "应发送了字节");
        List<MeshFrame> frames = bus.framesTo(FOLLOWER);
        assertFalse(frames.isEmpty(), "应至少发出 1 个 INSTALL_SNAPSHOT 帧");
        // 全部帧都是 INSTALL_SNAPSHOT
        for (MeshFrame f : frames) {
            assertEquals(MessageType.INSTALL_SNAPSHOT.getCode(), f.getType());
        }

        // 默认 chunk 4MB，小数据集应只有 1 帧，且 done=true，offset=0
        assertEquals(1, frames.size(), "小数据集应单帧");
        InstallSnapshotMessage m = decodeSnapshot(frames.get(0));
        assertEquals(0L, m.getOffset());
        assertTrue(m.isDone());
        assertTrue(m.getData().length > 0);

        // lastIncludedIndex/Term 在 sendSnapshot 中由 state 推算（空 state → lastApplied=0 → lastIncluded=0）
        assertEquals(0L, m.getLastIncludedIndex());
    }

    @Test
    void sendSnapshot_smallChunk_multipleFramesOffsetIncreasing() {
        // 用小 chunk（256B）强制多帧
        MemoryStore store = new DefaultMemoryStore();
        // 写入足够数据使 RDB > 256B
        for (int i = 0; i < 50; i++) {
            store.set(0, "key" + i, "value-padding-" + i + "-xxxxxxxxxxxxxxxxxxxxxx");
        }
        SnapshotManager leaderMgr = new SnapshotManager(LEADER, new MeshState(), bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                256, SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null);

        long sent = leaderMgr.sendSnapshot(FOLLOWER);
        assertTrue(sent > 0);

        List<MeshFrame> frames = bus.framesTo(FOLLOWER);
        assertTrue(frames.size() > 1, "小 chunk 应产生多帧, 实际=" + frames.size());

        // 验证 offset 连续递增，所有非最后帧 done=false，最后帧 done=true
        long expectedOffset = 0;
        boolean seenDone = false;
        long totalData = 0;
        for (int i = 0; i < frames.size(); i++) {
            InstallSnapshotMessage m = decodeSnapshot(frames.get(i));
            assertEquals(expectedOffset, m.getOffset(), "帧 " + i + " offset 应连续");
            totalData += m.getData().length;
            expectedOffset += m.getData().length;
            boolean isLast = (i == frames.size() - 1);
            assertEquals(isLast, m.isDone(), "帧 " + i + " done 标记应正确");
            if (isLast) {
                seenDone = true;
            }
        }
        assertTrue(seenDone, "应有 done=true 的最后帧");
        assertEquals(sent, totalData, "各 chunk 字节累加应等于 sendSnapshot 返回值");
    }

    // ==================== Follower 侧：累积拼装 + 加载 + 截断 + 更新状态 ====================

    @Test
    void handleInstallSnapshot_accumulatesChunks_loadsAndTruncatesLog() {
        // Leader 先生成快照字节序列
        MemoryStore leaderStore = new DefaultMemoryStore();
        leaderStore.set(0, "alpha", "1");
        leaderStore.set(0, "beta", "2");
        leaderStore.set(0, "gamma", "3");

        // 用很小的 chunk（16B）强制 Leader 发多个帧（RDB 文件头就 9 字节 + 数据，必 > 16B）
        SnapshotManager leaderMgr = new SnapshotManager(LEADER, new MeshState(), bus, leaderStore,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                16, SnapshotManager.DEFAULT_SNAPSHOT_LOG_THRESHOLD, null);
        leaderMgr.sendSnapshot(FOLLOWER);
        List<MeshFrame> leaderFrames = bus.framesTo(FOLLOWER);
        assertTrue(leaderFrames.size() > 1, "应多帧, 实际=" + leaderFrames.size());

        // Follower 侧准备：lastIncludedIndex=0，preinstall 一些 log（应被快照覆盖/截断）
        MeshState followerState = new MeshState();
        followerState.currentTerm = 5;
        followerState.commitIndex = 3;
        followerState.lastApplied = 3;
        // 加几条旧 log（index 1,2,3），快照边界 lastIncludedIndex=10 会覆盖它们
        followerState.appendEntry(new LogEntry(5, 1, new byte[]{1}, 0, null));
        followerState.appendEntry(new LogEntry(5, 2, new byte[]{2}, 0, null));
        followerState.appendEntry(new LogEntry(5, 3, new byte[]{3}, 0, null));

        MemoryStore followerStore = new DefaultMemoryStore();
        FollowerFixture ff = newFollowerManager(followerState, followerStore, 16);

        // 把每个 leader 帧（含正确的 lastIncludedIndex=10）依次投递给 followerMgr
        long snapIndex = 10;
        long snapTerm = 5;
        for (MeshFrame f : leaderFrames) {
            InstallSnapshotMessage orig = decodeSnapshot(f);
            // 改写 lastIncludedIndex/Term/term 为本次测试期望值
            InstallSnapshotMessage rewritten = new InstallSnapshotMessage(
                    6L, LEADER, snapTerm, snapIndex,
                    orig.getOffset(), orig.getData(), orig.isDone());
            ff.mgr.handleInstallSnapshot(LEADER, rewritten);
        }

        // Follower 应：加载 3 个 key、截断 log（≤10 全删）、更新 commitIndex/lastApplied/lastIncluded
        assertEquals(snapIndex, followerState.lastIncludedIndex, "lastIncludedIndex 应推进到 snapIndex");
        assertEquals(5L, followerState.lastIncludedTerm);
        assertEquals(0, followerState.log.size(), "log 应被清空（全部 ≤ snapIndex）");
        assertEquals(snapIndex, followerState.commitIndex, "commitIndex 应推进到 snapIndex");
        assertEquals(snapIndex, followerState.lastApplied, "lastApplied 应推进到 snapIndex");
        // raw store 应有 3 个 key
        assertEquals("1", followerStore.get(0, "alpha"));
        assertEquals("2", followerStore.get(0, "beta"));
        assertEquals("3", followerStore.get(0, "gamma"));

        // Follower 应回了 AppendEntriesResponse（success=true, matchIndex=snapIndex）
        List<MeshFrame> acks = ff.bus.framesTo(LEADER);
        assertFalse(acks.isEmpty(), "应回了 ACK");
        MeshFrame ackFrame = acks.get(acks.size() - 1);
        assertEquals(MessageType.APPEND_ENTRIES_RESP.getCode(), ackFrame.getType());
        AppendEntriesResponse ack = decodeAppendResp(ackFrame);
        assertTrue(ack.isSuccess(), "ACK success 应为 true");
        assertEquals(snapIndex, ack.getMatchIndex(), "ACK matchIndex 应为 snapIndex");
    }

    @Test
    void handleInstallSnapshot_rejectsStaleTerm() {
        MeshState state = new MeshState();
        state.currentTerm = 10;
        MemoryStore store = new DefaultMemoryStore();
        FollowerFixture ff = newFollowerManager(state, store, 1024);

        // term=5 < currentTerm=10 → 拒绝
        InstallSnapshotMessage msg = new InstallSnapshotMessage(
                5L, LEADER, 4L, 1L, 0L, new byte[]{1, 2}, true);
        ff.mgr.handleInstallSnapshot(LEADER, msg);

        // currentTerm 不变（不更新），lastIncluded 不变
        assertEquals(10L, state.currentTerm);
        assertEquals(0L, state.lastIncludedIndex);

        // 应回 success=false
        List<MeshFrame> acks = ff.bus.framesTo(LEADER);
        assertFalse(acks.isEmpty());
        AppendEntriesResponse ack = decodeAppendResp(acks.get(0));
        assertFalse(ack.isSuccess(), "过期任期应回 success=false");
    }

    @Test
    void handleInstallSnapshot_offsetMismatch_dropsChunk() {
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MemoryStore store = new DefaultMemoryStore();
        FollowerFixture ff = newFollowerManager(state, store, 1024);

        // 第一片 offset=0 正常
        InstallSnapshotMessage first = new InstallSnapshotMessage(
                6L, LEADER, 5L, 10L, 0L, new byte[]{1, 2, 3}, false);
        ff.mgr.handleInstallSnapshot(LEADER, first);
        assertEquals(3L, ff.mgr.getReceivedBytes());

        // 第二片 offset 不连续（期望 3，给 100）→ 丢弃，receivedBytes 不变
        InstallSnapshotMessage bad = new InstallSnapshotMessage(
                6L, LEADER, 5L, 10L, 100L, new byte[]{4, 5}, false);
        ff.mgr.handleInstallSnapshot(LEADER, bad);
        assertEquals(3L, ff.mgr.getReceivedBytes(), "offset 不连续的 chunk 应被丢弃");
    }

    @Test
    void handleInstallSnapshot_newSessionDiscardsOld() {
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MemoryStore store = new DefaultMemoryStore();
        FollowerFixture ff = newFollowerManager(state, store, 1024);

        // 会话 A：lastIncluded=10
        ff.mgr.handleInstallSnapshot(LEADER, new InstallSnapshotMessage(
                6L, LEADER, 5L, 10L, 0L, new byte[]{1, 2}, false));
        assertNotNull(ff.mgr.getIncoming());

        // 会话 B（不同 lastIncludedIndex）→ 作废旧会话，开新会话
        ff.mgr.handleInstallSnapshot(LEADER, new InstallSnapshotMessage(
                6L, LEADER, 5L, 20L, 0L, new byte[]{9}, false));
        assertNotNull(ff.mgr.getIncoming());
        assertEquals(20L, ff.mgr.getIncoming().lastIncludedIndex, "应为新会话 lastIncluded=20");
        assertEquals(1L, ff.mgr.getReceivedBytes(), "新会话 receivedBytes 应重置");
    }

    // ==================== 周期快照 ====================

    @Test
    void takePeriodicSnapshot_belowThreshold_noOp() {
        MeshState state = new MeshState();
        state.lastApplied = 5;
        MemoryStore store = new DefaultMemoryStore();
        store.set(0, "k", "v");
        // 阈值 100，log 只有几条
        SnapshotManager mgr = new SnapshotManager(LEADER, state, bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                4096, 100, null);

        boolean triggered = mgr.takePeriodicSnapshotIfNeeded();
        assertFalse(triggered, "未达阈值不应触发");
        assertEquals(0L, state.lastIncludedIndex, "lastIncluded 不应变");
    }

    @Test
    void takePeriodicSnapshot_atThreshold_truncatesLogUpdatesLastIncluded() {
        MeshState state = new MeshState();
        state.currentTerm = 3;
        state.lastApplied = 5; // 快照到 index 5
        // 加 5 条 log（index 1..5），达阈值
        for (int i = 1; i <= 5; i++) {
            state.appendEntry(new LogEntry(3, i, new byte[]{(byte) i}, 0, null));
        }
        MemoryStore store = new DefaultMemoryStore();
        store.set(0, "k1", "v1");
        store.set(0, "k2", "v2");

        // 阈值=5，chunk=4KB
        SnapshotManager mgr = new SnapshotManager(LEADER, state, bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                4096, 5, null);

        boolean triggered = mgr.takePeriodicSnapshotIfNeeded();
        assertTrue(triggered, "达阈值应触发");

        assertEquals(5L, state.lastIncludedIndex, "lastIncludedIndex 应推进到 lastApplied");
        assertEquals(3L, state.lastIncludedTerm, "lastIncludedTerm 应为 index5 对应 term");
        assertEquals(0, state.log.size(), "log 应被全部丢弃（≤5 全删）");

        // dump.rdb 应已落盘（唯一写者）
        File dump = new File(DATA_DIR, "dump.rdb");
        assertTrue(dump.exists(), "dump.rdb 应已落盘");
        assertTrue(dump.length() > 0);
    }

    @Test
    void takePeriodicSnapshot_noNewProgress_skip() {
        MeshState state = new MeshState();
        state.lastIncludedIndex = 10;
        state.lastApplied = 5; // lastApplied < lastIncludedIndex（异常但应安全跳过）
        MemoryStore store = new DefaultMemoryStore();
        SnapshotManager mgr = new SnapshotManager(LEADER, state, bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                4096, 1, null);
        boolean triggered = mgr.takePeriodicSnapshotIfNeeded();
        assertFalse(triggered, "snapshotIndex <= lastIncluded 应跳过");
    }

    // ==================== 崩溃时序（index 先落盘修复，Task 4）====================

    @Test
    void snapshotCrashWindow_indexNewerThanConf_loaderMarksUntrusted() throws Exception {
        // 崩溃磁盘态（修复后 index 先落盘，d18c6a6）：
        //   dump.rdb.index=新(3) 已先落盘、dump.rdb=新快照(3) 已写完、raft-nodes.conf=旧边界(0，conf 未更新)
        //   → loader 比对 dump.rdb.index(3) != lastIncludedIndex(0) → 判不可信 → 全量追平，
        //     杜绝「信任误判 → 载入新 rdb + 重放 (旧,新] 条目 → 双 apply」（INCR 等非幂等命令）
        MeshState state = new MeshState();
        state.currentTerm = 1L;
        for (long i = 1; i <= 5; i++) {
            state.appendEntry(new LogEntry(1L, i, respFrame("SET", "k" + i, "v"), 0, null));
        }
        MeshConfigPersister persister = new MeshConfigPersister(DATA_DIR);
        persister.save(state, NODE_ID);

        // 模拟崩溃前已完成的写入：新快照 rdb（边界 3）+ index=3 先落盘；conf 仍为旧边界 0
        DefaultMemoryStore snapStore = new DefaultMemoryStore();
        for (long i = 1; i <= 3; i++) {
            snapStore.set(0, "k" + i, "v");
        }
        assertTrue(generateDumpRdb(snapStore).exists(), "dump.rdb（新快照）应已落盘");
        persister.saveDumpRdbIndex(3L); // index 先落盘（新值 3），conf 仍为旧边界 0

        DefaultMemoryStore rawStore = new DefaultMemoryStore();
        LogApplier applier = new LogApplier(new DefaultCommandHandler(), rawStore);
        MeshStartupLoader loader = new MeshStartupLoader(
                persister, persistService, applier, rawStore, DATA_DIR);
        MeshStartupLoader.StartupResult result = loader.load(NODE_ID);

        assertFalse(result.isTrusted, "index≠lastIncludedIndex 应判不可信，避免载入新快照后双 apply");
        assertEquals(0L, result.replayedCount, "不可信时不应重放 logTail");
        assertNull(rawStore.get(0, "k1"), "不可信时内存为空（不载入新 dump.rdb）");
    }

    @Test
    void takePeriodicSnapshot_indexWriteFails_abortsBeforeWritingDumpRdb() {
        MeshState state = new MeshState();
        state.currentTerm = 3;
        state.lastApplied = 5; // 快照到 index 5
        for (int i = 1; i <= 5; i++) {
            state.appendEntry(new LogEntry(3, i, new byte[]{(byte) i}, 0, null));
        }
        MemoryStore store = new DefaultMemoryStore();
        store.set(0, "k1", "v1");

        // dump.rdb.index 写入失败（模拟磁盘错误）→ 必须中止快照，dump.rdb 不得被写入
        //（若继续写 rdb，index 仍为旧值 → 重启信任误判窗口复开）
        SnapshotManager mgr = new SnapshotManager(LEADER, state, bus, store,
                snapshotGenerator, new RdbDataLoader(persistService, DATA_DIR), DATA_DIR,
                4096, 5, null,
                idx -> { throw new IllegalStateException("simulated index write failure"); });

        boolean triggered = mgr.takePeriodicSnapshotIfNeeded();
        assertFalse(triggered, "dump.rdb.index 写失败应中止快照");

        File dump = new File(DATA_DIR, "dump.rdb");
        assertFalse(dump.exists(), "index 写失败时 dump.rdb 不得被写入");
        assertEquals(0L, state.lastIncludedIndex, "lastIncludedIndex 不应推进");
        assertEquals(5, state.log.size(), "log 不应被截断");
    }

    @Test
    void handleInstallSnapshot_done_indexWriteFails_acksFalse() {
        MeshState state = new MeshState();
        state.currentTerm = 5;
        MemoryStore store = new DefaultMemoryStore();
        FollowerFixture ff = newFollowerManager(state, store, 1024,
                idx -> { throw new IllegalStateException("simulated index write failure"); });

        // done=true 完成路径：先落盘 dump.rdb.index 失败 → 中止安装、回 success=false（Leader 重发快照），
        // 绝不带着旧 index 继续加载/应用快照
        ff.mgr.handleInstallSnapshot(LEADER, new InstallSnapshotMessage(
                6L, LEADER, 5L, 10L, 0L, new byte[0], true));

        List<MeshFrame> acks = ff.bus.framesTo(LEADER);
        assertFalse(acks.isEmpty(), "应回了 ACK");
        AppendEntriesResponse ack = decodeAppendResp(acks.get(0));
        assertFalse(ack.isSuccess(), "index 写失败应回 success=false");
        assertEquals(0L, state.lastIncludedIndex, "快照不应被应用（lastIncluded 不变）");
        assertNull(ff.mgr.getIncoming(), "失败后会话应被清理");
    }

    // ==================== 工具 ====================

    private static InstallSnapshotMessage decodeSnapshot(MeshFrame f) {
        MeshRpcMessage msg = MeshRpcMessage.decode(MessageType.fromCode(f.getType()), f.getBody());
        return (InstallSnapshotMessage) msg;
    }

    @SuppressWarnings("unused")
    private static AppendEntriesResponse decodeAppendResp(MeshFrame f) {
        MeshRpcMessage msg = MeshRpcMessage.decode(MessageType.fromCode(f.getType()), f.getBody());
        return (AppendEntriesResponse) msg;
    }

    /** 用 RdbSnapshotGenerator 生成 dump.rdb（模拟崩溃前已落盘的新快照）。 */
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
