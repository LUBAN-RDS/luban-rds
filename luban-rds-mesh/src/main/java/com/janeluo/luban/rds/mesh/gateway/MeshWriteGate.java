package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.common.util.SlotUtils;
import com.janeluo.luban.rds.core.acl.ACLCommandCategories;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * handler 命令层写门面（DESIGN.md §7.2 / §5.1 / §5.3 / §5.7 / 决策 7）。
 * <p>
 * 替代「store 装饰器」方案：命令层 gate 只需 {@code commandName/args/rawFrame} 三样东西，
 * 写路径响应天然是 apply 的返回值，零转换（参见 DESIGN §7.2「为什么不做 store 装饰器」）。
 * </p>
 *
 * <h3>读写分流</h3>
 * <ul>
 *   <li><b>写命令/事务</b>：{@link #write(byte[], int, byte[])} 调 {@link MeshNode#propose(byte[], int, byte[])}
 *       走 Raft（多数派 commit + apply），阻塞至完成，返回 apply 产生的响应字节直写客户端 Channel。
 *       非 Leader 时 propose 以 {@link MovedToLeaderException} 完成未来，本方法解包向上抛。</li>
 *   <li><b>读命令</b>：{@link #read(int, String[])} 走本地 {@link DefaultCommandHandler#handle} 直接读
 *       raw store。Leader + 租约有效时本地执行（DESIGN §5.7）；非 Leader 抛 {@link MovedToLeaderException}。</li>
 * </ul>
 *
 * <h3>读写判定（{@link #isWriteCommand(String)}）</h3>
 * <p>
 * 复用 {@link ACLCommandCategories}（{@code @write}/{@code @read} 类别）作为基础，补充本仓库特有命令。
 * 动态命令 {@code EVAL}/{@code EVALSHA} 一律当写（DESIGN §9 风险表：mesh 不识别 Lua 内容）。
 * <b>未知命令默认按写处理</b>——强一致系统中漏复制（写当读）会导致副本间发散且不可自愈，
 * 而多复制（读当写）只增加一次 Raft RTT、可自愈，故保守取写。
 * </p>
 *
 * <h3>阶段说明</h3>
 * <ul>
 *   <li>阶段 5（本类）：write/read/redirectResponse 接口 + isWriteCommand 判定。
 *       读路径租约校验先简化（Leader 本地读，租约失效做短时 awaitValid）；阶段 7 完善严格租约/read-index。</li>
 *   <li>阶段 6 完善 {@link #redirectResponse(String)}：用 nodeId→serviceAddr 映射给出真实 ip:port
 *       （当前阶段 5 用 leaderId 作地址占位，slot 已用真实 CRC16）。</li>
 *   <li>阶段 12 集成进 {@code RedisServerHandler}（本阶段不改 RedisServerHandler）。</li>
 * </ul>
 *
 * <h3>线程模型</h3>
 * <p>
 * {@code write} 阻塞调用线程至 propose future 完成（单次写延迟 = 1 次 Raft RTT，DESIGN §9）；
 * {@code read} 在调用线程同步执行 handler.handle（与 apply 线程并发访问 raw store，
 * 由 {@code DefaultMemoryStore} 并发容器 + apply 串行保证互斥，DESIGN §5.7）。本类自身无锁、无状态。
 * </p>
 */
public class MeshWriteGate {

    private static final Logger logger = LoggerFactory.getLogger(MeshWriteGate.class);

    /** 默认 propose 阻塞超时（ms）；阶段 5 常量，后续可由配置覆盖。 */
    private static final long DEFAULT_WRITE_TIMEOUT_MS = 5_000L;
    /** 读路径租约失效时的等待上限（ms）；阶段 7 用配置替换。 */
    private static final long READ_LEASE_AWAIT_MS = 1_000L;

    private final MeshNode meshNode;
    /** 真实 DefaultMemoryStore——apply 唯一目标、读路径直接读。 */
    private final MemoryStore rawStore;
    /** 读路径本地执行用（apply 也复用同一个 handler）。 */
    private final DefaultCommandHandler handler;
    /** 响应序列化（读路径 Object → RESP 字节；复用 protocol 模块）。 */
    private final RedisProtocolParser protocolParser;
    /** 写路径 propose 阻塞超时（ms）。 */
    private final long writeTimeoutMs;

    // ==================== 读写命令集合 ====================

    /**
     * 写命令补充集——本仓库中 mutating 但不在 ACL {@code @write} 类别的命令。
     * （{@code @write} 已含 SET/INCR/HSET/LPUSH/ZADD/XADD/SADD/HDEL/LREM/LSET/LTRIM 等。）
     */
    private static final Set<String> WRITE_SUPPLEMENT = unmodifiableSet(
            // keyspace 写
            "DEL", "UNLINK", "GETSET", "RENAME", "RENAMENX", "MOVE", "COPY", "RESTORE",
            "EXPIRE", "PEXPIRE", "EXPIREAT", "PEXPIREAT", "PERSIST",
            // 管理面写
            "FLUSHDB", "FLUSHALL", "SAVE", "BGSAVE", "BGREWRITEAOF", "SHUTDOWN", "SWAPDB",
            // 连接/事务（EXEC 入 Raft；MULTI/DISCARD/WATCH 走写路径以保持简单，MULTI 起已在连接级入队不过 gate）
            "SELECT", "MULTI", "EXEC", "DISCARD", "WATCH", "UNWATCH",
            // list / set / zset store 类写
            "LINSERT", "SDIFFSTORE", "SUNIONSTORE", "SINTERSTORE",
            "ZUNIONSTORE", "ZINTERSTORE", "ZPOPMAX", "ZPOPMIN",
            // hyperloglog / geo 写
            "PFADD", "PFMERGE", "GEOADD", "GEOSEARCHSTORE",
            // 脚本：EVAL/EVALSHA 由 isWriteCommand 强制判写（DESIGN §9），此处不重复
            "SCRIPT", "FUNCTION"
    );

    /**
     * 读命令补充集——本仓库中只读但不在 ACL {@code @read} 类别的命令。
     * （{@code @read} 已含 GET/MGET/HGET/HGETALL/HMGET/HEXISTS/HLEN/LINDEX/LRANGE/LLEN/
     * SMEMBERS/SISMEMBER/SCARD/ZSCORE/ZRANGE/ZREVRANGE/ZCARD/XLEN/XRANGE 等。）
     */
    private static final Set<String> READ_SUPPLEMENT = unmodifiableSet(
            // keyspace 读
            "TYPE", "EXISTS", "TTL", "PTTL", "RANDOMKEY", "OBJECT", "TOUCH", "SCAN", "DBSIZE",
            "DUMP", "MEMORY",
            // stream 读
            "XREAD", "XINFO",
            // 集合扫描 / 随机
            "HSCAN", "SSCAN", "ZSCAN", "SRANDMEMBER",
            // geo 读
            "GEOSEARCH", "GEORADIUS", "GEORADIUSBYMEMBER",
            // 连接/控制（非 mutating，不应走 Raft）
            "PING", "ECHO", "AUTH", "HELLO", "RESET", "COMMAND", "INFO", "TIME",
            "CLIENT", "CONFIG", "ROLE", "LASTSAVE", "SLOWLOG", "acl"
    );

    // ==================== 构造 ====================

    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler) {
        this(meshNode, rawStore, handler, new RedisProtocolParser(), DEFAULT_WRITE_TIMEOUT_MS);
    }

    /**
     * 测试/定制构造器：可注入自定义 {@link RedisProtocolParser} 与写超时。
     *
     * @param meshNode       集群节点（提供 propose / isLeader / lease / getLeaderId）
     * @param rawStore       真实存储（apply 唯一目标 + 读路径直接读）
     * @param handler        读路径本地执行用命令处理器
     * @param protocolParser 响应序列化器
     * @param writeTimeoutMs 写路径 propose 阻塞超时（ms，&lt;=0 表示不超时、一直等）
     */
    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler,
                         RedisProtocolParser protocolParser, long writeTimeoutMs) {
        if (meshNode == null) {
            throw new IllegalArgumentException("meshNode 不能为 null");
        }
        if (rawStore == null) {
            throw new IllegalArgumentException("rawStore 不能为 null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (protocolParser == null) {
            throw new IllegalArgumentException("protocolParser 不能为 null");
        }
        this.meshNode = meshNode;
        this.rawStore = rawStore;
        this.handler = handler;
        this.protocolParser = protocolParser;
        this.writeTimeoutMs = writeTimeoutMs;
    }

    // ==================== 写路径 ====================

    /**
     * 写命令/事务：propose 并阻塞至 commit+apply，返回 apply 产生的响应字节（DESIGN §5.1）。
     * <p>
     * 调 {@link MeshNode#propose(byte[], int, byte[])}，阻塞等待 future 完成（带 {@link #writeTimeoutMs}
     * 超时），返回响应字节直写客户端 Channel。future 异常完成时：
     * <ul>
     *   <li>cause 为 {@link MovedToLeaderException}：解包后原样向上抛（供 handler 生成 MOVED）；</li>
     *   <li>其它异常：包装为 {@link RuntimeException} 向上抛（含失去 Leader 身份等场景）。</li>
     * </ul>
     * </p>
     *
     * @param rawRespFrame 完整 RESP 命令帧（客户端发来的原始字节；事务时为 MULTI 帧）
     * @param dbIndex      命令作用的 db
     * @param extra        事务：命令帧序列 + WATCH 版本快照；普通写为 {@code null}
     * @return apply 产生的响应字节（直写客户端 Channel）
     * @throws MovedToLeaderException 当前不是 Leader（携带 leader service 地址；阶段 5 为 nodeId 占位）
     * @throws RuntimeException        propose 超时或其它异常
     */
    public byte[] write(byte[] rawRespFrame, int dbIndex, byte[] extra) {
        CompletableFuture<byte[]> future = meshNode.propose(rawRespFrame, dbIndex, extra);
        try {
            if (writeTimeoutMs > 0) {
                return future.get(writeTimeoutMs, TimeUnit.MILLISECONDS);
            }
            return future.get();
        } catch (TimeoutException e) {
            // 超时不再等待 future（不 cancel——Raft entry 仍可能后续 commit；由上层决定如何回复客户端）
            throw new RuntimeException("mesh write propose timeout after " + writeTimeoutMs + "ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("mesh write interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                cause = e;
            }
            if (cause instanceof MovedToLeaderException) {
                // 非 Leader：原样抛，供上层 catch 生成 MOVED/MESHDOWN
                throw (MovedToLeaderException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException("mesh write propose failed", cause);
        }
    }

    // ==================== 读路径 ====================

    /**
     * 读命令：Leader + 租约有效则本地执行并返回响应字节；非 Leader 走 MOVED（DESIGN §5.7）。
     * <p>
     * 阶段 5 策略（先简单）：
     * <ol>
     *   <li>非 Leader → 抛 {@link MovedToLeaderException}（阶段 7 完善严格 lease 校验）；</li>
     *   <li>Leader + 租约有效 → {@code handler.handle(commandName, dbIndex, args, rawStore)} → 序列化响应字节；</li>
     *   <li>Leader + 租约失效 → {@code lease.awaitValid(READ_LEASE_AWAIT_MS)} 阻塞至下一轮续租后读
     *       （阶段 7 完善 read-index / 严格租约；阶段 5 先简单 await 或直接读）。</li>
     * </ol>
     * </p>
     *
     * @param dbIndex 命令作用的 db
     * @param args    命令参数（{@code args[0]=}命令名，含 key 用于本地读）
     * @return 响应字节（本地读结果序列化）
     * @throws MovedToLeaderException  非 Leader 时
     * @throws IllegalArgumentException args 为空
     */
    public byte[] read(int dbIndex, String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("read: args 不能为空");
        }

        // 1. 非 Leader → MOVED（阶段 7 完善 lease 校验）
        if (!meshNode.isLeader()) {
            throw new MovedToLeaderException(meshNode.getLeaderId());
        }

        // 2. Leader + 租约失效 → 短时等待续租（阶段 7 完善严格策略，阶段 5 先 await）
        long now = System.currentTimeMillis();
        if (!meshNode.lease().isValid(now)) {
            try {
                boolean valid = meshNode.lease().awaitValid(READ_LEASE_AWAIT_MS);
                if (!valid) {
                    // 租约仍未恢复：阶段 5 保守放行本地读（Leader 身份仍在），阶段 7 改为拒绝/读 index
                    logger.debug("read: 租约等待超时仍失效，阶段 5 放行本地读 cmd={}", args[0]);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("read: 等待租约续租被中断", e);
            }
        }

        // 3. 本地执行：handler.handle → 序列化响应字节（与 LogApplier.serializeResponse 同口径）
        String commandName = args[0];
        String upperName = commandName.trim().toUpperCase();
        Object response;
        try {
            response = handler.handle(upperName, dbIndex, args, rawStore);
        } catch (Exception e) {
            logger.error("read: 本地执行异常 cmd={}", upperName, e);
            response = "-ERR read command error: " + safeMsg(e) + "\r\n";
        }
        return serializeResponse(response);
    }

    // ==================== MOVED / MESHDOWN 生成 ====================

    /**
     * MOVED/MESHDOWN 响应生成（DESIGN §5.3 / 决策 12）。
     * <p>
     * 阶段 5 基本实现：slot 用 key 的真实 CRC16（{@link SlotUtils#getSlot}，0–16383）；
     * Leader 地址取 {@link MeshNode#getLeaderId()}（<b>阶段 5 占位为 nodeId</b>，阶段 6 注入
     * nodeId→serviceAddr 映射后给出真实 {@code ip:port}）。Leader 未知时返回 MESHDOWN。
     * </p>
     *
     * @param key 命令的 key（用于算 slot）；null/空时 slot=0
     * @return {@code "-MOVED <slot> <leaderAddr>\r\n"}；无 Leader 返回 {@code "-MESHDOWN ...\r\n"}
     */
    public String redirectResponse(String key) {
        String leaderAddr = resolveLeaderServiceAddr();
        if (leaderAddr == null || leaderAddr.isEmpty()) {
            return "-MESHDOWN The mesh cluster has no leader\r\n";
        }
        int slot = SlotUtils.getSlot(key);
        return "-MOVED " + slot + " " + leaderAddr + "\r\n";
    }

    /**
     * 解析 Leader 的 service 地址（{@code host:port}）。
     * <p>
     * 阶段 5：{@link MeshConfig} 尚无 nodeId→serviceAddr 映射，暂以 {@code leaderId} 作占位返回。
     * 阶段 6 完善后从配置查真实 service 端口（非 bus 端口）。
     * </p>
     *
     * @return Leader service 地址；无 Leader 返回 {@code null}
     */
    protected String resolveLeaderServiceAddr() {
        String leaderId = meshNode.getLeaderId();
        // 阶段 6：从 MeshConfig 查 nodeId→serviceAddr 映射；此处先用 leaderId 占位
        return leaderId;
    }

    // ==================== 写命令判定 ====================

    /**
     * 判定命令是否走写路径（propose）。大小写不敏感。
     * <p>
     * 判定顺序：
     * <ol>
     *   <li>{@code EVAL}/{@code EVALSHA} → 写（DESIGN §9：mesh 不识别 Lua 内容，统一当写）；</li>
     *   <li>属于 ACL {@code @write} 类别 → 写；</li>
     *   <li>属于 {@link #WRITE_SUPPLEMENT} → 写；</li>
     *   <li>属于 ACL {@code @read} 类别 → 读；</li>
     *   <li>属于 {@link #READ_SUPPLEMENT} → 读；</li>
     *   <li><b>未知命令默认写</b>（强一致优先：漏复制不可自愈，多复制可自愈）。</li>
     * </ol>
     * </p>
     *
     * @param commandName 命令名（args[0]）；null/空返回 true（保守当写）
     * @return true=写路径（propose）；false=读路径（本地读）
     */
    public static boolean isWriteCommand(String commandName) {
        if (commandName == null || commandName.isEmpty()) {
            return true;
        }
        String upper = commandName.trim().toUpperCase();
        // 动态脚本统一当写
        if ("EVAL".equals(upper) || "EVALSHA".equals(upper)) {
            return true;
        }
        if (ACLCommandCategories.isCommandInCategory(upper, "@write")) {
            return true;
        }
        if (WRITE_SUPPLEMENT.contains(upper)) {
            return true;
        }
        if (ACLCommandCategories.isCommandInCategory(upper, "@read")) {
            return false;
        }
        if (READ_SUPPLEMENT.contains(upper)) {
            return false;
        }
        // 未知命令保守当写（强一致优先）
        return true;
    }

    // ==================== 序列化辅助 ====================

    /** 把 handler.handle 返回的响应对象序列化为 RESP 字节（与 LogApplier.serializeResponse 同口径）。 */
    private byte[] serializeResponse(Object response) {
        Object resp = response == null ? "$-1\r\n" : response;
        ByteBuf buf = protocolParser.serialize(resp);
        if (buf == null) {
            return new byte[0];
        }
        try {
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            if (buf.refCnt() > 0) {
                buf.release();
            }
        }
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private static Set<String> unmodifiableSet(String... items) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(items)));
    }
}
