package com.janeluo.luban.rds.mesh.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.mesh.core.MeshRole;
import com.janeluo.luban.rds.mesh.core.MeshState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

/**
 * raft-nodes.conf 原子读写（DESIGN §7.4 / IMPLEMENTATION_PLAN 阶段 11）。
 *
 * <p>负责 {@link MeshState} 持久化字段（{@code currentTerm / votedFor / logTail /
 * lastIncludedIndex / lastIncludedTerm}）的原子落盘与启动加载。<b>不持久化</b>
 * {@code commitIndex / lastApplied / leaderId / role}（运行时字段，重启后由快照 + 重放重建）。</p>
 *
 * <h3>raft-nodes.conf 格式（DESIGN §7.4）</h3>
 * <pre>{@code
 * {
 *   "nodeId": "abc123...",
 *   "currentTerm": 5,
 *   "votedFor": "xyz789...",
 *   "lastIncludedIndex": 100,
 *   "lastIncludedTerm": 4,
 *   "logTail": [
 *     {"term": 5, "index": 101, "dbIndex": 0, "payload": "<base64>", "extra": "<base64>"}
 *   ]
 * }
 * }</pre>
 * <ul>
 *   <li>{@code logTail} 仅含快照截断后的 tail（{@code index > lastIncludedIndex} 的条目）。</li>
 *   <li>{@code payload} 是 {@link LogEntry#getRespPayload()} 的 base64（避免 JSON 转义二进制）。</li>
 *   <li>{@code extra} 同理 base64；为 {@code null} 时省略该字段。</li>
 * </ul>
 *
 * <h3>原子写（复用 ClusterConfigPersister 模式）</h3>
 * <ol>
 *   <li>序列化 state 为 JSON → 写 tmp 文件（tmp 名含线程 ID 防并发）。</li>
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

    private final ObjectMapper mapper = new ObjectMapper();

    /** raft-nodes.conf 绝对路径（{@code dbDir/raft-nodes.conf}）。 */
    private final Path raftNodesFile;

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
    }

    /** raft-nodes.conf 路径（测试用）。 */
    public Path getRaftNodesFile() {
        return raftNodesFile;
    }

    /** dump.rdb.index 路径（{@code dbDir/dump.rdb.index}）。 */
    public Path dumpRdbIndexFile() {
        return raftNodesFile.resolveSibling(DUMP_RDB_INDEX_FILENAME);
    }

    // ==================== 保存（原子写）====================

    /**
     * 原子写 {@link MeshState} 到 raft-nodes.conf（tmp + fsync + ATOMIC_MOVE）。
     *
     * <p><b>线程安全</b>：tmp 文件名含线程 ID，避免多线程并发保存时共享固定 tmp 名导致的竞态。
     * 实际上 mesh 模块所有 persist 调用都在 {@code raftExecutor} 单线程上，但保留此防御。</p>
     *
     * @param state  待持久化的状态（取 currentTerm/votedFor/lastIncludedIndex/lastIncludedTerm/log）
     * @param nodeId 本节点 nodeId（写入 JSON 头，启动加载时校验归属）
     * @throws IOException 写文件失败
     */
    public void save(MeshState state, String nodeId) throws IOException {
        if (state == null) {
            throw new IllegalArgumentException("state 不能为 null");
        }
        Path parent = raftNodesFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // 序列化为 JSON 字节
        byte[] json = serialize(state, nodeId);

        // 唯一 tmp 文件名（含线程 ID），避免并发竞态（复用 ClusterConfigPersister 模式）
        Path tmp = raftNodesFile.resolveSibling(
                raftNodesFile.getFileName().toString()
                        + ".tmp." + Thread.currentThread().getId());

        try {
            // 1. 写 tmp 文件
            Files.write(tmp, json, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            // 2. fsync（同时刷数据 + 元数据；对齐 Redis rewriteConfig / cluster N-28 教训）
            try (java.nio.channels.FileChannel channel =
                         java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }

            // 3. 原子替换 tmp -> target
            try {
                Files.move(tmp, raftNodesFile,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                logger.warn("文件系统不支持原子移动，降级为普通替换: {}", raftNodesFile);
                Files.move(tmp, raftNodesFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (logger.isTraceEnabled()) {
                logger.trace("raft-nodes.conf 保存成功: term={}, votedFor={}, logTail={}, lastIncluded={}/{}",
                        state.currentTerm, state.votedFor, state.log.size(),
                        state.lastIncludedIndex, state.lastIncludedTerm);
            }
        } catch (IOException | RuntimeException e) {
            // 清理 tmp 文件，避免残留
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            logger.error("保存 raft-nodes.conf 失败: target={}, tmp={}", raftNodesFile, tmp, e);
            if (e instanceof IOException) {
                throw (IOException) e;
            }
            throw e;
        }
    }

    // ==================== 加载（启动）====================

    /**
     * 启动加载 raft-nodes.conf → {@link MeshState}。
     *
     * @return 恢复的 state；<b>文件不存在时返回 {@code null}</b>（首次启动，调用方新建空 state）
     * @throws IOException          读文件失败
     * @throws MeshConfigParseException JSON 解析/格式非法——<b>不静默重置 term</b>（DESIGN §5.5）
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
            // JSON 解析失败 → 抛异常，不静默重置 term（DESIGN §5.5）
            throw new MeshConfigParseException(
                    "raft-nodes.conf JSON 解析失败（不静默重置 term）: " + raftNodesFile, e);
        }
        if (root == null || !root.isObject()) {
            throw new MeshConfigParseException(
                    "raft-nodes.conf 顶层非 JSON 对象: " + raftNodesFile);
        }

        return deserialize(root);
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

    // ==================== 序列化 / 反序列化 ====================

    /**
     * 序列化 {@link MeshState} 持久化字段为 JSON 字节。
     * <p>logTail 在读锁内拷贝快照（避免遍历期间被 append/truncate）。</p>
     */
    private byte[] serialize(MeshState state, String nodeId) {
        ObjectNode root = mapper.createObjectNode();
        root.put("nodeId", nodeId);
        root.put("currentTerm", state.currentTerm);
        // votedFor 为 null 时写 null（JSON null），便于反序列化区分"未投票"与"空字符串"
        if (state.votedFor == null) {
            root.putNull("votedFor");
        } else {
            root.put("votedFor", state.votedFor);
        }
        root.put("lastIncludedIndex", state.lastIncludedIndex);
        root.put("lastIncludedTerm", state.lastIncludedTerm);

        // logTail：在 readLock 内遍历（防并发 append/truncate）
        ArrayNode logTail = mapper.createArrayNode();
        List<LogEntry> snapshot;
        state.readLock().lock();
        try {
            snapshot = new ArrayList<>(state.log);
        } finally {
            state.readLock().unlock();
        }
        Base64.Encoder b64 = Base64.getEncoder();
        for (LogEntry e : snapshot) {
            ObjectNode item = mapper.createObjectNode();
            item.put("term", e.getTerm());
            item.put("index", e.getIndex());
            item.put("dbIndex", e.getDbIndex());
            item.put("payload", b64.encodeToString(e.getRespPayload()));
            // extra 为 null 时省略（反序列化时缺失视为 null）
            if (e.getExtra() != null) {
                item.put("extra", b64.encodeToString(e.getExtra()));
            }
            logTail.add(item);
        }
        root.set("logTail", logTail);

        try {
            return mapper.writeValueAsBytes(root);
        } catch (IOException e) {
            // Jackson 写内存不会抛 IO，仅因接口签名
            throw new RuntimeException("MeshState JSON 序列化失败", e);
        }
    }

    /**
     * 反序列化 JSON → {@link MeshState}。
     * <p>运行时字段初始化：{@code commitIndex = lastApplied = lastIncludedIndex}、
     * {@code role = FOLLOWER}、{@code leaderId = null}。</p>
     */
    private MeshState deserialize(JsonNode root) {
        MeshState state = new MeshState();

        // 必填字段缺失 → 视为损坏（不静默重置）
        if (!root.has("currentTerm")) {
            throw new MeshConfigParseException("raft-nodes.conf 缺少 currentTerm 字段");
        }
        state.currentTerm = root.get("currentTerm").asLong();
        // votedFor 可为 null
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

        // logTail 反序列化（payload/extra base64 解码）
        Base64.Decoder b64 = Base64.getDecoder();
        JsonNode logTail = root.get("logTail");
        if (logTail != null && logTail.isArray()) {
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

        // 运行时字段：commitIndex/lastApplied 初始化到快照边界（由后续重放推进）
        state.commitIndex = state.lastIncludedIndex;
        state.lastApplied = state.lastIncludedIndex;
        state.role = MeshRole.FOLLOWER;
        state.leaderId = null;
        return state;
    }

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

    // ==================== 异常类 ====================

    /**
     * raft-nodes.conf / dump.rdb.index 解析失败异常。
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
