package com.janeluo.luban.rds.core.store;

import io.netty.buffer.ByteBuf;

/**
 * 堆外 string entry：堆上轻量元数据 + 堆外 ByteBuf payload。
 * 字段：ByteBuf buffer(引用~4-8B) + int len + long expireTime + long lastAccessTime ≈ 36-40B 含对象头。
 * 不可变 buffer/len；expireTime/lastAccessTime 可变（淘汰/过期/LRU 更新）。
 */
final class OffHeapEntry {

    private static final long NO_EXPIRE = 0L;

    private final ByteBuf buffer;       // direct ByteBuf，引用计数句柄
    private final int len;              // payload 字节长度
    private long expireTime;            // 绝对 ms 时间戳，0 = 不过期
    private long lastAccessTime;        // LRU 最近访问时间

    OffHeapEntry(ByteBuf buffer, int len, long expireTime, long lastAccessTime) {
        this.buffer = buffer;
        this.len = len;
        this.expireTime = expireTime;
        this.lastAccessTime = lastAccessTime;
    }

    ByteBuf getBuffer() { return buffer; }
    int getLen() { return len; }
    long getExpireTime() { return expireTime; }
    long getLastAccessTime() { return lastAccessTime; }

    boolean hasExpireTime() { return expireTime != NO_EXPIRE; }

    boolean isExpired() {
        return expireTime != NO_EXPIRE && System.currentTimeMillis() >= expireTime;
    }

    void setExpireTime(long expireTime) { this.expireTime = expireTime; }
    void updateAccessTime() { this.lastAccessTime = System.currentTimeMillis(); }
    void updateAccessTime(long t) { this.lastAccessTime = t; }
}
