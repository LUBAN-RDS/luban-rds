package com.janeluo.luban.rds.cluster.lifecycle;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 集群内部传播 RESP 帧构建工具（P0-新3）。
 * <p>
 * 为 MIGRATE 的复制/AOF 传播构造可被从节点 {@code ReplicationStreamApplier} 与 AOF 重放
 * 的命令帧：
 * <ul>
 *   <li>{@link #delFrame(String)}：源端迁移成功后删除键的传播（对齐 Redis 对单键 DEL 的传播）；</li>
 *   <li>{@link #restoreFrame(String, long, byte[], boolean)}：目标端导入键的传播（对齐 Redis
 *       以 RESTORE 进入传播流，ttl 单位为毫秒）。</li>
 * </ul>
 * 二进制安全：键名与序列化载荷按 ISO-8859-1 映射进 RESP bulk string（每字节 0-255 对应
 * 一个字符），与 {@code ReplicationStreamApplier} 的 ISO-8859-1 解码约定一致，任意字节序列
 * 往返无损。
 * </p>
 */
public final class PropagationFrames {

    private PropagationFrames() {
    }

    /**
     * 构造单键 DEL 传播帧：{@code DEL key}。
     *
     * @param key 键名
     * @return RESP 编码的 DEL 命令帧
     */
    public static byte[] delFrame(String key) {
        return arrayFrame(new String[]{"DEL", key});
    }

    /**
     * 构造 SELECT 传播帧：{@code SELECT db}（N-30）。
     * <p>
     * 目标端导入到非 0 数据库时，在 RESTORE 帧前追加 SELECT 使复制/AOF 重放落库正确。
     * 与普通命令路径的 SELECT 传播语义一致（复制流中 SELECT 为粘性上下文切换，
     * 由 {@code ReplicationStreamApplier} 追踪 currentDatabase）。
     * </p>
     *
     * @param db 目标数据库号（≤0 时返回 null，无需切换）
     * @return RESP 编码的 SELECT 命令帧，db ≤ 0 时为 null
     */
    public static byte[] selectFrame(int db) {
        if (db <= 0) {
            return null;
        }
        return arrayFrame(new String[]{"SELECT", String.valueOf(db)});
    }

    /**
     * 构造 RESTORE 传播帧：{@code RESTORE key ttl payload [REPLACE]}。
     * <p>
     * ttl 为毫秒（对齐 Redis 7 RESTORE 的毫秒语义与本项目 {@code MemoryStore.setWithExpireMs}）。
     * payload 为 Java 序列化字节（与 MIGRATE_KEY 载荷一致），从节点经 core 模块的
     * RESTORE 处理器按同一白名单反序列化还原值对象。
     * </p>
     *
     * @param key     键名
     * @param ttlMs   TTL（毫秒），≤0 表示无过期
     * @param payload 序列化载荷
     * @param replace 是否携带 REPLACE（对齐导入时的 REPLACE 选项）
     * @return RESP 编码的 RESTORE 命令帧
     */
    public static byte[] restoreFrame(String key, long ttlMs, byte[] payload, boolean replace) {
        String ttl = ttlMs > 0 ? String.valueOf(ttlMs) : "0";
        if (replace) {
            return arrayFrame(new String[]{"RESTORE", key, ttl, isoString(payload), "REPLACE"});
        }
        return arrayFrame(new String[]{"RESTORE", key, ttl, isoString(payload)});
    }

    /**
     * 构造 RESP 数组命令帧（元素按 ISO-8859-1 编码）。
     *
     * @param args 命令参数（args[0] 为命令名）
     * @return RESP 编码字节
     */
    private static byte[] arrayFrame(String[] args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "*" + args.length + "\r\n");
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.ISO_8859_1);
            writeAscii(out, "$" + bytes.length + "\r\n");
            out.write(bytes, 0, bytes.length);
            writeAscii(out, "\r\n");
        }
        return out.toByteArray();
    }

    /**
     * 将二进制字节按 ISO-8859-1 映射为 String（每字节 → 单字符，往返无损）。
     *
     * @param bytes 二进制数据
     * @return ISO-8859-1 字符串
     */
    private static String isoString(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(bytes, 0, bytes.length);
    }
}
