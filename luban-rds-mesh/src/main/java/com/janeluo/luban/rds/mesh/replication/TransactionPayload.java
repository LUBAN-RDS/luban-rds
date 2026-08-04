package com.janeluo.luban.rds.mesh.replication;

import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MULTI/EXEC 事务载荷（{@code LogEntry.extra}）的序列化/反序列化工具
 * （DESIGN.md §5.8 场景 8 / 阶段 9）。
 *
 * <p>整事务单条 {@link LogEntry} 的 {@code extra} 由两部分组成：</p>
 * <ol>
 *   <li><b>WATCH 版本快照</b>：Leader 在 EXEC 到达 gate 时对每个 WATCH 的
 *       {@code (db, key)} 捕获的 {@code MemoryStore.getKeyVersion(...)} 值。
 *       apply 时按此快照做校验，任一不符即事务中止（返回 {@code *-1}，RESP null multi）。</li>
 *   <li><b>命令帧序列</b>：事务队列内各命令的完整原始 RESP 帧
 *       （与客户端发来的字节完全一致；apply 按序执行并收集响应组装成 RESP 数组）。</li>
 * </ol>
 *
 * <h3>二进制格式</h3>
 * <p>基于 {@link DataOutputStream}/{@link DataInputStream}，全部 multi-byte 数值按大端写入
 * （int 4 字节、long 8 字节）。byte[] 与 String（key）统一用 length-prefix
 * （复用 {@link LogEntry#writeBytes} / {@link LogEntry#writeUtf8}）。</p>
 *
 * <pre>
 * extra = [
 *   watchCount (int),
 *   watch entries (each: int db | utf8 key | long version),
 *   commandCount (int),
 *   commands (each: int frameLen | frameBytes)
 * ]
 * </pre>
 *
 * <p><b>空事务</b>：{@code watchCount=0} 且 {@code commandCount=0}，
 * apply 后返回空 RESP 数组 {@code *0\r\n}（与 Redis 行为一致）。</p>
 *
 * <h3>线程模型</h3>
 * <p>本类为无状态静态工具，所有方法对入参只读，可被多线程并发调用。</p>
 */
public final class TransactionPayload {

    private TransactionPayload() {
    }

    // ==================== WATCH 快照条目 ====================

    /**
     * 单个 WATCH 条目：{@code (db, key)} + 捕获时的版本号。
     * <p>不可变值对象；apply 时按 {@link #db}/{@link #key} 取 rawStore 当前版本与
     * {@link #version} 比对，不等则事务中止。</p>
     */
    public static final class WatchEntry {

        private final int db;
        private final String key;
        private final long version;

        public WatchEntry(int db, String key, long version) {
            this.db = db;
            this.key = key;
            this.version = version;
        }

        public int getDb() {
            return db;
        }

        public String getKey() {
            return key;
        }

        public long getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return "WatchEntry{db=" + db + ", key=" + key + ", version=" + version + '}';
        }
    }

    // ==================== 反序列化产物 ====================

    /**
     * 反序列化后的事务载荷：WATCH 快照 + 命令帧序列。
     * <p>不可变值对象（命令帧列表为不可修改视图）。</p>
     */
    public static final class Decoded {

        private final List<WatchEntry> watchEntries;
        private final List<byte[]> commandFrames;

        public Decoded(List<WatchEntry> watchEntries, List<byte[]> commandFrames) {
            this.watchEntries = watchEntries;
            this.commandFrames = commandFrames;
        }

        public List<WatchEntry> getWatchEntries() {
            return watchEntries;
        }

        public List<byte[]> getCommandFrames() {
            return commandFrames;
        }

        /** WATCH 条目数量。 */
        public int watchCount() {
            return watchEntries.size();
        }

        /** 队列内命令数量。 */
        public int commandCount() {
            return commandFrames.size();
        }

        /** 是否为空事务（无命令）。 */
        public boolean isEmptyTransaction() {
            return commandFrames.isEmpty();
        }
    }

    // ==================== 编码 ====================

    /**
     * 序列化事务载荷为 {@code byte[]}（即 {@link LogEntry#getExtra()}）。
     *
     * @param watchEntries  WATCH 版本快照（可为空列表）
     * @param commandFrames 事务队列内各命令的完整 RESP 帧（可为空列表）
     * @return 序列化字节；不会返回 null
     */
    public static byte[] encode(List<WatchEntry> watchEntries, List<byte[]> commandFrames) {
        List<WatchEntry> watches = watchEntries == null ? Collections.emptyList() : watchEntries;
        List<byte[]> frames = commandFrames == null ? Collections.emptyList() : commandFrames;

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            // 1. WATCH 快照
            out.writeInt(watches.size());
            for (WatchEntry w : watches) {
                out.writeInt(w.getDb());
                LogEntry.writeUtf8(out, w.getKey());
                out.writeLong(w.getVersion());
            }
            // 2. 命令帧序列
            out.writeInt(frames.size());
            for (byte[] frame : frames) {
                LogEntry.writeBytes(out, frame);
            }
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream 写内存不会真抛 IO（仅签名声明）
            throw new RuntimeException("TransactionPayload encode 失败", e);
        }
    }

    /**
     * 便捷重载：无 WATCH 快照（事务未 WATCH 任何 key）。
     *
     * @param commandFrames 事务队列内各命令的完整 RESP 帧
     * @return 序列化字节
     */
    public static byte[] encodeCommands(List<byte[]> commandFrames) {
        return encode(Collections.<WatchEntry>emptyList(), commandFrames);
    }

    /**
     * 单命令事务便捷构造（payload = 1 帧，无 WATCH）。
     *
     * @param commandFrame 完整 RESP 帧
     * @return 序列化字节
     */
    public static byte[] encodeSingleCommand(byte[] commandFrame) {
        List<byte[]> frames = new ArrayList<>(1);
        frames.add(commandFrame);
        return encodeCommands(frames);
    }

    // ==================== 解码 ====================

    /**
     * 反序列化 {@link LogEntry#getExtra()} 为 {@link Decoded}。
     *
     * @param extra 事务载荷字节（不应为 null）
     * @return 解析结果
     * @throws RuntimeException 格式非法（长度不足、负长度等）
     */
    public static Decoded decode(byte[] extra) {
        if (extra == null || extra.length == 0) {
            return new Decoded(Collections.<WatchEntry>emptyList(), Collections.<byte[]>emptyList());
        }
        try (ByteArrayInputStream bais = new ByteArrayInputStream(extra);
             DataInputStream in = new DataInputStream(bais)) {
            // 1. WATCH 快照
            int watchCount = in.readInt();
            if (watchCount < 0) {
                throw new IOException("非法 watchCount: " + watchCount);
            }
            List<WatchEntry> watches = new ArrayList<>(watchCount);
            for (int i = 0; i < watchCount; i++) {
                int db = in.readInt();
                String key = LogEntry.readUtf8(in);
                long version = in.readLong();
                watches.add(new WatchEntry(db, key, version));
            }
            // 2. 命令帧序列
            int commandCount = in.readInt();
            if (commandCount < 0) {
                throw new IOException("非法 commandCount: " + commandCount);
            }
            List<byte[]> frames = new ArrayList<>(commandCount);
            for (int i = 0; i < commandCount; i++) {
                byte[] frame = LogEntry.readBytes(in);
                if (frame == null) {
                    throw new IOException("命令帧 " + i + " 为 null（长度 -1）");
                }
                frames.add(frame);
            }
            return new Decoded(
                    Collections.unmodifiableList(watches),
                    Collections.unmodifiableList(frames));
        } catch (IOException e) {
            // DataInputStream 读内存不会真抛 IO，除非格式非法（EOF/负长度）
            throw new RuntimeException("TransactionPayload decode 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 提取命令帧数量（不解析完整结构，供日志/断言）。
     *
     * @param extra 事务载荷字节
     * @return 命令数量；extra 为 null/空返回 0
     */
    public static int commandCountOf(byte[] extra) {
        if (extra == null || extra.length == 0) {
            return 0;
        }
        return decode(extra).commandCount();
    }

    // ==================== UTF-8 工具（备用，与 LogEntry.writeUtf8 同口径） ====================

    /**
     * 将 byte[] 按 UTF-8 解码为字符串（仅调试/日志用）。
     */
    static String utf8(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
