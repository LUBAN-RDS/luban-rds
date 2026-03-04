package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class SoftMaxMemoryWarningTest {
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
        common.handle(db, new String[]{"CONFIG SET","maxmemory","1024"}, store);
        common.handle(db, new String[]{"CONFIG SET","softmaxmemory-threshold","1"}, store);
    }

    @Test
    public void testSoftLimitWarningInInfo() {
        String before = common.handle(db, new String[]{"INFO"}, store).toString();
        Object resp = strings.handle(db, new String[]{"SET","k","value"}, store);
        assertEquals("+OK\r\n", resp);
        String after = common.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(after.contains("softmaxmemory_threshold_percent:1"));
        assertTrue(after.contains("softmaxmemory_warning:1"));
    }
}
