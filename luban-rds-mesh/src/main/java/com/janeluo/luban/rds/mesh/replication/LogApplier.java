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
     *   <li>事务（{@code entry.extra != null}）：阶段 4 暂不支持，抛
     *       {@link UnsupportedOperationException}（阶段 9 完善）。</li>
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

        // 事务暂不支持（阶段 9 完善）
        if (entry.getExtra() != null) {
            throw new UnsupportedOperationException(
                    "MULTI/EXEC 事务 apply 在阶段 9 实现，当前不支持 extra != null 的条目: " + entry);
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
}
