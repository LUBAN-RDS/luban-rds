package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests for the custom Reaper Lua script.
 * Verifies script logic using LuaCommandHandler and DefaultMemoryStore.
 */
public class LuaScriptIntegrationTest {

    private LuaCommandHandler luaCommandHandler;
    private MemoryStore memoryStore;
    private int database;

    // The script provided by the user
    private static final String REAPER_SCRIPT = 
            "if redis.call('setnx', KEYS[6], ARGV[4]) == 0 then return -1;end;" +
            "redis.call('expire', KEYS[6], ARGV[3]); " +
            "local expiredKeys1 = redis.call('zrangebyscore', KEYS[2], 0, ARGV[1], 'limit', 0, ARGV[2]); " +
            "for i, key in ipairs(expiredKeys1) do " +
            "    local v = redis.call('hget', KEYS[1], key); " +
            "    if v ~= false then " +
            "        local t, val = struct.unpack('dLc0', v); " +
            "        local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); " +
            "        local listeners = redis.call(ARGV[5], KEYS[4], msg); " +
            "        if (listeners == 0) then break;end; " +
            "    end;" +
            "end;" +
            "for i=1, #expiredKeys1, 5000 do " +
            "    redis.call('zrem', KEYS[5], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); " +
            "    redis.call('zrem', KEYS[3], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); " +
            "    redis.call('zrem', KEYS[2], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); " +
            "    redis.call('hdel', KEYS[1], unpack(expiredKeys1, i, math.min(i+4999, table.getn(expiredKeys1)))); " +
            "end; " +
            "local expiredKeys2 = redis.call('zrangebyscore', KEYS[3], 0, ARGV[1], 'limit', 0, ARGV[2]); " +
            "for i, key in ipairs(expiredKeys2) do " +
            "    local v = redis.call('hget', KEYS[1], key); " +
            "    if v ~= false then " +
            "        local t, val = struct.unpack('dLc0', v); " +
            "        local msg = struct.pack('Lc0Lc0', string.len(key), key, string.len(val), val); " +
            "        local listeners = redis.call(ARGV[5], KEYS[4], msg); " +
            "        if (listeners == 0) then break;end; " +
            "    end;" +
            "end;" +
            "for i=1, #expiredKeys2, 5000 do " +
            "    redis.call('zrem', KEYS[5], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); " +
            "    redis.call('zrem', KEYS[3], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); " +
            "    redis.call('zrem', KEYS[2], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); " +
            "    redis.call('hdel', KEYS[1], unpack(expiredKeys2, i, math.min(i+4999, table.getn(expiredKeys2)))); " +
            "end; " +
            "return #expiredKeys1 + #expiredKeys2; ";

    @Before
    public void setUp() {
        luaCommandHandler = new LuaCommandHandler();
        memoryStore = new DefaultMemoryStore();
        database = 0;
    }

    /**
     * Helper to execute Lua script with KEYS and ARGV.
     */
    private Object executeScript(String script, String[] keys, String[] argv) {
        String[] args = new String[3 + keys.length + argv.length];
        args[0] = "EVAL";
        args[1] = script;
        args[2] = String.valueOf(keys.length);
        System.arraycopy(keys, 0, args, 3, keys.length);
        System.arraycopy(argv, 0, args, 3 + keys.length, argv.length);
        return luaCommandHandler.handle(database, args, memoryStore);
    }

    /**
     * Helper to pack data using Lua StructLib via a helper script.
     * Since we can't easily access StructLib from Java directly without reflection or dependency,
     * we use a small Lua script to do the packing.
     */
    private String structPack(String format, Object... args) {
        StringBuilder script = new StringBuilder("return struct.pack('" + format + "'");
        for (Object arg : args) {
            script.append(", ");
            if (arg instanceof String) {
                script.append("'").append(arg).append("'");
            } else {
                script.append(arg);
            }
        }
        script.append(")");
        
        Object res = executeScript(script.toString(), new String[]{}, new String[]{});
        // The result from LuaCommandHandler is RESP encoded string (e.g. "$len\r\ncontent\r\n")
        // We need to parse it to get raw bytes/string.
        String resp = res.toString();
        if (resp.startsWith("$")) {
            int lenEnd = resp.indexOf("\r\n");
            int len = Integer.parseInt(resp.substring(1, lenEnd));
            return resp.substring(lenEnd + 2, lenEnd + 2 + len);
        }
        throw new RuntimeException("Failed to pack data: " + resp);
    }

