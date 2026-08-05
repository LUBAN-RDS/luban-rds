package com.janeluo.luban.rds.core.store;

import java.util.ArrayList;
import java.util.List;

/**
 * 堆上结构化类型引擎：承载 hash/list/set/zset/stream + 小 string。
 *
 * <p>继承 {@link DefaultMemoryStore} 复用其全部已验证的业务逻辑（约 4390 行）与 6 策略淘汰。
 * 额外实现 {@link StoreEngine} 回调，供 HybridMemoryStore 做跨引擎淘汰/过期/聚合调度。
 *
 * <p>桥接策略：DefaultMemoryStore 的 StoreValue / DatabaseStore.storage 均为 private，
 * 因此通过 {@link DefaultMemoryStore#sampleForEvictionOnHeap} /
 * {@link DefaultMemoryStore#expireBatchOnHeap} 两个 protected 钩子 + {@link StoreEngineCand}
 * 桥接类访问，避免私有内部结构泄露给子类。
 */
public class OnHeapStructEngine extends DefaultMemoryStore implements StoreEngine {

    public static final String ENGINE_ID = "onheap";

    public OnHeapStructEngine() {
        super();
    }

    public OnHeapStructEngine(int databases, long maxMemory, String maxMemoryPolicy) {
        super(databases, maxMemory, maxMemoryPolicy);
    }

    @Override
    public String engineId() {
        return ENGINE_ID;
    }

    @Override
    public List<EvictionCandidate> sampleForEviction(int database, String policy, int n) {
        // 委托给 DefaultMemoryStore 的 protected 钩子（随机抽样 + volatileOnly 过滤），
        // 再把桥接候选转换为 EvictionCandidate。
        List<StoreEngineCand> raw = sampleForEvictionOnHeap(database, policy, n);
        List<EvictionCandidate> result = new ArrayList<>(raw.size());
        for (StoreEngineCand c : raw) {
            result.add(new EvictionCandidate(ENGINE_ID, database, c.key, c.lastAccessTime, c.expireTime));
        }
        return result;
    }

    @Override
    public long evict(int database, String key) {
        // 复用 DefaultMemoryStore.del，返回估算释放字节（驱逐前后 usedMemory 差值）。
        long before = getUsedMemory();
        del(database, key);
        return before - getUsedMemory();
    }

    @Override
    public int expireBatch(int database, int budget) {
        // 委托给 DefaultMemoryStore 的 protected 过期扫描钩子。
        return expireBatchOnHeap(database, budget);
    }

    @Override
    public int size(int database) {
        return (int) dbsize(database);
    }

    @Override
    public long estimateUsedMemory() {
        return getUsedMemory();
    }
}
