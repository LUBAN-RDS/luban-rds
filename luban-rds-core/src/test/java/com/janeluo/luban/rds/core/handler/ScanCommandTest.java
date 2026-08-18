package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.HybridMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import com.janeluo.luban.rds.core.store.RedisDoubleFormatter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * SCAN / HSCAN / SSCAN / ZSCAN 命令族专项测试（JUnit 5，可被 surefire 运行）。
 *
 * <p>覆盖：全量遍历无丢失无重复、游标原样回传、MATCH glob、TYPE 过滤、COUNT 边界、
 * 并发写入下的弱一致遍历、缺少键/错误类型/非法游标/未知选项等错误分支、
 * zset 的 (score, member) 排序与 Redis 兼容 score 输出、hybrid 模式（堆外+堆上）遍历。
 *
 * <p>命令全部通过 {@link CommandHandler} 层驱动，响应按 RESP 字符串解析，断言真实行为。
 */
class ScanCommandTest {

    private static final int DB = 0;
    private static final String DONE = "0";

    private final CommonCommandHandler common = new CommonCommandHandler();
    private final HashCommandHandler hash = new HashCommandHandler();
    private final SetCommandHandler set = new SetCommandHandler();
    private final ZSetCommandHandler zset = new ZSetCommandHandler();

    // ==================== A. SCAN 基础 ====================

