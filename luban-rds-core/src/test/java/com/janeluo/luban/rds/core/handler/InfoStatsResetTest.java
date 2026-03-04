package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InfoStatsResetTest {
    private CommonCommandHandler common;
    private LuaCommandHandler lua;
    private MemoryStore store;
    private int db;

    @Before
    public void setUp() {
        common = new CommonCommandHandler();
        lua = new LuaCommandHandler();
        store = new DefaultMemoryStore();
        db = 0;
    }

    @Test
    public void testResetStatClearsCounters() {
        store.set(db, "a", "1");
        new StringCommandHandler().handle(db, new String[]{"GET","a"}, store);
        new StringCommandHandler().handle(db, new String[]{"GET","missing"}, store);
        lua.handle(db, new String[]{"EVAL","return 'x'","0"}, store);
        common.handle(db, new String[]{"CONFIG RESETSTAT"}, store);
        Object info = common.handle(db, new String[]{"INFO"}, store);
        String s = info.toString();
        assertTrue(s.contains("keyspace_hits:0"));
        assertTrue(s.contains("keyspace_misses:0"));
        assertTrue(s.contains("script_executions:0"));
        assertTrue(s.contains("script_timeouts:0"));
        assertTrue(s.contains("script_kills:0"));
        assertTrue(s.contains("script_avg_time_ms:0"));
        assertTrue(s.contains("script_max_time_ms:0"));
        assertTrue(s.contains("stats_last_reset_time_ms:"));
        assertTrue(s.contains("stats_last_reset_time_iso:"));
    }
}
