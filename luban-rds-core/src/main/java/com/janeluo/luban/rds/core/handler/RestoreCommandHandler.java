package com.janeluo.luban.rds.core.handler;

import com.google.common.collect.Sets;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.core.store.ValueSerialization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Set;

/**
 * RESTORE 命令处理器（P0-新3）。
 * <p>
 * 语法：{@code RESTORE key ttl payload [REPLACE] [ABSTTL]}，ttl 单位为毫秒（对齐 Redis 7）。
 * <p>
 * 用途：目标节点经集群总线 MIGRATE_KEY 导入键后，以 RESTORE 帧进入复制/AOF 传播流，
 * 使目标 master 的 slave 与 AOF 重放能还原导入的键（否则 failover 后副本丢键）。
 * 载荷为 Java 序列化字节（与 MIGRATE_KEY 载荷一致），按 {@link ValueSerialization}
 * 统一白名单反序列化还原值对象。
 * </p>
 * <p>
 * 同时对齐 Redis RESTORE 的客户端可见语义：键已存在且未带 REPLACE 时返回 -BUSYKEY。
 * </p>
 */
public class RestoreCommandHandler implements CommandHandler {

    private static final Logger logger = LoggerFactory.getLogger(RestoreCommandHandler.class);

    private static final Set<String> SUPPORTED = Sets.newHashSet("RESTORE");

    @Override
    public Set<String> supportedCommands() {
        return SUPPORTED;
    }

    @Override
    public Object handle(int database, String[] args, MemoryStore store) {
        if (args.length < 4) {
            return "-ERR wrong number of arguments for 'restore' command\r\n";
        }
        String key = args[1];
        String payload = args[3];

        long ttl;
        try {
            ttl = Long.parseLong(args[2]);
            if (ttl < 0) {
                return "-ERR Invalid TTL value, must be >= 0\r\n";
            }
        } catch (NumberFormatException e) {
            return "-ERR value is not an integer or out of range\r\n";
        }

        boolean replace = false;
        // 从 args[4] 起解析选项：REPLACE / ABSTTL（ABSTTL 下 ttl 仍为绝对毫秒，与默认一致，no-op）
        for (int i = 4; i < args.length; i++) {
            String opt = args[i].toUpperCase();
            if ("REPLACE".equals(opt)) {
                replace = true;
            } else if ("ABSTTL".equals(opt)) {
                // 对齐 Redis：RESTORE 的 ttl 始终按绝对毫秒处理，与内部传播帧一致
            } else {
                return "-ERR syntax error\r\n";
            }
        }

        if (!replace && store.exists(database, key)) {
            return "-BUSYKEY Target key name already exists.\r\n";
        }

        try {
            Object value = ValueSerialization.deserialize(
                    payload.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            if (ttl > 0) {
                store.setWithExpireMs(database, key, value, ttl);
            } else {
                store.set(database, key, value);
            }
            return "+OK\r\n";
        } catch (IOException | ClassNotFoundException e) {
            logger.error("RESTORE 键 {} 失败", key, e);
            return "-ERR Bad data format\r\n";
        }
    }
}
