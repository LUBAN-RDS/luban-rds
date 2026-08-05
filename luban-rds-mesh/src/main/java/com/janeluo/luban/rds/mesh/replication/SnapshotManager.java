package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.bus.MeshBusClient;
import com.janeluo.luban.rds.mesh.bus.MeshFrame;
import com.janeluo.luban.rds.mesh.bus.MessageType;
import com.janeluo.luban.rds.mesh.core.MeshState;
import com.janeluo.luban.rds.mesh.rpc.AppendEntriesResponse;
import com.janeluo.luban.rds.mesh.rpc.InstallSnapshotMessage;
import com.janeluo.luban.rds.replication.RdbDataLoader;
import com.janeluo.luban.rds.replication.RdbSnapshotGenerator;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * 快照管理器（DESIGN.md §5.4 / §5.5，IMPLEMENTATION_PLAN 阶段 10）。
 *
 * <p>承担三件事：</p>
 * <ol>
 *   <li><b>Leader chunked 发送</b>（{@link #sendSnapshot}）：把当前 {@link MemoryStore} 生成 RDB 临时文件，
 *       按 {@value #DEFAULT_CHUNK_SIZE_BYTES} 字节切片，逐个发 {@link InstallSnapshotMessage}（offset 递增，
 *       最后一片 {@code done=true}）。单帧 body ≤ 16MB（{@code MeshFrame.MAX_BODY_LENGTH}），
 *       几百 MB 快照无法单帧传输，故 v1 必须 chunked（DESIGN §5.4）。</li>
 *   <li><b>Follower chunked 接收</b>（{@link #handleInstallSnapshot}）：按 {@code offset} 把 chunk 累积写入
 *       临时 RDB 文件；{@code done=true} 时整体用 {@link RdbDataLoader} 加载到内存 + 落盘 dump.rdb，
 *       然后截断本地 log（保留 {@code lastIncludedIndex} 之后）、更新 {@code commitIndex=lastApplied=lastIncludedIndex}、
 *       更新 {@code state.lastIncludedIndex/Term}，最后回 {@link AppendEntriesResponse} 让 Leader 推进 nextIndex/matchIndex。</li>
 *   <li><b>周期快照</b>（{@link #takePeriodicSnapshotIfNeeded}）：log 达阈值时触发——生成 RDB 落盘 dump.rdb
 *       （唯一写者，DESIGN D3）、截断 log、更新 lastIncludedIndex/Term。各节点独立触发，防日志无界增长。</li>
 * </ol>
 *
 * <h3>dump.rdb 写者归属（DESIGN D3 / §5.4）</h3>
 * <p>mesh 模式禁用 server 原 RDB save（BGSAVE / PersistService save），dump.rdb 的唯一写者 = 本类。
 * 阶段 10 保证本类写路径正确（{@link #writeDumpRdb}）；阶段 12 在 server 集成时 gate 掉 server 的 save 路径。</p>
 *
 * <h3>lastIncludedIndex 与 dump.rdb 非原子写（DESIGN §5.4）</h3>
 * <p>三份落盘（dump.rdb.index / dump.rdb / conf）非原子，顺序固定为 <b>index → rdb → conf</b>：
 * 信任校验（index==conf）通过 ⟹ conf 已落盘 ⟹ 同轮 rdb 已写完 ⟹ 载入的 rdb 与新边界一致；
 * 任何中间崩溃 → index≠conf → 不可信 → 全量追平（§5.5 常态容错，阶段 11 启动加载处理）。
 * index 必须先写：若 rdb 先写而崩溃，index==conf==旧 + rdb==新 → 重启信任误判 → (旧,新] 条目重放双 apply。</p>
 *
 * <h3>线程模型</h3>
 * <p>本类所有方法必须在 {@code MeshNode.raftExecutor} 单线程上串行调用——与 {@link LogReplicator} 一致
 * （DESIGN §3.1：所有 Raft 状态访问串行）。{@link #followerIncoming} 与 {@link #chunkFile} 的并发访问
 * 仅来自该单线程，无需额外加锁。</p>
 *
 * <h3>Follower ACK 复用 AppendEntriesResponse</h3>
 * <p>DESIGN/MessageType 未定义专用的 INSTALL_SNAPSHOT 响应类型。Follower 完成快照加载后，复用
 * {@link AppendEntriesResponse}（{@code success=true, matchIndex=lastIncludedIndex, term=currentTerm}）回给 Leader。
 * Leader 侧在 {@code MeshNode.handleAppendEntriesResponse} 中按既有路径推进 {@code matchIndex[peer]=lastIncludedIndex}、
 * {@code nextIndex[peer]=lastIncludedIndex+1}，从而恢复普通 AppendEntries 复制。</p>
 */
public class SnapshotManager {

    private static final Logger logger = LoggerFactory.getLogger(SnapshotManager.class);

    /** 默认 chunk 大小：4MB（DESIGN §5.4 / IMPLEMENTATION_PLAN 阶段 10.1）。 */
    public static final int DEFAULT_CHUNK_SIZE_BYTES = 4 * 1024 * 1024;

    /** dump.rdb 文件名（与 server / RdbPersistService 一致）。 */
    public static final String DUMP_RDB_FILENAME = "dump.rdb";

    private final String nodeId;
    private final MeshState state;
    private final MeshBusClient busClient;
    private final MemoryStore rawStore;
    private final RdbSnapshotGenerator snapshotGenerator;
    private final RdbDataLoader dataLoader;
    private final String dataDir;

    /** chunk 大小（字节）。 */
    private final int chunkSizeBytes;

    /**
     * 周期快照阈值（log 条目数）。{@code state.log.size()} ≥ 该值时触发 {@link #takePeriodicSnapshotIfNeeded}。
     * 默认 {@value #DEFAULT_SNAPSHOT_LOG_THRESHOLD}。
     */
    private final long snapshotLogThreshold;

    /**
     * 持久化 hook：MeshState 变更（lastIncludedIndex/Term、log 截断）后调用，供阶段 11 fsync
     * raft-nodes.conf。阶段 10 可为 no-op（{@code null} 时跳过）。
     */
    private final Runnable persistHook;

    /**
     * dump.rdb 索引写入 hook（阶段 11）：把 {@code lastIncludedIndex} 落盘到 {@code dump.rdb.index}
     * （含 fsync + ATOMIC_MOVE）。<b>必须在 dump.rdb 写入之前调用</b>（fix：index 先于 rdb，
     * 见 {@link #takePeriodicSnapshotIfNeeded}）。{@code null} 时跳过（阶段 10 之前的测试兼容）。
     * <p>用函数式回调而非直接依赖 lifecycle 包，保持 replication → lifecycle 的依赖方向不反转。</p>
     */
    private final java.util.function.LongConsumer dumpRdbIndexWriter;

    // ==================== Follower 侧 chunk 累积状态 ====================

    /**
     * Follower 侧：当前正在接收的快照会话。
     * <p>key = "{leaderId}:{lastIncludedIndex}:{lastIncludedTerm}"（标识一次快照传输；
     * 不同 Leader / 不同 lastIncludedIndex 视为不同会话，新会话到达会作废旧会话的临时文件）。</p>
     */
    private volatile IncomingSnapshot incoming;

    /**
     * Follower 侧：累积 chunk 的临时文件输出流。
     * <p>按 {@code offset} 顺序追加；{@code done=true} 时关闭并交给 {@link RdbDataLoader} 加载。</p>
     */
    private volatile BufferedOutputStream chunkOut;

    /** Follower 侧：累积已写入的字节数（校验 offset 连续性）。 */
    private volatile long receivedBytes;

    /**
     * 构造（使用默认 chunk 大小与默认快照阈值）。
     *
     * @param nodeId            本节点 nodeId
     * @param state             Raft 状态
     * @param busClient         总线客户端（发 InstallSnapshot / AppendEntriesResponse）
     * @param rawStore          真实 raw MemoryStore（快照加载目标）
     * @param snapshotGenerator RDB 快照生成器（{@code generateTempRdbFile} 已提 public）
     * @param dataLoader        RDB 数据加载器
     * @param dataDir           数据目录（dump.rdb 落盘位置）
     */
    public SnapshotManager(String nodeId, MeshState state, MeshBusClient busClient,
                           MemoryStore rawStore, RdbSnapshotGenerator snapshotGenerator,
                           RdbDataLoader dataLoader, String dataDir) {
        this(nodeId, state, busClient, rawStore, snapshotGenerator, dataLoader, dataDir,
                DEFAULT_CHUNK_SIZE_BYTES, DEFAULT_SNAPSHOT_LOG_THRESHOLD, null, null);
    }

    /**
     * 全参构造（测试/定制用）。
     *
     * @param chunkSizeBytes       chunk 大小（字节）；&lt;=0 用默认 4MB
     * @param snapshotLogThreshold 周期快照阈值（log 条目数）；&lt;=0 用默认值
     * @param persistHook          MeshState 持久化 hook；可为 {@code null}（阶段 10 no-op）
     */
    public SnapshotManager(String nodeId, MeshState state, MeshBusClient busClient,
                           MemoryStore rawStore, RdbSnapshotGenerator snapshotGenerator,
                           RdbDataLoader dataLoader, String dataDir,
                           int chunkSizeBytes, long snapshotLogThreshold,
                           Runnable persistHook) {
        this(nodeId, state, busClient, rawStore, snapshotGenerator, dataLoader, dataDir,
                chunkSizeBytes, snapshotLogThreshold, persistHook, null);
    }

    /**
     * 全参构造（阶段 11：增加 dump.rdb.index 写入 hook）。
     *
     * @param chunkSizeBytes       chunk 大小（字节）；&lt;=0 用默认 4MB
     * @param snapshotLogThreshold 周期快照阈值（log 条目数）；&lt;=0 用默认值
     * @param persistHook          MeshState 持久化 hook；可为 {@code null}
     * @param dumpRdbIndexWriter   dump.rdb.index 写入 hook；可为 {@code null}（跳过索引落盘）
     */
    public SnapshotManager(String nodeId, MeshState state, MeshBusClient busClient,
                           MemoryStore rawStore, RdbSnapshotGenerator snapshotGenerator,
                           RdbDataLoader dataLoader, String dataDir,
                           int chunkSizeBytes, long snapshotLogThreshold,
                           Runnable persistHook,
                           java.util.function.LongConsumer dumpRdbIndexWriter) {
        this.nodeId = nodeId;
        this.state = state;
        this.busClient = busClient;
        this.rawStore = rawStore;
        this.snapshotGenerator = snapshotGenerator;
        this.dataLoader = dataLoader;
        this.dataDir = dataDir != null ? dataDir : System.getProperty("java.io.tmpdir");
        this.chunkSizeBytes = chunkSizeBytes > 0 ? chunkSizeBytes : DEFAULT_CHUNK_SIZE_BYTES;
        this.snapshotLogThreshold = snapshotLogThreshold > 0
                ? snapshotLogThreshold : DEFAULT_SNAPSHOT_LOG_THRESHOLD;
        this.persistHook = persistHook;
        this.dumpRdbIndexWriter = dumpRdbIndexWriter;
    }

    /** 默认周期快照阈值：10 万条 log（DESIGN §5.4「每 N 条 / 累计 M 字节，如 10 万条」）。 */
    public static final long DEFAULT_SNAPSHOT_LOG_THRESHOLD = 100_000L;

    // ==================== Leader 侧：chunked 发送 ====================

    /**
     * Leader 发送快照给落后 Follower（chunked，DESIGN §5.4 步骤 2）。
     *
     * <p>流程：
     * <ol>
     *   <li>用 {@link RdbSnapshotGenerator#generateTempRdbFile} 生成临时 RDB 文件（复用既有落盘路径，
     *       DESIGN Open Question 方案 A）。</li>
     *   <li>计算 {@code lastIncludedTerm}/{@code lastIncludedIndex}：取 {@code state.lastApplied} 对应的
     *       term（即当前已 apply 的最后一条日志的 term），作为快照边界。若 lastApplied=0 则取
     *       lastIncludedIndex/Term（无新日志可快照，退化为重发既有快照）。</li>
     *   <li>按 {@link #chunkSizeBytes} 切片文件，逐个发 {@link InstallSnapshotMessage}：
     *       {@code offset} 从 0 递增，{@code data}=chunk 字节，最后一片 {@code done=true}。</li>
     *   <li>发送完毕删除临时文件。</li>
     * </ol>
     * </p>
     *
     * <p>线程模型：必须在 raftExecutor 上调用。</p>
     *
     * @param targetNodeId 目标 Follower nodeId
     * @return 实际发送的字节数；生成快照失败返回 -1
     */
    public long sendSnapshot(String targetNodeId) {
        File tempRdbFile = snapshotGenerator.generateTempRdbFile(rawStore);
        if (tempRdbFile == null || !tempRdbFile.exists()) {
            logger.error("sendSnapshot: 生成 RDB 临时文件失败, target={}", targetNodeId);
            return -1L;
        }

        long lastIncludedIndex = computeSnapshotIndex();
        long lastIncludedTerm = computeSnapshotTerm(lastIncludedIndex);

        long totalSent = sendRdbFileChunked(targetNodeId, tempRdbFile,
                state.currentTerm, lastIncludedTerm, lastIncludedIndex);

        // 删除临时文件（发完即删）
        deleteQuietly(tempRdbFile);

        if (totalSent > 0) {
            logger.info("sendSnapshot: 已向 {} chunked 发送快照 lastIncluded={}/{}, bytes={}",
                    targetNodeId, lastIncludedIndex, lastIncludedTerm, totalSent);
        }
        return totalSent;
    }

    /**
     * 把 RDB 文件按 {@link #chunkSizeBytes} 切片，逐个发 {@link InstallSnapshotMessage}。
     *
     * @return 发送的总字节数；IO 异常返回 -1
     */
    private long sendRdbFileChunked(String targetNodeId, File rdbFile,
                                    long term, long lastIncludedTerm, long lastIncludedIndex) {
        long fileSize = rdbFile.length();
        long totalSent = 0;
        long offset = 0;
        try {
            byte[] readBuf = new byte[chunkSizeBytes];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(rdbFile);
                 java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis)) {
                int bytesRead;
                while ((bytesRead = bis.read(readBuf)) != -1) {
                    boolean last = (offset + bytesRead) >= fileSize;
                    byte[] chunk = (bytesRead == readBuf.length) ? readBuf : java.util.Arrays.copyOf(readBuf, bytesRead);

                    InstallSnapshotMessage msg = new InstallSnapshotMessage(
                            term, nodeId, lastIncludedTerm, lastIncludedIndex,
                            offset, chunk, last);
                    MeshFrame frame = new MeshFrame(nodeId, MessageType.INSTALL_SNAPSHOT.getCode(), msg.encode());
                    busClient.send(targetNodeId, frame);

                    totalSent += bytesRead;
                    offset += bytesRead;
                }
            }
            // 空文件保护：fileSize==0 时上面循环不执行，发一个 done=true 的空 chunk 让 Follower 完成会话
            if (offset == 0) {
                InstallSnapshotMessage msg = new InstallSnapshotMessage(
                        term, nodeId, lastIncludedTerm, lastIncludedIndex,
                        0L, new byte[0], true);
                MeshFrame frame = new MeshFrame(nodeId, MessageType.INSTALL_SNAPSHOT.getCode(), msg.encode());
                busClient.send(targetNodeId, frame);
            }
            return totalSent;
        } catch (IOException e) {
            logger.error("sendRdbFileChunked: 读 RDB 文件失败, target={}, file={}",
                    targetNodeId, rdbFile.getAbsolutePath(), e);
            return -1L;
        }
    }

    // ==================== Leader 侧：周期快照（防日志无界增长） ====================

    /**
     * 周期快照：达阈值时触发（DESIGN §5.4 / §5.5）。
     *
     * <p>流程：
     * <ol>
     *   <li>阈值检查：{@code state.log.size() >= snapshotLogThreshold}。未达阈值直接返回。</li>
     *   <li>dump.rdb.index 落盘：先写索引记录本次快照边界（失败则中止快照）。</li>
     *   <li>生成 RDB 落盘 dump.rdb（唯一写者，{@link #writeDumpRdb}）。</li>
     *   <li>截断 {@code state.log}：保留 lastIncludedIndex 之后的条目（{@link MeshState#discardUpToInclusive}）。</li>
     *   <li>更新 {@code state.lastIncludedIndex/Term} = 当前 lastApplied / 对应 term。</li>
     *   <li>持久化 MeshState（{@link #persistHook}，阶段 11 fsync）。</li>
     * </ol>
     * </p>
     *
     * <p><b>非原子写容错（DESIGN §5.4）</b>：三份落盘（index / rdb / conf）非原子，顺序固定为
     * <b>index → rdb → conf</b>。信任校验（index==conf）通过 ⟹ conf 已落盘 ⟹ 同轮 rdb 已写完 ⟹
     * 载入的 rdb 与新边界一致；任何中间崩溃 → index≠conf → 不可信 → 全量追平（§5.5）。
     * index 必须先于 rdb：若 rdb 先写而崩溃，index==conf==旧 + rdb==新 → 重启信任误判 → (旧,新] 条目重放双 apply。</p>
     *
     * <p>线程模型：必须在 raftExecutor 上调用。</p>
     *
     * @return true=本次触发了快照；false=未达阈值或失败
     */
    public boolean takePeriodicSnapshotIfNeeded() {
        int logSize = state.log.size();
        if (logSize < snapshotLogThreshold) {
            return false;
        }
        long snapshotIndex = computeSnapshotIndex();
        if (snapshotIndex <= state.lastIncludedIndex) {
            // 无新进度可快照（lastApplied 未推进过）
            logger.debug("takePeriodicSnapshotIfNeeded: 无新进度 (snapshotIndex={} <= lastIncluded={})",
                    snapshotIndex, state.lastIncludedIndex);
            return false;
        }
        long snapshotTerm = computeSnapshotTerm(snapshotIndex);

        logger.info("takePeriodicSnapshotIfNeeded: 触发周期快照 logSize={}, snapshotIndex={}, snapshotTerm={}",
                logSize, snapshotIndex, snapshotTerm);

        // 1. 先落盘 dump.rdb.index（fix：index 先于 rdb——信任通过 ⟹ conf 已落盘 ⟹ rdb 已写完；
        //    若先写 rdb，崩溃后 index==conf==旧 + rdb==新 → 重启信任误判 → (旧,新] 条目双 apply）
        if (!runDumpRdbIndexWriter(snapshotIndex)) {
            logger.warn("takePeriodicSnapshotIfNeeded: dump.rdb.index 写入失败，中止本次快照");
            return false;
        }

        // 2. 生成 RDB 落盘 dump.rdb（唯一写者）
        File dump = writeDumpRdb();
        if (dump == null) {
            logger.warn("takePeriodicSnapshotIfNeeded: dump.rdb 写入失败，跳过本次快照");
            return false;
        }

        // 3. 丢弃已被快照覆盖的 log 条目（≤ snapshotIndex），保留 > snapshotIndex 的 tail
        //    必须在更新 lastIncludedIndex 之前调用（用旧 lastIncludedIndex 换算 list 索引）
        state.discardUpToInclusive(snapshotIndex);

        // 4. 更新 lastIncludedIndex/Term（先 dump.rdb 后 lastIncluded，非原子；DESIGN §5.4）
        state.lastIncludedIndex = snapshotIndex;
        state.lastIncludedTerm = snapshotTerm;

        // 5. 持久化 MeshState（WAL 重写 + conf 新边界）
        runPersistHook();

        logger.info("takePeriodicSnapshotIfNeeded: 完成, lastIncluded={}/{}, logSizeAfter={}",
                state.lastIncludedIndex, state.lastIncludedTerm, state.log.size());
        return true;
    }

    /**
     * 当前可快照的 index：取 {@code state.lastApplied}（已 apply 的最后一条）。
     * lastApplied=0 时返回 lastIncludedIndex（无新日志，退化为既有快照边界）。
     */
    private long computeSnapshotIndex() {
        long applied = state.lastApplied;
        if (applied > 0) {
            return applied;
        }
        return state.lastIncludedIndex;
    }

    /**
     * 给定 index 取其 term：index==lastIncludedIndex 取 lastIncludedTerm；
     * 否则取 {@link MeshState#getLogTerm(long)}（返回 -1 表示不可查，此时退化为当前任期）。
     */
    private long computeSnapshotTerm(long index) {
        if (index == state.lastIncludedIndex) {
            return state.lastIncludedTerm;
        }
        long t = state.getLogTerm(index);
        return t > 0 ? t : state.currentTerm;
    }

    /**
     * 生成 dump.rdb：用 {@link RdbSnapshotGenerator#generateTempRdbFile} 得到临时副本，
     * 再原子移动/复制为 dump.rdb。作为 mesh 模式 dump.rdb 的唯一写路径（DESIGN D3）。
     *
     * @return 写入成功的 dump.rdb 文件；失败返回 {@code null}
     */
    private File writeDumpRdb() {
        File temp = snapshotGenerator.generateTempRdbFile(rawStore);
        if (temp == null || !temp.exists()) {
            logger.error("writeDumpRdb: 生成临时 RDB 失败");
            return null;
        }
        File target = new File(dataDir, DUMP_RDB_FILENAME);
        try {
            // 先写 tmp 再 rename，避免半写文件
            File staging = new File(dataDir, DUMP_RDB_FILENAME + ".staging."
                    + System.currentTimeMillis());
            copyFile(temp, staging);
            // rename 到 dump.rdb（目标存在则先删）
            if (target.exists() && !target.delete()) {
                logger.warn("writeDumpRdb: 删除旧 dump.rdb 失败，尝试直接覆盖");
            }
            if (!staging.renameTo(target)) {
                // rename 失败（跨设备等），退化为 copy
                copyFile(staging, target);
                staging.delete();
            }
            return target;
        } catch (IOException e) {
            logger.error("writeDumpRdb: 写 dump.rdb 失败", e);
            return null;
        } finally {
            deleteQuietly(temp);
        }
    }

    // ==================== Follower 侧：chunked 接收 + 加载 ====================

    /**
     * Follower 处理 INSTALL_SNAPSHOT（chunked，DESIGN §5.4 步骤 3）。
     *
     * <p>流程：
     * <ol>
     *   <li>任期校验：{@code msg.term < currentTerm} → 拒绝（回 success=false）。</li>
     *   <li>会话管理：不同 (leaderId, lastIncludedIndex, lastIncludedTerm) 视为新会话，
     *       作废旧会话（删旧临时文件），开启新会话。</li>
     *   <li>按 {@code msg.offset} 把 {@code msg.data} 追加写入临时文件（offset 必须等于已接收字节数）。</li>
     *   <li>{@code done=true} 时：
     *     <ul>
     *       <li>关闭临时文件输出流。</li>
     *       <li>先落盘 dump.rdb.index（记录新边界；失败则中止安装、回 success=false，Leader 重发快照）。</li>
     *       <li>用 {@link RdbDataLoader} 加载临时 RDB 到内存 + 落盘 dump.rdb。</li>
     *       <li>截断本地 log 保留 lastIncludedIndex 之后的条目。</li>
     *       <li>更新 {@code commitIndex=lastApplied=lastIncludedIndex}。</li>
     *       <li>更新 {@code state.lastIncludedIndex/Term=msg 的值}。</li>
     *       <li>持久化 MeshState（阶段 11）。</li>
     *       <li>回 {@link AppendEntriesResponse}(success=true, matchIndex=lastIncludedIndex)。</li>
     *     </ul>
     *   </li>
     *   <li>非最后 chunk：不回响应（Leader 仅在 done 后等 ACK；中间 chunk 静默累积，降低 RPC 往返）。</li>
     * </ol>
     * </p>
     *
     * <p>线程模型：必须在 raftExecutor 上调用（与 {@code handleAppendEntries} 一致）。</p>
     *
     * @param fromNodeId Leader nodeId（消息来源）
     * @param msg        INSTALL_SNAPSHOT 请求
     */
    public void handleInstallSnapshot(String fromNodeId, InstallSnapshotMessage msg) {
        long currentTerm = state.currentTerm;

        // 1. 任期校验
        if (msg.getTerm() < currentTerm) {
            logger.debug("handleInstallSnapshot: 拒绝过期任期 msg.term={} < currentTerm={}, from={}",
                    msg.getTerm(), currentTerm, fromNodeId);
            // 任期过期的 InstallSnapshot 仍需回一个失败响应，让 Leader 知道 Follower 状态
            sendSnapshotAck(fromNodeId, currentTerm, false, state.lastApplied);
            return;
        }

        // 任期 >= currentTerm：若更大则更新自身 term（降级为 Follower 由上层 MeshNode 统一处理；
        // 这里至少保证 currentTerm 跟上 Leader）
        if (msg.getTerm() > currentTerm) {
            state.currentTerm = msg.getTerm();
            state.votedFor = null;
            state.leaderId = msg.getLeaderId();
            logger.info("handleInstallSnapshot: 更新任期 → {}, leader={}", state.currentTerm, msg.getLeaderId());
        }

        // 2. 会话管理：新会话作废旧累积
        String sessionId = sessionKey(msg);
        if (incoming == null || !sessionId.equals(incoming.sessionId)) {
            // 作废旧会话
            if (incoming != null) {
                closeChunkOutQuietly();
                deleteQuietly(incoming.tempFile);
                incoming = null;
            }
            File tempFile = new File(dataDir, "mesh-snapshot-incoming-" + System.currentTimeMillis() + ".rdb");
            try {
                chunkOut = new BufferedOutputStream(new FileOutputStream(tempFile, false));
            } catch (IOException e) {
                logger.error("handleInstallSnapshot: 创建临时文件失败, file={}", tempFile.getAbsolutePath(), e);
                sendSnapshotAck(fromNodeId, state.currentTerm, false, state.lastApplied);
                return;
            }
            receivedBytes = 0;
            incoming = new IncomingSnapshot(sessionId, tempFile,
                    msg.getLastIncludedIndex(), msg.getLastIncludedTerm(), msg.getLeaderId());
            logger.info("handleInstallSnapshot: 开启新会话 {}, tempFile={}",
                    sessionId, tempFile.getAbsolutePath());
        }

        // 3. offset 连续性校验 + 追加写入
        if (msg.getOffset() != receivedBytes) {
            logger.warn("handleInstallSnapshot: offset 不连续 expected={} got={}, 会话={}, 丢弃该 chunk",
                    receivedBytes, msg.getOffset(), sessionId);
            // 不回响应，等 Leader 重发（或超时后 Leader 重新发起整个快照）
            return;
        }
        try {
            byte[] data = msg.getData();
            if (data.length > 0) {
                chunkOut.write(data);
                receivedBytes += data.length;
            }
        } catch (IOException e) {
            logger.error("handleInstallSnapshot: 写临时文件失败, 会话={}", sessionId, e);
            closeChunkOutQuietly();
            deleteQuietly(incoming.tempFile);
            incoming = null;
            sendSnapshotAck(fromNodeId, state.currentTerm, false, state.lastApplied);
            return;
        }

        // 4. done=true 时整体加载 + 截断 + 更新状态 + 回 ACK
        if (msg.isDone()) {
            IncomingSnapshot done = incoming;
            try {
                closeChunkOutQuietly();
                if (!done.tempFile.exists() || done.tempFile.length() == 0
                        && receivedBytes > 0) {
                    logger.error("handleInstallSnapshot: done 但临时文件异常, 会话={}", sessionId);
                    sendSnapshotAck(fromNodeId, state.currentTerm, false, state.lastApplied);
                    return;
                }
                // 4a.0 先落盘 dump.rdb.index（fix：index 先于 rdb 写入，关闭「rdb 新 + index/conf 旧」信任误判窗口；
                //     写失败 → ACK false 让 Leader 重发快照，绝不带着旧 index 继续）
                if (!runDumpRdbIndexWriter(done.lastIncludedIndex)) {
                    logger.error("handleInstallSnapshot: dump.rdb.index 写入失败，中止快照安装");
                    sendSnapshotAck(fromNodeId, state.currentTerm, false, state.lastApplied);
                    return;
                }
                // 4a. 加载到内存 + 落盘 dump.rdb（RdbDataLoader 内部完成 copy 到 dump.rdb + load）
                boolean loaded = loadIncomingSnapshot(done.tempFile);
                if (!loaded) {
                    logger.error("handleInstallSnapshot: 加载快照失败, 会话={}", sessionId);
                    sendSnapshotAck(fromNodeId, state.currentTerm, false, state.lastApplied);
                    return;
                }

                // 4b. 截断 log + 更新 commitIndex/lastApplied/lastIncluded*
                applySnapshotToState(done.lastIncludedIndex, done.lastIncludedTerm);

                // 4c. 持久化 MeshState（WAL 重写 + conf 新边界）
                runPersistHook();

                logger.info("handleInstallSnapshot: 快照加载完成 lastIncluded={}/{}, bytes={}, 回 ACK 给 {}",
                        done.lastIncludedIndex, done.lastIncludedTerm, receivedBytes, fromNodeId);
                // 4d. 回 ACK（复用 AppendEntriesResponse，success=true, matchIndex=lastIncludedIndex）
                sendSnapshotAck(fromNodeId, state.currentTerm, true, done.lastIncludedIndex);
            } finally {
                // 清理会话（无论成功失败）
                if (incoming != null) {
                    deleteQuietly(incoming.tempFile);
                }
                incoming = null;
                chunkOut = null;
                receivedBytes = 0;
            }
        }
        // 非 done chunk：静默累积，不回响应（Leader 在 done 后才等 ACK）
    }

    /**
     * 用 {@link RdbDataLoader} 加载临时 RDB 文件到 {@link #rawStore}。
     * <p>{@code RdbDataLoader} 内部会把临时文件 copy 到 dump.rdb 再调 {@code load}，
     * 故本步同时完成"内存加载 + dump.rdb 落盘"（DESIGN §5.4 步骤 3）。</p>
     *
     * @return true=加载成功
     */
    private boolean loadIncomingSnapshot(File tempRdbFile) {
        // RdbDataLoader.startLoading 会创建它自己的临时文件路径；这里我们已累积好 tempRdbFile。
        // 为复用其 writeChunk→finishLoading 流程，先 startLoading（建空 temp），再把累积文件内容
        // 通过 writeChunk 喂入。这样走统一的 finishLoading 路径（含 dump.rdb 落盘 + keysLoaded 统计）。
        if (!dataLoader.startLoading(rawStore, null)) {
            logger.error("loadIncomingSnapshot: startLoading 失败（可能上次加载未结束）");
            return false;
        }
        try {
            // 把累积好的临时文件按 RdbDataLoader 的 writeChunk 分批喂入
            byte[] buf = new byte[64 * 1024];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(tempRdbFile);
                 java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis)) {
                int n;
                while ((n = bis.read(buf)) != -1) {
                    byte[] chunk = (n == buf.length) ? buf : java.util.Arrays.copyOf(buf, n);
                    if (!dataLoader.writeChunk(Unpooled.wrappedBuffer(chunk))) {
                        logger.error("loadIncomingSnapshot: writeChunk 失败");
                        return false;
                    }
                }
            }
            long keys = dataLoader.finishLoading(rawStore);
            logger.info("loadIncomingSnapshot: 加载完成 keys={}", keys);
            return keys >= 0;
        } catch (Exception e) {
            logger.error("loadIncomingSnapshot: 异常", e);
            dataLoader.cancelLoading();
            return false;
        }
    }

    /**
     * 把已加载的快照边界应用到 MeshState：丢弃已被快照覆盖的 log、更新 lastIncludedIndex/lastIncludedTerm、
     * 推进 commitIndex/lastApplied。
     *
     * <p>顺序：<b>先用当前（旧）lastIncludedIndex 调 {@link MeshState#discardUpToInclusive}</b>
     * 丢弃 ≤ snapIndex 的条目（保留 > snapIndex 的 tail），再赋值新 lastIncludedIndex（见
     * {@link MeshState#discardUpToInclusive} 的调用顺序约定）。若 snapIndex &le; 本地 lastIncludedIndex，
     * 视为重复/旧快照，不更新 lastIncluded（避免倒退），但仍推进 commitIndex/lastApplied。</p>
     *
     * @param snapIndex  快照最后包含的 index
     * @param snapTerm   snapIndex 对应 term
     */
    private void applySnapshotToState(long snapIndex, long snapTerm) {
        if (snapIndex > state.lastIncludedIndex) {
            // 先截断（用旧 lastIncludedIndex 换算 list 索引），再推进 lastIncludedIndex
            state.discardUpToInclusive(snapIndex);
            state.lastIncludedIndex = snapIndex;
            state.lastIncludedTerm = snapTerm;
        }
        // 推进 commitIndex / lastApplied 到 snapIndex
        if (state.commitIndex < snapIndex) {
            state.commitIndex = snapIndex;
        }
        if (state.lastApplied < snapIndex) {
            state.lastApplied = snapIndex;
        }
    }

    // ==================== 工具方法 ====================

    /** 复用 AppendEntriesResponse 回 Leader（success + matchIndex + 当前 term）。 */
    private void sendSnapshotAck(String leaderId, long term, boolean success, long matchIndex) {
        AppendEntriesResponse resp = new AppendEntriesResponse(term, success, matchIndex);
        MeshFrame frame = new MeshFrame(nodeId, MessageType.APPEND_ENTRIES_RESP.getCode(), resp.encode());
        try {
            busClient.send(leaderId, frame);
        } catch (Exception e) {
            logger.warn("sendSnapshotAck: 发往 {} 失败", leaderId, e);
        }
    }

    /** 构造会话标识：(leaderId, lastIncludedIndex, lastIncludedTerm)。 */
    private String sessionKey(InstallSnapshotMessage msg) {
        return msg.getLeaderId() + ":" + msg.getLastIncludedIndex() + ":" + msg.getLastIncludedTerm();
    }

    private void closeChunkOutQuietly() {
        BufferedOutputStream out = chunkOut;
        if (out != null) {
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                logger.warn("closeChunkOutQuietly: 关闭失败", e);
            }
        }
        chunkOut = null;
    }

    private void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (IOException e) {
                logger.warn("deleteQuietly: 删除失败 file={}", f.getAbsolutePath(), e);
            }
        }
    }

    private void copyFile(File source, File target) throws IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(source);
             java.io.FileOutputStream fos = new java.io.FileOutputStream(target);
             java.io.BufferedInputStream bis = new java.io.BufferedInputStream(fis);
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos)) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = bis.read(buffer)) != -1) {
                bos.write(buffer, 0, n);
            }
        }
    }

    private void runPersistHook() {
        if (persistHook != null) {
            try {
                persistHook.run();
            } catch (Exception e) {
                logger.warn("runPersistHook: 持久化 hook 异常（阶段 11 之前可忽略）", e);
            }
        }
    }

    /**
     * 落盘 dump.rdb.index：记录 dump.rdb 对应的 lastIncludedIndex，供启动时比对衔接。
     * <p><b>必须在 dump.rdb 写入之前调用</b>（fix：index 先于 rdb——信任校验通过 ⟹ conf 已落盘 ⟹ 同轮 rdb 已写完；
     * 若 rdb 先写，崩溃后 index==conf==旧 + rdb==新 → 重启信任误判 → (旧,新] 条目双 apply）。
     * 失败返回 false，调用方必须中止快照（若继续写 rdb，index 仍为旧值 → 信任误判窗口复开）。
     * {@code dumpRdbIndexWriter} 为 {@code null} 时跳过（阶段 10 之前的测试兼容），视为成功。</p>
     *
     * @return true=写入成功（或索引写入未启用）；false=写入失败（调用方应中止快照流程）
     */
    private boolean runDumpRdbIndexWriter(long lastIncludedIndex) {
        if (dumpRdbIndexWriter == null) {
            return true;
        }
        try {
            dumpRdbIndexWriter.accept(lastIncludedIndex);
            return true;
        } catch (Exception e) {
            logger.error("runDumpRdbIndexWriter: 写 dump.rdb.index 失败 lastIncludedIndex={}",
                    lastIncludedIndex, e);
            return false;
        }
    }

    // ==================== 测试辅助（包级可见） ====================

    /** 取当前 chunk 大小（测试用）。 */
    int getChunkSizeBytes() {
        return chunkSizeBytes;
    }

    /** 取周期快照阈值（测试用）。 */
    long getSnapshotLogThreshold() {
        return snapshotLogThreshold;
    }

    /** 取当前 Follower 接收会话（测试用；可能为 null）。 */
    IncomingSnapshot getIncoming() {
        return incoming;
    }

    /** 取已接收字节数（测试用）。 */
    long getReceivedBytes() {
        return receivedBytes;
    }

    /** Follower 接收会话内部状态（测试可观测）。 */
    static final class IncomingSnapshot {
        final String sessionId;
        final File tempFile;
        final long lastIncludedIndex;
        final long lastIncludedTerm;
        final String leaderId;

        IncomingSnapshot(String sessionId, File tempFile,
                         long lastIncludedIndex, long lastIncludedTerm, String leaderId) {
            this.sessionId = sessionId;
            this.tempFile = tempFile;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
            this.leaderId = leaderId;
        }
    }
}
