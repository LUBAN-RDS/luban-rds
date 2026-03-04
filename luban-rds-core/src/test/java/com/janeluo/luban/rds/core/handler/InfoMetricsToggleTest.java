package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InfoMetricsToggleTest {
    private CommonCommandHandler common;
    private StringCommandHandler strings;
    private MemoryStore store;
    private int db;

    @Before
    public void setUp() {
        common = new CommonCommandHandler();
        strings = new StringCommandHandler();
        store = new DefaultMemoryStore();
        db = 0;
    }

    @Test
    public void testDisableMetricsStopsCounting() {
        Object ok = common.handle(db, new String[]{"CONFIG SET","metrics-enabled","0"}, store);
        assertEquals("+OK\r\n", ok);
        String before = common.handle(db, new String[]{"INFO"}, store).toString();
        int hitsBefore = extractInt(before, "keyspace_hits:");
        int missesBefore = extractInt(before, "keyspace_misses:");
        strings.handle(db, new String[]{"GET","missingKey"}, store);
        store.set(db, "a", "1");
        strings.handle(db, new String[]{"GET","a"}, store);
        Object info = common.handle(db, new String[]{"INFO"}, store);
        String s = info.toString();
        assertTrue(s.contains("metrics_enabled:0"));
        int hitsAfter = extractInt(s, "keyspace_hits:");
        int missesAfter = extractInt(s, "keyspace_misses:");
        assertEquals(hitsBefore, hitsAfter);
        assertEquals(missesBefore, missesAfter);
    }

    @Test
    public void testEnableMetricsResumesCounting() {
        common.handle(db, new String[]{"CONFIG SET","metrics-enabled","0"}, store);
        common.handle(db, new String[]{"CONFIG SET","metrics-enabled","1"}, store);
        String before = common.handle(db, new String[]{"INFO"}, store).toString();
        int hitsBefore = extractInt(before, "keyspace_hits:");
        int missesBefore = extractInt(before, "keyspace_misses:");
        strings.handle(db, new String[]{"GET","missingKey2"}, store);
        store.set(db, "b", "1");
        strings.handle(db, new String[]{"GET","b"}, store);
        Object info = common.handle(db, new String[]{"INFO"}, store);
        String s = info.toString();
        assertTrue(s.contains("metrics_enabled:1"));
        int hitsAfter = extractInt(s, "keyspace_hits:");
        int missesAfter = extractInt(s, "keyspace_misses:");
        assertTrue(hitsAfter > hitsBefore || missesAfter > missesBefore);
    }

    private int extractInt(String info, String key) {
        int idx = info.indexOf(key);
        if (idx < 0) return 0;
        int end = info.indexOf("\r\n", idx);
        if (end < 0) end = info.length();
        String line = info.substring(idx + key.length(), end).trim();
        try { return Integer.parseInt(line); } catch (Exception e) { return 0; }
    }
}
