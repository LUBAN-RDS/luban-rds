package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class OomErrorRepliesTest {
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
        common.handle(db, new String[]{"CONFIG SET","maxmemory","1"}, store);
        common.handle(db, new String[]{"CONFIG SET","maxmemory-policy","noeviction"}, store);
        common.handle(db, new String[]{"CONFIG RESETSTAT"}, store);
    }

    @Test
    public void testOomCountsInInfo() {
        String before = common.handle(db, new String[]{"INFO"}, store).toString();
        int totalBefore = extractInt(before, "total_error_replies:");
        int oomBefore = extractInt(before, "oom_error_replies:");
        Object resp = strings.handle(db, new String[]{"SET","k","v"}, store);
        assertTrue(resp.toString().startsWith("-OOM"));
        String after = common.handle(db, new String[]{"INFO"}, store).toString();
        int totalAfter = extractInt(after, "total_error_replies:");
        int oomAfter = extractInt(after, "oom_error_replies:");
        assertTrue(totalAfter > totalBefore);
        assertTrue(oomAfter > oomBefore);
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
