package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InfoUsedMemoryTest {
    private CommonCommandHandler common;
    private MemoryStore store;
    private int db;

    @Before
    public void setUp() {
        common = new CommonCommandHandler();
        store = new DefaultMemoryStore();
        db = 0;
    }

    @Test
    public void testUsedMemoryReflectsSetAndFlushdb() {
        String before = common.handle(db, new String[]{"INFO"}, store).toString();
        long usedBefore = extractLong(before, "used_memory:");
        store.set(db, "k1", "v1");
        store.set(db, "k2", "v2");
        String afterSet = common.handle(db, new String[]{"INFO"}, store).toString();
        long usedAfter = extractLong(afterSet, "used_memory:");
        assertTrue(usedAfter > usedBefore);
        common.handle(db, new String[]{"FLUSHDB"}, store);
        String afterFlush = common.handle(db, new String[]{"INFO"}, store).toString();
        long usedAfterFlush = extractLong(afterFlush, "used_memory:");
        assertTrue(usedAfterFlush <= usedAfter);
    }

    private long extractLong(String info, String key) {
        int idx = info.indexOf(key);
        if (idx < 0) return 0;
        int end = info.indexOf("\r\n", idx);
        if (end < 0) end = info.length();
        String line = info.substring(idx + key.length(), end).trim();
        try { return Long.parseLong(line); } catch (Exception e) { return 0; }
    }
}
