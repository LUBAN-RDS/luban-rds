package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LuaBlockedFunctionsTest {
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
        common.handle(db, new String[]{"CONFIG SET","lua-sandbox-enabled","1"}, store);
        common.handle(db, new String[]{"CONFIG SET","lua-allowed-modules","os"}, store);
        common.handle(db, new String[]{"CONFIG SET","lua-blocked-functions","os.execute"}, store);
    }

    @Test
    public void testBlockedFunctionNil() {
        String t1 = lua.handle(db, new String[]{"EVAL","return type(os)","0"}, store).toString();
        assertTrue(t1.contains("$5\r\ntable\r\n"));
        String t2 = lua.handle(db, new String[]{"EVAL","return type(os.execute)","0"}, store).toString();
        assertTrue(t2.contains("$3\r\nnil\r\n"));
    }
}
