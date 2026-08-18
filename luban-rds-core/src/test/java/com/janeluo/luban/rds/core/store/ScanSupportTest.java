package com.janeluo.luban.rds.core.store;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanSupportTest {
    @Test
    void pageReturnsAllElementsAcrossPages() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 20; i++) keys.add("k" + String.format("%02d", i));
        List<String> collected = new ArrayList<>();
        Iterator<Object> it = new ArrayList<Object>(keys).iterator();
        String cursor = "0";
        int pages = 0;
        do {
            List<Object> page = ScanSupport.page(it, 10, e -> true, e -> List.of((String) e));
            cursor = (String) page.get(0);
            for (int i = 1; i < page.size(); i++) collected.add((String) page.get(i));
            pages++;
        } while (!"0".equals(cursor));
        assertEquals(20, collected.size());
        assertEquals(new HashSet<>(keys), new HashSet<>(collected));
        assertEquals(2, pages); // 10 + 10（末页随键一起返回"0"，无空收尾页）
    }

    @Test
    void pageVisitsExactlyCountElements() {
        List<String> keys = Arrays.asList("a", "b", "c", "d", "e");
        List<Object> page = ScanSupport.page(new ArrayList<Object>(keys).iterator(), 2, e -> "a".equals(e) || "d".equals(e), e -> List.of((String) e));
        assertEquals("c:62", page.get(0)); // 访问 a、b（最后访问 b，hex 62），仅匹配 a
        assertEquals(1, page.size() - 1);
    }

    @Test
    void emptyInputReturnsDone() {
        List<Object> page = ScanSupport.page(Collections.emptyIterator(), 10, e -> true, e -> List.of(e));
        assertEquals("0", page.get(0));
        assertEquals(1, page.size());
    }

    @Test
    void cursorEncodeDecodeRoundTrip() {
        String enc = ScanSupport.encode("0"); // 键名为 "0" 不与终止符冲突
        assertEquals("0", ScanSupport.decodeKey(enc));
        assertTrue(enc.startsWith("c:"));
        assertNull(ScanSupport.decodeKey("0"));
        assertNull(ScanSupport.decodeKey("garbage"));
    }

    @Test
    void pairCursorRoundTrip() {
        String enc = ScanSupport.encodePair(1.5, "m:1");
        assertEquals(1.5, ScanSupport.decodePair(enc)[0]);
        assertEquals("m:1", ScanSupport.decodePair(enc)[1]);
    }

    @Test
    void mergedIteratorMergesTwoSortedStreams() {
        Iterator<String> a = Arrays.asList("a", "c", "e").iterator();
        Iterator<String> b = Arrays.asList("b", "d").iterator();
        List<String> merged = new ArrayList<>();
        ScanSupport.merge(a, b).forEachRemaining(merged::add);
        assertEquals(Arrays.asList("a", "b", "c", "d", "e"), merged);
    }
}
