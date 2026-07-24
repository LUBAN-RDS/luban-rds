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

    /** 默认数据库索引（复制命令统一在 db 0 重放，SELECT 命令由处理器处理） */
    private static final int REPLICATION_DATABASE = 0;

    private final MemoryStore memoryStore;
    private final DefaultCommandHandler commandHandler;
    private final RedisProtocolParser protocolParser;
    private final ByteBuf accumulationBuffer;

    /** 已应用字节偏移量，对应主节点 backlog 的 masterReplOffset */
    private long appliedOffset;

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
     * @param command 已解析的命令
     * @throws ReplicationApplyException 命令执行失败
     */
    private void applyCommand(Command command) {
        String commandName = command.getName();
        String[] args = command.getArgs();

        if (logger.isDebugEnabled()) {
            logger.debug("Applying replicated command: {}", commandName);
        }

        try {
            Object result = commandHandler.handle(commandName, REPLICATION_DATABASE, args, memoryStore);
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
     */
    public void reset() {
        accumulationBuffer.clear();
        appliedOffset = 0L;
    }

    /**
     * 关闭应用器，释放累积缓冲区。
     */
    public void close() {
        accumulationBuffer.clear();
        accumulationBuffer.release();
    }
}
