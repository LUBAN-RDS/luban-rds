package com.janeluo.luban.rds.mesh.lifecycle;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.replication.LogApplier;
import com.janeluo.luban.rds.persistence.impl.RdbPersistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Mesh 启动加载器（DESIGN §5.5 / IMPLEMENTATION_PLAN 阶段 11）。
 *
 * <p>严格按 DESIGN §5.5 的启动加载顺序恢复节点状态：</p>
 * <ol>
 *   <li>加载 raft-nodes.conf → {@link MeshState}（含 lastIncludedIndex/lastIncludedTerm）；
 *       文件不存在则新建空 state（{@code currentTerm=1}，<b>非 0</b>，避免首次启动 term=0 与
 *       「未投票」语义混淆，且 Raft term 从 1 起算更符合常规约定）。</li>
 *   <li>检查 dump.rdb 衔接：若 dump.rdb 存在 <b>且</b> {@code dump.rdb.index} 记录的索引
 *       == raft-nodes.conf 的 lastIncludedIndex → 用 {@link RdbPersistService} 载入内存
 *       （快照作为状态地基）；否则标记「本地状态不可信」（内存为空）。</li>
 *   <li>logTail 重放：将 {@code index > lastIncludedIndex} 的条目按序 apply 到内存之上
 *       （用 {@link LogApplier#apply}，apply 的响应对象丢弃，仅推进 lastApplied）。
 *       <b>绝不回放 AOF</b>（mesh 模式无 AOF）。</li>
 * </ol>
 *
 * <h3>为什么必须这样（DESIGN §5.5）</h3>
 * <p>log 被快照截断后，raft-nodes.conf 里只有 logTail；如果跳过第 2 步直接在空库上重放 tail，
 * {@code INCRBY/HSET/DEL} 等依赖前置数据的命令会产出错误状态，节点带着错误状态参与选举会
 * 污染整个集群。</p>
 *
 * <h3>dump.rdb 索引判断方案（辅助文件 dump.rdb.index）</h3>
 * <p>dump.rdb 本身不记录它对应的 lastIncludedIndex。本加载器采用「辅助文件」方案：
 * {@link com.janeluo.luban.rds.mesh.replication.SnapshotManager} 在写完 dump.rdb 后，
 * 通过 {@link MeshConfigPersister#saveDumpRdbIndex(long)} 同步落盘 {@code dump.rdb.index}
 * （含 fsync + ATOMIC_MOVE）。启动时比对 {@code dump.rdb.index == state.lastIncludedIndex}
 * 判断衔接是否可信。</p>
 *
 * <h3>不可信衔接的常态容错（DESIGN §5.4 / §5.5）</h3>
 * <p>lastIncludedIndex 与 dump.rdb 非原子写：若进程在两者之间崩溃（dump.rdb 写完但
 * raft-nodes.conf 未更新，或反之），启动时索引不匹配 → 标记不可信 → 内存为空 →
 * 选举后由 Leader 以 INSTALL_SNAPSHOT 全量追平。此为<b>常态容错</b>而非异常。</p>
 */
public class MeshStartupLoader {

    private static final Logger logger = LoggerFactory.getLogger(MeshStartupLoader.class);

    /** dump.rdb 文件名（与 SnapshotManager / RdbPersistService 一致）。 */
    public static final String DUMP_RDB_FILENAME = "dump.rdb";

    private final MeshConfigPersister persister;
    private final RdbPersistService rdbPersistService;
    private final LogApplier applier;
    private final MemoryStore rawStore;
    private final String dbDir;

    /**
     * @param persister         raft-nodes.conf 读写器
     * @param rdbPersistService RDB 加载服务（构造时已绑定 {@code dbDir/dump.rdb}）
     * @param applier           日志应用器（用于 logTail 重放到 raw store）
     * @param rawStore          真实 raw MemoryStore（快照 + 重放的目标）
     * @param dbDir             数据目录（dump.rdb 所在）
     */
    public MeshStartupLoader(MeshConfigPersister persister,
                             RdbPersistService rdbPersistService,
                             LogApplier applier,
                             MemoryStore rawStore,
                             String dbDir) {
        if (persister == null) {
            throw new IllegalArgumentException("persister 不能为 null");
        }
        if (rdbPersistService == null) {
            throw new IllegalArgumentException("rdbPersistService 不能为 null");
        }
        if (applier == null) {
            throw new IllegalArgumentException("applier 不能为 null");
        }
        if (rawStore == null) {
            throw new IllegalArgumentException("rawStore 不能为 null");
        }
        this.persister = persister;
        this.rdbPersistService = rdbPersistService;
        this.applier = applier;
        this.rawStore = rawStore;
        this.dbDir = dbDir != null ? dbDir : System.getProperty("java.io.tmpdir");
    }

    /**
     * 按序加载，返回恢复后的 {@link MeshState} + 是否可信标志。
     *
     * <p><b>异常语义</b>：raft-nodes.conf <b>损坏</b>时（{@link MeshConfigPersister.MeshConfigParseException}）
     * 直接向上抛——调用方必须中止启动，不静默重置 term（DESIGN §5.5）。</p>
     *
     * @param nodeId 本节点 nodeId（传给 persister.load，用于日志/校验）
     * @return 启动结果（state + isTrusted）
     * @throws IOException 读 raft-nodes.conf / dump.rdb.index 失败（启动硬故障，调用方应中止）
     */
    public StartupResult load(String nodeId) throws IOException {
        // 1. 加载 raft-nodes.conf → MeshState（无则新建空 state，currentTerm=1）
        MeshState state = persister.load(nodeId);
        boolean firstStart;
        if (state == null) {
            // 首次启动：新建空 state
            state = new MeshState();
            state.currentTerm = 1; // term 从 1 起算（非 0，避免与「未初始化」混淆）
            state.role = com.janeluo.luban.rds.mesh.core.MeshRole.FOLLOWER;
            firstStart = true;
            logger.info("首次启动：无 raft-nodes.conf，新建空 state currentTerm=1, nodeId={}",
                    abbrev(nodeId));
        } else {
            firstStart = false;
            logger.info("加载 raft-nodes.conf 成功: term={}, votedFor={}, lastIncluded={}/{}, logTail={}, nodeId={}",
                    state.currentTerm, state.votedFor, state.lastIncludedIndex, state.lastIncludedTerm,
                    state.log.size(), abbrev(nodeId));
        }

        // 2. 检查 dump.rdb 衔接
        //    dump.rdb 存在 且 dump.rdb.index == lastIncludedIndex → 载入内存（可信）
        //    否则（无快照 / 衔接不上 / 首次启动）→ 内存为空（不可信）
        boolean isTrusted = false;
        Path dumpRdbPath = Paths.get(dbDir, DUMP_RDB_FILENAME);
        if (firstStart) {
            // 首次启动：无历史，不需要快照；视为可信的空状态（commitIndex/lastApplied 都是 0）
            isTrusted = true;
            logger.info("首次启动：跳过 dump.rdb 加载，内存为空（可信空状态）");
        } else if (!Files.exists(dumpRdbPath)) {
            // 无 dump.rdb：若 lastIncludedIndex == 0（从未快照过）→ 可信（无快照历史是正常的）；
            //             若 lastIncludedIndex > 0（曾快照过）→ dump.rdb 丢失，不可信
            if (state.lastIncludedIndex == 0) {
                isTrusted = true;
                logger.info("无 dump.rdb 且 lastIncludedIndex=0（从未快照），视为可信");
            } else {
                isTrusted = false;
                logger.warn("dump.rdb 不存在但 lastIncludedIndex={}（曾快照过），标记不可信，"
                        + "选举后由 Leader INSTALL_SNAPSHOT 全量追平", state.lastIncludedIndex);
            }
        } else {
            // dump.rdb 存在：比对 dump.rdb.index 与 lastIncludedIndex
            long dumpIndex;
            try {
                dumpIndex = persister.loadDumpRdbIndex();
            } catch (IOException e) {
                // 读 dump.rdb.index 失败（IO 错误，非「文件不存在」）→ 不可信
                logger.warn("读取 dump.rdb.index 失败，标记不可信", e);
                dumpIndex = -2;
            } catch (MeshConfigPersister.MeshConfigParseException e) {
                // dump.rdb.index 损坏 → 抛异常（不静默忽略，与 raft-nodes.conf 损坏一致语义）
                throw e;
            }

            if (dumpIndex == state.lastIncludedIndex) {
                // 衔接可信：载入 dump.rdb 到内存
                isTrusted = loadDumpRdbIntoMemory(dumpRdbPath);
                if (isTrusted) {
                    logger.info("dump.rdb 衔接可信（index={}），已载入内存", dumpIndex);
                } else {
                    logger.warn("dump.rdb 加载失败，标记不可信，选举后由 Leader INSTALL_SNAPSHOT 全量追平");
                }
            } else {
                // 衔接不上（dump.rdb 索引 ≠ lastIncludedIndex，或 dump.rdb.index 不存在=-1）
                isTrusted = false;
                logger.warn("dump.rdb 衔接不上: dump.rdb.index={}, lastIncludedIndex={}，标记不可信，"
                        + "选举后由 Leader INSTALL_SNAPSHOT 全量追平", dumpIndex, state.lastIncludedIndex);
            }
        }

        // 3. logTail 重放：对 index > lastIncludedIndex 的条目按序 apply 到内存之上
        //    （apply 响应对象丢弃，仅推进 lastApplied）
        //    注意：即便不可信也尝试重放 tail（如果 tail 非空但快照缺失，重放可能产出错误状态；
        //    但不可信分支下内存为空，且后续 Leader 会 INSTALL_SNAPSHOT 覆盖，故重放无害——
        //    更稳妥：不可信时不重放，等 Leader 全量追平）
        long appliedCount = 0;
        if (isTrusted) {
            appliedCount = replayLogTail(state);
            logger.info("logTail 重放完成: applied={} 条, lastApplied={}", appliedCount, state.lastApplied);
        } else if (!state.log.isEmpty()) {
            // 不可信但有 tail：清空 log（避免带着不可信 tail 参与选举）。
            // lastIncludedIndex/lastIncludedTerm 保留（raft-nodes.conf 的元数据可信，
            // 只是 dump.rdb 对不上）；commitIndex/lastApplied 回退到 lastIncludedIndex。
            // Leader INSTALL_SNAPSHOT 完成后会重置这些字段。
            logger.warn("不可信衔接且有 {} 条 logTail：暂不重放，等 Leader INSTALL_SNAPSHOT 追平",
                    state.log.size());
        }

        return new StartupResult(state, isTrusted, firstStart, appliedCount);
    }

    /**
     * 用 {@link RdbPersistService#loadWithKeyCount} 载入 dump.rdb 到 {@link #rawStore}。
     *
     * @return true=加载成功
     */
    private boolean loadDumpRdbIntoMemory(Path dumpRdbPath) {
        try {
            long keys = rdbPersistService.loadWithKeyCount(rawStore);
            logger.info("dump.rdb 载入内存完成: keys={}", keys);
            return true;
        } catch (Exception e) {
            logger.error("dump.rdb 载入内存异常: {}", dumpRdbPath, e);
            return false;
        }
    }

    /**
     * logTail 重放：对 {@code index > lastIncludedIndex} 的条目按序 apply 到内存之上。
     * <p>apply 的响应对象丢弃（仅推进 lastApplied）。</p>
     *
     * @return 实际 apply 的条目数
     */
    private long replayLogTail(MeshState state) {
        // 在 readLock 内拷贝 tail 快照（避免遍历期间并发修改）
        List<LogEntry> tail;
        state.readLock().lock();
        try {
            tail = new ArrayList<>(state.log);
        } finally {
            state.readLock().unlock();
        }

        long applied = 0;
        for (LogEntry entry : tail) {
            if (entry.getIndex() <= state.lastIncludedIndex) {
                // 已被快照覆盖的条目（不应出现，防御跳过）
                logger.warn("logTail 含 index={} <= lastIncludedIndex={} 的条目，跳过",
                        entry.getIndex(), state.lastIncludedIndex);
                continue;
            }
            try {
                // apply 到 raw store，响应对象丢弃（仅推进 lastApplied）
                applier.apply(entry);
                state.lastApplied = entry.getIndex();
                applied++;
            } catch (Exception e) {
                // 单条 apply 异常不中断重放（与运行时 apply 一致容错），记录错误继续
                logger.error("logTail 重放异常: index={}, term={}",
                        entry.getIndex(), entry.getTerm(), e);
            }
        }
        // commitIndex 推进到 lastApplied（重放的都是已持久化的 tail，视为已提交）
        if (state.commitIndex < state.lastApplied) {
            state.commitIndex = state.lastApplied;
        }
        return applied;
    }

    /** 启动加载结果。 */
    public static final class StartupResult {
        /** 恢复后的 MeshState。 */
        public final MeshState state;
        /** 本地状态是否可信（dump.rdb 衔接 + tail 重放后状态正确）。
         * 不可信时选举后由 Leader INSTALL_SNAPSHOT 全量追平。 */
        public final boolean isTrusted;
        /** 是否首次启动（无 raft-nodes.conf）。 */
        public final boolean firstStart;
        /** logTail 重放 apply 的条目数。 */
        public final long replayedCount;

        public StartupResult(MeshState state, boolean isTrusted, boolean firstStart, long replayedCount) {
            this.state = state;
            this.isTrusted = isTrusted;
            this.firstStart = firstStart;
            this.replayedCount = replayedCount;
        }
    }

    private static String abbrev(String id) {
        if (id == null) {
            return "?";
        }
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
