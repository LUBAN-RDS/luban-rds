package com.janeluo.luban.rds.replication;

import com.janeluo.luban.rds.core.handler.DefaultCommandHandler;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.protocol.Command;
import com.janeluo.luban.rds.protocol.RedisProtocolParser;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复制流应用器。
 *
 * <p>累积主节点传播的 RESP 字节流，使用 {@link RedisProtocolParser} 解析出完整的命令帧，
 * 并通过 {@link DefaultCommandHandler} 在从节点本地 {@link MemoryStore} 上重放每条命令。
 *
 * <p><b>多数据库限制：</b>从节点复制重放只支持单数据库语义。复制流中的 SELECT 命令仅用于
 * 追踪当前目标数据库索引（{@link #currentDatabase}），后续命令会按该索引在 MemoryStore 上重放。
 * 由于 {@link DefaultCommandHandler} 的命令执行未真正按 database 参数隔离存储，实际多 DB
 * 数据仍会落在同一存储视图；此处的 SELECT 处理保证偏移量与主节点对齐，并尽可能将 database
 * 索引透传给处理器，而非真正实现 Redis 的多 DB 切换。
 *
 * <p>处理以下场景：
 * <ul>
 *   <li>拆包（半包）：累积缓冲区中数据不足时，保留已接收字节等待后续数据到达后继续解析</li>
 *   <li>粘包：单次到达数据可能包含多条完整命令，循环解析直到缓冲区不足</li>
 *   <li>二进制安全：RESP Bulk String 经 ISO-8859-1 解码，保留任意字节序列</li>
 *   <li>偏移量追踪：按实际消费的字节数推进 appliedOffset，与主节点 backlog 偏移对齐</li>
 * </ul>
 *
 * <p>命令执行失败时抛出 {@link ReplicationApplyException}，由上层触发断开重连。
 *
 * @author janeluo
 * @since 1.0.0
 */
public class ReplicationStreamApplier {

    private static final Logger logger = LoggerFactory.getLogger(ReplicationStreamApplier.class);

    /** 累积缓冲区初始容量 */
    private static final int INITIAL_BUFFER_CAPACITY = 1024;

    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    private final ByteBuf accumulationBuffer;

    /** 已应用字节偏移量，对应主节点 backlog 的 masterReplOffset */
    private long appliedOffset;

    /**
     * 当前复制目标数据库索引。
     *
     * <p>复制流中的 SELECT 命令会更新此值，后续命令重放时透传给 {@link DefaultCommandHandler}。
     * 默认为 0。注意：这是单 DB 复制限制下的简化处理，见类级 Javadoc。
     */
    private int currentDatabase = 0;

    /** 是否已关闭，防止 close() 重复释放累积缓冲区 */
    private volatile boolean closed = false;

    /**
     * 创建复制流应用器。
     *
     * @param memoryStore 从节点本地内存存储
     */
    public ReplicationStreamApplier(MemoryStore memoryStore) {
        this.memoryStore = memoryStore;
        this.commandHandler = new DefaultCommandHandler();
        this.protocolParser = new RedisProtocolParser();
        this.accumulationBuffer = PooledByteBufAllocator.DEFAULT.buffer(INITIAL_BUFFER_CAPACITY);
        this.appliedOffset = 0L;
    }

    /**
     * 应用主节点传播的命令数据。
     *
     * <p>调用方传入 {@code data} 的所有权转移给本方法：数据会被拷贝到内部累积缓冲区后释放。
     * 随后循环解析累积缓冲区中的完整 RESP 命令帧并逐条执行，推进 appliedOffset。
     *
     * @param data 主节点传播的 RESP 字节流，可能包含半条或多条命令
     * @throws ReplicationApplyException 命令解析或执行失败
     */
    public void applyData(ByteBuf data) {
        if (data == null || !data.isReadable()) {
            if (data != null) {
                data.release();
            }
            return;
        }

        // 已关闭：直接释放传入数据，不再累积/解析，避免使用已释放的累积缓冲区
        if (closed) {
            data.release();
            return;
        }

        try {
            accumulationBuffer.writeBytes(data);
        } finally {
            data.release();
        }

        applyAccumulatedCommands();
    }

    /**
     * 循环解析并执行累积缓冲区中的完整命令。
     */
    private void applyAccumulatedCommands() {
        while (accumulationBuffer.isReadable()) {
            int readerIndexBefore = accumulationBuffer.readerIndex();
            Command command;
            try {
                command = protocolParser.parse(accumulationBuffer);
            } catch (Exception e) {
                throw new ReplicationApplyException("Failed to parse replication command stream", e);
            }

            if (command == null) {
                // 半包：剩余字节不足以构成完整命令，压缩缓冲区等待后续数据
                accumulationBuffer.discardReadBytes();
                return;
            }

            int consumed = accumulationBuffer.readerIndex() - readerIndexBefore;
            applyCommand(command);
            appliedOffset += consumed;
        }

        // 缓冲区已全部消费，重置读写指针避免无限增长
        accumulationBuffer.clear();
    }

    /**
     * 执行单条复制命令。
     *
     * <p>SELECT 命令不真正执行，仅解析参数更新 {@link #currentDatabase}，后续命令按该索引重放。
     *
     * @param command 已解析的命令
     * @throws ReplicationApplyException 命令执行失败
     */
    private void applyCommand(Command command) {
        String commandName = command.getName();
        String[] args = command.getArgs();

        if (logger.isDebugEnabled()) {
            logger.debug("Applying replicated command: {}", commandName);
        }

        // SELECT 命令仅切换当前数据库索引，不真正执行；保证与主节点偏移对齐
        if ("SELECT".equalsIgnoreCase(commandName) && args.length >= 2) {
            try {
                currentDatabase = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                logger.warn("复制流 SELECT 命令参数无效: {}", args[1]);
            }
            return; // SELECT 不需要执行，只切换数据库
        }

        try {
            Object result = commandHandler.handle(commandName, currentDatabase, args, memoryStore);
            if (logger.isTraceEnabled() && result instanceof String) {
                logger.trace("Replicated command '{}' result: {}", commandName, result);
            }
        } catch (Exception e) {
            throw new ReplicationApplyException(
                    "Failed to apply replicated command: " + commandName, e);
        }
    }

    /**
     * 获取已应用字节偏移量。
     *
     * @return 已消费并执行的 RESP 字节数
     */
    public long getAppliedOffset() {
        return appliedOffset;
    }

    /**
     * 重置应用器状态。
     *
     * <p>清空累积缓冲区并归零偏移量，用于全量同步后重新开始追踪增量偏移。
     * 已关闭的应用器重置为 no-op。
     */
    public void reset() {
        if (closed) {
            return;
        }
        accumulationBuffer.clear();
        appliedOffset = 0L;
    }

    /**
     * 关闭应用器，释放累积缓冲区。
     *
     * <p>可重复调用：内部用 {@link #closed} 标志保证只释放一次，避免重复释放导致引用计数为负。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (accumulationBuffer.refCnt() > 0) {
            accumulationBuffer.release();
        }
    }
}
