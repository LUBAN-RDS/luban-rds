package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaxMemoryConfigTest {
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
    public void testSetAndGetMaxMemory() {
        Object ok = common.handle(db, new String[]{"CONFIG SET","maxmemory","1024"}, store);
        assertEquals("+OK\r\n", ok);
        String info = common.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info.contains("maxmemory:1024"));
        assertTrue(info.contains("maxmemory_human:1.00KB"));
        String get = common.handle(db, new String[]{"CONFIG GET","maxmemory"}, store).toString();
        assertTrue(get.contains("$9\r\nmaxmemory\r\n"));
        assertTrue(get.contains("$4\r\n1024\r\n"));
    }

    @Test
    public void testSetPolicyShowsInInfo() {
        Object ok = common.handle(db, new String[]{"CONFIG SET","maxmemory-policy","allkeys-lru"}, store);
        assertEquals("+OK\r\n", ok);
        String info = common.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info.contains("maxmemory_policy:allkeys-lru"));
        String get = common.handle(db, new String[]{"CONFIG GET","maxmemory-policy"}, store).toString();
        assertTrue(get.contains("$16\r\nmaxmemory-policy\r\n"));
        assertTrue(get.contains("allkeys-lru"));
    }

    @Test
    public void testSetSamplesGetReflects() {
        Object ok = common.handle(db, new String[]{"CONFIG SET","maxmemory-samples","7"}, store);
        assertEquals("+OK\r\n", ok);
        String get = common.handle(db, new String[]{"CONFIG GET","maxmemory-samples"}, store).toString();
        assertTrue(get.contains("$17\r\nmaxmemory-samples\r\n"));
        assertTrue(get.contains("$1\r\n7\r\n"));
    }
}
