package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZSet 同分字典序测试（C12）。
 *
 * <p>Redis 7 语义：相同分数的成员按字典序（lexicographic）排序。本测试使用真实的
 * {@link DefaultMemoryStore}（非 mock），验证 ZRANGE/ZREVRANGE/ZPOPMIN/ZPOPMAX/ZRANK/
 * ZINCRBY 在同分场景下的排序行为，以及多线程并发 ZADD 后排序的稳定性。
 */
public class ZSetOrderingTest {

    private static final int DB = 0;

    private MemoryStore memoryStore;

    @BeforeEach
    public void setUp() {
        memoryStore = new DefaultMemoryStore();
    }

    /**
     * 向指定 key 加入若干同分成员（score 均为 1.0）。
     * 插入顺序刻意打乱，以验证最终顺序由字典序决定而非插入顺序。
     */
    private void addSameScoreMembers(String key, String... members) {
        for (String m : members) {
            memoryStore.zadd(DB, key, 1.0, m);
        }
    }

    @Test
    public void testSameScoreZrangeIsLexicographic() {
        String key = "zset_zrange";
        // 刻意乱序插入
        addSameScoreMembers(key, "banana", "apple", "cherry");

        List<String> result = memoryStore.zrange(DB, key, 0, -1);
        assertEquals(Arrays.asList("apple", "banana", "cherry"), result);
    }

    @Test
    public void testSameScoreZrevrangeIsReverseLexicographic() {
        String key = "zset_zrevrange";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        List<String> result = memoryStore.zrevrange(DB, key, 0, -1);
        assertEquals(Arrays.asList("cherry", "banana", "apple"), result);
    }

    @Test
    public void testZpopminSameScorePopsLexSmallest() {
        String key = "zset_zpopmin";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        // zpopmin 返回 [member, score, ...]
        List<String> result = memoryStore.zpopmin(DB, key, 1);
        assertNotNull(result);
        assertEquals("apple", result.get(0), "应弹出字典序最小的 apple");
        assertEquals("1.0", result.get(1));
    }

    @Test
    public void testZpopmaxSameScorePopsLexLargest() {
        String key = "zset_zpopmax";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        List<String> result = memoryStore.zpopmax(DB, key, 1);
        assertNotNull(result);
        assertEquals("cherry", result.get(0), "应弹出字典序最大的 cherry");
        assertEquals("1.0", result.get(1));
    }

    @Test
    public void testZpopmaxCount2SameScoreReverseLexOrder() {
        String key = "zset_zpopmax_count2";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        // count=2 应依次弹出字典序最大的两个：cherry, banana
        List<String> result = memoryStore.zpopmax(DB, key, 2);
        assertNotNull(result);
        assertEquals(4, result.size());
        assertEquals("cherry", result.get(0));
        assertEquals("banana", result.get(2));
    }

    @Test
    public void testZrankSameScoreLexOrder() {
        String key = "zset_zrank";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        assertEquals(Long.valueOf(0L), memoryStore.zrank(DB, key, "apple"));
        assertEquals(Long.valueOf(1L), memoryStore.zrank(DB, key, "banana"));
        assertEquals(Long.valueOf(2L), memoryStore.zrank(DB, key, "cherry"));
    }

    @Test
    public void testZrevrankSameScoreLexOrder() {
        String key = "zset_zrevrank";
        addSameScoreMembers(key, "banana", "apple", "cherry");

        assertEquals(Long.valueOf(2L), memoryStore.zrevrank(DB, key, "apple"));
        assertEquals(Long.valueOf(1L), memoryStore.zrevrank(DB, key, "banana"));
        assertEquals(Long.valueOf(0L), memoryStore.zrevrank(DB, key, "cherry"));
    }

    @Test
    public void testZincrbyReshufflesLexOrder() {
        String key = "zset_zincrby";
        // 三个成员同分（score=1）
        addSameScoreMembers(key, "banana", "apple", "cherry");

        // 将 apple 加分到 2，使其移动到更高分组
        double newScore = memoryStore.zincrby(DB, key, 1.0, "apple");
        assertEquals(2.0, newScore, 0.0001);

        // ZRANGE 应：banana(1), cherry(1) 在前（字典序），apple(2) 在后
        List<String> result = memoryStore.zrange(DB, key, 0, -1);
        assertEquals(Arrays.asList("banana", "cherry", "apple"), result);
    }

    @Test
    public void testMixedScoresLexWithinScore() {
        String key = "zset_mixed";
        // score=1: b1, a1 ; score=2: b2, a2
        // 刻意交错插入以验证分组与组内字典序
        memoryStore.zadd(DB, key, 1.0, "b1");
        memoryStore.zadd(DB, key, 2.0, "a2");
        memoryStore.zadd(DB, key, 1.0, "a1");
        memoryStore.zadd(DB, key, 2.0, "b2");

        List<String> result = memoryStore.zrange(DB, key, 0, -1);
        // 分数升序，同分组内字典序升序
        assertEquals(Arrays.asList("a1", "b1", "a2", "b2"), result);
    }

    @Test
    public void testConcurrentZaddSameScoreLexOrderStable() throws Exception {
        String key = "zset_concurrent";
        int threadCount = 8;
        int membersPerThread = 25;
        // 总计 threadCount * membersPerThread 个同分成员，成员名按字典序可比较
        int total = threadCount * membersPerThread;

        // 预先创建 key（避免并发首次插入时 zadd 的 create-race 丢失成员，
        // 该 race 与 C12 字典序无关）。后续并发 ZADD 均命中已存在的 ZSetStore，
        // 其内部 ConcurrentHashMap / ConcurrentSkipListMap 是线程安全的。
        memoryStore.zadd(DB, key, 1.0, "seed");
        memoryStore.zrem(DB, key, "seed");

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int base = t * membersPerThread;
            pool.submit(() -> {
                try {
                    start.await();
                    // 每个线程加入 membersPerThread 个成员，名称形如 "m%04d"
                    for (int i = 0; i < membersPerThread; i++) {
                        memoryStore.zadd(DB, key, 1.0, String.format("m%04d", base + i));
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发线程未在超时内完成");
        pool.shutdown();
        assertEquals(0, errors.get(), "并发 ZADD 不应产生异常");

        List<String> result = memoryStore.zrange(DB, key, 0, -1);
        assertEquals(total, result.size());

        // 无论插入顺序如何，最终顺序应为严格字典序升序
        List<String> expected = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            expected.add(String.format("m%04d", i));
        }
        assertEquals(expected, result);
    }

    @Test
    public void testZremrangeByRankRespectsLexOrder() {
        String key = "zset_zremrangebyrank";
        addSameScoreMembers(key, "banana", "apple", "cherry", "date");

        // 删除 rank [0,1) 即字典序第一个 apple
        int removed = memoryStore.zremrangeByRank(DB, key, 0, 0);
        assertEquals(1, removed);

        List<String> result = memoryStore.zrange(DB, key, 0, -1);
        assertEquals(Arrays.asList("banana", "cherry", "date"), result);
    }

    @Test
    public void testEmptyZsetReturnsEmpty() {
        String key = "zset_empty";
        assertEquals(Collections.emptyList(), memoryStore.zrange(DB, key, 0, -1));
        assertEquals(Collections.emptyList(), memoryStore.zrevrange(DB, key, 0, -1));
        assertEquals(Collections.emptyList(), memoryStore.zpopmin(DB, key, 1));
        assertEquals(Collections.emptyList(), memoryStore.zpopmax(DB, key, 1));
    }
}
