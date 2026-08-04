package com.janeluo.luban.rds.mesh.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Raft 日志条目（DESIGN.md §3.2）。
 * <p>
 * 拦截点在 handler 命令层：写命令以<strong>原始 RESP 帧</strong>（客户端发来的那份字节）入 Raft 日志，
 * 不做 store 方法级重编码（杜绝语义漂移）。apply 阶段直接走现有 RESP 解析 →
 * {@code DefaultCommandHandler.handle(...)} → {@code MemoryStore}，apply 的返回值即客户端响应字节。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code term}：创建时的任期号（long）</li>
 *   <li>{@code index}：日志中的位置（long，1-based 绝对索引，含快照偏移）</li>
 *   <li>{@code respPayload}：完整 RESP 命令帧（事务时为 MULTI 帧）；与客户端发来字节完全一致</li>
 *   <li>{@code dbIndex}：apply 时传给 handler 的 database 参数（int）</li>
 *   <li>{@code extra}：可选扩展载荷；MULTI/EXEC 事务为"命令帧序列 + WATCH 版本快照"，普通写为 {@code null}</li>
 * </ul>
 *
 * <h3>编解码约定</h3>
 * <p>
 * {@link #encode()} / {@link #decode(byte[])} 基于 {@link DataOutputStream} / {@link DataInputStream}，
 * 序列化全部 5 字段；{@code byte[]} 与 {@code String}（本类不含 String）统一用 length-prefix
 * （先写 int 长度再写字节）。{@code extra} 为 {@code null} 时写入长度 {@code -1}，解码时据此还原 {@code null}。
 * </p>
 */
public class LogEntry {

    /** 创建时的任期号 */
    private final long term;

    /** 日志中的位置（1-based 绝对索引，含快照偏移） */
    private final long index;

    /** 完整 RESP 命令帧（事务时为 MULTI 帧） */
    private final byte[] respPayload;

    /** apply 时传给 handler 的 database 参数 */
    private final int dbIndex;

    /** 事务：命令帧序列 + WATCH 版本快照；普通写为 {@code null} */
    private final byte[] extra;

    /**
     * 全参构造器。
     *
     * @param term        创建时的任期号
     * @param index       日志位置（1-based）
     * @param respPayload 完整 RESP 命令帧（可为 null，按 0 长度处理）
     * @param dbIndex     apply 时传给 handler 的 database 参数
     * @param extra       事务扩展载荷；普通写为 {@code null}
     */
    public LogEntry(long term, long index, byte[] respPayload, int dbIndex, byte[] extra) {
        this.term = term;
        this.index = index;
        this.respPayload = respPayload;
        this.dbIndex = dbIndex;
        this.extra = extra;
    }

    public long getTerm() {
        return term;
    }

    public long getIndex() {
        return index;
    }

    /** 完整 RESP 命令帧；保证非 null（构造时为 null 则返回空数组） */
    public byte[] getRespPayload() {
        return respPayload == null ? new byte[0] : respPayload;
    }

    public int getDbIndex() {
        return dbIndex;
    }

    /** 事务扩展载荷；普通写返回 {@code null} */
    public byte[] getExtra() {
        return extra;
    }

    /**
     * 序列化全部 5 字段为 byte[]。
     * <p>
     * 格式：{@code long term | long index | int payloadLen + payload | int dbIndex | int extraLen + extra}，
     * 其中 {@code extraLen = -1} 表示 extra 为 null。所有 multi-byte 数值由 {@link DataOutputStream}
     * 按大端写入（long 8 字节、int 4 字节）。
     * </p>
     *
     * @return 序列化字节
     */
    public byte[] encode() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            out.writeLong(term);
            out.writeLong(index);
            writeBytes(out, respPayload);
            out.writeInt(dbIndex);
            writeBytes(out, extra);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream/DataOutputStream 写内存不会抛 IO，仅因接口签名声明
            throw new RuntimeException("LogEntry encode 失败", e);
        }
    }

    /**
     * 反序列化 byte[] 为 LogEntry。
     *
     * @param bytes {@link #encode()} 的产物
     * @return 还原的 LogEntry
     */
    public static LogEntry decode(byte[] bytes) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            long index = in.readLong();
            byte[] respPayload = readBytes(in);
            int dbIndex = in.readInt();
            byte[] extra = readBytes(in);
            return new LogEntry(term, index, respPayload, dbIndex, extra);
        } catch (IOException e) {
            throw new RuntimeException("LogEntry decode 失败", e);
        }
    }

    // ==================== 通用 length-prefix 读写工具（供 rpc 包复用） ====================

    /**
     * 写 length-prefix 字节数组：{@code null} 写 -1，否则写 {@code int len + bytes}。
     * <p>
     * public static，供 {@code rpc} 包的 RPC 消息编解码复用（统一 byte[] / String 编码约定）。
     * </p>
     */
    public static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        if (bytes == null) {
            out.writeInt(-1);
        } else {
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    /**
     * 读 length-prefix 字节数组：读到 -1 返回 {@code null}，否则读 {@code int len + len 字节}。
     */
    public static byte[] readBytes(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return bytes;
    }

    /**
     * 写 length-prefix UTF-8 字符串：{@code null} 写 -1，否则写 {@code int byteLen + utf8Bytes}。
     */
    public static void writeUtf8(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    /**
     * 读 length-prefix UTF-8 字符串：读到 -1 返回 {@code null}。
     */
    public static String readUtf8(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0) {
            return null;
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "LogEntry{term=" + term + ", index=" + index + ", dbIndex=" + dbIndex
                + ", payloadLen=" + (respPayload == null ? 0 : respPayload.length)
                + ", extraLen=" + (extra == null ? -1 : extra.length) + '}';
    }
}
