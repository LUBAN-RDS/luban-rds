package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.mesh.core.LogEntry;
import com.janeluo.luban.rds.protocol.Command;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 日志应用器（DESIGN.md §5.1 步骤4 / 阶段 4.3）。
 * <p>
 * 将一条 {@link LogEntry} apply 到<strong>真实 raw store</strong>，返回客户端响应对象。
 * apply 是 Raft 状态机驱动的「提交后回放」阶段：Leader 与 Follower 都用同一个 LogApplier 把
 * 已提交日志作用于 {@link MemoryStore}。
 * </p>
 *
 * <h3>核心约束（DESIGN §5.1、§7.2）</h3>
 * <ul>
 *   <li><b>只用 raw store + DefaultCommandHandler.handle</b>：绝不经过任何拦截层
 *       （如 MeshWriteGate、复制传播、AOF），防止递归 propose。</li>
 *   <li><b>不写 AOF</b>：mesh 模式 AOF 退役，Raft log 即 WAL。本类不调用
 *       {@code PersistService.recordCommand}。</li>
 *   <li>{@link DefaultCommandHandler#handle(String, int, String[], MemoryStore)} 返回 {@link Object}
 *       且不写 Channel——apply 返回值即客户端响应对象（如 {@code "+OK\r\n"}、{@code ":1\r\n"}、
 *       数组等），零转换管线（DESIGN v1.1 最大优点）。</li>
 *   <li>RESP 解析失败时返回 {@code "-ERR ..."} 错误响应对象（不抛异常中断 apply 循环）。</li>
 *   <li><b>阶段 9：MULTI/EXEC 整事务单条 LogEntry</b>（DESIGN §5.8）——当 {@code entry.extra != null}
 *       时走 {@link #applyTransaction} 分支：反序列化 WATCH 版本快照 + 命令帧序列 → WATCH 校验
 *       → 按序执行 → 组装 RESP 数组。</li>
 * </ul>
 *
 * <h3>RESP 解析对接</h3>
 * <p>
 * 复用 {@link RedisProtocolParser}（luban-rds-protocol）。{@code LogEntry.respPayload} 是
 * {@code byte[]}（客户端原始帧），而 {@link RedisProtocolParser#parse(ByteBuf)} 入参为
 * {@link ByteBuf}，故用 {@link Unpooled#wrappedBuffer(byte[])} 包装后喂给 parser。
 * 解析得到 {@link Command}，取 {@code command.getName()}（命令名）与 {@code command.getArgs()}
 * （含命令名，{@code args[0]}=命令名、{@code args[1]}=key，与 server handler 约定一致）。
 * </p>
 *
 * <h3>线程模型</h3>
 * <p>
 * v1 推荐：apply 在 {@code raftExecutor} 单线程串行执行（DESIGN §5.7：apply 串行保证互斥；
 * apply 速度通常快于网络 RTT，不阻塞心跳）。本类本身无锁、无内部状态，由调用方保证串行调用。
 * </p>
 */
public class LogApplier {

    private static final Logger logger = LoggerFactory.getLogger(LogApplier.class);

    /** apply 只用这个 handler（直接路由到各命令处理器，不经过拦截层）。 */
    private final DefaultCommandHandler handler;
    /** apply 唯一目标：真实 raw MemoryStore（DefaultMemoryStore）。 */
    private final MemoryStore rawStore;
    /** RESP 解析器（复用 protocol 模块）。 */
    private final RedisProtocolParser protocolParser;

    /**
     * @param handler  apply 唯一命令处理器（直接调 handle，不经拦截层）
     * @param rawStore apply 唯一目标存储（真实 DefaultMemoryStore）
     */
    public LogApplier(DefaultCommandHandler handler, MemoryStore rawStore) {
        this(handler, rawStore, new RedisProtocolParser());
    }

    /**
     * 测试/定制构造器：可注入自定义 {@link RedisProtocolParser}。
     *
     * @param handler        apply 命令处理器
     * @param rawStore       apply 目标存储
     * @param protocolParser RESP 解析器
     */
    public LogApplier(DefaultCommandHandler handler, MemoryStore rawStore,
                      RedisProtocolParser protocolParser) {
        if (handler == null) {
            throw new IllegalArgumentException("handler 不能为 null");
        }
        if (rawStore == null) {
            throw new IllegalArgumentException("rawStore 不能为 null");
        }
        if (protocolParser == null) {
            throw new IllegalArgumentException("protocolParser 不能为 null");
        }
        this.handler = handler;
        this.rawStore = rawStore;
        this.protocolParser = protocolParser;
    }

    /**
     * apply 一条 {@link LogEntry} 到 raw store，返回客户端响应对象。
     * <p>
     * 流程：
     * <ol>
     *   <li>事务（{@code entry.extra != null}）：走 {@link #applyTransaction}（阶段 9 完善）
     *       —— WATCH 版本校验 + 按序执行命令帧组装 RESP 数组。</li>
     *   <li>RESP 解析：{@code entry.respPayload} (byte[]) → {@link Command}
     *       （用 {@link Unpooled#wrappedBuffer(byte[])} 包成 ByteBuf 喂 parser）。</li>
     *   <li>{@code handler.handle(commandName, entry.dbIndex, args, rawStore)} → Object response。
     *       （handle 返回 Object，不写 Channel、不写 AOF）。</li>
     *   <li><b>不调 persistService.recordCommand</b>（AOF 退役，Raft log 即 WAL）。</li>
     *   <li>返回 response（= 客户端响应对象，如 {@code "+OK\r\n"}、整数、数组等）。</li>
     * </ol>
     * </p>
     *
     * @param entry 待 apply 的日志条目
     * @return 客户端响应对象（handle 的返回值；解析失败时返回 {@code "-ERR ..."} 字符串）
     */
    public Object apply(LogEntry entry) {
        if (entry == null) {
            return "-ERR nil log entry\r\n";
        }

        // 阶段 9：MULTI/EXEC 整事务单条 LogEntry（extra != null）→ 事务分支
        if (entry.getExtra() != null) {
            return applyTransaction(entry);
        }

        byte[] payload = entry.getRespPayload();
        if (payload.length == 0) {
            return "-ERR empty resp payload\r\n";
        }

        // RESP 解析：byte[] → ByteBuf → Command
        Command command;
        ByteBuf buf = Unpooled.wrappedBuffer(payload);
        try {
            command = protocolParser.parse(buf);
        } catch (Exception e) {
            logger.warn("apply: RESP 解析失败, index={}, payloadLen={}", entry.getIndex(), payload.length, e);
            return "-ERR protocol parse error\r\n";
        } finally {
            buf.release();
        }

        if (command == null) {
            logger.warn("apply: RESP 解析返回 null（不完整帧）, index={}", entry.getIndex());
            return "-ERR incomplete resp frame\r\n";
        }

        String commandName = command.getName();
        String[] args = command.getArgs();
        if (commandName == null || commandName.isEmpty()) {
            return "-ERR empty command name\r\n";
        }
        // handle 入参的 commandName 约定为大写（与 RedisServerHandler.processCommand 一致）
        String upperName = commandName.trim().toUpperCase();

        // apply 只用 raw store + handle，绝不经过拦截层；不写 AOF
        try {
            Object response = handler.handle(upperName, entry.getDbIndex(), args, rawStore);
            // handle 返回值即客户端响应对象（+OK\r\n / :1\r\n / 数组…），零转换
            if (response == null) {
                return "$-1\r\n";
            }
            return response;
        } catch (Exception e) {
            logger.error("apply: 命令执行异常, cmd={}, index={}", upperName, entry.getIndex(), e);
            return "-ERR apply command error: " + e.getMessage() + "\r\n";
        }
    }

    /**
     * apply 一条 {@link LogEntry} 并将响应对象序列化为 RESP 字节（供 propose 的 future 使用）。
     * <p>
     * 等价于 {@code serialize(apply(entry))}。复用 {@link RedisProtocolParser#serialize(Object)}
     * （与 server 的 RedisServerHandler:841 一致），保证响应字节与直连 server 完全一致。
     * </p>
     *
     * @param entry 待 apply 的日志条目
     * @return 客户端响应对象的 RESP 字节
     */
    public byte[] applyAndSerialize(LogEntry entry) {
        Object response = apply(entry);
        return serializeResponse(response);
    }

    // ==================== MULTI/EXEC 事务分支（阶段 9 / DESIGN §5.8） ====================

    /**
     * apply 一条事务 LogEntry（DESIGN.md §5.8 场景 8）。
     * <p>
     * 整事务封装为单条 {@link LogEntry}：{@code respPayload} 为 MULTI 帧（或 EXEC 帧）作事务标识，
     * {@code extra} 为 {@link TransactionPayload} 序列化的「WATCH 版本快照 + 命令帧序列」。
     * apply 流程：
     * <ol>
     *   <li>反序列化 extra 得 WATCH 快照 + 命令帧序列
     *       （{@link TransactionPayload#decode(byte[])}）。</li>
     *   <li><b>WATCH 校验</b>：对每个 WATCH 的 {@code (db, key)}，取 rawStore 当前版本
     *       （{@link MemoryStore#getKeyVersion(int, String)}），若与快照不符 → 事务中止，
     *       返回 {@code "*-1\r\n"}（RESP null multi，与 Redis WATCH 失败语义一致）。</li>
     *   <li>按序执行命令帧：每帧 RESP 解析 → {@code handler.handle(...)} → 收集响应对象。</li>
     *   <li>组装 RESP 数组：{@code *<n>\r\n} + 各响应序列化字节。</li>
     *   <li>返回该数组（= 客户端 EXEC 的响应）。</li>
     * </ol>
     * </p>
     *
     * <h3>原子性</h3>
     * <p>单条 LogEntry 被 Raft 提交后，各节点 apply 串行执行：apply 期间 rawStore 不接受其它
     * apply（{@code raftExecutor} 串行），且事务所有命令在同一 apply 调用内执行——
     * 故整个事务要么全部生效要么全部不生效（仅 WATCH 失败时不执行命令，返回 {@code *-1}）。</p>
     *
     * <h3>异常容错</h3>
     * <p>单条命令执行异常不会中断整个事务（与 Redis 行为一致：命令错误仍占一个数组元素，
     * 写命令在 handler 内已不抛——错误以 {@code "-ERR ..."} 对象返回）；extra 格式非法
     * （反序列化失败）返回 {@code "-ERR ..."} 错误响应对象。</p>
     *
     * @param entry 事务日志条目（extra 非空）
     * @return EXEC 响应：RESP 数组字节串（如 {@code "*2\r\n+OK\r\n:1\r\n"}）或
     *         {@code "*-1\r\n"}（WATCH 失败）或 {@code "-ERR ..."}（extra 格式错误）
     */
    private Object applyTransaction(LogEntry entry) {
        TransactionPayload.Decoded payload;
        try {
            payload = TransactionPayload.decode(entry.getExtra());
        } catch (RuntimeException e) {
            logger.warn("applyTransaction: extra 反序列化失败, index={}", entry.getIndex(), e);
            return "-ERR transaction payload decode error\r\n";
        }

        // 1. WATCH 版本校验
        for (TransactionPayload.WatchEntry w : payload.getWatchEntries()) {
            long currentVersion = rawStore.getKeyVersion(w.getDb(), w.getKey());
            if (currentVersion != w.getVersion()) {
                logger.debug("applyTransaction: WATCH 校验失败 db={} key={} snapshot={} current={}",
                        w.getDb(), w.getKey(), w.getVersion(), currentVersion);
                // RESP null multi（*-1\r\n），与 Redis WATCH 失败语义一致
                return "*-1\r\n";
            }
        }

        // 2. 空事务 → 空数组（*0\r\n，与 Redis EXEC 无排队命令行为一致）
        if (payload.isEmptyTransaction()) {
            return "*0\r\n";
        }

        // 3. 按序执行队列内命令，收集响应
        List<Object> responses = new java.util.ArrayList<>(payload.commandCount());
        for (byte[] frame : payload.getCommandFrames()) {
            responses.add(applyCommandFrame(frame, entry.getDbIndex()));
        }

        // 4. 组装 RESP 数组并返回（字符串形式 "*<n>\r\n + 各响应序列化"）
        return assembleRespArray(responses);
    }

    /**
     * apply 单个命令帧到 rawStore（事务内命令复用普通 apply 的解析/执行路径）。
     * <p>帧解析或执行失败时返回 {@code "-ERR ..."} 字符串（不抛异常，占一个数组元素）。</p>
     *
     * @param frame   完整 RESP 命令帧
     * @param dbIndex apply 时传给 handler 的 database 参数
     * @return 命令响应对象（{@code "+OK\r\n"} / {@code ":1\r\n"} / {@code "$-1\r\n"} / {@code "-ERR ..."}）
     */
    private Object applyCommandFrame(byte[] frame, int dbIndex) {
        if (frame == null || frame.length == 0) {
            return "-ERR empty command frame\r\n";
        }

        Command command;
        ByteBuf buf = Unpooled.wrappedBuffer(frame);
        try {
            command = protocolParser.parse(buf);
        } catch (Exception e) {
            logger.warn("applyCommandFrame: RESP 解析失败, frameLen={}", frame.length, e);
            return "-ERR protocol parse error\r\n";
        } finally {
            buf.release();
        }

        if (command == null) {
            return "-ERR incomplete resp frame\r\n";
        }

        String commandName = command.getName();
        String[] args = command.getArgs();
        if (commandName == null || commandName.isEmpty()) {
            return "-ERR empty command name\r\n";
        }
        String upperName = commandName.trim().toUpperCase();

        try {
            Object response = handler.handle(upperName, dbIndex, args, rawStore);
            return response == null ? "$-1\r\n" : response;
        } catch (Exception e) {
            logger.error("applyCommandFrame: 命令执行异常, cmd={}", upperName, e);
            return "-ERR apply command error: " + safeMsg(e) + "\r\n";
        }
    }

    /**
     * 把各命令响应对象组装为 RESP 数组（{@code *<n>\r\n + 各响应序列化字节}）。
     * <p>用 {@link RedisProtocolParser#serialize(Object)} 序列化每项后拼接，
     * 与直连 server 的 EXEC 响应字节完全一致。</p>
     *
     * @param responses 各命令响应对象
     * @return RESP 数组字节串
     */
    private Object assembleRespArray(List<Object> responses) {
        StringBuilder sb = new StringBuilder();
        sb.append('*').append(responses.size()).append("\r\n");
        for (Object resp : responses) {
            byte[] bytes = serializeResponse(resp);
            sb.append(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
        }
        return sb.toString();
    }

    /**
     * 将 apply 返回的响应对象序列化为 RESP 字节。
     * <p>
     * 复用 {@link RedisProtocolParser#serialize(Object)}（与 server RedisServerHandler:841 一致），
     * 保证响应字节与直连 server 完全一致。在 {@code onEntryApplied} 中使用，避免对已 apply 的条目二次 apply。
     * </p>
     *
     * @param response apply 返回的响应对象
     * @return RESP 字节
     */
    public byte[] serializeResponse(Object response) {
        ByteBuf respBuf = protocolParser.serialize(response);
        try {
            byte[] bytes = new byte[respBuf.readableBytes()];
            respBuf.readBytes(bytes);
            return bytes;
        } finally {
            if (respBuf.refCnt() > 0) {
                respBuf.release();
            }
        }
    }

    /** 安全提取异常 message（null 时返回简单类名），用于错误响应构造。 */
    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
