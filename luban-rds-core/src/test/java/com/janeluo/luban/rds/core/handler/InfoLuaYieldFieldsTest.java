package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class InfoLuaYieldFieldsTest {
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
    public void testInfoShowsLuaOpsAndYield() {
        common.handle(db, new String[]{"CONFIG SET","lua-max-ops-per-script","123"}, store);
        common.handle(db, new String[]{"CONFIG SET","lua-yield-ms","7"}, store);
        String info = common.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info.contains("lua_max_ops_per_script:"));
        assertTrue(info.contains("lua_yield_ms:"));
    }
}
