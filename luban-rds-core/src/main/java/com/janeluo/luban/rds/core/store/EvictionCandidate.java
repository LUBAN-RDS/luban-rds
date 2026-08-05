package com.janeluo.luban.rds.core.store;

/** 淘汰候选：由各引擎采样产出，供 EvictionScheduler 合并排序。 */
final class EvictionCandidate {
    final String engineId;      // "offheap" | "onheap"
    final String key;
    final long lastAccessTime;  // LRU 排序键
    final long expireTime;      // TTL 排序键（剩余寿命 = expireTime - now）
    final int database;

    EvictionCandidate(String engineId, int database, String key, long lastAccessTime, long expireTime) {
        this.engineId = engineId;
        this.database = database;
        this.key = key;
        this.lastAccessTime = lastAccessTime;
        this.expireTime = expireTime;
    }
}
