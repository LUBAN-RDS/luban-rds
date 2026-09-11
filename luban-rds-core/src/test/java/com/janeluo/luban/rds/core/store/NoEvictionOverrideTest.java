package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P1-6（2026-09-11 mesh 审计）：强制 noeviction 覆盖——mesh 模式确定性收口。
 * 置位后：非 noeviction 策略设置被拒绝；内存压力下淘汰路径短路（不淘汰任何键）。
 */
class NoEvictionOverrideTest {

    private static long usedMemoryOf(DefaultMemoryStore store) throws Exception {
        Field f = DefaultMemoryStore.class.getDeclaredField("usedMemory");
        f.setAccessible(true);
        return ((AtomicLong) f.get(store)).get();
    }

    private static boolean tryEvict(DefaultMemoryStore store, long required) throws Exception {
        java.lang.reflect.Method m = DefaultMemoryStore.class
                .getDeclaredMethod("tryEvictMemory", long.class);
        m.setAccessible(true);
        return (boolean) m.invoke(store, required);
    }

    @Test
    void overrideRejectsPolicySwitch() {
        DefaultMemoryStore store = new DefaultMemoryStore();
        store.setMaxMemoryPolicy(DefaultMemoryStore.POLICY_ALLKEYS_LRU);
        assertEquals(DefaultMemoryStore.POLICY_ALLKEYS_LRU, store.getMaxMemoryPolicy());

        store.setNoEvictionOverride(true);
        store.setMaxMemoryPolicy(DefaultMemoryStore.POLICY_ALLKEYS_RANDOM);
        assertEquals(DefaultMemoryStore.POLICY_ALLKEYS_LRU, store.getMaxMemoryPolicy(),
                "override 生效期间应拒绝策略切换");
        assertTrue(store.isNoEvictionOverride());
    }

    @Test
    void overrideShortCircuitsEvictionUnderPressure() throws Exception {
        DefaultMemoryStore store = new DefaultMemoryStore(16, 100_000_000L,
                DefaultMemoryStore.POLICY_ALLKEYS_LRU);
        store.setNoEvictionOverride(true);
        store.set(0, "k1", "v1");

        // 构造必然的压力：required 使 used + required > maxMemory
        long required = 100_000_000L - usedMemoryOf(store) + 1;
        boolean evicted = tryEvict(store, required);
        assertFalse(evicted, "override + 内存压力下淘汰路径必须短路返回 false");
        assertEquals("v1", store.get(0, "k1"), "override 下不得淘汰任何键");
    }

    @Test
    void withoutOverrideEvictionEvictsCandidateUnderPressure() throws Exception {
        DefaultMemoryStore store = new DefaultMemoryStore(16, 100_000_000L,
                DefaultMemoryStore.POLICY_ALLKEYS_LRU);
        store.set(0, "k1", "v1");
        store.set(0, "k2", "v2");

        long required = 100_000_000L - usedMemoryOf(store) + 1;
        boolean evicted = tryEvict(store, required);
        assertTrue(evicted, "无 override 时 allkeys-lru 应淘汰候选键满足压力");
        assertTrue(store.get(0, "k1") == null || store.get(0, "k2") == null,
                "候选键应被淘汰");
    }
}
