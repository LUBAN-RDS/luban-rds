package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.common.context.InfoProvider;
import com.janeluo.luban.rds.common.context.ServerContext;
import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class CommonCommandHandlerInfoTest {

    private CommonCommandHandler handler;
    private MemoryStore store;
    private StringCommandHandler strings;
    private LuaCommandHandler lua;
    private final int db = 0;

    @Before
    public void setUp() {
        handler = new CommonCommandHandler();
        store = new DefaultMemoryStore();
        strings = new StringCommandHandler();
        lua = new LuaCommandHandler();
    }

    @After
    public void tearDown() {
        ServerContext.setInfoProvider(null);
    }

    @Test
    public void testHandleInfoAll() {
        ServerContext.setInfoProvider(new MockInfoProvider());
        String[] args = {"INFO"};
        Object result = handler.handle(db, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
        assertTrue(response.contains("# Clients"));
        assertTrue(response.contains("connected_clients:10"));
    }

    @Test
    public void testHandleInfoSection() {
        ServerContext.setInfoProvider(new MockInfoProvider());
        String[] args = {"INFO", "server"};
        Object result = handler.handle(db, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
        assertTrue(!response.contains("# Clients"));
    }
    
    @Test
    public void testHandleInfoNoProvider() {
        ServerContext.setInfoProvider(null);
        String[] args = {"INFO"};
        Object result = handler.handle(db, args, store);
        assertTrue(result instanceof String);
        String response = (String) result;
        assertTrue(response.contains("# Server"));
        assertTrue(response.contains("redis_version:1.0.0"));
    }

    @Test
    public void testHashAndSetReadsAffectKeyspaceStats() {
        String info1 = handler.handle(db, new String[]{"INFO"}, store).toString();
        store.hget(db, "hashKeyMissing", "f");
        store.hset(db, "hashKey", "f", "v");
        store.hget(db, "hashKey", "f");
        store.sismember(db, "setKeyMissing", "m");
        store.sadd(db, "setKey", "m");
        store.sismember(db, "setKey", "m");
        String info2 = handler.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info2.contains("keyspace_hits:"));
        assertTrue(info2.contains("keyspace_misses:"));
        assertTrue(!info2.contains("keyspace_hits:0"));
        assertTrue(!info2.contains("keyspace_misses:0"));
    }

    @Test
    public void testResetStatClearsCounters() {
        store.set(db, "a", "1");
        strings.handle(db, new String[]{"GET","a"}, store);
        strings.handle(db, new String[]{"GET","missing"}, store);
        lua.handle(db, new String[]{"EVAL","return 'x'","0"}, store);
        handler.handle(db, new String[]{"CONFIG RESETSTAT"}, store);
        Object info = handler.handle(db, new String[]{"INFO"}, store);
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

    @Test
    public void testUsedMemoryReflectsSetAndFlushdb() {
        String before = handler.handle(db, new String[]{"INFO"}, store).toString();
        long usedBefore = extractLong(before, "used_memory:");
        store.set(db, "k1", "v1");
        store.set(db, "k2", "v2");
        String afterSet = handler.handle(db, new String[]{"INFO"}, store).toString();
        long usedAfter = extractLong(afterSet, "used_memory:");
        assertTrue(usedAfter > usedBefore);
        handler.handle(db, new String[]{"FLUSHDB"}, store);
        String afterFlush = handler.handle(db, new String[]{"INFO"}, store).toString();
        long usedAfterFlush = extractLong(afterFlush, "used_memory:");
        assertTrue(usedAfterFlush <= usedAfter);
    }

    @Test
    public void testDisableMetricsStopsCounting() {
        Object ok = handler.handle(db, new String[]{"CONFIG SET","metrics-enabled","0"}, store);
        assertEquals("+OK\r\n", ok);
        String before = handler.handle(db, new String[]{"INFO"}, store).toString();
        int hitsBefore = extractInt(before, "keyspace_hits:");
        int missesBefore = extractInt(before, "keyspace_misses:");
        strings.handle(db, new String[]{"GET","missingKey"}, store);
        store.set(db, "a", "1");
        strings.handle(db, new String[]{"GET","a"}, store);
        Object info = handler.handle(db, new String[]{"INFO"}, store);
        String s = info.toString();
        assertTrue(s.contains("metrics_enabled:0"));
        int hitsAfter = extractInt(s, "keyspace_hits:");
        int missesAfter = extractInt(s, "keyspace_misses:");
        assertEquals(hitsBefore, hitsAfter);
        assertEquals(missesBefore, missesAfter);
    }

    @Test
    public void testEnableMetricsResumesCounting() {
        handler.handle(db, new String[]{"CONFIG SET","metrics-enabled","0"}, store);
        handler.handle(db, new String[]{"CONFIG SET","metrics-enabled","1"}, store);
        String before = handler.handle(db, new String[]{"INFO"}, store).toString();
        int hitsBefore = extractInt(before, "keyspace_hits:");
        int missesBefore = extractInt(before, "keyspace_misses:");
        strings.handle(db, new String[]{"GET","missingKey2"}, store);
        store.set(db, "b", "1");
        strings.handle(db, new String[]{"GET","b"}, store);
        Object info = handler.handle(db, new String[]{"INFO"}, store);
        String s = info.toString();
        assertTrue(s.contains("metrics_enabled:1"));
        int hitsAfter = extractInt(s, "keyspace_hits:");
        int missesAfter = extractInt(s, "keyspace_misses:");
        assertTrue(hitsAfter > hitsBefore || missesAfter > missesBefore);
    }

    @Test
    public void testInfoShowsLuaOpsAndYield() {
        handler.handle(db, new String[]{"CONFIG SET","lua-max-ops-per-script","123"}, store);
        handler.handle(db, new String[]{"CONFIG SET","lua-yield-ms","7"}, store);
        String info = handler.handle(db, new String[]{"INFO"}, store).toString();
        assertTrue(info.contains("lua_max_ops_per_script:"));
        assertTrue(info.contains("lua_yield_ms:"));
    }

    private long extractLong(String info, String key) {
        int idx = info.indexOf(key);
        if (idx < 0) return 0;
        int end = info.indexOf("\r\n", idx);
        if (end < 0) end = info.length();
        String line = info.substring(idx + key.length(), end).trim();
        try { return Long.parseLong(line); } catch (Exception e) { return 0; }
    }

    private int extractInt(String info, String key) {
        int idx = info.indexOf(key);
        if (idx < 0) return 0;
        int end = info.indexOf("\r\n", idx);
        if (end < 0) end = info.length();
        String line = info.substring(idx + key.length(), end).trim();
        try { return Integer.parseInt(line); } catch (Exception e) { return 0; }
    }

    private static class MockInfoProvider implements InfoProvider {
        @Override
        public Map<String, Object> getInfo(String section) {
            Map<String, Object> info = new HashMap<>();
            if ("server".equalsIgnoreCase(section)) {
                info.put("redis_version", "1.0.0");
            } else if ("clients".equalsIgnoreCase(section)) {
                info.put("connected_clients", 10);
            }
            return info;
        }
    }
}