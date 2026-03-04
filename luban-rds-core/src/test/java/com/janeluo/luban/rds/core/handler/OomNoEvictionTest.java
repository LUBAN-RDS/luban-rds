package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class OomNoEvictionTest {
    private CommonCommandHandler common;
    private StringCommandHandler strings;
    private ListCommandHandler lists;
    private SetCommandHandler sets;
    private ZSetCommandHandler zsets;
    private MemoryStore store;
    private int db;

    @Before
    public void setUp() {
        common = new CommonCommandHandler();
        strings = new StringCommandHandler();
        lists = new ListCommandHandler();
        sets = new SetCommandHandler();
        zsets = new ZSetCommandHandler();
        store = new DefaultMemoryStore();
        db = 0;
        common.handle(db, new String[]{"CONFIG SET","maxmemory","1"}, store);
        common.handle(db, new String[]{"CONFIG SET","maxmemory-policy","noeviction"}, store);
    }

    @Test
    public void testSetReturnsOom() {
        Object resp = strings.handle(db, new String[]{"SET","k","v"}, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", resp);
    }

    @Test
    public void testLpushReturnsOom() {
        Object resp = lists.handle(db, new String[]{"LPUSH","k","v"}, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", resp);
    }

    @Test
    public void testSaddReturnsOom() {
        Object resp = sets.handle(db, new String[]{"SADD","k","m"}, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", resp);
    }

    @Test
    public void testZaddReturnsOom() {
        Object resp = zsets.handle(db, new String[]{"ZADD","k","1.0","m"}, store);
        assertEquals("-OOM command not allowed when used memory > 'maxmemory'\r\n", resp);
    }
}
