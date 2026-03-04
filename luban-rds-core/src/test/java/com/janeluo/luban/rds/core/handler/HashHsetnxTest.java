package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.constant.RdsCommandConstant;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class HashHsetnxTest {
    private MemoryStore store;
    private HashCommandHandler handler;
    private final int db = 0;

    @Before
    public void setUp() {
        store = new DefaultMemoryStore();
        handler = new HashCommandHandler();
    }

    @Test
    public void testHsetnxInsert() {
        String[] args = new String[]{RdsCommandConstant.HSETNX, "hk", "f1", "v1"};
        Object resp = handler.handle(db, args, store);
        assertEquals(":1\r\n", resp);
        assertEquals("v1", store.hget(db, "hk", "f1"));
    }

    @Test
    public void testHsetnxNoOverwrite() {
        store.hset(db, "hk", "f1", "v1");
        String[] args = new String[]{RdsCommandConstant.HSETNX, "hk", "f1", "v2"};
        Object resp = handler.handle(db, args, store);
        assertEquals(":0\r\n", resp);
        assertEquals("v1", store.hget(db, "hk", "f1"));
    }
}
