package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LuaAllowedModulesTest {
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
        common.handle(db, new String[]{"CONFIG SET","lua-allowed-modules",""}, store);
    }

    @Test
    public void testAllowSpecificModule() {
        String before = lua.handle(db, new String[]{"EVAL","return type(os)","0"}, store).toString();
        assertTrue(before.contains("$3\r\nnil\r\n"));
        Object ok = common.handle(db, new String[]{"CONFIG SET","lua-allowed-modules","os"}, store);
        assertEquals("+OK\r\n", ok);
        String after = lua.handle(db, new String[]{"EVAL","return type(os)","0"}, store).toString();
        assertTrue(after.contains("$5\r\ntable\r\n"));
        // io remains disabled
        String ioType = lua.handle(db, new String[]{"EVAL","return type(io)","0"}, store).toString();
        assertTrue(ioType.contains("$3\r\nnil\r\n"));
    }
}
