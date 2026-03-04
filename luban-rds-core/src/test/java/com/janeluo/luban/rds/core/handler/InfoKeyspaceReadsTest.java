package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InfoKeyspaceReadsTest {
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
    public void testHashAndSetReadsAffectKeyspaceStats() {
        String info1 = common.handle(db, new String[]{"INFO"}, store).toString();
        store.hget(db, "hashKeyMissing", "f");
        store.hset(db, "hashKey", "f", "v");
        store.hget(db, "hashKey", "f");
        store.sismember(db, "setKeyMissing", "m");
        store.sadd(db, "setKey", "m");
        store.sismember(db, "setKey", "m");
        String info2 = common.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info2.contains("keyspace_hits:"));
        assertTrue(info2.contains("keyspace_misses:"));
        assertTrue(!info2.contains("keyspace_hits:0"));
        assertTrue(!info2.contains("keyspace_misses:0"));
    }
}
