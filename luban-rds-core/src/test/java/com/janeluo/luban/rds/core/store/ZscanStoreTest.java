package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ZscanStoreTest {
    @Test
    void zscanFullIterationNoLossNoDup() {
        DefaultMemoryStore store = new DefaultMemoryStore(16, -1, "noeviction");
        for (int i = 0; i < 20; i++) {
            store.zadd(0, "z", i / 2.0, "m" + String.format("%02d", i));
        }
        List<String> collected = new ArrayList<>();
        String cursor = "0";
        int pages = 0;
        do {
            List<Object> page = store.zscan(0, "z", cursor, "*", 10);
            cursor = (String) page.get(0);
            for (int i = 1; i < page.size(); i += 2) collected.add((String) page.get(i));
            pages++;
            assertTrue(pages < 10, "must terminate");
        } while (!"0".equals(cursor));
        assertEquals(20, collected.size());
        assertEquals(20, new HashSet<>(collected).size());
    }

    @Test
    void zscanScoreFormatting() {
        DefaultMemoryStore store = new DefaultMemoryStore(16, -1, "noeviction");
        store.zadd(0, "z", 1.0, "a");
        store.zadd(0, "z", 1.5, "b");
        store.zadd(0, "z", -0.0, "c");
        List<Object> page = store.zscan(0, "z", "0", "*", 10);
        // 顺序 (score, member): -0.0:c, 1.0:a, 1.5:b
        List<String> scores = new ArrayList<>();
        for (int i = 2; i < page.size(); i += 2) scores.add((String) page.get(i));
        assertEquals(List.of("0", "1", "1.5"), scores);
        assertEquals("0", page.get(0)); // 一次页内遍历完
    }

    @Test
    void zscanSameScoreMembersLexicographic() {
        DefaultMemoryStore store = new DefaultMemoryStore(16, -1, "noeviction");
        store.zadd(0, "z", 5.0, "b");
        store.zadd(0, "z", 5.0, "a");
        store.zadd(0, "z", 5.0, "c");
        List<Object> page = store.zscan(0, "z", "0", "*", 10);
        List<String> members = new ArrayList<>();
        for (int i = 1; i < page.size(); i += 2) members.add((String) page.get(i));
        assertEquals(List.of("a", "b", "c"), members); // 同分字典序
    }

    @Test
    void zscanMissingKeyAndWrongType() {
        DefaultMemoryStore store = new DefaultMemoryStore(16, -1, "noeviction");
        assertEquals(List.of("0"), store.zscan(0, "nope", "0", "*", 10));
        store.set(0, "str", "v");
        assertEquals(List.of("0"), store.zscan(0, "str", "0", "*", 10));
    }
}