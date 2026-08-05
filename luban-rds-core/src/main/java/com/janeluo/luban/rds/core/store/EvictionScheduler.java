package com.janeluo.luban.rds.core.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 统一淘汰调度器（DD-3 X3）：合并两引擎样本，按 6 策略排序淘汰。
 *
 * <p>全局视角，保持 Redis maxmemoryPolicy 语义：
 * <ul>
 *   <li>noeviction → 直接返回 false</li>
 *   <li>lru 策略 → 按 lastAccessTime 升序（idle 越久越优先）</li>
 *   <li>ttl 策略 → 按 expireTime 升序（剩余寿命小者优先）</li>
 *   <li>random 策略 → 不排序</li>
 *   <li>volatile-* → 只采有 TTL 的候选；无候选返回 false</li>
 * </ul>
 */
public class EvictionScheduler {

    /** 每轮合并采样的总数（两引擎各取一半）。 */
    private static final int SAMPLE_SIZE = 16;
    /** 单次 tryEvictMemory 的最大驱逐轮次，避免极端情况下死循环。 */
    private static final int MAX_ROUNDS = 1000;

    private final StoreEngine offheap;
    private final StoreEngine onheap;
    private final long maxMemory;
    private final String policy;

    public EvictionScheduler(StoreEngine offheap, StoreEngine onheap, long maxMemory, String policy) {
        this.offheap = offheap;
        this.onheap = onheap;
        this.maxMemory = maxMemory;
        this.policy = policy;
    }

    /**
     * 尝试驱逐以满足 requiredSize。database 为当前操作的 db。
     *
     * @param database       当前 db 索引
     * @param usedMemoryNow  当前已用内存（两引擎合计）
     * @param requiredSize   本次写操作需要的新增字节数
     * @return true 若驱逐后腾出足够空间（或原本就够）；false 若策略下无法驱逐
     *         （noeviction 或 volatile-* 无候选）
     */
    public boolean tryEvictMemory(int database, long usedMemoryNow, long requiredSize) {
        if (DefaultMemoryStore.POLICY_NOEVICTION.equals(policy)) {
            return false;
        }
        long used = usedMemoryNow;
        if (used + requiredSize <= maxMemory) {
            return true;
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<EvictionCandidate> cands = new ArrayList<>(SAMPLE_SIZE);
            cands.addAll(offheap.sampleForEviction(database, policy, SAMPLE_SIZE / 2));
            cands.addAll(onheap.sampleForEviction(database, policy, SAMPLE_SIZE / 2));
            if (cands.isEmpty()) {
                return false; // volatile-* 无候选
            }

            sortByPolicy(cands);

            boolean anyEvicted = false;
            for (EvictionCandidate c : cands) {
                StoreEngine target = "offheap".equals(c.engineId) ? offheap : onheap;
                long freed = target.evict(c.database, c.key);
                if (freed > 0) {
                    used -= freed;
                    anyEvicted = true;
                }
                if (used + requiredSize <= maxMemory) {
                    break;
                }
            }
            if (!anyEvicted) {
                return false;
            }
            if (used + requiredSize <= maxMemory) {
                return true;
            }
        }
        return false;
    }

    private void sortByPolicy(List<EvictionCandidate> cands) {
        if (DefaultMemoryStore.POLICY_ALLKEYS_LRU.equals(policy)
                || DefaultMemoryStore.POLICY_VOLATILE_LRU.equals(policy)) {
            cands.sort(Comparator.comparingLong(c -> c.lastAccessTime)); // idle 越久越优先
        } else if (DefaultMemoryStore.POLICY_VOLATILE_TTL.equals(policy)) {
            cands.sort(Comparator.comparingLong(c -> c.expireTime)); // 剩余寿命小者优先
        }
        // random 不排序
    }
}
