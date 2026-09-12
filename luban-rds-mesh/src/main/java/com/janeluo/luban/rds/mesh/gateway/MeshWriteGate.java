package com.janeluo.luban.rds.mesh.gateway;

import com.janeluo.luban.rds.common.util.SlotUtils;
import com.janeluo.luban.rds.core.acl.ACLCommandCategories;
import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.MeshConfig;
import com.janeluo.luban.rds.mesh.MeshConfig.ReadConsistency;
import com.janeluo.luban.rds.mesh.MeshNode;
import com.janeluo.luban.rds.mesh.client.LeaseInvalidException;
import com.janeluo.luban.rds.mesh.client.MeshClientRedirector;
import com.janeluo.luban.rds.mesh.client.MovedToLeaderException;
import com.janeluo.luban.rds.mesh.client.RetryableMeshException;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
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
 *       raw store。Leader + 租约有效时本地执行（DESIGN §5.7）；非 Leader 抛 {@link MovedToLeaderException}；
 *       租约失效按 {@link MeshConfig#getReadConsistency()} 切换：lease 模式被动等续租（超时抛
 *       {@link LeaseInvalidException}），read-index 模式主动确认（同步等当前心跳续租）后才读。</li>
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
 *   <li>阶段 5：write/read/redirectResponse 接口 + isWriteCommand 判定（读路径为简化版）。</li>
 *   <li>阶段 6 完善 {@link #redirectResponse(String)}：用 nodeId→serviceAddr 映射给出真实 ip:port
 *       （当前阶段 5 用 leaderId 作地址占位，slot 已用真实 CRC16）。</li>
 *   <li><b>阶段 7（本类读路径）</b>：完善严格 Leader Lease + read-index 退化。读路径按
 *       {@link MeshConfig#getReadConsistency()}（LEASE / READ_INDEX）切换；租约失效不再宽松放行，
 *       改抛 {@link LeaseInvalidException} 让客户端重试（防旧 Leader 分区后陈旧读）。</li>
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
    /** lease 模式租约失效时的 awaitValid 等待上限（ms），无 config 注入时用此默认。 */
    private static final long DEFAULT_READ_LEASE_WAIT_MS = 1_000L;
    /**
     * read-index 模式主动确认的等待上限（ms）。
     * <p>简化策略：等当前心跳完成续租，timeout 设为 {@code heartbeatInterval × 2 + 一点抖动余量}。
     * 区别于 lease 模式的被动 awaitValid（等「下一轮」更长时间）——read-index 表示「主动等当前心跳」，
     * 故设较短 timeout；超时说明当前心跳 RTT 内多数派未 ACK，退化为抛异常让客户端重试。</p>
     */
    private static final long DEFAULT_READ_INDEX_WAIT_MS = 300L;
    /** follower 读总预算无 config 注入时的默认值（ms）。 */
    private static final long DEFAULT_FOLLOWER_READ_MAX_WAIT_MS = 500L;
    /** readIndex 短窗口缓存无 config 注入时的默认有效期（ms；<=0 = 关闭）。 */
    private static final long DEFAULT_FOLLOWER_READ_CACHE_MS = 100L;

    /**
     * BLOCK 类命令禁用错误响应字节（DESIGN §9 风险表 / 决策 17 / 阶段 9）。
     * <p>v1 在 mesh 模式禁用 BLPOP/BRPOP/BZPOPMIN/BZPOPMAX，到达 gate 时直接返回此错误。</p>
     */
    public static final byte[] BLOCK_COMMAND_ERR_BYTES =
            "-ERR BLOCK commands are not supported in mesh mode\r\n"
                    .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

    private final MeshNode meshNode;
    /** 真实 DefaultMemoryStore——apply 唯一目标、读路径直接读。 */
    private final MemoryStore rawStore;
    /** 读路径本地执行用（apply 也复用同一个 handler）。 */
    private final DefaultCommandHandler handler;
    /** 响应序列化（读路径 Object → RESP 字节；复用 protocol 模块）。 */
    private final RedisProtocolParser protocolParser;
    /** 写路径 propose 阻塞超时（ms）。 */
    private long writeTimeoutMs;

    /**
     * P1-15（2026-09-11 审计 P1）：全局在途写上限（&lt;=0 = 不限，默认 256）——
     * pipeline N 条写串行 N×5s 占业务线程的根治：超限立即 -TRYAGAIN，占用有界。
     */
    private int maxInflightWrites = 256;

    /** P1-15：当前在途写数（超时放弃但未落定的 proposal 也计入，直到 future 落定）。 */
    private final java.util.concurrent.atomic.AtomicInteger inflightWrites =
            new java.util.concurrent.atomic.AtomicInteger();

    /** P1-15：运行时调整在途写上限（&lt;=0 = 不限）。 */
    public void setMaxInflightWrites(int max) {
        this.maxInflightWrites = max;
    }

    /** P1-15：当前在途写数（观测/测试）。 */
    public int getInflightWrites() {
        return inflightWrites.get();
    }

    /** P1-15：运行时调整写超时（ms；&lt;=0 表示不超时）。须在对外服务前设置。 */
    public void setWriteTimeoutMs(long timeoutMs) {
        this.writeTimeoutMs = timeoutMs;
    }
    /**
     * 读一致性配置（DESIGN §5.7）。null 时按默认 LEASE 行为：租约有效本地读、
     * 失效 awaitValid({@link #DEFAULT_READ_LEASE_WAIT_MS})。
     */
    private final MeshConfig config;
    /**
     * nodeId → service 地址（{@code "host:port"}）映射；只读。
     * <p>供 {@link #resolveLeaderServiceAddr()} 把 Leader 的 nodeId 解析成真实客户端可达地址，
     * 用于 {@link #redirectResponse(String)} 生成 {@code -MOVED <slot> <ip:port>}。
     * 为空映射时回退为返回 nodeId（仅占位，生产装配总会注入）。</p>
     */
    private final Map<String, String> nodeIdToServiceAddr;
    /**
     * 本节点自身 service 地址（{@code "host:port"}）；自重定向守卫用。可空（不触发守卫）。
     * <p>{@link #redirectResponse(String)} 解析出的 Leader 地址等于自身时改发 MESHDOWN，
     * 防止非 Leader 节点 MOVED 回自己触发客户端死循环。</p>
     */
    private final String selfServiceAddr;

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
            // Q4（2026-09-11 审计 P2）：KEYS 是纯读，原默认判写进 Raft（读放大 + 日志垃圾）
            "KEYS",
            // geo 读
            "GEOSEARCH", "GEORADIUS", "GEORADIUSBYMEMBER",
            // 连接/控制（非 mutating，不应走 Raft）
            "PING", "ECHO", "AUTH", "HELLO", "RESET", "COMMAND", "INFO", "TIME",
            "CLIENT", "CONFIG", "ROLE", "LASTSAVE", "SLOWLOG"
            // Q12（2026-09-11 审计 P3）：原小写 "acl" 死键已删——ACL 命令在 handler 层
            // mesh 分支显式拒绝（-ERR ACL is not supported in mesh mode），不达 gate。
    );

    /**
     * BLOCK 类命令禁用集（DESIGN §9 风险表 / 决策 17 / 阶段 9）。
     * <p>
     * v1 在 mesh 模式禁用这些命令——其阻塞/唤醒路径绕过拦截层，无法被 Raft 复制：
     * <ul>
     *   <li>BLPOP/BRPOP 唤醒：{@code BlockingRequestManager.tryWakeUpWithPop} 接收 lambda，
     *       真正直调 {@code memoryStore.lpop/rpop} 在 {@code RedisServerHandler:2082-2086}，
     *       绕过集群重定向门与 AOF/传播段；</li>
     *   <li>BZPOPMIN/BZPOPMAX：同样的阻塞语义与唤醒路径；</li>
     *   <li>XREAD BLOCK：唤醒在 Stream 等待器机制（{@code RedisServerHandler:2132/2146/2220-2466}），
     *       不在 BlockingRequestManager，同样绕拦截层。
     *       <b>简化</b>：XREAD 统一当读处理（非阻塞 XREAD COUNT n 可用），不在此集合——
     *       仅当带 BLOCK 选项时才禁用（由 {@link #isBlockXRead(String[])} 判定）。</li>
     * </ul>
     * </p>
     */
    private static final Set<String> BLOCK_COMMANDS = unmodifiableSet(
            "BLPOP", "BRPOP", "BZPOPMIN", "BZPOPMAX"
    );

    // ==================== 构造 ====================

    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler) {
        this(meshNode, rawStore, handler, new RedisProtocolParser(), DEFAULT_WRITE_TIMEOUT_MS, null, null, null);
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
        this(meshNode, rawStore, handler, protocolParser, writeTimeoutMs, null, null, null);
    }

    /**
     * 阶段 7 构造器：注入 {@link MeshConfig} 以驱动读一致性模式（DESIGN §5.7）。
     *
     * @param meshNode       集群节点
     * @param rawStore       真实存储
     * @param handler        命令处理器
     * @param config         集群配置（读一致性模式 / 租约等待时长）；null 时按默认 LEASE 行为
     */
    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler,
                         MeshConfig config) {
        this(meshNode, rawStore, handler, new RedisProtocolParser(), DEFAULT_WRITE_TIMEOUT_MS, config, null, null);
    }

    /**
     * 阶段 12 装配构造器：同时注入 {@link MeshConfig} 与 nodeId→serviceAddr 映射。
     * <p>映射用于 {@link #redirectResponse(String)} 把 Leader nodeId 解析成真实 {@code ip:port}，
     * 修正阶段 5 用 nodeId 作地址占位的缺陷（MOVED 无端口致 Redisson 解码失败）。</p>
     *
     * @param meshNode            集群节点
     * @param rawStore            真实存储
     * @param handler             命令处理器
     * @param config              集群配置；null 时按默认 LEASE 行为
     * @param nodeIdToServiceAddr nodeId → service 地址（{@code "host:port"}）映射；null/空 等同空映射
     */
    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler,
                         MeshConfig config, Map<String, String> nodeIdToServiceAddr) {
        this(meshNode, rawStore, handler, config, nodeIdToServiceAddr, null);
    }

    /**
     * 阶段 12 装配构造器（带自身地址）：同时注入 {@link MeshConfig}、nodeId→serviceAddr 映射、
     * 本节点自身 service 地址。
     * <p>selfServiceAddr 用于 {@link #redirectResponse(String)} 自重定向守卫（D2）：解析出的 Leader
     * 地址等于自身时改发 MESHDOWN，防止非 Leader 节点 MOVED 回自己触发客户端死循环。</p>
     *
     * @param meshNode            集群节点
     * @param rawStore            真实存储
     * @param handler             命令处理器
     * @param config              集群配置；null 时按默认 LEASE 行为
     * @param nodeIdToServiceAddr nodeId → service 地址（{@code "host:port"}）映射；null/空 等同空映射
     * @param selfServiceAddr     本节点自身 service 地址（{@code "host:port"}）；null 时不触发自重定向守卫
     */
    public MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler,
                         MeshConfig config, Map<String, String> nodeIdToServiceAddr, String selfServiceAddr) {
        this(meshNode, rawStore, handler, new RedisProtocolParser(), DEFAULT_WRITE_TIMEOUT_MS,
                config, nodeIdToServiceAddr, selfServiceAddr);
    }

    /**
     * 全参构造器。
     *
     * @param config 集群配置（读一致性模式 / 租约等待时长）；null 时按默认 LEASE 行为
     */
    private MeshWriteGate(MeshNode meshNode, MemoryStore rawStore, DefaultCommandHandler handler,
                          RedisProtocolParser protocolParser, long writeTimeoutMs, MeshConfig config,
                          Map<String, String> nodeIdToServiceAddr, String selfServiceAddr) {
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
        this.config = config;
        this.nodeIdToServiceAddr = nodeIdToServiceAddr == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new java.util.LinkedHashMap<>(nodeIdToServiceAddr));
        this.selfServiceAddr = selfServiceAddr;
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
     * @throws MovedToLeaderException  当前不是 Leader（携带 leader service 地址；阶段 5 为 nodeId 占位）
     * @throws RetryableMeshException  propose 超时（瞬时拥塞 → TRYAGAIN 让客户端重试）
     * @throws RuntimeException         其它 propose 异常
     * @apiNote <b>BLOCK 命令禁用</b>（阶段 9 / DESIGN §9）：本方法接收原始 RESP 帧，
     *          BLOCK 命令判定需命令名/参数，由上层（阶段 12 RedisServerHandler 集成时）
     *          在调本方法前用 {@link #isBlockCommand(String, String[])} 预检；
     *          命中时直接回 {@link #blockCommandError()}，不进入 propose。
     */
    public byte[] write(byte[] rawRespFrame, int dbIndex, byte[] extra) {
        return write(null, rawRespFrame, dbIndex, extra);
    }

    /** P1-9 去重索引上限：极端堆积时整体清空（语义退化为可双写，避免索引自身膨胀）。 */
    private static final int DEDUP_INDEX_LIMIT = 1024;

    /** P1-9：超时未决 proposal 索引（channelId + sha1(frame) → 在途 future）。 */
    private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<byte[]>> timedOutProposals =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * P1-9（2026-09-11 审计 P1）：带连接标识的写路径——超时未决 proposal 去重。
     * <p>同连接同帧的重试在原 proposal 仍在途时<b>挂接同一 future</b>，不再生成第二条
     * Raft 条目（INCR 等非幂等写不再被 TRYAGAIN 重试双写）。原 proposal 落定后索引即清，
     * 窗口外的重试视为新请求（无客户端协议变更下的承诺边界，残余风险见 docs）。</p>
     *
     * @param channelId 连接标识（null = 不参与去重，走原语义）
     */
    public byte[] write(String channelId, byte[] rawRespFrame, int dbIndex, byte[] extra) {
        // Q4（2026-09-11 审计 P2）：未知命令（含拼写错误）不生成 Raft 条目——
        // 此前三节点各 apply 一条错误串（日志垃圾）。PUBLISH 由 handler 层处理不经
        // 命令注册表，属豁免白名单（P1-10 将经 Raft 复制）。
        String commandName = extractCommandName(rawRespFrame);
        if (commandName != null && !commandName.isEmpty()
                && !"PUBLISH".equals(commandName)
                && (handler == null || !handler.isCommandRegistered(commandName))) {
            throw new UnknownCommandException(commandName);
        }
        String dedupKey = channelId == null ? null
                : channelId + ":" + sha1Hex(rawRespFrame);
        if (dedupKey != null) {
            CompletableFuture<byte[]> inFlight = timedOutProposals.get(dedupKey);
            if (inFlight != null) {
                if (inFlight.isDone()) {
                    // 原 proposal 已落定：窗口关闭，清索引，走正常 propose
                    timedOutProposals.remove(dedupKey, inFlight);
                } else {
                    try {
                        // 挂接原 future（重试 = 对同一在途写的等待）
                        return inFlight.get(writeTimeoutMs, TimeUnit.MILLISECONDS);
                    } catch (TimeoutException e) {
                        throw new RetryableMeshException("mesh write still in flight, retry", e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("mesh write interrupted", e);
                    } catch (ExecutionException e) {
                        return unwrapProposeFailure(e);
                    }
                }
            }
        }
        // P1-15：在途写并发上限——超限立即 TRYAGAIN（不进入 propose），业务线程占用有界。
        // 计数经 whenComplete 回收：超时放弃但稍后落定的 future 也会正确递减。
        if (maxInflightWrites > 0 && inflightWrites.incrementAndGet() > maxInflightWrites) {
            inflightWrites.decrementAndGet();
            throw new RetryableMeshException("mesh inflight writes saturated ("
                    + maxInflightWrites + "), retry");
        }
        CompletableFuture<byte[]> future = meshNode.propose(rawRespFrame, dbIndex, extra);
        future.whenComplete((r, t) -> inflightWrites.decrementAndGet());
        try {
            if (writeTimeoutMs > 0) {
                return future.get(writeTimeoutMs, TimeUnit.MILLISECONDS);
            }
            return future.get();
        } catch (TimeoutException e) {
            // 超时不再等待 future（不 cancel——Raft entry 仍可能后续 commit；由上层决定如何回复客户端）
            // P1-9：未决 future 进入去重索引——同连接同帧重试挂接原 future，不二次 propose
            if (dedupKey != null && !future.isDone()) {
                if (timedOutProposals.size() >= DEDUP_INDEX_LIMIT) {
                    timedOutProposals.clear();
                }
                timedOutProposals.putIfAbsent(dedupKey, future);
            }
            // 瞬时拥塞 → TRYAGAIN 让客户端自动重试
            throw new RetryableMeshException(
                    "mesh write propose timeout after " + writeTimeoutMs + "ms, retry", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("mesh write interrupted", e);
        } catch (ExecutionException e) {
            return unwrapProposeFailure(e);
        }
    }

    /** P1-9：propose 异常解包（MOVED 原样抛 / 其它 RuntimeException 直抛 / 包装）。 */
    private byte[] unwrapProposeFailure(ExecutionException e) throws RuntimeException {
        Throwable cause = e.getCause();
        if (cause == null) {
            cause = e;
        }
        if (cause instanceof MovedToLeaderException) {
            throw (MovedToLeaderException) cause;
        }
        if (cause instanceof RuntimeException) {
            throw (RuntimeException) cause;
        }
        throw new RuntimeException("mesh write propose failed", cause);
    }

    /** P1-9：帧指纹（SHA-1 hex；channelId 已入 key，碰撞概率对本用途足够）。 */
    private static String sha1Hex(byte[] data) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(data == null ? new byte[0] : data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // ==================== 读路径 ====================

    /**
     * 读命令：Leader + 租约有效则本地执行并返回响应字节；非 Leader 走 MOVED（DESIGN §5.7）。
     * <p>
     * 阶段 7 完整读路径，按 {@link MeshConfig#getReadConsistency()} 切换：
     * <ol>
     *   <li>非 Leader → 默认抛 {@link MovedToLeaderException}（上层生成 MOVED/MESHDOWN）；
     *       配置 {@code mesh-read-from-follower=readindex} 时先尝试 follower 本地读
     *       （readIndex + apply 屏障），任一失败仍收敛到同一 MOVED。</li>
     *   <li><b>lease 模式（默认）</b>：租约有效直接本地读；失效则
     *       {@code lease.awaitValid(config.getReadLeaseWaitMs())} 被动等下一轮心跳续租，
     *       仍失效抛 {@link LeaseInvalidException} 让客户端重试（<b>不再放行陈旧读</b>，
     *       修正阶段 5 的宽松放行行为，防旧 Leader 分区后服务陈旧读）。</li>
     *   <li><b>read-index 模式</b>（时钟不可靠）：调 {@link #ensureReadIndex()} 主动确认
     *       （同步等当前心跳多数派 ACK 续租）后才本地读；确认失败抛
     *       {@link LeaseInvalidException}。</li>
     *   <li>本地执行：{@code handler.handle(commandName, dbIndex, args, rawStore)} → 序列化响应字节。</li>
     * </ol>
     * </p>
     *
     * @param dbIndex 命令作用的 db
     * @param args    命令参数（{@code args[0]=}命令名，含 key 用于本地读）
     * @return 响应字节（本地读结果序列化）
     * @throws MovedToLeaderException 非 Leader 时
     * @throws LeaseInvalidException  lease 模式租约续租等待超时 / read-index 模式主动确认失败
     * @throws RetryableMeshException apply fail-stop 或本地未就绪（客户端 -TRYAGAIN 退避重试）
     * @throws IllegalArgumentException args 为空
     */
    public byte[] read(int dbIndex, String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("read: args 不能为空");
        }

        // A1（fix-mesh-follower-read，修审计 F1）：apply fail-stop 后 lastApplied 冻结，
        // 但 Leader 租约仍照常续租 → 继续本地读会静默返回陈旧值。读入口 fail-fast，
        // 让客户端 -TRYAGAIN 退避重试。写入口不拦：apply 停摆时 propose 本就会超时失败，
        // 额外拦截只会拒掉可能成功的写路径（引入新的写可用性风险）。
        if (meshNode.isApplyHalted()) {
            throw new RetryableMeshException("mesh apply halted (fail-stop), please retry");
        }

        // A2（fix-mesh-follower-read）：本地状态不可信（store 为空 / 未追平）时不得作答。
        // 写入口不拦（同上理由）。
        if (!meshNode.isReady()) {
            throw new RetryableMeshException("mesh node not ready (state not caught up), please retry");
        }

        // 0. BLOCK 类命令禁用（阶段 9 / DESIGN §9）：到达 gate 即返回错误字节
        if (isBlockCommand(args[0], args)) {
            return blockCommandError();
        }

        // 1. 非 Leader：按配置决定"本地读（readindex）"还是"MOVED（off/现状）"
        if (!meshNode.isLeader()) {
            if (getEffectiveReadFromFollower() == MeshConfig.ReadFromFollower.READ_INDEX) {
                if (isScriptNotLocallyExecutable(args)) {
                    // EVALSHA 本地未命中：不本地执行，回落 MOVED（不返回 -NOSCRIPT）。
                    // 同样计入回落计数，让 mesh_follower_read_fallback_moved 覆盖全部回落原因。
                    meshNode.incFollowerReadFallback();
                    logger.debug("follower 脚本未命中回落 MOVED: cmd={}", args[0]);
                } else {
                    byte[] local = tryFollowerRead(dbIndex, args);
                    if (local != null) {
                        return local;
                    }
                    // 本地读不可用（无读点/屏障超时/执行异常）→ 回落 MOVED。
                    // 关键不变量：所有失败路径都收敛到下面的 MOVED，结构上不可能返回
                    // "未达 readIndex 的状态"。
                    meshNode.incFollowerReadFallback();
                    logger.debug("follower 读回落 MOVED: cmd={}", args[0]);
                }
            }
            String leaderId = meshNode.getLeaderId();
            String key = args.length >= 2 ? args[1] : null;
            throw new MovedToLeaderException(leaderId, null, key);
        }

        // 2. 按读一致性模式切换
        if (getEffectiveReadConsistency() == ReadConsistency.READ_INDEX) {
            // read-index：主动确认（等当前心跳多数派 ACK 续租）后才读
            ensureReadIndex();
        } else {
            // lease 模式：租约有效直接读；失效被动等续租，超时抛异常（不放行陈旧读）
            long now = System.currentTimeMillis();
            if (!meshNode.lease().isValid(now)) {
                boolean valid;
                try {
                    valid = meshNode.lease().awaitValid(getReadLeaseWaitMs());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LeaseInvalidException("read: 等待租约续租被中断", e);
                }
                if (!valid) {
                    // 续租等待超时仍失效：抛异常让客户端重试（避免陈旧读，DESIGN §5.7）
                    logger.debug("read: lease 模式租约等待超时仍失效，拒绝读 cmd={}", args[0]);
                    throw new LeaseInvalidException(
                            "mesh leader lease expired (lease mode), please retry");
                }
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

    /**
     * read-index 模式主动确认：同步等当前心跳完成多数派 ACK 续租后才读。
     * <p>
     * <b>阶段 7 简化策略</b>（DESIGN §5.7 完整 read-index 需「读前记 commitIndex、发心跳、
     * 等 commitIndex ≥ readIndex」机制，较复杂；本阶段先简化）：
     * <ul>
     *   <li>读前 {@code lease.awaitValid(heartbeatInterval × 2 + 余量)} 等当前心跳周期完成续租
     *       （约 200-300ms）。区别于 lease 模式的被动 awaitValid（设更长 timeout 等「下一轮」）：
     *       read-index 用较短 timeout 表示「主动等当前心跳」，超时即认定多数派未及时 ACK、退化为抛异常。</li>
     *   <li>若 MeshNode 心跳定时器已在周期续租，awaitValid 会在当前心跳 ACK 后立即被唤醒返回 true，
     *       语义等价于「主动确认了 Leader 仍是多数派认可的真 Leader」。</li>
     *   <li>不引入额外的同步心跳发送机制（避免复杂化 raftExecutor 与心跳线程的交互），
     *     也不主动校验 commitIndex ≥ lastApplied（Leader 的 apply 在 raftExecutor 串行推进，
     *     心跳续租成功隐含 majority matchIndex 推进、commit 已稳定）。</li>
     * </ul>
     * </p>
     * <p>超时（多数派未在当前心跳周期内 ACK）抛 {@link LeaseInvalidException} 让客户端重试。</p>
     *
     * @throws LeaseInvalidException 等待当前心跳续租超时
     */
    private void ensureReadIndex() {
        long waitMs = resolveReadIndexWaitMs();
        boolean valid;
        try {
            valid = meshNode.lease().awaitValid(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LeaseInvalidException("read-index: 等待心跳续租被中断", e);
        }
        if (!valid) {
            logger.debug("read-index: 等待当前心跳续租超时 ({}ms)，拒绝读", waitMs);
            throw new LeaseInvalidException(
                    "mesh leader lease expired (read-index mode), please retry");
        }
    }

    /** 读一致性模式：config 注入时取其配置，否则默认 LEASE。 */
    private ReadConsistency getEffectiveReadConsistency() {
        return config != null ? config.getReadConsistency() : ReadConsistency.LEASE;
    }

    /** lease 模式 awaitValid 等待上限：config 注入时取其配置，否则默认 1s。 */
    private long getReadLeaseWaitMs() {
        return config != null ? config.getReadLeaseWaitMs() : DEFAULT_READ_LEASE_WAIT_MS;
    }

    /**
     * read-index 模式 awaitValid 等待上限：config 注入 heartbeatInterval 时取 {@code heartbeatInterval × 2 + 100ms}，
     * 否则默认 {@link #DEFAULT_READ_INDEX_WAIT_MS}。
     */
    private long resolveReadIndexWaitMs() {
        if (config != null && config.getHeartbeatIntervalMs() > 0) {
            return config.getHeartbeatIntervalMs() * 2 + 100L;
        }
        return DEFAULT_READ_INDEX_WAIT_MS;
    }

    // ==================== Follower 本地读（fix-mesh-follower-read） ====================

    /** Follower 读模式：config 注入时取其值，否则默认 OFF（零回归）。 */
    private MeshConfig.ReadFromFollower getEffectiveReadFromFollower() {
        return config != null ? config.getReadFromFollower() : MeshConfig.ReadFromFollower.OFF;
    }

    /** follower 读总预算（ms）：config 注入时取其值，否则默认 500。 */
    private long getFollowerReadMaxWaitMs() {
        return config != null ? config.getFollowerReadMaxWaitMs() : DEFAULT_FOLLOWER_READ_MAX_WAIT_MS;
    }

    /** readIndex 缓存窗口（ms）：config 注入时取其值，否则默认 100。 */
    private long getFollowerReadCacheMs() {
        return config != null ? config.getFollowerReadCacheMs() : DEFAULT_FOLLOWER_READ_CACHE_MS;
    }

    /**
     * 尝试在 Follower 本地读（readIndex + apply 屏障）。
     * <p><b>任一步失败返回 {@code null}</b>，由调用方回落 MOVED——结构上不可能返回
     * "未达 readIndex 的状态"。读点来自租约有效的 Leader，本地 apply 追平该读点后
     * 本地状态至少包含该读点前的全部已提交写。</p>
     * <p>本方法只读 raw store（{@code handler.handle} 读命令），不触碰写路径。</p>
     */
    private byte[] tryFollowerRead(int dbIndex, String[] args) {
        long budget = getFollowerReadMaxWaitMs();
        long start = System.currentTimeMillis();
        // 1) 取读点（短窗口缓存 + single-flight；cacheMs<=0 时每次取新读点）。
        //    预算对半：一半留给取点 RPC，剩余留给 apply 屏障。
        long rpcTimeout = Math.max(50L, budget / 2);
        ReadIndexCache.Entry entry;
        try {
            entry = meshNode.readIndexCache().get(getFollowerReadCacheMs(), rpcTimeout,
                    () -> meshNode.fetchReadIndex(rpcTimeout), meshNode.currentTerm());
        } catch (Exception e) {
            logger.debug("follower 读取读点异常: cmd={}", args[0], e);
            return null;
        }
        if (entry == null) {
            return null;
        }
        // 2) apply 屏障：等本地 lastApplied 追平该读点
        long remain = budget - (System.currentTimeMillis() - start);
        if (remain <= 0) {
            return null;
        }
        try {
            if (!meshNode.applyBarrier().awaitApplied(entry.readIndex, remain)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        // 3) 本地执行（只读）并序列化响应
        String upperName = args[0].trim().toUpperCase();
        Object response;
        try {
            response = handler.handle(upperName, dbIndex, args, rawStore);
        } catch (Exception e) {
            logger.error("follower 本地读异常 cmd={}", upperName, e);
            return null;
        }
        meshNode.incFollowerReadLocal();
        return serializeResponse(response);
    }

    /**
     * EVALSHA 且本地脚本缓存未命中 → 不在 Follower 执行。
     * <p>回落 MOVED 而不是返回 {@code -NOSCRIPT}：集群感知客户端应被重定向到 Leader 后
     * 读到正确值，而不是因为 Follower 本地没这份脚本就报脚本缺失。</p>
     */
    private boolean isScriptNotLocallyExecutable(String[] args) {
        if (args.length < 2 || !"EVALSHA".equalsIgnoreCase(args[0])) {
            return false;
        }
        return handler.resolveScriptBody("EVALSHA", args) == null;
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
            // Q5（2026-09-11 审计 P2）：无 Leader 返回标准 -CLUSTERDOWN（主流客户端退避重试）
        return MeshClientRedirector.MESHDOWN_RESPONSE;
        }
        // D2: 自重定向守卫——解析出的 Leader 地址等于本节点自身地址时，本节点明明非 Leader
        // 却 MOVED 回自己，客户端会死循环（Redisson "MOVED redirection loop detected"）。
        // 改发 MESHDOWN 让客户端退避重试，等 Leader 稳定 / 拓扑刷新后再 MOVED 到正确地址。
        if (selfServiceAddr != null && selfServiceAddr.equals(leaderAddr)) {
            return MeshClientRedirector.MESHDOWN_SELF_REDIRECT_RESPONSE;
        }
        // P3: leaderId 不可达兜底——选举风暴中 leaderId 可能短暂指向已死节点或无效地址，
        // 解析出的 leaderAddr 不在已知 peers 映射里时，MOVED 过去客户端也连不上，
        // 还可能在多节点间形成 A→B→C→A 循环。改发 MESHDOWN 让客户端感知集群不可用并重试。
        if (!nodeIdToServiceAddr.containsValue(leaderAddr)) {
            return MeshClientRedirector.MESHDOWN_LEADER_UNREACHABLE_RESPONSE;
        }
        int slot = SlotUtils.getSlot(key);
        return "-MOVED " + slot + " " + leaderAddr + "\r\n";
    }

    /**
     * 解析 Leader 的 service 地址（{@code host:port}）。
     * <p>
     * 从构造时注入的 {@code nodeIdToServiceAddr} 映射查 Leader nodeId 对应的真实 service 地址
     * （客户端可达端口，非 bus 端口）。映射为空或查无时返回 {@code null}（→ 由
     * {@link #redirectResponse(String)} 生成 MESHDOWN，避免回写无端口的 MOVED）。
     * </p>
     *
     * @return Leader service 地址；无 Leader 或映射无此节点返回 {@code null}
     */
    protected String resolveLeaderServiceAddr() {
        String leaderId = meshNode.getLeaderId();
        if (leaderId == null) {
            return null;
        }
        return nodeIdToServiceAddr.get(leaderId);
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
    /**
     * Q4（2026-09-11 审计 P2）：从完整 RESP 命令帧解析命令名（第一个 bulk string）。
     * <p>仅解析数组头 + 第一个元素，不持有帧；帧畸形/不可解析返回 {@code null}（调用方跳过预检）。</p>
     */
    public static String extractCommandName(byte[] respFrame) {
        if (respFrame == null || respFrame.length < 4 || respFrame[0] != '*') {
            return null;
        }
        try {
            int pos = 1;
            while (pos < respFrame.length && respFrame[pos] != '\r') {
                pos++;
            }
            if (pos + 1 >= respFrame.length || respFrame[pos + 1] != '\n') {
                return null;
            }
            pos += 2;
            // 第 1 个元素：命令名 bulk string（$len\r\n<data>\r\n）
            return parseBulkStringAt(respFrame, pos);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 pos 处 bulk string（$len\r\n<data>），返回 US_ASCII 数据；非法返回 null。 */
    private static String parseBulkStringAt(byte[] frame, int pos) {
        if (pos >= frame.length || frame[pos] != '$') {
            return null;
        }
        pos++;
        int lenStart = pos;
        while (pos < frame.length && frame[pos] != '\r') {
            pos++;
        }
        if (pos + 1 >= frame.length || frame[pos + 1] != '\n') {
            return null;
        }
        int len;
        try {
            len = Integer.parseInt(new String(frame, lenStart, pos - lenStart,
                    java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NumberFormatException e) {
            return null;
        }
        if (len < 0 || pos + 2 + len > frame.length) {
            return null;
        }
        return new String(frame, pos + 2, len, java.nio.charset.StandardCharsets.US_ASCII);
    }

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

    // ==================== BLOCK 命令禁用（阶段 9 / DESIGN §9 / 决策 17） ====================

    /**
     * 判定命令是否为 mesh 模式禁用的 BLOCK 类命令（大小写不敏感）。
     * <p>
     * v1 禁用 {@code BLPOP/BRPOP/BZPOPMIN/BZPOPMAX}（集合判定）。{@code XREAD} 本身不在禁用集——
     * 非阻塞 XREAD 是普通读；仅当其参数中带 {@code BLOCK} 选项时才禁用，由
     * {@link #isBlockCommand(String, String[])} 的 args 变体重载判定。
     * </p>
     *
     * @param commandName 命令名（args[0]）；null/空返回 false（由上层 isWriteCommand/default 处理）
     * @return true=该命令在 mesh 模式应被拒绝（返回 BLOCK 错误）
     */
    public static boolean isBlockCommand(String commandName) {
        if (commandName == null || commandName.isEmpty()) {
            return false;
        }
        return BLOCK_COMMANDS.contains(commandName.trim().toUpperCase());
    }

    /**
     * 判定命令（含参数）是否为 mesh 模式禁用的 BLOCK 类命令。
     * <p>
     * 在 {@link #isBlockCommand(String)} 基础上，额外处理 {@code XREAD} 带 {@code BLOCK} 选项的情况：
     * <ul>
     *   <li>{@code BLPOP/BRPOP/BZPOPMIN/BZPOPMAX}：恒禁用；</li>
     *   <li>{@code XREAD ... BLOCK &lt;ms&gt;}：禁用（阻塞语义）；</li>
     *   <li>{@code XREAD COUNT n ...}（无 BLOCK）：不禁用（普通读）。</li>
     * </ul>
     * </p>
     *
     * @param commandName 命令名（args[0]）
     * @param args        完整参数数组（含命令名）；可传 null（仅按命令名判定）
     * @return true=该命令在 mesh 模式应被拒绝
     */
    public static boolean isBlockCommand(String commandName, String[] args) {
        if (!isBlockCommand(commandName)) {
            // 非 BLOCK 命令集，但 XREAD 带 BLOCK 选项也要拦
            return isBlockXRead(commandName, args);
        }
        return true;
    }

    /**
     * 判定 XREAD 是否带 BLOCK 选项。
     * <p>XREAD 参数形如 {@code XREAD [COUNT n] [BLOCK ms] STREAMS key id ...}，
     * 大小写不敏感查找 token {@code "BLOCK"}（非前缀匹配）。</p>
     *
     * @param commandName 命令名
     * @param args        参数数组
     * @return true=XREAD 且带 BLOCK 选项
     */
    private static boolean isBlockXRead(String commandName, String[] args) {
        if (commandName == null || commandName.isEmpty() || args == null || args.length == 0) {
            return false;
        }
        if (!"XREAD".equalsIgnoreCase(commandName.trim())) {
            return false;
        }
        for (String a : args) {
            if (a != null && "BLOCK".equalsIgnoreCase(a.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 返回 BLOCK 命令禁用错误响应字节（{@link #BLOCK_COMMAND_ERR_BYTES}）。
     * <p>供 write/read 入口拒绝 BLOCK 命令时返回，直写客户端 Channel。</p>
     *
     * @return {@code -ERR BLOCK commands are not supported in mesh mode\r\n}
     */
    public static byte[] blockCommandError() {
        return BLOCK_COMMAND_ERR_BYTES.clone();
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
