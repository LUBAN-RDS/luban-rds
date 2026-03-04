package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LuaYieldConfigTest {
    private LuaCommandHandler lua;
    private CommonCommandHandler common;
    private MemoryStore store;
    private int db;

    @Before
    public void setUp() {
        lua = new LuaCommandHandler();
        common = new CommonCommandHandler();
        store = new DefaultMemoryStore();
        db = 0;
    }

    @Test
    public void testOpsQuotaAndYield() {
        common.handle(db, new String[]{"CONFIG SET","lua-max-ops-per-script","1"}, store);
        common.handle(db, new String[]{"CONFIG SET","lua-yield-ms","5"}, store);
        long start = System.currentTimeMillis();
        Object resp = lua.handle(db, new String[]{"EVAL","for i=1,5 do redis.call('SET','k',tostring(i)) end return 'ok'","0"}, store);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(resp.toString().startsWith("+OK") || resp.toString().contains("ok"));
        assertTrue(elapsed >= 20);
        common.handle(db, new String[]{"CONFIG SET","lua-max-ops-per-script","1000"}, store);
        common.handle(db, new String[]{"CONFIG SET","lua-yield-ms","1"}, store);
    }
}