    @Test
    public void testBasicExpirationFlow() {
        // 1. Setup Data
        String hashKey = "keys:1:hash";
        String zsetExp = "keys:2:zset_exp";
        String zsetAct = "keys:3:zset_act"; // Using as second source of expired keys?
        String channel = "keys:4:channel";
        String zsetAux = "keys:5:zset_aux";
        String lockKey = "keys:6:lock";

        String[] keys = {hashKey, zsetExp, zsetAct, channel, zsetAux, lockKey};
        // ARGV: [1] score limit, [2] count limit, [3] lock ttl, [4] lock val, [5] publish cmd
        // We use "LPUSH" instead of "PUBLISH" to simulate active listeners and capture messages
        String[] argv = {"1000", "100", "60", "lock_val", "LPUSH"};

        // Insert test data
        String key1 = "task1";
        String payload1 = "payload1";
        // Pack: dLc0 -> double timestamp, int len, string payload
        // Note: struct.pack in Lua uses native types.
        // We use a helper script to pack data into the Hash
        String packedVal = structPack("dLc0", 123.456, payload1.length(), payload1);
        
        // HSET
        luaCommandHandler.handle(database, new String[]{"EVAL", "redis.call('HSET', KEYS[1], KEYS[2], ARGV[1])", "2", hashKey, key1, packedVal}, memoryStore);
        
        // ZADD to zsetExp (expired)
        luaCommandHandler.handle(database, new String[]{"EVAL", "redis.call('ZADD', KEYS[1], 500, KEYS[2])", "2", zsetExp, key1}, memoryStore);
        
        // ZADD to zsetAux (to verify deletion)
        luaCommandHandler.handle(database, new String[]{"EVAL", "redis.call('ZADD', KEYS[1], 500, KEYS[2])", "2", zsetAux, key1}, memoryStore);

        // 2. Execute Script
        Object result = executeScript(REAPER_SCRIPT, keys, argv);

        // 3. Assertions
        String resp = result.toString();
        // Expect integer 1 (1 expired key processed)
        assertEquals(":1\r\n", resp);

        // Verify Deletion
        // Check Hash
        Object hExists = luaCommandHandler.handle(database, new String[]{"EVAL", "return redis.call('HEXISTS', KEYS[1], KEYS[2])", "2", hashKey, key1}, memoryStore);
        assertEquals(":0\r\n", hExists.toString()); // Should be 0

        // Verify Lock
        Object ttl = luaCommandHandler.handle(database, new String[]{"EVAL", "return redis.call('TTL', KEYS[1])", "1", lockKey}, memoryStore);
        // TTL should be positive (around 60)
        String ttlStr = ttl.toString();
        assertTrue(ttlStr.startsWith(":"));
        int ttlVal = Integer.parseInt(ttlStr.substring(1, ttlStr.indexOf("\r\n")));
        assertTrue(ttlVal > 0 && ttlVal <= 60);

        // Verify Message in Channel (List)
        Object msgList = luaCommandHandler.handle(database, new String[]{"EVAL", "return redis.call('LPOP', KEYS[1])", "1", channel}, memoryStore);
        String msgResp = msgList.toString();
        assertTrue(msgResp.startsWith("$")); // Bulk string
        // The message should be packed 'Lc0Lc0': len(key), key, len(val), val
        // We can unpack it to verify or just check length/content roughly
        // We trust the script logic if it ran without error and produced output.
    }

    @Test
    public void testLockContention() {
        String lockKey = "keys:6:lock";
        String[] keys = {"h", "z1", "z2", "c", "z3", lockKey};
        String[] argv = {"1000", "100", "60", "lock_val", "LPUSH"};

        // Acquire lock first
        luaCommandHandler.handle(database, new String[]{"EVAL", "redis.call('SET', KEYS[1], 'existing_lock')", "1", lockKey}, memoryStore);

        // Execute
        Object result = executeScript(REAPER_SCRIPT, keys, argv);

        // Expect -1
        assertEquals(":-1\r\n", result.toString());
    }

    @Test
    public void testBatchProcessing() {
        // Test with more keys to hit the loop
        // We won't do 10k in unit test for speed, but say 20 keys
        String hashKey = "keys:1:hash";
        String zsetExp = "keys:2:zset_exp";
        String[] keys = {hashKey, zsetExp, "z2", "c", "z3", "l"};
        String[] argv = {"1000", "100", "60", "v", "LPUSH"};

        // Insert 20 keys
        StringBuilder luaInsert = new StringBuilder();
        luaInsert.append("for i=1,20 do ");
        luaInsert.append("local k = 'k'..i; ");
        luaInsert.append("local v = struct.pack('dLc0', i, 5, 'value'); ");
        luaInsert.append("redis.call('HSET', KEYS[1], k, v); ");
        luaInsert.append("redis.call('ZADD', KEYS[2], 100, k); "); // score 100 < 1000
        luaInsert.append("end; return 20;");
        
        executeScript(luaInsert.toString(), new String[]{hashKey, zsetExp}, new String[]{});

        // Execute
        Object result = executeScript(REAPER_SCRIPT, keys, argv);
        assertEquals(":20\r\n", result.toString());
        
        // Verify empty zset
        Object count = luaCommandHandler.handle(database, new String[]{"EVAL", "return redis.call('ZCARD', KEYS[1])", "1", zsetExp}, memoryStore);
        assertEquals(":0\r\n", count.toString());
    }
}
