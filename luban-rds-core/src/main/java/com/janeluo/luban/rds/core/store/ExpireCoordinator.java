package com.janeluo.luban.rds.core.store;

/**
 * 过期协调器（DD-4 Y2）：每周期固定预算按两引擎 key 量比例分配。
 *
 * <p>遍历所有 db，对每个 db 按两引擎当前 key 数比例把 100 的预算分给各自，
 * 调用 {@link StoreEngine#expireBatch} 删除到期 key。
 */
public class ExpireCoordinator {

    private static final int BUDGET = 100;

    private final StoreEngine offheap;
    private final StoreEngine onheap;
    private final int maxDatabases;

    public ExpireCoordinator(StoreEngine offheap, StoreEngine onheap, int maxDatabases) {
        this.offheap = offheap;
        this.onheap = onheap;
        this.maxDatabases = maxDatabases;
    }

    /** 每周期调用：遍历所有 db，按比例分配过期预算。返回总删除数。 */
    public int runCycle() {
        int total = 0;
        for (int db = 0; db < maxDatabases; db++) {
            long oh = offheap.size(db);
            long nh = onheap.size(db);
            long sum = oh + nh;
            if (sum == 0) {
                continue;
            }
            int ohBudget = (int) (BUDGET * oh / sum);
            int nhBudget = BUDGET - ohBudget;
            total += offheap.expireBatch(db, ohBudget);
            total += onheap.expireBatch(db, nhBudget);
        }
        return total;
    }
}
