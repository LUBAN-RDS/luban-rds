package com.janeluo.luban.rds.mesh.rpc;

import com.janeluo.luban.rds.mesh.core.LogEntry;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AppendEntries RPC 请求（DESIGN.md §4.3，消息类型 {@code 0x60}）。
 * <p>
 * Leader → Follower：心跳 + 日志复制（{@code entries} 为空即心跳）。同时也是 Leader Lease 的租约载体
 * （DESIGN §5.7：收到多数派 {@code success=true} 即续租）。
 * </p>
 *
 * <pre>
 * long term;              // Leader 当前任期
 * String leaderId;        // Leader nodeId
 * long prevLogIndex;      // 上次同步到的日志索引
 * long prevLogTerm;       // prevLogIndex 对应的任期
 * List&lt;LogEntry&gt; entries; // 本次推送的日志条目（心跳时为空）
 * long leaderCommit;      // Leader 已提交的索引
 * </pre>
 */
public class AppendEntriesMessage extends MeshRpcMessage {

    private final String leaderId;
    private final long prevLogIndex;
    private final long prevLogTerm;
    private final List<LogEntry> entries;
    private final long leaderCommit;

    /**
     * @param term         Leader 当前任期
     * @param leaderId     Leader nodeId
     * @param prevLogIndex 上次同步到的日志索引
     * @param prevLogTerm  prevLogIndex 对应的任期
     * @param entries      本次推送的日志条目（心跳时为空或 null；null 归一化为空列表）
     * @param leaderCommit Leader 已提交的索引
     */
    public AppendEntriesMessage(long term, String leaderId, long prevLogIndex, long prevLogTerm,
                                List<LogEntry> entries, long leaderCommit) {
        super(term);
        this.leaderId = leaderId;
        this.prevLogIndex = prevLogIndex;
        this.prevLogTerm = prevLogTerm;
        this.entries = entries == null ? Collections.emptyList() : new ArrayList<>(entries);
        this.leaderCommit = leaderCommit;
    }

    public String getLeaderId() {
        return leaderId;
    }

    public long getPrevLogIndex() {
        return prevLogIndex;
    }

    public long getPrevLogTerm() {
        return prevLogTerm;
    }

    /** 本次推送的日志条目（保证非 null，心跳时为空列表）。 */
    public List<LogEntry> getEntries() {
        return entries;
    }

    public long getLeaderCommit() {
        return leaderCommit;
    }

    @Override
    protected void encodeBody(DataOutputStream out) throws Exception {
        LogEntry.writeUtf8(out, leaderId);
        out.writeLong(prevLogIndex);
        out.writeLong(prevLogTerm);
        // List<LogEntry>：先 int size，再逐个 encode
        out.writeInt(entries.size());
        for (LogEntry entry : entries) {
            byte[] bytes = entry.encode();
            LogEntry.writeBytes(out, bytes);
        }
        out.writeLong(leaderCommit);
    }

    /**
     * 反序列化 byte[] 为 {@link AppendEntriesMessage}。
     * <p>约定 {@link MeshRpcMessage#decode(MessageType, byte[])} 经 {@code APPEND_ENTRIES} 分支调用。</p>
     */
    public static AppendEntriesMessage decode(byte[] body) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(body);
             DataInputStream in = new DataInputStream(bais)) {
            long term = in.readLong();
            String leaderId = LogEntry.readUtf8(in);
            long prevLogIndex = in.readLong();
            long prevLogTerm = in.readLong();
            int size = in.readInt();
            List<LogEntry> entries = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                byte[] entryBytes = LogEntry.readBytes(in);
                entries.add(LogEntry.decode(entryBytes));
            }
            long leaderCommit = in.readLong();
            return new AppendEntriesMessage(term, leaderId, prevLogIndex, prevLogTerm, entries, leaderCommit);
        } catch (Exception e) {
            throw new RuntimeException("AppendEntriesMessage decode 失败", e);
        }
    }

    @Override
    public String toString() {
        return "AppendEntriesMessage{term=" + term + ", leaderId=" + leaderId
                + ", prevLogIndex=" + prevLogIndex + ", prevLogTerm=" + prevLogTerm
                + ", entries=" + entries.size() + ", leaderCommit=" + leaderCommit + '}';
    }
}
