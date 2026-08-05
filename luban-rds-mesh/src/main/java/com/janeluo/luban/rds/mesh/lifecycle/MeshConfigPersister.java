package com.janeluo.luban.rds.mesh.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Mesh 持久化状态原子读写（raft-nodes.conf 纯头部 + raft-nodes.log WAL，DESIGN §3 / §7.4）。
 *
 * <p>负责 {@link MeshState} 持久化字段（{@code currentTerm / votedFor / logTail /
 * lastIncludedIndex / lastIncludedTerm}）的原子落盘与启动加载。<b>不持久化</b>
 * {@code commitIndex / lastApplied / leaderId / role}（运行时字段，重启后由快照 + 重放重建）。</p>
 *
 * <h3>文件布局（DESIGN §3）</h3>
 * <ul>
 *   <li>{@code raft-nodes.conf}：纯头部（nodeId / currentTerm / votedFor / lastIncludedIndex /
 *       lastIncludedTerm），小文件 O(1) 原子写；旧格式内嵌 {@code logTail} 的兼容与迁移见 {@link #load}。</li>
 *   <li>{@code raft-nodes.log}：WAL，每行一个 {@link LogEntry} JSON（{@code payload / extra} base64），
 *       常态仅追加 + fsync（O(1)）；快照截断 / 冲突截断时全量重写。</li>
 * </ul>
 *
 * <h3>raft-nodes.conf 头部格式</h3>
 * <pre>{@code
 * {
 *   "nodeId": "abc123...",
 *   "currentTerm": 5,
 *   "votedFor": "xyz789...",
 *   "lastIncludedIndex": 100,
 *   "lastIncludedTerm": 4
 * }
 * }</pre>
 *
 * <h3>raft-nodes.log WAL 行格式（每行一条）</h3>
 * <pre>{@code
 * {"term":5,"index":101,"dbIndex":0,"payload":"<base64>","extra":null}
 * }</pre>
 *
 * <h3>原子写（复用 ClusterConfigPersister 模式）</h3>
 * <ol>
 *   <li>序列化为 JSON 字节 → 写 tmp 文件（tmp 名含线程 ID 防并发）。</li>
 *   <li>{@code FileChannel.open(WRITE).force(true)} fsync（同时刷数据 + 元数据）。</li>
 *   <li>{@code Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE)}。</li>
 *   <li>{@link AtomicMoveNotSupportedException} 时降级为普通 REPLACE_EXISTING。</li>
 *   <li>catch 分支清理 tmp 文件，避免残留 tmp 污染下次启动。</li>
 * </ol>
 *
 * <h3>启动加载（DESIGN §5.5）</h3>
 * <ul>
 *   <li>文件不存在 → {@link #load} 返回 {@code null}（首次启动，调用方新建空 state）。</li>
 *   <li>文件存在但 JSON 解析失败 → <b>抛 {@link MeshConfigParseException}，不静默重置 term</b>
 *       （DESIGN §5.5：静默重置 term 会导致同任期二次投票 → 双 Leader）。调用方应中止启动。</li>
 *   <li>构造的 {@link MeshState} 运行时字段初始化为默认值：
 *       {@code commitIndex=lastIncludedIndex}、{@code lastApplied=lastIncludedIndex}、
 *       {@code role=FOLLOWER}、{@code leaderId=null}。</li>
 * </ul>
 *
 * <h3>JSON 库选择</h3>
 * <p>复用 {@code luban-rds-core} 已引入的 <b>Jackson 2.13.5</b>（{@code jackson-databind}，
 * 经 {@code luban-rds-mesh → luban-rds-core} 传递依赖可用）。避免手写 JSON 解析器的转义/边界 bug。</p>
 */
public class MeshConfigPersister {

    private static final Logger logger = LoggerFactory.getLogger(MeshConfigPersister.class);

    /** raft-nodes.conf 文件名。 */
    public static final String RAFT_NODES_FILENAME = "raft-nodes.conf";

    /** dump.rdb 对应快照索引的辅助文件（见 {@link #dumpRdbIndexFilename}）。 */
    public static final String DUMP_RDB_INDEX_FILENAME = "dump.rdb.index";

    /** raft-nodes.log WAL 文件名（日志条目增量落盘，DESIGN §3）。 */
    public static final String RAFT_LOG_FILENAME = "raft-nodes.log";

    private final ObjectMapper mapper = new ObjectMapper();

    /** raft-nodes.conf 绝对路径（{@code dbDir/raft-nodes.conf}）。 */
    private final Path raftNodesFile;

    /** WAL 绝对路径（dbDir/raft-nodes.log）。 */
    private final Path raftLogFile;

    /** 上次已落盘的 log 末条 index（-1=未知，需 initFromWal；volatile 供快照线程可见）。 */
    private volatile long lastPersistedIndex = -1L;

    /** 上次已落盘的 log 末条 term（log 空时为 lastIncludedTerm；-1=未知，需 initFromWal；volatile 供快照线程可见）。 */
    private volatile long lastPersistedTerm = -1L;

    /** 上次已落盘的头部快照（用于检测 term/votedFor/lastIncluded 变化）。 */
    private volatile HeaderSnapshot lastHeader;

    /** save 主体串行化锁（raftExecutor + SnapshotManager 线程两路调用）。 */
    private final Object persistLock = new Object();

    /**
     * @param dbDir 数据目录（dump.rdb 所在目录）
     */
    public MeshConfigPersister(String dbDir) {
        this(dbDir == null || dbDir.isEmpty()
                ? Paths.get(System.getProperty("java.io.tmpdir"))
                : Paths.get(dbDir));
    }

    /**
     * @param dbDir 数据目录（dump.rdb 所在目录）
     */
    public MeshConfigPersister(Path dbDir) {
        this.raftNodesFile = dbDir.resolve(RAFT_NODES_FILENAME);
        this.raftLogFile = dbDir.resolve(RAFT_LOG_FILENAME);
    }

    /** raft-nodes.conf 路径（测试用）。 */
    public Path getRaftNodesFile() {
        return raftNodesFile;
    }

    /** raft-nodes.log 路径（测试用）。 */
    public Path getRaftLogFile() {
        return raftLogFile;
    }

    /** dump.rdb.index 路径（{@code dbDir/dump.rdb.index}）。 */
    public Path dumpRdbIndexFile() {
        return raftNodesFile.resolveSibling(DUMP_RDB_INDEX_FILENAME);
    }

    // ==================== 保存（原子写）====================

    /**
     * 原子写纯头部 raft-nodes.conf（tmp + fsync + ATOMIC_MOVE）。
     * <p>格式不再内嵌 logTail（logTail 由 raft-nodes.log WAL 承载）；旧格式兼容见 {@link #load}。</p>
     */
    private void writeHeaderFile(long term, String votedFor,
                                 long lastIncludedIndex, long lastIncludedTerm,
                                 String nodeId) throws IOException {
        Path parent = raftNodesFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("nodeId", nodeId);
        root.put("currentTerm", term);
        if (votedFor == null) {
            root.putNull("votedFor");
        } else {
            root.put("votedFor", votedFor);
        }
        root.put("lastIncludedIndex", lastIncludedIndex);
        root.put("lastIncludedTerm", lastIncludedTerm);

        byte[] json;
        try {
            json = mapper.writeValueAsBytes(root);
        } catch (IOException e) {
            throw new RuntimeException("MeshState 头部 JSON 序列化失败", e);
        }

        Path tmp = raftNodesFile.resolveSibling(
                raftNodesFile.getFileName().toString() + ".tmp." + Thread.currentThread().getId());
        try {
            Files.write(tmp, json, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            atomicMove(tmp, raftNodesFile);
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            logger.error("保存 raft-nodes.conf 失败: target={}, tmp={}", raftNodesFile, tmp, e);
            throw e;
        }
    }

    /** tmp → target 原子替换（不支持原子移动时降级普通替换）。 */
    private void atomicMove(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            logger.warn("文件系统不支持原子移动，降级为普通替换: {}", target);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 持久化 {@link MeshState}（WAL 增量 + 纯头部 conf）。
     *
     * <p><b>分支</b>（幂等，synchronized 串行）：
     * <ol>
     *   <li>分支 1：lastIncludedIndex 变化（快照截断）→ 重写 WAL 为 log（丢弃 ≤ 边界陈旧条目）；</li>
     *   <li>分支 2：log 为空 → WAL 清空（已空则跳过）；</li>
     *   <li>分支 3：log 末条 index &lt; lastPersistedIndex（纯截断）→ 重写 WAL（去除陈旧条目）；</li>
     *   <li>分支 4：log 首条 index &gt; lastPersistedIndex+1（间隙，防御）→ 重写 WAL；</li>
     *   <li>分支 5'：frontier 条目任期 ≠ 上次落盘任期（截断+重追加）→ 重写 WAL（去除被替换的陈旧条目）；</li>
     *   <li>分支 6：常态 → 仅追加 index &gt; lastPersistedIndex 的条目 + fsync（O(1)）。</li>
     * </ol>
     * <b>任期对比（分支 5'）正确性</b>：Raft 截断只在 localTerm ≠ entry.term 时触发 → 被替换的
     * frontier 条目任期必与旧持久化不同 → 任期对比精确识别「截断+重追加」；幂等重 save 与纯追加的
     * frontier 任期不变 → 仍走 O(1) 追加；纯截断（log.last &lt; lastPersistedIndex）由分支 3 覆盖。
     * <b>conf-first</b>：头部（term/votedFor/lastIncluded）变化时先写纯头部 conf，再改 WAL——
     * WAL 追加失败不会留下「已追加但未确认」的条目（propose 失败 truncateAfter 后磁盘与内存一致）。</p>
     *
     * <p><b>线程安全</b>：主体 synchronized(persistLock)，raftExecutor 与 SnapshotManager 两路调用串行；
     * log 拷贝在 readLock 内完成。</p>
     */
    public void save(MeshState state, String nodeId) throws IOException {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为 null");
        }
        // readLock 内拷贝 log 切片（防遍历期间并发 append/truncate）；头部字段为 volatile 标量，锁外读无撕裂
        long term = state.currentTerm;
        String votedFor = state.votedFor;
        long lastIncludedIndex = state.lastIncludedIndex;
        long lastIncludedTerm = state.lastIncludedTerm;
        List<LogEntry> log;
        state.readLock().lock();
        try {
            log = new ArrayList<>(state.log);
        } finally {
            state.readLock().unlock();
        }

        synchronized (persistLock) {
            if (lastPersistedIndex < 0) {
                initFromWal(lastIncludedIndex, lastIncludedTerm);
            }
            HeaderSnapshot prev = lastHeader;
            boolean headerChanged = prev == null
                    || term != prev.term
                    || !Objects.equals(votedFor, prev.votedFor)
                    || lastIncludedIndex != prev.lastIncludedIndex
                    || lastIncludedTerm != prev.lastIncludedTerm;

            // conf-first：先写头部（纯头部，小文件 O(1)）
            if (headerChanged) {
                writeHeaderFile(term, votedFor, lastIncludedIndex, lastIncludedTerm, nodeId);
            }

            // 分支判定（prev==null 首次保存时跳过分支 1：无历史边界可比）
            if (prev != null && lastIncludedIndex != prev.lastIncludedIndex) {
                rewriteWal(log, lastIncludedIndex);                       // 分支 1：快照截断
            } else if (log.isEmpty()) {
                rewriteWal(log, lastIncludedIndex);                       // 分支 2：清空
            } else if (log.get(log.size() - 1).getIndex() < lastPersistedIndex) {
                rewriteWal(log, lastIncludedIndex);                       // 分支 3：纯截断
            } else if (log.get(0).getIndex() > lastPersistedIndex + 1) {
                rewriteWal(log, lastIncludedIndex);                       // 分支 4：间隙（防御）
            } else if (lastPersistedTerm >= 0
                    && lastPersistedIndex > lastIncludedIndex
                    && log.get((int) (lastPersistedIndex - lastIncludedIndex - 1)).getTerm() != lastPersistedTerm) {
                rewriteWal(log, lastIncludedIndex);                       // 分支 5'：截断+重追加（frontier 任期发散）
            } else {
                appendWalEntries(log, lastPersistedIndex);                // 分支 6：常态 O(1)
            }

            lastPersistedIndex = log.isEmpty() ? lastIncludedIndex : log.get(log.size() - 1).getIndex();
            lastPersistedTerm = log.isEmpty() ? lastIncludedTerm : log.get(log.size() - 1).getTerm();
            lastHeader = new HeaderSnapshot(term, votedFor, lastIncludedIndex, lastIncludedTerm);
        }
    }

    // ==================== WAL（raft-nodes.log）====================

    /**
     * 常态增量追加：把 log 中 index &gt; fromIndex 的条目逐行追加到 WAL 末尾并 fsync。
     * <p>追加路径每次 open/append/force/close（Windows 开销 µs 级，远小于全量序列化；见 DESIGN §6 优化项）。
     * force(true) 同时刷数据 + 文件大小元数据（尾部追加改变 EOF）。
     * 追加前先校验文件末字节：上次追加中断残留的断行（无换行结尾）先截断，避免断行被接续成中间行、
     * 下次启动 load 因中间行损坏硬失败（I1）。</p>
     */
    private void appendWalEntries(List<LogEntry> log, long fromIndex) throws IOException {
        List<LogEntry> toAppend = new ArrayList<>();
        for (LogEntry e : log) {
            if (e.getIndex() > fromIndex) {
                toAppend.add(e);
            }
        }
        if (toAppend.isEmpty()) {
            return;
        }
        Path parent = raftLogFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // 断行修复：上次追加中断可能残留无换行结尾的断行 → 先截断（否则断行被接续成中间行，load 硬失败）
        truncateBrokenWalTail();
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                raftLogFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE)) {
            for (LogEntry e : toAppend) {
                byte[] line = serializeEntry(e);
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(line);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }
            }
            channel.force(true);
        }
    }

    /**
     * 修复 WAL 末尾断行（上次追加中断留下的无换行结尾字节）。
     * <p>断行从未被确认（追加失败即 save 抛异常、propose 回滚），追加前先物理截断到最后一个
     * '\n' 之后（全文无 '\n' 则清空），避免断行被下一次追加接续成中间行、下次启动 load 硬失败。
     * 末字节为 '\n' 时 O(1) 返回（无断行）。</p>
     *
     * @return true=实际截断了断行
     */
    private boolean truncateBrokenWalTail() throws IOException {
        if (!Files.exists(raftLogFile) || Files.size(raftLogFile) == 0) {
            return false;
        }
        try (RandomAccessFile raf = new RandomAccessFile(raftLogFile.toFile(), "rw")) {
            long size = raf.length();
            raf.seek(size - 1);
            if (raf.read() == '\n') {
                return false;
            }
            // 从尾部向前找最后一个 '\n'（断行至多一行长；分块扫描防御极端情况）
            long cut = size;
            byte[] chunk = new byte[4096];
            while (cut > 0) {
                long start = Math.max(0, cut - chunk.length);
                int len = (int) (cut - start);
                raf.seek(start);
                raf.readFully(chunk, 0, len);
                for (int i = len - 1; i >= 0; i--) {
                    if (chunk[i] == '\n') {
                        cut = start + i + 1; // 保留该 '\n'，断行起点在它之后
                        raf.setLength(cut);
                        raf.getFD().sync();
                        logger.warn("raft-nodes.log 末尾断行已截断（该条目从未确认）: {}", raftLogFile);
                        return true;
                    }
                }
                cut = start;
            }
            // 全文无 '\n'（整个文件都是断行）→ 清空
            raf.setLength(0);
            raf.getFD().sync();
            logger.warn("raft-nodes.log 全文为断行，已清空（该条目从未确认）: {}", raftLogFile);
            return true;
        }
    }

    /**
     * 全量重写 WAL（快照截断/冲突截断/防御分支）：tmp + fsync + ATOMIC_MOVE（复用 conf 原子写模式）。
     * <p>log 为空且文件已不存在或已为空时返回 false（避免无意义重写）。</p>
     *
     * @return true=实际重写了文件
     */
    private boolean rewriteWal(List<LogEntry> log, long lastIncludedIndex) throws IOException {
        if (log.isEmpty()) {
            if (!Files.exists(raftLogFile) || Files.size(raftLogFile) == 0) {
                return false;
            }
        }
        Path parent = raftLogFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = raftLogFile.resolveSibling(
                raftLogFile.getFileName().toString() + ".tmp." + Thread.currentThread().getId());
        try {
            try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(
                    tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (LogEntry e : log) {
                    byte[] line = serializeEntry(e);
                    java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(line);
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }
                }
                channel.force(true);
            }
            atomicMove(tmp, raftLogFile);
            return true;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            logger.error("重写 raft-nodes.log 失败: target={}", raftLogFile, e);
            throw e;
        }
    }

    /**
     * 首次 save 对齐：扫 WAL 取已落盘末条 index 与其 term（文件缺失/空 → lastIncludedIndex/lastIncludedTerm）。
     * <p>正常路径 load() 已对齐；本方法仅防御「未 load 直接 save」（测试直构 persister 场景）。
     * 损坏行忽略（只读 index/term 字段，不中断）。</p>
     */
    private void initFromWal(long lastIncludedIndex, long lastIncludedTerm) throws IOException {
        long maxIndex = lastIncludedIndex;
        long maxTerm = lastIncludedTerm;
        if (Files.exists(raftLogFile)) {
            byte[] bytes = Files.readAllBytes(raftLogFile);
            if (bytes.length > 0) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                for (String line : text.split("\n")) {
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    try {
                        JsonNode n = mapper.readTree(line);
                        if (n != null && n.isObject() && n.has("index")) {
                            long idx = n.get("index").asLong();
                            if (idx >= maxIndex) {
                                maxIndex = idx;
                                maxTerm = n.has("term") ? n.get("term").asLong() : maxTerm;
                            }
                        }
                    } catch (IOException e) {
                        // 损坏行忽略（防御路径）
                    }
                }
            }
        }
        lastPersistedIndex = maxIndex;
        lastPersistedTerm = maxTerm;
    }

    /** 单条 LogEntry → 一行 JSON（payload/extra base64；extra 为 null 写 null，行格式统一）。 */
    private byte[] serializeEntry(LogEntry e) {
        ObjectNode item = mapper.createObjectNode();
        item.put("term", e.getTerm());
        item.put("index", e.getIndex());
        item.put("dbIndex", e.getDbIndex());
        item.put("payload", Base64.getEncoder().encodeToString(e.getRespPayload()));
        if (e.getExtra() != null) {
            item.put("extra", Base64.getEncoder().encodeToString(e.getExtra()));
        } else {
            item.putNull("extra");
        }
        try {
            byte[] line = mapper.writeValueAsBytes(item);
            byte[] out = new byte[line.length + 1];
            System.arraycopy(line, 0, out, 0, line.length);
            out[line.length] = '\n';
            return out;
        } catch (IOException ex) {
            throw new RuntimeException("LogEntry JSON 序列化失败", ex);
        }
    }

    // ==================== 加载（启动）====================

    /**
     * 启动加载 → {@link MeshState}。
     * <p>组装：纯头部 conf + raft-nodes.log（WAL）条目拼接 logTail。
     * <ul>
     *   <li>WAL 存在 → logTail = WAL 中 index &gt; lastIncludedIndex 的条目（过滤陈旧条目，多余者 warn）；
     *       旧格式 conf.logTail 同时存在时以 WAL 为准。</li>
     *   <li>WAL 缺失且 conf 有旧格式 logTail → 用之并<b>立即迁移</b>（写 WAL + 重写 conf 去 logTail）。</li>
     *   <li>WAL 最后一段无换行结尾（完整 JSON 与否，从未 ACK）→ 物理截断到该段起点 + warn；
     *       中间行损坏 → 抛 {@link MeshConfigParseException}（不静默丢弃）。</li>
     * </ul>
     * 对齐 lastPersistedIndex / lastHeader，供后续 save 增量。</p>
     */
    public MeshState load(String nodeId) throws IOException {
        if (!Files.exists(raftNodesFile)) {
            logger.info("raft-nodes.conf 不存在，首次启动: {}", raftNodesFile);
            return null;
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(raftNodesFile);
        } catch (IOException e) {
            logger.error("读取 raft-nodes.conf 失败: {}", raftNodesFile, e);
            throw e;
        }

        JsonNode root;
        try {
            root = mapper.readTree(bytes);
        } catch (IOException e) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf JSON 解析失败（不静默重置 term）: " + raftNodesFile, e);
        }
        if (root == null || !root.isObject()) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf 顶层非 JSON 对象: " + raftNodesFile);
        }

        // 头部字段（currentTerm 必填；其余可缺省）
        MeshState state = new MeshState();
        if (!root.has("currentTerm")) {
            throw new MeshConfigParseException("raft-nodes.conf 缺少 currentTerm 字段");
        }
        state.currentTerm = root.get("currentTerm").asLong();
        JsonNode votedNode = root.get("votedFor");
        if (votedNode != null && !votedNode.isNull()) {
            state.votedFor = votedNode.asText();
        }
        if (root.has("lastIncludedIndex")) {
            state.lastIncludedIndex = root.get("lastIncludedIndex").asLong();
        }
        if (root.has("lastIncludedTerm")) {
            state.lastIncludedTerm = root.get("lastIncludedTerm").asLong();
        }

        // logTail 来源：WAL 优先；WAL 缺失时旧格式 conf.logTail（并迁移）
        JsonNode legacyTail = root.get("logTail");
        if (Files.exists(raftLogFile)) {
            loadWalInto(state);
        } else if (legacyTail != null && legacyTail.isArray()) {
            parseLegacyTail(state, legacyTail);
            migrateLegacyToWal(state, nodeId);
        }

        // 运行时字段：commitIndex/lastApplied 初始化到快照边界（由后续重放推进）
        state.commitIndex = state.lastIncludedIndex;
        state.lastApplied = state.lastIncludedIndex;
        state.role = MeshRole.FOLLOWER;
        state.leaderId = null;

        // 对齐增量状态（供后续 save 使用）
        synchronized (persistLock) {
            this.lastPersistedIndex = state.log.isEmpty()
                    ? state.lastIncludedIndex : state.log.get(state.log.size() - 1).getIndex();
            this.lastPersistedTerm = state.log.isEmpty()
                    ? state.lastIncludedTerm : state.log.get(state.log.size() - 1).getTerm();
            this.lastHeader = new HeaderSnapshot(state.currentTerm, state.votedFor,
                    state.lastIncludedIndex, state.lastIncludedTerm);
        }
        return state;
    }

    /**
     * 解析 WAL 全部行到 state.log（writeLock 内追加）。
     * <p>末尾一段无换行结尾（完整 JSON 与否）一律按未确认截断到该段起点——serializeEntry 恒以 '\n'
     * 结尾且 save 成功时 force 已刷入，无 '\n' 必为从未 ACK 的写入（I1b）；中间行损坏 → 抛异常。
     * 过滤 index &lt;= lastIncludedIndex 的陈旧条目（conf-first 崩溃态，warn 记录）。</p>
     */
    private void loadWalInto(MeshState state) throws IOException {
        byte[] bytes = Files.readAllBytes(raftLogFile);
        if (bytes.length == 0) {
            return;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int pos = 0;
        int lineNo = 0;
        int filtered = 0;
        state.writeLock().lock();
        try {
            while (pos < text.length()) {
                int nl = text.indexOf('\n', pos);
                boolean hasNl = nl >= 0;
                int lineEnd = hasNl ? nl : text.length();
                String line = text.substring(pos, lineEnd);
                if (!hasNl) {
                    // 无换行结尾的末行：从未确认（serializeEntry 恒以 '\n' 结尾且 save 成功时 force 已刷入），
                    // 与 truncateBrokenWalTail 保持一致语义：无 '\n' ⇒ 未确认 ⇒ 截断。
                    // （此前仅解析失败才截断——完整 JSON 但无 '\n' 的行会被保留，下次 save 又会被截掉 → WAL 空洞）
                    truncateWalTo(pos);
                    logger.warn("raft-nodes.log 末尾无换行结尾（从未确认），已截断: {}", raftLogFile);
                    break;
                }
                if (!line.isEmpty()) {
                    try {
                        LogEntry entry = parseWalLine(line);
                        if (entry.getIndex() <= state.lastIncludedIndex) {
                            filtered++; // 陈旧条目（conf-first 崩溃态：conf 已新、WAL 未重写）
                        } else {
                            state.log.add(entry);
                        }
                    } catch (MeshConfigParseException e) {
                        // 中间行损坏 → 抛异常（无换行分支已在上方守卫处理，此处必为中间行）
                        throw new MeshConfigParseException(
                                "raft-nodes.log 第 " + (lineNo + 1) + " 行 JSON 损坏: "
                                        + raftLogFile, e);
                    }
                }
                pos = nl + 1;
                lineNo++;
            }
        } finally {
            state.writeLock().unlock();
        }
        if (filtered > 0) {
            logger.warn("raft-nodes.log 含 {} 条 <= lastIncludedIndex 的陈旧条目，已过滤: {}",
                    filtered, raftLogFile);
        }
    }

    /** 解析单行 WAL JSON → LogEntry。 */
    private LogEntry parseWalLine(String line) {
        JsonNode item;
        try {
            item = mapper.readTree(line);
        } catch (IOException e) {
            throw new MeshConfigParseException("raft-nodes.log 行 JSON 解析失败: " + raftLogFile, e);
        }
        if (item == null || !item.isObject()) {
            throw new MeshConfigParseException("raft-nodes.log 行非 JSON 对象: " + raftLogFile);
        }
        long term = requireLong(item, "term");
        long index = requireLong(item, "index");
        int dbIndex = item.has("dbIndex") ? item.get("dbIndex").asInt() : 0;
        Base64.Decoder b64 = Base64.getDecoder();
        byte[] payload = requireBytesB64(item, "payload", b64);
        byte[] extra = optionalBytesB64(item, "extra", b64);
        return new LogEntry(term, index, payload, dbIndex, extra);
    }

    /**
     * 物理截断 WAL 到指定字节偏移（半行清理）。
     * <p>前提：WAL 内容全 ASCII（JSON 数字 + base64），String 字符偏移 == 字节偏移。</p>
     */
    private void truncateWalTo(int byteOffset) throws IOException {
        try (java.nio.channels.FileChannel channel =
                     java.nio.channels.FileChannel.open(raftLogFile, StandardOpenOption.WRITE)) {
            channel.truncate(byteOffset);
            channel.force(true);
        }
    }

    /** 旧格式 conf.logTail → state.log（与 WAL 行同构）。 */
    private void parseLegacyTail(MeshState state, JsonNode logTail) {
        Base64.Decoder b64 = Base64.getDecoder();
        state.writeLock().lock();
        try {
            for (JsonNode item : logTail) {
                if (!item.isObject()) {
                    throw new MeshConfigParseException(
                            "raft-nodes.conf logTail 元素非 JSON 对象: " + item);
                }
                long term = requireLong(item, "term");
                long index = requireLong(item, "index");
                int dbIndex = item.has("dbIndex") ? item.get("dbIndex").asInt() : 0;
                byte[] payload = requireBytesB64(item, "payload", b64);
                byte[] extra = optionalBytesB64(item, "extra", b64);
                state.log.add(new LogEntry(term, index, payload, dbIndex, extra));
            }
        } finally {
            state.writeLock().unlock();
        }
    }

    /**
     * 旧格式迁移：把 conf.logTail 写入 WAL + 重写 conf（去 logTail）。
     * <p>在 load 内同步完成，避免「进程先崩溃、旧 conf 仍在但 WAL 缺失」的中间态——
     * 迁移后所有 save 都是 WAL 增量。conf 重写保留原头部值。</p>
     */
    private void migrateLegacyToWal(MeshState state, String nodeId) throws IOException {
        logger.info("检测到旧格式 raft-nodes.conf（内嵌 logTail={} 条），迁移到 raft-nodes.log",
                state.log.size());
        List<LogEntry> tail;
        state.readLock().lock();
        try {
            tail = new ArrayList<>(state.log);
        } finally {
            state.readLock().unlock();
        }
        rewriteWal(tail, state.lastIncludedIndex);
        writeHeaderFile(state.currentTerm, state.votedFor,
                state.lastIncludedIndex, state.lastIncludedTerm, nodeId);
    }

    // ==================== dump.rdb 快照索引辅助文件 ====================

    /**
     * 原子写 {@code dump.rdb.index}（记录 dump.rdb 对应的 lastIncludedIndex，启动时比对衔接）。
     *
     * <p>dump.rdb 本身不记录它对应的快照索引。为判断「dump.rdb 快照索引 = lastIncludedIndex」，
     * 用此辅助文件单独记录。{@link SnapshotManager} 在写完 dump.rdb 后调本方法落盘索引。</p>
     *
     * @param lastIncludedIndex dump.rdb 对应的快照索引
     * @throws IOException 写失败
     */
    public void saveDumpRdbIndex(long lastIncludedIndex) throws IOException {
        Path idx = dumpRdbIndexFile();
        Path parent = idx.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = idx.resolveSibling(
                idx.getFileName().toString() + ".tmp." + Thread.currentThread().getId());
        try {
            byte[] payload = Long.toString(lastIncludedIndex).getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, payload, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(tmp, idx, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, idx, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    /**
     * 读取 {@code dump.rdb.index}。
     *
     * @return dump.rdb 对应的快照索引；文件不存在返回 -1（无记录，视为不可信衔接）
     * @throws IOException              读失败
     * @throws MeshConfigParseException 内容非合法 long（文件损坏，不静默忽略）
     */
    public long loadDumpRdbIndex() throws IOException {
        Path idx = dumpRdbIndexFile();
        if (!Files.exists(idx)) {
            return -1L;
        }
        String text = new String(Files.readAllBytes(idx), StandardCharsets.UTF_8).trim();
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new MeshConfigParseException(
                    "dump.rdb.index 内容非法（非 long）: " + idx + " content=" + text, e);
        }
    }

    // ==================== 字段解析辅助 ====================

    private static long requireLong(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.canConvertToLong()) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf 缺少/非法字段: " + field);
        }
        return v.asLong();
    }

    private static byte[] requireBytesB64(JsonNode node, String field, Base64.Decoder b64) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf 缺少字段: " + field);
        }
        try {
            return b64.decode(v.asText());
        } catch (IllegalArgumentException e) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf base64 解码失败: " + field, e);
        }
    }

    private static byte[] optionalBytesB64(JsonNode node, String field, Base64.Decoder b64) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        try {
            return b64.decode(v.asText());
        } catch (IllegalArgumentException e) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf base64 解码失败: " + field, e);
        }
    }

    // ==================== 内部类 ====================

    /** 已落盘头部快照（幂等比较用）。 */
    private static final class HeaderSnapshot {
        final long term;
        final String votedFor;
        final long lastIncludedIndex;
        final long lastIncludedTerm;

        HeaderSnapshot(long term, String votedFor, long lastIncludedIndex, long lastIncludedTerm) {
            this.term = term;
            this.votedFor = votedFor;
            this.lastIncludedIndex = lastIncludedIndex;
            this.lastIncludedTerm = lastIncludedTerm;
        }
    }

    // ==================== 异常类 ====================

    /**
     * raft-nodes.conf / raft-nodes.log / dump.rdb.index 解析失败异常。
     * <p><b>调用方必须中止启动</b>（不静默重置 term，DESIGN §5.5）。</p>
     */
    public static class MeshConfigParseException extends RuntimeException {
        public MeshConfigParseException(String message) {
            super(message);
        }

        public MeshConfigParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