    @Test
    void scanEmptyDb() {
        DefaultMemoryStore store = newStore();
        Object resp = common.handle(DB, new String[]{"SCAN", "0"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(DONE, reply.get(0), "空库应从 0 直接返回完成游标");
        assertTrue(elements(reply).isEmpty(), "空库不应返回任何键");
    }

    @Test
    void scanFullIterationNoLossNoDup() {
        DefaultMemoryStore store = newStore();
        int total = 200;
        for (int i = 0; i < total; i++) {
            store.set(DB, "key" + String.format("%03d", i), "v");
        }
        List<String> collected = iterateAll(common, store, "SCAN", null, 37, 100);
        assertEquals(total, collected.size(), "COUNT 37（非约数）应把全部键收集到，无丢失无重复");
        assertEquals(new HashSet<>(collected).size(), collected.size(), "遍历结果存在重复");
        for (int i = 0; i < total; i++) {
            assertTrue(collected.contains("key" + String.format("%03d", i)), "缺失键 key" + i);
        }
    }

    @Test
    void scanCursorRoundTrip() {
        DefaultMemoryStore store = newStore();
        for (int i = 0; i < 50; i++) {
            store.set(DB, "key" + String.format("%03d", i), "v");
        }
        List<String> seen = new ArrayList<>();
        String cursor = "0";
        String previous = null;
        for (int page = 0; page < 20; page++) {
            Object resp = common.handle(DB, new String[]{"SCAN", cursor, "COUNT", "17"}, store);
            List<Object> reply = parsedArray((String) resp);
            String next = (String) reply.get(0);
            for (Object o : elements(reply)) {
                seen.add((String) o);
            }
            if (DONE.equals(next)) {
                break;
            }
            assertTrue(next.startsWith("c:"), "续游标应为不透明 c: 串: " + next);
            assertNotEquals(previous, next, "游标必须严格前进");
            previous = cursor;
            cursor = next; // 原样回传第 2 页游标
        }
        assertEquals(50, new HashSet<>(seen).size(), "游标原样回传后应能遍历全部键且无重复");
    }

    @Test
    void scanWithConcurrentWrites() throws InterruptedException {
        DefaultMemoryStore store = newStore();
        int originals = 500;
        for (int i = 0; i < originals; i++) {
            store.set(DB, "key" + String.format("%04d", i), "v");
        }
        // 并发写线程持续插入新键（键名前缀 w 字典序大于 key，位于游标前进方向之后）
        AtomicBoolean stopped = new AtomicBoolean(false);
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 1000 && !stopped.get(); i++) {
                store.set(DB, "w" + String.format("%04d", i), "v");
            }
        }, "scan-writer");
        writer.start();
        List<String> collected = new ArrayList<>();
        String cursor = "0";
        int pages = 0;
        while (true) {
            assertTrue(pages++ < 3000, "并发写场景下遍历未终止");
            Object resp = common.handle(DB, new String[]{"SCAN", cursor, "COUNT", "37"}, store);
            List<Object> reply = parsedArray((String) resp);
            String next = (String) reply.get(0);
            for (Object o : elements(reply)) {
                collected.add((String) o);
            }
            if (DONE.equals(next)) {
                break;
            }
            cursor = next;
        }
        stopped.set(true);
        writer.join(5000);
        Set<String> got = new HashSet<>(collected);
        for (int i = 0; i < originals; i++) {
            assertTrue(got.contains("key" + String.format("%04d", i)), "遍历期间始终存在的原始键丢失: key" + i);
        }
    }

    // ==================== B. SCAN 选项 ====================

    @Test
    void scanMatchGlob() {
        DefaultMemoryStore store = newStore();
        for (String k : Arrays.asList("user:1", "user:2", "order:1", "a.b", "axb", "k1", "k2", "ka")) {
            store.set(DB, k, "v");
        }
        assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")),
                new HashSet<>(pageKeys(common, store, "SCAN", "0", "MATCH", "user:*")));
        assertEquals(new HashSet<>(Collections.singletonList("a.b")),
                new HashSet<>(pageKeys(common, store, "SCAN", "0", "MATCH", "a.b")), "模式 a.b 应只匹配字面点，不含 axb");
        assertEquals(new HashSet<>(Arrays.asList("k1", "k2")),
                new HashSet<>(pageKeys(common, store, "SCAN", "0", "MATCH", "k[12]")));
    }

    @Test
    void scanTypeFilter() {
        DefaultMemoryStore store = newStore();
        store.set(DB, "s1", "str");
        store.set(DB, "s2", "str");
        store.hset(DB, "h1", "f", "v");
        store.hset(DB, "h2", "f", "v");
        store.zadd(DB, "z1", 1.5, "m");
        List<String> hashKeys = iterateType(common, store, "hash", 10, 100);
        assertEquals(new HashSet<>(Arrays.asList("h1", "h2")), new HashSet<>(hashKeys), "TYPE hash 应只返回 hash 键");
        List<String> zsetKeys = iterateType(common, store, "zset", 10, 100);
        assertEquals(new HashSet<>(Collections.singletonList("z1")), new HashSet<>(zsetKeys), "TYPE zset 应只返回 zset 键");
        List<String> noSuch = iterateType(common, store, "nosuch", 10, 100);
        assertTrue(noSuch.isEmpty(), "未知 TYPE 应静默过滤掉全部键");
    }

    @Test
    void scanCountBounds() {
        DefaultMemoryStore store = newStore();
        for (int i = 0; i < 200; i++) {
            store.set(DB, "key" + String.format("%03d", i), "v");
        }
        // COUNT 5：每页返回键数不超过 5
        String cursor = "0";
        int pages = 0;
        while (true) {
            assertTrue(pages++ < 400, "COUNT 5 遍历未终止");
            Object resp = common.handle(DB, new String[]{"SCAN", cursor, "COUNT", "5"}, store);
            List<Object> reply = parsedArray((String) resp);
            List<Object> page = elements(reply);
            assertTrue(page.size() <= 5, "COUNT 5 单页不应超过 5 个键，实际 " + page.size());
            cursor = (String) reply.get(0);
            if (DONE.equals(cursor)) {
                break;
            }
        }
        // COUNT 1000：单页返回全部 200 键并直接完成
        List<String> one = pageKeys(common, store, "SCAN", "0", "COUNT", "1000");
        assertEquals(200, one.size(), "大 COUNT 应单页返回全部键");
    }

    @Test
    void scanErrors() {
        DefaultMemoryStore store = newStore();
        Object resp;
        resp = common.handle(DB, new String[]{"SCAN"}, store);
        assertEquals("-ERR wrong number of arguments for 'scan' command\r\n", resp, "SCAN 缺参");
        resp = common.handle(DB, new String[]{"SCAN", "abc"}, store);
        assertEquals("-ERR invalid cursor\r\n", resp, "非法游标");
        resp = common.handle(DB, new String[]{"SCAN", "0", "FOOBAR"}, store);
        assertEquals("-ERR syntax error\r\n", resp, "未知选项");
        resp = common.handle(DB, new String[]{"SCAN", "0", "COUNT", "0"}, store);
        assertEquals("-ERR syntax error\r\n", resp, "COUNT 0");
        resp = common.handle(DB, new String[]{"SCAN", "0", "COUNT", "-1"}, store);
        assertEquals("-ERR syntax error\r\n", resp, "COUNT -1");
        resp = common.handle(DB, new String[]{"SCAN", "0", "COUNT", "abc"}, store);
        assertEquals("-ERR value is not an integer or out of range\r\n", resp, "COUNT 非整数");
    }

    // ==================== C. HSCAN ====================

    @Test
    void hscanFullIteration() {
        DefaultMemoryStore store = newStore();
        Map<String, String> expected = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            String f = "f" + String.format("%02d", i);
            expected.put(f, "v" + i);
            store.hset(DB, "h", f, "v" + i);
        }
        List<String> flat = iterateAll(hash, store, "HSCAN", "h", 17, 200);
        assertEquals(expected.size() * 2, flat.size(), "HSCAN 应无丢失无重复地收集全部字段和值");
        Map<String, String> got = new HashMap<>();
        for (int i = 0; i < flat.size(); i += 2) {
            got.put(flat.get(i), flat.get(i + 1));
        }
        assertEquals(expected, got, "HSCAN 收集的字段值对应与插入一致");
    }

    @Test
    void hscanMatchAndOptionals() {
        DefaultMemoryStore store = newStore();
        store.hset(DB, "h", "user:1", "a");
        store.hset(DB, "h", "user:2", "b");
        store.hset(DB, "h", "order:1", "c");
        Object resp = hash.handle(DB, new String[]{"HSCAN", "h", "0", "MATCH", "user:*", "COUNT", "100"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(new HashSet<>(Arrays.asList("user:1", "a", "user:2", "b")),
                new HashSet<>(strings(elements(reply))), "MATCH user:* 应只返回 user 字段对");
        resp = hash.handle(DB, new String[]{"HSCAN", "h", "0", "FOOBAR"}, store);
        assertEquals("-ERR syntax error\r\n", resp, "HSCAN 未知选项");
    }

    @Test
    void hscanMissingKey() {
        DefaultMemoryStore store = newStore();
        Object resp = hash.handle(DB, new String[]{"HSCAN", "missing", "0", "COUNT", "10"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(DONE, reply.get(0), "缺键 HSCAN 应返回完成游标 0");
        assertTrue(elements(reply).isEmpty(), "缺键 HSCAN 应返回空数组");
    }

    @Test
    void hscanWrongType() {
        DefaultMemoryStore store = newStore();
        store.set(DB, "str", "plain");
        Object resp = hash.handle(DB, new String[]{"HSCAN", "str", "0"}, store);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", resp, "对 string 键 HSCAN 应返回 WRONGTYPE");
    }

    @Test
    void hscanInvalidCursor() {
        DefaultMemoryStore store = newStore();
        store.hset(DB, "h", "f", "v");
        Object resp = hash.handle(DB, new String[]{"HSCAN", "h", "abc"}, store);
        assertEquals("-ERR invalid cursor\r\n", resp, "HSCAN 非法游标");
    }

    @Test
    void hscanDeterministicOrder() {
        DefaultMemoryStore store = newStore();
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            fields.add("f" + String.format("%02d", i));
        }
        Collections.shuffle(fields, new java.util.Random(42)); // 固定种子乱序插入
        for (String f : fields) {
            store.hset(DB, "h", f, "v");
        }
        List<String> run1 = fieldSequence(iterateAll(hash, store, "HSCAN", "h", 13, 200));
        List<String> run2 = fieldSequence(iterateAll(hash, store, "HSCAN", "h", 13, 200));
        assertEquals(run1, run2, "两次全量 HSCAN 应返回完全一致的字段序列");
        assertEquals(fields.stream().sorted().collect(Collectors.toList()), run1, "HSCAN 字段应按字典序输出");
    }

    private static List<String> fieldSequence(List<String> flat) {
        List<String> fields = new ArrayList<>();
        for (int i = 0; i < flat.size(); i += 2) {
            fields.add(flat.get(i));
        }
        return fields;
    }

    // ==================== D. SSCAN ====================

    @Test
    void sscanFullIteration() {
        DefaultMemoryStore store = newStore();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String m = "m" + String.format("%03d", i);
            expected.add(m);
            store.sadd(DB, "s", m);
        }
        List<String> got = iterateAll(set, store, "SSCAN", "s", 17, 200);
        assertEquals(expected.size(), got.size(), "SSCAN 应无丢失无重复");
        assertEquals(expected, new HashSet<>(got), "SSCAN 收集的成员应与插入一致");
    }

    @Test
    void sscanMatchAndErrors() {
        DefaultMemoryStore store = newStore();
        store.sadd(DB, "s", "user:1", "user:2", "order:1");
        Object resp = set.handle(DB, new String[]{"SSCAN", "s", "0", "MATCH", "user:*", "COUNT", "100"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(new HashSet<>(Arrays.asList("user:1", "user:2")),
                new HashSet<>(strings(elements(reply))), "SSCAN MATCH user:*");
        resp = set.handle(DB, new String[]{"SSCAN", "s", "0", "FOOBAR"}, store);
        assertEquals("-ERR syntax error\r\n", resp, "SSCAN 未知选项");
    }

    @Test
    void sscanMissingKeyWrongTypeInvalidCursor() {
        DefaultMemoryStore store = newStore();
        Object resp = set.handle(DB, new String[]{"SSCAN", "missing", "0"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(DONE, reply.get(0), "缺键 SSCAN 应返回完成游标");
        assertTrue(elements(reply).isEmpty(), "缺键 SSCAN 应返回空数组");
        store.set(DB, "str", "plain");
        resp = set.handle(DB, new String[]{"SSCAN", "str", "0"}, store);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", resp, "对 string 键 SSCAN 应返回 WRONGTYPE");
        store.sadd(DB, "s", "m");
        resp = set.handle(DB, new String[]{"SSCAN", "s", "abc"}, store);
        assertEquals("-ERR invalid cursor\r\n", resp, "SSCAN 非法游标");
    }

    @Test
    void sscanDeterministicOrder() {
        DefaultMemoryStore store = newStore();
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            members.add("m" + String.format("%03d", i));
        }
        Collections.shuffle(members, new java.util.Random(7)); // 固定种子乱序插入
        for (String m : members) {
            store.sadd(DB, "s", m);
        }
        List<String> run1 = iterateAll(set, store, "SSCAN", "s", 13, 200);
        List<String> run2 = iterateAll(set, store, "SSCAN", "s", 13, 200);
        assertEquals(run1, run2, "两次全量 SSCAN 应返回完全一致的成员序列");
        assertEquals(members.stream().sorted().collect(Collectors.toList()), run1, "SSCAN 成员应按字典序输出");
    }

    // ==================== E. ZSCAN ====================

    @Test
    void zscanFullIteration() {
        DefaultMemoryStore store = newStore();
        Map<String, Double> scores = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            String m = "m" + String.format("%03d", i);
            double s = i % 5 == 0 ? i * 0.5 : i; // 半数带小数，其余整数
            scores.put(m, s);
            store.zadd(DB, "z", s, m);
        }
        scores.put("big", 1099511627776.0); // 一个大整数（2^40）
        store.zadd(DB, "z", 1099511627776.0, "big");
        List<String> expected = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey()))
                .flatMap(e -> Stream.of(e.getKey(), RedisDoubleFormatter.format(e.getValue())))
                .collect(Collectors.toList());
        List<String> actual = iterateAll(zset, store, "ZSCAN", "z", 17, 200);
        assertEquals(scores.size() * 2, actual.size(), "ZSCAN 应无丢失无重复地收集全部成员与分数");
        assertEquals(expected, actual, "ZSCAN 应按 (score, member) 排序并以 Redis 格式输出分数");
    }

    @Test
    void zscanOrderAndFormat() {
        DefaultMemoryStore store = newStore();
        store.zadd(DB, "z", 5.0, "a");
        store.zadd(DB, "z", 5.0, "b");
        store.zadd(DB, "z", 1.0, "c");
        List<String> flat = iterateAll(zset, store, "ZSCAN", "z", 100, 10);
        // 按 (score, member) 升序：c(1)、a(5)、b(5)；整数分数输出 "1"/"5" 而非 "1.0"/"5.0"
        assertEquals(Arrays.asList("c", "1", "a", "5", "b", "5"), flat, "ZSCAN 排序与分数格式应对齐 Redis");
    }

    @Test
    void zscanMissingKeyWrongTypeInvalidCursor() {
        DefaultMemoryStore store = newStore();
        Object resp = zset.handle(DB, new String[]{"ZSCAN", "missing", "0"}, store);
        List<Object> reply = parsedArray((String) resp);
        assertEquals(DONE, reply.get(0), "缺键 ZSCAN 应返回完成游标");
        assertTrue(elements(reply).isEmpty(), "缺键 ZSCAN 应返回空数组");
        store.set(DB, "str", "plain");
        resp = zset.handle(DB, new String[]{"ZSCAN", "str", "0"}, store);
        assertEquals("-WRONGTYPE Operation against a key holding the wrong kind of value\r\n", resp, "对 string 键 ZSCAN 应返回 WRONGTYPE");
        store.zadd(DB, "z", 1.0, "m");
        resp = zset.handle(DB, new String[]{"ZSCAN", "z", "abc"}, store);
        assertEquals("-ERR invalid cursor\r\n", resp, "ZSCAN 非法游标");
    }

    // ==================== F. Hybrid 模式 ====================

    @Test
    void hybridScanFullIteration() {
        HybridMemoryStore store = new HybridMemoryStore(16, -1, "noeviction", 8);
        int big = 40, small = 30, hashes = 30;
        for (int i = 0; i < big; i++) {
            store.set(DB, "big:" + String.format("%03d", i), "0123456789"); // 14 字节 >= 阈值 8 → 堆外
        }
        for (int i = 0; i < small; i++) {
            store.set(DB, "sml:" + String.format("%03d", i), "x"); // 短串 → 堆上
        }
        for (int i = 0; i < hashes; i++) {
            store.hset(DB, "hsh:" + String.format("%03d", i), "f", "v");
        }
        List<String> collected = iterateAll(common, store, "SCAN", null, 13, 400);
        assertEquals(big + small + hashes, collected.size(), "hybrid 全量遍历应无丢失无重复");
        assertEquals(new HashSet<>(collected).size(), collected.size(), "hybrid 遍历结果存在重复");
        for (int i = 0; i < big; i++) {
            assertTrue(collected.contains("big:" + String.format("%03d", i)), "缺失堆外键 big:" + i);
        }
        for (int i = 0; i < small; i++) {
            assertTrue(collected.contains("sml:" + String.format("%03d", i)), "缺失堆上键 sml:" + i);
        }
        for (int i = 0; i < hashes; i++) {
            assertTrue(collected.contains("hsh:" + String.format("%03d", i)), "缺失 hash 键 hsh:" + i);
        }
    }

    @Test
    void hybridScanTerminatesWithOffheap() {
        HybridMemoryStore store = new HybridMemoryStore(16, -1, "noeviction", 8);
        int n = 40;
        for (int i = 0; i < n; i++) {
            store.set(DB, "big:" + String.format("%03d", i), "0123456789");
        }
        // 仅堆外键：小 COUNT 分页遍历必须终止，且堆外键不会跨页重复
        List<String> collected = iterateAll(common, store, "SCAN", null, 3, 400);
        assertEquals(n, collected.size(), "堆外键遍历应在游标 0 处终止且无丢失");
        assertEquals(new HashSet<>(collected).size(), collected.size(), "堆外键出现跨页重复");
    }

    @Test
    void hybridScanTypeFilter() {
        HybridMemoryStore store = new HybridMemoryStore(16, -1, "noeviction", 8);
        for (int i = 0; i < 20; i++) {
            store.set(DB, "big:" + String.format("%02d", i), "0123456789"); // 堆外 string
            store.set(DB, "sml:" + String.format("%02d", i), "x");         // 堆上 string
            store.hset(DB, "hsh:" + String.format("%02d", i), "f", "v");   // hash
            store.zadd(DB, "zst:" + String.format("%02d", i), i, "m");     // zset
        }
        List<String> strings = iterateType(common, store, "string", 10, 100);
        assertEquals(40, strings.size(), "TYPE string 应返回堆外+堆上全部 string 键");
        for (String k : strings) {
            assertTrue(k.startsWith("big:") || k.startsWith("sml:"), "TYPE string 不应混入非 string 键: " + k);
        }
        List<String> hashes = iterateType(common, store, "hash", 10, 100);
        assertEquals(20, hashes.size(), "TYPE hash 应只返回 hash 键");
        for (String k : hashes) {
            assertTrue(k.startsWith("hsh:"), "TYPE hash 不应混入非 hash 键: " + k);
        }
        assertTrue(iterateType(common, store, "nosuch", 10, 100).isEmpty(), "hybrid 未知 TYPE 应静默过滤");
    }

    // ==================== 工具方法 ====================

    private static DefaultMemoryStore newStore() {
        return new DefaultMemoryStore(16, -1, "noeviction");
    }

    /** 全量遍历 <cmd> 或 <cmd> <key>，COUNT 分页直到游标 "0"，返回收集到的全部元素。 */
    private static List<String> iterateAll(CommandHandler h, MemoryStore store, String cmd, String key,
                                           int count, int maxPages) {
        List<String> all = new ArrayList<>();
        String cursor = "0";
        int pages = 0;
        while (true) {
            assertTrue(pages++ < maxPages, cmd + " 迭代未在 " + maxPages + " 页内终止");
            List<Object> reply = pageReply(h, store, cmd, key, cursor, count);
            String next = (String) reply.get(0);
            for (Object o : elements(reply)) {
                all.add((String) o);
            }
            if (DONE.equals(next)) {
                return all;
            }
            cursor = next; // 续游标原样回传
        }
    }

    /** 按 TYPE 过滤全量遍历 SCAN，返回收集到的全部键。 */
    private static List<String> iterateType(CommandHandler h, MemoryStore store, String type, int count,
                                            int maxPages) {
        List<String> all = new ArrayList<>();
        String cursor = "0";
        int pages = 0;
        while (true) {
            assertTrue(pages++ < maxPages, "SCAN TYPE " + type + " 迭代未终止");
            Object resp = h.handle(DB, new String[]{"SCAN", cursor, "COUNT", String.valueOf(count),
                    "TYPE", type}, store);
            List<Object> reply = parsedArray((String) resp);
            String next = (String) reply.get(0);
            for (Object o : elements(reply)) {
                all.add((String) o);
            }
            if (DONE.equals(next)) {
                return all;
            }
            cursor = next;
        }
    }

    /** 单页 SCAN 命令（支持附加 MATCH / COUNT 等选项），返回页面元素列表。 */
    private static List<String> pageKeys(CommandHandler h, MemoryStore store, String... args) {
        Object resp = h.handle(DB, args, store);
        assertNotNull(resp, "SCAN 不应返回 null");
        List<Object> reply = parsedArray((String) resp);
        return strings(elements(reply));
    }

    private static List<Object> pageReply(CommandHandler h, MemoryStore store, String cmd, String key,
                                          String cursor, int count) {
        Object resp = key == null
                ? h.handle(DB, new String[]{cmd, cursor, "COUNT", String.valueOf(count)}, store)
                : h.handle(DB, new String[]{cmd, key, cursor, "COUNT", String.valueOf(count)}, store);
        assertNotNull(resp, cmd + " 不应返回 null");
        String s = (String) resp;
        assertTrue(s.startsWith("*"), cmd + " 应返回 RESP 数组，实际: " + s);
        return parsedArray(s);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> elements(List<Object> reply) {
        return (List<Object>) reply.get(1);
    }

    private static List<String> strings(List<Object> list) {
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add((String) o);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> parsedArray(String resp) {
        Object o = parseResp(resp);
        assertTrue(o instanceof List, "应以数组开头: " + resp);
        return (List<Object>) o;
    }

    /**
     * 极小 RESP 解析器：数组 → List&lt;Object&gt;（元素为 String 或嵌套 List）；
     * 错误/简单串等非数组 → 原样返回字符串。
     */
    private static Object parseResp(String resp) {
        if (!resp.startsWith("*")) {
            return resp;
        }
        List<Object> out = new ArrayList<>();
        int nl = resp.indexOf("\r\n");
        int n = Integer.parseInt(resp.substring(1, nl));
        int i = nl + 2;
        for (int k = 0; k < n; k++) {
            char c = resp.charAt(i);
            if (c == '$') {
                int nl2 = resp.indexOf("\r\n", i);
                int len = Integer.parseInt(resp.substring(i + 1, nl2));
                i = nl2 + 2;
                out.add(resp.substring(i, i + len));
                i += len + 2;
            } else if (c == ':') {
                int nl2 = resp.indexOf("\r\n", i);
                out.add(resp.substring(i + 1, nl2));
                i = nl2 + 2;
            } else if (c == '*') {
                int nl2 = resp.indexOf("\r\n", i);
                int m = Integer.parseInt(resp.substring(i + 1, nl2));
                i = nl2 + 2;
                List<Object> inner = new ArrayList<>();
                for (int j = 0; j < m; j++) {
                    char cc = resp.charAt(i);
                    if (cc != '$') {
                        fail("嵌套数组元素应为 bulk string，实际: " + cc);
                    }
                    int nl3 = resp.indexOf("\r\n", i);
                    int len = Integer.parseInt(resp.substring(i + 1, nl3));
                    i = nl3 + 2;
                    inner.add(resp.substring(i, i + len));
                    i += len + 2;
                }
                out.add(inner);
            } else {
                fail("无法解析的 RESP 字符: " + c);
            }
        }
        return out;
    }
}