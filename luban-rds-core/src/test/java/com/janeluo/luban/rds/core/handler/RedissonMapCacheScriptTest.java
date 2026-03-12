package com.janeluo.luban.rds.core.handler;

import com.janeluo.luban.rds.core.store.DefaultMemoryStore;
import com.janeluo.luban.rds.core.store.MemoryStore;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class RedissonMapCacheScriptTest {

    private LuaCommandHandler luaCommandHandler;
    private MemoryStore memoryStore;
    private int database;

    private static final String REDISSON_GET_SCRIPT = 
            "local s = redis.call('hgetall', KEYS[1]); " +
            "local maxSize = tonumber(redis.call('hget', KEYS[5], 'max-size')); " +
            "local result = {}; " +
            "for i, v in ipairs(s) do " +
            "    if i % 2 == 0 then " +
            "        local t, len, val = struct.unpack('dLc0', v); " +
            "        local key = s[i-1]; " +
            "        local expireDate = 92233720368547758; " +
            "        local expireDateScore = redis.call('zscore', KEYS[2], key); " +
            "        if expireDateScore ~= false then " +
            "            expireDate = tonumber(expireDateScore) " +
            "        end; " +
            "        if t ~= 0 then " +
            "            local expireIdle = redis.call('zscore', KEYS[3], key); " +
            "            if expireIdle ~= false then " +
            "                if tonumber(expireIdle) > tonumber(ARGV[1]) then " +
            "                    redis.call('zadd', KEYS[3], t + tonumber(ARGV[1]), key); " +
            "                    if maxSize ~= nil and maxSize ~= 0 then " +
            "                        local mode = redis.call('hget', KEYS[5], 'mode'); " +
            "                        if mode == false or mode == 'LRU' then " +
            "                            redis.call('zadd', KEYS[4], tonumber(ARGV[1]), key); " +
            "                        else " +
            "                            redis.call('zincrby', KEYS[4], 1, key); " +
            "                        end; " +
            "                    end; " +
            "                end; " +
            "                expireDate = math.min(expireDate, tonumber(expireIdle)) " +
            "            end; " +
            "        end; " +
            "        if expireDate > tonumber(ARGV[1]) then " +
            "            table.insert(result, key); " +
            "        end; " +
            "    end; " +
            "end; " +
            "return result;";

    @Before
    public void setUp() {
        luaCommandHandler = new LuaCommandHandler();
        memoryStore = new DefaultMemoryStore();
        database = 0;
    }

    private Object executeScript(String script, String[] keys, String[] argv) {
        String[] args = new String[3 + keys.length + argv.length];
        args[0] = "EVAL";
        args[1] = script;
        args[2] = String.valueOf(keys.length);
        System.arraycopy(keys, 0, args, 3, keys.length);
        System.arraycopy(argv, 0, args, 3 + keys.length, argv.length);
        return luaCommandHandler.handle(database, args, memoryStore);
    }

    private String structPack(String format, Object... params) {
        StringBuilder script = new StringBuilder("return struct.pack('" + format + "'");
        for (Object param : params) {
            script.append(", ");
            if (param instanceof String) {
                script.append("'").append(param).append("'");
            } else if (param instanceof Number) {
                script.append(param);
            } else {
                script.append(param);
            }
        }
        script.append(")");
        
        Object res = executeScript(script.toString(), new String[]{}, new String[]{});
        String resp = res.toString();
        if (resp.startsWith("$")) {
            int lenEnd = resp.indexOf("\r\n");
            int len = Integer.parseInt(resp.substring(1, lenEnd));
            return resp.substring(lenEnd + 2, lenEnd + 2 + len);
        }
        throw new RuntimeException("Failed to pack data: " + resp);
    }

    private void hset(String key, String field, String value) {
        luaCommandHandler.handle(database, new String[]{"EVAL", 
                "redis.call('HSET', KEYS[1], KEYS[2], ARGV[1])", "2", key, field, value}, memoryStore);
    }

    private void zadd(String key, double score, String member) {
        luaCommandHandler.handle(database, new String[]{"EVAL", 
                "redis.call('ZADD', KEYS[1], ARGV[1], KEYS[2])", "2", key, member, String.valueOf(score)}, memoryStore);
    }

    @Test
    public void testRedissonMapCacheGetScriptWithEmptyHash() {
        String hashKey = "ignew-mysql1:ShiroDbRealm.authorizationCache";
        String timeoutSet = "redisson__timeout__set:{ignew-mysql1:ShiroDbRealm.authorizationCache}";
        String idleSet = "redisson__idle__set:{ignew-mysql1:ShiroDbRealm.authorizationCache}";
        String lastAccessSet = "redisson__map_cache__last_access__set:{ignew-mysql1:ShiroDbRealm.authorizationCache}";
        String optionsKey = "{ignew-mysql1:ShiroDbRealm.authorizationCache}:redisson_options";

        String[] keys = {hashKey, timeoutSet, idleSet, lastAccessSet, optionsKey};
        String[] argv = {"1773287349770"};

        Object result = executeScript(REDISSON_GET_SCRIPT, keys, argv);
        String resp = result.toString();
        assertTrue("Empty hash should return empty array", resp.startsWith("*0\r\n"));
    }

    @Test
    public void testRedissonMapCacheGetScriptWithSingleEntry() {
        String hashKey = "test:cache";
        String timeoutSet = "redisson__timeout__set:{test:cache}";
        String idleSet = "redisson__idle__set:{test:cache}";
        String lastAccessSet = "redisson__map_cache__last_access__set:{test:cache}";
        String optionsKey = "{test:cache}:redisson_options";

        String key = "entry1";
        String value = "value1";
        double timestamp = 1773287349.0;
        String packedValue = structPack("dLc0", timestamp, value.length(), value);

        hset(hashKey, key, packedValue);

        zadd(timeoutSet, 1773399349770.0, key);

        String[] keys = {hashKey, timeoutSet, idleSet, lastAccessSet, optionsKey};
        String[] argv = {"1773287349770"};

        Object result = executeScript(REDISSON_GET_SCRIPT, keys, argv);
        String resp = result.toString();
        assertTrue("Should return array with 1 element", resp.startsWith("*1\r\n"));
        assertTrue("Should contain the key", resp.contains(key));
    }

    @Test
    public void testRedissonMapCacheGetScriptWithExpiredEntry() {
        String hashKey = "test:cache:expired";
        String timeoutSet = "redisson__timeout__set:{test:cache:expired}";
        String idleSet = "redisson__idle__set:{test:cache:expired}";
        String lastAccessSet = "redisson__map_cache__last_access__set:{test:cache:expired}";
        String optionsKey = "{test:cache:expired}:redisson_options";

        String key = "expiredEntry";
        String value = "expiredValue";
        double timestamp = 1000.0;
        String packedValue = structPack("dLc0", timestamp, value.length(), value);

        hset(hashKey, key, packedValue);

        zadd(timeoutSet, 2000.0, key);

        String[] keys = {hashKey, timeoutSet, idleSet, lastAccessSet, optionsKey};
        String[] argv = {"5000"};

        Object result = executeScript(REDISSON_GET_SCRIPT, keys, argv);
        String resp = result.toString();

        assertTrue("Entry with expireDate (2000) <= ARGV (5000) should not be returned", resp.startsWith("*0\r\n"));
    }

    @Test
    public void testRedissonMapCacheGetScriptWithIdleTimeout() {
        String hashKey = "test:cache:idle";
        String timeoutSet = "redisson__timeout__set:{test:cache:idle}";
        String idleSet = "redisson__idle__set:{test:cache:idle}";
        String lastAccessSet = "redisson__map_cache__last_access__set:{test:cache:idle}";
        String optionsKey = "{test:cache:idle}:redisson_options";

        String key = "idleEntry";
        String value = "idleValue";
        double timestamp = 1773287349.0;
        String packedValue = structPack("dLc0", timestamp, value.length(), value);

        hset(hashKey, key, packedValue);

        zadd(timeoutSet, 9999999999999.0, key);

        zadd(idleSet, 1000.0, key);

        hset(optionsKey, "max-size", "100");
        hset(optionsKey, "mode", "LRU");

        String[] keys = {hashKey, timeoutSet, idleSet, lastAccessSet, optionsKey};
        String[] argv = {"5000"};

        Object result = executeScript(REDISSON_GET_SCRIPT, keys, argv);
        String resp = result.toString();

        assertTrue("Entry with expireDate <= ARGV should not be returned", resp.startsWith("*0\r\n"));
    }

    @Test
    public void testRedissonMapCacheGetScriptWithMultipleEntries() {
        String hashKey = "test:cache:multi";
        String timeoutSet = "redisson__timeout__set:{test:cache:multi}";
        String idleSet = "redisson__idle__set:{test:cache:multi}";
        String lastAccessSet = "redisson__map_cache__last_access__set:{test:cache:multi}";
        String optionsKey = "{test:cache:multi}:redisson_options";

        for (int i = 1; i <= 3; i++) {
            String key = "key" + i;
            String value = "value" + i;
            double timestamp = 1773287349.0 + i;
            String packedValue = structPack("dLc0", timestamp, value.length(), value);
            
            hset(hashKey, key, packedValue);
            zadd(timeoutSet, 9999999999999.0, key);
        }

        String[] keys = {hashKey, timeoutSet, idleSet, lastAccessSet, optionsKey};
        String[] argv = {"1773287349770"};

        Object result = executeScript(REDISSON_GET_SCRIPT, keys, argv);
        String resp = result.toString();
        
        assertTrue("Should return array with 3 elements", resp.startsWith("*3\r\n"));
        assertTrue("Should contain key1", resp.contains("key1"));
        assertTrue("Should contain key2", resp.contains("key2"));
        assertTrue("Should contain key3", resp.contains("key3"));
    }

    @Test
    public void testHgetallReturnsCorrectFormat() {
        String hashKey = "test:hgetall:format";
        
        String key1 = "field1";
        String key2 = "field2";
        String packedValue1 = structPack("dLc0", 123.456, 3, "abc");
        String packedValue2 = structPack("dLc0", 789.012, 3, "xyz");
        
        hset(hashKey, key1, packedValue1);
        hset(hashKey, key2, packedValue2);

        String script = "local s = redis.call('hgetall', KEYS[1]); return s";
        Object result = executeScript(script, new String[]{hashKey}, new String[]{});
        String resp = result.toString();

        assertTrue("Should return array with 4 elements (2 keys + 2 values)", resp.startsWith("*4\r\n"));
        assertTrue("Should contain field1", resp.contains(key1));
        assertTrue("Should contain field2", resp.contains(key2));
    }

@Test
    public void testStructUnpackInLoop() {
        String hashKey = "test:struct:loop";
        
        String key = "testKey";
        String originalValue = "testValue123";
        double originalTimestamp = 12345.678;
        
        String packedValue = structPack("dLc0", originalTimestamp, originalValue.length(), originalValue);
        
        hset(hashKey, key, packedValue);
        
        String script = 
            "local s = redis.call('hgetall', KEYS[1]); " +
            "for i, v in ipairs(s) do " +
            "    if i % 2 == 0 then " +
            "        local t, len, val = struct.unpack('dLc0', v); " +
            "        return {t, len, val}; " +
            "    end; " +
            "end; " +
            "return nil;";
        
        Object result = executeScript(script, new String[]{hashKey}, new String[]{});
        String resp = result.toString();

        System.out.println("testStructUnpackInLoop resp: " + resp.replace("\r\n", "\\r\\n"));
        assertTrue("Should return array with 3 elements", resp.startsWith("*3\r\n"));
        assertTrue("Should contain timestamp: " + resp, resp.contains("12345"));
        assertTrue("Should contain original value: " + resp, resp.contains(originalValue));
    }
    
    @Test
    public void testRedissonGetScriptReturnValue() {
        String hashKey = "test:redisson:get";
        
        String key = "admin";
        String originalValue = "mySecretValue123";
        double originalTimestamp = 9999999999999.0;
        
        String packedValue = structPack("dLc0", originalTimestamp, originalValue.length(), originalValue);
        
        hset(hashKey, key, packedValue);
        
        String wrongScript = 
            "local value = redis.call('hget', KEYS[1], ARGV[1]); " +
            "if value == false then return nil; end; " +
            "local t, val = struct.unpack('dLc0', value); " +
            "return val;";
        
        String correctScript = 
            "local value = redis.call('hget', KEYS[1], ARGV[1]); " +
            "if value == false then return nil; end; " +
            "local t, len, val = struct.unpack('dLc0', value); " +
            "return val;";
        
        Object wrongResult = executeScript(wrongScript, new String[]{hashKey}, new String[]{key});
        Object correctResult = executeScript(correctScript, new String[]{hashKey}, new String[]{key});
        
        String wrongResp = wrongResult.toString();
        String correctResp = correctResult.toString();
        
        System.out.println("Wrong script result: " + wrongResp.replace("\r\n", "\\r\\n"));
        System.out.println("Correct script result: " + correctResp.replace("\r\n", "\\r\\n"));
        
        assertTrue("Wrong script returns length as integer: " + wrongResp, wrongResp.startsWith(":" + originalValue.length() + "\r\n"));
        assertTrue("Correct script returns actual value: " + correctResp, correctResp.startsWith("$" + originalValue.length() + "\r\n"));
        assertTrue("Correct script contains value: " + correctResp, correctResp.contains(originalValue));
    }
    
    @Test
    public void testStructUnpackReturnCount() {
        String script = 
            "local packed = struct.pack('dLc0', 12345.678, 10, 'helloWorld'); " +
            "local results = {struct.unpack('dLc0', packed)}; " +
            "return #results;";
        
        Object result = executeScript(script, new String[]{}, new String[]{});
        String resp = result.toString();

        System.out.println("testStructUnpackReturnCount resp: " + resp.replace("\r\n", "\\r\\n"));
        assertEquals("struct.unpack('dLc0', ...) should return 3 values (d, L, c0)", ":3\r\n", resp);
    }
}