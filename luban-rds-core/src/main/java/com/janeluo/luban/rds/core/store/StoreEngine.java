package com.janeluo.luban.rds.core.store;

import java.util.List;

/**
 * 存储引擎统一回调接口：OffHeapStringEngine 和 OnHeapStructEngine 都实现。
 * HybridMemoryStore 持有引擎实例，通过此接口做淘汰/过期/聚合。
 */
interface StoreEngine {

    /** 引擎标识，用于 EvictionCandidate.engineId。 */
    String engineId();

    /** 按策略采样 n 个淘汰候选（不含已过期）。volatileOnly 时只返回有 TTL 的。 */
    List<EvictionCandidate> sampleForEviction(int database, String policy, int n);

    /** 驱逐指定 key（OffHeap 实现内部含 ByteBuf release）。返回实际释放的字节估算。 */
    long evict(int database, String key);

    /** 过期扫描：随机抽 budget 个 key，删到期的。返回实际删除数。 */
    int expireBatch(int database, int budget);

    /** 当前 key 总数。 */
    int size(int database);

    /** 引擎估算占用内存。 */
    long estimateUsedMemory();
}
